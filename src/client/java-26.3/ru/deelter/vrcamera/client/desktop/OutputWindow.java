package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import ru.deelter.vrcamera.Vrcamera;

/**
 * The second window that shows the picture of the camera. Not there yet for this version of the game: it makes
 * its windows with SDL and not with GLFW anymore, and the window of the camera has to be made the same way.
 */
public final class OutputWindow {
	private static boolean told;

	private OutputWindow() {
	}

	public static boolean open(Minecraft mc) {
		if (!told) {
			told = true;
			Vrcamera.LOGGER.error("VRCamera: the camera window is not there yet for this version of Minecraft");
		}
		return false;
	}

	public static void show(Minecraft mc, RenderTarget picture, boolean grid, boolean fill) {
	}

	public static int[] size() {
		return null;
	}

	public static void handleKeys() {
	}

	public static void toggleFullscreen() {
	}

	public static double scrolled() {
		return 0;
	}

	public static void capture(boolean wanted) {
	}

	public static double[] mouseMoved() {
		return new double[2];
	}

	public static boolean isFocused() {
		return false;
	}

	public static boolean isKeyDown(int key) {
		return false;
	}

	public static void close() {
	}
}
