package spacesb.remake.mixin;

import net.minecraft.client.util.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public class WindowLobotomyMixin {

    @Inject(method = "setVsync", at = @At("HEAD"), cancellable = true)
    private void lobotomizeVsync(boolean vsync, CallbackInfo ci) {

        GLFW.glfwSwapInterval(0);

        ci.cancel();
    }
}