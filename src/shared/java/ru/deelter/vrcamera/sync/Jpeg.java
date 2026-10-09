package ru.deelter.vrcamera.sync;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * packs pictures the same way in the mod and on the server
 */
public final class Jpeg {

	private Jpeg() {
	}

	/**
	 * @param quality 0 = smallest, 1 = best
	 */
	public static byte[] encode(BufferedImage image, float quality) throws IOException {
		final Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
		if (!writers.hasNext()) {
			throw new IOException("this Java can't write JPEG");
		}
		final ImageWriter writer = writers.next();
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream(16_384);
		try (final ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
			final ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);
			writer.setOutput(out);
			writer.write(null, new IIOImage(image, null, null), param);
		} finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}
}
