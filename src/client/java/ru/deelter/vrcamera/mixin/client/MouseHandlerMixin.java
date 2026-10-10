package ru.deelter.vrcamera.mixin.client;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ru.deelter.vrcamera.client.desktop.DesktopCamera;
import ru.deelter.vrcamera.client.desktop.OutputWindow;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$wheelMovesCamera(long window, double horizontal, double vertical, CallbackInfo ci) {
		if (OutputWindow.onScroll(window, vertical) || DesktopCamera.INSTANCE.scroll(vertical)) {
			ci.cancel();
		}
	}
}
