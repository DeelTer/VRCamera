package ru.deelter.vrcamera.mixin.client;

import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ru.deelter.vrcamera.client.desktop.CameraHud;
import ru.deelter.vrcamera.client.desktop.ChromaKey;

@Mixin(Hud.class)
public class HudMixin {
	@Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$noVignetteOverGreen(CallbackInfo ci) {
		if (ChromaKey.isOn() || CameraHud.isCameraPicture()) {
			ci.cancel();
		}
	}

	@Inject(method = {"extractCrosshair", "extractChat", "extractOverlayMessage",
			"extractScoreboardSidebar", "extractSubtitleOverlay", "extractCameraOverlays", "extractSleepOverlay",
			"extractDemoOverlay", "extractDebugOverlay"}, at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$onlyForThePlayer(CallbackInfo ci) {
		if (CameraHud.isCameraPicture()) {
			ci.cancel();
		}
	}

	@Inject(method = "extractTabList", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$tabForCamera(CallbackInfo ci) {
		if (CameraHud.isCameraPicture() && !CameraHud.showsTab()) {
			ci.cancel();
		}
	}

	@Inject(method = {"extractHotbarAndDecorations", "extractEffects", "extractBossOverlay", "extractTitle"}, at = @At("HEAD"),
			cancellable = true, require = 0)
	private void vrcamera$onlyTheTab(CallbackInfo ci) {
		if (CameraHud.isCameraPicture() && !CameraHud.showsHud()) {
			ci.cancel();
		}
	}
}
