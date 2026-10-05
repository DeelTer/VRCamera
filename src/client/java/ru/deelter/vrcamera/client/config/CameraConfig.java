package ru.deelter.vrcamera.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
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

	// number of the last change of defaults this file has seen, see migrate
	private static final int VERSION = 4;
	public int version = VERSION;

	/**
	 * show the camera view on the desktop mirror while the camera is on
	 */
	public boolean forceMirror = true;
	public Marker marker = Marker.MODEL;
	/**
	 * show the name of the current shot next to the dot
	 */
	public boolean markerLabel = true;
	/**
	 * size of the dot
	 */
	public double markerSize = 14;
	/**
	 * show the camera icon and the distance to the camera in the headset
	 */
	public boolean indicator = true;
	/**
	 * size of the camera icon
	 */
	public double indicatorSize = 1.0;
	/**
	 * how far a camera flies that is thrown by hand, 0 = it can't be thrown
	 */
	public double throwPower = 1.0;
	/**
	 * How much a dropped camera turns its lens to the player while it comes to rest, in the physics mode.
	 * 0 = it lies however it fell, 1 = it looks right at the player
	 */
	public double physicsAim = 0.75;
	/**
	 * how much a camera held in the hand sways with breath and steps, in the physics mode. 0 = steady
	 */
	public double physicsShake = 1.0;
	/**
	 * how hard hands and feet hit a dropped camera, in the physics mode. 0 = they pass through it
	 */
	public double kickPower = 1.0;
	/**
	 * under water the camera of the physics mode films wider, sinks with a slow roll and leaves bubbles
	 */
	public boolean underwaterLook = true;
	/**
	 * Seconds to point at a dropped camera and hold the button, for it to fly into the hand.
	 * 0 = it can't be pulled
	 */
	public double pullSeconds = 1.25;
	/**
	 * How much a camera held in the hand is steadied against trembling and twitching of that hand.
	 * 0 = not at all, 1 = as much as it gets
	 */
	public double handStabilize = 0.5;
	/**
	 * the arm that holds the camera is not drawn in the picture, unless the camera looks at the player
	 */
	public boolean hideHoldingArm = true;
	/**
	 * the camera can be pulled in every mode, not only in the physics mode
	 */
	public boolean pullAllModes = true;
	/**
	 * a taken photo comes out of the camera as a sheet. Without this it is only saved
	 */
	public boolean photoSheet = true;
	/**
	 * Seconds the second hand holds interact at a held camera to take a photo. 0 = no photo that way
	 */
	public double photoHoldSeconds = 1.5;
	/**
	 * the director shows the menu shot for the chat as well, like for an inventory
	 */
	public boolean menuShotChat = true;
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
	 * Blocks. A camera closer than this aims at the face, one twice as far at the body, and in between it blends.
	 * 0 = always the body
	 */
	public double faceDistance = 1.25;
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

	/**
	 * Moves values that are still at a default that turned out badly on to the new default. Values that were
	 * changed by the player stay as they are.
	 *
	 * @param from version of the file that was read
	 */
	private void migrate(int from) {
		if (from < 2) {
			// hand placed shots started too close
			for (ShotConfig preset : this.presets) {
				if (preset.azimuth == 0 && preset.elevation == 5 && preset.distance == 2.5) {
					preset.distance = defaultPreset().distance;
				}
			}
			// the menu shot was too far behind the player, the shoulder covered the menu
			ShotConfig menu = shot(ShotType.MENU);
			if (menu.azimuth == 155) {
				menu.azimuth = ShotType.MENU.defaults().azimuth;
			}
		}
		if (from < 3 && this.pullSeconds == 2.0) {
			// pulling the camera took too long
			this.pullSeconds = 1.25;
		}
		if (from < 4 && this.marker == Marker.DOT) {
			this.marker = Marker.MODEL;
		}
		this.version = VERSION;
	}

	public static CameraConfig load() {
		CameraConfig config = null;
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				JsonObject json = GSON.fromJson(reader, JsonObject.class);
				config = GSON.fromJson(json, CameraConfig.class);
				if (config != null && !json.has("version")) {
					// from before files had a version
					config.version = 1;
				}
			} catch (Exception e) {
				Vrcamera.LOGGER.error("VRCamera: failed to read {}, using defaults", PATH, e);
			}
		}
		if (config == null) {
			config = new CameraConfig();
		}
		config.fillDefaults();
		config.migrate(config.version);
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
