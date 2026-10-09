package ru.deelter.vrcamera.client.gui;

import net.fabricmc.loader.api.FabricLoader;

/**
 * If Cloth Config is installed, the mod the settings screen is built with. Asked here and not in
 * {@link ConfigScreen}: that class can't even be looked at without Cloth Config, the game stops over it.
 */
public final class ClothConfig {
	private ClothConfig() {
	}

	public static boolean isInstalled() {
		return FabricLoader.getInstance().isModLoaded("cloth-config");
	}
}
