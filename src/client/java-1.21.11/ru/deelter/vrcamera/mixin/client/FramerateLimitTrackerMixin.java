package ru.deelter.vrcamera.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;

@Mixin(FramerateLimitTracker.class)
public class FramerateLimitTrackerMixin {
	@Unique
	private boolean idle;

	@ModifyReturnValue(method = "getThrottleReason", at = @At("RETURN"), require = 0)
	private FramerateLimitTracker.FramerateThrottleReason vrcamera$allFramesForCamera(
			FramerateLimitTracker.FramerateThrottleReason reason) {
		idle = reason != FramerateLimitTracker.FramerateThrottleReason.NONE;
		return DesktopCamera.INSTANCE.hasOwnWindow() ? FramerateLimitTracker.FramerateThrottleReason.NONE : reason;
	}

	/**
	 * While the game would draw a few frames per second, minimized or with nobody at the keys, it draws for the
	 * camera alone: as many frames as the camera shows, and not as many as the graphics card can do
	 */
	@ModifyReturnValue(method = "getFramerateLimit", at = @At("RETURN"), require = 0)
	private int vrcamera$noMoreThanTheCameraShows(int limit) {
		final double shown = CameraConfig.current().outputFps;
		return idle && shown > 0 && DesktopCamera.INSTANCE.hasOwnWindow() ? Math.min(limit, (int) Math.ceil(shown)) :
				limit;
	}
}
