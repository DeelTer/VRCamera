package ru.deelter.vrcamera.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import ru.deelter.vrcamera.client.desktop.DesktopCamera;

@Mixin(FramerateLimitTracker.class)
public class FramerateLimitTrackerMixin {

	@ModifyReturnValue(method = "getThrottleReason", at = @At("RETURN"), require = 0)
	private FramerateLimitTracker.FramerateThrottleReason vrcamera$allFramesForCamera(
			FramerateLimitTracker.FramerateThrottleReason reason) {
		return DesktopCamera.INSTANCE.hasOwnWindow() ? FramerateLimitTracker.FramerateThrottleReason.NONE : reason;
	}
}
