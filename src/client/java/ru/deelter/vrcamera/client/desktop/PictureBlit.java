package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.lang.reflect.Method;

/**
 * Copies the picture of the camera to the window that OpenGL draws into right now, with the lines that help to
 * frame it over it. Needs the OpenGL renderer of the game.
 */
final class PictureBlit {
	private static final int LINE_PART = 360;
	private static Method glId;

	private PictureBlit() {
	}

	/**
	 * @return what OpenGL calls the texture of the picture. The class that knows is not the same in every
	 * supported version of the game, its method is
	 * @throws IllegalStateException if the game does not draw with OpenGL
	 */
	static int textureId(RenderTarget picture) {
		final Object texture = picture.getColorTexture();
		try {
			if (glId == null || glId.getDeclaringClass() != texture.getClass()) {
				glId = texture.getClass().getMethod("glId");
			}
			return (int) glId.invoke(texture);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("the camera window needs the OpenGL renderer of the game", e);
		}
	}

	/**
	 * @param texture     what {@link #textureId} said
	 * @param frameBuffer a frame buffer of the OpenGL context that is current, to read the picture through
	 * @param width       how wide the window is, in pixels
	 * @param fill        if the picture was drawn for the shape of the window, and fills it. Otherwise it is shown whole,
	 *                    with black bars where the window has another shape
	 * @param guide       the lines over the picture, null for none
	 */
	static void draw(
			RenderTarget picture, int texture, int frameBuffer, int width, int height, boolean fill, FrameGuide guide) {
		final double scale = Math.min(width / (double) picture.width, height / (double) picture.height);
		final int shownWidth = fill ? width : (int) Math.round(picture.width * scale);
		final int shownHeight = fill ? height : (int) Math.round(picture.height * scale);
		final Box shown = new Box((width - shownWidth) / 2, (height - shownHeight) / 2, shownWidth, shownHeight);

		GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
		GL11.glViewport(0, 0, width, height);
		GL11.glDisable(GL11.GL_SCISSOR_TEST);
		GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
		GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, frameBuffer);
		GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture,
				0);
		GL30.glBlitFramebuffer(0, 0, picture.width, picture.height, shown.x(), shown.y(), shown.x() + shownWidth,
				shown.y() + shownHeight, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
		SeeThrough.draw(shown);
		if (guide != null) {
			drawGuide(guide, shown);
		}
	}

	/**
	 * Lines without a shader of its own: thin strips of the window are cleared to white.
	 *
	 * @param shown where in the window the picture is, counted from its lower left corner
	 */
	private static void drawGuide(FrameGuide guide, Box shown) {
		final int thickness = Math.max(1, shown.height() / LINE_PART);
		GL11.glEnable(GL11.GL_SCISSOR_TEST);
		GL11.glClearColor(1.0F, 1.0F, 1.0F, 1.0F);
		for (final double across : guide.across(shown.width(), shown.height())) {
			strip(shown.x() + (int) (shown.width() * across), shown.y(), thickness, shown.height());
		}
		for (final double up : guide.up()) {
			strip(shown.x(), shown.y() + (int) (shown.height() * up), shown.width(), thickness);
		}
		for (final double in : guide.frames()) {
			final int x = shown.x() + (int) (shown.width() * in);
			final int y = shown.y() + (int) (shown.height() * in);
			final int width = shown.width() - 2 * (int) (shown.width() * in);
			final int height = shown.height() - 2 * (int) (shown.height() * in);
			strip(x, y, width, thickness);
			strip(x, y + height - thickness, width, thickness);
			strip(x, y, thickness, height);
			strip(x + width - thickness, y, thickness, height);
		}

		double[] before = null;
		for (final double[] point : guide.curve()) {
			final double x = shown.x() + shown.width() * point[0];
			final double y = shown.y() + shown.height() * point[1];
			if (before != null) {
				final int dots = Math.max(1, (int) Math.ceil(Math.hypot(x - before[0], y - before[1]) / thickness));
				for (int dot = 1; dot <= dots; dot++) {
					final int dotX = (int) (before[0] + (x - before[0]) * dot / dots);
					final int dotY = (int) (before[1] + (y - before[1]) * dot / dots);
					strip(dotX, dotY, thickness, thickness);
				}
			}
			before = new double[]{x, y};
		}
		GL11.glDisable(GL11.GL_SCISSOR_TEST);
	}

	private static void strip(int x, int y, int width, int height) {
		GL11.glScissor(x, y, width, height);
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
	}
}
