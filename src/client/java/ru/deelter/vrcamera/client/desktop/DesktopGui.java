package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.Vive;
import ru.deelter.vrcamera.client.Vr;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.math.CamMath;
import ru.deelter.vrcamera.client.rig.Subject;
import ru.deelter.vrcamera.mixin.client.ContainerScreenAccessor;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The menu a player without VR has open, as something in the world: a screen that stands in front of them, for the
 * camera to look at over their shoulder the way it does in VR. Only the camera sees it. The player has the menu
 * on their screen as always.
 * <p>
 * The game draws the menu once more for that, alone and into a picture of its own, and that picture is put on a
 * rectangle in front of the player while the camera films.
 */
public final class DesktopGui {
	private static final double DISTANCE = 0.85;
	private static final double DROP = 0.12;

	private static final float WIDTH = 1.1F;
	private static final float HEIGHT = 0.7F;

	private static final double WALL_GAP = 0.15;
	private static final double MIN_DISTANCE = 0.35;

	private static final int FRAME_MARGIN = 8;

	private static final int CONTAINER_MARGIN = 34;

	private static final double WIDGETS_PART = 0.4;

	private static final Vector4fc BACKGROUND = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
	private static final int FULL_LIGHT = 0xF000F0;

	private static final Set<GuiEventListener> LEFT_OUT = Collections.newSetFromMap(new WeakHashMap<>());
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(Vrcamera.MOD_ID, "menu");
	private static RenderTarget target;
	private static TargetTexture texture;
	private static boolean drawing;
	private static boolean broken;
	private static boolean drawn;

	private static Vec3 center;
	private static double facing;
	private static float size = 1.0F;

	private static float frameLeft;
	private static float frameRight = 1.0F;
	private static float frameBottom;
	private static float frameTop = 1.0F;

	private DesktopGui() {
	}

	/**
	 * @return if what the game draws right now is the menu alone, for the screen in the world
	 */
	public static boolean isDrawing() {
		return drawing;
	}

	/**
	 * @return the middle of the screen in the world, null without a menu that is worth a look
	 */
	@Nullable
	public static Vec3 place(Minecraft mc, Subject subject, CameraConfig config) {
		final Screen screen = mc.gui.screen();

		if (broken || screen == null || (screen instanceof ChatScreen && !config.menuShotChat)) {
			center = null;
			drawn = false;
			return null;
		}
		if (center == null) {
			facing = subject.facing;
		}

		final Vec3 ahead = CamMath.forward(facing);

		double distance = DISTANCE * subject.unit;
		final BlockHitResult wall = subject.player.level().clip(new ClipContext(subject.head,
				subject.head.add(ahead.scale(distance + WALL_GAP)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
				subject.player));
		if (wall.getType() != HitResult.Type.MISS) {
			distance = Math.max(MIN_DISTANCE * subject.unit, wall.getLocation().distanceTo(subject.head) - WALL_GAP);
		}
		center = subject.head.add(ahead.scale(distance)).add(0, -DROP * subject.unit, 0);
		size = (float) (Math.clamp(config.menuSize, 0.25, 4.0) * subject.unit);
		frame(screen);
		return center;
	}

	/**
	 * @param widget something in a menu that is not part of it, and has no say in where the menu is
	 */
	public static void leaveOut(GuiEventListener widget) {
		LEFT_OUT.add(widget);
	}

	/**
	 * draws the menu alone, into the picture that goes on the screen in the world
	 */
	public static void draw(Minecraft mc, DeltaTracker deltaTracker, RenderTarget own) {
		if (center == null || broken) {
			return;
		}
		try {
			if (target == null) {
				target = new MainTarget(own.width, own.height);
			} else if (target.width != own.width || target.height != own.height) {
				target.resize(own.width, own.height);
			}
			GameFrame.begin();
			drawing = true;
			DirectorPass.setTarget(mc, target);
			RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(),
					BACKGROUND, target.getDepthTexture(), 0.0);
			try (final Gizmos.TemporaryCollection ignored = mc.levelExtractor.collectPerFrameMainThreadGizmos()) {
				mc.gameRenderer.extract(deltaTracker, false);
			}
			try (final Gizmos.TemporaryCollection ignored = mc.levelRenderer.collectPerFrameRenderThreadGizmos()) {
				GameFrame.render(mc, deltaTracker, false);
			}
			mc.gameRenderer.renderBuffers().endFrame();
			drawn = true;
		} catch (RuntimeException | LinkageError e) {
			broken = true;
			center = null;
			Vrcamera.LOGGER.error("VRCamera: the menu can't be put into the world, the camera films without it", e);
		} finally {
			drawing = false;
			DirectorPass.setTarget(mc, own);
		}
	}

	/**
	 * puts the screen into the picture of the camera
	 *
	 * @param viewPosition where the pass looks from, the pose stack is relative to that
	 */
	public static void render(SubmitNodeCollector output, Vec3 viewPosition, PoseStack poseStack) {
		if (center == null || !drawn || broken || target == null) {
			return;
		}
		try {
			final Vec3 right = new Vec3(-Math.cos(facing), 0, -Math.sin(facing));

			final float shape = (frameRight - frameLeft) * target.width /
					Math.max(1.0F, (frameTop - frameBottom) * target.height);
			final float halfHeight = Math.min(HEIGHT, WIDTH / shape) * size / 2.0F;
			final float halfWidth = halfHeight * shape;
			final Vec3 from = center.subtract(viewPosition);
			poseStack.pushPose();

			output.submitCustomGeometry(poseStack, layer(), (pose, consumer) -> {
				corner(consumer, pose, from, right, -halfWidth, halfHeight, frameLeft, frameTop);
				corner(consumer, pose, from, right, halfWidth, halfHeight, frameRight, frameTop);
				corner(consumer, pose, from, right, halfWidth, -halfHeight, frameRight, frameBottom);
				corner(consumer, pose, from, right, -halfWidth, -halfHeight, frameLeft, frameBottom);
				corner(consumer, pose, from, right, -halfWidth, -halfHeight, frameLeft, frameBottom);
				corner(consumer, pose, from, right, halfWidth, -halfHeight, frameRight, frameBottom);
				corner(consumer, pose, from, right, halfWidth, halfHeight, frameRight, frameTop);
				corner(consumer, pose, from, right, -halfWidth, halfHeight, frameLeft, frameTop);
			});
			poseStack.popPose();
		} catch (RuntimeException | LinkageError e) {
			broken = true;
			Vrcamera.LOGGER.error("VRCamera: the menu can't be drawn in the world, the camera films without it", e);
		}
	}

	public static void close() {
		center = null;
		drawn = false;
		if (texture != null) {
			Minecraft.getInstance().getTextureManager().release(TEXTURE);
			texture = null;
		}
		if (target != null) {
			target.destroyBuffers();
			target = null;
		}
	}

	/**
	 * Finds the part of the game window the menu is in: around everything that can be clicked, and the window of
	 * an inventory. The rest of the game window is empty, and is left off the screen in the world
	 */
	private static void frame(Screen screen) {
		int left = screen.width;
		int top = screen.height;
		int right = 0;
		int bottom = 0;
		for (final GuiEventListener part : screen.children()) {
			final ScreenRectangle box = part.getRectangle();
			if (box.width() > 0 && box.height() > 0 && !LEFT_OUT.contains(part)) {
				left = Math.min(left, box.left());
				top = Math.min(top, box.top());
				right = Math.max(right, box.right());
				bottom = Math.max(bottom, box.bottom());
			}
		}
		if (screen instanceof AbstractContainerScreen<?> container) {
			final ContainerScreenAccessor window = (ContainerScreenAccessor) container;
			left = Math.min(left, window.vrcamera$left());
			top = Math.min(top, window.vrcamera$top());
			right = Math.max(right, window.vrcamera$left() + window.vrcamera$width());
			bottom = Math.max(bottom, window.vrcamera$top() + window.vrcamera$height());
		}

		final boolean drawn = !(screen instanceof AbstractContainerScreen<?>) &&
				bottom - top < screen.height * WIDGETS_PART;
		if (right <= left || bottom <= top || screen instanceof ChatScreen || drawn || screen.width < 1 ||
				screen.height < 1) {
			frameLeft = 0;
			frameRight = 1;
			frameBottom = 0;
			frameTop = 1;
			return;
		}
		final int margin = screen instanceof AbstractContainerScreen<?> ? CONTAINER_MARGIN : FRAME_MARGIN;
		frameLeft = Math.max(0, left - margin) / (float) screen.width;
		frameRight = Math.min(screen.width, right + margin) / (float) screen.width;

		frameTop = 1.0F - Math.max(0, top - margin) / (float) screen.height;
		frameBottom = 1.0F - Math.min(screen.height, bottom + margin) / (float) screen.height;
	}

	/**
	 * @return how the picture of the menu is drawn onto the screen in the world. With Vivecraft the way Vivecraft
	 * draws its own screens, without it as a texture of the game like any other
	 */
	private static RenderType layer() {
		if (Vr.INSTALLED) {
			return Vive.pictureLayer(target);
		}
		if (texture == null) {
			texture = new TargetTexture();
			Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
		}
		texture.show(target);
		return RenderTypes.text(TEXTURE);
	}

	private static void corner(
			VertexConsumer consumer, PoseStack.Pose pose, Vec3 middle, Vec3 right, float along, float up, float u,
			float v) {
		consumer.addVertex(pose, (float) (middle.x + right.x * along), (float) (middle.y + up),
						(float) (middle.z + right.z * along))
				.setColor(1.0F, 1.0F, 1.0F, 1.0F)
				.setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(FULL_LIGHT)
				.setNormal(0.0F, 1.0F, 0.0F);
	}
}
