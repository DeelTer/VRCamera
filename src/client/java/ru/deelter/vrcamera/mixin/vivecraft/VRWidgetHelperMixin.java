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
	@Inject(method = "extractVRHandheldCameraWidget", at = @At("TAIL"), require = 0)
	private static void vrcamera$adjustCameraModel(
			CameraWidgetRenderState cameraState, LocalPlayer player, CallbackInfo ci) {
		final CameraController controller = CameraController.INSTANCE;
		if (controller.hidesModel()) {
			cameraState.visible = false;
			return;
		}
		final Vec3 hand = controller.handPosition();
		final ClientDataHolderVR dataHolder = ClientDataHolderVR.getInstance();
		if (dataHolder.currentPass == RenderPass.CAMERA) {
			cameraState.visible = false;
			return;
		}
		if (hand == null || !cameraState.visible || dataHolder.vrPlayer == null) {
			return;
		}

		final Matrix4f filmed = dataHolder.vrPlayer.vrdata_world_render.getEye(RenderPass.CAMERA).getMatrix();
		cameraState.modelMatrix.mulLocal(filmed.invert())
				.mulLocal(new Matrix4f().rotation(controller.handRotation()));
		cameraState.pos = hand;
	}

	@Inject(method = "renderVRHandheldCameraWidget", at = @At("HEAD"), cancellable = true, require = 0)
	private static void vrcamera$keepModelOutOfPicture(
			SubmitNodeCollector output, CameraRenderState cameraState, CameraWidgetRenderState widgetState,
			PoseStack poseStack, CallbackInfo ci) {
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
