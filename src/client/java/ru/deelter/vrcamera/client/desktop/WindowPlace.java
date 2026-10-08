package ru.deelter.vrcamera.client.desktop;

import ru.deelter.vrcamera.client.config.CameraConfig;

/**
 * Where the window of the camera is on the desktop: it comes back where it was, fills a monitor and goes back, and
 * is kept where it can be got hold of. The same for every version of the game, which only differ in how a window is
 * asked where it is and told where to go.
 */
final class WindowPlace {
	private final Native window;
	private final int width;
	private final int height;
	private Box windowed;

	/**
	 * @param width how wide the window is the first time
	 */
	WindowPlace(Native window, int width, int height) {
		this.window = window;
		this.width = width;
		this.height = height;
	}

	/**
	 * Where the window was when it was closed the last time, and as large, and over the whole monitor if it was.
	 * Not if that monitor is gone: a window nobody sees is no help
	 */
	void putBack() {
		final CameraConfig config = CameraConfig.current();
		final Box place = Box.of(config.outputWindowPlace);
		if (place != null && window.monitorOf(place) != null) {
			window.move(place);
			keepReachable();
			if (config.outputWindowFull) {
				toggleFullscreen();
			}
		}
		if (config.hasOutputSize() && windowed == null) {
			resize(config.outputWidth, config.outputHeight);
		}
	}

	/**
	 * Makes the window that large, where it is. A program that records a window gets as many pixels as the window
	 * has: for a picture larger than the monitor, the window has to be larger than the monitor
	 */
	void resize(int width, int height) {
		if (windowed != null) {
			toggleFullscreen();
		}
		final Box place = window.place();
		window.move(new Box(place.x(), place.y(), width, height));
		keepReachable();
	}

	void remember() {
		final CameraConfig config = CameraConfig.current();
		config.outputWindowFull = windowed != null;
		config.outputWindowPlace = (windowed != null ? windowed : window.place()).toArray();
		config.save();
	}

	/**
	 * the window as it is the first time: its usual size, in the middle of the monitor the game is on
	 */
	void reset() {
		if (windowed != null) {
			toggleFullscreen();
		}
		final Box monitor = window.monitorOf(window.gamePlace());
		final Box place = window.place();
		window.move(monitor == null ? new Box(place.x(), place.y(), width, height) :
				new Box(monitor.middleX() - width / 2, monitor.middleY() - height / 2, width,
						height));
		keepReachable();
	}

	/**
	 * Fills the monitor the window is on, without a frame, or goes back to the window it was. Not the fullscreen
	 * a game takes for itself: that one goes away as soon as another window is clicked, which is the game
	 */
	void toggleFullscreen() {
		if (windowed != null) {
			window.setBordered(true);
			window.move(windowed);
			windowed = null;
			keepReachable();
			return;
		}
		final Box place = window.place();
		final Box monitor = window.monitorOf(place);
		if (monitor != null) {
			windowed = place;
			window.setBordered(false);
			window.move(monitor);
		}
	}

	/**
	 * A window is moved by the bar at its top. With that bar off the monitor, or the window larger than the
	 * monitor, there is no getting hold of it anymore: it is made to fit, and its bar is brought back into view
	 */
	private void keepReachable() {
		final Box place = window.place();
		final Box monitor = window.monitorOf(place);
		if (monitor == null) {
			return;
		}
		final int bar = window.barHeight();
		if (CameraConfig.current().hasOutputSize()) {

			if (place.y() < monitor.y() + bar) {
				window.move(new Box(place.x(), monitor.y() + bar, place.width(), place.height()));
			}
			return;
		}
		final int fitWidth = Math.min(place.width(), monitor.width());
		final int fitHeight = Math.min(place.height(), monitor.height() - bar);
		final Box fitted = new Box(Math.clamp(place.x(), monitor.x(), monitor.x() + monitor.width() - fitWidth),
				Math.clamp(place.y(), monitor.y() + bar, monitor.y() + monitor.height() - fitHeight), fitWidth,
				fitHeight);
		if (!fitted.equals(place)) {
			window.move(fitted);
		}
	}

	/**
	 * the window of the camera, as the library the game makes its windows with has it
	 */
	interface Native {
		Box place();

		void move(Box to);

		/**
		 * @return the monitor the middle of that part of the desktop is on, null if it is on none
		 */
		Box monitorOf(Box place);

		void setBordered(boolean bordered);

		/**
		 * @return how high the bar at the top of the window is, that it is moved by
		 */
		int barHeight();

		/**
		 * @return where the window of the game is
		 */
		Box gamePlace();
	}
}
