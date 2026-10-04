package ru.deelter.vrcamera.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.vivecraft.api.client.Tracker;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.VRData;
import org.vivecraft.client_vr.VRState;
import org.vivecraft.client_vr.gameplay.trackers.CameraTracker;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.config.ShotConfig;
import ru.deelter.vrcamera.client.director.Director;
import ru.deelter.vrcamera.client.math.CamMath;
import ru.deelter.vrcamera.client.rig.Rig;
import ru.deelter.vrcamera.client.rig.Subject;
import ru.deelter.vrcamera.client.shot.Shot;
import ru.deelter.vrcamera.client.shot.ShotType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Drives the Vivecraft handheld camera. Runs as a Vivecraft tracker, once per frame before rendering.
 */
public final class CameraController implements Tracker {
	public static final CameraController INSTANCE = new CameraController();

	public enum Mode {
		/** the camera is left alone */
		OFF,
		/** shots are picked and switched automatically */
		DIRECTOR,
		/** the camera stays where it was placed by hand, relative to the player */
		FOLLOW;

		public Component label() {
			return Component.translatable("vrcamera.mode." + name().toLowerCase(Locale.ROOT));
		}
	}

	private static final int MARKER_COLOR = 0xFFFF2020;
	// seconds a summoned camera waits to be picked up
	private static final double PARK_SECONDS = 20.0;
	// a gap between frames this long means VR was paused, not a slow frame
	private static final double RESUME_GAP = 0.5;

	private final Subject subject = new Subject();
	private final Rig rig = new Rig();

	private Mode mode = Mode.OFF;
	private CameraConfig config = CameraConfig.load();
	private Director director = new Director(this.config);
	private Shot followShot;

	// Vivecraft state that gets changed while the camera is on, and is put back after
	private boolean engaged;
	private boolean previousMirror;
	private float previousFov;
	private boolean shownByUs;

	private boolean wasGrabbed;
	// seconds the camera still waits in front of the player, to be picked up by hand
	private double parkedTime;
	private long lastNanos;

	private CameraController() {}

	public Mode mode() {
		return this.mode;
	}

	public Director director() {
		return this.director;
	}

	public CameraConfig config() {
		return this.config;
	}

	/**
	 * @return if the Vivecraft camera model should not be shown in the headset
	 */
	public boolean hidesModel() {
		return this.engaged && !"model".equals(this.config.marker);
	}

	/**
	 * Shows where the camera is, called while Vivecraft collects what to render for one of the eyes.
	 *
	 * @param pos        camera position
	 * @param worldScale Vivecraft world scale, to keep the size the same for the player
	 */
	public void drawMarker(Vec3 pos, float worldScale) {
		if (!this.engaged || !"dot".equals(this.config.marker)) {
			return;
		}
		try {
			Gizmos.point(pos, MARKER_COLOR, (float) this.config.markerSize);
			if (this.config.markerLabel) {
				Gizmos.billboardText(markerText(), pos.add(0, 0.07 * worldScale, 0),
					TextGizmo.Style.forColorAndCentered(MARKER_COLOR).withScale(0.1F * worldScale));
			}
		} catch (RuntimeException e) {
			// no gizmo collection is running, nothing to draw into
		}
	}

	private String markerText() {
		if (this.mode == Mode.FOLLOW) {
			return "REC follow";
		}
		Shot shot = this.director.current();
		String text = shot == null ? "REC" : "REC " + shot.type.name().toLowerCase(Locale.ROOT);
		return this.director.isHolding() ? text + " (hold)" : text;
	}

	/**
	 * @return lines describing what the camera is doing, for the debug overlay
	 */
	public List<String> debugLines() {
		List<String> lines = new ArrayList<>();
		lines.add("VRCamera " + this.mode + (this.engaged || this.mode == Mode.OFF ? "" : " (waiting for VR)") +
			(this.parkedTime > 0 ? String.format(Locale.ROOT, " (parked %.0fs)", this.parkedTime) : ""));
		if (this.mode == Mode.OFF) {
			return lines;
		}
		Shot shot = this.mode == Mode.FOLLOW ? this.followShot : this.director.current();
		if (shot != null) {
			String time = shot.duration > 1.0E6 ? String.format(Locale.ROOT, "%.1fs", shot.age) :
				String.format(Locale.ROOT, "%.1f/%.1fs", shot.age, shot.duration);
			lines.add("shot: " + shot.type + (shot.side < 0 ? " left " : " right ") + time +
				(this.director.isHolding() ? " HOLD" : ""));
		}
		if (this.mode == Mode.DIRECTOR) {
			lines.add("why: " + this.director.lastReason());
			lines.add("context: " + this.director.context() + (this.director.isTight() ? " tight" : "") +
				(this.director.event() != Director.Event.NONE ? " event " + this.director.event() : ""));
			lines.add(String.format(Locale.ROOT, "blocked: %.1fs", Math.max(0, this.director.occludedTime())));
		} else {
			lines.add("preset: " + presetLabel());
		}
		lines.add(String.format(Locale.ROOT, "arm: %.0f%%%s  fov: %.0f", this.rig.arm() * 100.0,
			this.rig.lookingPast() ? " (looking past)" : "", this.rig.fov()));
		lines.add(String.format(Locale.ROOT, "speed: %.1f  scale: %.2f", this.subject.speed, this.subject.unit));
		if (this.subject.target != null) {
			lines.add("target: " + this.subject.target.getName().getString());
		}
		return lines;
	}

	public void toggleDebug() {
		this.config.debugOverlay = !this.config.debugOverlay;
		this.config.save();
	}

	public boolean debugEnabled() {
		return this.config.debugOverlay;
	}

	/**
	 * @return text for the preset button, like "2/3"
	 */
	public String presetLabel() {
		return (this.config.activePreset + 1) + "/" + this.config.presets.size();
	}

	/**
	 * switches to the next hand placed shot
	 */
	public void nextPreset() {
		if (this.mode == Mode.OFF) {
			return;
		}
		this.config.activePreset = (this.config.activePreset + 1) % this.config.presets.size();
		this.config.save();
		showPreset();
	}

	/**
	 * @param index number of the hand placed shot, starting at 0
	 * @return false if there is no such shot, or the camera is off
	 */
	public boolean selectPreset(int index) {
		if (this.mode == Mode.OFF || index < 0 || index >= this.config.presets.size()) {
			return false;
		}
		this.config.activePreset = index;
		this.config.save();
		showPreset();
		return true;
	}

	/**
	 * adds a hand placed shot, as a copy of the active one, to then be placed somewhere else
	 */
	public void newPreset() {
		if (this.mode == Mode.OFF) {
			return;
		}
		this.config.presets.add(this.config.preset().copy());
		this.config.activePreset = this.config.presets.size() - 1;
		this.config.save();
		showPreset();
	}

	public void deletePreset() {
		if (this.mode == Mode.OFF || this.config.presets.size() <= 1) {
			return;
		}
		this.config.presets.remove(this.config.activePreset);
		this.config.activePreset = Math.min(this.config.activePreset, this.config.presets.size() - 1);
		this.config.save();
		showPreset();
	}

	private void showPreset() {
		notify(Component.translatable("vrcamera.message.preset", presetLabel()));
		if (!this.rig.ready() || this.subject.player == null) {
			return;
		}
		Shot shot = customShot();
		Shot previous = this.mode == Mode.FOLLOW ? this.followShot : this.director.current();
		if (previous != null && !previous.isWorld()) {
			// swing over to it
			this.rig.blend();
		} else {
			this.rig.snap(shot, this.subject);
		}
		if (this.mode == Mode.FOLLOW) {
			this.followShot = shot;
		} else {
			this.director.showManual(shot);
		}
	}

	public static boolean isVRRunning() {
		ClientDataHolderVR dh = ClientDataHolderVR.getInstance();
		return VRState.VR_RUNNING && dh.vrPlayer != null && dh.vrPlayer.vrdata_world_render != null;
	}

	public void cycleMode() {
		if (this.mode != Mode.OFF && !isVRRunning()) {
			// VR went away while the camera was on, the only way from here is off
			setMode(Mode.OFF);
			return;
		}
		setMode(Mode.values()[(this.mode.ordinal() + 1) % Mode.values().length]);
	}

	/**
	 * Called every client tick, also when VR is not running. Vivecraft can switch VR off at any time, when the
	 * headset is taken off or VR gets disabled, and then stops calling the tracker without notice.
	 */
	public void tick() {
		if (this.engaged && (!isVRRunning() || Minecraft.getInstance().player == null)) {
			// don't leave the camera settings changed while nothing is filmed, the mode stays for when VR is back
			release();
			this.subject.reset();
			this.rig.reset();
			this.wasGrabbed = false;
			this.parkedTime = 0;
		}
	}

	/**
	 * Puts the camera in front of the face of the player and leaves it there, to be grabbed and placed by hand.
	 * A camera that follows the player can not be reached otherwise, it backs away when walking up to it.
	 */
	public void summon() {
		if (this.mode == Mode.OFF || !isVRRunning()) {
			return;
		}
		ClientDataHolderVR dh = ClientDataHolderVR.getInstance();
		VRData vr = dh.vrPlayer.vrdata_world_render;
		Vec3 head = vr.hmd.getPosition();
		Vector3f look = vr.hmd.getDirection();
		Vec3 forward = new Vec3(look.x, 0, look.z);
		forward = forward.length() < 1.0E-3 ? CamMath.forward(vr.hmd.getYawRad()) : forward.normalize();

		// within reach, a bit below the eyes
		Vec3 pos = head.add(forward.scale(0.45 * vr.worldScale)).add(0, -0.15 * vr.worldScale, 0);
		Quaternionf rotation = new Quaternionf();
		CamMath.lookRotation(head.subtract(pos), rotation);
		dh.cameraTracker.setPosition(pos);
		dh.cameraTracker.setRotation(rotation);

		this.parkedTime = PARK_SECONDS;
		notify(Component.translatable("vrcamera.message.summon"));
	}

	public void setMode(Mode mode) {
		if (mode == this.mode) {
			return;
		}
		if (mode != Mode.OFF) {
			if (!isVRRunning()) {
				notify(Component.translatable("vrcamera.message.novr"));
				return;
			}
			if (ClientDataHolderVR.getInstance().vrSettings.seated) {
				notify(Component.translatable("vrcamera.message.seated"));
				return;
			}
			if (this.mode == Mode.OFF) {
				// pick up changes made to the file
				this.config = CameraConfig.load();
				this.director = new Director(this.config);
			}
		}
		this.mode = mode;
		this.followShot = null;
		this.director.reset();
		this.rig.reset();
		this.subject.reset();
		this.wasGrabbed = false;
		this.parkedTime = 0;
		if (mode == Mode.OFF) {
			release();
		}
		notify(Component.translatable("vrcamera.message.mode", mode.label()));
	}

	public void nextShot() {
		if (this.mode == Mode.DIRECTOR) {
			this.director.next();
			notify(Component.translatable("vrcamera.message.next"));
		}
	}

	/**
	 * Shows a shot of the given type, turns the director on for it if needed.
	 *
	 * @return false if the camera can't be turned on
	 */
	public boolean showShot(ShotType type) {
		if (this.mode != Mode.DIRECTOR) {
			setMode(Mode.DIRECTOR);
			if (this.mode != Mode.DIRECTOR) {
				return false;
			}
		}
		this.director.force(type);
		notify(Component.translatable("vrcamera.message.shot", type.name().toLowerCase(Locale.ROOT)));
		return true;
	}

	/**
	 * reads the config file again, without turning the camera off
	 */
	public void reloadConfig() {
		this.config = CameraConfig.load();
		this.director = new Director(this.config);
		this.followShot = null;
		this.rig.reset();
	}

	public void toggleHold() {
		if (this.mode == Mode.DIRECTOR) {
			notify(Component.translatable(
				this.director.toggleHold() ? "vrcamera.message.hold" : "vrcamera.message.release"));
		}
	}

	private void notify(Component message) {
		if (Minecraft.getInstance().player != null) {
			Minecraft.getInstance().player.sendOverlayMessage(message);
		}
	}

	/**
	 * takes over the Vivecraft camera settings
	 */
	private void engage(ClientDataHolderVR dh) {
		this.previousMirror = dh.vrSettings.displayMirrorUseScreenshotCamera;
		this.previousFov = dh.vrSettings.handCameraFov;
		if (this.config.forceMirror) {
			dh.vrSettings.displayMirrorUseScreenshotCamera = true;
		}
		this.engaged = true;
	}

	/**
	 * puts the Vivecraft camera settings back to what they were
	 */
	public void release() {
		if (!this.engaged) {
			return;
		}
		ClientDataHolderVR dh = ClientDataHolderVR.getInstance();
		dh.vrSettings.displayMirrorUseScreenshotCamera = this.previousMirror;
		dh.vrSettings.handCameraFov = this.previousFov;
		if (this.shownByUs && dh.cameraTracker.isVisible()) {
			dh.cameraTracker.toggleVisibility();
		}
		this.shownByUs = false;
		this.engaged = false;
	}

	@Override
	public ProcessType processType() {
		return ProcessType.PER_FRAME;
	}

	@Override
	public boolean isActive(LocalPlayer player) {
		return this.mode != Mode.OFF && player != null && Minecraft.getInstance().gameMode != null && isVRRunning();
	}

	@Override
	public void inactiveProcess(LocalPlayer player) {
		// left the world or VR, start fresh when back
		this.subject.reset();
		this.rig.reset();
		this.wasGrabbed = false;
		this.parkedTime = 0;
	}

	@Override
	public void activeProcess(LocalPlayer player) {
		try {
			process(player);
		} catch (RuntimeException e) {
			// this runs right before the frame is rendered for the headset, a crash here would throw the player out
			// of VR. Turn the camera off instead
			Vrcamera.LOGGER.error("VRCamera: camera update failed, turning the camera off", e);
			setMode(Mode.OFF);
			notify(Component.translatable("vrcamera.message.error"));
		}
	}

	private void process(LocalPlayer player) {
		ClientDataHolderVR dh = ClientDataHolderVR.getInstance();
		CameraTracker camera = dh.cameraTracker;
		if (dh.vrSettings.seated) {
			// Vivecraft disables the handheld camera in seated mode
			setMode(Mode.OFF);
			return;
		}
		if (camera.isQuickMode()) {
			// the player is taking a quick screenshot, stay out of the way
			return;
		}
		if (!this.engaged) {
			engage(dh);
		}
		if (!camera.isVisible()) {
			camera.toggleVisibility();
			this.shownByUs = true;
		}

		long now = System.nanoTime();
		double realDt = Math.max(0.0, (now - this.lastNanos) / 1.0E9);
		double dt = Math.min(realDt, 0.1);
		this.lastNanos = now;
		if (Minecraft.getInstance().isPaused()) {
			dt = 0;
		}
		if (realDt > RESUME_GAP) {
			// VR was paused, the player can be anywhere by now
			this.subject.reset();
			this.rig.reset();
		}

		VRData vr = dh.vrPlayer.vrdata_world_render;
		float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
		this.subject.update(player, vr, partialTick, dt, realDt, this.config);

		if (camera.isMoving()) {
			// the player holds the camera in their hand
			this.wasGrabbed = true;
			this.parkedTime = 0;
			return;
		}
		if (this.wasGrabbed) {
			this.wasGrabbed = false;
			placedByHand(camera.getPosition());
		}
		if (this.parkedTime > 0) {
			// waiting to be picked up, runs out if nobody does
			this.parkedTime -= dt;
			return;
		}

		Shot shot;
		if (this.mode == Mode.FOLLOW) {
			if (this.followShot == null || !this.rig.ready()) {
				this.followShot = customShot();
				this.rig.snap(this.followShot, this.subject);
			} else if (this.subject.teleported) {
				this.rig.rebase(this.subject);
			}
			this.followShot.update(this.subject, this.config, dt);
			shot = this.followShot;
		} else {
			this.director.update(this.subject, this.rig, dt);
			shot = this.director.current();
		}

		this.rig.update(shot, this.subject, dt, this.config);

		camera.setPosition(this.rig.position());
		camera.setRotation(this.rig.rotation());
		dh.vrSettings.handCameraFov = (float) CamMath.clamp(this.rig.fov(), 1.0, 179.0);
	}

	private Shot customShot() {
		Shot shot = new Shot(ShotType.CUSTOM, this.config.preset(), 1);
		shot.start(this.subject, this.config);
		return shot;
	}

	/**
	 * the player let go of the camera, keep it where they put it, relative to them
	 */
	private void placedByHand(Vec3 cameraPos) {
		Vec3 offset = cameraPos.subtract(this.subject.center);
		ShotConfig preset = this.config.preset();
		preset.azimuth = Math.toDegrees(CamMath.wrap(CamMath.azimuthOf(offset) - this.subject.facing));
		preset.elevation = Math.toDegrees(CamMath.elevationOf(offset));
		// not inside the player
		preset.distance = Math.max(0.3, offset.length() / this.subject.unit);
		this.config.save();

		Shot shot = customShot();
		this.rig.adopt(cameraPos, shot, this.subject);
		if (this.mode == Mode.FOLLOW) {
			this.followShot = shot;
		} else {
			this.director.showManual(shot);
		}
	}
}
