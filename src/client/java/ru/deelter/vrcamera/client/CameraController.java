package ru.deelter.vrcamera.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
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
	private Director director = new Director(config);
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
		final ClientDataHolderVR dataHolder = ClientDataHolderVR.getInstance();
		return VRState.VR_RUNNING && dataHolder.vrPlayer != null && dataHolder.vrPlayer.vrdata_world_render != null;
	}

	public Mode mode() {
		return mode;
	}

	public Director director() {
		return director;
	}

	public CameraConfig config() {
		return config;
	}

	/**
	 * @return if the Vivecraft camera model should not be shown in the headset
	 */
	public boolean hidesModel() {
		if (!engaged || mode == Mode.PHYSICS) {
			return false;
		}
		final Shot shot = shot();

		return config.marker != Marker.MODEL || (shot != null && shot.type == ShotType.POV);
	}

	/**
	 * @return where the hand has the camera, before steadying. Null while it is not held
	 */
	public Vec3 handPosition() {
		return handPosition;
	}

	public Quaternionf handRotation() {
		return handRotation;
	}

	/**
	 * @return the arm that holds the camera, if it should not be in the picture. Null to leave both arms alone
	 */
	@Nullable
	public HumanoidArm armToHide() {
		final ClientDataHolderVR dataHolder = ClientDataHolderVR.getInstance();
		if (!engaged || !config.hideHoldingArm || handPosition == null ||
				dataHolder.currentPass != RenderPass.CAMERA || !dataHolder.cameraTracker.isMoving()) {
			return null;
		}
		final CameraTracker camera = dataHolder.cameraTracker;

		if (looksAtPlayer(camera.getPosition(), camera.getRotation())) {
			return null;
		}
		final boolean rightHand = (camera.getMovingController() == 0) != dataHolder.vrSettings.reverseHands;
		return rightHand ? HumanoidArm.RIGHT : HumanoidArm.LEFT;
	}

	/**
	 * @return if the head of the player should not be in the picture: the camera sits on it and films from inside
	 */
	public boolean hidesOwnHead() {
		return engaged && mode == Mode.PHYSICS && dropped.isOnPlayer() &&
				ClientDataHolderVR.getInstance().currentPass == RenderPass.CAMERA;
	}

	/**
	 * @return if the camera is near the player with its lens to them, who then can't see the screen on its back.
	 * In the hand, or anywhere it was put
	 */
	public boolean showsSelfieScreen() {
		if (!engaged || !config.selfieScreen || hidesModel()) {
			return false;
		}
		final CameraTracker camera = ClientDataHolderVR.getInstance().cameraTracker;
		final boolean held = camera.isMoving() && handPosition != null;
		final Vec3 position = held ? handPosition : camera.getPosition();
		return position.distanceTo(subject.head) <= config.selfieDistance &&
				looksAtPlayer(position, held ? handRotation : camera.getRotation());
	}

	/**
	 * Draws what helps the player find the camera, called while Vivecraft collects what to render for one of the eyes.
	 */
	public void drawHeadsetAids(VRData vrData) {
		try {
			if (config.indicator) {
				final Vec3 head = vrData.hmd.getPosition();
				PhotoAlbum.INSTANCE.forEachLoose(sheet -> {
					final double distance = sheet.distanceTo(head);
					if (distance > PHOTO_ICON_MIN_DISTANCE && distance < PHOTO_ICON_MAX_DISTANCE) {
						drawIndicator(PHOTO_ICON, sheet, vrData, false);
					}
				});
			}
			if (!engaged || !ClientDataHolderVR.getInstance().cameraTracker.isVisible()) {
				return;
			}
			final Vec3 camera = handPosition != null ? handPosition : vrData.getEye(RenderPass.CAMERA).getPosition();
			if (config.marker == Marker.DOT && mode != Mode.PHYSICS) {
				drawMarker(camera, vrData.worldScale);
			}
			final Shot shot = shot();

			if (config.indicator && (shot == null || shot.type != ShotType.POV)) {
				drawIndicator(INDICATOR_ICON, camera, vrData, true);
			}
		} catch (IllegalStateException e) {
		}
	}

	/**
	 * @return the shot the camera is showing, null while there is none
	 */
	public Shot shot() {
		return mode == Mode.FOLLOW ? followShot : director.current();
	}

	public Rig rig() {
		return rig;
	}

	public Subject subject() {
		return subject;
	}

	/**
	 * @return if the camera is being driven, and not just turned on and waiting for VR
	 */
	public boolean isEngaged() {
		return engaged;
	}

	/**
	 * @return seconds a summoned camera still waits to be picked up
	 */
	public double parkedTime() {
		return parkedTime;
	}

	public void toggleDebug() {
		config.debugOverlay = !config.debugOverlay;
		config.save();
	}

	public boolean debugEnabled() {
		return config.debugOverlay;
	}

	/**
	 * @return text for the preset button, like "2/3"
	 */
	public String presetLabel() {
		return (config.activePreset + 1) + "/" + config.presets.size();
	}

	/**
	 * switches to the next hand placed shot
	 */
	public void nextPreset() {
		if (mode == Mode.OFF) {
			return;
		}
		config.activePreset = (config.activePreset + 1) % config.presets.size();
		config.save();
		showPreset();
	}

	/**
	 * @param index number of the hand placed shot, starting at 0
	 * @return false if there is no such shot, or the camera is off
	 */
	public boolean selectPreset(int index) {
		if (mode == Mode.OFF || index < 0 || index >= config.presets.size()) {
			return false;
		}
		config.activePreset = index;
		config.save();
		showPreset();
		return true;
	}

	/**
	 * adds a hand placed shot, as a copy of the active one, to then be placed somewhere else
	 */
	public void newPreset() {
		if (mode == Mode.OFF) {
			return;
		}
		config.presets.add(config.preset().copy());
		config.activePreset = config.presets.size() - 1;
		config.save();
		showPreset();
	}

	public void deletePreset() {
		if (mode == Mode.OFF || config.presets.size() <= 1) {
			return;
		}
		config.presets.remove(config.activePreset);
		config.activePreset = Math.min(config.activePreset, config.presets.size() - 1);
		config.save();
		showPreset();
	}

	public void cycleMode() {
		if (mode != Mode.OFF && !isVRRunning()) {
			setMode(Mode.OFF);
			return;
		}
		setMode(Mode.values()[(mode.ordinal() + 1) % Mode.values().length]);
	}

	/**
	 * Called every client tick, also when VR is not running. Vivecraft can switch VR off at any time, when the
	 * headset is taken off or VR gets disabled, and then stops calling the tracker without notice.
	 */
	public void tick() {
		if (engaged && (!isVRRunning() || Minecraft.getInstance().player == null)) {
			release();
			subject.reset();
			rig.reset();
			wasGrabbed = false;
			parkedTime = 0;
			pullHand = null;
			handPosition = null;
		}
		tickShutterButton();

		if (engaged && config.shareCamera && ++shareTicks % 2 == 0 && isVRRunning()) {
			final CameraTracker camera = ClientDataHolderVR.getInstance().cameraTracker;
			if (camera.isVisible()) {
				final boolean held = handPosition != null;
				PhotoSync.INSTANCE.shareCamera(held ? handPosition : camera.getPosition(),
						held ? handRotation : camera.getRotation());
			}
		}
	}

	/**
	 * Puts the camera in front of the face of the player and leaves it there, to be grabbed and placed by hand.
	 * A camera that follows the player can not be reached otherwise, it backs away when walking up to it.
	 */
	public void summon() {
		if (mode == Mode.OFF || !isVRRunning()) {
			return;
		}
		final ClientDataHolderVR dataHolder = ClientDataHolderVR.getInstance();
		final VRData vrData = dataHolder.vrPlayer.vrdata_world_render;
		final Vec3 head = vrData.hmd.getPosition();
		final Vector3f look = vrData.hmd.getDirection();
		Vec3 forward = new Vec3(look.x, 0, look.z);
		forward = forward.length() < 1.0E-3 ? CamMath.forward(vrData.hmd.getYawRad()) : forward.normalize();

		final Vec3 pos = head.add(forward.scale(0.45 * vrData.worldScale)).add(0, -0.15 * vrData.worldScale, 0);
		final Quaternionf rotation = new Quaternionf();
		CamMath.lookRotation(head.subtract(pos), rotation);
		dataHolder.cameraTracker.setPosition(pos);
		dataHolder.cameraTracker.setRotation(rotation);
		dropped.pickUp();
		pullHand = null;

		parkedTime = PARK_SECONDS;
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
				config = CameraConfig.reload();
				director = new Director(config);
			}
		}
		this.mode = mode;
		followShot = null;
		director.reset();
		rig.reset();
		subject.reset();
		wasGrabbed = false;
		parkedTime = 0;
		handPosition = null;
		handThrow.clear();
		glideTarget = null;
		plainHeld = false;
		dropped.pickUp();
		pullHand = null;
		wasDead = false;
		killer = null;
		underwater.reset(0);
		wasInWater = false;
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
		if (pullHand != null) {
			return "pulled";
		}
		if (parkedTime > 0) {
			return "waiting";
		}
		if (!dropped.isDropped()) {
			return "held";
		}
		if (dropped.isCarried()) {
			return "carried";
		}
		return dropped.isResting() ? "lying" : "falling";
	}

	/**
	 * @return if the camera is somewhere on its own, to be pulled into a hand from afar
	 */
	public boolean canPull() {
		if (!engaged || pullHand != null) {
			return false;
		}
		if (mode == Mode.PHYSICS) {
			return dropped.isDropped();
		}

		return config.pullAllModes && rig.ready() &&
				!ClientDataHolderVR.getInstance().cameraTracker.isMoving();
	}

	/**
	 * the camera flies into the hand, and stays in it until {@link #endPull}
	 */
	public void startPull(InteractionHand hand) {
		final LocalPlayer player = Minecraft.getInstance().player;
		if (!canPull() || player == null) {
			return;
		}
		pullHand = hand;
		parkedTime = 0;
		pullDrawn = config.pullStyle == PullStyle.TELEKINESIS;
		pullStart = ClientDataHolderVR.getInstance().cameraTracker.getPosition();
		pullLast = pullStart;
		pullVelocity = Vec3.ZERO;
		pullElapsed = 0;
		pullLifted = false;

		pullArc = mode == Mode.PHYSICS && dropped.isResting() && !dropped.isMounted() &&
				!dropped.isAttached();
		pullGlide.reset(pullStart);
		handThrow.clear();
		if (!pullDrawn) {
			dropped.pickUp();
			pullLifted = true;
		}
		CameraEffects.pullStart(player);
	}

	/**
	 * @return how far the camera is on its way to that hand, from 0 to 1. -1 if it is not being drawn to it
	 */
	public double pullProgress(InteractionHand hand) {
		if (pullHand != hand || !pullDrawn) {
			return -1;
		}
		return Math.min(1.0, pullElapsed / Math.max(0.05, config.pullSeconds));
	}

	/**
	 * the hand that pulled the camera let go of it
	 */
	public void endPull(InteractionHand hand) {
		final CameraTracker camera = ClientDataHolderVR.getInstance().cameraTracker;
		if (pullHand == hand) {
			pullHand = null;
			if (!pullLifted) {
				return;
			}
			if (mode == Mode.PHYSICS) {
				Vec3 speed = pullDrawn ? pullVelocity : Vec3.ZERO;

				final Vec3 swing = pullDrawn ? handThrow.velocity(subject.velocity) : Vec3.ZERO;
				if (swing.length() > PULL_FLING_SPEED) {
					speed = swing.scale(PULL_FLING_GAIN * config.throwPower).add(speed.scale(0.25));
				}
				if (speed.length() > PULL_MAX_RELEASE_SPEED) {
					speed = speed.normalize().scale(PULL_MAX_RELEASE_SPEED);
				}
				dropped.drop(camera.getPosition(), camera.getRotation(), speed);
				limbs.reset();
			} else if (pullDrawn) {
				placedByHand(camera.getPosition());
			} else {
				final Shot shot = shot();
				if (shot != null && rig.ready() && subject.player != null) {
					rig.adopt(camera.getPosition(), shot, subject);
					rig.blend();
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
		final LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && !isVRRunning()) {
			if (!PhotoAlbum.INSTANCE.takeWithoutCamera(player, config.photoSheet)) {
				return false;
			}
			CameraEffects.ownShutter(player);
			return true;
		}
		if (!engaged || player == null) {
			notify(Component.translatable("vrcamera.message.photo.off"));
			return false;
		}

		if (PhotoAlbum.INSTANCE.isPrinting() || !PhotoAlbum.INSTANCE.take(config.photoSheet)) {
			return false;
		}
		shutter = SHUTTER_TIME;
		CameraEffects.ownShutter(player);
		return true;
	}

	public void offerHand(int hand) {
		offeredHand = hand;
	}

	public void withdrawHand(int hand) {
		if (offeredHand == hand) {
			offeredHand = -1;
		}
	}

	public void nextShot() {
		if (mode == Mode.DIRECTOR) {
			director.next();
			notify(Component.translatable("vrcamera.message.next"));
		}
	}

	/**
	 * Shows a shot of the given type, turns the director on for it if needed.
	 *
	 * @return false if the camera can't be turned on
	 */
	public boolean showShot(ShotType type) {
		if (mode != Mode.DIRECTOR) {
			setMode(Mode.DIRECTOR);
			if (mode != Mode.DIRECTOR) {
				return false;
			}
		}
		director.force(type);
		notify(Component.translatable("vrcamera.message.shot", type.name().toLowerCase(Locale.ROOT)));
		return true;
	}

	/**
	 * reads the config file again, without turning the camera off
	 */
	public void reloadConfig() {
		config = CameraConfig.reload();
		director = new Director(config);
		followShot = null;
		rig.reset();
	}

	/**
	 * the player hit something, that is what a fight is filmed against
	 */
	public void onAttack(Entity entity) {
		if (mode == Mode.DIRECTOR) {
			director.onAttack(entity);
		}
	}

	public void toggleHold() {
		if (mode == Mode.DIRECTOR) {
			notify(Component.translatable(
					director.toggleHold() ? "vrcamera.message.hold" : "vrcamera.message.release"));
		}
	}

	/**
	 * puts the Vivecraft camera settings back to what they were
	 */
	public void release() {
		if (!engaged) {
			return;
		}
		final ClientDataHolderVR dataHolder = ClientDataHolderVR.getInstance();
		dataHolder.vrSettings.displayMirrorUseScreenshotCamera = previousMirror;
		dataHolder.vrSettings.handCameraFov = previousFov;
		if (shownByUs && dataHolder.cameraTracker.isVisible()) {
			dataHolder.cameraTracker.toggleVisibility();
		}
		shownByUs = false;
		engaged = false;
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
		if (mode == Mode.OFF) {
			throwPlainCamera(player);
		}

		final long now = System.nanoTime();
		final double dt = Minecraft.getInstance().isPaused() ? 0 : Math.min((now - albumNanos) / 1.0E9, 0.1);
		albumNanos = now;
		try {
			final VRData worldData = ClientDataHolderVR.getInstance().vrPlayer.vrdata_world_render;
			PhotoAlbum.INSTANCE.update(player.level(), Vive.hands(worldData), dt);
		} catch (RuntimeException e) {
			Vrcamera.LOGGER.error("VRCamera: updating photo sheets failed", e);
			PhotoAlbum.INSTANCE.clear();
		}
	}

	@Override
	public boolean isActive(LocalPlayer player) {
		return mode != Mode.OFF && player != null && Minecraft.getInstance().gameMode != null && isVRRunning();
	}

	@Override
	public void inactiveProcess(LocalPlayer player) {
		subject.reset();
		rig.reset();
		wasGrabbed = false;
		parkedTime = 0;
		pullHand = null;
		handPosition = null;
	}

	@Override
	public void activeProcess(LocalPlayer player) {
		try {
			frameDt = 0;
			process(player);
			if (engaged) {
				final CameraTracker camera = ClientDataHolderVR.getInstance().cameraTracker;
				final boolean held = handPosition != null;

				PhotoAlbum.INSTANCE.hangFrom(held ? handPosition : camera.getPosition(),
						held ? handRotation : camera.getRotation(),
						ClientDataHolderVR.getInstance().vrPlayer.vrdata_world_render.worldScale);
			}
		} catch (RuntimeException e) {
			Vrcamera.LOGGER.error("VRCamera: camera update failed, turning the camera off", e);
			setMode(Mode.OFF);
			notify(Component.translatable("vrcamera.message.error"));
		}
	}

	private boolean looksAtPlayer(Vec3 position, Quaternionf rotation) {
		final Vec3 lens = new Vec3(rotation.transform(new Vector3f(0, 0, -1)));
		final Vec3 toHead = subject.head.subtract(position);
		return toHead.lengthSqr() > 1.0E-6 && lens.dot(toHead.normalize()) > SELFIE_COS;
	}

	private void drawMarker(Vec3 camera, float worldScale) {
		Gizmos.point(camera, MARKER_COLOR, (float) config.markerSize);
		if (config.markerLabel) {
			Gizmos.billboardText(markerText(), camera.add(0, 0.07 * worldScale, 0),
					TextGizmo.Style.forColorAndCentered(MARKER_COLOR).withScale(0.1F * worldScale));
		}
	}

	/**
	 * The camera icon with the distance to the camera below it, like a waypoint: at the camera and seen through
	 * walls. While the camera is out of sight the icon sticks to the edge of the view on the side the camera is on.
	 */
	private void drawIndicator(String icon, Vec3 camera, VRData vrData, boolean alsoOutOfSight) {
		CameraIndicator.draw(icon, "", camera, vrData.hmd.getPosition(), new Vec3(vrData.hmd.getDirection()),
				new Vec3(vrData.hmd.getCustomVector(MathUtils.UP)), vrData.worldScale, alsoOutOfSight, 1.0,
				UnaryOperator.identity());
	}

	private String markerText() {
		if (mode == Mode.FOLLOW) {
			return "REC follow";
		}
		final Shot shot = director.current();
		final String text = shot == null ? "REC" : "REC " + shot.type.name().toLowerCase(Locale.ROOT);
		return director.isHolding() ? text + " (hold)" : text;
	}

	private void showPreset() {
		notify(Component.translatable("vrcamera.message.preset", presetLabel()));
		if (!rig.ready() || subject.player == null) {
			return;
		}
		final Shot shot = customShot();
		final Shot previous = shot();
		if (previous != null && previous.blends()) {
			rig.blend();
		} else {
			rig.snap(shot, subject);
		}
		if (mode == Mode.FOLLOW) {
			followShot = shot;
		} else {
			director.showManual(shot);
		}
	}

	/**
	 * the photo gesture of the hand that holds the camera: its other button, held for a moment
	 */
	private void tickShutterButton() {
		final CameraTracker camera = ClientDataHolderVR.getInstance().cameraTracker;
		if (!engaged || config.photoGesture != PhotoGesture.SAME_HAND || !isVRRunning() ||
				!camera.isMoving() || camera.isQuickMode()) {
			secondButton.reset();
			shutterTicks = 0;
			shutterTaken = false;
			return;
		}
		final int hand = camera.getMovingController();
		if (!secondButton.isDown(hand)) {
			shutterTicks = 0;
			shutterTaken = false;
			return;
		}
		if (shutterTaken) {
			return;
		}
		final double seconds = config.photoHoldSeconds;
		final double progress = seconds <= 0 ? 1.0 : Math.min(1.0, ++shutterTicks / (seconds * 20.0));

		VRClientAPI.instance().triggerHapticPulse(hand == 0 ? VRBodyPart.MAIN_HAND : VRBodyPart.OFF_HAND, 0.05F,
				(float) CamMath.lerp(120.0, 320.0, progress), (float) CamMath.lerp(0.15, 1.0, progress), 0.0F);

		if (progress >= 1.0 && takePhoto()) {
			shutterTaken = true;
		}
	}

	private double shutterFov() {
		return SHUTTER_FOV * shutter / SHUTTER_TIME;
	}

	private void notify(Component message) {
		if (Minecraft.getInstance().player != null) {
			Minecraft.getInstance().player.sendOverlayMessage(message);
		}
	}

	/**
	 * takes over the Vivecraft camera settings
	 */
	private void engage(ClientDataHolderVR dataHolder) {
		previousMirror = dataHolder.vrSettings.displayMirrorUseScreenshotCamera;
		previousFov = dataHolder.vrSettings.handCameraFov;
		if (config.forceMirror) {
			dataHolder.vrSettings.displayMirrorUseScreenshotCamera = true;
		}
		engaged = true;
	}

	/**
	 * Lets the camera of Vivecraft be thrown as well, while this mod does nothing else with it. It flies to where
	 * it was thrown and stays there.
	 */
	private void throwPlainCamera(LocalPlayer player) {
		final CameraTracker camera = ClientDataHolderVR.getInstance().cameraTracker;
		final long now = System.nanoTime();
		final double dt = Math.min((now - glideNanos) / 1.0E9, 0.1);
		glideNanos = now;

		if (!camera.isVisible() || camera.isQuickMode()) {
			plainHeld = false;
			glideTarget = null;
			handThrow.clear();
			return;
		}
		if (camera.isMoving()) {
			plainHeld = true;
			glideTarget = null;
			handThrow.sample(camera.getPosition());
			return;
		}
		if (plainHeld) {
			plainHeld = false;
			final Vec3 motion = player.getDeltaMovement();

			final Vec3 thrown = handThrow.release(new Vec3(motion.x * 20.0, 0, motion.z * 20.0),
					config.throwPower);
			if (thrown.lengthSqr() > 0) {
				final Vec3 from = camera.getPosition();
				glideTarget = WorldProbe.reach(player, from, from.add(thrown));
				glide.reset(from);
			}
		}
		if (glideTarget != null) {
			final Vec3 pos = glide.update(glideTarget, GLIDE_TIME, dt);
			camera.setPosition(pos);
			if (pos.distanceTo(glideTarget) < 0.02) {
				glideTarget = null;
			}
		}
	}

	/**
	 * @return where in the world the menu is that the player has open, null if there is none
	 */
	@Nullable
	private Vec3 openMenuPosition(VRData vrData) {
		final Screen screen = Minecraft.getInstance().gui.screen();

		if (GuiHandler.GUI_POS_ROOM == null || screen == null ||
				(screen instanceof ChatScreen && !config.menuShotChat)) {
			return null;
		}
		return VRPlayer.roomToWorldPos(GuiHandler.GUI_POS_ROOM, vrData);
	}

	private void process(LocalPlayer player) {
		final ClientDataHolderVR dataHolder = ClientDataHolderVR.getInstance();
		final CameraTracker camera = dataHolder.cameraTracker;
		handPosition = null;
		if (dataHolder.vrSettings.seated) {
			setMode(Mode.OFF);
			return;
		}
		if (camera.isQuickMode()) {
			return;
		}
		if (!engaged) {
			engage(dataHolder);
		}
		if (!camera.isVisible()) {
			camera.toggleVisibility();
			shownByUs = true;
		}

		final long now = System.nanoTime();
		final double realDt = Math.max(0.0, (now - lastNanos) / 1.0E9);
		final double dt = Math.min(realDt, 0.1);
		lastNanos = now;

		final double worldDt = Minecraft.getInstance().isPaused() ? 0 : dt;
		frameDt = worldDt;
		shutter = Math.max(0.0, shutter - dt);
		if (realDt > RESUME_GAP) {
			subject.reset();
			rig.reset();
		}

		final VRData vrData = dataHolder.vrPlayer.vrdata_world_render;
		final float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
		VrSubject.update(subject, player, vrData, partialTick, dt, realDt, config);
		subject.guiCenter = openMenuPosition(vrData);
		dropped.allowSelf(config.attachToSelf);
		final ResourceKey<Level> dimension = player.level().dimension();
		changedDimension = this.dimension != null && !this.dimension.equals(dimension);
		this.dimension = dimension;

		if (mode == Mode.PHYSICS) {
			watchDeath(player, camera);

			final boolean submerged = config.underwaterLook && WorldProbe.inFluid(subject, camera.getPosition());
			final double wet = underwater.update(submerged ? 1.0 : 0.0, UNDERWATER_TIME, dt);
			dataHolder.vrSettings.handCameraFov = (float) CamMath.clamp(
					previousFov + dropped.fovOffset() + wet * UNDERWATER_FOV - shutterFov(), 1.0, 179.0);
		}
		if (pullHand != null) {
			flyToHand(camera, vrData, player, dt);
			return;
		}
		if (camera.isMoving()) {
			if (!wasGrabbed) {
				stabilizer.reset();
			}
			wasGrabbed = true;
			parkedTime = 0;
			dropped.pickUp();

			handPosition = camera.getPosition();
			handRotation.set(camera.getRotation());

			handThrow.sample(camera.getPosition());

			stabilizer.update(camera.getPosition().subtract(vrData.origin), camera.getRotation(), dt,
					config.handStabilize);
			camera.setPosition(stabilizer.position().add(vrData.origin));
			camera.setRotation(new Quaternionf(stabilizer.rotation()));
			putUpCheck -= worldDt;
			if (mode == Mode.PHYSICS && putUpCheck <= 0) {
				putUpCheck = PUT_UP_CHECK_TIME;

				final boolean canPutUp = dropped.canPutUp(subject,
						WorldProbe.reach(player, subject.head, handPosition), handPosition);
				if (canPutUp && !couldPutUp) {
					VRClientAPI.instance().triggerHapticPulse(camera.getMovingController() == 0 ?
							VRBodyPart.MAIN_HAND : VRBodyPart.OFF_HAND, 0.06F, 180.0F, 0.7F, 0.0F);
				}
				couldPutUp = canPutUp;
			}
			if (mode == Mode.PHYSICS && config.physicsShake > 0) {
				camera.getRotation().mul(shake.update(worldDt, subject.speed, player.hurtTime > 0,
						config.physicsShake));
			}
			return;
		}
		couldPutUp = false;
		if (wasGrabbed && offeredHand >= 0) {
			camera.startMoving(offeredHand);
			return;
		}
		if (wasGrabbed) {
			wasGrabbed = false;
			if (mode == Mode.PHYSICS) {
				final Vec3 start = WorldProbe.reach(player, subject.head, camera.getPosition());
				final Vec3 handVelocity = handThrow.velocity(subject.velocity);
				if (!dropped.place(subject, start, camera.getPosition(), camera.getRotation(),
						handVelocity)) {
					dropped.drop(start, camera.getRotation(), handVelocity.scale(config.throwPower));
				} else if (dropped.isMounted()) {
					CameraEffects.pinned(player.level(), dropped.position());
				}
				limbs.reset();
				limbs.released(camera.getMovingController());
			} else {
				placedByHand(camera.getPosition());
			}
		}
		if (parkedTime > 0) {
			parkedTime -= worldDt;
			return;
		}
		if (mode == Mode.PHYSICS) {
			letFall(camera, vrData, worldDt);
			return;
		}

		Shot shot;
		if (mode == Mode.FOLLOW) {
			if (followShot == null || !rig.ready()) {
				followShot = customShot();
				rig.snap(followShot, subject);
			} else if (subject.teleported) {
				rig.rebase(subject);
			}
			followShot.update(subject, config, dt);
			shot = followShot;
		} else {
			director.update(subject, rig, dt);
			shot = director.current();
		}

		rig.update(shot, subject, dt, config);

		camera.setPosition(rig.position());
		camera.setRotation(rig.rotation());
		dataHolder.vrSettings.handCameraFov = (float) CamMath.clamp(rig.fov() - shutterFov(), 1.0, 179.0);
	}

	/**
	 * the physics mode while the camera is not in the hand: it falls and stays where it lands
	 */
	private void letFall(CameraTracker camera, VRData vrData, double dt) {
		if (changedDimension && !dropped.isOnPlayer()) {
			dropped.pickUp();
			summon();
			return;
		}

		if (camera.getPosition().distanceTo(subject.head) > PHYSICS_LEASH) {
			dropped.pickUp();
			summon();
			return;
		}
		if (!dropped.isDropped()) {
			dropped.drop(camera.getPosition(), camera.getRotation(), Vec3.ZERO);
			limbs.reset();
		}
		if (subject.teleported) {
			limbs.reset();
		}
		limbs.update(vrData, dropped, dt, config.kickPower);
		dropped.update(subject, dt, config, restFocus());
		camera.setPosition(dropped.position());
		if (dropped.isAttached() && config.physicsShake > 0) {
			camera.setRotation(new Quaternionf(dropped.rotation()).mul(shake.update(dt,
					dropped.attachedSpeed(), false, config.physicsShake)));
		} else {
			camera.setRotation(dropped.rotation());
		}

		final Vec3 lens = new Vec3(dropped.rotation().transform(new Vector3f(0, 0, -1)));
		final Entity host = dropped.pollHost();
		if (host != null) {
			CameraEffects.putOn(subject.player.level(), host, dropped.position());
		}
		final DroppedCamera.Impact impact = dropped.pollImpact();
		if (impact != null) {
			CameraEffects.impact(subject.player.level(), impact, lens);
		}
		final boolean inWater = config.underwaterLook &&
				CameraEffects.inWater(subject.player.level(), dropped.position());
		if (inWater && !wasInWater) {
			CameraEffects.splash(subject.player.level(), dropped.position(), lens);
		} else if (inWater && !dropped.isResting() && Math.random() < dt * BUBBLES_PER_SECOND) {
			CameraEffects.bubble(subject.player.level(), dropped.position(), lens);
		}
		wasInWater = inWater;
	}

	/**
	 * @return what a camera on the ground turns its lens to
	 */
	private Vec3 restFocus() {
		if (killer == null || !killer.isAlive()) {
			return subject.center;
		}
		final Vec3 killerCenter = killer.getPosition(subject.partialTick)
				.add(0, killer.getBbHeight() * 0.5, 0);
		return subject.center.lerp(killerCenter, 0.5);
	}

	/**
	 * a dead hand holds nothing: the camera drops, and films what is left and who did it
	 */
	private void watchDeath(LocalPlayer player, CameraTracker camera) {
		final boolean dead = player.isDeadOrDying();
		if (dead && !wasDead) {
			final DamageSource source = player.getLastDamageSource();
			final Entity attacker = source == null ? null : source.getEntity();
			killer = attacker == player ? null : attacker;

			dropped.settleAgain();
		} else if (!dead) {
			killer = null;
		}
		wasDead = dead;
		if (dead) {
			pullHand = null;
			if (camera.isMoving()) {
				camera.stopMoving();
			}
		}
	}

	/**
	 * a pulled camera on its way to the hand that pulled it
	 */
	private void flyToHand(CameraTracker camera, VRData vrData, LocalPlayer player, double dt) {
		final boolean rightHand = (pullHand == InteractionHand.MAIN_HAND) !=
				ClientDataHolderVR.getInstance().vrSettings.reverseHands;
		Vec3 toRight = new Vec3(-subject.headDir.z, 0, subject.headDir.x);
		toRight = toRight.lengthSqr() < 1.0E-6 ? Vec3.ZERO : toRight.normalize();
		final Vec3 grip = toRight.scale((rightHand ? -1 : 1) * PULL_GRIP_OFFSET * vrData.worldScale);
		final Vec3 hand = vrData.getController(pullHand.ordinal()).getPosition().add(grip);
		Vec3 position;
		boolean arrived;
		if (pullDrawn) {
			pullElapsed += dt;
			final double progress = Math.min(1.0, pullElapsed / Math.max(0.05, config.pullSeconds));
			if (progress < PULL_WINDUP) {
				camera.setPosition(pullStart.add(0, Math.sin(pullElapsed * 45.0) * 0.012, 0));
				return;
			}
			if (!pullLifted) {
				pullLifted = true;
				dropped.pickUp();
			}
			handThrow.sample(hand);

			final double way = Math.pow((progress - PULL_WINDUP) / (1.0 - PULL_WINDUP), 1.8);
			position = pullStart.lerp(hand, way);
			if (pullArc) {
				final double height = CamMath.clamp(pullStart.distanceTo(hand) * 0.25, 0.4, 2.5);
				position = position.add(0, Math.sin(Math.PI * way) * height, 0);
			}
			if (dt > 1.0E-4) {
				pullVelocity = position.subtract(pullLast).scale(1.0 / dt);
			}
			pullLast = position;
			arrived = progress >= 1.0;
		} else {
			position = pullGlide.update(hand, PULL_TIME, dt);
			arrived = position.distanceTo(hand) <= PULL_ARRIVED * vrData.worldScale;
		}
		final Quaternionf atPlayer = new Quaternionf();
		if (CamMath.lookRotation(subject.head.subtract(position), atPlayer)) {
			camera.getRotation().slerp(atPlayer, (float) (1.0 - Math.exp(-dt / 0.1)));
		}
		if (!arrived) {
			camera.setPosition(position);
			if (Math.random() < dt * PULL_SPARKS) {
				CameraEffects.pullTrail(player.level(), position);
			}
			return;
		}

		final Vec3 tickHand = ClientDataHolderVR.getInstance().vrPlayer.vrdata_world_pre
				.getController(pullHand.ordinal()).getPosition();
		camera.setPosition(tickHand.add(grip));
		camera.startMoving(pullHand.ordinal());
		pullHand = null;
		handThrow.clear();
		CameraEffects.pullArrive(player, hand);
	}

	private Shot customShot() {
		final Shot shot = new Shot(ShotType.CUSTOM, config.preset(), 1);
		shot.start(subject, config);
		return shot;
	}

	/**
	 * the player let go of the camera, keep it where they put it, or threw it to, relative to them
	 */
	private void placedByHand(Vec3 cameraPos) {
		Vec3 landing = cameraPos;
		final Vec3 thrown = handThrow.release(subject.velocity, config.throwPower);
		final boolean wasThrown = thrown.lengthSqr() > 0;
		if (wasThrown) {
			final Vec3 target = cameraPos.add(thrown);
			landing = cameraPos.lerp(target, WorldProbe.armFraction(subject, cameraPos, target, config));
		}

		final Vec3 offset = landing.subtract(subject.center);
		final ShotConfig preset = config.preset();
		preset.azimuth = Math.toDegrees(CamMath.wrap(CamMath.azimuthOf(offset) - subject.facing));
		preset.elevation = Math.toDegrees(CamMath.elevationOf(offset));
		preset.distance = Math.max(0.3, offset.length() / subject.unit);
		config.save();

		final Shot shot = customShot();
		rig.adopt(cameraPos, shot, subject);
		if (wasThrown) {
			rig.blend();
		}
		if (mode == Mode.FOLLOW) {
			followShot = shot;
		} else {
			director.showManual(shot);
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
