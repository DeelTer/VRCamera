package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLCapabilities;
import ru.deelter.vrcamera.Vrcamera;

import java.lang.reflect.Method;
import org.lwjgl.glfw.Callbacks;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVidMode;
import ru.deelter.vrcamera.client.CameraController;
import ru.deelter.vrcamera.client.config.CameraConfig;

/**
 * A second window that shows the picture of the camera and nothing else, for OBS to capture.
 * <p>
 * It only shows. The game draws the picture in its own window as it draws everything, this one shares the textures
 * of the game and copies the finished picture to its screen. Needs the OpenGL renderer of the game.
 */
public final class OutputWindow {
	/**
	 * the lines that help to frame a picture: where they are, as parts of its width and of its height
	 */
	private enum Grid {
		THIRDS(1.0 / 3.0, 2.0 / 3.0),
		GOLDEN(0.382, 0.618),
		HALVES(0.5),
		// what of a wide picture is left in an upright one, for a video that is cut to both
		UPRIGHT,
		// Frames and not lines: what is inside the outer one is seen on any screen, what is inside the inner one is
		// not under the buttons and titles a player puts over a video
		SAFE,
		NONE;

		private static final double UPRIGHT_SHAPE = 9.0 / 16.0;
		// how far in from each edge the frames are, as parts of the picture
		private static final double[] SAFE_FRAMES = {0.05, 0.1};

		private final double[] up;

		Grid(double... lines) {
			this.up = lines;
		}

		private double[] across(int width, int height) {
			if (this != UPRIGHT) {
				return this.up;
			}
			double part = height * UPRIGHT_SHAPE / width;
			return part >= 1.0 ? new double[0] : new double[]{0.5 - part / 2.0, 0.5 + part / 2.0};
		}
	}

	private static final int WIDTH = 1280;
	private static final int HEIGHT = 720;

	private static long window;
	private static GLCapabilities capabilities;
	private static int frameBuffer;
	private static Method glId;
	private static boolean unsupported;
	private static double scrolled;
	private static boolean captured;
	private static boolean fullKeyDown;
	private static boolean gridKeyDown;
	private static int grid;
	// where the window was and how large, while it fills a monitor. Null while it is a window
	private static int[] windowed;
	private static boolean letGo;
	private static boolean moving;
	private static double mouseX;
	private static double mouseY;

	private OutputWindow() {
	}

	/**
	 * @return false if there is no window and there can't be one
	 */
	public static boolean open(Minecraft mc) {
		if (unsupported) {
			return false;
		}
		if (window != 0) {
			if (!GLFW.glfwWindowShouldClose(window)) {
				return true;
			}
			// closed by its button: that turns the camera off like the command does
			close();
			DesktopCamera.INSTANCE.setMode(DesktopCamera.Mode.OFF);
			return true;
		}
		long game = mc.getWindow().handle();
		if (GLFW.glfwGetWindowAttrib(game, GLFW.GLFW_CLIENT_API) != GLFW.GLFW_OPENGL_API) {
			Vrcamera.LOGGER.error("VRCamera: the camera window needs the OpenGL renderer of the game");
			unsupported = true;
			return false;
		}
		// a context like the one of the game, or the two can't share what they draw
		GLFW.glfwDefaultWindowHints();
		GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,
				GLFW.glfwGetWindowAttrib(game, GLFW.GLFW_CONTEXT_VERSION_MAJOR));
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,
				GLFW.glfwGetWindowAttrib(game, GLFW.GLFW_CONTEXT_VERSION_MINOR));
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.glfwGetWindowAttrib(game, GLFW.GLFW_OPENGL_PROFILE));
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT,
				GLFW.glfwGetWindowAttrib(game, GLFW.GLFW_OPENGL_FORWARD_COMPAT));
		// It must not take the keyboard from the game: the game pauses when it loses it
		GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
		GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
		// shown once it is where it was the last time, not before
		GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
		window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "VRCamera", 0, game);
		GLFW.glfwDefaultWindowHints();
		if (window == 0) {
			Vrcamera.LOGGER.error("VRCamera: the camera window could not be made");
			unsupported = true;
			return false;
		}
		GLFW.glfwSetScrollCallback(window, (handle, x, y) -> scrolled += y);
		capabilities = null;
		frameBuffer = 0;
		putBack();
		GLFW.glfwShowWindow(window);
		return true;
	}

	/**
	 * Where the window was when it was closed the last time, and as large, and over the whole monitor if it was.
	 * Not if that monitor is gone: a window nobody sees is no help
	 */
	private static void putBack() {
		CameraConfig config = CameraController.INSTANCE.config();
		int[] place = config.outputWindowPlace;
		if (place == null || place.length != 4 || place[2] < 1 || place[3] < 1 ||
				monitorAround(place[0] + place[2] / 2, place[1] + place[3] / 2) == null) {
			return;
		}
		GLFW.glfwSetWindowPos(window, place[0], place[1]);
		GLFW.glfwSetWindowSize(window, place[2], place[3]);
		keepReachable();
		if (config.outputWindowFull) {
			toggleFullscreen();
		}
	}

	/**
	 * A window is moved by the bar at its top. With that bar off the monitor, or the window larger than the
	 * monitor, there is no getting hold of it anymore: it is made to fit, and its bar is brought back into view
	 */
	private static void keepReachable() {
		int[] place = place();
		int[] monitor = monitorAround(place[0] + place[2] / 2, place[1] + place[3] / 2);
		if (monitor == null) {
			return;
		}
		int[] bar = new int[1];
		GLFW.glfwGetWindowFrameSize(window, null, bar, null, null);
		int width = Math.min(place[2], monitor[2]);
		int height = Math.min(place[3], monitor[3] - bar[0]);
		int x = Math.clamp(place[0], monitor[0], monitor[0] + monitor[2] - width);
		int y = Math.clamp(place[1], monitor[1] + bar[0], monitor[1] + monitor[3] - height);
		if (width != place[2] || height != place[3]) {
			GLFW.glfwSetWindowSize(window, width, height);
		}
		if (x != place[0] || y != place[1]) {
			GLFW.glfwSetWindowPos(window, x, y);
		}
	}

	/**
	 * the window as it is the first time: its usual size, in the middle of the monitor the game is on
	 */
	public static void resetPlace() {
		if (window == 0) {
			return;
		}
		if (windowed != null) {
			toggleFullscreen();
		}
		int[] x = new int[1];
		int[] y = new int[1];
		int[] width = new int[1];
		int[] height = new int[1];
		long game = Minecraft.getInstance().getWindow().handle();
		GLFW.glfwGetWindowPos(game, x, y);
		GLFW.glfwGetWindowSize(game, width, height);
		int[] monitor = monitorAround(x[0] + width[0] / 2, y[0] + height[0] / 2);
		GLFW.glfwSetWindowSize(window, WIDTH, HEIGHT);
		if (monitor != null) {
			GLFW.glfwSetWindowPos(window, monitor[0] + (monitor[2] - WIDTH) / 2, monitor[1] + (monitor[3] - HEIGHT) / 2);
		}
		keepReachable();
	}

	private static void remember() {
		CameraConfig config = CameraController.INSTANCE.config();
		config.outputWindowFull = windowed != null;
		config.outputWindowPlace = windowed != null ? windowed : place();
		config.save();
	}

	/**
	 * @return where the window is and how large: x, y, width and height
	 */
	private static int[] place() {
		int[] x = new int[1];
		int[] y = new int[1];
		int[] width = new int[1];
		int[] height = new int[1];
		GLFW.glfwGetWindowPos(window, x, y);
		GLFW.glfwGetWindowSize(window, width, height);
		return new int[]{x[0], y[0], width[0], height[0]};
	}

	/**
	 * @return the monitor that point of the desktop is on, as {@link #place} has a window, null if it is on none
	 */
	private static int[] monitorAround(int x, int y) {
		PointerBuffer monitors = GLFW.glfwGetMonitors();
		for (int i = 0; monitors != null && i < monitors.limit(); i++) {
			long monitor = monitors.get(i);
			GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
			int[] left = new int[1];
			int[] top = new int[1];
			GLFW.glfwGetMonitorPos(monitor, left, top);
			if (mode != null && x >= left[0] && x < left[0] + mode.width() && y >= top[0] &&
					y < top[0] + mode.height()) {
				return new int[]{left[0], top[0], mode.width(), mode.height()};
			}
		}
		return null;
	}

	/**
	 * copies the picture to the window, as large as it fits
	 *
	 * @param grid if the lines that help to frame a picture go over it, the ones that were picked with H
	 * @param fill if the picture was drawn for the shape of the window, and fills it
	 */
	public static void show(Minecraft mc, RenderTarget picture, boolean grid, boolean fill) {
		if (window == 0) {
			return;
		}
		int texture = textureId(picture);
		long game = mc.getWindow().handle();
		GLCapabilities gameCapabilities = GL.getCapabilities();
		// what the game drew has to be done before the other context looks at it
		GL11.glFlush();
		GLFW.glfwMakeContextCurrent(window);
		try {
			if (capabilities == null) {
				capabilities = GL.createCapabilities();
				GLFW.glfwSwapInterval(0);
				frameBuffer = GL30.glGenFramebuffers();
			} else {
				GL.setCapabilities(capabilities);
			}
			int[] width = new int[1];
			int[] height = new int[1];
			GLFW.glfwGetFramebufferSize(window, width, height);
			if (width[0] > 0 && height[0] > 0) {
				// the whole picture, with black bars if the window has another shape
				double scale = Math.min(width[0] / (double) picture.width, height[0] / (double) picture.height);
				int shownWidth = fill ? width[0] : (int) Math.round(picture.width * scale);
				int shownHeight = fill ? height[0] : (int) Math.round(picture.height * scale);
				int left = (width[0] - shownWidth) / 2;
				int bottom = (height[0] - shownHeight) / 2;

				GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
				GL11.glViewport(0, 0, width[0], height[0]);
				GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
				GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
				GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, frameBuffer);
				GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
						texture, 0);
				GL30.glBlitFramebuffer(0, 0, picture.width, picture.height, left, bottom, left + shownWidth,
						bottom + shownHeight, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
				if (grid) {
					// lines without a shader of its own: clearing thin strips of the window
					int thickness = Math.max(1, shownHeight / 360);
					GL11.glEnable(GL11.GL_SCISSOR_TEST);
					GL11.glClearColor(1.0F, 1.0F, 1.0F, 1.0F);
					Grid lines = Grid.values()[OutputWindow.grid];
					for (double across : lines.across(shownWidth, shownHeight)) {
						GL11.glScissor(left + (int) (shownWidth * across), bottom, thickness, shownHeight);
						GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
					}
					for (double up : lines.up) {
						GL11.glScissor(left, bottom + (int) (shownHeight * up), shownWidth, thickness);
						GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
					}
					for (double in : lines == Grid.SAFE ? Grid.SAFE_FRAMES : new double[0]) {
						int x = left + (int) (shownWidth * in);
						int y = bottom + (int) (shownHeight * in);
						int across = shownWidth - 2 * (int) (shownWidth * in);
						int high = shownHeight - 2 * (int) (shownHeight * in);
						for (int[] side : new int[][]{{x, y, across, thickness}, {x, y + high - thickness, across, thickness},
								{x, y, thickness, high}, {x + across - thickness, y, thickness, high}}) {
							GL11.glScissor(side[0], side[1], side[2], side[3]);
							GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
						}
					}
					GL11.glDisable(GL11.GL_SCISSOR_TEST);
				}
				GLFW.glfwSwapBuffers(window);
			}
		} finally {
			GLFW.glfwMakeContextCurrent(game);
			GL.setCapabilities(gameCapabilities);
		}
	}

	/**
	 * @return what OpenGL calls the texture of the picture. The class that knows is not the same in every
	 * supported version of the game, its method is
	 */
	private static int textureId(RenderTarget picture) {
		Object texture = picture.getColorTexture();
		try {
			if (glId == null || glId.getDeclaringClass() != texture.getClass()) {
				glId = texture.getClass().getMethod("glId");
			}
			return (int) glId.invoke(texture);
		} catch (ReflectiveOperationException e) {
			unsupported = true;
			throw new IllegalStateException("the camera window needs the OpenGL renderer of the game", e);
		}
	}

	/**
	 * @return if the window of the camera is the one the keyboard goes to
	 */
	public static boolean isFocused() {
		return window != 0 && GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
	}

	/**
	 * @param key a key of the keyboard as the game numbers them
	 * @return if it is held down in the window of the camera
	 */
	public static boolean isKeyDown(int key) {
		return window != 0 && key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_LAST &&
				GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
	}

	/**
	 * The keys of the window itself. F11: over the whole monitor it is on, and back. H: the next kind of lines
	 * over the picture, see {@link Grid}
	 */
	public static void handleKeys() {
		if (window == 0) {
			return;
		}
		boolean full = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F11) == GLFW.GLFW_PRESS;
		if (full && !fullKeyDown) {
			toggleFullscreen();
		}
		fullKeyDown = full;
		boolean grid = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_H) == GLFW.GLFW_PRESS;
		if (grid && !gridKeyDown) {
			OutputWindow.grid = (OutputWindow.grid + 1) % Grid.values().length;
		}
		gridKeyDown = grid;
	}

	/**
	 * Fills the monitor the window is on, without a frame, or goes back to the window it was. Not the fullscreen
	 * a game takes for itself: that one goes away as soon as another window is clicked, which is the game
	 */
	public static void toggleFullscreen() {
		if (window == 0) {
			return;
		}
		if (windowed != null) {
			GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
			GLFW.glfwSetWindowPos(window, windowed[0], windowed[1]);
			GLFW.glfwSetWindowSize(window, windowed[2], windowed[3]);
			windowed = null;
			keepReachable();
			return;
		}
		int[] place = place();
		int[] monitor = monitorAround(place[0] + place[2] / 2, place[1] + place[3] / 2);
		if (monitor != null) {
			windowed = place;
			GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
			GLFW.glfwSetWindowPos(window, monitor[0], monitor[1]);
			GLFW.glfwSetWindowSize(window, monitor[2], monitor[3]);
		}
	}

	/**
	 * @return width and height of the window, null without one that shows
	 */
	public static int[] size() {
		if (window == 0) {
			return null;
		}
		int[] width = new int[1];
		int[] height = new int[1];
		GLFW.glfwGetFramebufferSize(window, width, height);
		return width[0] > 0 && height[0] > 0 ? new int[]{width[0], height[0]} : null;
	}

	/**
	 * @return how far the wheel was turned over the window since this was asked last
	 */
	public static double scrolled() {
		double turned = scrolled;
		scrolled = 0;
		return turned;
	}

	/**
	 * Takes the mouse for the window, hidden and held inside of it, or lets it go again. Escape lets it go as well,
	 * to move the window or to leave it, and a click into the window takes it back.
	 */
	public static void capture(boolean wanted) {
		if (window == 0) {
			return;
		}
		if (!wanted) {
			letGo = false;
		} else if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS) {
			letGo = true;
		} else if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS) {
			letGo = false;
		}
		boolean held = wanted && !letGo;
		if (held != captured) {
			captured = held;
			moving = false;
			GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR,
					held ? GLFW.GLFW_CURSOR_DISABLED : GLFW.GLFW_CURSOR_NORMAL);
		}
	}

	/**
	 * @return how far the mouse the window has taken was moved since this was asked last, in pixels to the right
	 * and down
	 */
	public static double[] mouseMoved() {
		double[] moved = new double[2];
		if (!captured || !isFocused()) {
			moving = false;
			return moved;
		}
		double[] x = new double[1];
		double[] y = new double[1];
		GLFW.glfwGetCursorPos(window, x, y);
		if (moving) {
			moved[0] = x[0] - mouseX;
			moved[1] = y[0] - mouseY;
		}
		moving = true;
		mouseX = x[0];
		mouseY = y[0];
		return moved;
	}

	public static void close() {
		if (window == 0) {
			return;
		}
		remember();
		Callbacks.glfwFreeCallbacks(window);
		scrolled = 0;
		captured = false;
		windowed = null;
		letGo = false;
		moving = false;
		long game = Minecraft.getInstance().getWindow().handle();
		GLCapabilities gameCapabilities = GL.getCapabilities();
		if (frameBuffer != 0 && capabilities != null) {
			// it belongs to the context of the window, and goes with it
			GLFW.glfwMakeContextCurrent(window);
			GL.setCapabilities(capabilities);
			GL30.glDeleteFramebuffers(frameBuffer);
			GLFW.glfwMakeContextCurrent(game);
			GL.setCapabilities(gameCapabilities);
		}
		GLFW.glfwDestroyWindow(window);
		window = 0;
		capabilities = null;
		frameBuffer = 0;
	}
}
