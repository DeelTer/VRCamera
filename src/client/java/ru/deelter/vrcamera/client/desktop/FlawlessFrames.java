package ru.deelter.vrcamera.client.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Mods that draw fewer frames while the game window is not looked at, like Dynamic FPS, are asked to draw them all
 * while the camera has a window of its own: its picture is drawn along with the frames of the game. The way to ask
 * is one they agreed on, the Flawless Frames API of FREX, which needs none of them to be there.
 * <p>
 * Only those mods are asked. The same question means something else to a renderer like Sodium: a video that is
 * rendered frame by frame, where no frame may miss a chunk. It then builds every chunk before it goes on, and the
 * game stands still each time new ones come into view.
 */
public final class FlawlessFrames implements Consumer<Function<String, Consumer<Boolean>>> {
	// what the classes of the mods that are asked have in their names
	private static final List<String> FRAME_LIMITERS = List.of("dynamic_fps");
	private static final List<Consumer<Boolean>> LISTENERS = new ArrayList<>();
	private static boolean wanted;

	static void set(boolean all) {
		if (all != wanted) {
			wanted = all;
			LISTENERS.forEach(listener -> listener.accept(all));
		}
	}

	@Override
	public void accept(Function<String, Consumer<Boolean>> provider) {
		String asker = provider.getClass().getName();
		if (FRAME_LIMITERS.stream().anyMatch(asker::contains)) {
			LISTENERS.add(provider.apply("vrcamera"));
		}
	}
}
