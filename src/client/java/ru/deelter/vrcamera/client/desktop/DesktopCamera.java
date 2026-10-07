package ru.deelter.vrcamera.client.desktop;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.CameraController;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.director.Director;
import ru.deelter.vrcamera.client.rig.Rig;
import ru.deelter.vrcamera.client.rig.Subject;
import ru.deelter.vrcamera.client.shot.Shot;
import ru.deelter.vrcamera.client.shot.ShotType;
import ru.deelter.vrcamera.client.config.ScreenOutput;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import ru.deelter.vrcamera.client.sync.RemoteCameras;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import ru.deelter.vrcamera.client.config.ShotConfig;
import ru.deelter.vrcamera.client.math.CamMath;
import ru.deelter.vrcamera.client.rig.HandThrow;
import ru.deelter.vrcamera.client.rig.WorldProbe;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

/**
 * The director and the follow camera for a player without VR: the same shots, picked the same way, from what the
 * game knows of a player at a screen. Shown in the game window in place of the view of the player.
 * <p>
 * Apart from everything that has to do with VR. It only runs while VR does not, and is off until it is asked for.
 */
public final class DesktopCamera {
	public static final DesktopCamera INSTANCE = new DesktopCamera();

	public enum Mode {
		OFF, DIRECTOR, FOLLOW
	}

	/**
	 * where the camera is and how it looks, for one frame
	 */
	public record Pose(Vec3 position, Quaternionf rotation, float fov) {
	}

	// a gap between frames this long means the game stood still, not a slow frame
	private static final double MAX_FRAME_TIME = 0.25;
	private static final double MAX_GAP = 1.0;
	// steering: degrees per second around the player and up or down, and the part of the distance per second
	private static final double STEER_TURN = 70.0;
	private static final double STEER_RISE = 40.0;
	private static final double STEER_ZOOM = 1.1;
	// closer to the player than this the camera has no place around them to start from
	private static final double STEER_MIN_DISTANCE = 1.0;
	// Taking the camera with the mouse: from how far, how well it has to be pointed at in blocks plus blocks per
	// block of distance, how near and far it can be held, and the part of the distance one notch of the wheel is
	private static final double GRAB_REACH = 24.0;
	private static final double GRAB_AIM = 0.3;
	private static final double GRAB_AIM_PER_BLOCK = 0.03;
	private static final double GRAB_NEAR = 0.7;
	private static final double GRAB_FAR = 32.0;
	private static final double GRAB_WHEEL = 0.12;
	// how fast a held camera comes after the look and after the wheel, per second
	private static final double GRAB_EASE = 9.0;
	private static final double GRAB_WHEEL_EASE = 3.5;
	// how much larger the icon is while the camera can be taken, how much of that it beats by, and how fast
	private static final double GRAB_ICON = 1.35;
	private static final double GRAB_ICON_BEAT = 0.2;
	private static final double GRAB_ICON_RATE = 9.0;
	// further than this in one step the camera cut to another shot, it did not fly there
	private static final double MARKER_JUMP = 1.5;
	// the camera glyph of the mod, see assets/minecraft/font/default.json
	private static final String CAMERA_ICON = "\uE7C0";

	private final Subject subject = new Subject();
	private final Rig rig = new Rig();
	private Director director;
	private CameraConfig config;
	private Shot followShot;
	private Mode mode = Mode.OFF;
	private CameraType viewBefore;
	private long lastNanos;
	private Pose pose;
	// the shot the player steers themselves, null while the camera works on its own
	private Shot steered;
	private boolean steeredFromWindow;
	// held with the mouse: where it hangs, how far in front of the eyes, and what the hand does with it
	private boolean grabbed;
	private boolean aimed;
	private double grabDistance;
	private Vec3 grabbedAt = Vec3.ZERO;
	// where it is on the way there: how far, where from the eyes, and how it is turned
	private double grabHeld;
	private Vec3 grabOffset = Vec3.ZERO;
	private final Quaternionf grabRotation = new Quaternionf();
	private final HandThrow handThrow = new HandThrow();
	private long poseNanos;
	private double poseStep;
	private Vec3 poseSpeed = Vec3.ZERO;

	private DesktopCamera() {
	}

	public Mode mode() {
		return this.mode;
	}

	public boolean isOn() {
		return this.mode != Mode.OFF;
	}

	public void cycleMode() {
		setMode(Mode.values()[(this.mode.ordinal() + 1) % Mode.values().length]);
	}

	public void setMode(Mode mode) {
		Minecraft mc = Minecraft.getInstance();
		if (mode != Mode.OFF && CameraController.isVRRunning()) {
			// in VR the camera of Vivecraft does this
			return;
		}
		if (mode == this.mode) {
			return;
		}
		this.mode = mode;
		this.steered = null;
		this.steeredFromWindow = false;
		this.grabbed = false;
		this.aimed = false;
		keepView(mc);
		this.subject.reset();
		this.rig.reset();
		this.followShot = null;
		this.director = null;
		this.pose = null;
		this.lastNanos = 0;
		if (mc.player != null) {
			mc.player.sendOverlayMessage(Component.translatable("vrcamera.message.mode",
					Component.translatable("vrcamera.mode." + mode.name().toLowerCase(java.util.Locale.ROOT))));
		}
	}

	public void nextShot() {
		if (this.mode == Mode.DIRECTOR && this.director != null) {
			this.director.next();
			say("vrcamera.message.next");
		}
	}

	public void toggleHold() {
		if (this.mode == Mode.DIRECTOR && this.director != null) {
			say(this.director.toggleHold() ? "vrcamera.message.hold" : "vrcamera.message.release");
		}
	}

	private static void say(String key) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			player.sendOverlayMessage(Component.translatable(key));
		}
	}

	/**
	 * Called while the game sets up its camera for a picture.
	 *
	 * @return where the camera is for that picture, null to leave the view of the game alone
	 */
	public Pose update(float partialTick) {
		if (!filmsNow() || DesktopGui.isDrawing()) {
			return null;
		}
		// Filming into the game window this is where the camera moves, once per frame. With a window of its own
		// it was moved already, before its picture is drawn
		if (!DirectorPass.isActive() && !advance(partialTick)) {
			return null;
		}
		return showsOwnView() ? null : this.pose;
	}

	/**
	 * moves the camera on by one frame
	 *
	 * @return false if it could not be, and is off now
	 */
	public boolean advance(float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (this.mode == Mode.OFF || player == null) {
			return false;
		}
		try {
			Pose before = this.pose;
			long now = System.nanoTime();
			this.pose = move(mc, player, partialTick);
			// how fast it moves, for where to show it between two of its pictures
			double seconds = (now - this.poseNanos) / 1.0E9;
			Vec3 moved = before == null ? Vec3.ZERO : this.pose.position().subtract(before.position());
			this.poseSpeed = seconds > 1.0E-4 && seconds < 0.5 && moved.length() < MARKER_JUMP ?
					moved.scale(1.0 / seconds) : Vec3.ZERO;
			this.poseStep = seconds;
			this.poseNanos = now;
			return true;
		} catch (RuntimeException e) {
			// this runs in the middle of a frame, a crash here would take the game with it
			Vrcamera.LOGGER.error("VRCamera: the camera on the screen failed and was turned off", e);
			setMode(Mode.OFF);
			return false;
		}
	}

	/**
	 * @return if the player has the camera themselves right now, and steers it with the keys they walk with
	 */
	public boolean isSteered() {
		return this.mode != Mode.OFF && this.steered != null;
	}

	/**
	 * The player takes the camera over, or gives it back. Taken over, the keys to walk move it: closer and away,
	 * around the player, up and down. Given back it stays where it was put, like a camera placed by hand in VR:
	 * the director keeps it for a while and then goes on, the follow camera keeps it for good.
	 */
	public void toggleSteering() {
		if (this.mode == Mode.OFF || this.pose == null) {
			return;
		}
		if (this.steered != null) {
			Shot kept = this.steered;
			this.steered = null;
			if (this.mode == Mode.FOLLOW) {
				this.followShot = kept;
			} else if (this.director != null) {
				this.director.showManual(kept);
			}
			if (!this.steeredFromWindow) {
				say("vrcamera.message.steer.off");
			}
			return;
		}
		// from where the camera is right now, so that taking it over is not a jump
		Vec3 offset = this.pose.position().subtract(this.subject.center);
		ShotConfig place = CameraConfig.defaultPreset();
		if (offset.length() > STEER_MIN_DISTANCE * this.subject.unit) {
			place.azimuth = Math.toDegrees(CamMath.wrap(CamMath.azimuthOf(offset) - this.subject.facing));
			place.elevation = Math.toDegrees(CamMath.elevationOf(offset));
			place.distance = offset.length() / this.subject.unit;
		} else {
			// it was at the eyes of the player: behind them, to have something to steer
			place.azimuth = 180;
			place.elevation = 15;
			place.distance = 3;
		}
		place.fov = this.pose.fov();
		this.steered = new Shot(ShotType.CUSTOM, place, 1);
		this.steered.start(this.subject, CameraController.INSTANCE.config());
		this.rig.adopt(this.pose.position(), this.steered, this.subject);
		if (!hasOwnWindow() || !OutputWindow.isFocused()) {
			say("vrcamera.message.steer.on");
		}
	}

	/**
	 * @return if the use key belongs to the camera right now: the player holds it, or points at it to take it
	 */
	public boolean wantsUseKey() {
		return this.mode != Mode.OFF && (this.grabbed || this.aimed);
	}

	/**
	 * The mouse wheel while the camera is held: away from the player and back.
	 *
	 * @return false if the camera is not held, and the wheel is for the game
	 */
	public boolean scroll(double amount) {
		if (this.mode == Mode.OFF || !this.grabbed) {
			return false;
		}
		this.grabDistance = CamMath.clamp(this.grabDistance * Math.exp(amount * GRAB_WHEEL), GRAB_NEAR, GRAB_FAR);
		return true;
	}

	/**
	 * The hand of a player at a screen is where they look. Pointing at the camera and holding the use key takes
	 * it, it then hangs in front of them at the distance it was taken from, and goes where they look. Let go of,
	 * it stays there like a camera put down by hand in VR. Let go of in a swing, it is thrown.
	 */
	private void reach(Minecraft mc, LocalPlayer player, float partialTick, double dt) {
		boolean possible = hasOwnWindow() && this.steered == null && this.pose != null && mc.gui.screen() == null &&
				!showsOwnView();
		if (!possible) {
			this.grabbed = false;
			this.aimed = false;
			return;
		}
		Vec3 eyes = player.getEyePosition(partialTick);
		Vec3 look = player.getViewVector(partialTick);
		if (this.grabbed) {
			if (!mc.options.keyUse.isDown()) {
				letGo();
				return;
			}
			// It comes after the look and the wheel, it is not nailed to them: a hand is not that steady, and a
			// wheel goes in notches
			this.grabHeld += (this.grabDistance - this.grabHeld) * (1.0 - Math.exp(-GRAB_WHEEL_EASE * dt));
			this.grabOffset = this.grabOffset.lerp(look.scale(this.grabHeld), 1.0 - Math.exp(-GRAB_EASE * dt));
			// not into a wall the player looks at
			this.grabbedAt = WorldProbe.reach(player, eyes, eyes.add(this.grabOffset));
			Quaternionf wanted = new Quaternionf(this.grabRotation);
			if (CamMath.lookRotation(this.subject.center.subtract(this.grabbedAt), wanted)) {
				this.grabRotation.slerp(wanted, (float) (1.0 - Math.exp(-GRAB_EASE * dt)));
			}
			this.handThrow.sample(this.grabbedAt);
			return;
		}
		Vec3 toCamera = this.pose.position().subtract(eyes);
		double along = toCamera.dot(look);
		// a bit more room for the aim the further away it is, a camera far off is a few pixels
		this.aimed = along > 0 && along < GRAB_REACH &&
				toCamera.subtract(look.scale(along)).length() < GRAB_AIM + GRAB_AIM_PER_BLOCK * along;
		if (this.aimed && mc.options.keyUse.isDown()) {
			this.grabbed = true;
			this.grabDistance = CamMath.clamp(toCamera.length(), GRAB_NEAR, GRAB_FAR);
			// from where and how it is right now, to not jump into the hand
			this.grabHeld = toCamera.length();
			this.grabOffset = toCamera;
			this.grabbedAt = this.pose.position();
			this.grabRotation.set(this.pose.rotation());
			this.handThrow.clear();
		}
	}

	private void letGo() {
		this.grabbed = false;
		CameraConfig config = CameraController.INSTANCE.config();
		Vec3 landing = this.grabbedAt;
		Vec3 thrown = this.handThrow.release(this.subject.velocity, config.throwPower);
		boolean wasThrown = thrown.lengthSqr() > 0;
		if (wasThrown) {
			Vec3 target = this.grabbedAt.add(thrown);
			landing = this.grabbedAt.lerp(target, WorldProbe.armFraction(this.subject, this.grabbedAt, target, config));
		}
		Vec3 offset = landing.subtract(this.subject.center);
		ShotConfig place = CameraConfig.defaultPreset();
		place.azimuth = Math.toDegrees(CamMath.wrap(CamMath.azimuthOf(offset) - this.subject.facing));
		place.elevation = Math.toDegrees(CamMath.elevationOf(offset));
		place.distance = Math.max(0.3, offset.length() / this.subject.unit);
		if (this.pose != null) {
			place.fov = this.pose.fov();
		}
		Shot shot = new Shot(ShotType.CUSTOM, place, 1);
		shot.start(this.subject, config);
		this.rig.adopt(this.grabbedAt, shot, this.subject);
		if (wasThrown) {
			this.rig.blend();
		}
		if (this.mode == Mode.FOLLOW) {
			this.followShot = shot;
		} else if (this.director != null) {
			this.director.showManual(shot);
		}
	}

	/**
	 * moves the camera the way the keys say
	 */
	private void steer(Minecraft mc, double dt) {
		ShotConfig place = this.steered.config;
		double around = held(mc.options.keyRight) - held(mc.options.keyLeft);
		double away = held(mc.options.keyDown) - held(mc.options.keyUp);
		double up = held(mc.options.keyJump) - held(mc.options.keyShift);
		double turned = around * STEER_TURN * dt;
		place.azimuth = Math.toDegrees(CamMath.wrap(Math.toRadians(place.azimuth + turned)));
		// Right away, and not when the shot has caught up with it: a shot follows where it should be slowly, to
		// not swing with every turn of the player
		this.steered.azimuth += Math.toRadians(turned);
		// by a part of the distance, not by blocks: a far camera would crawl and a near one jump
		place.distance = CamMath.clamp(place.distance * Math.exp(away * STEER_ZOOM * dt), 0.6, 48.0);
		place.elevation = CamMath.clamp(place.elevation + up * STEER_RISE * dt, -35.0, 85.0);
	}

	private double held(KeyMapping key) {
		// in the window of the camera the game does not hear the keys, they are asked for there
		boolean down = this.steeredFromWindow ?
				OutputWindow.isKeyDown(KeyMappingHelper.getBoundKeyOf(key).getValue()) : key.isDown();
		return down ? 1.0 : 0.0;
	}

	/**
	 * @return if the picture is what the player sees themselves right now. In VR a camera with no room around the
	 * player films from their face. At a screen the view of the player is that already, with their hand and
	 * their menus in it
	 */
	public boolean showsOwnView() {
		return this.steered == null && this.mode == Mode.DIRECTOR && this.director != null &&
				this.director.current() != null && this.director.current().type == ShotType.POV;
	}

	/**
	 * Shows the player where the camera is, while it films into a window of its own and they can't tell from
	 * their view.
	 *
	 * @param viewPosition where the pass looks from, the pose stack is relative to that
	 */
	public void renderModel(SubmitNodeCollector output, Vec3 viewPosition, PoseStack poseStack) {
		if (showsMarker()) {
			RemoteCameras.INSTANCE.drawModel(output, viewPosition, poseStack, markerPosition(), this.pose.rotation());
		}
	}

	/**
	 * The camera icon with the distance to it, like in VR: over the camera, or at the edge of the view on the
	 * side the camera is on. Called while the game collects gizmos for a pass
	 */
	public void drawLabel() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (!showsMarker() || player == null || !CameraController.INSTANCE.config().indicator) {
			return;
		}
		try {
			// From where the game looks in this very frame. From where the player was when the camera was moved
			// last, the icon would shake with every step
			Camera view = mc.gameRenderer.mainCamera();
			CameraController.INSTANCE.drawIndicatorWithoutVR(markerPosition(), view.position(),
					new Vec3(view.forwardVector().x(), view.forwardVector().y(), view.forwardVector().z()),
					new Vec3(view.upVector().x(), view.upVector().y(), view.upVector().z()), player.getScale(),
					// it beats while the mouse is on the camera and can take it, and is held larger while it has it
					this.grabbed ? GRAB_ICON : this.aimed ?
							GRAB_ICON + GRAB_ICON_BEAT * Math.sin(System.nanoTime() / 1.0E9 * GRAB_ICON_RATE) : 1.0);
		} catch (IllegalStateException e) {
			// no gizmo collection is running, nothing to draw into
		}
	}

	/**
	 * @return what the camera is doing, for the debug overlay
	 */
	public List<String> debugLines() {
		List<String> lines = new ArrayList<>();
		lines.add("VRCamera on screen: " + this.mode + (hasOwnWindow() ? ", own window" : ", game window"));
		Shot shot = this.steered != null ? this.steered : this.mode == Mode.FOLLOW ? this.followShot :
				this.director == null ? null : this.director.current();
		if (shot != null) {
			lines.add("shot: " + shot.type + (showsOwnView() ? " (own view of the player)" : ""));
		}
		if (hasOwnWindow()) {
			lines.addAll(DirectorPass.debugLines());
		}
		return lines;
	}

	/**
	 * @return where the camera is right now. It is moved once per picture it takes, the game draws more frames
	 * than that: shown where it was moved to, it would go in steps
	 */
	private Vec3 markerPosition() {
		Minecraft mc = Minecraft.getInstance();
		if (this.grabbed && mc.player != null && this.grabbedAt.distanceToSqr(this.pose.position()) < 1.0E-6) {
			// held, it goes with the eyes of the player: with those of this very frame
			Vec3 eyes = mc.player.getEyePosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
			return WorldProbe.reach(mc.player, eyes, eyes.add(this.grabOffset));
		}
		double since = Math.min((System.nanoTime() - this.poseNanos) / 1.0E9, this.poseStep * 1.5);
		return this.pose.position().add(this.poseSpeed.scale(Math.max(0.0, since)));
	}

	private boolean showsMarker() {
		return hasOwnWindow() && this.pose != null && !DirectorPass.isActive() && !showsOwnView();
	}

	/**
	 * @return if what the game draws right now is the picture of this camera: all the time while it films into
	 * the game window, and only in its own turn while it has a window of its own
	 */
	public boolean filmsNow() {
		return this.mode != Mode.OFF && (DirectorPass.isActive() ||
				CameraController.INSTANCE.config().screenOutput == ScreenOutput.SCREEN);
	}

	/**
	 * Called once per frame. Filming into the game window, the game has to look from behind the player: that
	 * draws the player, and leaves the hand that is drawn over a first person view out of the picture. Also after
	 * F5 was pressed. With a window of its own the view of the player is theirs
	 */
	public void keepView(Minecraft mc) {
		if (this.mode != Mode.OFF && CameraController.isVRRunning()) {
			// in VR the camera of Vivecraft does all of this
			setMode(Mode.OFF);
			return;
		}
		boolean ownView = this.mode != Mode.OFF &&
				CameraController.INSTANCE.config().screenOutput == ScreenOutput.SCREEN;
		if (ownView) {
			if (this.viewBefore == null) {
				this.viewBefore = mc.options.getCameraType();
			}
			// with no room for the camera the picture is the first person view of the game itself
			CameraType view = showsOwnView() ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK;
			if (mc.options.getCameraType() != view) {
				mc.options.setCameraType(view);
			}
		} else if (this.viewBefore != null) {
			mc.options.setCameraType(this.viewBefore);
			this.viewBefore = null;
		}
	}

	/**
	 * @return if the camera is on and films into a window of its own
	 */
	public boolean hasOwnWindow() {
		return this.mode != Mode.OFF && CameraController.INSTANCE.config().screenOutput == ScreenOutput.WINDOW;
	}

	/**
	 * the picture can't be drawn or shown: off, and the player is told why
	 */
	public void failed(String message) {
		setMode(Mode.OFF);
		say(message);
	}

	/**
	 * @return where the camera was put by the last {@link #update}, null while it does not film
	 */
	public Pose pose() {
		return filmsNow() && !showsOwnView() && !DesktopGui.isDrawing() ? this.pose : null;
	}

	private Pose move(Minecraft mc, LocalPlayer player, float partialTick) {
		CameraConfig config = CameraController.INSTANCE.config();
		if (config != this.config || this.director == null) {
			// the settings were read again
			this.config = config;
			this.director = new Director(config);
			this.followShot = null;
		}
		long now = System.nanoTime();
		double realDt = this.lastNanos == 0 ? 0 : (now - this.lastNanos) / 1.0E9;
		this.lastNanos = now;
		if (realDt > MAX_GAP) {
			this.subject.reset();
			realDt = 0;
		}
		// a camera that takes few pictures per second has long steps, but not longer than this
		realDt = Math.min(realDt, MAX_FRAME_TIME);
		// like in VR the camera keeps moving while the game is paused, to get to the pause menu
		double dt = realDt;

		this.subject.updateWithoutVR(player, partialTick, dt, realDt, config);
		// Only for a camera with a window of its own. Filming into the game window, the menu covers the picture
		this.subject.guiCenter = hasOwnWindow() ? DesktopGui.place(mc, this.subject, config) : null;
		// The window of the camera has the keyboard: the player went over to it to steer. And back to the game
		boolean inWindow = hasOwnWindow() && OutputWindow.isFocused();
		if (inWindow != this.steeredFromWindow && (inWindow ? this.steered == null : this.steered != null)) {
			toggleSteering();
		}
		this.steeredFromWindow = inWindow && this.steered != null;
		if (this.steeredFromWindow) {
			// the game takes a player who presses nothing in its own window for gone, and draws fewer frames
			mc.getFramerateLimitTracker().onInputReceived();
		}
		reach(mc, player, partialTick, realDt);
		if (this.grabbed) {
			// in the hand of the player, which is where they look: no shot has a say in that
			return new Pose(this.grabbedAt, new Quaternionf(this.grabRotation),
					this.pose == null ? 70.0F : this.pose.fov());
		}
		Shot shot;
		if (this.steered != null) {
			steer(mc, realDt);
			this.steered.update(this.subject, config, dt);
			shot = this.steered;
		} else if (this.mode == Mode.FOLLOW) {
			if (this.followShot == null || !this.rig.ready()) {
				this.followShot = new Shot(ShotType.CUSTOM, config.preset(), 1);
				this.followShot.start(this.subject, config);
				this.rig.snap(this.followShot, this.subject);
			} else if (this.subject.teleported) {
				this.rig.rebase(this.subject);
			}
			this.followShot.update(this.subject, config, dt);
			shot = this.followShot;
		} else {
			this.director.update(this.subject, this.rig, dt);
			shot = this.director.current();
		}
		this.rig.update(shot, this.subject, dt, config);
		return new Pose(this.rig.position(), new Quaternionf(this.rig.rotation()),
				(float) Math.clamp(this.rig.fov(), 1.0, 179.0));
	}
}
