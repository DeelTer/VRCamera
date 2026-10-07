package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.Callbacks;
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

	private static final class Handle implements WindowPlace.Native, WindowInput.Native {
		@Override
		public Box place() {
			return placeOf(window);
		}

		@Override
		public void move(Box to) {
			GLFW.glfwSetWindowPos(window, to.x(), to.y());
			GLFW.glfwSetWindowSize(window, to.width(), to.height());
		}

		@Override
		public Box monitorOf(Box place) {
			PointerBuffer monitors = GLFW.glfwGetMonitors();
			for (int i = 0; monitors != null && i < monitors.limit(); i++) {
				long monitor = monitors.get(i);
				GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
				int[] left = new int[1];
				int[] top = new int[1];
				GLFW.glfwGetMonitorPos(monitor, left, top);
				if (mode != null) {
					Box whole = new Box(left[0], top[0], mode.width(), mode.height());
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
			int[] bar = new int[1];
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

		private static Box placeOf(long ofWindow) {
			int[] x = new int[1];
			int[] y = new int[1];
			int[] width = new int[1];
			int[] height = new int[1];
			GLFW.glfwGetWindowPos(ofWindow, x, y);
			GLFW.glfwGetWindowSize(ofWindow, width, height);
			return new Box(x[0], y[0], width[0], height[0]);
		}
	}

	private static long window;
	private static boolean unsupported;
	private static WindowPlace place;
	private static WindowInput input;
	private static GLCapabilities capabilities;
	private static int frameBuffer;
	private static double scrolled;
	// where the mouse the window has taken was a frame ago, if it was looked at then
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
				// closed by its button: that turns the camera off like the command does
				close();
				DesktopCamera.INSTANCE.setMode(DesktopCamera.Mode.OFF);
			}
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
		for (int hint : new int[]{GLFW.GLFW_CONTEXT_VERSION_MAJOR, GLFW.GLFW_CONTEXT_VERSION_MINOR,
				GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_FORWARD_COMPAT}) {
			GLFW.glfwWindowHint(hint, GLFW.glfwGetWindowAttrib(game, hint));
		}
		// It must not take the keyboard from the game when it opens. And it is shown once it is where it was the
		// last time, not before
		GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
		GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
		GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
		window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "VRCamera", 0, game);
		GLFW.glfwDefaultWindowHints();
		if (window == 0) {
			Vrcamera.LOGGER.error("VRCamera: the camera window could not be made");
			unsupported = true;
			return false;
		}
		GLFW.glfwSetScrollCallback(window, (handle, x, y) -> scrolled += y);
		Handle handle = new Handle();
		place = new WindowPlace(handle, WIDTH, HEIGHT);
		input = new WindowInput(handle);
		capabilities = null;
		frameBuffer = 0;
		place.putBack();
		GLFW.glfwShowWindow(window);
		return true;
	}

	public static void close() {
		if (window == 0) {
			return;
		}
		place.remember();
		Callbacks.glfwFreeCallbacks(window);
		if (frameBuffer != 0 && capabilities != null) {
			// it belongs to the context of the window, and goes with it
			long game = Minecraft.getInstance().getWindow().handle();
			GLCapabilities gameCapabilities = GL.getCapabilities();
			GLFW.glfwMakeContextCurrent(window);
			GL.setCapabilities(capabilities);
			GL30.glDeleteFramebuffers(frameBuffer);
			GLFW.glfwMakeContextCurrent(game);
			GL.setCapabilities(gameCapabilities);
		}
		GLFW.glfwDestroyWindow(window);
		window = 0;
		place = null;
		input = null;
		capabilities = null;
		frameBuffer = 0;
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
	 * copies the picture to the window
	 *
	 * @param guides if the lines that help to frame a picture go over it, the ones that were picked with H
	 * @param fill   if the picture was drawn for the shape of the window, and fills it
	 */
	public static void show(Minecraft mc, RenderTarget picture, boolean guides, boolean fill) {
		int[] size = size();
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
			PictureBlit.draw(picture, texture, frameBuffer, size[0], size[1], fill, guides ? WindowInput.guide() : null);
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
		double turned = scrolled;
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
		double[] moved = new double[2];
		if (window == 0 || !input.hasMouse() || !isFocused()) {
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
}
