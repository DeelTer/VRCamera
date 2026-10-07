package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_EventFilter;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.system.MemoryStack;
import ru.deelter.vrcamera.Vrcamera;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * A second window that shows the picture of the camera and nothing else, for OBS to capture.
 * <p>
 * It only shows. The game draws the picture in its own window as it draws everything, and the finished picture is
 * copied to this one. Needs the OpenGL renderer of the game.
 * <p>
 * A player who goes over to it steers the camera from there: it tells which keys are held in it and what the mouse
 * does, and has a few keys of its own.
 * <p>
 * This is the one for the versions of the game that make their windows with SDL. There the game draws into this
 * window with the OpenGL context it has: what the copy changes about that context is put back afterwards, the game
 * keeps track of it and would not know.
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
			SDLVideo.SDL_SetWindowPosition(window, to.x(), to.y());
			SDLVideo.SDL_SetWindowSize(window, to.width(), to.height());
		}

		@Override
		public Box monitorOf(Box place) {
			IntBuffer displays = SDLVideo.SDL_GetDisplays();
			try (MemoryStack stack = MemoryStack.stackPush()) {
				SDL_Rect bounds = SDL_Rect.malloc(stack);
				for (int i = 0; displays != null && i < displays.limit(); i++) {
					if (SDLVideo.SDL_GetDisplayBounds(displays.get(i), bounds)) {
						Box whole = new Box(bounds.x(), bounds.y(), bounds.w(), bounds.h());
						if (whole.has(place.middleX(), place.middleY())) {
							return whole;
						}
					}
				}
			}
			return null;
		}

		@Override
		public void setBordered(boolean bordered) {
			SDLVideo.SDL_SetWindowBordered(window, bordered);
		}

		@Override
		public int barHeight() {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer top = stack.callocInt(1);
				SDLVideo.SDL_GetWindowBordersSize(window, top, stack.callocInt(1), stack.callocInt(1),
						stack.callocInt(1));
				return top.get(0);
			}
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
			try (MemoryStack stack = MemoryStack.stackPush()) {
				int buttons = SDLMouse.SDL_GetMouseState(stack.callocFloat(1), stack.callocFloat(1));
				return SDLMouse.SDL_GetMouseFocus() == window && (buttons & SDLMouse.SDL_BUTTON_LMASK) != 0;
			}
		}

		@Override
		public void setMouseCaptured(boolean captured) {
			SDLMouse.SDL_SetWindowRelativeMouseMode(window, captured);
			// what the mouse did before is not a turn of the camera
			readMouse();
		}

		private static Box placeOf(long ofWindow) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer x = stack.callocInt(1);
				IntBuffer y = stack.callocInt(1);
				IntBuffer width = stack.callocInt(1);
				IntBuffer height = stack.callocInt(1);
				SDLVideo.SDL_GetWindowPosition(ofWindow, x, y);
				SDLVideo.SDL_GetWindowSize(ofWindow, width, height);
				return new Box(x.get(0), y.get(0), width.get(0), height.get(0));
			}
		}
	}

	private static long window;
	// what the events of the window are told apart by, and if one of them asked for it to be closed. Events can come
	// from another thread
	private static volatile int windowId;
	private static volatile boolean closeRequested;
	private static SDL_EventFilter filter;
	private static boolean unsupported;
	private static WindowPlace place;
	private static WindowInput input;
	private static int frameBuffer;
	private static double scrolled;

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
			if (closeRequested) {
				// closed by its button: that turns the camera off like the command does
				close();
				DesktopCamera.INSTANCE.setMode(DesktopCamera.Mode.OFF);
			}
			return true;
		}
		if (SDLVideo.SDL_GL_GetCurrentContext() == 0) {
			Vrcamera.LOGGER.error("VRCamera: the camera window needs the OpenGL renderer of the game");
			unsupported = true;
			return false;
		}
		// It must not take the keyboard from the game when it opens. And it is shown once it is where it was the
		// last time, not before
		SDLHints.SDL_SetHint(SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN, "0");
		window = SDLVideo.SDL_CreateWindow("VRCamera", WIDTH, HEIGHT,
				SDLVideo.SDL_WINDOW_OPENGL | SDLVideo.SDL_WINDOW_RESIZABLE | SDLVideo.SDL_WINDOW_HIDDEN);
		if (window == 0) {
			SDLHints.SDL_SetHint(SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN, "1");
			Vrcamera.LOGGER.error("VRCamera: the camera window could not be made: {}", SDLError.SDL_GetError());
			unsupported = true;
			return false;
		}
		windowId = SDLVideo.SDL_GetWindowID(window);
		closeRequested = false;
		keepEventsFromGame();
		Handle handle = new Handle();
		place = new WindowPlace(handle, WIDTH, HEIGHT);
		input = new WindowInput(handle);
		place.putBack();
		SDLVideo.SDL_ShowWindow(window);
		SDLHints.SDL_SetHint(SDLHints.SDL_HINT_WINDOW_ACTIVATE_WHEN_SHOWN, "1");
		return true;
	}

	/**
	 * The game takes every event of a window for one of its own window: it would close when this one is closed,
	 * and take the size of this one for its own. What happens to this window is taken out before the game sees it.
	 * Keys and the mouse are told to the game with the window they are from, and it leaves those alone itself.
	 */
	private static void keepEventsFromGame() {
		filter = SDL_EventFilter.create((userdata, event) -> {
			SDL_Event happened = SDL_Event.create(event);
			int type = happened.type();
			if (type < SDLEvents.SDL_EVENT_WINDOW_FIRST || type > SDLEvents.SDL_EVENT_WINDOW_LAST ||
					happened.window().windowID() != windowId) {
				return true;
			}
			if (type == SDLEvents.SDL_EVENT_WINDOW_CLOSE_REQUESTED) {
				closeRequested = true;
			}
			return false;
		});
		SDLEvents.SDL_SetEventFilter(filter, 0);
	}

	public static void close() {
		if (window == 0) {
			return;
		}
		place.remember();
		SDLEvents.nSDL_SetEventFilter(0, 0);
		filter.free();
		filter = null;
		if (frameBuffer != 0) {
			GL30.glDeleteFramebuffers(frameBuffer);
		}
		SDLVideo.SDL_DestroyWindow(window);
		window = 0;
		windowId = 0;
		closeRequested = false;
		place = null;
		input = null;
		frameBuffer = 0;
		scrolled = 0;
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
		if (window == 0 || (SDLVideo.SDL_GetWindowFlags(window) & SDLVideo.SDL_WINDOW_MINIMIZED) != 0) {
			return null;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer width = stack.callocInt(1);
			IntBuffer height = stack.callocInt(1);
			SDLVideo.SDL_GetWindowSizeInPixels(window, width, height);
			return width.get(0) > 0 && height.get(0) > 0 ? new int[]{width.get(0), height.get(0)} : null;
		}
	}

	/**
	 * copies the picture to the window
	 *
	 * @param guides if the lines that help to frame a picture go over it, the ones that were picked with H
	 * @param fill if the picture was drawn for the shape of the window, and fills it
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
		if (frameBuffer == 0) {
			frameBuffer = GL30.glGenFramebuffers();
		}
		long context = SDLVideo.SDL_GL_GetCurrentContext();
		long game = SDLVideo.SDL_GL_GetCurrentWindow();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int readBuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
			int drawBuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
			boolean scissors = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
			IntBuffer viewport = stack.mallocInt(4);
			IntBuffer scissor = stack.mallocInt(4);
			FloatBuffer clearColor = stack.mallocFloat(4);
			ByteBuffer colorMask = stack.malloc(4);
			GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
			GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor);
			GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
			GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);
			IntBuffer interval = stack.mallocInt(1);
			boolean intervalKnown = SDLVideo.SDL_GL_GetSwapInterval(interval);
			if (!SDLVideo.SDL_GL_MakeCurrent(window, context)) {
				throw new IllegalStateException("the camera window can't be drawn into: " + SDLError.SDL_GetError());
			}
			try {
				// not in step with the monitor: the game waits for that once per frame already
				SDLVideo.SDL_GL_SetSwapInterval(0);
				GL11.glColorMask(true, true, true, true);
				PictureBlit.draw(picture, texture, frameBuffer, size[0], size[1], fill,
						guides ? WindowInput.guide() : null);
				SDLVideo.SDL_GL_SwapWindow(window);
			} finally {
				SDLVideo.SDL_GL_MakeCurrent(game, context);
				if (intervalKnown) {
					SDLVideo.SDL_GL_SetSwapInterval(interval.get(0));
				}
				GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readBuffer);
				GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawBuffer);
				GL11.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
				GL11.glScissor(scissor.get(0), scissor.get(1), scissor.get(2), scissor.get(3));
				if (scissors) {
					GL11.glEnable(GL11.GL_SCISSOR_TEST);
				}
				GL11.glClearColor(clearColor.get(0), clearColor.get(1), clearColor.get(2), clearColor.get(3));
				GL11.glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0,
						colorMask.get(3) != 0);
			}
		}
	}

	/**
	 * @return if the window of the camera is the one the keyboard goes to
	 */
	public static boolean isFocused() {
		return window != 0 && SDLKeyboard.SDL_GetKeyboardFocus() == window;
	}

	/**
	 * @param key a key of the keyboard as the game numbers them
	 * @return if it is held down in the window of the camera
	 */
	public static boolean isKeyDown(int key) {
		if (key < 0 || !isFocused()) {
			return false;
		}
		// the keyboard is asked, not a window: the keys are those of the window the keyboard goes to
		ByteBuffer keys = SDLKeyboard.SDL_GetKeyboardState();
		return keys != null && key < keys.limit() && keys.get(key) != 0;
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
	 * The game was told of a turn of the wheel, with the window it happened over.
	 *
	 * @return if it was for this window, and is not for the game
	 */
	public static boolean onScroll(long ofWindow, double amount) {
		if (window == 0 || ofWindow != window) {
			return false;
		}
		scrolled += amount;
		return true;
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
		return window == 0 || !input.hasMouse() || !isFocused() ? new double[2] : readMouse();
	}

	private static double[] readMouse() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			FloatBuffer x = stack.callocFloat(1);
			FloatBuffer y = stack.callocFloat(1);
			SDLMouse.SDL_GetRelativeMouseState(x, y);
			return new double[]{x.get(0), y.get(0)};
		}
	}
}
