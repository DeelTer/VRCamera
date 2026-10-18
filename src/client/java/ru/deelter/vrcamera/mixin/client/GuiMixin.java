package ru.deelter.vrcamera.mixin.client;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.vrcamera.client.desktop.CameraHud;
import ru.deelter.vrcamera.client.desktop.ChromaKey;

@Mixin(Gui.class)
public class GuiMixin {
	@Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$noVignetteOverGreen(CallbackInfo ci) {
		if (ChromaKey.isOn() || CameraHud.isCameraPicture()) {
			ci.cancel();
		}
	}

	@Inject(method = {"renderCrosshair", "renderChat",
			"renderScoreboardSidebar", "renderSubtitleOverlay", "renderCameraOverlays", "renderSleepOverlay",
			"renderDemoOverlay", "renderDebugOverlay"}, at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$onlyForThePlayer(CallbackInfo ci) {
		if (CameraHud.isCameraPicture()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderTabList", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$tabForCamera(CallbackInfo ci) {
		if (CameraHud.isCameraPicture() && !CameraHud.showsTab()) {
			ci.cancel();
		}
	}

	@Inject(method = {"renderHotbarAndDecorations", "renderEffects", "renderBossOverlay", "renderTitle"}, at = @At("HEAD"),
			cancellable = true, require = 0)
	private void vrcamera$onlyTheTab(CallbackInfo ci) {
		if (CameraHud.isCameraPicture() && !CameraHud.showsHud()) {
			ci.cancel();
		}
	}

	@Shadow
	private Component overlayMessageString;

	@Inject(method = "renderOverlayMessage", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$serverMessagesForCamera(CallbackInfo ci) {
		if (CameraHud.isCameraPicture() && (!CameraHud.showsHud() || CameraHud.isOwn(overlayMessageString))) {
			ci.cancel();
		}
	}
}
