package ru.deelter.vrcamera.client.gui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * adds the settings button to the mod list of Mod Menu
 */
public class ModMenuIntegration implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		// a lambda and not a method reference, to not touch the screen class without Cloth Config
		return parent -> ConfigScreen.isAvailable() ? ConfigScreen.create(parent) : null;
	}
}
