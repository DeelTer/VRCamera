package ru.deelter.vrcamera.mixin.vivecraft;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vivecraft.api.client.data.RenderPass;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.render.helpers.VRWidgetHelper;
import org.vivecraft.client_vr.render.renderstates.CameraWidgetRenderState;
import ru.deelter.vrcamera.client.CameraController;
import ru.deelter.vrcamera.client.SelfieScreen;
import ru.deelter.vrcamera.client.photo.CameraFlashes;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;
import ru.deelter.vrcamera.client.sync.RemoteCameras;

@Pseudo
@Mixin(value = VRWidgetHelper.class, remap = false)
public class VRWidgetHelperMixin {

	// optional, the mod works without this, so don't crash if Vivecraft changes the method
	@Inject(method = "extractVRHandheldCameraWidget", at = @At("TAIL"), require = 0)
	private static void vrcamera$adjustCameraModel(
			CameraWidgetRenderState cameraState, LocalPlayer player, CallbackInfo ci) {
		CameraController controller = CameraController.INSTANCE;
		if (controller.hidesModel()) {
			cameraState.visible = false;
			return;
		}
		Vec3 hand = controller.handPosition();
		ClientDataHolderVR dh = ClientDataHolderVR.getInstance();
		if (dh.currentPass == RenderPass.CAMERA) {
			// the camera must never film its own model, which is no longer where it films from
			cameraState.visible = false;
			return;
		}
		if (hand == null || !cameraState.visible || dh.vrPlayer == null) {
			return;
		}
		// Vivecraft put the model where the camera films from, which is steadied. Turn it back into the hand
		Matrix4f filmed = dh.vrPlayer.vrdata_world_render.getEye(RenderPass.CAMERA).getMatrix();
		cameraState.modelMatrix.mulLocal(filmed.invert())
				.mulLocal(new Matrix4f().rotation(controller.handRotation()));
		cameraState.pos = hand;
	}

	// Checked again when it is drawn: the flag set above did not keep the model out of the camera's own picture.
	// Vivecraft never notices, its model is where the camera films from and is looked at from inside
	@Inject(method = "renderVRHandheldCameraWidget", at = @At("HEAD"), cancellable = true, require = 0)
	private static void vrcamera$keepModelOutOfPicture(
			SubmitNodeCollector output, CameraRenderState cameraState, CameraWidgetRenderState widgetState,
			PoseStack poseStack, CallbackInfo ci) {
		// this is called once in every pass, with a pose stack in world axes: a place to draw into the world from
		PhotoAlbum.INSTANCE.render(output, cameraState.pos, poseStack);
		RemoteCameras.INSTANCE.render(output, cameraState.pos, poseStack);
		CameraFlashes.INSTANCE.render(output, cameraState.pos, poseStack);
		if (widgetState.visible) {
			SelfieScreen.render(output, cameraState.pos, widgetState.pos, widgetState.modelMatrix, poseStack);
		}
		if (ClientDataHolderVR.getInstance().currentPass == RenderPass.CAMERA &&
				CameraController.INSTANCE.isEngaged()) {
			ci.cancel();
		}
	}
}
