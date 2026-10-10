package ru.deelter.vrcamera.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import ru.deelter.vrcamera.client.desktop.DirectorPass;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.occlusion.AsyncCameraTimingControl", remap = false)
public class SodiumCameraTimingMixin {
	@Inject(method = "getShouldRenderSync", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$cameraIsSeenOnTheSpot(CallbackInfoReturnable<Boolean> cir) {
		if (DirectorPass.isActive()) {
			cir.setReturnValue(true);
		}
	}
}
