package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;

/**
 * what the game is asked for to draw a frame, which is not asked for the same way in every version of it
 */
final class GameFrame {

	/**
	 * Pictures the mod had the game draw in this frame, on top of the one of the game itself. The game keeps
	 * three sets of what a picture is drawn with and takes the next one for every picture, counting on one
	 * picture per frame: a fourth picture in a frame would need the set of the first, which is still in use.
	 * From the second picture of the mod on, the game is told that a frame is over before each of them
	 */
	private static int extraPictures;

	private GameFrame() {
	}

	static void newFrame() {
		extraPictures = 0;
	}

	/**
	 * before anything of a picture of the mod is worked out or drawn: what the game keeps for one frame is not
	 * to change hands in the middle of a picture
	 */
	static void begin() {
		if (extraPictures++ > 0) {
			RenderSystem.getDevice().createCommandEncoder().submit();
		}
	}

	/**
	 * @param renderLevel false to draw the menus alone. What the newer game draws was said when it was extracted
	 */
	static void render(Minecraft mc, DeltaTracker deltaTracker, boolean renderLevel) {
		mc.gameRenderer.render(deltaTracker, renderLevel);
	}
}
