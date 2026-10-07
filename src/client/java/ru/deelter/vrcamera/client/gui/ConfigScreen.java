package ru.deelter.vrcamera.client.gui;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.deelter.vrcamera.client.config.*;
import ru.deelter.vrcamera.client.desktop.OutputWindow;
import ru.deelter.vrcamera.client.shot.ShotType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Settings screen, built with Cloth Config. Only load this class when Cloth Config is installed.
 */
public final class ConfigScreen {
	// the sizes a picture is recorded in, wide and upright. Any other one is set with /vrcam screen size
	private static final int[][] OUTPUT_SIZES = {
			{1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}, {1080, 1920}, {1440, 2560}};
	private final ConfigEntryBuilder entries;

	private ConfigScreen(ConfigEntryBuilder entries) {
		this.entries = entries;
	}

	public static boolean isAvailable() {
		return FabricLoader.getInstance().isModLoaded("cloth-config");
	}

	public static Screen create(Screen parent) {
		// the config the camera is using right now, changes apply as soon as they are saved
		CameraConfig config = CameraConfig.current();
		CameraConfig defaults = new CameraConfig();
		Pace pace = config.pace;

		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Component.translatable("vrcamera.config.title"))
				.setSavingRunnable(() -> {
					// After everything else was written: a new pace sets values that have entries of their own here
					if (config.pace != pace) {
						config.pace.apply(config);
					}
					config.save();
				});
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
		general.addEntry(screen.toggle("underwaterLook", config.underwaterLook, defaults.underwaterLook,
				value -> config.underwaterLook = value));
		general.addEntry(screen.toggle("hideHoldingArm", config.hideHoldingArm, defaults.hideHoldingArm,
				value -> config.hideHoldingArm = value));
		general.addEntry(screen.toggle("selfieScreen", config.selfieScreen, defaults.selfieScreen,
				value -> config.selfieScreen = value));
		general.addEntry(screen.toggle("attachToSelf", config.attachToSelf, defaults.attachToSelf,
				value -> config.attachToSelf = value));
		general.addEntry(screen.entries.startStrField(Component.translatable("vrcamera.option.chromaColor"),
						config.chromaColor)
				.setDefaultValue(defaults.chromaColor)
				.setTooltipSupplier(help("vrcamera.option.chromaColor.tooltip"))
				.setErrorSupplier(value -> value.trim().matches("#?[0-9a-fA-F]{6}") ? Optional.empty() :
						Optional.of(Component.translatable("vrcamera.option.chromaColor.invalid")))
				.setSaveConsumer(value -> config.chromaColor = value.trim())
				.build());
		general.addEntry(screen.selector("screenOutput", ScreenOutput.values(), config.screenOutput,
				defaults.screenOutput, value -> config.screenOutput = value));
		general.addEntry(screen.slider("menuSize", config.menuSize, defaults.menuSize, 0.5, 3, 0.25, "%.2fx",
				value -> config.menuSize = value));
		general.addEntry(screen.slider("chromaDistance", config.chromaDistance, defaults.chromaDistance, 0, 128, 8,
				"%.0f", value -> config.chromaDistance = value));
		general.addEntry(screen.slider("cameraLabelDistance", config.cameraLabelDistance,
				defaults.cameraLabelDistance, 0, 256, 8, "%.0f", value -> config.cameraLabelDistance = value));
		general.addEntry(screen.entries.startStrField(Component.translatable("vrcamera.option.filmPlayer"),
						config.filmPlayer)
				.setDefaultValue(defaults.filmPlayer)
				.setTooltipSupplier(help("vrcamera.option.filmPlayer.tooltip"))
				.setSaveConsumer(value -> config.filmPlayer = value.trim())
				.build());
		general.addEntry(screen.entries.startStrField(Component.translatable("vrcamera.option.filmWith"),
						config.filmWith)
				.setDefaultValue(defaults.filmWith)
				.setTooltipSupplier(help("vrcamera.option.filmWith.tooltip"))
				.setSaveConsumer(value -> config.filmWith = value.trim())
				.build());
		general.addEntry(screen.toggle("freeAutoSwitch", config.freeAutoSwitch, defaults.freeAutoSwitch,
				value -> config.freeAutoSwitch = value));
		general.addEntry(screen.slider("freeAutoSwitchAngle", config.freeAutoSwitchAngle,
				defaults.freeAutoSwitchAngle, 10, 90, 5, "%.0f", value -> config.freeAutoSwitchAngle = value));
		general.addEntry(screen.slider("freeAutoSwitchSeconds", config.freeAutoSwitchSeconds,
				defaults.freeAutoSwitchSeconds, 0, 1.5, 0.05, "%.2f s", value -> config.freeAutoSwitchSeconds = value));
		general.addEntry(screen.slider("outputFps", config.outputFps, defaults.outputFps, 0, 144, 6, "%.0f",
				value -> config.outputFps = value));
		general.addEntry(screen.outputSize(config));
		general.addEntry(screen.slider("selfieDistance", config.selfieDistance, defaults.selfieDistance, 0.5, 8,
				0.5, "%.1f", value -> config.selfieDistance = value));
		general.addEntry(screen.slider("kickPower", config.kickPower, defaults.kickPower, 0, 3, 0.1,
				"%.1f", value -> config.kickPower = value));
		general.addEntry(screen.slider("handStabilize", config.handStabilize, defaults.handStabilize, 0, 1, 0.05,
				"%.2f", value -> config.handStabilize = value));
		general.addEntry(screen.selector("pullStyle", PullStyle.values(), config.pullStyle, defaults.pullStyle,
				value -> config.pullStyle = value));
		general.addEntry(screen.slider("pullSeconds", config.pullSeconds, defaults.pullSeconds, 0, 5, 0.25,
				"%.2f s", value -> config.pullSeconds = value));
		general.addEntry(screen.toggle("pullAllModes", config.pullAllModes, defaults.pullAllModes,
				value -> config.pullAllModes = value));
		general.addEntry(screen.toggle("photoSheet", config.photoSheet, defaults.photoSheet,
				value -> config.photoSheet = value));
		general.addEntry(screen.toggle("showOthersPhotos", config.showOthersPhotos, defaults.showOthersPhotos,
				value -> config.showOthersPhotos = value));
		general.addEntry(screen.toggle("showCustomPhotos", config.showCustomPhotos, defaults.showCustomPhotos,
				value -> config.showCustomPhotos = value));
		general.addEntry(screen.toggle("shareCamera", config.shareCamera, defaults.shareCamera,
				value -> config.shareCamera = value));
		general.addEntry(screen.slider("othersCameras", config.othersCameras, defaults.othersCameras, 0, 16, 1,
				"%.0f", value -> config.othersCameras = value));
		general.addEntry(screen.selector("photoGesture", PhotoGesture.values(), config.photoGesture,
				defaults.photoGesture, value -> config.photoGesture = value));
		general.addEntry(screen.slider("photoHoldSeconds", config.photoHoldSeconds, defaults.photoHoldSeconds, 0, 3,
				0.25, "%.2f s", value -> config.photoHoldSeconds = value));
		general.addEntry(screen.slider("photoBrightness", config.photoBrightness, defaults.photoBrightness, 0, 1,
				0.05, "%.2f", value -> config.photoBrightness = value));
		general.addEntry(screen.slider("photoPixels", config.photoPixels, defaults.photoPixels, 0, 1,
				0.05, "%.2f", value -> config.photoPixels = value));
		general.addEntry(screen.toggle("photoSounds", config.photoSounds, defaults.photoSounds,
				value -> config.photoSounds = value));
		general.addEntry(screen.toggle("photoClipboard", config.photoClipboard, defaults.photoClipboard,
				value -> config.photoClipboard = value));
		general.addEntry(screen.toggle("menuShotChat", config.menuShotChat, defaults.menuShotChat,
				value -> config.menuShotChat = value));
		general.addEntry(screen.toggle("debugOverlay", config.debugOverlay, defaults.debugOverlay,
				value -> config.debugOverlay = value));

		ConfigCategory motion = builder.getOrCreateCategory(Component.translatable("vrcamera.config.motion"));
		motion.addEntry(screen.slider("aimHeight", config.aimHeight, defaults.aimHeight, 0, 1, 0.05, "%.2f",
				value -> config.aimHeight = value));
		motion.addEntry(screen.slider("faceDistance", config.faceDistance, defaults.faceDistance, 0, 4, 0.25,
				"%.2f", value -> config.faceDistance = value));
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
		director.addEntry(screen.selector("pace", Pace.values(), config.pace, defaults.pace,
				value -> config.pace = value));
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
		director.addEntry(screen.toggle("povHome", config.povHome, defaults.povHome, value -> config.povHome = value));
		director.addEntry(screen.slider("povHomeSeconds", config.povHomeSeconds, defaults.povHomeSeconds, 10, 300, 5,
				"%.0f s", value -> config.povHomeSeconds = value));

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
		group.add(shotSlider("minDuration", shot.minDuration, defaults.minDuration, 1, 120, 0.5, "%.1f s",
				value -> shot.minDuration = value));
		group.add(shotSlider("maxDuration", shot.maxDuration, defaults.maxDuration, 1, 180, 0.5, "%.1f s",
				value -> shot.maxDuration = value));
	}

	/**
	 * What an option does, shown next to the mouse. Only while Shift is held: these are whole sentences, and over
	 * a slider they would cover the value that is being set
	 */
	private static Supplier<Optional<Component[]>> help(String key) {
		return () -> Optional.of(new Component[]{Component.translatable(
				Minecraft.getInstance().hasShiftDown() ? key : "vrcamera.config.help")});
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
				.setTooltipSupplier(help(key + ".tooltip"))
				.setSaveConsumer(save)
				.build();
	}

	private <T extends Enum<T>> AbstractConfigListEntry<?> selector(
			String field, T[] values, T value, T def, Consumer<T> save) {
		String key = "vrcamera.option." + field;
		return this.entries.startSelector(Component.translatable(key), values, value)
				.setDefaultValue(def)
				.setNameProvider(option -> Component.translatable(key + "." + option.name().toLowerCase(Locale.ROOT)))
				.setTooltipSupplier(help(key + ".tooltip"))
				.setSaveConsumer(save)
				.build();
	}

	private AbstractConfigListEntry<?> outputSize(CameraConfig config) {
		String key = "vrcamera.option.outputSize";
		List<int[]> sizes = new ArrayList<>();
		sizes.add(new int[]{0, 0});
		sizes.addAll(Arrays.asList(OUTPUT_SIZES));
		int width = config.hasOutputSize() ? config.outputWidth : 0;
		int height = config.hasOutputSize() ? config.outputHeight : 0;
		int[] current = sizes.stream().filter(size -> size[0] == width && size[1] == height).findFirst().orElse(null);
		if (current == null) {
			// one that was set with the command
			current = new int[]{width, height};
			sizes.add(current);
		}
		return this.entries.startSelector(Component.translatable(key), sizes.toArray(new int[0][]), current)
				.setDefaultValue(sizes.getFirst())
				.setNameProvider(size -> size[0] > 0 ? Component.literal(size[0] + "×" + size[1]) :
						Component.translatable(key + ".auto"))
				.setTooltipSupplier(help(key + ".tooltip"))
				.setSaveConsumer(size -> {
					boolean changed = size[0] != width || size[1] != height;
					config.outputWidth = size[0];
					config.outputHeight = size[1];
					// only when it was changed: a window the player pulled to another size stays as it is
					if (changed && size[0] > 0) {
						OutputWindow.setSize(size[0], size[1]);
					}
				})
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
				.setTooltipSupplier(help(key + ".tooltip"))
				.setSaveConsumer(index -> save.accept(min + index * step))
				.build();
	}

	private static int toStep(double value, double min, double step, int steps) {
		return Math.clamp(Math.round((value - min) / step), 0, steps);
	}
}
