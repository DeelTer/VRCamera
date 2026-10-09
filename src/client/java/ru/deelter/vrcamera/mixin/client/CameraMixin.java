package ru.deelter.vrcamera.mixin.client;

import org.joml.Vector3fc;
import ru.deelter.vrcamera.client.desktop.PlayerEars;
import org.joml.Quaternionf;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ru.deelter.vrcamera.client.desktop.DesktopCamera;
import ru.deelter.vrcamera.client.desktop.DirectorPass;

@Mixin(Camera.class)
public abstract class CameraMixin {

	@Shadow
	private boolean detached;

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	public abstract float getCameraEntityPartialTicks(DeltaTracker deltaTracker);

	@Inject(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V", shift = At.Shift.AFTER), require = 0)
	private void vrcamera$filmFromDirector(DeltaTracker deltaTracker, CallbackInfo ci) {
		final DesktopCamera.Pose pose = DesktopCamera.INSTANCE.update(getCameraEntityPartialTicks(deltaTracker));
		if (pose == null) {
			return;
		}
		final Vector3f forward = pose.rotation().transform(new Vector3f(0, 0, -1));
		setRotation((float) Math.atan2(-forward.x, forward.z) * Mth.RAD_TO_DEG,
				(float) -Math.asin(Mth.clamp(forward.y, -1.0F, 1.0F)) * Mth.RAD_TO_DEG);
		setPosition(pose.position());

		detached = true;
	}

	@ModifyArg(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setupPerspective(FFFFF)V"), index = 0, require = 0)
	private float vrcamera$nearOfPicture(float near) {
		return DirectorPass.near(near);
	}

	@ModifyReturnValue(method = "calculateFov", at = @At("RETURN"), require = 0)
	private float vrcamera$fovOfDirector(float fov) {
		final DesktopCamera.Pose pose = DesktopCamera.INSTANCE.pose();
		return pose == null ? fov : pose.fov();
	}

	@ModifyExpressionValue(method = {"update", "createProjectionMatrixForCulling"}, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;getWidth()I"), require = 0)
	private int vrcamera$widthOfPicture(int width) {
		return DirectorPass.width(width);
	}

	@ModifyExpressionValue(method = {"update", "createProjectionMatrixForCulling"}, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;getHeight()I"), require = 0)
	private int vrcamera$heightOfPicture(int height) {
		return DirectorPass.height(height);
	}

	@ModifyReturnValue(method = "position", at = @At("RETURN"), require = 0)
	private Vec3 vrcamera$earsPosition(Vec3 position) {
		final PlayerEars.Kept ears = PlayerEars.now();
		return ears == null ? position : ears.position();
	}

	@ModifyReturnValue(method = "forwardVector", at = @At("RETURN"), require = 0)
	private Vector3fc vrcamera$earsForward(Vector3fc forward) {
		final PlayerEars.Kept ears = PlayerEars.now();
		return ears == null ? forward : ears.forward();
	}

	@ModifyReturnValue(method = "upVector", at = @At("RETURN"), require = 0)
	private Vector3fc vrcamera$earsUp(Vector3fc up) {
		final PlayerEars.Kept ears = PlayerEars.now();
		return ears == null ? up : ears.up();
	}

	@ModifyReturnValue(method = "leftVector", at = @At("RETURN"), require = 0)
	private Vector3fc vrcamera$earsLeft(Vector3fc left) {
		final PlayerEars.Kept ears = PlayerEars.now();
		return ears == null ? left : ears.left();
	}

	@ModifyReturnValue(method = "rotation", at = @At("RETURN"), require = 0)
	private Quaternionf vrcamera$earsRotation(Quaternionf rotation) {
		final PlayerEars.Kept ears = PlayerEars.now();
		return ears == null ? rotation : ears.rotation();
	}

	@ModifyReturnValue(method = "yRot", at = @At("RETURN"), require = 0)
	private float vrcamera$earsYRot(float yRot) {
		final PlayerEars.Kept ears = PlayerEars.now();
		return ears == null ? yRot : ears.yRot();
	}

	@ModifyReturnValue(method = "xRot", at = @At("RETURN"), require = 0)
	private float vrcamera$earsXRot(float xRot) {
		final PlayerEars.Kept ears = PlayerEars.now();
		return ears == null ? xRot : ears.xRot();
	}
}
