package net.blancworks.figura.mixin;

import net.blancworks.figura.FiguraMod;
import net.blancworks.figura.avatar.AvatarData;
import net.blancworks.figura.avatar.AvatarDataManager;
import net.blancworks.figura.lua.CustomScript;
import net.minecraft.client.gui.hud.DebugHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(DebugHud.class)
public class DebugHudMixin {

    @Inject(at = @At("RETURN"), method = "getRightText")
    protected void getRightText(CallbackInfoReturnable<List<String>> cir) {
        if (AvatarDataManager.panic) return;

        List<String> lines = cir.getReturnValue();

        int i = 0;
        for (; i < lines.size(); i++) {
            if (lines.get(i).equals(""))
                break;
        }

        lines.add(++i, "§b[FIGURA]§r");
        lines.add(++i, "Version: " + FiguraMod.MOD_VERSION);

        AvatarData data = AvatarDataManager.localPlayer;
        if (data != null && data.hasAvatar()) {
            lines.add(++i, String.format("Complexity: %d", data.getComplexity()));

            if (data.model != null)
                lines.add(++i, String.format("Animations Complexity: %d", data.model.animRendered));

            CustomScript script = AvatarDataManager.localPlayer.script;
            if (script != null) {
                lines.add(++i, String.format("Init instructions: %d", script.initInstructionCount));
                lines.add(++i, String.format("Tick instructions: %d", script.tickInstructionCount));
                lines.add(++i, String.format("Render instructions: %d",script.renderInstructionCount));
            }
        }
        lines.add(++i, String.format("Pings per second: ↑%d, ↓%d", CustomScript.pingSent, CustomScript.pingReceived));

        lines.add(++i, "");
    }
}