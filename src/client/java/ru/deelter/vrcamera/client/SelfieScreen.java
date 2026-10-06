package ru.deelter.vrcamera.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.NonNull;
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
	// In sixteenths of the model, like the model itself is made: as wide as the screen on its back, standing on
	// top of it. The lens of the model looks to the north
	private static final float LEFT = 3.733F / 16.0F;
	private static final float RIGHT = 12.266F / 16.0F;
	private static final float BOTTOM = 8.4F / 16.0F;
	private static final float TOP = 13.2F / 16.0F;
	private static final float DEPTH = 8.0F / 16.0F;
	private static final int FULL_LIGHT = 0xF000F0;

	private static boolean broken;

	private SelfieScreen() {
	}

	/**
	 * @param viewPosition where the pass looks from, the pose stack is relative to that
	 * @param model        how Vivecraft places the model of the camera
	 */
	public static void render(
			SubmitNodeCollector output, Vec3 viewPosition, Vec3 cameraPosition, Matrix4f model, PoseStack poseStack) {
		ClientDataHolderVR dh = ClientDataHolderVR.getInstance();
		// only for the eyes of the player: in the picture of the camera it would be a screen showing itself
		if (broken || (dh.currentPass != RenderPass.LEFT && dh.currentPass != RenderPass.RIGHT) ||
				!CameraController.INSTANCE.showsSelfieScreen()) {
			return;
		}
		try {
			poseStack.pushPose();
			poseStack.translate(cameraPosition.x - viewPosition.x, cameraPosition.y - viewPosition.y,
					cameraPosition.z - viewPosition.z);
			poseStack.mulPose(model);
			poseStack.translate(-0.5F, -0.5F, -0.5F);
			output.submitCustomGeometry(poseStack, VRRenderTypes.entitySolidNoCardinalLight(
					dh.vrRenderer.cameraFramebuffer.getColorTextureView(), true), (pose, consumer) -> {
				// Like a mirror: what is on the right of the player is on the right of the screen. Drawn from both
				// sides, whichever of them the game takes for the front
				vertex(consumer, pose, LEFT, TOP, 0, 1);
				vertex(consumer, pose, RIGHT, TOP, 1, 1);
				vertex(consumer, pose, RIGHT, BOTTOM, 1, 0);
				vertex(consumer, pose, LEFT, BOTTOM, 0, 0);
				vertex(consumer, pose, LEFT, BOTTOM, 0, 0);
				vertex(consumer, pose, RIGHT, BOTTOM, 1, 0);
				vertex(consumer, pose, RIGHT, TOP, 1, 1);
				vertex(consumer, pose, LEFT, TOP, 0, 1);
			});
			poseStack.popPose();
		} catch (RuntimeException | LinkageError e) {
			// drawn with parts of Vivecraft that are not made for others to use, and may change
			broken = true;
			Vrcamera.LOGGER.error("VRCamera: the selfie screen can't be drawn, it is off until the game restarts", e);
		}
	}

	private static void vertex(@NonNull VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float u, float v) {
		consumer.addVertex(pose, x, y, DEPTH)
				.setColor(1.0F, 1.0F, 1.0F, 1.0F)
				.setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(FULL_LIGHT)
				.setNormal(0.0F, 1.0F, 0.0F);
	}
}
