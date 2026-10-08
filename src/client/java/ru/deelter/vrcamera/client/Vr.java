package ru.deelter.vrcamera.client;

import net.fabricmc.loader.api.FabricLoader;

/**
 * If there is VR at all. The mod works without Vivecraft, for a player at a screen: nothing that is of Vivecraft may
 * be touched then, its classes are not there. Everything that does touch them is asked for through here or through
 * {@link Vive}, and only after this said that Vivecraft is installed.
 */
public final class Vr {
	public static final boolean INSTALLED = FabricLoader.getInstance().isModLoaded("vivecraft");

	/**
	 * @return if the player is in VR right now
	 */
	public static boolean isRunning() {
		return INSTALLED && Vive.isRunning();
	}

	/**
	 * @return if the picture that is drawn right now is one of the game itself, and not an eye or the camera of
	 * Vivecraft
	 */
	public static boolean isVanillaPass() {
		return !INSTALLED || Vive.isVanillaPass();
	}

	private Vr() {
	}
}
