package ru.deelter.vrcamera.client.desktop;

/**
 * The lines over the picture of the camera that help to frame it. Where they are is given in parts of the picture,
 * from 0 to 1.
 */
enum FrameGuide {
	THIRDS(1.0 / 3.0, 2.0 / 3.0),
	GOLDEN(0.382, 0.618),
	HALVES(0.5),
	// what of a wide picture is left in an upright one, for a video that is cut to both
	UPRIGHT,
	// Frames and not lines: what is inside the outer one is seen on any screen, what is inside the inner one is not
	// under the buttons and titles a player puts over a video
	SAFE,
	NONE;

	private static final double UPRIGHT_SHAPE = 9.0 / 16.0;
	private static final double[] NO_LINES = {};
	private static final double[] SAFE_FRAMES = {0.05, 0.1};
	private static final FrameGuide[] ALL = values();

	private final double[] lines;

	FrameGuide(double... lines) {
		this.lines = lines;
	}

	FrameGuide next() {
		return ALL[(ordinal() + 1) % ALL.length];
	}

	/**
	 * @return where the lines from top to bottom are, for a picture of that size
	 */
	double[] across(int width, int height) {
		if (this != UPRIGHT) {
			return this.lines;
		}
		double part = height * UPRIGHT_SHAPE / width;
		return part >= 1.0 ? NO_LINES : new double[]{0.5 - part / 2.0, 0.5 + part / 2.0};
	}

	/**
	 * @return where the lines from side to side are
	 */
	double[] up() {
		return this.lines;
	}

	/**
	 * @return how far in from each edge of the picture its frames are
	 */
	double[] frames() {
		return this == SAFE ? SAFE_FRAMES : NO_LINES;
	}
}
