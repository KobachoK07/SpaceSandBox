package spacesb.remake.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public class MinecraftClientLobotomyMixin {

    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
    private void lobotomizeFpsLimit(CallbackInfoReturnable<Integer> cir) {

        cir.setReturnValue(1000);
    }
}