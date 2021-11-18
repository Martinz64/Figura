package net.blancworks.figura.lua.api.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.brigadier.StringReader;
import net.blancworks.figura.PlayerData;
import net.blancworks.figura.PlayerDataManager;
import net.blancworks.figura.lua.CustomScript;
import net.blancworks.figura.lua.api.ReadOnlyLuaTable;
import net.blancworks.figura.lua.api.block.BlockStateAPI;
import net.blancworks.figura.lua.api.item.ItemStackAPI;
import net.blancworks.figura.lua.api.math.LuaVector;
import net.blancworks.figura.lua.api.model.CustomModelAPI;
import net.blancworks.figura.models.CustomModelPart;
import net.blancworks.figura.models.shaders.FiguraRenderLayer;
import net.blancworks.figura.models.shaders.FiguraShader;
import net.blancworks.figura.models.tasks.BlockRenderTask;
import net.blancworks.figura.models.tasks.ItemRenderTask;
import net.blancworks.figura.models.tasks.TextRenderTask;
import net.blancworks.figura.utils.TextUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Shader;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.item.ItemStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3f;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

public class RendererAPI {

    public static Identifier getID() {
        return new Identifier("default", "renderer");
    }

    public static ReadOnlyLuaTable getForScript(CustomScript script) {
        return new ReadOnlyLuaTable(new LuaTable(){{
            set("setShadowSize", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue arg) {
                    script.customShadowSize = arg.isnil() ? null : MathHelper.clamp(arg.checknumber().tofloat(), 0f, 24f);
                    return NIL;
                }
            });

            set("getShadowSize", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return script.customShadowSize == null ? NIL : LuaNumber.valueOf(script.customShadowSize);
                }
            });

            set("isFirstPerson", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    if (PlayerDataManager.localPlayer != script.playerData)
                        return LuaBoolean.FALSE;

                    return MinecraftClient.getInstance().options.getPerspective().isFirstPerson() ? LuaBoolean.TRUE : LuaBoolean.FALSE;
                }
            });

            set("isCameraBackwards", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    if (PlayerDataManager.localPlayer != script.playerData)
                        return LuaBoolean.FALSE;

                    return MinecraftClient.getInstance().options.getPerspective().isFrontView() ? LuaBoolean.TRUE : LuaBoolean.FALSE;
                }
            });

            set("getCameraPos", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    //Yes, this IS intended to also be called for non-local players.
                    //This might be exploitable? idk
                    return LuaVector.of(MinecraftClient.getInstance().gameRenderer.getCamera().getPos());
                }
            });

            set("setRenderFire", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue arg) {
                    script.shouldRenderFire = arg.isnil() ? null : arg.checkboolean();
                    return NIL;
                }
            });

            set("getRenderFire", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return script.shouldRenderFire == null ? NIL : LuaBoolean.valueOf(script.shouldRenderFire);
                }
            });

            set("setUniform", new ThreeArgFunction() {
                @Override
                public LuaValue call(LuaValue layerName, LuaValue uniformName, LuaValue value) {
                    if (!script.playerData.canRenderCustomLayers())
                        return NIL;
                    RenderSystem.recordRenderCall(() -> {
                        try {
                            Shader customShader;
                            if (script.playerData.customVCP != null) {
                                FiguraRenderLayer customLayer = script.playerData.customVCP.getRenderLayer(layerName.checkjstring());
                                if (customLayer != null)
                                    customShader = customLayer.getShader();
                                else
                                    throw new LuaError("There is no custom layer with that name!");
                            } else
                                throw new LuaError("The player has no custom VCP!");

                            if (customShader != null) {
                                if (customShader instanceof FiguraShader)
                                    ((FiguraShader) customShader).setUniformFromLua(uniformName, value);
                                else
                                    throw new LuaError("Either your shader syntax is incorrect, or you're trying to setUniform on a vanilla shader. Either one is bad!");
                            }
                        } catch (Exception e) {
                            PlayerData local = PlayerDataManager.localPlayer;
                            if (local == null || script.playerData.playerId != local.playerId || script.equals(local.script))
                                script.handleError(e);
                        }
                    });
                    return NIL;
                }
            });

            set("renderItem", new VarArgFunction() {
                @Override
                public Varargs onInvoke(Varargs args) {
                    if (script.renderMode == CustomScript.RenderType.WORLD_RENDER)
                        throw new LuaError("Cannot render item on world render!");

                    ItemStack stack = ItemStackAPI.checkOrCreateItemStack(args.arg(1));
                    CustomModelPart parent = CustomModelAPI.checkCustomModelPart(args.arg(2));
                    ModelTransformation.Mode mode = !args.arg(3).isnil() ? ModelTransformation.Mode.valueOf(args.arg(3).checkjstring()) : ModelTransformation.Mode.FIXED;
                    boolean emissive = !args.arg(4).isnil() && args.arg(4).checkboolean();
                    Vec3f pos = args.arg(5).isnil() ? null : LuaVector.checkOrNew(args.arg(5)).asV3f();
                    Vec3f rot = args.arg(6).isnil() ? null : LuaVector.checkOrNew(args.arg(6)).asV3f();
                    Vec3f scale = args.arg(7).isnil() ? null : LuaVector.checkOrNew(args.arg(7)).asV3f();

                    FiguraRenderLayer customLayer = null;
                    if (!args.arg(8).isnil()) {
                        if (script.playerData.customVCP != null) {
                            customLayer = script.playerData.customVCP.getRenderLayer(args.arg(8).checkjstring());
                        } else
                            throw new LuaError("The player has no custom VCP!");
                    }

                    parent.renderTasks.add(new ItemRenderTask(stack, mode, emissive, pos, rot, scale, customLayer));

                    return NIL;
                }
            });

            set("renderBlock", new VarArgFunction() {
                @Override
                public Varargs onInvoke(Varargs args) {
                    if (script.renderMode == CustomScript.RenderType.WORLD_RENDER)
                        throw new LuaError("Cannot render block on world render!");

                    BlockState state = BlockStateAPI.checkOrCreateBlockState(args.arg(1));
                    CustomModelPart parent = CustomModelAPI.checkCustomModelPart(args.arg(2));
                    boolean emissive = !args.arg(3).isnil() && args.arg(3).checkboolean();
                    Vec3f pos = args.arg(4).isnil() ? null : LuaVector.checkOrNew(args.arg(4)).asV3f();
                    Vec3f rot = args.arg(5).isnil() ? null : LuaVector.checkOrNew(args.arg(5)).asV3f();
                    Vec3f scale = args.arg(6).isnil() ? null : LuaVector.checkOrNew(args.arg(6)).asV3f();
                    FiguraRenderLayer customLayer = null;
                    if (!args.arg(7).isnil()) {
                        if (script.playerData.customVCP != null) {
                            customLayer = script.playerData.customVCP.getRenderLayer(args.arg(7).checkjstring());
                        } else
                            throw new LuaError("The player has no custom VCP!");
                    }

                    parent.renderTasks.add(new BlockRenderTask(state, emissive, pos, rot, scale, customLayer));

                    return NIL;
                }
            });

            set("renderText", new VarArgFunction() {
                @Override
                public Varargs onInvoke(Varargs args) {
                    if (script.renderMode == CustomScript.RenderType.WORLD_RENDER)
                        throw new LuaError("Cannot render text on world render!");

                    String arg1 = TextUtils.noBadges4U(args.arg(1).checkjstring()).replaceAll("[\n\r]", " ");

                    if (arg1.length() > 65535)
                        throw new LuaError("Text too long - oopsie!");

                    Text text;
                    try {
                        text = Text.Serializer.fromJson(new StringReader(arg1));

                        if (text == null)
                            throw new Exception("Error parsing JSON string");
                    } catch (Exception ignored) {
                        text = new LiteralText(arg1);
                    }

                    CustomModelPart parent = CustomModelAPI.checkCustomModelPart(args.arg(2));
                    boolean emissive = !args.arg(3).isnil() && args.arg(3).checkboolean();
                    Vec3f pos = args.arg(4).isnil() ? null : LuaVector.checkOrNew(args.arg(4)).asV3f();
                    Vec3f rot = args.arg(5).isnil() ? null : LuaVector.checkOrNew(args.arg(5)).asV3f();
                    Vec3f scale = args.arg(6).isnil() ? null : LuaVector.checkOrNew(args.arg(6)).asV3f();

                    parent.renderTasks.add(new TextRenderTask(text, emissive, pos, rot, scale));

                    return NIL;
                }
            });

            set("getTextWidth", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue arg) {
                    String arg1 = TextUtils.noBadges4U(arg.checkjstring()).replaceAll("[\n\r]", " ");
                    Text text;
                    try {
                        text = Text.Serializer.fromJson(new StringReader(arg1));

                        if (text == null)
                            throw new Exception("Error parsing JSON string");
                    } catch (Exception ignored) {
                        text = new LiteralText(arg1);
                    }
                    return LuaNumber.valueOf(MinecraftClient.getInstance().textRenderer.getWidth(text));
                }
            });

            set("setMountEnabled", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue arg) {
                    script.renderMount = arg.checkboolean();
                    return NIL;
                }
            });

            set("isMountEnabled", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return LuaBoolean.valueOf(script.renderMount);
                }
            });

            set("setMountShadowEnabled", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue arg) {
                    script.renderMountShadow = arg.checkboolean();
                    return NIL;
                }
            });

            set("isMountShadowEnabled", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return LuaBoolean.valueOf(script.renderMountShadow);
                }
            });

        }});
    }
}
