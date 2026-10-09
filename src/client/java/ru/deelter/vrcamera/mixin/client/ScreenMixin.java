package ru.deelter.vrcamera.mixin.client;

import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.vrcamera.client.desktop.DesktopGui;

@Mixin(Screen.class)
public class ScreenMixin {
	@Inject(method = "renderTransparentBackground", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$noDimInWorld(CallbackInfo ci) {
		if (DesktopGui.isDrawing()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderBlurredBackground", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$noBlurInWorld(CallbackInfo ci) {
		if (DesktopGui.isDrawing()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderMenuBackground(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$noSheetInWorld(CallbackInfo ci) {
		if (DesktopGui.isDrawing()) {
			ci.cancel();
		}
	}
}
