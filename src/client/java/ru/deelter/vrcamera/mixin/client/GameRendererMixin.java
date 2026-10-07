package ru.deelter.vrcamera.mixin.client;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.vrcamera.client.desktop.DesktopGui;
import ru.deelter.vrcamera.client.desktop.DirectorPass;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

	// the picture of the camera is the world alone: no hotbar, no crosshair, no menu
	@WrapWithCondition(method = "extract", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V"), require = 0)
	private boolean vrcamera$noHudForCamera(Gui gui, DeltaTracker deltaTracker, boolean renderLevel, boolean loaded) {
		// only when the menu is drawn alone, for the screen that stands in front of the player
		return !DirectorPass.isActive() || DesktopGui.isDrawing();
	}

	// the sway of a walking or hurt player is theirs, not the camera's
	@Inject(method = "bobView", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$noBobForCamera(CallbackInfo ci) {
		if (DirectorPass.isActive()) {
			ci.cancel();
		}
	}

	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true, require = 0)
	private void vrcamera$noHurtBobForCamera(CallbackInfo ci) {
		if (DirectorPass.isActive()) {
			ci.cancel();
		}
	}
}
