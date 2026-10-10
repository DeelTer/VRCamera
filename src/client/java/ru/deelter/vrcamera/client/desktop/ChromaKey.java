package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.Vive;
import ru.deelter.vrcamera.client.Vr;
import ru.deelter.vrcamera.client.config.CameraConfig;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Objects;

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
	public static final int EVEN_LIGHT = 0xF000F0;
	private static final Vector4fc NOTHING = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
	private static final double FAR = 0.0;
	private static final double NEAR = 1.0;
	private static final Int2LongOpenHashMap SEEN = new Int2LongOpenHashMap();
	private static final long SEEN_NANOS = 100_000_000L;
	private static final int SEEN_MOST = 1024;
	private static final Vector4f color = new Vector4f(0.0F, 0xB1 / 255.0F, 0x40 / 255.0F, 1.0F);
	private static Preset preset = Preset.CONFIG;
	private static boolean enabled;
	private static boolean broken;
	private static Boolean shadowsBefore;
	private static String colorText;

	private ChromaKey() {
	}

	public static boolean isOn() {
		return enabled;
	}

	public static Preset preset() {
		return preset;
	}

	/**
	 * the next step of the button that works it: on, through its colours, and off after the last one
	 */
	public static void cycle() {
		final Preset[] presets = Preset.values();
		if (!enabled) {
			preset = presets[0];
			set(true);
		} else if (preset == presets[presets.length - 1]) {
			set(false);
		} else {
			preset = presets[preset.ordinal() + 1];
		}
	}

	/**
	 * Without the world in the way, every entity around would be in the picture: the cows behind the hill, the
	 * bats in the caves below.
	 *
	 * @return if that entity belongs into it: near the player, and not behind blocks as the camera sees it
	 */
	public static boolean shows(Entity entity) {
		final Minecraft mc = Minecraft.getInstance();
		final LocalPlayer player = mc.player;
		if (player == null || entity == player || entity.getRootVehicle() == player.getRootVehicle()) {
			return true;
		}
		final double reach = CameraConfig.current().chromaDistance;
		if (reach > 0 && entity.distanceToSqr(player) > reach * reach) {
			return false;
		}

		final long now = System.nanoTime();
		final long known = SEEN.get(entity.getId());
		if (known != 0 && now - Math.abs(known) < SEEN_NANOS) {
			return known > 0;
		}
		final Vec3 lens = mc.gameRenderer.mainCamera().position();
		final boolean seen = sees(entity, lens, entity.getBoundingBox().getCenter()) ||
				sees(entity, lens, entity.getEyePosition());
		if (SEEN.size() >= SEEN_MOST) {
			SEEN.clear();
		}
		SEEN.put(entity.getId(), seen ? now : -now);
		return seen;
	}

	public static void set(boolean value) {
		final Minecraft mc = Minecraft.getInstance();
		if (value == enabled) {
			return;
		}
		enabled = value;
		broken = false;
		SEEN.clear();

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
		if (DirectorPass.drawsEntitiesAlone()) {
			return !broken;
		}
		if (!enabled || broken) {
			return false;
		}
		if (Vr.isRunning()) {
			return Vive.isCameraPass();
		}

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
	 * {@link #paintOver} for where the game is in the middle of drawing into the picture: newer versions draw the
	 * world and the entities in one go
	 */
	public static void paintOverWhileDrawing() {
		clearWhileDrawing(true);
	}

	/**
	 * {@link #shutOut} for where the game is in the middle of drawing into the picture
	 */
	public static void shutOutWhileDrawing() {
		clearWhileDrawing(false);
	}

	/**
	 * @return the colour as RGB, the default if it is not six hex digits with or without a # in front
	 */
	public static int parse(String text) {
		final String hex = text.trim().startsWith("#") ? text.trim().substring(1) : text.trim();
		if (hex.matches("[0-9a-fA-F]{6}")) {
			return Integer.parseInt(hex, 16);
		}
		return Integer.parseInt(DEFAULT_COLOR.substring(1), 16);
	}

	private static boolean sees(Entity entity, Vec3 from, Vec3 to) {
		return entity.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
				CollisionContext.empty())).getType() == HitResult.Type.MISS;
	}

	/**
	 * @return if entities are lit the same wherever they stand. Not in a picture of the entities alone that is no
	 * green screen: that one is put over the picture of the world, and has to look like it
	 */
	public static boolean lightsEvenly() {
		return applies() && !DirectorPass.drawsEntitiesAlone();
	}

	/**
	 * @return the colour of the settings, the default green if what is written there is not one
	 */
	private static Vector4fc background() {
		if (DirectorPass.drawsEntitiesAlone()) {
			return NOTHING;
		}
		final String text = preset.color == null ? CameraConfig.current().chromaColor : preset.color;
		if (!Objects.equals(text, colorText)) {
			colorText = text;
			final int rgb = parse(text == null ? "" : text);
			color.set((rgb >> 16 & 0xFF) / 255.0F, (rgb >> 8 & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F, 1.0F);
		}
		return color;
	}

	private static void clear(boolean color) {
		if (!applies()) {
			return;
		}
		try {
			final RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			if (color) {
				RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(),
						background(), target.getDepthTexture(), FAR);
			} else {
				RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(target.getDepthTexture(), NEAR);
			}
		} catch (RuntimeException | LinkageError e) {
			failed(e);
		}
	}

	/**
	 * While the game draws into a picture it can't be asked to clear it, that is not what it is in the middle of.
	 * OpenGL is told directly then. The game keeps track of what it told OpenGL: everything that is changed for
	 * the clear is put back the way it was.
	 */
	private static void clearWhileDrawing(boolean color) {
		if (!applies()) {
			return;
		}
		try (final MemoryStack stack = MemoryStack.stackPush()) {
			final boolean scissors = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
			final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
			final double clearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
			final ByteBuffer colorMask = stack.malloc(4);
			final FloatBuffer clearColor = stack.mallocFloat(4);
			GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);
			GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);

			GL11.glDisable(GL11.GL_SCISSOR_TEST);
			GL11.glDepthMask(true);
			if (color) {
				final Vector4fc green = background();
				GL11.glColorMask(true, true, true, true);
				GL11.glClearColor(green.x(), green.y(), green.z(), green.w());
				GL11.glClearDepth(FAR);
				GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
			} else {
				GL11.glClearDepth(NEAR);
				GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
			}

			if (scissors) {
				GL11.glEnable(GL11.GL_SCISSOR_TEST);
			}
			GL11.glDepthMask(depthMask);
			GL11.glClearDepth(clearDepth);
			GL11.glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0,
					colorMask.get(3) != 0);
			GL11.glClearColor(clearColor.get(0), clearColor.get(1), clearColor.get(2), clearColor.get(3));
		} catch (RuntimeException | LinkageError e) {
			failed(e);
		}
	}

	private static void failed(Throwable cause) {
		broken = true;
		Vrcamera.LOGGER.error("VRCamera: the green screen can't be drawn, it is off until it is turned on again",
				cause);
	}

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
}
