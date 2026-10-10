package ru.deelter.vrcamera.client.photo;

import org.jetbrains.annotations.NotNull;
import ru.deelter.vrcamera.client.sync.PhotoCodec;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
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

	private static final int MAX_SIDE = 8192;
	private static final long MAX_PIXELS = 40_000_000L;

	private static final int SHEET_PIXELS = 384;

	private static final float[] SHAPES = {9.0F / 16.0F, 1.0F, 16.0F / 9.0F};

	private CustomPictures() {
	}

	@NotNull
	public static Loaded load(String address) throws IOException {
		URI uri;
		try {
			uri = URI.create(address.trim());
		} catch (IllegalArgumentException e) {
			throw new IOException("not an address");
		}
		final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);

		if (!scheme.equals("http") && !scheme.equals("https")) {
			throw new IOException("only http and https addresses");
		}
		final byte[] original = download(uri);

		try (final ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
			final Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext()) {
				throw new IOException("not a picture this can read: PNG, JPEG or GIF");
			}
			final ImageReader reader = readers.next();
			try {
				reader.setInput(in);
				final int width = reader.getWidth(0);
				final int height = reader.getHeight(0);
				if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE ||
						(long) width * height > MAX_PIXELS) {
					throw new IOException("picture of " + width + "x" + height + " is too large");
				}
				final String format = reader.getFormatName().toLowerCase(Locale.ROOT);
				return new Loaded(fit(reader.read(0)), original, format.equals("jpeg") ? "jpg" : format);
			} finally {
				reader.dispose();
			}
		} catch (RuntimeException e) {
			throw new IOException("broken picture");
		}
	}

	private static byte[] download(URI uri) throws IOException {
		final HttpClient client = HttpClient.newBuilder()
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

		final CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request,
				HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_BYTES));
		try {
			final HttpResponse<byte[]> response = pending.get(DOWNLOAD_SECONDS, TimeUnit.SECONDS);
			if (response.statusCode() != 200) {
				throw new IOException("the server answered " + response.statusCode());
			}
			return response.body();
		} catch (TimeoutException e) {
			throw new IOException("took longer than " + DOWNLOAD_SECONDS + " seconds");
		} catch (ExecutionException e) {
			final Throwable cause = e.getCause() == null ? e : e.getCause();
			final String message = String.valueOf(cause.getMessage());
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
	@NotNull
	private static PhotoCodec.Picture fit(BufferedImage image) {
		final float aspect = image.getHeight() / (float) image.getWidth();
		float shape = SHAPES[0];
		for (final float candidate : SHAPES) {
			if (Math.abs(Math.log(candidate / aspect)) < Math.abs(Math.log(shape / aspect))) {
				shape = candidate;
			}
		}
		final int cutWidth = Math.min(image.getWidth(), Math.round(image.getHeight() / shape));
		final int cutHeight = Math.min(image.getHeight(), Math.round(cutWidth * shape));
		final int left = (image.getWidth() - cutWidth) / 2;
		final int top = (image.getHeight() - cutHeight) / 2;

		final int width = shape <= 1.0F ? SHEET_PIXELS : Math.round(SHEET_PIXELS / shape);
		final int height = Math.max(1, Math.round(width * shape));
		final BufferedImage fitted = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		final Graphics2D graphics = fitted.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

		graphics.setColor(java.awt.Color.WHITE);
		graphics.fillRect(0, 0, width, height);
		graphics.drawImage(image, 0, 0, width, height, left, top, left + cutWidth, top + cutHeight, null);
		graphics.dispose();
		return new PhotoCodec.Picture(width, height, fitted.getRGB(0, 0, width, height, null, 0, width));
	}

	/**
	 * @param original the file as it was downloaded, to keep
	 * @param format   what kind of file that is, for its name
	 */
	public record Loaded(PhotoCodec.Picture picture, byte[] original, String format) {
	}
}
