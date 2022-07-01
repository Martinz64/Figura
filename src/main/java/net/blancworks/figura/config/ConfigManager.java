package net.blancworks.figura.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.blancworks.figura.FiguraMod;
import net.blancworks.figura.avatar.AvatarDataManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

public final class ConfigManager {

    //accent color
    public static final UnaryOperator<Style> ACCENT_COLOR = FiguraMod.ACCENT_COLOR;

    //mod name
    public static final String MOD_NAME = "figura";

    //mod config version
    //only change this if you rename old configs
    public static final int CONFIG_VERSION = 1;

    //configs!!
    public enum Config {
        NameTag,

        PREVIEW_NAMEPLATE(true),
        NAMEPLATE_MODIFICATIONS(true),
        CHAT_MODIFICATIONS(true),
        PLAYERLIST_MODIFICATIONS(true),
        BADGES(true),
        BADGE_AS_ICONS(true) {{
            this.tooltip = new TranslatableText("figura.config.badge_as_icons.tooltip",
                    new LiteralText("△").setStyle(Style.EMPTY.withFont(FiguraMod.FIGURA_FONT)));
        }},

        Misc,

        FIGURA_BUTTON_LOCATION(4, 5),
        SCRIPT_LOG_LOCATION(0, 3) {{
            String path = "figura.config.log_location.";
            this.enumList = List.of(
                    new TranslatableText(path + "1"),
                    new TranslatableText(path + "2"),
                    new TranslatableText(path + "3")
            );
        }},
        PLAYER_POPUP_BUTTON(GLFW.GLFW_KEY_R, FiguraMod.PLAYER_POPUP_BUTTON),
        ACCENT_COLOR(0x55FFFF, InputType.HEX_COLOR),
        RELEASE_CHANNEL(0, 3) {
            @Override
            public void runOnChange() {
                FiguraMod.getLatestModVersion();
            }
        },
        CUSTOM_PLAYER_HEADS(true),
        FIX_HANDS(true),
        EASTER_EGGS(true),

        ActionWheel,

        ACTION_WHEEL_BUTTON(GLFW.GLFW_KEY_B, FiguraMod.ACTION_WHEEL_BUTTON),
        ACTION_WHEEL_TITLE_POS(0, 4),
        ACTION_WHEEL_EXECUTE_ON_CLOSE(true),
        //NEW_ACTION_WHEEL(false),

        Dev {{this.name = new TranslatableText("figura.config.dev").formatted(Formatting.RED);}},

        USE_LOCAL_SERVER(false),
        FORMAT_SCRIPT_ON_UPLOAD(true) {
            @Override
            public void runOnChange() {
                if (AvatarDataManager.localPlayer != null && AvatarDataManager.localPlayer.isLocalAvatar) {
                    AvatarDataManager.localPlayer.reloadAvatar();
                }
            }
        },
        LOG_OTHERS_SCRIPT(false),
        RENDER_DEBUG_PARTS_PIVOT(true) {{
            String tooltip = "figura.config.render_debug_parts_pivot.tooltip";
            this.tooltip = new TranslatableText(tooltip,
                    new TranslatableText(tooltip + ".cubes").setStyle(Style.EMPTY.withColor(0xff72b7)),
                    new TranslatableText(tooltip + ".groups").setStyle(Style.EMPTY.withColor(0xaff2ff)));
        }},
        RENDER_OWN_NAMEPLATE(false),
        MODEL_FOLDER_PATH("", InputType.FOLDER_PATH),
        PANIC_BUTTON(GLFW.GLFW_KEY_UNKNOWN, FiguraMod.PANIC_BUTTON),
        PINGS_LOG_LOCATION(3, 4) {{
            String path = "figura.config.log_location.";
            this.enumList = List.of(
                    new TranslatableText(path + "1"),
                    new TranslatableText(path + "2"),
                    new TranslatableText(path + "3"),
                    new TranslatableText(path + "4")
            );
        }},
        BACKEND_PATH("https://figuranew.blancworks.org", InputType.ANY),
        VERIFY_SSL(false),
        BACKEND_AUTH_ADDRESS("figuranew.blancworks.org:25565", InputType.ANY);

        //config data
        public Object value;
        public Object configValue;

        public Text name;
        public Text tooltip;
        public List<Text> enumList;

        public final ConfigType type;
        public final Object defaultValue;

        public final Integer length;
        public KeyBinding keyBind;
        public final InputType inputType;

        //type constructors
        Config() {
            this(ConfigType.CATEGORY, null, null, null, null);
        }
        Config(Object value) {
            this(ConfigType.BOOLEAN, value, null, null, null);
        }
        Config(Object value, Integer length) {
            this(ConfigType.ENUM, value, length, null, null);
        }
        Config(Object value, InputType inputType) {
            this(ConfigType.INPUT, value, null, null, inputType);
        }
        Config(Object value, KeyBinding keyBind) {
            this(ConfigType.KEYBIND, value, null, keyBind, null);
        }

        //global constructor
        Config(ConfigType type, Object value, Integer length, KeyBinding keyBind, InputType inputType) {
            //set values
            this.type = type;
            this.value = value;
            this.defaultValue = value;
            this.configValue = value;
            this.length = length;
            this.keyBind = keyBind;
            this.inputType = inputType;

            //generate names
            String name = MOD_NAME + ".config." + this.name().toLowerCase();
            this.name = new TranslatableText(name);
            this.tooltip = new TranslatableText(name + ".tooltip");

            //generate enum list
            if (length != null) {
                List<Text> enumList = new ArrayList<>();
                for (int i = 1; i <= length; i++)
                    enumList.add(new TranslatableText(name + "." + i));
                this.enumList = enumList;
            }
        }

        public void setValue(String text) {
            try {
                if (value instanceof String)
                    value = text;
                else if (value instanceof Boolean)
                    value = Boolean.valueOf(text);
                else if (value instanceof Integer)
                    value = Integer.valueOf(text);
                else if (value instanceof Float)
                    value = Float.valueOf(text);
                else if (value instanceof Long)
                    value = Long.valueOf(text);
                else if (value instanceof Double)
                    value = Double.valueOf(text);
                else if (value instanceof Byte)
                    value = Byte.valueOf(text);
                else if (value instanceof Short)
                    value = Short.valueOf(text);

                if (length != null)
                    value = ((Integer.parseInt(text) % length) + length) % length;
            } catch (Exception e) {
                value = defaultValue;
            }

            configValue = value;
        }

        public void runOnChange() {}
    }

    public enum ConfigType {
        CATEGORY,
        BOOLEAN,
        ENUM,
        INPUT,
        KEYBIND
    }

    public enum InputType {
        ANY(s -> true),
        INT(s -> s.matches("^[\\-+]?[0-9]*$")),
        FLOAT(s -> s.matches("[\\-+]?[0-9]*(\\.[0-9]+)?") || s.endsWith(".") || s.isEmpty()),
        HEX_COLOR(s -> s.matches("^[#]?[0-9A-Fa-f]{0,6}$")),
        FOLDER_PATH(s -> {
            if (!s.isBlank()) {
                try {
                    return Path.of(s.trim()).toFile().isDirectory();
                } catch (Exception ignored) {
                    return false;
                }
            }

            return true;
        });

        public final Predicate<String> validator;
        InputType(Predicate<String> predicate) {
            this.validator = predicate;
        }
    }

    //old config -> used on migration
    private static final Map<Config, String> V0_CONFIG = new HashMap<>() {{
        put(Config.PREVIEW_NAMEPLATE, "previewNameTag");
        put(Config.FIGURA_BUTTON_LOCATION, "buttonLocation");
        put(Config.USE_LOCAL_SERVER, "useLocalServer");
        put(Config.SCRIPT_LOG_LOCATION, "scriptLog");
        put(Config.PLAYERLIST_MODIFICATIONS, "listMods");
        put(Config.CHAT_MODIFICATIONS, "chatMods");
        put(Config.NAMEPLATE_MODIFICATIONS, "nameTagMods");
        put(Config.BADGE_AS_ICONS, "nameTagIcon");
        put(Config.BADGES, "showBadges");
        put(Config.RENDER_OWN_NAMEPLATE, "ownNameTag");
        put(Config.LOG_OTHERS_SCRIPT, "logOthers");
        put(Config.ACTION_WHEEL_BUTTON, "actionWheel");
        put(Config.FORMAT_SCRIPT_ON_UPLOAD, "formatScript");
        put(Config.ACTION_WHEEL_TITLE_POS, "actionWheelPos");
        put(Config.RENDER_DEBUG_PARTS_PIVOT, "partsHitBox");
    }};

    private static final File FILE = new File(FabricLoader.getInstance().getConfigDir().resolve(MOD_NAME + ".json").toString());
    private static final List<Config> CONFIG_ENTRIES = new ArrayList<>() {{
        for (Config value : Config.values()) {
            if (value.type != ConfigType.CATEGORY)
                this.add(value);
        }
    }};

    public static void initialize() {
        loadConfig();
        saveConfig();
    }

    public static void loadConfig() {
        try {
            if (FILE.exists()) {
                BufferedReader br = new BufferedReader(new FileReader(FILE));
                JsonObject json = JsonParser.parseReader(br).getAsJsonObject();

                JsonElement version = json.get("CONFIG_VERSION");
                if (version == null || version.getAsInt() < CONFIG_VERSION) {
                    update(json, version == null ? 0 : version.getAsInt());
                }
                else {
                    for (Config config : CONFIG_ENTRIES) {
                        JsonElement object = json.get(config.name().toLowerCase());
                        if (object == null)
                            continue;

                        String jsonValue = object.getAsString();
                        config.setValue(jsonValue);
                    }
                }

                br.close();
            }
        } catch (Exception e) {
            FiguraMod.LOGGER.warn("Failed to load config file! Generating a new one...");
            e.printStackTrace();
            setDefaults();
        }
    }

    public static void saveConfig() {
        try {
            JsonObject configJson = new JsonObject();

            for(Config config : CONFIG_ENTRIES) {
                if (config.value instanceof Number)
                    configJson.addProperty(config.name().toLowerCase(), (Number) config.value);
                if (config.value instanceof Character)
                    configJson.addProperty(config.name().toLowerCase(), (Character) config.value);
                else if (config.value instanceof Boolean)
                    configJson.addProperty(config.name().toLowerCase(), (boolean) config.value);
                else
                    configJson.addProperty(config.name().toLowerCase(), String.valueOf(config.value));
            }
            configJson.addProperty("CONFIG_VERSION", CONFIG_VERSION);

            String jsonString = new GsonBuilder().setPrettyPrinting().create().toJson(configJson);

            FileWriter fileWriter = new FileWriter(FILE);
            fileWriter.write(jsonString);
            fileWriter.close();
        } catch (Exception e) {
            FiguraMod.LOGGER.error("Failed to save config file!");
            e.printStackTrace();
        }
    }

    public static void applyConfig() {
        for(Config config : CONFIG_ENTRIES) {
            boolean change = !config.value.equals(config.configValue);
            config.setValue(String.valueOf(config.configValue));
            if (change) config.runOnChange();
        }
    }

    public static void discardConfig() {
        for(Config config : CONFIG_ENTRIES) {
            config.configValue = config.value;
        }
    }

    public static void setDefaults() {
        for(Config config : CONFIG_ENTRIES) {
            config.value = config.defaultValue;
        }
    }

    public static void update(JsonObject json, int version) {
        Map<Config, String> versionMap = null;

        //from V0
        if (version == 0)
            versionMap = V0_CONFIG;

        if (versionMap == null)
            return;

        for (Map.Entry<Config, String> config : versionMap.entrySet()) {
            JsonElement object = json.get(config.getValue());

            if (object == null)
                continue;

            String jsonValue = object.getAsString();
            Config.valueOf(config.getKey().toString()).setValue(jsonValue);
        }
    }

    public static class ConfigKeyBind extends KeyBinding {
        private final Config config;

        public ConfigKeyBind(String translationKey, int code, String category, Config config) {
            super(translationKey, code, category);
            this.config = config;
            config.keyBind = this;
        }

        @Override
        public void setBoundKey(InputUtil.Key boundKey) {
            super.setBoundKey(boundKey);
            config.value = boundKey.getCode();
            saveConfig();
        }
    }

    //returns true if modmenu shifts other buttons on the game menu screen
    public static boolean modmenuButton() {
        if (FabricLoader.getInstance().isModLoaded("modmenu")) {
            String buttonStyle = com.terraformersmc.modmenu.config.ModMenuConfig.MODS_BUTTON_STYLE.getValue().toString();
            return !buttonStyle.equals("SHRINK") && !buttonStyle.equals("ICON");
        }

        return false;
    }
}