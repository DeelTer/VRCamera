package ru.deelter.vrcamera.sync;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Packs a picture of few colours as a PNG with a palette, the same way in the mod and on the server. Pixel art
 * is smaller that way than as a JPEG, and stays what it was: a JPEG smears the edges of its pixels.
 */
public final class IndexedPng {
	public static final int MOST_COLORS = 256;

	private IndexedPng() {
	}

	/**
	 * @return null if the picture has more colours than a palette holds
	 */
	public static byte[] encode(BufferedImage image) throws IOException {
		final int width = image.getWidth();
		final int height = image.getHeight();
		final int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
		final Map<Integer, Integer> places = new HashMap<>();
		final int[] indexes = new int[pixels.length];
		for (int pixel = 0; pixel < pixels.length; pixel++) {
			final int color = pixels[pixel] & 0xFFFFFF;
			Integer place = places.get(color);
			if (place == null) {
				if (places.size() >= MOST_COLORS) {
					return null;
				}
				place = places.size();
				places.put(color, place);
			}
			indexes[pixel] = place;
		}
		final int count = places.size();
		final int bits = count <= 2 ? 1 : count <= 4 ? 2 : count <= 16 ? 4 : 8;
		final byte[] reds = new byte[count];
		final byte[] greens = new byte[count];
		final byte[] blues = new byte[count];
		places.forEach((color, place) -> {
			reds[place] = (byte) (color >> 16);
			greens[place] = (byte) (color >> 8);
			blues[place] = color.byteValue();
		});
		final BufferedImage indexed = new BufferedImage(width, height,
				bits == 8 ? BufferedImage.TYPE_BYTE_INDEXED : BufferedImage.TYPE_BYTE_BINARY,
				new IndexColorModel(bits, count, reds, greens, blues));
		indexed.getRaster().setSamples(0, 0, width, height, 0, indexes);

		final ByteArrayOutputStream bytes = new ByteArrayOutputStream(16_384);
		if (!ImageIO.write(indexed, "png", bytes)) {
			throw new IOException("this Java can't write PNG");
		}
		return bytes.toByteArray();
	}
}
