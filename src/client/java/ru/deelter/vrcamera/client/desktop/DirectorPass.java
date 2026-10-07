package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.CameraController;
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
	 * Called once per frame, after the game drew the view of the player and before it shows it.
	 */
	public static void onFrame(Minecraft mc, DeltaTracker deltaTracker, boolean renderLevel) {
		DesktopCamera camera = DesktopCamera.INSTANCE;
		camera.keepView(mc);
		boolean wanted = camera.isOn() && !CameraController.isVRRunning() &&
				CameraController.INSTANCE.config().screenOutput == ScreenOutput.WINDOW;
		if (!wanted) {
			close();
			return;
		}
		if (active || !renderLevel || mc.level == null || mc.player == null) {
			return;
		}
		double fps = CameraController.INSTANCE.config().outputFps;
		long now = System.nanoTime();
		// the world is drawn twice for this, less often than the game is half as bad
		if (fps > 0 && now - lastNanos < 1.0E9 / fps - 500_000L) {
			return;
		}
		lastNanos = now;

		RenderTarget own = mc.gameRenderer.mainRenderTarget();
		if (!OutputWindow.open(mc)) {
			camera.failed("vrcamera.message.output.failed");
			return;
		}
		if (!camera.isOn() || !camera.advance(deltaTracker.getGameTimeDeltaPartialTick(true))) {
			// its window was closed, or it could not be moved and turned itself off
			return;
		}
		try {
			if (camera.showsOwnView()) {
				// No room for a camera around the player. What the player sees is the picture then, with their
				// hand and everything on their screen, and it is there already
				OutputWindow.show(mc, own);
				return;
			}
			if (target == null) {
				target = new MainTarget(own.width, own.height);
			} else if (target.width != own.width || target.height != own.height) {
				target.resize(own.width, own.height);
			}
			long started = System.nanoTime();
			DesktopGui.draw(mc, deltaTracker, own);
			draw(mc, deltaTracker, own);
			long drawn = System.nanoTime();
			OutputWindow.show(mc, target);
			measure(started, drawn, System.nanoTime());
		} catch (RuntimeException | LinkageError e) {
			Vrcamera.LOGGER.error("VRCamera: the picture of the camera could not be drawn, it was turned off", e);
			camera.failed("vrcamera.message.output.failed");
		}
	}

	private static void draw(Minecraft mc, DeltaTracker deltaTracker, RenderTarget own) {
		CameraType view = mc.options.getCameraType();
		try {
			active = true;
			setTarget(mc, target);
			// from outside: the player is drawn, their hand in front of the lens is not
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), BLACK,
					target.getDepthTexture(), 0.0);
			try (Gizmos.TemporaryCollection ignored = mc.levelExtractor.collectPerFrameMainThreadGizmos()) {
				mc.gameRenderer.update(deltaTracker);
				mc.gameRenderer.extract(deltaTracker, true);
			}
			try (Gizmos.TemporaryCollection ignored = mc.levelRenderer.collectPerFrameRenderThreadGizmos()) {
				GameFrame.render(mc, deltaTracker, true);
			}
			mc.levelRenderer.endFrame();
			mc.gameRenderer.renderBuffers().endFrame();
		} finally {
			active = false;
			mc.options.setCameraType(view);
			setTarget(mc, own);
			// The camera of the game goes back to the player. Sounds are heard from where it is, and what happens
			// between two frames takes it for the eyes of the player
			try (Gizmos.TemporaryCollection ignored = mc.levelExtractor.collectPerFrameMainThreadGizmos()) {
				mc.gameRenderer.update(deltaTracker);
			}
		}
	}

	private static void measure(long started, long drawn, long shown) {
		// evened out, single frames jump around too much to read
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
		// Time on the processor only. What the graphics card does with it afterwards is not in here, the frame
		// rate of the game itself tells about that
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

	private static void close() {
		OutputWindow.close();
		DesktopGui.close();
		if (target != null) {
			target.destroyBuffers();
			target = null;
		}
	}
}
