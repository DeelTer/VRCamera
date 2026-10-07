package ru.deelter.vrcamera.mixin.vivecraft;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.vivecraft.api.client.data.RenderPass;
import org.vivecraft.client_vr.render.helpers.VRWidgetHelper;
import ru.deelter.vrcamera.client.CameraController;
import ru.deelter.vrcamera.client.SelfieScreen;

// optional, the mod works without these, so don't crash if Vivecraft changes the methods
@Pseudo
@Mixin(value = VRWidgetHelper.class, remap = false)
public class VRWidgetHelperMixin {

	@Inject(method = "renderVRHandheldCameraWidget", at = @At("HEAD"), cancellable = true, require = 0)
	private static void vrcamera$hideCameraModel(CallbackInfo ci) {
		if (CameraController.INSTANCE.hidesModel()) {
			ci.cancel();
		}
	}

	// Vivecraft puts the model where the camera films from, which is steadied. It belongs into the hand
	@ModifyVariable(method = "renderVRCameraWidget", at = @At("STORE"), ordinal = 0, require = 0)
	private static Vec3 vrcamera$modelIntoHand(Vec3 filmedFrom, @Local(argsOnly = true) RenderPass shown) {
		Vec3 hand = CameraController.INSTANCE.handPosition();
		return shown == RenderPass.CAMERA && hand != null ? hand : filmedFrom;
	}

	@ModifyVariable(method = "renderVRCameraWidget", at = @At("STORE"), ordinal = 0, require = 0)
	private static Matrix4f vrcamera$modelTurnedLikeHand(Matrix4f filmed, @Local(argsOnly = true) RenderPass shown) {
		CameraController controller = CameraController.INSTANCE;
		return shown == RenderPass.CAMERA && controller.handPosition() != null ?
				new Matrix4f().rotation(controller.handRotation()) : filmed;
	}

	@Inject(method = "renderVRCameraWidget", at = @At("TAIL"), require = 0)
	private static void vrcamera$selfieScreen(
			CallbackInfo ci, @Local(argsOnly = true) RenderPass shown, @Local PoseStack poseStack) {
		if (shown == RenderPass.CAMERA) {
			SelfieScreen.render(poseStack);
		}
	}
}
