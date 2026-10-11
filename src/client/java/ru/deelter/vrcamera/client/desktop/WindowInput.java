package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * The keys of the window of the camera itself, and its hold on the mouse. The same for every version of the game,
 * which only differ in how a window is asked what is held down in it.
 */
final class WindowInput {
	private static FrameGuide guide = FrameGuide.THIRDS;
	private final Native window;
	private boolean fullKeyDown;
	private boolean escapeDown;
	private boolean captured;
	private boolean letGo;

	WindowInput(Native window) {
		this.window = window;
	}

	static FrameGuide guide() {
		return guide;
	}

	/**
	 * the next kind of lines over the picture, see {@link FrameGuide}. It has a key of the game, which works in
	 * the window of the game as well
	 */
	static void nextGuide() {
		guide = guide.next();
	}

	/**
	 * F11: over the whole monitor the window is on, and back
	 */
	void handleKeys(Runnable toggleFullscreen) {
		final boolean full = window.isKeyDown(InputConstants.KEY_F11);
		if (full && !fullKeyDown) {
			toggleFullscreen.run();
		}
		fullKeyDown = full;
	}

	/**
	 * Holds the mouse in the window while that is wanted. Escape lets it go all the same, to move the window or to
	 * leave it, and a click into the window takes it back. Escape with the mouse free goes back to the game.
	 *
	 * @return if the window has the mouse now
	 */
	boolean capture(boolean wanted) {
		final boolean escape = window.isKeyDown(InputConstants.KEY_ESCAPE);
		final boolean pressed = escape && !escapeDown;
		escapeDown = escape;
		if (pressed && !captured) {
			window.focusGame();
		}
		if (!wanted) {
			letGo = false;
		} else if (escape) {
			letGo = true;
		} else if (window.isLeftButtonDown()) {
			letGo = false;
		}
		final boolean held = wanted && !letGo;
		if (held != captured) {
			captured = held;
			window.setMouseCaptured(held);
		}
		return held;
	}

	boolean hasMouse() {
		return captured;
	}

	/**
	 * the window of the camera, as the library the game makes its windows with has it
	 */
	interface Native {
		/**
		 * @param key a key of the keyboard as the game numbers them
		 */
		boolean isKeyDown(int key);

		boolean isLeftButtonDown();

		/**
		 * takes the mouse for the window, hidden and held inside of it, or lets it go again
		 */
		void setMouseCaptured(boolean captured);

		/**
		 * the keyboard goes to the window of the game, which comes to the front
		 */
		void focusGame();
	}
}
