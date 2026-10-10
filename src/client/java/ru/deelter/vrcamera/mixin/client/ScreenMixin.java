package ru.deelter.vrcamera.mixin.client;

import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ru.deelter.vrcamera.client.desktop.DesktopGui;

@Mixin(Screen.class)
public class ScreenMixin {
	@Inject(method = "extractTransparentBackground", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$noDimInWorld(CallbackInfo ci) {
		if (DesktopGui.isDrawing()) {
			ci.cancel();
		}
	}

	@Inject(method = "extractBlurredBackground", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$noBlurInWorld(CallbackInfo ci) {
		if (DesktopGui.isDrawing()) {
			ci.cancel();
		}
	}

	@Inject(method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$noSheetInWorld(CallbackInfo ci) {
		if (DesktopGui.isDrawing()) {
			ci.cancel();
		}
	}
}
