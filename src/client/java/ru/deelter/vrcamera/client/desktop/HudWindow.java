package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import ru.deelter.vrcamera.mixin.client.WindowAccessor;

/**
 * The window of the game for a moment in the shape of the window of the camera, while the HUD is laid out and
 * drawn for it. The picture of the camera is drawn as large as the window of the game and squeezed into the shape
 * of its own window: a HUD in that shape comes out right there, and not stretched. The window is taken to be as
 * large as fits into the picture in that shape, and what the HUD cuts out of the picture is put where it is in the
 * picture. The scale of the HUD stays the one of the game, another one would have the game build its pictures of
 * items anew twice a frame
 */
public final class HudWindow implements AutoCloseable {
	private static float @Nullable [] scissor;
	private final Window window;
	private final int width;
	private final int height;
	private final int scale;

	private HudWindow(Window window) {
		this.window = window;
		width = ((WindowAccessor) (Object) window).vrcamera$framebufferWidth();
		height = ((WindowAccessor) (Object) window).vrcamera$framebufferHeight();
		scale = window.getGuiScale();
	}

	/**
	 * @param shape  width and height the picture is shown in
	 * @param target what the picture is drawn into
	 * @return what puts the window back as it was when it is closed
	 */
	static HudWindow fit(Minecraft mc, int[] shape, RenderTarget target) {
		final HudWindow before = new HudWindow(mc.getWindow());
		final double ratio = (double) shape[0] / shape[1];
		final int width = (int) Math.min(target.width, Math.round(target.height * ratio));
		final int height = (int) Math.min(target.height, Math.round(target.width / ratio));
		final WindowAccessor window = (WindowAccessor) (Object) mc.getWindow();
		window.vrcamera$setFramebufferWidth(Math.max(1, width));
		window.vrcamera$setFramebufferHeight(Math.max(1, height));
		mc.getWindow().setGuiScale(before.scale);
		scissor = new float[]{(float) target.width / Math.max(1, width), (float) target.height / Math.max(1, height),
				target.width, target.height};
		return before;
	}

	/**
	 * @return how much wider and higher the picture is than the window is taken to be, and how wide and high it
	 * is, while the HUD is drawn for the camera. Null otherwise
	 */
	public static float @Nullable [] scissor() {
		return scissor;
	}

	@Override
	public void close() {
		scissor = null;
		((WindowAccessor) (Object) window).vrcamera$setFramebufferWidth(width);
		((WindowAccessor) (Object) window).vrcamera$setFramebufferHeight(height);
		window.setGuiScale(scale);
	}
}
