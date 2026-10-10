package ru.deelter.vrcamera.mixin.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import ru.deelter.vrcamera.client.desktop.DesktopCamera;
import ru.deelter.vrcamera.client.desktop.DirectorPass;
import ru.deelter.vrcamera.client.desktop.OutputWindow;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;

@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Shadow
	@Final
	private DeltaTracker.Timer deltaTracker;

	@Inject(method = "renderFrame", at = @At(value = "CONSTANT", args = "stringValue=present"), require = 0)
	private void vrcamera$drawDirector(boolean renderLevel, CallbackInfo ci) {
		PhotoAlbum.INSTANCE.frameWithoutVR((Minecraft) (Object) this);
		DirectorPass.onFrame((Minecraft) (Object) this, deltaTracker, renderLevel);
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$useKeyTakesCamera(CallbackInfo ci) {
		if (DesktopCamera.INSTANCE.wantsUseKey()) {
			ci.cancel();
		}
	}

	@Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$noPauseForCameraWindow(CallbackInfo ci) {
		if (OutputWindow.isFocused()) {
			ci.cancel();
		}
	}

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$attackKeyPicksCamera(CallbackInfoReturnable<Boolean> cir) {
		if (DesktopCamera.INSTANCE.select()) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$attackKeyStaysOnCamera(boolean leftClick, CallbackInfo ci) {
		if (leftClick && DesktopCamera.INSTANCE.pointsAtFreeCamera()) {
			ci.cancel();
		}
	}
}
