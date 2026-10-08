package ru.deelter.vrcamera.client.desktop;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * a part of the desktop or of a window: a monitor, where a window is, where a picture is in it
 */
record Box(int x, int y, int width, int height) {
	int middleX() {
		return x + width / 2;
	}

	int middleY() {
		return y + height / 2;
	}

	boolean has(int pointX, int pointY) {
		return pointX >= x && pointX < x + width && pointY >= y && pointY < y + height;
	}

	@NotNull
	int[] toArray() {
		return new int[]{x, y, width, height};
	}

	/**
	 * @return null if those are not the four numbers of a box that has a size
	 */
	@Nullable
	static Box of(@Nullable int[] values) {
		return values == null || values.length != 4 || values[2] < 1 || values[3] < 1 ? null :
				new Box(values[0], values[1], values[2], values[3]);
	}
}
