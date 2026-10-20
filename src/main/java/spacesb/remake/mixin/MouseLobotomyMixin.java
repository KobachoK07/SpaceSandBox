package spacesb.remake.mixin;

import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import spacesb.remake.SpaceEngine;

@Mixin(Mouse.class)
public class MouseLobotomyMixin {

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void interceptScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        SpaceEngine.onScroll(vertical);

        ci.cancel();
    }
}