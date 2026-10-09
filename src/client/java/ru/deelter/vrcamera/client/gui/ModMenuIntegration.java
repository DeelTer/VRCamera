package ru.deelter.vrcamera.client.gui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * adds the settings button to the mod list of Mod Menu
 */
public class ModMenuIntegration implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {

		return parent -> ClothConfig.isInstalled() ? ConfigScreen.create(parent) : null;
	}
}
