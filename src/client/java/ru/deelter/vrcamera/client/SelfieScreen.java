package ru.deelter.vrcamera.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;
import org.vivecraft.api.client.data.RenderPass;
import org.vivecraft.client_vr.ClientDataHolderVR;
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

	private static boolean broken;

	private SelfieScreen() {
	}

	/**
	 * Drawn right away, the way Vivecraft draws the model of the camera in this version of the game.
	 *
	 * @param poseStack where Vivecraft has the model
	 */
	public static void render(PoseStack poseStack) {
		ClientDataHolderVR dh = ClientDataHolderVR.getInstance();
		// only for the eyes of the player: in the picture of the camera it would be a screen showing itself
		if (broken || (dh.currentPass != RenderPass.LEFT && dh.currentPass != RenderPass.RIGHT) ||
				!CameraController.INSTANCE.showsSelfieScreen()) {
			return;
		}
		try {
			LightTexture light = Minecraft.getInstance().gameRenderer.lightTexture();
			Matrix4f pose = poseStack.last().pose();
			RenderSystem.setShaderTexture(0, dh.vrRenderer.cameraFramebuffer.getColorTextureId());
			RenderSystem.setShader(CoreShaders.RENDERTYPE_ENTITY_SOLID);
			light.turnOnLightLayer();
			RenderSystem.disableBlend();
			BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
			// Like a mirror: what is on the right of the player is on the right of the screen. Drawn from both
			// sides, whichever of them the game takes for the front
			vertex(buffer, pose, LEFT, TOP, 0, 1);
			vertex(buffer, pose, RIGHT, TOP, 1, 1);
			vertex(buffer, pose, RIGHT, BOTTOM, 1, 0);
			vertex(buffer, pose, LEFT, BOTTOM, 0, 0);
			vertex(buffer, pose, LEFT, BOTTOM, 0, 0);
			vertex(buffer, pose, RIGHT, BOTTOM, 1, 0);
			vertex(buffer, pose, RIGHT, TOP, 1, 1);
			vertex(buffer, pose, LEFT, TOP, 0, 1);
			BufferUploader.drawWithShader(buffer.buildOrThrow());
			RenderSystem.enableBlend();
			light.turnOffLightLayer();
		} catch (RuntimeException | LinkageError e) {
			// drawn with parts of Vivecraft that are not made for others to use, and may change
			broken = true;
			Vrcamera.LOGGER.error("VRCamera: the selfie screen can't be drawn, it is off until the game restarts", e);
		}
	}

	private static void vertex(BufferBuilder buffer, Matrix4f pose, float x, float y, float u, float v) {
		buffer.addVertex(pose, x, y, DEPTH)
				.setColor(1.0F, 1.0F, 1.0F, 1.0F)
				.setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(LightTexture.FULL_BRIGHT)
				.setNormal(0.0F, 1.0F, 0.0F);
	}
}
