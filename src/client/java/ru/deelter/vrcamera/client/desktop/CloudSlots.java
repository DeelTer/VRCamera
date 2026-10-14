package ru.deelter.vrcamera.client.desktop;

import net.minecraft.client.Minecraft;

/**
 * The clouds of the game, with a second set of what they were built for: one for the player, one for the camera.
 * Not on every version of the game, there it is one for both.
 */
public interface CloudSlots {
	/**
	 * goes over to the clouds of the camera, or back to the ones of the player
	 */
	static void swap(Minecraft mc) {
		if ((Object) mc.levelRenderer.cloudRenderer() instanceof CloudSlots slots) {
			slots.vrcamera$swap();
		}
	}

	void vrcamera$swap();
}
