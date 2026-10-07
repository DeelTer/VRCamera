package ru.deelter.vrcamera.mixin.client;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;
import ru.deelter.vrcamera.client.desktop.DirectorPass;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;

@Mixin(Minecraft.class)
public class MinecraftMixin {

	// After the game drew its frame and before it shows it. Without VR the camera of the director gets a picture
	// of its own here
	@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V", shift = At.Shift.AFTER), require = 0)
	private void vrcamera$drawDirector(boolean renderLevel, CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		PhotoAlbum.INSTANCE.frameWithoutVR(mc);
		DirectorPass.onFrame(mc, mc.getDeltaTracker(), renderLevel);
	}

	// the use key takes the camera a player without VR points at, it does not also eat or place a block
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$useKeyTakesCamera(CallbackInfo ci) {
		if (DesktopCamera.INSTANCE.wantsUseKey()) {
			ci.cancel();
		}
	}

	// the attack key picks the free camera a player without VR points at, it does not also hit what is behind it
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$attackKeyPicksCamera(CallbackInfoReturnable<Boolean> cir) {
		if (DesktopCamera.INSTANCE.select()) {
			cir.setReturnValue(true);
		}
	}

	// and held on a camera, it does not start on the block behind it either: no arm that swings
	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$attackKeyStaysOnCamera(boolean leftClick, CallbackInfo ci) {
		if (leftClick && DesktopCamera.INSTANCE.pointsAtFreeCamera()) {
			ci.cancel();
		}
	}
}
