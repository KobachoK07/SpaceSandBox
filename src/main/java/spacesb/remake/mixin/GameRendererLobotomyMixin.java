package spacesb.remake.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import spacesb.remake.SpaceEngine;

@Mixin(GameRenderer.class)
public class GameRendererLobotomyMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void killMinecraftGraphics(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getWindow() == null || client.getFramebuffer() == null) return;

        client.getFramebuffer().beginWrite(true);
        SpaceEngine.processInput(client.getWindow().getHandle());

        RenderSystem.clearColor(0.02f, 0.05f, 0.15f, 1.0f);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);

        DrawContext context = new DrawContext(client, client.getBufferBuilders().getEntityVertexConsumers());

        SpaceEngine.render3D(context);
        SpaceEngine.renderUI(context);

        ci.cancel();
    }
}