package ru.deelter.vrcamera.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.vrcamera.client.desktop.CameraHud;
import ru.deelter.vrcamera.client.desktop.DesktopGui;
import ru.deelter.vrcamera.client.desktop.DirectorPass;
import ru.deelter.vrcamera.client.desktop.PlayerEars;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@ModifyExpressionValue(method = "extractCamera", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getEffectiveRenderDistance()I"), require = 0)
	private int vrcamera$cameraReach(int chunks) {
		return DirectorPass.renderDistance(chunks);
	}

	@WrapWithCondition(method = "extract", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V"), require = 0)
	private boolean vrcamera$noHudForCamera(Gui gui, DeltaTracker deltaTracker, boolean renderLevel, boolean loaded) {
		return !DirectorPass.isActive() || DesktopGui.isDrawing() || CameraHud.shown();
	}

	/**
	 * The picture of the camera has a size of its own, and the game takes it for a window that changed size: it
	 * would throw away everything it keeps for drawing and make it anew, every picture
	 */
	@WrapWithCondition(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;resize(II)V"), require = 0)
	private boolean vrcamera$noResizeForCamera(GameRenderer renderer, int width, int height) {
		return !DirectorPass.isActive();
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/Profiler;get()Lnet/minecraft/util/profiling/ProfilerFiller;", ordinal = 0), require = 0)
	private void vrcamera$listenersAreDone(CallbackInfo ci) {
		PlayerEars.listenersDone();
	}

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
