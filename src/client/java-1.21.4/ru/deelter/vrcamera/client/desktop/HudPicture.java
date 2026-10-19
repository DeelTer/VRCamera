package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;
import ru.deelter.vrcamera.Vrcamera;

/**
 * Draws the HUD of the player over the picture of the camera that was just drawn, see {@link CameraHud}. Once it
 * fails it stays away, the picture is worth more than the HUD on it
 */
final class HudPicture {
	private static final float GUI_NEAR = 1000.0F;
	private static final float GUI_FAR = 21000.0F;
	private static final float GUI_DEPTH = -11000.0F;
	private static boolean broken;

	private HudPicture() {
	}

	static void draw(Minecraft mc, DeltaTracker deltaTracker, RenderTarget target) {
		if (broken || !CameraHud.shown()) {
			return;
		}
		final Matrix4fStack modelView = RenderSystem.getModelViewStack();
		RenderSystem.backupProjectionMatrix();
		modelView.pushMatrix();
		try (final HudWindow ignored = HudWindow.fit(mc, DirectorPass.shownShape(target), target)) {
			target.bindWrite(true);
			RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT);
			final Window window = mc.getWindow();
			final double scale = window.getGuiScale();
			RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0.0F, (float) (window.getWidth() / scale),
					(float) (window.getHeight() / scale), 0.0F, GUI_NEAR, GUI_FAR), ProjectionType.ORTHOGRAPHIC);
			modelView.translation(0.0F, 0.0F, GUI_DEPTH);
			Lighting.setupFor3DItems();
			final GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
			mc.gui.render(graphics, deltaTracker);
			graphics.flush();
		} catch (RuntimeException | LinkageError e) {
			broken = true;
			Vrcamera.LOGGER.error("VRCamera: the HUD can't be drawn for the camera, it films without it", e);
		} finally {
			modelView.popMatrix();
			RenderSystem.restoreProjectionMatrix();
		}
	}
}
