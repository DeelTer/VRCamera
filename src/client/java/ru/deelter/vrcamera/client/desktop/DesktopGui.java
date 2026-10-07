package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.compat.SubmitNodeCollector;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.math.CamMath;
import ru.deelter.vrcamera.client.rig.Subject;
import ru.deelter.vrcamera.mixin.client.ContainerScreenAccessor;
import ru.deelter.vrcamera.mixin.client.MinecraftAccessor;

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
	// blocks from the eyes to the screen, below them, and across it
	private static final double DISTANCE = 0.85;
	private static final double DROP = 0.12;
	// the box the screen fits into, in blocks
	private static final float WIDTH = 1.1F;
	private static final float HEIGHT = 0.7F;
	// blocks it keeps away from a wall behind it, and how close to the eyes it may get for that
	private static final double WALL_GAP = 0.15;
	private static final double MIN_DISTANCE = 0.35;
	// pixels of the menu around what is in it
	private static final int FRAME_MARGIN = 8;
	// an inventory has more around its window than it says: the tabs of the creative one, above and below
	private static final int CONTAINER_MARGIN = 34;
	// how far away the game has what it draws on the screen
	private static final float GUI_NEAR = 1000.0F;
	private static final float GUI_FAR = 21000.0F;
	private static final float GUI_DEPTH = -11000.0F;
	private static final int FULL_LIGHT = 0xF000F0;

	// widgets in a menu that are not part of it. They go when their menu does
	private static final Set<GuiEventListener> LEFT_OUT = Collections.newSetFromMap(new WeakHashMap<>());
	private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(Vrcamera.MOD_ID, "menu");
	private static RenderTarget target;
	private static TargetTexture texture;
	private static boolean drawing;
	private static boolean broken;
	private static boolean drawn;
	// where the screen stands, and which way the player faced when they opened the menu. It stays there while the
	// menu is open, like the one of Vivecraft
	private static Vec3 center;
	private static double facing;
	private static float size = 1.0F;
	// the part of the picture the menu is in, from 0 to 1
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
	public static Vec3 place(Minecraft mc, Subject subject, CameraConfig config) {
		Screen screen = mc.screen;
		// chat is often only open for a moment, not everyone wants a cut for that
		if (broken || screen == null || (screen instanceof ChatScreen && !config.menuShotChat)) {
			center = null;
			drawn = false;
			return null;
		}
		if (center == null) {
			facing = subject.facing;
		}
		// In front of the head wherever the head goes: a player who opens it in a jump lands, and the menu with
		// them. Only the way it faces stays as it was, a body turns a bit with every step
		Vec3 ahead = CamMath.forward(facing);
		// not into a wall the player stands in front of: closer, as close as it has to be
		double distance = DISTANCE * subject.unit;
		BlockHitResult wall = subject.player.level().clip(new ClipContext(subject.head,
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
	 * Finds the part of the game window the menu is in: around everything that can be clicked, and the window of
	 * an inventory. The rest of the game window is empty, and is left off the screen in the world
	 */
	private static void frame(Screen screen) {
		int left = screen.width;
		int top = screen.height;
		int right = 0;
		int bottom = 0;
		for (GuiEventListener part : screen.children()) {
			ScreenRectangle box = part.getRectangle();
			if (box.width() > 0 && box.height() > 0 && !LEFT_OUT.contains(part)) {
				left = Math.min(left, box.left());
				top = Math.min(top, box.top());
				right = Math.max(right, box.right());
				bottom = Math.max(bottom, box.bottom());
			}
		}
		if (screen instanceof AbstractContainerScreen<?> container) {
			ContainerScreenAccessor window = (ContainerScreenAccessor) container;
			left = Math.min(left, window.vrcamera$left());
			top = Math.min(top, window.vrcamera$top());
			right = Math.max(right, window.vrcamera$left() + window.vrcamera$width());
			bottom = Math.max(bottom, window.vrcamera$top() + window.vrcamera$height());
		}
		// chat is its lines, which are nothing that can be clicked
		if (right <= left || bottom <= top || screen instanceof ChatScreen || screen.width < 1 || screen.height < 1) {
			frameLeft = 0;
			frameRight = 1;
			frameBottom = 0;
			frameTop = 1;
			return;
		}
		int margin = screen instanceof AbstractContainerScreen<?> ? CONTAINER_MARGIN : FRAME_MARGIN;
		frameLeft = Math.max(0, left - margin) / (float) screen.width;
		frameRight = Math.min(screen.width, right + margin) / (float) screen.width;
		// the picture has its first line at the bottom, a screen counts from the top
		frameTop = 1.0F - Math.max(0, top - margin) / (float) screen.height;
		frameBottom = 1.0F - Math.min(screen.height, bottom + margin) / (float) screen.height;
	}

	/**
	 * draws the menu alone, into the picture that goes on the screen in the world
	 */
	public static void draw(Minecraft mc, DeltaTracker deltaTracker, RenderTarget own) {
		Screen screen = mc.screen;
		if (center == null || broken || screen == null) {
			return;
		}
		Matrix4fStack modelView = RenderSystem.getModelViewStack();
		RenderSystem.backupProjectionMatrix();
		modelView.pushMatrix();
		try {
			if (target == null) {
				target = new TextureTarget(own.width, own.height, true);
			} else if (target.width != own.width || target.height != own.height) {
				target.resize(own.width, own.height);
			}
			drawing = true;
			// a menu that asks the game for its picture, to draw into it again, gets this one
			((MinecraftAccessor) mc).vrcamera$setMainRenderTarget(target);
			// see-through: where the menu draws nothing, the world shows
			target.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
			target.clear();
			target.bindWrite(true);

			Window window = mc.getWindow();
			double scale = window.getGuiScale();
			RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0.0F, (float) (window.getWidth() / scale),
					(float) (window.getHeight() / scale), 0.0F, GUI_NEAR, GUI_FAR), ProjectionType.ORTHOGRAPHIC);
			modelView.translation(0.0F, 0.0F, GUI_DEPTH);
			Lighting.setupFor3DItems();
			GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
			int mouseX = (int) (mc.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth());
			int mouseY = (int) (mc.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight());
			screen.renderWithTooltip(graphics, mouseX, mouseY, deltaTracker.getGameTimeDeltaTicks());
			graphics.flush();
			drawn = true;
		} catch (RuntimeException | LinkageError e) {
			broken = true;
			center = null;
			Vrcamera.LOGGER.error("VRCamera: the menu can't be put into the world, the camera films without it", e);
		} finally {
			drawing = false;
			modelView.popMatrix();
			RenderSystem.restoreProjectionMatrix();
			((MinecraftAccessor) mc).vrcamera$setMainRenderTarget(own);
			own.bindWrite(true);
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
			// to the right of the player, as they stood when they opened it
			Vec3 right = new Vec3(-Math.cos(facing), 0, -Math.sin(facing));
			// As large as fits into a box of one size, whatever shape the menu has: a wide one is as wide as the
			// box, a high one as high
			float shape = (frameRight - frameLeft) * target.width /
					Math.max(1.0F, (frameTop - frameBottom) * target.height);
			float halfHeight = Math.min(HEIGHT, WIDTH / shape) * size / 2.0F;
			float halfWidth = halfHeight * shape;
			Vec3 from = center.subtract(viewPosition);
			// seen from both sides, and only where the menu is
			output.submitCustomGeometry(poseStack, layer(), (pose, consumer) -> {
				corner(consumer, pose, from, right, -halfWidth, halfHeight, frameLeft, frameTop);
				corner(consumer, pose, from, right, halfWidth, halfHeight, frameRight, frameTop);
				corner(consumer, pose, from, right, halfWidth, -halfHeight, frameRight, frameBottom);
				corner(consumer, pose, from, right, -halfWidth, -halfHeight, frameLeft, frameBottom);
			});
		} catch (RuntimeException | LinkageError e) {
			broken = true;
			Vrcamera.LOGGER.error("VRCamera: the menu can't be drawn in the world, the camera films without it", e);
		}
	}

	/**
	 * @return how the picture of the menu is drawn onto the screen in the world: as a texture like any other
	 */
	private static RenderType layer() {
		if (texture == null) {
			texture = new TargetTexture();
			Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
		}
		texture.show(target);
		return RenderType.entityCutoutNoCull(TEXTURE);
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
}
