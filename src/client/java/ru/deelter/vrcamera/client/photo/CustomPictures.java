package ru.deelter.vrcamera.client.photo;

import ru.deelter.vrcamera.client.sync.PhotoCodec;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Loads a picture from the internet to put it on a sheet, for {@code /vrcam load}. Plain Java, runs off the game
 * thread.
 * <p>
 * Only the client of the player who asked for it ever opens the address. The others get what the server made of
 * the picture, never the address itself.
 */
public final class CustomPictures {
	private static final int MAX_BYTES = 8 * 1024 * 1024;
	private static final int DOWNLOAD_SECONDS = 30;
	// what a file may claim to be before it is unpacked, a few bytes can claim a billion pixels
	private static final int MAX_SIDE = 8192;
	private static final long MAX_PIXELS = 40_000_000L;
	// pixels along the longer side of a sheet, the same as for a photo
	private static final int SHEET_PIXELS = 384;
	// The shapes a sheet can have, height by width: wide, square and tall. A picture is cut to the nearest one
	private static final float[] SHAPES = {9.0F / 16.0F, 1.0F, 16.0F / 9.0F};

	/**
	 * @param original the file as it was downloaded, to keep
	 * @param format   what kind of file that is, for its name
	 */
	public record Loaded(PhotoCodec.Picture picture, byte[] original, String format) {}

	private CustomPictures() {
	}

	public static Loaded load(String address) throws IOException {
		URI uri;
		try {
			uri = URI.create(address.trim());
		} catch (IllegalArgumentException e) {
			throw new IOException("not an address");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		// nothing but the web: not files of this computer, not whatever else Java knows how to open
		if (!scheme.equals("http") && !scheme.equals("https")) {
			throw new IOException("only http and https addresses");
		}
		byte[] original = download(uri);

		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext()) {
				throw new IOException("not a picture this can read: PNG, JPEG or GIF");
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(in);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE ||
						(long) width * height > MAX_PIXELS)
				{
					throw new IOException("picture of " + width + "x" + height + " is too large");
				}
				String format = reader.getFormatName().toLowerCase(Locale.ROOT);
				return new Loaded(fit(reader.read(0)), original, format.equals("jpeg") ? "jpg" : format);
			} finally {
				reader.dispose();
			}
		} catch (RuntimeException e) {
			// the readers of Java throw all kinds of things at broken files
			throw new IOException("broken picture");
		}
	}

	private static byte[] download(URI uri) throws IOException {
		HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(8))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		HttpRequest request;
		try {
			request = HttpRequest.newBuilder(uri)
					.timeout(Duration.ofSeconds(20))
					.header("User-Agent", "VRCamera")
					.GET()
					.build();
		} catch (IllegalArgumentException e) {
			client.shutdownNow();
			throw new IOException("not an address");
		}
		// The whole download has this long. A server that sends a byte now and then would hold it forever otherwise
		CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request,
				HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_BYTES));
		try {
			HttpResponse<byte[]> response = pending.get(DOWNLOAD_SECONDS, TimeUnit.SECONDS);
			if (response.statusCode() != 200) {
				throw new IOException("the server answered " + response.statusCode());
			}
			return response.body();
		} catch (TimeoutException e) {
			throw new IOException("took longer than " + DOWNLOAD_SECONDS + " seconds");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			String message = String.valueOf(cause.getMessage());
			if (message.contains("capacity")) {
				throw new IOException("file is larger than " + MAX_BYTES / (1024 * 1024) + " MB");
			}
			throw new IOException(cause.getMessage() == null ? "could not connect" : message);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted");
		} finally {
			pending.cancel(true);
			client.shutdownNow();
		}
	}

	/**
	 * Cuts the picture to the nearest shape a sheet can have, from its middle, and scales it down. Cut and not
	 * squeezed: a face stays a face.
	 */
	private static PhotoCodec.Picture fit(BufferedImage image) {
		float aspect = image.getHeight() / (float) image.getWidth();
		float shape = SHAPES[0];
		for (float candidate : SHAPES) {
			if (Math.abs(Math.log(candidate / aspect)) < Math.abs(Math.log(shape / aspect))) {
				shape = candidate;
			}
		}
		int cutWidth = Math.min(image.getWidth(), Math.round(image.getHeight() / shape));
		int cutHeight = Math.min(image.getHeight(), Math.round(cutWidth * shape));
		int left = (image.getWidth() - cutWidth) / 2;
		int top = (image.getHeight() - cutHeight) / 2;

		int width = shape <= 1.0F ? SHEET_PIXELS : Math.round(SHEET_PIXELS / shape);
		int height = Math.max(1, Math.round(width * shape));
		BufferedImage fitted = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = fitted.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		// on white: what is see-through in the picture is paper on a sheet
		graphics.setColor(java.awt.Color.WHITE);
		graphics.fillRect(0, 0, width, height);
		graphics.drawImage(image, 0, 0, width, height, left, top, left + cutWidth, top + cutHeight, null);
		graphics.dispose();
		return new PhotoCodec.Picture(width, height, fitted.getRGB(0, 0, width, height, null, 0, width));
	}
}
