package ru.deelter.vrcamera.mixin.client;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vivecraft.api.client.data.RenderPass;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.VRData;
import org.vivecraft.client_vr.render.helpers.VRWidgetHelper;
import org.vivecraft.client_vr.render.renderstates.CameraWidgetRenderState;
import ru.deelter.vrcamera.client.CameraController;

@Mixin(value = VRWidgetHelper.class, remap = false)
public class VRWidgetHelperMixin {

	// optional, the mod works without this, so don't crash if Vivecraft changes the method
	@Inject(method = "extractVRHandheldCameraWidget", at = @At("TAIL"), require = 0)
	private static void vrcamera$replaceCameraModel(
		CameraWidgetRenderState cameraState, LocalPlayer player, CallbackInfo ci)
	{
		CameraController controller = CameraController.INSTANCE;
		if (!cameraState.visible || !controller.hidesModel()) {
			return;
		}
		cameraState.visible = false;

		// only for the eyes of the player, it should not show up in the recording
		ClientDataHolderVR dh = ClientDataHolderVR.getInstance();
		if (dh.vrPlayer != null && (dh.currentPass == RenderPass.LEFT || dh.currentPass == RenderPass.RIGHT)) {
			VRData data = dh.vrPlayer.vrdata_world_render;
			controller.drawMarker(data.getEye(RenderPass.CAMERA).getPosition(), data.worldScale);
		}
	}
}
