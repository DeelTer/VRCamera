package ru.deelter.vrcamera.mixin.client;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vivecraft.api.client.data.RenderPass;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.render.helpers.VRWidgetHelper;
import org.vivecraft.client_vr.render.renderstates.CameraWidgetRenderState;
import ru.deelter.vrcamera.client.CameraController;

@Mixin(value = VRWidgetHelper.class, remap = false)
public class VRWidgetHelperMixin {

	// optional, the mod works without this, so don't crash if Vivecraft changes the method
	@Inject(method = "extractVRHandheldCameraWidget", at = @At("TAIL"), require = 0)
	private static void vrcamera$replaceCameraModel(
			CameraWidgetRenderState cameraState, LocalPlayer player, CallbackInfo ci) {
		CameraController controller = CameraController.INSTANCE;
		ClientDataHolderVR dh = ClientDataHolderVR.getInstance();
		if (!cameraState.visible || !controller.isEngaged() || dh.vrPlayer == null) {
			return;
		}
		if (controller.hidesModel()) {
			cameraState.visible = false;
		}
		// only for the eyes of the player, none of this should show up in the recording
		if (dh.currentPass == RenderPass.LEFT || dh.currentPass == RenderPass.RIGHT) {
			controller.drawHeadsetAids(dh.vrPlayer.vrdata_world_render);
		}
	}
}
