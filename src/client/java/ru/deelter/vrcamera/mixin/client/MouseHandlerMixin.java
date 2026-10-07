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

	// while the camera is held with the mouse the wheel moves it away and back, and leaves the hotbar alone
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$wheelMovesCamera(long window, double horizontal, double vertical, CallbackInfo ci) {
		// where every window tells the game of its wheel, the one of the camera does as well: not for the game
		if (OutputWindow.onScroll(window, vertical) || DesktopCamera.INSTANCE.scroll(vertical)) {
			ci.cancel();
		}
	}
}
