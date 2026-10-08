package ru.deelter.vrcamera.client.desktop;

/**
 * a part of the desktop or of a window: a monitor, where a window is, where a picture is in it
 */
record Box(int x, int y, int width, int height) {
	/**
	 * @return null if those are not the four numbers of a box that has a size
	 */
	static Box of(int[] values) {
		return values == null || values.length != 4 || values[2] < 1 || values[3] < 1 ? null :
				new Box(values[0], values[1], values[2], values[3]);
	}

	int middleX() {
		return this.x + this.width / 2;
	}

	int middleY() {
		return this.y + this.height / 2;
	}

	boolean has(int pointX, int pointY) {
		return pointX >= this.x && pointX < this.x + this.width && pointY >= this.y && pointY < this.y + this.height;
	}

	int[] toArray() {
		return new int[]{this.x, this.y, this.width, this.height};
	}
}
