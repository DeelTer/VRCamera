package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.vivecraft.api.client.data.RenderPass;
import org.vivecraft.client_vr.ClientDataHolderVR;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.CameraController;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Films entities in front of plain green, to cut them out of the picture later: the world, the sky and everything
 * else that is not an entity is left out.
 * <p>
 * Done with two clears in the middle of a frame instead of telling every part of the world not to draw itself,
 * which a renderer like Sodium would not listen to. Right before the entities are drawn, what is there of the
 * world is painted over. Right after them, the depth is set to as near as it gets: nothing that comes later
 * passes it.
 */
public final class ChromaKey {
	public static final String DEFAULT_COLOR = "#00B140";
	// what entities are lit with in front of it: full light from blocks and from the sky, the same at any hour
	public static final int EVEN_LIGHT = 0xF000F0;
	// the game keeps depth the other way around: 0 is far away, 1 is right at the lens
	private static final double FAR = 0.0;
	private static final double NEAR = 1.0;

	/**
	 * the colours to switch between while filming, without going to the settings
	 */
	public enum Preset {
		CONFIG(null), GREEN(DEFAULT_COLOR), BLUE("#0047BB"), RED("#FF0000");

		private final String color;

		Preset(String color) {
			this.color = color;
		}
	}

	private static Preset preset = Preset.CONFIG;
	private static boolean on;
	private static boolean broken;
	private static Boolean shadowsBefore;
	private static String colorText;
	private static final Vector4f color = new Vector4f(0.0F, 0xB1 / 255.0F, 0x40 / 255.0F, 1.0F);

	private ChromaKey() {
	}

	public static boolean isOn() {
		return on;
	}

	public static Preset preset() {
		return preset;
	}

	public static void nextPreset() {
		preset = Preset.values()[(preset.ordinal() + 1) % Preset.values().length];
	}

	/**
	 * Without the world in the way, every entity around would be in the picture: the cows behind the hill, the
	 * bats in the caves below.
	 *
	 * @return if that entity belongs into it: near the player, and not behind blocks as the camera sees it
	 */
	public static boolean shows(Entity entity) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || entity == player || entity.getRootVehicle() == player.getRootVehicle()) {
			return true;
		}
		double reach = CameraController.INSTANCE.config().chromaDistance;
		if (reach > 0 && entity.distanceToSqr(player) > reach * reach) {
			return false;
		}
		Vec3 lens = mc.gameRenderer.mainCamera().position();
		return sees(entity, lens, entity.getBoundingBox().getCenter()) || sees(entity, lens, entity.getEyePosition());
	}

	private static boolean sees(Entity entity, Vec3 from, Vec3 to) {
		return entity.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
				CollisionContext.empty())).getType() == HitResult.Type.MISS;
	}

	public static void set(boolean value) {
		Minecraft mc = Minecraft.getInstance();
		if (value == on) {
			return;
		}
		on = value;
		broken = false;
		// the round shadow under an entity is drawn on the ground, and there is no ground
		if (value) {
			shadowsBefore = mc.options.entityShadows().get();
			mc.options.entityShadows().set(false);
		} else if (shadowsBefore != null) {
			mc.options.entityShadows().set(shadowsBefore);
			shadowsBefore = null;
		}
	}

	/**
	 * @return if the picture that is being drawn right now is one to key. In VR that is only the one of the
	 * camera, the player still has to see where they are
	 */
	public static boolean applies() {
		if (!on || broken) {
			return false;
		}
		if (CameraController.isVRRunning()) {
			return ClientDataHolderVR.getInstance().currentPass == RenderPass.CAMERA;
		}
		// without VR the same goes for a camera with a window of its own. Filming into the game window, or with
		// no camera at all, the game window is what is keyed
		return DirectorPass.isActive() || !DesktopCamera.INSTANCE.hasOwnWindow();
	}

	/**
	 * before the entities: green over the world that was drawn so far
	 */
	public static void paintOver() {
		clear(true);
	}

	/**
	 * after the entities: nothing else gets into the picture
	 */
	public static void shutOut() {
		clear(false);
	}

	/**
	 * @return the colour of the settings, the default green if what is written there is not one
	 */
	private static Vector4fc background() {
		String text = preset.color == null ? CameraController.INSTANCE.config().chromaColor : preset.color;
		if (!java.util.Objects.equals(text, colorText)) {
			colorText = text;
			int rgb = parse(text == null ? "" : text);
			color.set((rgb >> 16 & 0xFF) / 255.0F, (rgb >> 8 & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F, 1.0F);
		}
		return color;
	}

	/**
	 * @return the colour as RGB, the default if it is not six hex digits with or without a # in front
	 */
	public static int parse(String text) {
		String hex = text.trim().startsWith("#") ? text.trim().substring(1) : text.trim();
		if (hex.matches("[0-9a-fA-F]{6}")) {
			return Integer.parseInt(hex, 16);
		}
		return Integer.parseInt(DEFAULT_COLOR.substring(1), 16);
	}

	private static void clear(boolean color) {
		if (!applies()) {
			return;
		}
		try {
			RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			if (color) {
				RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(),
						background(), target.getDepthTexture(), FAR);
			} else {
				RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(target.getDepthTexture(), NEAR);
			}
		} catch (RuntimeException | LinkageError e) {
			broken = true;
			Vrcamera.LOGGER.error("VRCamera: the green screen can't be drawn, it is off until it is turned on again", e);
		}
	}
}
