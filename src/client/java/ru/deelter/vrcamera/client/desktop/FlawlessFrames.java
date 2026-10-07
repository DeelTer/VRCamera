package ru.deelter.vrcamera.client.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Mods that draw fewer frames while the game window is not looked at, like Dynamic FPS, are asked to draw them all
 * while the camera has a window of its own: its picture is drawn along with the frames of the game. The way to ask
 * is one they agreed on, the Flawless Frames API of FREX, which needs none of them to be there.
 */
public final class FlawlessFrames implements Consumer<Function<String, Consumer<Boolean>>> {
	private static final List<Consumer<Boolean>> LISTENERS = new ArrayList<>();
	private static boolean wanted;

	@Override
	public void accept(Function<String, Consumer<Boolean>> provider) {
		LISTENERS.add(provider.apply("vrcamera"));
	}

	static void set(boolean all) {
		if (all != wanted) {
			wanted = all;
			LISTENERS.forEach(listener -> listener.accept(all));
		}
	}
}
