package ru.deelter.vrcamera.mixin.client;

import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vivecraft.client.extensions.EntityRenderStateExtension;
import org.vivecraft.client.render.VRPlayerModel;
import org.vivecraft.client.render.VRPlayerRenderData;
import ru.deelter.vrcamera.client.CameraController;

@Mixin(value = VRPlayerModel.class, remap = false)
public class VRPlayerModelMixin {

	// keeps the arm that holds the camera out of the picture, it is right next to the lens
	@Inject(method = "animateVRModel", at = @At("RETURN"), require = 0)
	private static void vrcamera$hideHoldingArm(
			PlayerModel model, AvatarRenderState renderState, Vector3f tempV, Vector3f tempV2, Matrix3f tempM,
			CallbackInfo ci) {
		VRPlayerRenderData data = ((EntityRenderStateExtension) renderState).vivecraft$getVRRenderData();
		if (data == null || !data.isMainPlayer()) {
			return;
		}
		HumanoidArm arm = CameraController.INSTANCE.armToHide();
		if (arm == null) {
			return;
		}
		if (model instanceof VRPlayerModel vrModel) {
			if (arm == HumanoidArm.LEFT) {
				vrModel.hideLeftArm(true);
			} else {
				vrModel.hideRightArm(true);
			}
		} else if (arm == HumanoidArm.LEFT) {
			model.leftArm.visible = false;
		} else {
			model.rightArm.visible = false;
		}
	}
}
