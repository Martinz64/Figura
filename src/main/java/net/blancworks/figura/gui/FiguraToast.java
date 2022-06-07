package net.blancworks.figura.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.blancworks.figura.FiguraMod;
import net.blancworks.figura.config.ConfigManager.Config;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class FiguraToast implements Toast {

    private final Text title;
    private final Text message;
    private final boolean cheese;

    private long startTime;
    private boolean justUpdated;

    private static final Identifier TEXTURE = new Identifier("figura", "textures/gui/toast.png");

    public FiguraToast(Text title, Text message) {
        this.cheese = (boolean) Config.EASTER_EGGS.value && (FiguraMod.IS_CHEESE || Math.random() < 0.0001);
        this.title = title.copy().fillStyle(Style.EMPTY.withColor(cheese ? 0xF8C53A : 0x55FFFF));
        this.message = message;
    }

    @Override
    public Visibility draw(MatrixStack matrices, ToastManager manager, long startTime) {
        long timeDiff = startTime - this.startTime;

        if (this.justUpdated) {
            this.startTime = startTime;
            this.justUpdated = false;
        }

        RenderSystem.setShaderTexture(0, TEXTURE);
        DrawableHelper.drawTexture(matrices, 0, 0, 0f, cheese ? 0 : (int) ((timeDiff / (5000 / 24) % 4) + 1) * 32f, 160, 32, 160, 160);

        if (this.message == null) {
            manager.getClient().textRenderer.draw(matrices, this.title, 31f, 12f, 0xFFFFFF);
        } else {
            manager.getClient().textRenderer.draw(matrices, this.title, 31f, 7f, 0xFFFFFF);
            manager.getClient().textRenderer.draw(matrices, this.message, 31f, 18f, 0xFFFFFF);
        }

        return timeDiff < 5000 ? Toast.Visibility.SHOW : Toast.Visibility.HIDE;
    }
}
