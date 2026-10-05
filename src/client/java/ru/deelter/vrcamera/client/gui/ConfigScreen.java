package ru.deelter.vrcamera.client.gui;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.deelter.vrcamera.client.CameraController;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.config.Marker;
import ru.deelter.vrcamera.client.config.ShotConfig;
import ru.deelter.vrcamera.client.config.Transition;
import ru.deelter.vrcamera.client.shot.ShotType;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Settings screen, built with Cloth Config. Only load this class when Cloth Config is installed.
 */
public final class ConfigScreen {
	private final ConfigEntryBuilder entries;

	private ConfigScreen(ConfigEntryBuilder entries) {
		this.entries = entries;
	}

	public static boolean isAvailable() {
		return FabricLoader.getInstance().isModLoaded("cloth-config");
	}

	public static Screen create(Screen parent) {
		// the config the camera is using right now, changes apply as soon as they are saved
		CameraConfig config = CameraController.INSTANCE.config();
		CameraConfig defaults = new CameraConfig();

		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Component.translatable("vrcamera.config.title"))
				.setSavingRunnable(config::save);
		ConfigScreen screen = new ConfigScreen(builder.entryBuilder());

		ConfigCategory general = builder.getOrCreateCategory(Component.translatable("vrcamera.config.general"));
		general.addEntry(screen.toggle("forceMirror", config.forceMirror, defaults.forceMirror,
				value -> config.forceMirror = value));
		general.addEntry(screen.selector("marker", Marker.values(), config.marker, defaults.marker,
				value -> config.marker = value));
		general.addEntry(screen.toggle("markerLabel", config.markerLabel, defaults.markerLabel,
				value -> config.markerLabel = value));
		general.addEntry(screen.slider("markerSize", config.markerSize, defaults.markerSize, 2, 60, 1, "%.0f",
				value -> config.markerSize = value));
		general.addEntry(screen.toggle("indicator", config.indicator, defaults.indicator,
				value -> config.indicator = value));
		general.addEntry(screen.slider("indicatorSize", config.indicatorSize, defaults.indicatorSize, 0.3, 3, 0.1,
				"%.1f", value -> config.indicatorSize = value));
		general.addEntry(screen.slider("throwPower", config.throwPower, defaults.throwPower, 0, 3, 0.1, "%.1f",
				value -> config.throwPower = value));
		general.addEntry(screen.slider("physicsAim", config.physicsAim, defaults.physicsAim, 0, 1, 0.05, "%.2f",
			value -> config.physicsAim = value));
		general.addEntry(screen.slider("physicsShake", config.physicsShake, defaults.physicsShake, 0, 3, 0.1,
			"%.1f", value -> config.physicsShake = value));
		general.addEntry(screen.slider("pullSeconds", config.pullSeconds, defaults.pullSeconds, 0, 5, 0.25,
			"%.2f s", value -> config.pullSeconds = value));
		general.addEntry(screen.toggle("pullAllModes", config.pullAllModes, defaults.pullAllModes,
			value -> config.pullAllModes = value));
		general.addEntry(screen.toggle("debugOverlay", config.debugOverlay, defaults.debugOverlay,
				value -> config.debugOverlay = value));

		ConfigCategory motion = builder.getOrCreateCategory(Component.translatable("vrcamera.config.motion"));
		motion.addEntry(screen.slider("aimHeight", config.aimHeight, defaults.aimHeight, 0, 1, 0.05, "%.2f",
				value -> config.aimHeight = value));
		motion.addEntry(screen.slider("positionLag", config.positionLag, defaults.positionLag, 0, 2, 0.05, "%.2f s",
				value -> config.positionLag = value));
		motion.addEntry(screen.slider("lookLag", config.lookLag, defaults.lookLag, 0, 1, 0.01, "%.2f s",
				value -> config.lookLag = value));
		motion.addEntry(screen.slider("turnLag", config.turnLag, defaults.turnLag, 0.1, 4, 0.1, "%.1f s",
				value -> config.turnLag = value));
		motion.addEntry(screen.slider("turnDeadzone", config.turnDeadzone, defaults.turnDeadzone, 0, 90, 1, "%.0f°",
				value -> config.turnDeadzone = value));
		motion.addEntry(screen.slider("leadRoom", config.leadRoom, defaults.leadRoom, 0, 1, 0.05, "%.2f s",
				value -> config.leadRoom = value));
		motion.addEntry(screen.slider("leadRoomMax", config.leadRoomMax, defaults.leadRoomMax, 0, 3, 0.1, "%.1f",
				value -> config.leadRoomMax = value));
		motion.addEntry(screen.slider("orbitSpeed", config.orbitSpeed, defaults.orbitSpeed, 0, 60, 1, "%.0f°/s",
				value -> config.orbitSpeed = value));
		motion.addEntry(screen.toggle("speedFov", config.speedFov, defaults.speedFov,
				value -> config.speedFov = value));

		ConfigCategory collision = builder.getOrCreateCategory(Component.translatable("vrcamera.config.collision"));
		collision.addEntry(screen.slider("collisionRadius", config.collisionRadius, defaults.collisionRadius,
				0.05, 0.5, 0.01, "%.2f", value -> config.collisionRadius = value));
		collision.addEntry(screen.slider("collisionMargin", config.collisionMargin, defaults.collisionMargin,
				0, 0.5, 0.01, "%.2f", value -> config.collisionMargin = value));
		collision.addEntry(screen.slider("softOcclusionTime", config.softOcclusionTime, defaults.softOcclusionTime,
				0, 2, 0.05, "%.2f s", value -> config.softOcclusionTime = value));
		collision.addEntry(screen.slider("occlusionRatio", config.occlusionRatio, defaults.occlusionRatio,
				0.1, 0.9, 0.05, "%.2f", value -> config.occlusionRatio = value));
		collision.addEntry(screen.slider("occlusionCutTime", config.occlusionCutTime, defaults.occlusionCutTime,
				0.1, 5, 0.1, "%.1f s", value -> config.occlusionCutTime = value));

		ConfigCategory director = builder.getOrCreateCategory(Component.translatable("vrcamera.config.director"));
		director.addEntry(screen.selector("transition", Transition.values(), config.transition, defaults.transition,
				value -> config.transition = value));
		director.addEntry(screen.slider("blendChance", config.blendChance, defaults.blendChance, 0, 1, 0.05, "%.2f",
				value -> config.blendChance = value));
		director.addEntry(screen.slider("minShotTime", config.minShotTime, defaults.minShotTime, 0, 15, 0.5,
				"%.1f s", value -> config.minShotTime = value));
		director.addEntry(screen.slider("manualHoldSeconds", config.manualHoldSeconds, defaults.manualHoldSeconds,
				0, 120, 1, "%.0f s", value -> config.manualHoldSeconds = value));
		director.addEntry(screen.toggle("events", config.events, defaults.events, value -> config.events = value));
		director.addEntry(screen.toggle("customInRotation", config.customInRotation, defaults.customInRotation,
				value -> config.customInRotation = value));

		ConfigCategory shots = builder.getOrCreateCategory(Component.translatable("vrcamera.config.shots"));
		for (ShotType type : ShotType.values()) {
			if (type == ShotType.CUSTOM) {
				continue;
			}
			ShotConfig shot = config.shot(type);
			String name = type.name().toLowerCase(Locale.ROOT);
			SubCategoryBuilder group = screen.entries.startSubCategory(Component.translatable("vrcamera.shot." + name))
					.setTooltip(Component.translatable("vrcamera.shot." + name + ".tooltip"));
			screen.addShot(group, shot, type.defaults(), true);
			shots.addEntry(group.build());
		}

		ConfigCategory presets = builder.getOrCreateCategory(Component.translatable("vrcamera.config.presets"));
		presets.addEntry(screen.entries.startTextDescription(
				Component.translatable("vrcamera.config.presets.description")).build());
		ShotConfig presetDefaults = CameraConfig.defaultPreset();
		for (int i = 0; i < config.presets.size(); i++) {
			SubCategoryBuilder group = screen.entries.startSubCategory(
							Component.translatable("vrcamera.config.preset", i + 1))
					.setExpanded(i == config.activePreset);
			screen.addShot(group, config.presets.get(i), presetDefaults, false);
			presets.addEntry(group.build());
		}

		return builder.build();
	}

	/**
	 * @param regular if this is a shot of the director, and not one placed by hand
	 */
	private void addShot(SubCategoryBuilder group, ShotConfig shot, ShotConfig defaults, boolean regular) {
		group.add(shotToggle("enabled", shot.enabled, defaults.enabled, value -> shot.enabled = value));
		group.add(shotSlider("weight", shot.weight, defaults.weight, 0, 3, 0.1, "%.1f",
				value -> shot.weight = value));
		// hand placed shots can be on either side, the regular ones get mirrored on their own
		group.add(shotSlider("azimuth", shot.azimuth, defaults.azimuth, regular ? 0 : -180, 180, 1, "%.0f°",
				value -> shot.azimuth = value));
		group.add(shotSlider("elevation", shot.elevation, defaults.elevation, -80, 85, 1, "%.0f°",
				value -> shot.elevation = value));
		group.add(shotSlider("distance", shot.distance, defaults.distance, 0.5, 20, 0.1, "%.1f",
				value -> shot.distance = value));
		group.add(shotSlider("fov", shot.fov, defaults.fov, 20, 110, 1, "%.0f°", value -> shot.fov = value));
		group.add(shotSlider("minDuration", shot.minDuration, defaults.minDuration, 1, 30, 0.5, "%.1f s",
				value -> shot.minDuration = value));
		group.add(shotSlider("maxDuration", shot.maxDuration, defaults.maxDuration, 1, 60, 0.5, "%.1f s",
				value -> shot.maxDuration = value));
	}

	private AbstractConfigListEntry<?> toggle(String field, boolean value, boolean def, Consumer<Boolean> save) {
		return toggleEntry("vrcamera.option." + field, value, def, save);
	}

	private AbstractConfigListEntry<?> shotToggle(String field, boolean value, boolean def, Consumer<Boolean> save) {
		return toggleEntry("vrcamera.shot.field." + field, value, def, save);
	}

	private AbstractConfigListEntry<?> toggleEntry(String key, boolean value, boolean def, Consumer<Boolean> save) {
		return this.entries.startBooleanToggle(Component.translatable(key), value)
				.setDefaultValue(def)
				.setTooltip(Component.translatable(key + ".tooltip"))
				.setSaveConsumer(save)
				.build();
	}

	private <T extends Enum<T>> AbstractConfigListEntry<?> selector(
			String field, T[] values, T value, T def, Consumer<T> save) {
		String key = "vrcamera.option." + field;
		return this.entries.startSelector(Component.translatable(key), values, value)
				.setDefaultValue(def)
				.setNameProvider(option -> Component.translatable(key + "." + option.name().toLowerCase(Locale.ROOT)))
				.setTooltip(Component.translatable(key + ".tooltip"))
				.setSaveConsumer(save)
				.build();
	}

	private AbstractConfigListEntry<?> slider(
			String field, double value, double def, double min, double max, double step, String format,
			Consumer<Double> save) {
		return sliderEntry("vrcamera.option." + field, value, def, min, max, step, format, save);
	}

	private AbstractConfigListEntry<?> shotSlider(
			String field, double value, double def, double min, double max, double step, String format,
			Consumer<Double> save) {
		return sliderEntry("vrcamera.shot.field." + field, value, def, min, max, step, format, save);
	}

	/**
	 * Cloth Config only has sliders for whole numbers, so the slider counts steps, and shows the value those stand for.
	 * A slider instead of a number field, because typing numbers in VR is no fun.
	 */
	private AbstractConfigListEntry<?> sliderEntry(
			String key, double value, double def, double min, double max, double step, String format,
			Consumer<Double> save) {
		int steps = (int) Math.round((max - min) / step);
		return this.entries.startIntSlider(Component.translatable(key), toStep(value, min, step, steps), 0, steps)
				.setDefaultValue(toStep(def, min, step, steps))
				.setTextGetter(index -> Component.literal(String.format(Locale.ROOT, format, min + index * step)))
				.setTooltip(Component.translatable(key + ".tooltip"))
				.setSaveConsumer(index -> save.accept(min + index * step))
				.build();
	}

	private static int toStep(double value, double min, double step, int steps) {
		return Math.clamp(Math.round((value - min) / step), 0, steps);
	}
}
