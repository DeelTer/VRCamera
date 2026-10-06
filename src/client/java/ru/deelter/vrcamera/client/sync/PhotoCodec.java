package ru.deelter.vrcamera.client.sync;

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
 * JPEG and small: a sheet is a hand wide in the world, and the channel it travels on is shared with the game and
 * every other mod.
 */
public final class PhotoCodec {
	// pixels across a picture that is sent. A quarter of the pixels of what is shown on this client
	private static final int SENT_WIDTH = 256;
	private static final float[] QUALITIES = {0.72F, 0.6F, 0.48F, 0.36F, 0.25F};

	public record Picture(int width, int height, int[] argb) {
	}

	private PhotoCodec() {
	}

	/**
	 * @param maxBytes what the server takes
	 * @return the picture as a JPEG no larger than that
	 */
	public static byte[] pack(Picture picture, int maxBytes) throws IOException {
		BufferedImage source = new BufferedImage(picture.width, picture.height, BufferedImage.TYPE_INT_RGB);
		source.setRGB(0, 0, picture.width, picture.height, picture.argb, 0, picture.width);
		int width = Math.min(SENT_WIDTH, picture.width);
		// first with less quality, and if that is not enough with fewer pixels
		for (int attempt = 0; attempt < 4; attempt++) {
			int height = Math.max(1, Math.round(width * picture.height / (float) picture.width));
			int scaledWidth = width;
			if (height > Protocol.MAX_IMAGE_SIDE) {
				// a tall picture: its height is what has to fit, narrower and not squashed
				scaledWidth = Math.max(1, Math.round(width * Protocol.MAX_IMAGE_SIDE / (float) height));
				height = Protocol.MAX_IMAGE_SIDE;
			}
			BufferedImage scaled = scale(source, scaledWidth, height);
			for (float quality : QUALITIES) {
				byte[] packed = Jpeg.encode(scaled, quality);
				if (packed.length <= maxBytes) {
					return packed;
				}
			}
			width = Math.max(16, width * 3 / 4);
		}
		throw new IOException("picture does not fit into " + maxBytes + " bytes");
	}

	private static BufferedImage scale(BufferedImage source, int width, int height) {
		if (source.getWidth() == width && source.getHeight() == height) {
			return source;
		}
		BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = scaled.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		graphics.drawImage(source, 0, 0, width, height, null);
		graphics.dispose();
		return scaled;
	}

	/**
	 * Unpacks a picture that came from a server. Its size is looked at before anything is unpacked: a few bytes
	 * can claim to be a picture of a billion pixels.
	 */
	public static Picture unpack(byte[] packed) throws IOException {
		if (packed.length > Protocol.MAX_IMAGE_BYTES) {
			throw new IOException("picture of " + packed.length + " bytes");
		}
		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(packed))) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext()) {
				throw new IOException("not a picture");
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(in);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width < 1 || height < 1 || width > Protocol.MAX_IMAGE_SIDE || height > Protocol.MAX_IMAGE_SIDE) {
					throw new IOException("picture of " + width + "x" + height);
				}
				BufferedImage image = reader.read(0);
				return new Picture(width, height, image.getRGB(0, 0, width, height, null, 0, width));
			} finally {
				reader.dispose();
			}
		} catch (RuntimeException e) {
			// the readers of Java throw all kinds of things at broken files
			throw new IOException(e);
		}
	}
}
