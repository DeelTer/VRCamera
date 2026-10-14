package ru.deelter.vrcamera.client.desktop;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.math.CamMath;
import ru.deelter.vrcamera.client.rig.HandThrow;
import ru.deelter.vrcamera.client.rig.HandheldShake;
import ru.deelter.vrcamera.client.rig.Subject;
import ru.deelter.vrcamera.client.rig.WorldProbe;

import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * The hand of a player at a screen is where they look. A camera they point at and hold the use key on is taken: it
 * hangs in front of them at the distance it was taken from, goes where they look, looks at them, and the wheel moves
 * it away and back. What happens to it when they let go is up to the mode the camera is in.
 * <p>
 * It follows the look in two steps, one after the other: it starts to move and comes to rest softly, however the
 * mouse is jerked. It turns to the player a moment late, which lets them drift in the picture while it is moved,
 * or looks right at them however fast it is moved, if that is what the player wants: then only a turn it was
 * taken in, or asked for, is eased out.
 * <p>
 * Turned away from the player, to film what they look at, it can shake in the hand like a body camera: with
 * every step, and a jolt when they get hurt, if the player wants that.
 */
final class CameraGrab {
	private static final int NONE = -1;

	private static final double REACH = 192.0;
	private static final double AIM = 0.3;
	private static final double AIM_PER_BLOCK = 0.05;

	private static final double NEAR = 0.7;
	private static final double FAR = 32.0;
	private static final double WHEEL = 0.12;

	private static final double TURN_EASE = 9.0;
	private static final double STEPS = 2.0;
	private static final double WHEEL_EASE = 3.5;
	private static final double TURN_BACK_SECONDS = 0.45;
	private static final double SHAKE_FADE = 0.3;

	private static final double ICON = 1.35;
	private static final double ICON_BEAT = 0.2;
	private static final double ICON_RATE = 9.0;

	private final HandThrow handThrow = new HandThrow();
	private final HandheldShake shake = new HandheldShake();
	private final Quaternionf shaken = new Quaternionf();
	private double shaking;
	private final Quaternionf rotation = new Quaternionf();
	private final Quaternionf behind = new Quaternionf();
	private boolean turned;
	private boolean holding;
	private boolean away;
	private double turningBack;
	private int aimedAt = NONE;

	private double wanted;
	private double distance;
	private Vec3 lead = Vec3.ZERO;
	private Vec3 offset = Vec3.ZERO;
	private Vec3 position = Vec3.ZERO;
	private Vec3 aim = Vec3.ZERO;

	boolean isHolding() {
		return holding;
	}

	boolean isAiming() {
		return aimedAt != NONE;
	}

	/**
	 * @return which camera is pointed at, or was when it was taken
	 */
	int aimedAt() {
		return aimedAt;
	}

	/**
	 * @return if the use key belongs to a camera right now, and not to the game
	 */
	boolean wantsUseKey() {
		return holding || isAiming();
	}

	Vec3 position() {
		return position;
	}

	/**
	 * @return what the held camera looks at
	 */
	Vec3 aim() {
		return aim;
	}

	/**
	 * @return how the held camera is turned, with the shake of the hand
	 */
	@NotNull
	Quaternionf rotation() {
		return new Quaternionf(rotation).mul(shaken);
	}

	@NotNull
	Vec3 forward() {
		return new Vec3(rotation.transform(new Vector3f(0, 0, -1)));
	}

	/**
	 * turns the held camera around: from looking at the player to looking where they look, to show something,
	 * and back
	 */
	void turnAround() {
		if (turningBack <= 0) {
			away = !away;
			turned = true;
		}
	}

	/**
	 * The use key is up. A camera that was turned away is not let go of at once: it is held a moment longer to
	 * turn back to the player, in one move a viewer can follow.
	 *
	 * @return true while it is still kept for that
	 */
	boolean turnsBack(double dt) {
		if (away) {
			away = false;
			turned = true;
			turningBack = TURN_BACK_SECONDS;
		}
		if (turningBack <= 0) {
			return false;
		}
		turningBack -= dt;
		if (turningBack <= 0) {
			CamMath.lookRotation(aim.subtract(position), rotation);
		}
		return turningBack > 0;
	}

	/**
	 * lets go of the camera without a word on where it lands
	 */
	void release() {
		holding = false;
	}

	void reset() {
		holding = false;
		aimedAt = NONE;
	}

	/**
	 * Finds the camera the player points at, the nearest one if there are several in a line.
	 *
	 * @param positions where each camera is
	 * @param takeable  which of them can be taken at all
	 */
	void aim(Vec3 eyes, Vec3 look, int cameras, IntFunction<Vec3> positions, IntPredicate takeable) {
		aimedAt = NONE;
		double nearest = REACH;
		for (int camera = 0; camera < cameras; camera++) {
			final Vec3 to = positions.apply(camera).subtract(eyes);
			final double along = to.dot(look);

			if (along > 0 && along < nearest && takeable.test(camera) &&
					to.subtract(look.scale(along)).length() < AIM + AIM_PER_BLOCK * along) {
				nearest = along;
				aimedAt = camera;
			}
		}
	}

	/**
	 * takes the camera from where and how it is right now, to not have it jump into the hand
	 */
	void take(DesktopCamera.Pose camera, Vec3 eyes) {
		holding = true;
		away = false;
		shaking = 0;
		shaken.identity();
		turningBack = 0;
		offset = camera.position().subtract(eyes);
		lead = offset;
		turned = true;
		distance = offset.length();
		wanted = CamMath.clamp(distance, NEAR, FAR);
		position = camera.position();
		aim = eyes;
		rotation.set(camera.rotation());
		handThrow.clear();
	}

	/**
	 * moves the held camera on by one frame
	 */
	void hold(LocalPlayer player, Vec3 eyes, Vec3 look, Subject subject, CameraConfig config, double dt) {
		final double ease = config.holdLag <= 0 ? 1.0 : 1.0 - Math.exp(-STEPS * dt / config.holdLag);
		distance += (wanted - distance) * (1.0 - Math.exp(-WHEEL_EASE * dt));
		lead = lead.lerp(look.scale(distance), ease);
		offset = offset.lerp(lead, ease);
		position = shownAt(player, eyes);

		final double near = config.faceDistance * subject.unit;
		final double closeness = near <= 0 ? 0 :
				1.0 - CamMath.smoothstep((position.distanceTo(subject.center) - near) / near);
		aim = subject.center.lerp(subject.head, closeness);
		final Quaternionf facing = new Quaternionf();
		if (CamMath.lookRotation(away ? position.subtract(aim) : aim.subtract(position), facing)) {
			face(facing, dt, config.keepCentered);
		}
		shake(player, subject, config, dt);
		handThrow.sample(position);
	}

	/**
	 * the shake of a body camera, while the camera is turned away from the player. It comes and goes softly with
	 * the turn
	 */
	private void shake(LocalPlayer player, Subject subject, CameraConfig config, double dt) {
		shaking += ((away ? 1.0 : 0.0) - shaking) * (1.0 - Math.exp(-dt / SHAKE_FADE));
		final double strength = config.holdShake * shaking;
		if (strength < 1.0E-3) {
			shaken.identity();
			return;
		}
		shaken.set(shake.update(dt, subject.speed, player.hurtTime > 0, strength));
	}

	/**
	 * @param facing   how the camera is turned once it looks right at what it films
	 * @param centered if it is to look there at once, and not a moment late
	 */
	private void face(Quaternionf facing, double dt, boolean centered) {
		if (!centered) {
			turned = true;
			rotation.slerp(facing, (float) (1.0 - Math.exp(-TURN_EASE * dt)));
			return;
		}
		if (turned) {
			facing.conjugate(behind).mul(rotation);
			turned = false;
		}
		behind.slerp(new Quaternionf(), (float) (1.0 - Math.exp(-TURN_EASE * dt)));
		facing.mul(behind, rotation);
	}

	/**
	 * @return where the held camera is for a player whose eyes are there: in front of them, and not in a wall
	 */
	Vec3 shownAt(LocalPlayer player, Vec3 eyes) {
		return WorldProbe.reach(player, eyes, eyes.add(offset));
	}

	/**
	 * @return false if no camera is held, and the wheel is for something else
	 */
	boolean scroll(double notches) {
		if (!holding) {
			return false;
		}
		wanted = CamMath.clamp(wanted * Math.exp(notches * WHEEL), NEAR, FAR);
		return true;
	}

	/**
	 * @param drift velocity of the player, that is not part of the swing
	 * @return how fast the camera was moved when it was let go of, in blocks per second
	 */
	Vec3 swing(Vec3 drift) {
		return handThrow.velocity(drift);
	}

	/**
	 * @return how far and where to the camera flies when it is let go of, {@link Vec3#ZERO} if it was not thrown
	 */
	Vec3 thrown(Vec3 drift, double power) {
		return handThrow.release(drift, power);
	}

	/**
	 * @param filming the camera that is held, if one is
	 * @return how much larger the icon of a camera is: it beats while the camera can be taken, and stays larger
	 * while it is held
	 */
	double iconSize(int camera, int filming) {
		if (holding) {
			return camera == filming ? ICON : 1.0;
		}
		return camera == aimedAt ? ICON + ICON_BEAT * Math.sin(System.nanoTime() / 1.0E9 * ICON_RATE) : 1.0;
	}
}
