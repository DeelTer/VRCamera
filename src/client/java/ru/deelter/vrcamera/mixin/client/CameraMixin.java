package ru.deelter.vrcamera.mixin.client;

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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
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

	// Without VR the director films into the view of the game. Right after the game put its camera at the player,
	// before it works out what that camera sees
	@Inject(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V", shift = At.Shift.AFTER), require = 0)
	private void vrcamera$filmFromDirector(DeltaTracker deltaTracker, CallbackInfo ci) {
		DesktopCamera.Pose pose = DesktopCamera.INSTANCE.update(getCameraEntityPartialTicks(deltaTracker));
		if (pose == null) {
			return;
		}
		Vector3f forward = pose.rotation().transform(new Vector3f(0, 0, -1));
		setRotation((float) Math.atan2(-forward.x, forward.z) * Mth.RAD_TO_DEG,
				(float) -Math.asin(Mth.clamp(forward.y, -1.0F, 1.0F)) * Mth.RAD_TO_DEG);
		setPosition(pose.position());
		// from outside of the player: the player is drawn
		this.detached = true;
	}

	@ModifyReturnValue(method = "calculateFov", at = @At("RETURN"), require = 0)
	private float vrcamera$fovOfDirector(float fov) {
		DesktopCamera.Pose pose = DesktopCamera.INSTANCE.pose();
		return pose == null ? fov : pose.fov();
	}

	// The picture of a camera with a window of its own has the shape of that window, not of the game window. It is
	// drawn as large as the game window all the same, and squeezed into shape when it is shown
	@ModifyExpressionValue(method = {"update", "createProjectionMatrixForCulling"}, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;getWidth()I"), require = 0)
	private int vrcamera$widthOfPicture(int width) {
		return DirectorPass.width(width);
	}

	@ModifyExpressionValue(method = {"update", "createProjectionMatrixForCulling"}, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;getHeight()I"), require = 0)
	private int vrcamera$heightOfPicture(int height) {
		return DirectorPass.height(height);
	}
}
