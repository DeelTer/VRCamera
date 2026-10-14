package ru.deelter.vrcamera.mixin.client;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.deelter.vrcamera.client.desktop.CameraHud;

/**
 * What the game draws over the HUD is only for the player: in the picture of the camera there are no menus,
 * loading screens, toasts or the saving sign
 */
@Mixin(Gui.class)
public class GuiMixin {
	@WrapWithCondition(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"), require = 0)
	private boolean vrcamera$noMenuForCamera(Screen screen, GuiGraphicsExtractor graphics, int x, int y, float ticks) {
		return !CameraHud.isCameraPicture();
	}

	@WrapWithCondition(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Overlay;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"), require = 0)
	private boolean vrcamera$noOverlayForCamera(Overlay overlay, GuiGraphicsExtractor graphics, int x, int y,
	                                            float ticks) {
		return !CameraHud.isCameraPicture();
	}

	@WrapWithCondition(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Hud;extractSavingIndicator(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"), require = 0)
	private boolean vrcamera$noSavingForCamera(Hud hud, GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		return !CameraHud.isCameraPicture();
	}

	@WrapWithCondition(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/toasts/ToastManager;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V"), require = 0)
	private boolean vrcamera$noToastsForCamera(ToastManager toasts, GuiGraphicsExtractor graphics) {
		return !CameraHud.isCameraPicture();
	}

	@WrapWithCondition(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;drawDebugMouseInfo(Lnet/minecraft/client/gui/Font;Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V"), require = 0)
	private boolean vrcamera$noMouseForCamera(MouseHandler mouse, Font font, GuiGraphicsExtractor graphics) {
		return !CameraHud.isCameraPicture();
	}
}
