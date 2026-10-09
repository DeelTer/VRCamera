package ru.deelter.vrcamera.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.vivecraft.api.client.Tracker;
import org.vivecraft.api.client.VRClientAPI;
import org.vivecraft.api.client.data.RenderPass;
import org.vivecraft.api.data.VRBodyPart;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.VRData;
import org.vivecraft.client_vr.VRState;
import org.vivecraft.client_vr.gameplay.VRPlayer;
import org.vivecraft.client_vr.gameplay.screenhandlers.GuiHandler;
import org.vivecraft.client_vr.gameplay.trackers.CameraTracker;
import org.vivecraft.common.utils.MathUtils;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.compat.Gizmos;
import ru.deelter.vrcamera.client.compat.TextGizmo;
import ru.deelter.vrcamera.client.config.*;
import ru.deelter.vrcamera.client.director.Director;
import ru.deelter.vrcamera.client.math.CamMath;
import ru.deelter.vrcamera.client.math.Smooth;
import ru.deelter.vrcamera.client.math.SmoothVec;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;
import ru.deelter.vrcamera.client.rig.*;
import ru.deelter.vrcamera.client.shot.Shot;
import ru.deelter.vrcamera.client.shot.ShotType;
import ru.deelter.vrcamera.client.sync.PhotoSync;

import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Drives the Vivecraft handheld camera. Runs as a Vivecraft tracker, once per frame before rendering.
 */
public final class CameraController implements Tracker {
	public static final CameraController INSTANCE = new CameraController();
	private static final int MARKER_COLOR = 0xFFFF2020;
	private static final String INDICATOR_ICON = "\uE7C0";
	private static final double PHYSICS_LEASH = 40.0;
	private static final double PULL_TIME = 0.12;
	private static final double PULL_ARRIVED = 0.15;
	private static final double PUT_UP_CHECK_TIME = 0.05;
	private static final double PULL_WINDUP = 0.14;
	private static final double PULL_MAX_RELEASE_SPEED = 14.0;
	private static final double PULL_FLING_SPEED = 1.5;
	private static final double PULL_FLING_GAIN = 2.2;
	private static final double PULL_SPARKS = 30.0;
	private static final double SELFIE_COS = Math.cos(Math.toRadians(60));
	private static final double UNDERWATER_FOV = 18.0;
	private static final double UNDERWATER_TIME = 0.4;
	private static final double BUBBLES_PER_SECOND = 6.0;
	private static final double SHUTTER_FOV = 5.0;
	private static final double SHUTTER_TIME = 0.15;
	private static final String PHOTO_ICON = "";
	private static final double PHOTO_ICON_MIN_DISTANCE = 2.5;
	private static final double PHOTO_ICON_MAX_DISTANCE = 48.0;
	private static final double PULL_GRIP_OFFSET = 0.08;
	private static final double GLIDE_TIME = 0.3;
	private static final double PARK_SECONDS = 20.0;
	private static final double RESUME_GAP = 0.5;
	private final Subject subject = new Subject();
	private final Rig rig = new Rig();
	private final HandThrow handThrow = new HandThrow();
	private final DroppedCamera dropped = new DroppedCamera();
	private final HandheldShake shake = new HandheldShake();
	private final LimbStrikes limbs = new LimbStrikes();
	private final SecondButton secondButton = new SecondButton();
	private final Quaternionf handRotation = new Quaternionf();
	private final HandStabilizer stabilizer = new HandStabilizer();
	private final SmoothVec pullGlide = new SmoothVec();
	private final Smooth underwater = new Smooth();
	private final SmoothVec glide = new SmoothVec();
	private Mode mode = Mode.OFF;
	private CameraConfig config = CameraConfig.current();
	private Director director = new Director(this.config);
	private Shot followShot;
	private boolean engaged;
	private boolean previousMirror;
	private float previousFov;
	private boolean shownByUs;
	private boolean wasGrabbed;
	private boolean couldPutUp;
	private ResourceKey<Level> dimension;
	private boolean changedDimension;
	private double putUpCheck;
	private int offeredHand = -1;
	private long albumNanos;
	private int shutterTicks;
	private int shareTicks;
	private boolean shutterTaken;
	private Vec3 handPosition;
	private InteractionHand pullHand;
	private boolean pullDrawn;
	private boolean pullLifted;
	private boolean pullArc;
	private double pullElapsed;
	private Vec3 pullStart = Vec3.ZERO;
	private Vec3 pullLast = Vec3.ZERO;
	private Vec3 pullVelocity = Vec3.ZERO;
	private double frameDt;
	private double shutter;
	private boolean wasInWater;
	private boolean wasDead;
	private Entity killer;
	private Vec3 glideTarget;
	private boolean plainHeld;
	private long glideNanos;
	private double parkedTime;
	private long lastNanos;

	private CameraController() {
	}

	public static boolean isVRRunning() {
		ClientDataHolderVR dh = ClientDataHolderVR.getInstance();
		return VRState.VR_RUNNING && dh.vrPlayer != null && dh.vrPlayer.vrdata_world_render != null;
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
		if (!this.engaged || this.mode == Mode.PHYSICS) {
			return false;
		}
		Shot shot = shot();
		return this.config.marker != Marker.MODEL || (shot != null && shot.type == ShotType.POV);
	}

	/**
	 * @return where the hand has the camera, before steadying. Null while it is not held
	 */
	public Vec3 handPosition() {
		return this.handPosition;
	}

	public Quaternionf handRotation() {
		return this.handRotation;
	}

	/**
	 * @return the arm that holds the camera, if it should not be in the picture. Null to leave both arms alone
	 */
	public HumanoidArm armToHide() {
		ClientDataHolderVR dh = ClientDataHolderVR.getInstance();
		if (!this.engaged || !this.config.hideHoldingArm || this.handPosition == null ||
				dh.currentPass != RenderPass.CAMERA || !dh.cameraTracker.isMoving()) {
			return null;
		}
		CameraTracker camera = dh.cameraTracker;
		if (looksAtPlayer(camera.getPosition(), camera.getRotation())) {
			return null;
		}
		boolean rightHand = (camera.getMovingController() == 0) != dh.vrSettings.reverseHands;
		return rightHand ? HumanoidArm.RIGHT : HumanoidArm.LEFT;
	}

	/**
	 * @return if the head of the player should not be in the picture: the camera sits on it and films from inside
	 */
	public boolean hidesOwnHead() {
		return this.engaged && this.mode == Mode.PHYSICS && this.dropped.isOnPlayer() &&
				ClientDataHolderVR.getInstance().currentPass == RenderPass.CAMERA;
	}

	private boolean looksAtPlayer(Vec3 position, Quaternionf rotation) {
		Vec3 lens = new Vec3(rotation.transform(new Vector3f(0, 0, -1)));
		Vec3 toHead = this.subject.head.subtract(position);
		return toHead.lengthSqr() > 1.0E-6 && lens.dot(toHead.normalize()) > SELFIE_COS;
	}

	/**
	 * @return if the camera is near the player with its lens to them, who then can't see the screen on its back.
	 * In the hand, or anywhere it was put
	 */
	public boolean showsSelfieScreen() {
		if (!this.engaged || !this.config.selfieScreen || hidesModel()) {
			return false;
		}
		CameraTracker camera = ClientDataHolderVR.getInstance().cameraTracker;
		boolean held = camera.isMoving() && this.handPosition != null;
		Vec3 position = held ? this.handPosition : camera.getPosition();
		return position.distanceTo(this.subject.head) <= this.config.selfieDistance &&
				looksAtPlayer(position, held ? this.handRotation : camera.getRotation());
	}

	/**
	 * Draws what helps the player find the camera, called while Vivecraft collects what to render for one of the eyes.
	 */
	public void drawHeadsetAids(VRData vr) {
		try {
			if (this.config.indicator) {
				Vec3 head = vr.hmd.getPosition();
				PhotoAlbum.INSTANCE.forEachLoose(sheet -> {
					double distance = sheet.distanceTo(head);
					if (distance > PHOTO_ICON_MIN_DISTANCE && distance < PHOTO_ICON_MAX_DISTANCE) {
						drawIndicator(PHOTO_ICON, sheet, vr, false);
					}
				});
			}
			if (!this.engaged || !ClientDataHolderVR.getInstance().cameraTracker.isVisible()) {
				return;
			}
			Vec3 camera = this.handPosition != null ? this.handPosition : vr.getEye(RenderPass.CAMERA).getPosition();
			if (this.config.marker == Marker.DOT && this.mode != Mode.PHYSICS) {
				drawMarker(camera, vr.worldScale);
			}
			Shot shot = shot();
			if (this.config.indicator && (shot == null || shot.type != ShotType.POV)) {
				drawIndicator(INDICATOR_ICON, camera, vr, true);
			}
		} catch (IllegalStateException e) {
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
	 * The camera icon with the distance to the camera below it, like a waypoint: at the camera and seen through
	 * walls. While the camera is out of sight the icon sticks to the edge of the view on the side the camera is on.
	 */
	private void drawIndicator(String icon, Vec3 camera, VRData vr, boolean alsoOutOfSight) {
		CameraIndicator.draw(icon, "", camera, vr.hmd.getPosition(), new Vec3(vr.hmd.getDirection()),
				new Vec3(vr.hmd.getCustomVector(MathUtils.UP)), vr.worldScale, alsoOutOfSight, 1.0,
				UnaryOperator.identity());
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

	public void cycleMode() {
		if (this.mode != Mode.OFF && !isVRRunning()) {
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
			release();
			this.subject.reset();
			this.rig.reset();
			this.wasGrabbed = false;
			this.parkedTime = 0;
			this.pullHand = null;
			this.handPosition = null;
		}
		tickShutterButton();
		if (this.engaged && this.config.shareCamera && ++this.shareTicks % 2 == 0 && isVRRunning()) {
			CameraTracker camera = ClientDataHolderVR.getInstance().cameraTracker;
			if (camera.isVisible()) {
				boolean held = this.handPosition != null;
				PhotoSync.INSTANCE.shareCamera(held ? this.handPosition : camera.getPosition(),
						held ? this.handRotation : camera.getRotation());
			}
		}
	}

	/**
	 * the photo gesture of the hand that holds the camera: its other button, held for a moment
	 */
	private void tickShutterButton() {
		CameraTracker camera = ClientDataHolderVR.getInstance().cameraTracker;
		if (!this.engaged || this.config.photoGesture != PhotoGesture.SAME_HAND || !isVRRunning() ||
				!camera.isMoving() || camera.isQuickMode()) {
			this.secondButton.reset();
			this.shutterTicks = 0;
			this.shutterTaken = false;
			return;
		}
		int hand = camera.getMovingController();
		if (!this.secondButton.isDown(hand)) {
			this.shutterTicks = 0;
			this.shutterTaken = false;
			return;
		}
		if (this.shutterTaken) {
			return;
		}
		double seconds = this.config.photoHoldSeconds;
		double progress = seconds <= 0 ? 1.0 : Math.min(1.0, ++this.shutterTicks / (seconds * 20.0));
		VRClientAPI.instance().triggerHapticPulse(hand == 0 ? VRBodyPart.MAIN_HAND : VRBodyPart.OFF_HAND, 0.05F,
				(float) CamMath.lerp(120.0, 320.0, progress), (float) CamMath.lerp(0.15, 1.0, progress), 0.0F);
		if (progress >= 1.0 && takePhoto()) {
			this.shutterTaken = true;
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

		Vec3 pos = head.add(forward.scale(0.45 * vr.worldScale)).add(0, -0.15 * vr.worldScale, 0);
		Quaternionf rotation = new Quaternionf();
		CamMath.lookRotation(head.subtract(pos), rotation);
		dh.cameraTracker.setPosition(pos);
		dh.cameraTracker.setRotation(rotation);
		this.dropped.pickUp();
		this.pullHand = null;

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
				this.config = CameraConfig.reload();
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
		this.handPosition = null;
		this.handThrow.clear();
		this.glideTarget = null;
		this.plainHeld = false;
		this.dropped.pickUp();
		this.pullHand = null;
		this.wasDead = false;
		this.killer = null;
		this.underwater.reset(0);
		this.wasInWater = false;
		if (mode == Mode.OFF) {
			release();
		}
		notify(Component.translatable("vrcamera.message.mode", mode.label()));
		if (mode == Mode.PHYSICS) {
			summon();
		}
	}

	/**
	 * @return what the camera of the physics mode is doing, for the debug overlay
	 */
	public String physicsState() {
		if (this.pullHand != null) {
			return "pulled";
		}
		if (this.parkedTime > 0) {
			return "waiting";
		}
		if (!this.dropped.isDropped()) {
			return "held";
		}
		if (this.dropped.isCarried()) {
			return "carried";
		}
		return this.dropped.isResting() ? "lying" : "falling";
	}

	/**
	 * @return if the camera is somewhere on its own, to be pulled into a hand from afar
	 */
	public boolean canPull() {
		if (!this.engaged || this.pullHand != null) {
			return false;
		}
		if (this.mode == Mode.PHYSICS) {
			return this.dropped.isDropped();
		}
		return this.config.pullAllModes && this.rig.ready() &&
				!ClientDataHolderVR.getInstance().cameraTracker.isMoving();
	}

	/**
	 * the camera flies into the hand, and stays in it until {@link #endPull}
	 */
	public void startPull(InteractionHand hand) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (!canPull() || player == null) {
			return;
		}
		this.pullHand = hand;
		this.parkedTime = 0;
		this.pullDrawn = this.config.pullStyle == PullStyle.TELEKINESIS;
		this.pullStart = ClientDataHolderVR.getInstance().cameraTracker.getPosition();
		this.pullLast = this.pullStart;
		this.pullVelocity = Vec3.ZERO;
		this.pullElapsed = 0;
		this.pullLifted = false;
		this.pullArc = this.mode == Mode.PHYSICS && this.dropped.isResting() && !this.dropped.isMounted() &&
				!this.dropped.isAttached();
		this.pullGlide.reset(this.pullStart);
		this.handThrow.clear();
		if (!this.pullDrawn) {
			this.dropped.pickUp();
			this.pullLifted = true;
		}
		CameraEffects.pullStart(player);
	}

	/**
	 * @return how far the camera is on its way to that hand, from 0 to 1. -1 if it is not being drawn to it
	 */
	public double pullProgress(InteractionHand hand) {
		if (this.pullHand != hand || !this.pullDrawn) {
			return -1;
		}
		return Math.min(1.0, this.pullElapsed / Math.max(0.05, this.config.pullSeconds));
	}

	/**
	 * the hand that pulled the camera let go of it
	 */
	public void endPull(InteractionHand hand) {
		CameraTracker camera = ClientDataHolderVR.getInstance().cameraTracker;
		if (this.pullHand == hand) {
			this.pullHand = null;
			if (!this.pullLifted) {
				return;
			}
			if (this.mode == Mode.PHYSICS) {
				Vec3 speed = this.pullDrawn ? this.pullVelocity : Vec3.ZERO;
				Vec3 swing = this.pullDrawn ? this.handThrow.velocity(this.subject.velocity) : Vec3.ZERO;
				if (swing.length() > PULL_FLING_SPEED) {
					speed = swing.scale(PULL_FLING_GAIN * this.config.throwPower).add(speed.scale(0.25));
				}
				if (speed.length() > PULL_MAX_RELEASE_SPEED) {
					speed = speed.normalize().scale(PULL_MAX_RELEASE_SPEED);
				}
				this.dropped.drop(camera.getPosition(), camera.getRotation(), speed);
				this.limbs.reset();
			} else if (this.pullDrawn) {
				placedByHand(camera.getPosition());
			} else {
				Shot shot = shot();
				if (shot != null && this.rig.ready() && this.subject.player != null) {
					this.rig.adopt(camera.getPosition(), shot, this.subject);
					this.rig.blend();
				}
			}
		} else if (camera.isMoving() && camera.getMovingController() == hand.ordinal()) {
			camera.stopMoving();
		}
	}

	/**
	 * saves what the camera films right now as a picture, and prints it
	 */
	public boolean takePhoto() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && !isVRRunning()) {
			if (!PhotoAlbum.INSTANCE.takeWithoutCamera(player, this.config.photoSheet)) {
				return false;
			}
			CameraEffects.ownShutter(player);
			return true;
		}
		if (!this.engaged || player == null) {
			notify(Component.translatable("vrcamera.message.photo.off"));
			return false;
		}
		if (PhotoAlbum.INSTANCE.isPrinting() || !PhotoAlbum.INSTANCE.take(this.config.photoSheet)) {
			return false;
		}
		this.shutter = SHUTTER_TIME;
		CameraEffects.ownShutter(player);
		return true;
	}

	public void offerHand(int hand) {
		this.offeredHand = hand;
	}

	public void withdrawHand(int hand) {
		if (this.offeredHand == hand) {
			this.offeredHand = -1;
		}
	}

	private double shutterFov() {
		return SHUTTER_FOV * this.shutter / SHUTTER_TIME;
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
		this.config = CameraConfig.reload();
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
			Minecraft.getInstance().player.displayClientMessage(message, true);
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
		if (player == null || !isVRRunning()) {
			return;
		}
		if (this.mode == Mode.OFF) {
			throwPlainCamera(player);
		}
		long now = System.nanoTime();
		double dt = Minecraft.getInstance().isPaused() ? 0 : Math.min((now - this.albumNanos) / 1.0E9, 0.1);
		this.albumNanos = now;
		try {
			PhotoAlbum.INSTANCE.update(player.level(), Vive.hands(ClientDataHolderVR.getInstance().vrPlayer.vrdata_world_render),
					dt);
		} catch (RuntimeException e) {
			Vrcamera.LOGGER.error("VRCamera: updating photo sheets failed", e);
			PhotoAlbum.INSTANCE.clear();
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
	 * @return where in the world the menu is that the player has open, null if there is none
	 */
	private Vec3 openMenuPosition(VRData vr) {
		Screen screen = Minecraft.getInstance().screen;
		if (GuiHandler.GUI_POS_ROOM == null || screen == null ||
				(screen instanceof ChatScreen && !this.config.menuShotChat)) {
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
		this.subject.reset();
		this.rig.reset();
		this.wasGrabbed = false;
		this.parkedTime = 0;
		this.pullHand = null;
		this.handPosition = null;
	}

	@Override
	public void activeProcess(LocalPlayer player) {
		try {
			this.frameDt = 0;
			process(player);
			if (this.engaged) {
				CameraTracker camera = ClientDataHolderVR.getInstance().cameraTracker;
				boolean held = this.handPosition != null;
				PhotoAlbum.INSTANCE.hangFrom(held ? this.handPosition : camera.getPosition(),
						held ? this.handRotation : camera.getRotation(),
						ClientDataHolderVR.getInstance().vrPlayer.vrdata_world_render.worldScale);
			}
		} catch (RuntimeException e) {
			Vrcamera.LOGGER.error("VRCamera: camera update failed, turning the camera off", e);
			setMode(Mode.OFF);
			notify(Component.translatable("vrcamera.message.error"));
		}
	}

	private void process(LocalPlayer player) {
		ClientDataHolderVR dh = ClientDataHolderVR.getInstance();
		CameraTracker camera = dh.cameraTracker;
		this.handPosition = null;
		if (dh.vrSettings.seated) {
			setMode(Mode.OFF);
			return;
		}
		if (camera.isQuickMode()) {
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
		double worldDt = Minecraft.getInstance().isPaused() ? 0 : dt;
		this.frameDt = worldDt;
		this.shutter = Math.max(0.0, this.shutter - dt);
		if (realDt > RESUME_GAP) {
			this.subject.reset();
			this.rig.reset();
		}

		VRData vr = dh.vrPlayer.vrdata_world_render;
		float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
		VrSubject.update(this.subject, player, vr, partialTick, dt, realDt, this.config);
		this.subject.guiCenter = openMenuPosition(vr);
		this.dropped.allowSelf(this.config.attachToSelf);
		ResourceKey<Level> dimension = player.level().dimension();
		this.changedDimension = this.dimension != null && !this.dimension.equals(dimension);
		this.dimension = dimension;

		if (this.mode == Mode.PHYSICS) {
			watchDeath(player, camera);
			boolean submerged = this.config.underwaterLook && WorldProbe.inFluid(this.subject, camera.getPosition());
			double wet = this.underwater.update(submerged ? 1.0 : 0.0, UNDERWATER_TIME, dt);
			dh.vrSettings.handCameraFov = (float) CamMath.clamp(
					this.previousFov + this.dropped.fovOffset() + wet * UNDERWATER_FOV - shutterFov(), 1.0, 179.0);
		}
		if (this.pullHand != null) {
			flyToHand(camera, vr, player, dt);
			return;
		}
		if (camera.isMoving()) {
			if (!this.wasGrabbed) {
				this.stabilizer.reset();
			}
			this.wasGrabbed = true;
			this.parkedTime = 0;
			this.dropped.pickUp();
			this.handPosition = camera.getPosition();
			this.handRotation.set(camera.getRotation());
			this.handThrow.sample(camera.getPosition());
			this.stabilizer.update(camera.getPosition().subtract(vr.origin), camera.getRotation(), dt,
					this.config.handStabilize);
			camera.setPosition(this.stabilizer.position().add(vr.origin));
			camera.setRotation(new Quaternionf(this.stabilizer.rotation()));
			this.putUpCheck -= worldDt;
			if (this.mode == Mode.PHYSICS && this.putUpCheck <= 0) {
				this.putUpCheck = PUT_UP_CHECK_TIME;
				boolean canPutUp = this.dropped.canPutUp(this.subject,
						WorldProbe.reach(player, this.subject.head, this.handPosition), this.handPosition);
				if (canPutUp && !this.couldPutUp) {
					VRClientAPI.instance().triggerHapticPulse(camera.getMovingController() == 0 ?
							VRBodyPart.MAIN_HAND : VRBodyPart.OFF_HAND, 0.06F, 180.0F, 0.7F, 0.0F);
				}
				this.couldPutUp = canPutUp;
			}
			if (this.mode == Mode.PHYSICS && this.config.physicsShake > 0) {
				camera.getRotation().mul(this.shake.update(worldDt, this.subject.speed, player.hurtTime > 0,
						this.config.physicsShake));
			}
			return;
		}
		this.couldPutUp = false;
		if (this.wasGrabbed && this.offeredHand >= 0) {
			camera.startMoving(this.offeredHand);
			return;
		}
		if (this.wasGrabbed) {
			this.wasGrabbed = false;
			if (this.mode == Mode.PHYSICS) {
				Vec3 start = WorldProbe.reach(player, this.subject.head, camera.getPosition());
				Vec3 handVelocity = this.handThrow.velocity(this.subject.velocity);
				if (!this.dropped.place(this.subject, start, camera.getPosition(), camera.getRotation(),
						handVelocity)) {
					this.dropped.drop(start, camera.getRotation(), handVelocity.scale(this.config.throwPower));
				} else if (this.dropped.isMounted()) {
					CameraEffects.pinned(player.level(), this.dropped.position());
				}
				this.limbs.reset();
				this.limbs.released(camera.getMovingController());
			} else {
				placedByHand(camera.getPosition());
			}
		}
		if (this.parkedTime > 0) {
			this.parkedTime -= worldDt;
			return;
		}
		if (this.mode == Mode.PHYSICS) {
			letFall(camera, vr, worldDt);
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
		dh.vrSettings.handCameraFov = (float) CamMath.clamp(this.rig.fov() - shutterFov(), 1.0, 179.0);
	}

	/**
	 * the physics mode while the camera is not in the hand: it falls and stays where it lands
	 */
	private void letFall(CameraTracker camera, VRData vr, double dt) {
		if (this.changedDimension && !this.dropped.isOnPlayer()) {
			this.dropped.pickUp();
			summon();
			return;
		}
		if (camera.getPosition().distanceTo(this.subject.head) > PHYSICS_LEASH) {
			this.dropped.pickUp();
			summon();
			return;
		}
		if (!this.dropped.isDropped()) {
			this.dropped.drop(camera.getPosition(), camera.getRotation(), Vec3.ZERO);
			this.limbs.reset();
		}
		if (this.subject.teleported) {
			this.limbs.reset();
		}
		this.limbs.update(vr, this.dropped, dt, this.config.kickPower);
		this.dropped.update(this.subject, dt, this.config, restFocus());
		camera.setPosition(this.dropped.position());
		if (this.dropped.isAttached() && this.config.physicsShake > 0) {
			camera.setRotation(new Quaternionf(this.dropped.rotation()).mul(this.shake.update(dt,
					this.dropped.attachedSpeed(), false, this.config.physicsShake)));
		} else {
			camera.setRotation(this.dropped.rotation());
		}

		Vec3 lens = new Vec3(this.dropped.rotation().transform(new Vector3f(0, 0, -1)));
		Entity host = this.dropped.pollHost();
		if (host != null) {
			CameraEffects.putOn(this.subject.player.level(), host, this.dropped.position());
		}
		DroppedCamera.Impact impact = this.dropped.pollImpact();
		if (impact != null) {
			CameraEffects.impact(this.subject.player.level(), impact, lens);
		}
		boolean inWater = this.config.underwaterLook &&
				CameraEffects.inWater(this.subject.player.level(), this.dropped.position());
		if (inWater && !this.wasInWater) {
			CameraEffects.splash(this.subject.player.level(), this.dropped.position(), lens);
		} else if (inWater && !this.dropped.isResting() && Math.random() < dt * BUBBLES_PER_SECOND) {
			CameraEffects.bubble(this.subject.player.level(), this.dropped.position(), lens);
		}
		this.wasInWater = inWater;
	}

	/**
	 * @return what a camera on the ground turns its lens to
	 */
	private Vec3 restFocus() {
		if (this.killer == null || !this.killer.isAlive()) {
			return this.subject.center;
		}
		Vec3 killerCenter = this.killer.getPosition(this.subject.partialTick)
				.add(0, this.killer.getBbHeight() * 0.5, 0);
		return this.subject.center.lerp(killerCenter, 0.5);
	}

	/**
	 * a dead hand holds nothing: the camera drops, and films what is left and who did it
	 */
	private void watchDeath(LocalPlayer player, CameraTracker camera) {
		boolean dead = player.isDeadOrDying();
		if (dead && !this.wasDead) {
			DamageSource source = player.getLastDamageSource();
			Entity attacker = source == null ? null : source.getEntity();
			this.killer = attacker == player ? null : attacker;
			this.dropped.settleAgain();
		} else if (!dead) {
			this.killer = null;
		}
		this.wasDead = dead;
		if (dead) {
			this.pullHand = null;
			if (camera.isMoving()) {
				camera.stopMoving();
			}
		}
	}

	/**
	 * a pulled camera on its way to the hand that pulled it
	 */
	private void flyToHand(CameraTracker camera, VRData vr, LocalPlayer player, double dt) {
		boolean rightHand = (this.pullHand == InteractionHand.MAIN_HAND) !=
				ClientDataHolderVR.getInstance().vrSettings.reverseHands;
		Vec3 toRight = new Vec3(-this.subject.headDir.z, 0, this.subject.headDir.x);
		toRight = toRight.lengthSqr() < 1.0E-6 ? Vec3.ZERO : toRight.normalize();
		Vec3 grip = toRight.scale((rightHand ? -1 : 1) * PULL_GRIP_OFFSET * vr.worldScale);
		Vec3 hand = vr.getController(this.pullHand.ordinal()).getPosition().add(grip);
		Vec3 position;
		boolean arrived;
		if (this.pullDrawn) {
			this.pullElapsed += dt;
			double progress = Math.min(1.0, this.pullElapsed / Math.max(0.05, this.config.pullSeconds));
			if (progress < PULL_WINDUP) {
				camera.setPosition(this.pullStart.add(0, Math.sin(this.pullElapsed * 45.0) * 0.012, 0));
				return;
			}
			if (!this.pullLifted) {
				this.pullLifted = true;
				this.dropped.pickUp();
			}
			this.handThrow.sample(hand);
			double way = Math.pow((progress - PULL_WINDUP) / (1.0 - PULL_WINDUP), 1.8);
			position = this.pullStart.lerp(hand, way);
			if (this.pullArc) {
				double height = CamMath.clamp(this.pullStart.distanceTo(hand) * 0.25, 0.4, 2.5);
				position = position.add(0, Math.sin(Math.PI * way) * height, 0);
			}
			if (dt > 1.0E-4) {
				this.pullVelocity = position.subtract(this.pullLast).scale(1.0 / dt);
			}
			this.pullLast = position;
			arrived = progress >= 1.0;
		} else {
			position = this.pullGlide.update(hand, PULL_TIME, dt);
			arrived = position.distanceTo(hand) <= PULL_ARRIVED * vr.worldScale;
		}
		Quaternionf atPlayer = new Quaternionf();
		if (CamMath.lookRotation(this.subject.head.subtract(position), atPlayer)) {
			camera.getRotation().slerp(atPlayer, (float) (1.0 - Math.exp(-dt / 0.1)));
		}
		if (!arrived) {
			camera.setPosition(position);
			if (Math.random() < dt * PULL_SPARKS) {
				CameraEffects.pullTrail(player.level(), position);
			}
			return;
		}
		Vec3 tickHand = ClientDataHolderVR.getInstance().vrPlayer.vrdata_world_pre
				.getController(this.pullHand.ordinal()).getPosition();
		camera.setPosition(tickHand.add(grip));
		camera.startMoving(this.pullHand.ordinal());
		this.pullHand = null;
		this.handThrow.clear();
		CameraEffects.pullArrive(player, hand);
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
		preset.distance = Math.max(0.3, offset.length() / this.subject.unit);
		this.config.save();

		Shot shot = customShot();
		this.rig.adopt(cameraPos, shot, this.subject);
		if (wasThrown) {
			this.rig.blend();
		}
		if (this.mode == Mode.FOLLOW) {
			this.followShot = shot;
		} else {
			this.director.showManual(shot);
		}
	}

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
		FOLLOW,
		/**
		 * the camera is carried in the hand, and falls to the ground when let go of
		 */
		PHYSICS;

		public Component label() {
			return Component.translatable("vrcamera.mode." + name().toLowerCase(Locale.ROOT));
		}
	}
}
