package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.Vr;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.config.ScreenOutput;
import ru.deelter.vrcamera.mixin.client.GameRendererAccessor;
import ru.deelter.vrcamera.mixin.client.MinecraftAccessor;

import java.util.List;
import java.util.Locale;

/**
 * Draws the world a second time in one frame, from where the director has the camera, into a picture of its own.
 * The player keeps their own view in the game window, the picture of the camera goes to a window next to it.
 * <p>
 * The game is told that another picture is the one to draw into, and draws the world once more. Everything that was
 * changed for that is put back before the frame goes on.
 */
public final class DirectorPass {
	private static final int BLACK = 0xFF000000;
	private static final double FAR = 1.0;
	private static final float USUAL_NEAR = 0.05F;
	/**
	 * how near the player can be to the camera for a picture without what is in the way
	 */
	private static final double CUT_CLOSEST = 1.5;
	private static final double CUT_FADE = 1.0;
	private static final float BELOW_PICTURE = -1.0F;
	private static boolean active;
	private static RenderTarget target;
	private static RenderTarget cut;
	private static float cutNear;
	private static boolean entitiesAlone;

	private static int[] shape;
	private static long lastNanos;
	private static double drawMillis;
	private static double showMillis;
	private static double framesPerSecond;
	private static int frames;
	private static long countedSince;

	private DirectorPass() {
	}

	/**
	 * @return if what is drawn right now is the picture of the camera, not the view of the player
	 */
	public static boolean isActive() {
		return active;
	}

	/**
	 * @return how wide the picture that is drawn right now is taken to be, for its shape
	 */
	public static int width(int ofGame) {
		return active && shape != null ? shape[0] : ofGame;
	}

	public static int height(int ofGame) {
		return active && shape != null ? shape[1] : ofGame;
	}

	/**
	 * @return the picture of a camera with a window of its own, null if there is none
	 */
	public static RenderTarget picture() {
		return DesktopCamera.INSTANCE.hasOwnWindow() ? target : null;
	}

	/**
	 * @return width and height of what {@link #picture} is shown in, null if that has the shape it was drawn in
	 */
	public static int[] shape() {
		return shape;
	}

	/**
	 * Called once per frame, after the game drew the view of the player and before it shows it.
	 */
	public static void onFrame(Minecraft mc, DeltaTracker deltaTracker, boolean renderLevel) {
		GameFrame.newFrame();
		final DesktopCamera camera = DesktopCamera.INSTANCE;
		if (camera.isOn() && (mc.level == null || mc.player == null)) {
			camera.setMode(DesktopCamera.Mode.OFF);
		}
		camera.keepView(mc);
		boolean wanted = camera.isOn() && !Vr.isRunning() &&
				CameraConfig.current().screenOutput == ScreenOutput.WINDOW;
		FlawlessFrames.set(wanted);
		if (!wanted) {
			close();
			return;
		}
		if (active) {
			return;
		}
		if (!OutputWindow.open(mc)) {
			camera.failed("vrcamera.message.output.failed");
			return;
		}
		OutputWindow.handleKeys();
		if (!camera.isOn() || !renderLevel) {
			return;
		}
		double fps = CameraConfig.current().outputFps;
		long now = System.nanoTime();
		if (fps > 0 && now - lastNanos < 1.0E9 / fps - 500_000L) {
			return;
		}
		lastNanos = now;

		RenderTarget own = mc.getMainRenderTarget();
		float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
		if (!camera.advance(partialTick)) {
			return;
		}
		try {
			if (camera.showsOwnView()) {
				SeeThrough.set(null);

				OutputWindow.show(mc, own, false, false);
				return;
			}
			CameraConfig config = CameraConfig.current();
			boolean ownSize = config.hasOutputSize();
			int width = ownSize ? config.outputWidth : own.width;
			int height = ownSize ? config.outputHeight : own.height;
			if (target == null) {
				target = new TextureTarget("VRCamera picture", width, height, true);
			} else if (target.width != width || target.height != height) {
				target.resize(width, height);
			}
			final long started = System.nanoTime();

			final int[] window = OutputWindow.size();
			if (window == null) {
				return;
			}
			shape = ownSize ? new int[]{width, height} : window;
			DesktopGui.draw(mc, deltaTracker, own);
			draw(mc, deltaTracker, own, target, partialTick);
			SeeThrough.set(cutThrough(mc, deltaTracker, own, camera, width, height, partialTick));
			final long drawn = System.nanoTime();
			OutputWindow.show(mc, target, camera.showsGrid(), shape != null && !ownSize);
			measure(started, drawn, System.nanoTime());
		} catch (RuntimeException | LinkageError e) {
			Vrcamera.LOGGER.error("VRCamera: the picture of the camera could not be drawn, it was turned off", e);
			camera.failed("vrcamera.message.output.failed");
		}
	}

	/**
	 * Draws the picture once more without what is nearer to the camera than the player, for {@link SeeThrough}.
	 *
	 * @return where in the picture the player is seen through what is in the way, null if nothing is
	 */
	private static SeeThrough.Hole cutThrough(
			Minecraft mc, DeltaTracker deltaTracker, RenderTarget own, DesktopCamera camera, int width, int height,
			float partialTick) {
		final DesktopCamera.Pose pose = camera.lens();
		if (pose == null || camera.revealAmount() < 0.01 || !OutputWindow.showsThrough()) {
			return null;
		}
		final Vector3f to = camera.revealCenter().subtract(pose.position()).toVector3f();
		pose.rotation().conjugate(new Quaternionf()).transform(to);
		final double depth = -to.z;
		final double radius = camera.revealRadius();
		final double amount = camera.revealAmount() * Math.clamp((depth - CUT_CLOSEST) / CUT_FADE, 0.0, 1.0);
		final DesktopCamera.Blocked blocked = camera.blockedBy(pose);
		if (amount < 0.01 || (!blocked.solid() && blocked.near() <= USUAL_NEAR)) {
			return null;
		}
		final double half = Math.tan(Math.toRadians(pose.fov()) / 2.0);
		final float aspect = shape == null ? width / (float) height : shape[0] / (float) shape[1];
		if (cut == null) {
			cut = new TextureTarget("VRCamera cut", width, height, true);
		} else if (cut.width != width || cut.height != height) {
			cut.resize(width, height);
		}
		entitiesAlone = blocked.solid();
		cutNear = entitiesAlone ? 0 : (float) blocked.near();
		try {
			draw(mc, deltaTracker, own, cut, partialTick);
		} finally {
			cutNear = 0;
			entitiesAlone = false;
		}
		final Vector3f feet = camera.revealFeet().subtract(pose.position()).toVector3f();
		pose.rotation().conjugate(new Quaternionf()).transform(feet);
		final float floor = feet.z < 0 ? (float) (feet.y / (-feet.z * half)) * 0.5F + 0.5F : 0.0F;
		return new SeeThrough.Hole(PictureBlit.textureId(cut), (float) (to.x / (depth * half * aspect)) * 0.5F + 0.5F,
				(float) (to.y / (depth * half)) * 0.5F + 0.5F,
				(float) (radius / (2.0 * depth * half)), aspect, (float) amount, blocked.solid() ? BELOW_PICTURE : floor);
	}

	/**
	 * @return if the picture that is drawn right now is one of the entities alone, with nothing of the world
	 * around them: the player to show through blocks that can't be opened up
	 */
	public static boolean drawsEntitiesAlone() {
		return active && entitiesAlone;
	}

	/**
	 * @return how near to the camera the picture that is drawn right now begins
	 */
	public static float near(float usual) {
		return active && cutNear > 0 ? cutNear : usual;
	}

	private static void draw(
			Minecraft mc, DeltaTracker deltaTracker, RenderTarget own, RenderTarget target, float partialTick) {
		final CameraType view = mc.options.getCameraType();
		try {
			GameFrame.begin();
			final Camera before = mc.gameRenderer.getMainCamera();
			PlayerEars.keep(before.getPosition(), before.getLookVector(), before.getUpVector(), before.getLeftVector(),
					before.rotation(), before.getYRot(), before.getXRot());
			active = true;
			((MinecraftAccessor) mc).vrcamera$setMainRenderTarget(target);
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), BLACK,
					target.getDepthTexture(), FAR);
			mc.gameRenderer.renderLevel(deltaTracker);
			((GameRendererAccessor) mc.gameRenderer).vrcamera$fogRenderer().endFrame();
		} finally {
			active = false;
			mc.options.setCameraType(view);
			((MinecraftAccessor) mc).vrcamera$setMainRenderTarget(own);
			Camera gameCamera = mc.gameRenderer.getMainCamera();
			if (mc.level != null && mc.getCameraEntity() != null) {
				gameCamera.setup(mc.level, mc.getCameraEntity(), !view.isFirstPerson(), view.isMirrored(), partialTick);
			}
			PlayerEars.letGo();
		}
	}

	private static void measure(long started, long drawn, long shown) {
		drawMillis += ((drawn - started) / 1.0E6 - drawMillis) * 0.1;
		showMillis += ((shown - drawn) / 1.0E6 - showMillis) * 0.1;
		frames++;
		if (shown - countedSince > 1_000_000_000L) {
			framesPerSecond = frames * 1.0E9 / (shown - countedSince);
			frames = 0;
			countedSince = shown;
		}
	}

	/**
	 * @return what the second picture costs, for the debug overlay
	 */
	static List<String> debugLines() {
		return List.of(String.format(Locale.ROOT, "camera window: %.0f fps, draw %.1f ms + show %.1f ms per picture",
						framesPerSecond, drawMillis, showMillis),
				String.format(Locale.ROOT, "that is %.0f%% of a second on the processor",
						framesPerSecond * (drawMillis + showMillis) / 10.0));
	}

	private static void close() {
		OutputWindow.close();
		DesktopGui.close();
		if (target != null) {
			target.destroyBuffers();
			target = null;
		}
		if (cut != null) {
			cut.destroyBuffers();
			cut = null;
		}
		SeeThrough.set(null);
	}
}
