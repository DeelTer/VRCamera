package ru.deelter.vrcamera.client.desktop;

/**
 * The lines over the picture of the camera that help to frame it. Where they are is given in parts of the picture,
 * from 0 to 1.
 */
enum FrameGuide {
	THIRDS(1.0 / 3.0, 2.0 / 3.0),
	GOLDEN(0.382, 0.618),

	SPIRAL,
	HALVES(0.5),

	UPRIGHT,

	SAFE,
	NONE;

	private static final double UPRIGHT_SHAPE = 9.0 / 16.0;
	private static final double[] NO_LINES = {};
	private static final double[] SAFE_FRAMES = {0.05, 0.1};
	private static final double[][] NO_CURVE = {};

	private static final int SPIRAL_TURNS = 10;
	private static final int TURN_POINTS = 48;
	private static final double[][] SPIRAL_CURVE = spiral();
	private static final FrameGuide[] ALL = values();

	private final double[] lines;

	FrameGuide(double... lines) {
		this.lines = lines;
	}

	/**
	 * A golden rectangle has a square cut off its left, then off the top of what is left, off the right, off the
	 * bottom, and so on around. A quarter circle through each square is the spiral.
	 */
	private static double[][] spiral() {
		final double golden = (1.0 + Math.sqrt(5.0)) / 2.0;
		double x = 0;
		double y = 0;
		double width = golden;
		double height = 1.0;
		final double[][] points = new double[SPIRAL_TURNS * (TURN_POINTS + 1)][];
		for (int turn = 0; turn < SPIRAL_TURNS; turn++) {
			double side;
			double centerX;
			double centerY;
			switch (turn % 4) {
				case 0 -> {
					side = height;
					centerX = x + side;
					centerY = y;
					x += side;
					width -= side;
				}
				case 1 -> {
					side = width;
					centerX = x;
					centerY = y + height - side;
					height -= side;
				}
				case 2 -> {
					side = height;
					centerX = x + width - side;
					centerY = y + height;
					width -= side;
				}
				default -> {
					side = width;
					centerX = x + width;
					centerY = y + side;
					y += side;
					height -= side;
				}
			}

			final double from = Math.PI - turn * Math.PI / 2.0;
			for (int point = 0; point <= TURN_POINTS; point++) {
				final double angle = from - Math.PI / 2.0 * point / TURN_POINTS;
				points[turn * (TURN_POINTS + 1) + point] = new double[]{
						(centerX + side * Math.cos(angle)) / golden, centerY + side * Math.sin(angle)};
			}
		}
		return points;
	}

	FrameGuide next() {
		return ALL[(ordinal() + 1) % ALL.length];
	}

	/**
	 * @return where the lines from top to bottom are, for a picture of that size
	 */
	double[] across(int width, int height) {
		if (this != UPRIGHT) {
			return lines;
		}
		final double part = height * UPRIGHT_SHAPE / width;
		return part >= 1.0 ? NO_LINES : new double[]{0.5 - part / 2.0, 0.5 + part / 2.0};
	}

	/**
	 * @return where the lines from side to side are
	 */
	double[] up() {
		return lines;
	}

	/**
	 * @return the points of a curve over the picture, from its left and from its bottom
	 */
	double[][] curve() {
		return this == SPIRAL ? SPIRAL_CURVE : NO_CURVE;
	}

	/**
	 * @return how far in from each edge of the picture its frames are
	 */
	double[] frames() {
		return this == SAFE ? SAFE_FRAMES : NO_LINES;
	}
}
