package ru.deelter.vrcamera.mixin.client;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vivecraft.client_vr.render.helpers.VRWidgetHelper;
import org.vivecraft.client_vr.render.renderstates.CameraWidgetRenderState;
import ru.deelter.vrcamera.client.CameraController;

@Mixin(value = VRWidgetHelper.class, remap = false)
public class VRWidgetHelperMixin {

	// optional, the mod works without this, so don't crash if Vivecraft changes the method
	@Inject(method = "extractVRHandheldCameraWidget", at = @At("TAIL"), require = 0)
	private static void vrcamera$hideCameraModel(
			CameraWidgetRenderState cameraState, LocalPlayer player, CallbackInfo ci) {
		if (CameraController.INSTANCE.hidesModel()) {
			cameraState.visible = false;
		}
	}
}
