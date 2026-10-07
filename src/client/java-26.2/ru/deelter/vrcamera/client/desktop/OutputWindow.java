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
import ru.deelter.vrcamera.client.CameraController;
import ru.deelter.vrcamera.client.config.CameraConfig;

import java.lang.reflect.Method;

/**
 * A second window that shows the picture of the camera and nothing else, for OBS to capture.
 * <p>
 * It only shows. The game draws the picture in its own window as it draws everything, this one shares the textures
 * of the game and copies the finished picture to its screen. Needs the OpenGL renderer of the game.
 * <p>
 * A player who goes over to it steers the camera from there: it tells which keys are held in it and what the mouse
 * does, and has a few keys of its own.
 */
public final class OutputWindow {
	private static final int WIDTH = 1280;
	private static final int HEIGHT = 720;
	// lines over the picture are this many times thinner than it is high
	private static final int LINE_PART = 360;

	/**
	 * a part of the desktop: a monitor, or where a window is on it
	 */
	private record Box(int x, int y, int width, int height) {
		int middleX() {
			return this.x + this.width / 2;
		}

		int middleY() {
			return this.y + this.height / 2;
		}

		boolean has(int pointX, int pointY) {
			return pointX >= this.x && pointX < this.x + this.width && pointY >= this.y &&
					pointY < this.y + this.height;
		}

		int[] toArray() {
			return new int[]{this.x, this.y, this.width, this.height};
		}

		static Box of(int[] values) {
			return values == null || values.length != 4 || values[2] < 1 || values[3] < 1 ? null :
					new Box(values[0], values[1], values[2], values[3]);
		}
	}

	private static long window;
	private static boolean unsupported;
	private static GLCapabilities capabilities;
	private static int frameBuffer;
	private static Method glId;
	// where the window was and how large, while it fills a monitor. Null while it is a window
	private static Box windowed;
	private static FrameGuide guide = FrameGuide.THIRDS;
	private static boolean fullKeyDown;
	private static boolean guideKeyDown;
	private static double scrolled;
	// the mouse: if the window has taken it, if the player asked for it back, and where it was a frame ago
	private static boolean captured;
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
		capabilities = null;
		frameBuffer = 0;
		putBack();
		GLFW.glfwShowWindow(window);
		return true;
	}

	public static void close() {
		if (window == 0) {
			return;
		}
		remember();
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
		capabilities = null;
		frameBuffer = 0;
		windowed = null;
		scrolled = 0;
		captured = false;
		letGo = false;
		moving = false;
	}

	// ---- where the window is

	/**
	 * Where the window was when it was closed the last time, and as large, and over the whole monitor if it was.
	 * Not if that monitor is gone: a window nobody sees is no help
	 */
	private static void putBack() {
		CameraConfig config = CameraController.INSTANCE.config();
		Box place = Box.of(config.outputWindowPlace);
		if (place == null || monitorOf(place) == null) {
			return;
		}
		move(place);
		keepReachable();
		if (config.outputWindowFull) {
			toggleFullscreen();
		}
	}

	private static void remember() {
		CameraConfig config = CameraController.INSTANCE.config();
		config.outputWindowFull = windowed != null;
		config.outputWindowPlace = (windowed != null ? windowed : place()).toArray();
		config.save();
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
		Box monitor = monitorOf(place(Minecraft.getInstance().getWindow().handle()));
		GLFW.glfwSetWindowSize(window, WIDTH, HEIGHT);
		if (monitor != null) {
			GLFW.glfwSetWindowPos(window, monitor.middleX() - WIDTH / 2, monitor.middleY() - HEIGHT / 2);
		}
		keepReachable();
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
			move(windowed);
			windowed = null;
			keepReachable();
			return;
		}
		Box place = place();
		Box monitor = monitorOf(place);
		if (monitor != null) {
			windowed = place;
			GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
			move(monitor);
		}
	}

	/**
	 * A window is moved by the bar at its top. With that bar off the monitor, or the window larger than the
	 * monitor, there is no getting hold of it anymore: it is made to fit, and its bar is brought back into view
	 */
	private static void keepReachable() {
		Box place = place();
		Box monitor = monitorOf(place);
		if (monitor == null) {
			return;
		}
		int[] bar = new int[1];
		GLFW.glfwGetWindowFrameSize(window, null, bar, null, null);
		int width = Math.min(place.width, monitor.width);
		int height = Math.min(place.height, monitor.height - bar[0]);
		Box fitted = new Box(Math.clamp(place.x, monitor.x, monitor.x + monitor.width - width),
				Math.clamp(place.y, monitor.y + bar[0], monitor.y + monitor.height - height), width, height);
		if (!fitted.equals(place)) {
			move(fitted);
		}
	}

	private static void move(Box to) {
		GLFW.glfwSetWindowPos(window, to.x, to.y);
		GLFW.glfwSetWindowSize(window, to.width, to.height);
	}

	private static Box place() {
		return place(window);
	}

	private static Box place(long ofWindow) {
		int[] x = new int[1];
		int[] y = new int[1];
		int[] width = new int[1];
		int[] height = new int[1];
		GLFW.glfwGetWindowPos(ofWindow, x, y);
		GLFW.glfwGetWindowSize(ofWindow, width, height);
		return new Box(x[0], y[0], width[0], height[0]);
	}

	/**
	 * @return the monitor the middle of that part of the desktop is on, null if it is on none
	 */
	private static Box monitorOf(Box place) {
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

	// ---- the picture

	/**
	 * copies the picture to the window
	 *
	 * @param guides if the lines that help to frame a picture go over it, the ones that were picked with H
	 * @param fill   if the picture was drawn for the shape of the window, and fills it. Otherwise it is shown whole,
	 *               with black bars where the window has another shape
	 */
	public static void show(Minecraft mc, RenderTarget picture, boolean guides, boolean fill) {
		int[] size = size();
		if (size == null) {
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
			double scale = Math.min(size[0] / (double) picture.width, size[1] / (double) picture.height);
			int width = fill ? size[0] : (int) Math.round(picture.width * scale);
			int height = fill ? size[1] : (int) Math.round(picture.height * scale);
			Box shown = new Box((size[0] - width) / 2, (size[1] - height) / 2, width, height);

			GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
			GL11.glViewport(0, 0, size[0], size[1]);
			GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
			GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, frameBuffer);
			GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
					texture, 0);
			GL30.glBlitFramebuffer(0, 0, picture.width, picture.height, shown.x, shown.y, shown.x + width,
					shown.y + height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
			if (guides) {
				drawGuide(shown);
			}
			GLFW.glfwSwapBuffers(window);
		} finally {
			GLFW.glfwMakeContextCurrent(game);
			GL.setCapabilities(gameCapabilities);
		}
	}

	/**
	 * Lines without a shader of its own: thin strips of the window are cleared to white.
	 *
	 * @param shown where in the window the picture is, counted from its lower left corner
	 */
	private static void drawGuide(Box shown) {
		int thickness = Math.max(1, shown.height / LINE_PART);
		GL11.glEnable(GL11.GL_SCISSOR_TEST);
		GL11.glClearColor(1.0F, 1.0F, 1.0F, 1.0F);
		for (double across : guide.across(shown.width, shown.height)) {
			strip(shown.x + (int) (shown.width * across), shown.y, thickness, shown.height);
		}
		for (double up : guide.up()) {
			strip(shown.x, shown.y + (int) (shown.height * up), shown.width, thickness);
		}
		for (double in : guide.frames()) {
			int x = shown.x + (int) (shown.width * in);
			int y = shown.y + (int) (shown.height * in);
			int width = shown.width - 2 * (int) (shown.width * in);
			int height = shown.height - 2 * (int) (shown.height * in);
			strip(x, y, width, thickness);
			strip(x, y + height - thickness, width, thickness);
			strip(x, y, thickness, height);
			strip(x + width - thickness, y, thickness, height);
		}
		GL11.glDisable(GL11.GL_SCISSOR_TEST);
	}

	private static void strip(int x, int y, int width, int height) {
		GL11.glScissor(x, y, width, height);
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
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

	// ---- the keyboard and the mouse in the window

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
	 * over the picture, see {@link FrameGuide}
	 */
	public static void handleKeys() {
		if (window == 0) {
			return;
		}
		boolean full = isKeyDown(GLFW.GLFW_KEY_F11);
		if (full && !fullKeyDown) {
			toggleFullscreen();
		}
		fullKeyDown = full;
		boolean next = isKeyDown(GLFW.GLFW_KEY_H);
		if (next && !guideKeyDown) {
			guide = guide.next();
		}
		guideKeyDown = next;
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
		} else if (isKeyDown(GLFW.GLFW_KEY_ESCAPE)) {
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
}
