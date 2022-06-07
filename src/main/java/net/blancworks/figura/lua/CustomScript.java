package net.blancworks.figura.lua;

import com.google.common.base.Splitter;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import net.blancworks.figura.FiguraMod;
import net.blancworks.figura.assets.FiguraAsset;
import net.blancworks.figura.avatar.AvatarData;
import net.blancworks.figura.avatar.AvatarDataManager;
import net.blancworks.figura.config.ConfigManager.Config;
import net.blancworks.figura.lua.api.RenderLayerAPI;
import net.blancworks.figura.lua.api.actionwheel.ActionWheelCustomization;
import net.blancworks.figura.lua.api.camera.CameraCustomization;
import net.blancworks.figura.lua.api.keybind.FiguraKeybind;
import net.blancworks.figura.lua.api.math.LuaVector;
import net.blancworks.figura.lua.api.model.VanillaModelAPI;
import net.blancworks.figura.lua.api.model.VanillaModelPartCustomization;
import net.blancworks.figura.lua.api.nameplate.NamePlateCustomization;
import net.blancworks.figura.lua.api.sound.FiguraSound;
import net.blancworks.figura.lua.api.sound.FiguraSoundManager;
import net.blancworks.figura.models.shaders.FiguraRenderLayer;
import net.blancworks.figura.models.shaders.FiguraShader;
import net.blancworks.figura.models.shaders.FiguraVertexConsumerProvider;
import net.blancworks.figura.network.NewFiguraNetworkManager;
import net.blancworks.figura.trust.TrustContainer;
import net.blancworks.figura.utils.TextUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.LiteralTextContent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;
import org.luaj.vm2.lib.jse.JseBaseLib;
import org.luaj.vm2.lib.jse.JseMathLib;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CustomScript extends FiguraAsset {

    public AvatarData avatarData;
    public String source;
    public boolean scriptError = false;
    public String scriptName = "main";

    //Global script values
    public Globals scriptGlobals = new Globals();
    //setHook, used for setting instruction count/other debug stuff
    public LuaValue setHook;
    //This is what's called when the instruction cap is hit.
    public LuaValue instructionCapFunction;


    //The currently running task.
    //Updated as things are added to it.
    public CompletableFuture<Void> currTask;

    //How many instructions the last events used.
    public int initInstructionCount = 0;
    public int tickInstructionCount = 0;
    public int renderInstructionCount = 0;
    public int worldRenderInstructionCount = 0;

    public static int pingSent = 0;
    public static int pingReceived = 0;

    //References to the tick and render functions for easy use elsewhere.
    private LuaEvent tickLuaEvent = null;
    private LuaEvent renderLuaEvent = null;

    private CompletableFuture<Void> lastTickFunction = null;

    public Map<String, LuaEvent> allEvents = new HashMap<>();

    //Vanilla model part customizations made via this script
    public Map<String, VanillaModelPartCustomization> allCustomizations = new HashMap<>();

    //Nameplate customizations
    public Map<String, NamePlateCustomization> nameplateCustomizations = new HashMap<>();

    //Camera customizations
    public Map<String, CameraCustomization> cameraCustomizations = new HashMap<>();

    //Action Wheel customizations
    public Map<String, ActionWheelCustomization> actionWheelCustomizations = new HashMap<>();
    public Map<String, ActionWheelCustomization> newActionWheelSlots = new HashMap<>();
    public int actionWheelLeftSize = 4;
    public int actionWheelRightSize = 4;

    //scripting custom keybindings
    public ArrayList<FiguraKeybind> keyBindings = new ArrayList<>();

    //Keep track of these because we want to apply data to them later.
    public ArrayList<VanillaModelAPI.ModelPartTable> vanillaModelPartTables = new ArrayList<>();

    public float particleSpawnCount = 0f;
    public float soundSpawnCount = 0f;

    public Float customShadowSize = null;
    public Boolean shouldRenderFire = null;

    public Vec2f crossHairPos = null;
    public boolean crossHairEnabled = true;

    public boolean unlockCursor = false;
    public static double mouseScroll = 0d;

    // If the player should render the entity their riding
    public boolean renderMount = true;
    public boolean renderMountShadow = true;

    public boolean renderPlayerHead = true;

    public boolean hasPlayer = false;

    public DamageSource lastDamageSource;

    public String commandPrefix = "\u0000";

    public final LuaTable sharedValues = new LuaTable();
    public boolean canBeTracked = true;

    public HashMap<String, FiguraSound> customSounds = new HashMap<>();

    public static final UnaryOperator<Style> LUA_COLOR = (s) -> s.withColor(0x5555FF);
    public static final UnaryOperator<Style> PING_COLOR = (s) -> s.withColor(0xDD1133);
    public static final Text LOG_PREFIX = Text.empty().formatted(Formatting.ITALIC).append(MutableText.of(new LiteralTextContent("[lua] ")).styled(LUA_COLOR));
    public static final Text PING_PREFIX = Text.empty().formatted(Formatting.ITALIC).append(MutableText.of(new LiteralTextContent("[ping] ")).styled(PING_COLOR));

    //Custom Rendering
    public static final int maxShaders = 16;
    public Map<String, FiguraShader> shaders = new HashMap<>();
    public FiguraVertexConsumerProvider customVCP = null;

    //----PINGS!----

    //Maps functions from lua to shorts for data saving.
    @Deprecated
    public BiMap<Short, String> oldFunctionIDMap = HashBiMap.create();
    public BiMap<Short, LuaTable> functionMap = HashBiMap.create();

    private short lastPingID = Short.MIN_VALUE;

    public Queue<LuaPing> incomingPingQueue = new LinkedList<>();

    public Queue<LuaPing> outgoingPingQueue = new LinkedList<>();

    public CustomScript() {
        source = "";
    }

    public CustomScript(AvatarData data, String content) {
        load(data, content);
    }

    //--Setup--
    //Loads the script using the targeted playerData and source code.
    public void load(AvatarData data, String src) {
        //Set the player data so we have something to target.
        avatarData = data;

        //Loads the source into this string variable for later use.
        source = src;

        //get the script name
        if (data == AvatarDataManager.localPlayer && (AvatarDataManager.localPlayer != null && AvatarDataManager.localPlayer.loadedName != null))
            scriptName = AvatarDataManager.localPlayer.loadedName;

        //Load up the default libraries we want to include.
        scriptGlobals.load(new JseBaseLib());
        scriptGlobals.load(new PackageLib());
        scriptGlobals.load(new Bit32Lib());
        scriptGlobals.load(new TableLib());
        scriptGlobals.load(new StringLib());
        scriptGlobals.load(new JseMathLib());

        //Set up debug in this environment, but never allow any users to access it.
        scriptGlobals.load(new DebugLib());
        //Yoink sethook from debug so we can use it later.
        setHook = scriptGlobals.get("debug").get("sethook");

        //Yeet debug library so nobody can access it.
        scriptGlobals.set("debug", LuaValue.NIL);
        scriptGlobals.set("dofile", LuaValue.NIL);
        scriptGlobals.set("loadfile", LuaValue.NIL);
        scriptGlobals.set("require", LuaValue.NIL);

        //math funnies :3
        scriptGlobals.get("math").set("lerp", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue arg1, LuaValue arg2, LuaValue arg3) {
                return arg1.add(arg2.sub(arg1).mul(arg3));
            }
        });
        scriptGlobals.get("math").set("clamp", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue arg1, LuaValue arg2, LuaValue arg3) {
                return LuaNumber.valueOf(MathHelper.clamp(arg1.checknumber().tofloat(), arg2.checknumber().tofloat(), arg3.checknumber().tofloat()));
            }
        });

        //Sets up the global values for the API and such in the script.
        setupGlobals();
        //Sets up events!
        setupEvents();

        //then yeet the package library
        scriptGlobals.set("package", LuaValue.NIL);

        scriptGlobals.set("loadstring", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                try {
                    return FiguraLuaManager.modGlobals.load(arg.checkjstring(), scriptName, scriptGlobals);
                } catch (LuaError e) {
                    return e.getMessageObject();
                }
            }
        });

        try {
            //Load the script source, name defaults to "main" for scripts for other players.
            LuaValue chunk = FiguraLuaManager.modGlobals.load(source, scriptName, scriptGlobals);

            instructionCapFunction = new ZeroArgFunction() {
                public LuaValue call() {
                    // A simple lua error may be caught by the script, but a
                    // Java Error will pass through to top and stop the script.
                    scriptError = true;
                    String error = "Script overran resource limits";

                    boolean logOthers = (boolean) Config.LOG_OTHERS_SCRIPT.value;
                    if (data != null && (data == AvatarDataManager.localPlayer || logOthers)) {
                        //prefix
                        MutableText message = LOG_PREFIX.copy();

                        //name
                        if (logOthers) message.append(data.name.copy().formatted(Formatting.DARK_RED, Formatting.BOLD)).append(" ");

                        //error
                        message.append(Text.literal(">> ").styled(LUA_COLOR)).append(error).formatted(Formatting.RED);

                        sendChatMessage(message);
                    }

                    throw new RuntimeException(error);
                }
            };

            //Queue up a new task.
            currTask = CompletableFuture.runAsync(
                    () -> {
                        initInstructionCount = 0;
                        setInstructionLimitPermission(TrustContainer.Trust.INIT_INST, 0);
                        try {
                            if (data != null) data.lastEntity = null;
                            chunk.call();
                        } catch (Exception error) {
                            handleError(error);
                        }
                        initInstructionCount += scriptGlobals.running.state.bytecodes;

                        isDone = true;
                        currTask = null;
                        FiguraMod.LOGGER.info("Script Loading Finished");
                    }
            );
        } catch (Exception error) {
            isDone = true;
            handleError(error);
        }
    }

    public void toNBT(NbtCompound tag) {
        String script = cleanScriptSource(source);

        if (script.length() < 60000) {
            tag.putString("src", script);
        } else {
            int i = 0;
            for (String substring : Splitter.fixedLength(60000).split(script)) {
                tag.putString("src_" + i, substring);
                i++;
            }
        }
    }

    public void fromNBT(AvatarData data, NbtCompound tag) {
        Set<String> keys = tag.getKeys();
        if (keys.size() <= 1) {
            source = tag.getString("src");
        } else {
            StringBuilder script = new StringBuilder();
            for (int i = 0; i < keys.size(); i++) {
                script.append(tag.getString("src_" + i));
            }
            source = script.toString();
        }

        load(data, source);
    }

    public void setPlayerEntity() {
        if (!isDone)
            return;

        if (!hasPlayer) {
            hasPlayer = true;
            queueTask(() -> {
                setInstructionLimitPermission(TrustContainer.Trust.INIT_INST, initInstructionCount);
                try {
                    allEvents.get("player_init").call();
                } catch (Exception error) {
                    handleError(error);
                }
                initInstructionCount += scriptGlobals.running.state.bytecodes;
            });
        }
    }

    public void onFiguraChatCommand(String message) {
        if (!isDone || scriptError || !hasPlayer || avatarData.lastEntity == null)
            return;

        queueTask(() -> {
            setInstructionLimitPermission(TrustContainer.Trust.TICK_INST, tickInstructionCount);
            try {
                allEvents.get("onCommand").call(LuaString.valueOf(message));
            } catch (Exception error) {
                handleError(error);
            }
            tickInstructionCount += scriptGlobals.running.state.bytecodes;
        });
    }

    public void runActionWheelFunction(LuaFunction function, LuaValue arg) {
        if (!isDone || scriptError || !hasPlayer || avatarData.lastEntity == null)
            return;

        queueTask(() -> {
            setInstructionLimitPermission(TrustContainer.Trust.TICK_INST, tickInstructionCount);
            try {
                function.call(arg == null ? LuaValue.NIL : arg);
            } catch (Exception error) {
                handleError(error);
            }
            tickInstructionCount += scriptGlobals.running.state.bytecodes;
        });
    }

    public void onWorldRender(float deltaTime) {
        if (!isDone || scriptError || !hasPlayer || avatarData.lastEntity == null)
            return;

        queueTask(() -> {
            renderInstructionCount -= worldRenderInstructionCount;
            worldRenderInstructionCount = 0;
            setInstructionLimitPermission(TrustContainer.Trust.RENDER_INST, renderInstructionCount);
            try {
                allEvents.get("world_render").call(LuaNumber.valueOf(deltaTime));
            } catch (Exception error) {
                handleError(error);
            }
            worldRenderInstructionCount = scriptGlobals.running.state.bytecodes;
            renderInstructionCount += worldRenderInstructionCount;
        });
    }

    public void onDamage(float amount, DamageSource source) {
        if (!isDone || scriptError || !hasPlayer || avatarData.lastEntity == null)
            return;

        queueTask(() -> {
            setInstructionLimitPermission(TrustContainer.Trust.TICK_INST, tickInstructionCount);
            try {
                allEvents.get("onDamage").call(LuaNumber.valueOf(amount), LuaString.valueOf(source.name));
            } catch (Exception error) {
                handleError(error);
            }
            tickInstructionCount += scriptGlobals.running.state.bytecodes;
        });
    }

    //Sets up and creates all the LuaEvents for this script
    public void setupEvents() {
        //Foreach event
        for (Map.Entry<String, Function<String, LuaEvent>> entry : FiguraLuaManager.registeredEvents.entrySet()) {
            //Add a new event created from the name here
            allEvents.put(entry.getKey(), entry.getValue().apply(entry.getKey()));
        }

        tickLuaEvent = allEvents.get("tick");
        renderLuaEvent = allEvents.get("render");
    }

    //Sets up global variables
    public void setupGlobals() {
        //Log! Only for local player.
        scriptGlobals.set("log", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue arg, LuaValue arg2) {
                try {
                    if (avatarData == AvatarDataManager.localPlayer || (boolean) Config.LOG_OTHERS_SCRIPT.value) {
                        MutableText message = LOG_PREFIX.copy();
                        if ((boolean) Config.LOG_OTHERS_SCRIPT.value) message.append(avatarData.name.copy()).append(" ");
                        message.append(Text.literal(">> ").styled(LUA_COLOR));

                        Text log;
                        if (arg instanceof LuaVector logText)
                            log = logText.toJsonText(LUA_COLOR, FiguraMod.ACCENT_COLOR);
                        else if (arg2.isnil() || !arg2.checkboolean())
                            log = Text.literal(arg.toString());
                        else
                            log = TextUtils.tryParseJson(arg.toString());

                        message.append(log);

                        int config = (int) Config.SCRIPT_LOG_LOCATION.value;
                        if (config != 2) {
                            FiguraMod.LOGGER.info(message.getString());
                        }
                        if (config != 1) {
                            sendChatMessage(message);
                        }
                    }
                } catch (Exception error) {
                    if (error instanceof LuaError e) throw e;
                    else handleError(error);
                }

                return NIL;
            }
        });

        //Re-map print to log.
        scriptGlobals.set("print", scriptGlobals.get("log"));

        scriptGlobals.set("logTable", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue arg1, LuaValue arg2) {
                try {
                    LuaTable table = arg1.checktable();
                    boolean deep = arg2.isnil() || arg2.checkboolean();

                    if (avatarData == AvatarDataManager.localPlayer || (boolean) Config.LOG_OTHERS_SCRIPT.value) {
                        MutableText message = LOG_PREFIX.copy();
                        if ((boolean) Config.LOG_OTHERS_SCRIPT.value) message.append(avatarData.name.copy()).append(" ");
                        message.append(Text.literal(">> ").styled(LUA_COLOR));
                        message.append(tableToText(table, deep, LUA_COLOR, FiguraMod.ACCENT_COLOR, 1, ""));

                        int config = (int) Config.SCRIPT_LOG_LOCATION.value;
                        if (config != 2) FiguraMod.LOGGER.info(message.getString());
                        if (config != 1) sendChatMessage(message);
                    }
                } catch (Exception error) {
                    if (error instanceof LuaError e) throw e;
                    else handleError(error);
                }

                return NIL;
            }
        });

        scriptGlobals.set("logTableContent", scriptGlobals.get("logTable"));

        //store a value to be read from others scripts
        scriptGlobals.set("storeValue", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue arg1, LuaValue arg2) {
                String key = arg1.checkjstring();
                sharedValues.set(key, arg2);
                return NIL;
            }
        });

        LuaTable globalMetaTable = new LuaTable();

        //When creating a new variable.
        globalMetaTable.set("__newindex", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue table, LuaValue key, LuaValue value) {
                if (table != scriptGlobals) {
                    scriptError = true;
                    error("Can't use global table metatable on other tables!");
                }

                if (value.isfunction() && key.isstring()) {
                    String funcName = key.checkjstring();
                    LuaFunction func = value.checkfunction();

                    LuaEvent possibleEvent = allEvents.get(funcName);

                    if (possibleEvent != null) {
                        possibleEvent.subscribe(func);
                        return NIL;
                    }
                }
                table.rawset(key, value);

                return NIL;
            }
        });

        scriptGlobals.setmetatable(globalMetaTable);

        FiguraLuaManager.setupScriptAPI(this);
    }

    //--Instruction Limits--

    //Sets the instruction limit of the next function we'll call, and resets the bytecode count to 0
    //Uses the permission at permissionID to set it.
    public void setInstructionLimitPermission(TrustContainer.Trust permissionID, int subtract) {
        int count = avatarData.getTrustContainer().getTrust(permissionID) - subtract;
        setInstructionLimit(count);
    }

    //Sets the instruction limit of the next function we'll call, and resets the bytecode count to 0.
    public void setInstructionLimit(int count) {
        scriptGlobals.running.state.bytecodes = 0;
        setHook.invoke(LuaValue.varargsOf(new LuaValue[]{
                instructionCapFunction, LuaValue.EMPTYSTRING, LuaValue.valueOf(Math.max(count, 1))
        }));
    }

    //--Events--

    //Called whenever the global tick event happens
    public void tick() {
        if (avatarData != null) {
            float particles = avatarData.getTrustContainer().getTrust(TrustContainer.Trust.PARTICLES);
            float sounds = avatarData.getTrustContainer().getTrust(TrustContainer.Trust.SOUNDS);
            particleSpawnCount = MathHelper.clamp(particleSpawnCount + (1 / 20f * particles), 0, particles);
            soundSpawnCount = MathHelper.clamp(soundSpawnCount + (1 / 20f * sounds), 0, sounds);
        }

        //If the tick function exists, call it.
        if (tickLuaEvent != null) {
            if (lastTickFunction != null && !lastTickFunction.isDone())
                return;
            lastTickFunction = queueTask(this::onTick);
        }
    }

    //Called whenever the game renders a new frame with this avatar in view
    public void render(float deltaTime) {
        //Don't render if the script is doing something else still
        //Prevents threading memory errors and also ensures that "long" ticks and events and such are penalized.
        if (renderLuaEvent == null || scriptError || currTask == null || !currTask.isDone() || !isDone || !hasPlayer || avatarData.lastEntity == null)
            return;

        onRender(deltaTime);
    }

    public void onTick() {
        if (!isDone || tickLuaEvent == null || scriptError || !hasPlayer || avatarData.lastEntity == null)
            return;

        tickInstructionCount = 0;
        setInstructionLimitPermission(TrustContainer.Trust.TICK_INST, 0);
        try {
            tickLuaEvent.call();

            //Process all pings.
            if (FiguraMod.ticksElapsed % 20 == 0) {
                pingSent = 0;
                pingReceived = 0;
            }

            while (incomingPingQueue.size() > 0) {
                pingReceived++;
                LuaPing p = incomingPingQueue.poll();
                p.function.call(p.args);

                //log pings
                int config = (int) Config.PINGS_LOG_LOCATION.value;
                if (config != 3) logPing(p, config, avatarData.name.copy());
            }

            //Batch-send pings.
            if (outgoingPingQueue.size() > 0) {
                pingSent += outgoingPingQueue.size();
                ((NewFiguraNetworkManager) FiguraMod.networkManager).sendPing(outgoingPingQueue);
            }
        } catch (Exception error) {
            handleError(error);
            error.printStackTrace();
        }
        tickInstructionCount += scriptGlobals.running.state.bytecodes;
    }

    public void onRender(float deltaTime) {
        renderInstructionCount = worldRenderInstructionCount;
        setInstructionLimitPermission(TrustContainer.Trust.RENDER_INST, 0);
        try {
            renderLuaEvent.call(LuaNumber.valueOf(deltaTime));
        } catch (Exception error) {
            handleError(error);
        }
        renderInstructionCount += scriptGlobals.running.state.bytecodes;
    }

    public void handleError(Exception error) {
        scriptError = true;

        tickLuaEvent = null;
        renderLuaEvent = null;
        if (error instanceof LuaError err)
            logLuaError(err);
        else
            error.printStackTrace();
    }

    //--Tasks--

    public CompletableFuture<Void> queueTask(Runnable task) {
        synchronized (this) {
            if (currTask == null || currTask.isDone()) {
                currTask = CompletableFuture.runAsync(task);
            } else {
                currTask = currTask.thenRun(task);
            }

            return currTask;
        }
    }

    public String cleanScriptSource(String s) {
        if (!(boolean) Config.FORMAT_SCRIPT_ON_UPLOAD.value)
            return s;

        StringBuilder ret = new StringBuilder();

        boolean inString = false;
        boolean inChar = false;
        boolean inBlockString = false;
        boolean inComment = s.startsWith("#");
        boolean inBlock = false;
        int blockDepth = 0;

        StringBuilder queue = new StringBuilder();

        for (int i = 0; i < s.length(); i++) {
            char curr = s.charAt(i);

            if (!inString && !inChar && !inBlockString && !inComment && !inBlock) {
                //check for string
                if (curr == '"') {
                    inString = true;
                    queue.append(curr);
                }
                //check for char (lua allows strings surrounded with '')
                else if (curr == '\'') {
                    inChar = true;
                    queue.append(curr);
                }
                //check for block strings
                else if (curr == '[' && i < s.length() - 1) {
                    for (int j = i + 1; j < s.length(); j++) {
                        if (s.charAt(j) == '=')
                            blockDepth++;
                        else if (s.charAt(j) == '[') {
                            inBlockString = true;
                            queue.append(curr);
                            break;
                        }
                        else {
                            queue.append(curr);
                            break;
                        }
                    }
                }
                //check single line comments
                else if (curr == '-' && i < s.length() - 1 && s.charAt(i + 1) == '-') {
                    inComment = true;
                    i++;

                    //check for comment block
                    if (i < s.length() - 2 && s.charAt(i + 1) == '[') {
                        for (int j = i + 2; j < s.length(); j++) {
                            if (s.charAt(j) == '=')
                                blockDepth++;
                            else if (s.charAt(j) == '[') {
                                inBlock = true;
                                i += 2;
                                break;
                            }
                            else break;
                        }
                    }
                }
                else {
                    queue.append(curr);
                }

                //dont continue on the last iteration
                if (i < s.length() - 1)
                    continue;
            }

            //format then append queue
            ret.append(queue.toString().replaceAll("\\s+", " "));
            queue = new StringBuilder();

            //add string contents
            if (inString) {
                //check for end of string and append
                inString = !(curr == '"' && s.charAt(i - 1) != '\\');
                ret.append(curr);
            }
            //add char contents
            else if (inChar) {
                //check for end of char and append
                inChar = !(curr == '\'' && s.charAt(i - 1) != '\\');
                ret.append(curr);
            }
            //add block string contents
            else if (inBlockString) {
                //check for end of block
                String append = "=".repeat(blockDepth) + "]";
                inBlockString = !(curr == ']' && s.startsWith(append, i + 1));

                //block string ended, so reset the depth counter
                if (!inBlockString) {
                    blockDepth = 0;
                    i += append.length();
                    ret.append(curr);
                    ret.append(append);
                } else {
                    //append text
                    ret.append(curr);
                }
            }
            //skip block comments
            else if (inBlock) {
                //check for end of block
                String append = "=".repeat(blockDepth) + "]";
                inBlock = !(curr == ']' && s.startsWith(append, i + 1));

                //if block ended
                if (!inBlock) {
                    queue.append(" ");
                    inComment = false;
                    blockDepth = 0;
                    i += append.length();
                }
            }
            //skip comments
            else if (curr == '\n') {
                queue.append(" ");
                inComment = false;
            }
        }

        return ret.toString();
    }

    //--Debugging--

    public void logLuaError(LuaError error) {
        boolean logOthers = (boolean) Config.LOG_OTHERS_SCRIPT.value;

        //log only local player if not everyone
        if (avatarData == null || (avatarData != AvatarDataManager.localPlayer && !logOthers))
            return;

        //error string
        String msg = error.getMessage();
        msg = msg.replace("\t", "   ");
        String[] messageParts = msg.split("\n");

        //prefix
        MutableText message = LOG_PREFIX.copy();

        //name
        if (logOthers) message.append(avatarData.name.copy()).append(" ");

        //header
        message.append(Text.literal(">> ").styled(LUA_COLOR));
        sendChatMessage(message);

        //non-local warning
        if (avatarData == AvatarDataManager.localPlayer && !avatarData.isLocalAvatar)
            sendChatMessage(Text.literal("non-local avatar script!\n").formatted(Formatting.RED, Formatting.UNDERLINE));

        //error
        for (String part : messageParts) {
            if (!part.trim().equals("[Java]: in ?"))
                sendChatMessage(Text.literal(part).formatted(Formatting.RED));
        }

        //script path
        String location = "?";
        try {
            //split the line at the first :
            String[] line = msg.split(Pattern.quote(scriptName), 2);
            if (line.length < 2) return;

            //use regex to get the first number group
            Pattern pattern = Pattern.compile("([0-9]+)");
            Matcher matcher = pattern.matcher(line[1]);

            //try to parse the line number as int
            if (matcher.find()) {
                int lineNumber = Integer.parseInt(matcher.group(1));

                //set the line text
                if (lineNumber > 0) {
                    String src = source.split("\n")[lineNumber - 1].trim();
                    String ext = "";
                    if (src.length() > 100) {
                        src = src.substring(0, 100);
                        ext = " [...]";
                    }
                    location = "'" + src + "'" + ext;
                }
            }
        } catch (Exception ignored) {}

        sendChatMessage(Text.literal("script:").formatted(Formatting.RED));
        sendChatMessage(Text.literal("   " + location).formatted(Formatting.RED));

        error.printStackTrace();
    }

    public static MutableText tableToText(LuaTable table, boolean deep, UnaryOperator<Style> keyColor, UnaryOperator<Style> valColor, int depth, String depthString) {
        String spacing = "  ";
        depthString = spacing.substring(2) + depthString;
        MutableText back = Text.literal("{\n").formatted(Formatting.ITALIC);

        for (LuaValue key : table.keys()) {
            LuaValue value = table.get(key);

            MutableText valString = Text.empty();
            valString.append(Text.literal(depthString + spacing + "\"").setStyle(Style.EMPTY.withItalic(false)));
            valString.append(Text.literal(key.toString()).styled(keyColor)).append("\" : ");

            if (value.istable() && deep) {
                valString.append(tableToText(value.checktable(), true, keyColor, valColor, depth + 1, spacing + depthString));
            } else {
                valString.append(Text.literal(value.toString()).styled(valColor)).append(",\n");
            }

            back.append(valString);
        }

        back.append(Text.literal(depthString + "},\n"));
        return back;
    }

    public static void logPing(LuaPing p, int config, Text pingOwner) {
        Text name = Text.literal(p.name);
        MutableText arg;

        if (p.args instanceof LuaVector vec)
            arg = (MutableText) vec.toJsonText(PING_COLOR, FiguraMod.ACCENT_COLOR);
        else if (p.args instanceof LuaTable tbl)
            arg = tableToText(tbl, true, PING_COLOR, FiguraMod.ACCENT_COLOR, 1, "");
        else
            arg = Text.literal(p.args.toString()).styled(FiguraMod.ACCENT_COLOR);

        MutableText message = PING_PREFIX.copy();
        message.append(pingOwner).append(" ");
        message.append(Text.literal(">> ").styled(PING_COLOR));

        if (p.size != null)
            message.append(Text.literal("(" + p.size + "b) ").styled(PING_COLOR));

        message.append(name).append(" : ").append(arg);

        if (config != 2) FiguraMod.LOGGER.info(message.getString());
        if (config != 1) sendChatMessage(message);
    }

    public static void sendChatMessage(Text text) {
        MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(text);
    }

    //--Vanilla Modifications--

    public VanillaModelPartCustomization getOrMakePartCustomization(String accessor) {
        VanillaModelPartCustomization currCustomization = getPartCustomization(accessor);

        if (currCustomization == null) {
            currCustomization = new VanillaModelPartCustomization();
            allCustomizations.put(accessor, currCustomization);
        }
        return currCustomization;
    }

    public VanillaModelPartCustomization getPartCustomization(String accessor) {
        return allCustomizations.get(accessor);
    }

    //--Nameplate Modifications--

    public NamePlateCustomization getOrMakeNameplateCustomization(String accessor) {
        NamePlateCustomization currCustomization = getNameplateCustomization(accessor);

        if (currCustomization == null) {
            currCustomization = new NamePlateCustomization();
            nameplateCustomizations.put(accessor, currCustomization);
        }
        return currCustomization;
    }

    public NamePlateCustomization getNameplateCustomization(String accessor) {
        return nameplateCustomizations.get(accessor);
    }

    //--Camera Modifications--

    public CameraCustomization getOrMakeCameraCustomization(String accessor) {
        CameraCustomization currCustomization = getCameraCustomization(accessor);

        if (currCustomization == null) {
            currCustomization = new CameraCustomization();
            cameraCustomizations.put(accessor, currCustomization);
        }
        return currCustomization;
    }

    public CameraCustomization getCameraCustomization(String accessor) {
        return cameraCustomizations.get(accessor);
    }

    //--ActionWheel Modifications--

    public ActionWheelCustomization getOrMakeActionWheelCustomization(String accessor) {
        ActionWheelCustomization currCustomization = getActionWheelCustomization(accessor);

        if (currCustomization == null) {
            currCustomization = new ActionWheelCustomization();
            actionWheelCustomizations.put(accessor, currCustomization);
        }
        return currCustomization;
    }

    public ActionWheelCustomization getActionWheelCustomization(String accessor) {
        return actionWheelCustomizations.get(accessor);
    }

    //--Pings--
    @Deprecated
    public void registerPingName(String s) {
        oldFunctionIDMap.put(lastPingID++, s);
    }

    public void registerPing(LuaTable func) {
        if (!functionMap.containsValue(func))
            functionMap.put(lastPingID++, func);
    }

    public LuaPing handlePing(short id, LuaValue args, Integer size) {
        try {
            String functionName = oldFunctionIDMap.get(id);
            LuaTable function = functionMap.get(id);

            if (function != null) {
                return addPing(function.get("value"), args, id, function.get("key").tojstring(), size);
            } else if (functionName != null) {
                LuaValue func = scriptGlobals.get(functionName);
                return addPing(func, args, id, functionName, size);
            }
        } catch (Exception error) {
            if (error instanceof LuaError err)
                logLuaError(err);
            else
                error.printStackTrace();
        }

        return null;
    }

    public LuaPing addPing(LuaValue function, LuaValue args, short id, String name, Integer size) {
        LuaPing p = new LuaPing(id, function, args, name, size);
        incomingPingQueue.add(p);
        return p;
    }

    public record LuaPing(short functionID, LuaValue function, LuaValue args, String name, Integer size) {}

    //--Misc--
    public void clearSounds() {
        if (avatarData != null) {
            FiguraSoundManager.getChannel().stopSound(avatarData.entityId);
        }

        customSounds.values().forEach(FiguraSound::close);
        //customSounds.clear();
    }

    public FiguraRenderLayer getCustomLayer(LuaValue arg) {
        if (!arg.isnil() && !RenderLayerAPI.areIrisShadersEnabled()) {
            if (customVCP != null) {
                FiguraRenderLayer customLayer = customVCP.getLayer(arg.checkjstring());
                if (customLayer == null) throw new LuaError("No custom layer named: " + arg.checkjstring());

                return customLayer;
            } else {
                throw new LuaError("The player has no custom VCP!");
            }
        }

        return null;
    }
}
