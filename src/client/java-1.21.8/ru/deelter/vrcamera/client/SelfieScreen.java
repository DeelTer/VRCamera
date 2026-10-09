package ru.deelter.vrcamera.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.vivecraft.api.client.data.RenderPass;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.render.rendertypes.VRRenderTypes;
import ru.deelter.vrcamera.Vrcamera;

/**
 * A second screen on top of a camera that is held with its lens to the player, like the one a camera for
 * filming oneself flips up: the screen of the model is on its back, where it can't be seen then.
 * <p>
 * Costs next to nothing. It shows the picture the camera renders anyway, on one more rectangle.
 */
public final class SelfieScreen {
	private static final float LEFT = 3.733F / 16.0F;
	private static final float RIGHT = 12.266F / 16.0F;
	private static final float BOTTOM = 8.4F / 16.0F;
	private static final float TOP = 13.2F / 16.0F;
	private static final float DEPTH = 8.0F / 16.0F;

	private static boolean broken;

	private SelfieScreen() {
	}

	/**
	 * Drawn right away, the way Vivecraft draws the screen of the model of the camera in this version of the game.
	 *
	 * @param poseStack where Vivecraft has the model
	 */
	public static void render(PoseStack poseStack) {
		final ClientDataHolderVR dh = ClientDataHolderVR.getInstance();
		if (broken || (dh.currentPass != RenderPass.LEFT && dh.currentPass != RenderPass.RIGHT) ||
				!CameraController.INSTANCE.showsSelfieScreen()) {
			return;
		}
		try {
			final MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
			final RenderType layer = VRRenderTypes.entitySolidNoCardinalLight(
					dh.vrRenderer.cameraFramebuffer.getColorTextureView());
			final VertexConsumer consumer = buffers.getBuffer(layer);
			final PoseStack.Pose pose = poseStack.last();
			vertex(consumer, pose, LEFT, TOP, 0, 1);
			vertex(consumer, pose, RIGHT, TOP, 1, 1);
			vertex(consumer, pose, RIGHT, BOTTOM, 1, 0);
			vertex(consumer, pose, LEFT, BOTTOM, 0, 0);
			vertex(consumer, pose, LEFT, BOTTOM, 0, 0);
			vertex(consumer, pose, RIGHT, BOTTOM, 1, 0);
			vertex(consumer, pose, RIGHT, TOP, 1, 1);
			vertex(consumer, pose, LEFT, TOP, 0, 1);
			buffers.endBatch(layer);
		} catch (RuntimeException | LinkageError e) {
			broken = true;
			Vrcamera.LOGGER.error("VRCamera: the selfie screen can't be drawn, it is off until the game restarts", e);
		}
	}

	private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float u, float v) {
		consumer.addVertex(pose, x, y, DEPTH)
				.setColor(1.0F, 1.0F, 1.0F, 1.0F)
				.setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(LightTexture.FULL_BRIGHT)
				.setNormal(pose, 0.0F, 1.0F, 0.0F);
	}
}
