package ru.deelter.vrcamera.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.shot.ShotType;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class CameraConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("vrcamera.json");

	/**
	 * show the camera view on the desktop mirror while the camera is on
	 */
	public boolean forceMirror = true;
	public Marker marker = Marker.DOT;
	/**
	 * show the name of the current shot next to the dot
	 */
	public boolean markerLabel = true;
	/**
	 * size of the dot
	 */
	public double markerSize = 14;
	/**
	 * show an arrow in the headset that points to the camera, while it is out of sight
	 */
	public boolean indicator = true;
	/**
	 * how far a camera flies that is thrown by hand, 0 = it can't be thrown
	 */
	public double throwPower = 1.0;
	/**
	 * show what the director is doing on the hud
	 */
	public boolean debugOverlay = false;

	/**
	 * where on the body the camera aims, 0 = feet, 1 = head
	 */
	public double aimHeight = 0.6;
	/**
	 * seconds the camera needs to catch up with its target position
	 */
	public double positionLag = 0.35;
	/**
	 * seconds the camera aim needs to catch up with the player
	 */
	public double lookLag = 0.12;
	/**
	 * seconds the camera needs to swing around when the player turns
	 */
	public double turnLag = 0.9;
	/**
	 * degrees the player can turn before the camera starts to swing around
	 */
	public double turnDeadzone = 12;
	/**
	 * widen the fov at high speeds
	 */
	public boolean speedFov = true;
	/**
	 * seconds the camera aims ahead of a moving player, that leaves room in the frame in front of them. 0 = off
	 */
	public double leadRoom = 0.25;
	/**
	 * most the aim moves away from the player because of leadRoom, in player sizes
	 */
	public double leadRoomMax = 0.8;

	/**
	 * blocks the camera keeps away from walls
	 */
	public double collisionRadius = 0.15;
	public double collisionMargin = 0.12;
	/**
	 * seconds the camera ignores something thin passing between it and the player, like a tree or post. 0 = off
	 */
	public double softOcclusionTime = 0.35;
	/**
	 * a shot gets replaced when walls push the camera closer than this fraction of its distance...
	 */
	public double occlusionRatio = 0.45;
	/**
	 * ...for this many seconds
	 */
	public double occlusionCutTime = 0.6;

	public Transition transition = Transition.AUTO;
	/**
	 * chance for a smooth swing instead of a hard cut, when transition is "auto"
	 */
	public double blendChance = 0.6;
	/**
	 * seconds a shot stays at least, before the director may replace it because the situation changed
	 */
	public double minShotTime = 2.5;
	/**
	 * seconds the director keeps a hand placed camera, before it takes over again
	 */
	public double manualHoldSeconds = 20;
	/**
	 * degrees per second
	 */
	public double orbitSpeed = 14;
	/**
	 * special shots for dying and for long falls
	 */
	public boolean events = true;

	/**
	 * hand placed shots, the active one is used by the follow mode
	 */
	public List<ShotConfig> presets = new ArrayList<>();
	public int activePreset = 0;
	/**
	 * also use the hand placed shots in the director rotation
	 */
	public boolean customInRotation = false;

	public Map<String, ShotConfig> shots = new LinkedHashMap<>();

	// from before there were presets, only read to carry it over
	private ShotConfig custom;

	/**
	 * @return settings of the shot, for the hand placed shot those of the active one
	 */
	public ShotConfig shot(ShotType type) {
		if (type == ShotType.CUSTOM) {
			return preset();
		}
		return this.shots.computeIfAbsent(key(type), key -> type.defaults());
	}

	/**
	 * @return the active hand placed shot
	 */
	public ShotConfig preset() {
		return this.presets.get(this.activePreset);
	}

	/**
	 * @return a hand placed shot as it is before it was placed anywhere: in front of the player
	 */
	public static ShotConfig defaultPreset() {
		return new ShotConfig(1.0, 0, 5, 4.0, 70, 8, 12);
	}

	private static String key(ShotType type) {
		return type.name().toLowerCase(Locale.ROOT);
	}

	private void fillDefaults() {
		if (this.shots == null) {
			this.shots = new LinkedHashMap<>();
		}
		// a shot set to null in the file counts as missing
		this.shots.values().removeIf(shot -> shot == null);
		// shots that no longer exist
		this.shots.keySet().removeIf(name -> Arrays.stream(ShotType.values()).noneMatch(type -> key(type).equals(name)));
		if (this.presets == null) {
			this.presets = new ArrayList<>();
		}
		this.presets.removeIf(preset -> preset == null);
		if (this.presets.isEmpty()) {
			this.presets.add(this.custom != null ? this.custom : defaultPreset());
		}
		for (ShotConfig preset : this.presets) {
			// the first default was too close. One still at exactly that was never placed by hand, move it out
			if (preset.azimuth == 0 && preset.elevation == 5 && preset.distance == 2.5) {
				preset.distance = defaultPreset().distance;
			}
		}
		this.custom = null;
		this.activePreset = Math.clamp(this.activePreset, 0, this.presets.size() - 1);
		// unknown values in the file end up as null
		if (this.transition == null) {
			this.transition = Transition.AUTO;
		}
		if (this.marker == null) {
			this.marker = Marker.DOT;
		}
		// write all shots to the file, also the ones added by an update
		for (ShotType type : ShotType.values()) {
			shot(type);
		}
	}

	public static CameraConfig load() {
		CameraConfig config = null;
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				config = GSON.fromJson(reader, CameraConfig.class);
			} catch (Exception e) {
				Vrcamera.LOGGER.error("VRCamera: failed to read {}, using defaults", PATH, e);
			}
		}
		if (config == null) {
			config = new CameraConfig();
		}
		config.fillDefaults();
		config.save();
		return config;
	}

	public void save() {
		try (Writer writer = Files.newBufferedWriter(PATH)) {
			GSON.toJson(this, writer);
		} catch (IOException e) {
			Vrcamera.LOGGER.error("VRCamera: failed to write {}", PATH, e);
		}
	}
}
