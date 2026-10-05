package ru.deelter.vrcamera.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.vivecraft.api.client.Tracker;
import org.vivecraft.api.client.data.RenderPass;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.VRData;
import org.vivecraft.client_vr.VRState;
import org.vivecraft.client_vr.gameplay.VRPlayer;
import org.vivecraft.client_vr.gameplay.screenhandlers.GuiHandler;
import org.vivecraft.client_vr.gameplay.trackers.CameraTracker;
import org.vivecraft.common.utils.MathUtils;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.config.Marker;
import ru.deelter.vrcamera.client.config.ShotConfig;
import ru.deelter.vrcamera.client.director.Director;
import ru.deelter.vrcamera.client.math.CamMath;
import ru.deelter.vrcamera.client.math.SmoothVec;
import ru.deelter.vrcamera.client.rig.HandThrow;
import ru.deelter.vrcamera.client.rig.Rig;
import ru.deelter.vrcamera.client.rig.Subject;
import ru.deelter.vrcamera.client.rig.WorldProbe;
import ru.deelter.vrcamera.client.shot.Shot;
import ru.deelter.vrcamera.client.shot.ShotType;

import java.util.Locale;

/**
 * Drives the Vivecraft handheld camera. Runs as a Vivecraft tracker, once per frame before rendering.
 */
public final class CameraController implements Tracker {
	public static final CameraController INSTANCE = new CameraController();

	public enum Mode {
		/**
		 * the camera is left alone
		 */
		OFF,
		/**
		 * shots are picked and switched automatically
		 */
		DIRECTOR,
		/**
		 * the camera stays where it was placed by hand, relative to the player
		 */
		FOLLOW;

		public Component label() {
			return Component.translatable("vrcamera.mode." + name().toLowerCase(Locale.ROOT));
		}
	}

	private static final int MARKER_COLOR = 0xFFFF2020;
	// the arrow to the camera: how far in front of the face it floats, how far from the middle of the view, its size.
	// In blocks, about meters for the player
	private static final double INDICATOR_DISTANCE = 0.6;
	private static final double INDICATOR_RING = 0.15;
	private static final double INDICATOR_LENGTH = 0.06;
	private static final float INDICATOR_WIDTH = 4.0F;
	// no arrow while the camera is this close to the middle of the view
	private static final double INDICATOR_HIDE_ANGLE = Math.toRadians(25);
	// seconds a thrown camera of Vivecraft needs to get where it was thrown
	private static final double GLIDE_TIME = 0.3;
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
	private final HandThrow handThrow = new HandThrow();
	// for throwing the camera of Vivecraft while this mod is off: where it is flying to, null when it is not flying
	private Vec3 glideTarget;
	private boolean plainHeld;
	private final SmoothVec glide = new SmoothVec();
	private long glideNanos;
	// seconds the camera still waits in front of the player, to be picked up by hand
	private double parkedTime;
	private long lastNanos;

	private CameraController() {
	}

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
		return this.engaged && this.config.marker != Marker.MODEL;
	}

	/**
	 * Draws what helps the player find the camera, called while Vivecraft collects what to render for one of the eyes.
	 */
	public void drawHeadsetAids(VRData vr) {
		Vec3 camera = vr.getEye(RenderPass.CAMERA).getPosition();
		try {
			if (this.config.marker == Marker.DOT) {
				drawMarker(camera, vr.worldScale);
			}
			Shot shot = shot();
			// in first person the camera is right in front of the face
			if (this.config.indicator && (shot == null || shot.type != ShotType.POV)) {
				drawIndicator(camera, vr);
			}
		} catch (IllegalStateException e) {
			// no gizmo collection is running, nothing to draw into
		}
	}

	private void drawMarker(Vec3 camera, float worldScale) {
		Gizmos.point(camera, MARKER_COLOR, (float) this.config.markerSize);
		if (this.config.markerLabel) {
			Gizmos.billboardText(markerText(), camera.add(0, 0.07 * worldScale, 0),
					TextGizmo.Style.forColorAndCentered(MARKER_COLOR).withScale(0.1F * worldScale));
		}
	}

	/**
	 * An arrow floating in front of the face, on a ring around where the player looks, pointing to the side the
	 * camera is on. Not shown while the camera is in view anyway.
	 */
	private void drawIndicator(Vec3 camera, VRData vr) {
		Vec3 head = vr.hmd.getPosition();
		Vec3 forward = new Vec3(vr.hmd.getDirection());
		Vec3 up = new Vec3(vr.hmd.getCustomVector(MathUtils.UP));
		Vec3 right = forward.cross(up);

		// where the camera is, as seen by the player
		Vec3 toCamera = camera.subtract(head);
		double x = toCamera.dot(right);
		double y = toCamera.dot(up);
		double z = toCamera.dot(forward);
		double sideways = Math.sqrt(x * x + y * y);
		if (Math.atan2(sideways, z) < INDICATOR_HIDE_ANGLE) {
			return;
		}
		// straight behind has no side, call that right
		Vec3 side = sideways < 1.0E-3 ? right : right.scale(x / sideways).add(up.scale(y / sideways));

		float scale = vr.worldScale;
		Vec3 ring = head.add(forward.scale(INDICATOR_DISTANCE * scale));
		Vec3 base = ring.add(side.scale(INDICATOR_RING * scale));
		Vec3 tip = ring.add(side.scale((INDICATOR_RING + INDICATOR_LENGTH) * scale));
		Gizmos.arrow(base, tip, MARKER_COLOR, INDICATOR_WIDTH).setAlwaysOnTop();
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
	 * @return the shot the camera is showing, null while there is none
	 */
	public Shot shot() {
		return this.mode == Mode.FOLLOW ? this.followShot : this.director.current();
	}

	public Rig rig() {
		return this.rig;
	}

	public Subject subject() {
		return this.subject;
	}

	/**
	 * @return if the camera is being driven, and not just turned on and waiting for VR
	 */
	public boolean isEngaged() {
		return this.engaged;
	}

	/**
	 * @return seconds a summoned camera still waits to be picked up
	 */
	public double parkedTime() {
		return this.parkedTime;
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
		Shot previous = shot();
		if (previous != null && previous.blends()) {
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
		this.handThrow.clear();
		this.glideTarget = null;
		this.plainHeld = false;
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

	/**
	 * the player hit something, that is what a fight is filmed against
	 */
	public void onAttack(Entity entity) {
		if (this.mode == Mode.DIRECTOR) {
			this.director.onAttack(entity);
		}
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
	public void idleProcess(LocalPlayer player) {
		if (this.mode == Mode.OFF && player != null && isVRRunning()) {
			throwPlainCamera(player);
		}
	}

	/**
	 * Lets the camera of Vivecraft be thrown as well, while this mod does nothing else with it. It flies to where
	 * it was thrown and stays there.
	 */
	private void throwPlainCamera(LocalPlayer player) {
		CameraTracker camera = ClientDataHolderVR.getInstance().cameraTracker;
		long now = System.nanoTime();
		double dt = Math.min((now - this.glideNanos) / 1.0E9, 0.1);
		this.glideNanos = now;

		if (!camera.isVisible() || camera.isQuickMode()) {
			this.plainHeld = false;
			this.glideTarget = null;
			this.handThrow.clear();
			return;
		}
		if (camera.isMoving()) {
			this.plainHeld = true;
			this.glideTarget = null;
			this.handThrow.sample(camera.getPosition());
			return;
		}
		if (this.plainHeld) {
			this.plainHeld = false;
			Vec3 motion = player.getDeltaMovement();
			// per tick to per second. Without the vertical part, that is gravity even while standing
			Vec3 thrown = this.handThrow.release(new Vec3(motion.x * 20.0, 0, motion.z * 20.0),
					this.config.throwPower);
			if (thrown.lengthSqr() > 0) {
				Vec3 from = camera.getPosition();
				this.glideTarget = WorldProbe.reach(player, from, from.add(thrown));
				this.glide.reset(from);
			}
		}
		if (this.glideTarget != null) {
			Vec3 pos = this.glide.update(this.glideTarget, GLIDE_TIME, dt);
			camera.setPosition(pos);
			if (pos.distanceTo(this.glideTarget) < 0.02) {
				this.glideTarget = null;
			}
		}
	}

	/**
	 * @return where in the world the inventory or chest menu is that the player has open, null if there is none
	 */
	private static Vec3 openMenuPosition(VRData vr) {
		if (GuiHandler.GUI_POS_ROOM == null ||
				!(Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?>)) {
			return null;
		}
		return VRPlayer.roomToWorldPos(GuiHandler.GUI_POS_ROOM, vr);
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
		this.subject.guiCenter = openMenuPosition(vr);

		if (camera.isMoving()) {
			// the player holds the camera in their hand
			this.wasGrabbed = true;
			this.parkedTime = 0;
			this.handThrow.sample(camera.getPosition());
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
	 * the player let go of the camera, keep it where they put it, or threw it to, relative to them
	 */
	private void placedByHand(Vec3 cameraPos) {
		Vec3 landing = cameraPos;
		Vec3 thrown = this.handThrow.release(this.subject.velocity, this.config.throwPower);
		boolean wasThrown = thrown.lengthSqr() > 0;
		if (wasThrown) {
			Vec3 target = cameraPos.add(thrown);
			landing = cameraPos.lerp(target, WorldProbe.armFraction(this.subject, cameraPos, target, this.config));
		}

		Vec3 offset = landing.subtract(this.subject.center);
		ShotConfig preset = this.config.preset();
		preset.azimuth = Math.toDegrees(CamMath.wrap(CamMath.azimuthOf(offset) - this.subject.facing));
		preset.elevation = Math.toDegrees(CamMath.elevationOf(offset));
		// not inside the player
		preset.distance = Math.max(0.3, offset.length() / this.subject.unit);
		this.config.save();

		Shot shot = customShot();
		// start where the hand let go
		this.rig.adopt(cameraPos, shot, this.subject);
		if (wasThrown) {
			// and fly from there to where it was thrown
			this.rig.blend();
		}
		if (this.mode == Mode.FOLLOW) {
			this.followShot = shot;
		} else {
			this.director.showManual(shot);
		}
	}
}
