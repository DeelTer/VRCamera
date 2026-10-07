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

/**
 * A second window that shows the picture of the camera and nothing else, for OBS to capture.
 * <p>
 * It only shows. The game draws the picture in its own window as it draws everything, this one shares the textures
 * of the game and copies the finished picture to its screen. Needs the OpenGL renderer of the game.
 */
public final class OutputWindow {
	private static final int WIDTH = 1280;
	private static final int HEIGHT = 720;

	private static long window;
	private static GLCapabilities capabilities;
	private static int frameBuffer;
	private static Method glId;
	private static boolean unsupported;

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
		window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "VRCamera", 0, game);
		GLFW.glfwDefaultWindowHints();
		if (window == 0) {
			Vrcamera.LOGGER.error("VRCamera: the camera window could not be made");
			unsupported = true;
			return false;
		}
		capabilities = null;
		frameBuffer = 0;
		return true;
	}

	/**
	 * copies the picture to the window, as large as it fits
	 */
	public static void show(Minecraft mc, RenderTarget picture) {
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
				int shownWidth = (int) Math.round(picture.width * scale);
				int shownHeight = (int) Math.round(picture.height * scale);
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

	public static void close() {
		if (window == 0) {
			return;
		}
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
