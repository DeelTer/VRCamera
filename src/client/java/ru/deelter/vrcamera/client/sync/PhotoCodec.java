package ru.deelter.vrcamera.client.sync;

import org.jetbrains.annotations.NotNull;
import ru.deelter.vrcamera.sync.IndexedPng;
import ru.deelter.vrcamera.sync.Jpeg;
import ru.deelter.vrcamera.sync.Protocol;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Packs the picture of a sheet for the network and unpacks what comes from it. Plain Java, safe to run off the
 * game thread.
 * <p>
 * Small, and a JPEG unless it is pixel art, see {@link IndexedPng}: a sheet is a hand wide in the world, and
 * the channel it travels on is shared with the game and every other mod.
 */
public final class PhotoCodec {

	private static final int SENT_WIDTH = 256;
	private static final float[] QUALITIES = {0.72F, 0.6F, 0.48F, 0.36F, 0.25F};

	private PhotoCodec() {
	}

	/**
	 * @param maxBytes what the server takes
	 * @param indexed  if the server takes a PNG with a palette
	 * @return the picture no larger than that: one of few colours as a PNG with a palette where that is taken,
	 * as a JPEG otherwise
	 */
	public static byte[] pack(Picture picture, int maxBytes, boolean indexed) throws IOException {
		final BufferedImage source = new BufferedImage(picture.width, picture.height, BufferedImage.TYPE_INT_RGB);
		source.setRGB(0, 0, picture.width, picture.height, picture.argb, 0, picture.width);
		int width = Math.min(SENT_WIDTH, picture.width);
		if (indexed) {
			final int height = Math.max(1, Math.round(width * picture.height / (float) picture.width));
			final byte[] packed = height > Protocol.MAX_IMAGE_SIDE ? null : IndexedPng.encode(
					scale(source, width, height, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR));
			if (packed != null && packed.length <= maxBytes) {
				return packed;
			}
		}

		for (int attempt = 0; attempt < 4; attempt++) {
			int height = Math.max(1, Math.round(width * picture.height / (float) picture.width));
			int scaledWidth = width;
			if (height > Protocol.MAX_IMAGE_SIDE) {

				scaledWidth = Math.max(1, Math.round(width * Protocol.MAX_IMAGE_SIDE / (float) height));
				height = Protocol.MAX_IMAGE_SIDE;
			}
			final BufferedImage scaled = scale(source, scaledWidth, height,
					RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			for (final float quality : QUALITIES) {
				final byte[] packed = Jpeg.encode(scaled, quality);
				if (packed.length <= maxBytes) {
					return packed;
				}
			}
			width = Math.max(16, width * 3 / 4);
		}
		throw new IOException("picture does not fit into " + maxBytes + " bytes");
	}

	/**
	 * Unpacks a picture that came from a server. Its size is looked at before anything is unpacked: a few bytes
	 * can claim to be a picture of a billion pixels.
	 */
	@NotNull
	public static Picture unpack(byte[] packed) throws IOException {
		if (packed.length > Protocol.MAX_IMAGE_BYTES) {
			throw new IOException("picture of " + packed.length + " bytes");
		}
		try (final ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(packed))) {
			final Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext()) {
				throw new IOException("not a picture");
			}
			final ImageReader reader = readers.next();
			try {
				reader.setInput(in);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width < 1 || height < 1 || width > Protocol.MAX_IMAGE_SIDE || height > Protocol.MAX_IMAGE_SIDE) {
					throw new IOException("picture of " + width + "x" + height);
				}
				final BufferedImage image = reader.read(0);
				return new Picture(width, height, image.getRGB(0, 0, width, height, null, 0, width));
			} finally {
				reader.dispose();
			}
		} catch (RuntimeException e) {

			throw new IOException(e);
		}
	}

	private static BufferedImage scale(BufferedImage source, int width, int height, Object smoothing) {
		if (source.getWidth() == width && source.getHeight() == height) {
			return source;
		}
		final BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		final Graphics2D graphics = scaled.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, smoothing);
		graphics.drawImage(source, 0, 0, width, height, null);
		graphics.dispose();
		return scaled;
	}

	public record Picture(int width, int height, int[] argb) {
	}
}
