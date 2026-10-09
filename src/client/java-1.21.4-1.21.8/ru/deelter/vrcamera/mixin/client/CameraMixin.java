package ru.deelter.vrcamera.mixin.client;

import ru.deelter.vrcamera.client.desktop.PlayerEars;
import org.joml.Quaternionf;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private boolean detached;

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Inject(method = "setup", at = @At("TAIL"), require = 0)
	private void vrcamera$filmFromDirector(
			BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {
		DesktopCamera.Pose pose = DesktopCamera.INSTANCE.update(partialTick);
		if (pose == null) {
			return;
		}
		Vector3f forward = pose.rotation().transform(new Vector3f(0, 0, -1));
		setRotation((float) Math.atan2(-forward.x, forward.z) * Mth.RAD_TO_DEG,
				(float) -Math.asin(Mth.clamp(forward.y, -1.0F, 1.0F)) * Mth.RAD_TO_DEG);
		setPosition(pose.position());
		detached = true;
	}

	@ModifyReturnValue(method = {"getPosition", "position"}, at = @At("RETURN"), require = 0)
	private Vec3 vrcamera$earsPosition(Vec3 position) {
		final PlayerEars.Kept ears = PlayerEars.now();
		return ears == null ? position : ears.position();
	}

	@ModifyReturnValue(method = "getLookVector", at = @At("RETURN"), require = 0)
	private Vector3f vrcamera$earsForward(Vector3f forward) {
		final PlayerEars.Kept ears = PlayerEars.now();
		return ears == null ? forward : ears.forward();
	}

	@ModifyReturnValue(method = "getUpVector", at = @At("RETURN"), require = 0)
	private Vector3f vrcamera$earsUp(Vector3f up) {
		final PlayerEars.Kept ears = PlayerEars.now();
		return ears == null ? up : ears.up();
	}

	@ModifyReturnValue(method = "getLeftVector", at = @At("RETURN"), require = 0)
	private Vector3f vrcamera$earsLeft(Vector3f left) {
		final PlayerEars.Kept ears = PlayerEars.now();
		return ears == null ? left : ears.left();
	}

	@ModifyReturnValue(method = "rotation", at = @At("RETURN"), require = 0)
	private Quaternionf vrcamera$earsRotation(Quaternionf rotation) {
		final PlayerEars.Kept ears = PlayerEars.now();
		return ears == null ? rotation : ears.rotation();
	}

	@ModifyReturnValue(method = "getYRot", at = @At("RETURN"), require = 0)
	private float vrcamera$earsYRot(float yRot) {
		final PlayerEars.Kept ears = PlayerEars.now();
		return ears == null ? yRot : ears.yRot();
	}

	@ModifyReturnValue(method = "getXRot", at = @At("RETURN"), require = 0)
	private float vrcamera$earsXRot(float xRot) {
		final PlayerEars.Kept ears = PlayerEars.now();
		return ears == null ? xRot : ears.xRot();
	}
}
