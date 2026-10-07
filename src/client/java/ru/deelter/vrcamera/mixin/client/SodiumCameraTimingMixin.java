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

	// Sodium watches how far the view moves from one frame to the next, and a view that jumps gets what is seen
	// worked out on the spot. The camera is such a jump every time, and is told so without Sodium taking note of
	// where it is: to it the view of the player goes on as if there was no camera
	@Inject(method = "getShouldRenderSync", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$cameraIsSeenOnTheSpot(CallbackInfoReturnable<Boolean> cir) {
		if (DirectorPass.isActive()) {
			cir.setReturnValue(true);
		}
	}
}
