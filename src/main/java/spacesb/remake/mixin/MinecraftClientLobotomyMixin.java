package spacesb.remake.mixin;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import spacesb.remake.SpaceEngine;

@Mixin(MinecraftClient.class)
public class MinecraftClientLobotomyMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void killMinecraftTicks(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void killMinecraftCompletely(boolean tick, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getWindow() != null) {
            long window = client.getWindow().getHandle();

            SpaceEngine.renderFrame();

            GLFW.glfwPollEvents();

            if (GLFW.glfwWindowShouldClose(window)) {
                client.scheduleStop();
            }
        }

        ci.cancel();
    }
}