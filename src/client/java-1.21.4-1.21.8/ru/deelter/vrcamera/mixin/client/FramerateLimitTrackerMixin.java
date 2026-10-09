package ru.deelter.vrcamera.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;

@Mixin(FramerateLimitTracker.class)
public class FramerateLimitTrackerMixin {

	/**
	 * The game draws a few frames per second while its window is minimized or nothing was pressed for a while. The
	 * picture of a camera with a window of its own is drawn with those frames, and is still being recorded: it
	 * gets as many frames as the camera shows then, and not as many as the graphics card can do
	 */
	@ModifyReturnValue(method = "getFramerateLimit", at = @At("RETURN"), require = 0)
	private int vrcamera$allFramesForCamera(int limit) {
		if (!DesktopCamera.INSTANCE.hasOwnWindow()) {
			return limit;
		}
		final int usual = Minecraft.getInstance().options.framerateLimit().get();
		final double shown = CameraConfig.current().outputFps;
		return limit < usual && shown > 0 ? Math.max(limit, Math.min(usual, (int) Math.ceil(shown))) :
				Math.max(limit, usual);
	}
}
