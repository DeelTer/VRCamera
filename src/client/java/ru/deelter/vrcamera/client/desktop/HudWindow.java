package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import ru.deelter.vrcamera.mixin.client.WindowAccessor;

/**
 * The window of the game as big as the picture of the camera for a moment, while the HUD is laid out and drawn for
 * it: the HUD is then in the shape of that picture, and not stretched from the shape of the window. The scale
 * of the HUD stays the one of the game, another one would have the game build its pictures of items anew
 * twice a frame
 */
final class HudWindow implements AutoCloseable {
	private final Window window;
	private final int width;
	private final int height;
	private final int scale;

	private HudWindow(Window window) {
		this.window = window;
		width = ((WindowAccessor) (Object) window).vrcamera$framebufferWidth();
		height = ((WindowAccessor) (Object) window).vrcamera$framebufferHeight();
		scale = (int) window.getGuiScale();
	}

	/**
	 * @param size width and height the window is taken to have
	 * @return what puts the window back as it was when it is closed
	 */
	static HudWindow fit(Minecraft mc, int[] size) {
		final HudWindow before = new HudWindow(mc.getWindow());
		final WindowAccessor window = (WindowAccessor) (Object) mc.getWindow();
		window.vrcamera$setFramebufferWidth(size[0]);
		window.vrcamera$setFramebufferHeight(size[1]);
		mc.getWindow().setGuiScale(before.scale);
		return before;
	}

	@Override
	public void close() {
		((WindowAccessor) (Object) window).vrcamera$setFramebufferWidth(width);
		((WindowAccessor) (Object) window).vrcamera$setFramebufferHeight(height);
		window.setGuiScale(scale);
	}
}
