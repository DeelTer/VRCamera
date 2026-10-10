package ru.deelter.vrcamera.client.photo;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the picture of a sheet into pixel art in the colours of a map of the game: few large pixels, each in one
 * of the colours a map can show, with a light pattern where two of them are mixed.
 */
public final class PixelArt {
	private static final int MOST_PIXELS = 128;
	private static final int LEAST_PIXELS = 32;

	private static final int SENT_PIXELS = 256;

	private static final double DITHER = 10.0;
	private static final int[] PATTERN = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

	private static int[] palette;

	private PixelArt() {
	}

	/**
	 * @param picture  closed if another one is returned in its place
	 * @param strength 0 = leave it as it is, 1 = as few pixels as it gets
	 */
	public static NativeImage apply(NativeImage picture, double strength) {
		if (strength <= 0) {
			return picture;
		}
		final int width = picture.getWidth();
		final int height = picture.getHeight();
		final int longer = (int) Math.round(MOST_PIXELS - (MOST_PIXELS - LEAST_PIXELS) * Math.min(strength, 1.0));
		final int cellsX = width >= height ? longer : Math.max(1, Math.round(longer * width / (float) height));
		final int cellsY = width >= height ? Math.max(1, Math.round(longer * height / (float) width)) : longer;
		if (cellsX >= width || cellsY >= height) {
			return picture;
		}
		final int[] colors = palette();
		final int square = Math.max(1, SENT_PIXELS / longer);
		final NativeImage art = new NativeImage(cellsX * square, cellsY * square, false);
		try {
			for (int cellY = 0; cellY < cellsY; cellY++) {
				final int top = cellY * height / cellsY;
				final int bottom = Math.max(top + 1, (cellY + 1) * height / cellsY);
				for (int cellX = 0; cellX < cellsX; cellX++) {
					final int left = cellX * width / cellsX;
					final int right = Math.max(left + 1, (cellX + 1) * width / cellsX);
					final int[] mean = meanColor(picture, left, top, right, bottom);
					final double shift = (PATTERN[(cellY & 3) * 4 + (cellX & 3)] / 16.0 - 0.47) * DITHER;
					final int color = 0xFF000000 | nearest(colors, shifted(mean[0], shift), shifted(mean[1], shift),
							shifted(mean[2], shift));
					fill(art, cellX * square, cellY * square, square, color);
				}
			}
		} catch (RuntimeException e) {
			art.close();
			throw e;
		}
		picture.close();
		return art;
	}

	/**
	 * @return red, green and blue of a part of a picture, evened out over it
	 */
	private static int[] meanColor(NativeImage picture, int left, int top, int right, int bottom) {
		int red = 0;
		int green = 0;
		int blue = 0;
		for (int y = top; y < bottom; y++) {
			for (int x = left; x < right; x++) {
				final int pixel = picture.getPixel(x, y);
				red += pixel >> 16 & 0xFF;
				green += pixel >> 8 & 0xFF;
				blue += pixel & 0xFF;
			}
		}
		final int count = (right - left) * (bottom - top);
		return new int[]{red / count, green / count, blue / count};
	}

	private static void fill(NativeImage art, int left, int top, int size, int color) {
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				art.setPixel(left + x, top + y, color);
			}
		}
	}

	private static int shifted(int value, double shift) {
		return (int) Math.clamp(value + shift, 0.0, 255.0);
	}

	private static int nearest(int[] colors, int red, int green, int blue) {
		int best = 0;
		long bestDistance = Long.MAX_VALUE;
		for (final int color : colors) {
			final int otherRed = color >> 16 & 0xFF;
			final int mean = (red + otherRed) / 2;
			final long redOff = red - otherRed;
			final long greenOff = green - (color >> 8 & 0xFF);
			final long blueOff = blue - (color & 0xFF);
			final long distance = ((512 + mean) * redOff * redOff >> 8) + 4 * greenOff * greenOff +
					((767 - mean) * blueOff * blueOff >> 8);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = color;
			}
		}
		return best;
	}

	/**
	 * @return every colour a map of the game can show, as RGB
	 */
	private static int[] palette() {
		if (palette == null) {
			final List<Integer> colors = new ArrayList<>();
			for (int id = 1; id < 64; id++) {
				final int base = MapColor.byId(id).col;
				if (base == 0) {
					continue;
				}
				for (final MapColor.Brightness brightness : MapColor.Brightness.values()) {
					final int shade = brightness.modifier;
					colors.add((base >> 16 & 0xFF) * shade / 255 << 16 | (base >> 8 & 0xFF) * shade / 255 << 8 |
							(base & 0xFF) * shade / 255);
				}
			}
			palette = colors.stream().mapToInt(Integer::intValue).toArray();
		}
		return palette;
	}
}
