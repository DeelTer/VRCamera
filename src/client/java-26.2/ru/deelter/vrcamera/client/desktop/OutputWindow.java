package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLCapabilities;
import ru.deelter.vrcamera.Vrcamera;

/**
 * A second window that shows the picture of the camera and nothing else, for OBS to capture.
 * <p>
 * It only shows. The game draws the picture in its own window as it draws everything, this one shares the textures
 * of the game and copies the finished picture to its screen. Needs the OpenGL renderer of the game.
 * <p>
 * A player who goes over to it steers the camera from there: it tells which keys are held in it and what the mouse
 * does, and has a few keys of its own.
 * <p>
 * This is the one for the versions of the game that make their windows with GLFW.
 */
public final class OutputWindow {
	private static final int WIDTH = 1280;
	private static final int HEIGHT = 720;
	private static long window;
	private static long kept;
	private static boolean unsupported;
	private static WindowPlace place;
	private static WindowInput input;
	private static GLCapabilities capabilities;
	private static int frameBuffer;
	private static double scrolled;
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
			if (GLFW.glfwWindowShouldClose(window)) {
				close();
				DesktopCamera.INSTANCE.setMode(DesktopCamera.Mode.OFF);
			}
			return true;
		}
		if (kept != 0) {
			window = kept;
			kept = 0;
			GLFW.glfwSetWindowShouldClose(window, false);
			return ready();
		}
		final long game = mc.getWindow().handle();
		if (GLFW.glfwGetWindowAttrib(game, GLFW.GLFW_CLIENT_API) != GLFW.GLFW_OPENGL_API) {
			Vrcamera.LOGGER.error("VRCamera: the camera window needs the OpenGL renderer of the game");
			unsupported = true;
			return false;
		}

		GLFW.glfwDefaultWindowHints();
		GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
		for (final int hint : new int[]{GLFW.GLFW_CONTEXT_VERSION_MAJOR, GLFW.GLFW_CONTEXT_VERSION_MINOR,
				GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_FORWARD_COMPAT}) {
			GLFW.glfwWindowHint(hint, GLFW.glfwGetWindowAttrib(game, hint));
		}

		GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
		GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
		GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);

		final long current = GLFW.glfwGetCurrentContext();
		GLFW.glfwMakeContextCurrent(0);
		try {
			window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "VRCamera", 0, game);
		} finally {
			GLFW.glfwMakeContextCurrent(current);
		}
		GLFW.glfwDefaultWindowHints();
		if (window == 0) {
			Vrcamera.LOGGER.error("VRCamera: the camera window could not be made");
			unsupported = true;
			return false;
		}
		GLFW.glfwSetScrollCallback(window, (handle, x, y) -> scrolled += y);
		capabilities = null;
		frameBuffer = 0;
		return ready();
	}

	public static void close() {
		if (window == 0) {
			return;
		}
		place.remember();
		new Handle().setMouseCaptured(false);
		GLFW.glfwHideWindow(window);
		kept = window;
		window = 0;
		place = null;
		input = null;
		scrolled = 0;
		moving = false;
	}

	/**
	 * the window as it is the first time: its usual size, in the middle of the monitor the game is on
	 */
	public static void resetPlace() {
		if (window != 0) {
			place.reset();
		}
	}

	/**
	 * makes the window as large as the picture was asked to be, also if no monitor is
	 */
	public static void setSize(int width, int height) {
		if (window != 0) {
			place.resize(width, height);
		}
	}

	/**
	 * fills the monitor the window is on, without a frame, or goes back to the window it was
	 */
	public static void toggleFullscreen() {
		if (window != 0) {
			place.toggleFullscreen();
		}
	}

	/**
	 * @return width and height of the picture the window shows, null without one that shows
	 */
	@Nullable
	public static int[] size() {
		if (window == 0) {
			return null;
		}
		final int[] width = new int[1];
		final int[] height = new int[1];
		GLFW.glfwGetFramebufferSize(window, width, height);
		return width[0] > 0 && height[0] > 0 ? new int[]{width[0], height[0]} : null;
	}

	/**
	 * @return if a player behind blocks can be shown through them: the window has an OpenGL context of its own to draw it in
	 */
	public static boolean showsThrough() {
		return true;
	}

	/**
	 * copies the picture to the window
	 *
	 * @param guides if the lines that help to frame a picture go over it, the ones that were picked with H
	 * @param fill   if the picture was drawn for the shape of the window, and fills it
	 */
	public static void show(Minecraft mc, RenderTarget picture, boolean guides, boolean fill) {
		final int[] size = size();
		if (size == null) {
			return;
		}
		int texture;
		try {
			texture = PictureBlit.textureId(picture);
		} catch (IllegalStateException e) {
			unsupported = true;
			throw e;
		}
		final long game = mc.getWindow().handle();
		final GLCapabilities gameCapabilities = GL.getCapabilities();

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
			PictureBlit.draw(picture, texture, frameBuffer, size[0], size[1], fill, guides ? WindowInput.guide() :
					null);
			GLFW.glfwSwapBuffers(window);
		} finally {
			GLFW.glfwMakeContextCurrent(game);
			GL.setCapabilities(gameCapabilities);
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
	 * the keys of the window itself, see {@link WindowInput}
	 */
	public static void handleKeys() {
		if (window != 0) {
			input.handleKeys(place::toggleFullscreen);
		}
	}

	/**
	 * The game was told of a turn of the wheel. This window hears of its own itself.
	 *
	 * @return false, it was not for this window
	 */
	public static boolean onScroll(long ofWindow, double amount) {
		return false;
	}

	/**
	 * @return how far the wheel was turned over the window since this was asked last
	 */
	public static double scrolled() {
		final double turned = scrolled;
		scrolled = 0;
		return turned;
	}

	/**
	 * holds the mouse in the window while that is wanted, see {@link WindowInput#capture}
	 */
	public static void capture(boolean wanted) {
		if (window != 0) {
			input.capture(wanted);
		}
	}

	/**
	 * @return how far the mouse the window has taken was moved since this was asked last, in pixels to the right
	 * and down
	 */
	public static double[] mouseMoved() {
		final double[] moved = new double[2];
		if (window == 0 || !input.hasMouse() || !isFocused()) {
			moving = false;
			return moved;
		}
		final double[] x = new double[1];
		final double[] y = new double[1];
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

	/**
	 * puts the window that is there where it was the last time, and shows it
	 */
	private static boolean ready() {
		final Handle handle = new Handle();
		place = new WindowPlace(handle, WIDTH, HEIGHT);
		input = new WindowInput(handle);
		place.putBack();
		GLFW.glfwShowWindow(window);
		return true;
	}

	private static final class Handle implements WindowPlace.Native, WindowInput.Native {
		@NotNull
		private static Box placeOf(long ofWindow) {
			final int[] x = new int[1];
			final int[] y = new int[1];
			final int[] width = new int[1];
			final int[] height = new int[1];
			GLFW.glfwGetWindowPos(ofWindow, x, y);
			GLFW.glfwGetWindowSize(ofWindow, width, height);
			return new Box(x[0], y[0], width[0], height[0]);
		}

		@Override
		public Box place() {
			return placeOf(window);
		}

		@Override
		public void move(Box to) {
			GLFW.glfwSetWindowPos(window, to.x(), to.y());
			GLFW.glfwSetWindowSize(window, to.width(), to.height());
		}

		@Nullable
		@Override
		public Box monitorOf(Box place) {
			final PointerBuffer monitors = GLFW.glfwGetMonitors();
			for (int i = 0; monitors != null && i < monitors.limit(); i++) {
				final long monitor = monitors.get(i);
				final GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
				final int[] left = new int[1];
				final int[] top = new int[1];
				GLFW.glfwGetMonitorPos(monitor, left, top);
				if (mode != null) {
					final Box whole = new Box(left[0], top[0], mode.width(), mode.height());
					if (whole.has(place.middleX(), place.middleY())) {
						return whole;
					}
				}
			}
			return null;
		}

		@Override
		public void setBordered(boolean bordered) {
			GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_DECORATED, bordered ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
		}

		@Override
		public int barHeight() {
			final int[] bar = new int[1];
			GLFW.glfwGetWindowFrameSize(window, null, bar, null, null);
			return bar[0];
		}

		@Override
		public Box gamePlace() {
			return placeOf(Minecraft.getInstance().getWindow().handle());
		}

		@Override
		public boolean isKeyDown(int key) {
			return OutputWindow.isKeyDown(key);
		}

		@Override
		public boolean isLeftButtonDown() {
			return GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		}

		@Override
		public void setMouseCaptured(boolean captured) {
			moving = false;
			GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR,
					captured ? GLFW.GLFW_CURSOR_DISABLED : GLFW.GLFW_CURSOR_NORMAL);
		}
	}
}
