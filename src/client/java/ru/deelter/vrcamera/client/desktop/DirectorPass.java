package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.Vr;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.config.ScreenOutput;
import ru.deelter.vrcamera.mixin.client.GameRendererAccessor;
import ru.deelter.vrcamera.mixin.client.SkyRendererAccessor;

import java.util.List;
import java.util.Locale;

/**
 * Draws the world a second time in one frame, from where the director has the camera, into a picture of its own.
 * The player keeps their own view in the game window, the picture of the camera goes to a window next to it.
 * <p>
 * The way Vivecraft draws its eyes and its camera: the game is told that another picture is the one to draw into,
 * and goes through everything that depends on where it is looked from once more. Everything that was changed for
 * that is put back before the frame goes on.
 */
public final class DirectorPass {
	private static final Vector4fc BLACK = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);

	private static boolean active;
	private static RenderTarget target;

	private static int[] shape;
	private static long lastNanos;
	private static double drawMillis;
	private static double showMillis;
	private static double framesPerSecond;
	private static int frames;
	private static long countedSince;

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
	@Nullable
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
		final DesktopCamera camera = DesktopCamera.INSTANCE;
		if (camera.isOn() && (mc.level == null || mc.player == null)) {

			camera.setMode(DesktopCamera.Mode.OFF);
		}
		camera.keepView(mc);
		final boolean wanted = camera.isOn() && !Vr.isRunning() &&
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
		final double fps = CameraConfig.current().outputFps;
		final long now = System.nanoTime();

		if (fps > 0 && now - lastNanos < 1.0E9 / fps - 500_000L) {
			return;
		}
		lastNanos = now;

		final RenderTarget own = mc.gameRenderer.mainRenderTarget();
		if (!camera.advance(deltaTracker.getGameTimeDeltaPartialTick(true))) {

			return;
		}
		try {
			if (camera.showsOwnView()) {

				OutputWindow.show(mc, own, false, false);
				return;
			}
			final CameraConfig config = CameraConfig.current();
			final boolean ownSize = config.hasOutputSize();
			final int width = ownSize ? config.outputWidth : own.width;
			final int height = ownSize ? config.outputHeight : own.height;
			if (target == null) {
				target = new MainTarget(width, height);
			} else if (target.width != width || target.height != height) {
				target.resize(width, height);
			}
			final long started = System.nanoTime();

			shape = ownSize ? new int[]{width, height} : OutputWindow.size();
			DesktopGui.draw(mc, deltaTracker, own);
			draw(mc, deltaTracker, own);
			final long drawn = System.nanoTime();
			OutputWindow.show(mc, target, camera.showsGrid(), shape != null && !ownSize);
			measure(started, drawn, System.nanoTime());
		} catch (RuntimeException | LinkageError e) {
			Vrcamera.LOGGER.error("VRCamera: the picture of the camera could not be drawn, it was turned off", e);
			camera.failed("vrcamera.message.output.failed");
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

	static void setTarget(Minecraft mc, RenderTarget to) {
		((GameRendererAccessor) mc.gameRenderer).vrcamera$setMainRenderTarget(to);
		if (mc.levelRenderer.skyRenderer() != null) {
			((SkyRendererAccessor) mc.levelRenderer.skyRenderer()).vrcamera$setRenderTarget(to);
		}
	}

	private static void draw(Minecraft mc, DeltaTracker deltaTracker, RenderTarget own) {
		final CameraType view = mc.options.getCameraType();
		try {
			active = true;
			setTarget(mc, target);

			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), BLACK,
					target.getDepthTexture(), 0.0);
			try (final Gizmos.TemporaryCollection ignored = mc.levelExtractor.collectPerFrameMainThreadGizmos()) {
				mc.gameRenderer.update(deltaTracker);
				mc.gameRenderer.extract(deltaTracker, true);
			}
			try (final Gizmos.TemporaryCollection ignored = mc.levelRenderer.collectPerFrameRenderThreadGizmos()) {
				GameFrame.render(mc, deltaTracker, true);
			}
			mc.levelRenderer.endFrame();
			mc.gameRenderer.renderBuffers().endFrame();
		} finally {
			active = false;
			mc.options.setCameraType(view);
			setTarget(mc, own);

			try (final Gizmos.TemporaryCollection ignored = mc.levelExtractor.collectPerFrameMainThreadGizmos()) {
				mc.gameRenderer.update(deltaTracker);
			}
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

	private static void close() {
		OutputWindow.close();
		DesktopGui.close();
		if (target != null) {
			target.destroyBuffers();
			target = null;
		}
	}

	private DirectorPass() {
	}
}
