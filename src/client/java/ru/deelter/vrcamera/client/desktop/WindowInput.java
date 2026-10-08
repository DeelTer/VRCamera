package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * The keys of the window of the camera itself, and its hold on the mouse. The same for every version of the game,
 * which only differ in how a window is asked what is held down in it.
 */
final class WindowInput {
	// kept from one window to the next: whoever turned the lines off does not want them back with every window
	private static FrameGuide guide = FrameGuide.THIRDS;
	private final Native window;
	private boolean fullKeyDown;
	private boolean guideKeyDown;
	private boolean captured;
	private boolean letGo;

	WindowInput(Native window) {
		this.window = window;
	}

	static FrameGuide guide() {
		return guide;
	}

	/**
	 * F11: over the whole monitor the window is on, and back. H: the next kind of lines over the picture, see
	 * {@link FrameGuide}
	 */
	void handleKeys(Runnable toggleFullscreen) {
		boolean full = this.window.isKeyDown(InputConstants.KEY_F11);
		if (full && !this.fullKeyDown) {
			toggleFullscreen.run();
		}
		this.fullKeyDown = full;
		boolean next = this.window.isKeyDown(InputConstants.KEY_H);
		if (next && !this.guideKeyDown) {
			guide = guide.next();
		}
		this.guideKeyDown = next;
	}

	/**
	 * Holds the mouse in the window while that is wanted. Escape lets it go all the same, to move the window or to
	 * leave it, and a click into the window takes it back.
	 *
	 * @return if the window has the mouse now
	 */
	boolean capture(boolean wanted) {
		if (!wanted) {
			this.letGo = false;
		} else if (this.window.isKeyDown(InputConstants.KEY_ESCAPE)) {
			this.letGo = true;
		} else if (this.window.isLeftButtonDown()) {
			this.letGo = false;
		}
		boolean held = wanted && !this.letGo;
		if (held != this.captured) {
			this.captured = held;
			this.window.setMouseCaptured(held);
		}
		return held;
	}

	boolean hasMouse() {
		return this.captured;
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
	}
}
