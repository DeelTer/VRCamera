package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.fog.FogRenderer;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.mixin.client.GameRendererAccessor;

/**
 * Draws the HUD of the player over the picture of the camera that was just drawn, see {@link CameraHud}. Once it
 * fails it stays away, the picture is worth more than the HUD on it
 */
final class HudPicture {
	private static boolean broken;

	private HudPicture() {
	}

	static void draw(Minecraft mc, DeltaTracker deltaTracker, RenderTarget target) {
		if (broken || !CameraHud.shown()) {
			return;
		}
		try (final HudWindow ignored = HudWindow.fit(mc, DirectorPass.shownShape(target), target)) {
			final GameRendererAccessor renderer = (GameRendererAccessor) mc.gameRenderer;
			RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(target.getDepthTexture(), 1.0);
			mc.gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_3D);
			renderer.vrcamera$guiRenderState().reset();
			mc.gui.render(new GuiGraphics(mc, renderer.vrcamera$guiRenderState(), -1, -1), deltaTracker);
			renderer.vrcamera$guiRenderer().render(renderer.vrcamera$fogRenderer().getBuffer(FogRenderer.FogMode.NONE));
			renderer.vrcamera$guiRenderer().incrementFrameNumber();
		} catch (RuntimeException | LinkageError e) {
			broken = true;
			Vrcamera.LOGGER.error("VRCamera: the HUD can't be drawn for the camera, it films without it", e);
		}
	}
}
