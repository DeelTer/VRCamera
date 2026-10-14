package ru.deelter.vrcamera.client.gui;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;

/**
 * If Cloth Config is installed, the mod the settings screen is built with. Asked here and not in
 * {@link ConfigScreen}: that class can't even be looked at without Cloth Config, the game stops over it. Only
 * looked at here once it is known to be there.
 */
public final class ClothConfig {
	private ClothConfig() {
	}

	public static boolean isInstalled() {
		return FabricLoader.getInstance().isModLoaded("cloth-config");
	}

	/**
	 * @return the screen of the settings, or what to do without Cloth Config
	 */
	public static Screen settings(Screen parent) {
		return isInstalled() ? ConfigScreen.create(parent) : new NoClothScreen(parent);
	}
}
