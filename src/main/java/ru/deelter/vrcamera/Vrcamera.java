package ru.deelter.vrcamera;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Vrcamera {
	public static final String MOD_ID = "vrcamera";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private Vrcamera() {
	}

	/**
	 * @return the version of the mod as its file says it, like 2.1.3+26.2
	 */
	public static String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("");
	}
}
