package ru.deelter.vrcamera.client.desktop;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.math.CamMath;
import ru.deelter.vrcamera.client.rig.HandThrow;
import ru.deelter.vrcamera.client.rig.Subject;
import ru.deelter.vrcamera.client.rig.WorldProbe;

import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * The hand of a player at a screen is where they look. A camera they point at and hold the use key on is taken: it
 * hangs in front of them at the distance it was taken from, goes where they look, looks at them, and the wheel moves
 * it away and back. What happens to it when they let go is up to the mode the camera is in.
 */
final class CameraGrab {
	private static final int NONE = -1;
	// from how far a camera can be taken, and how well it has to be pointed at: blocks, plus blocks per block of
	// distance
	private static final double REACH = 192.0;
	private static final double AIM = 0.3;
	private static final double AIM_PER_BLOCK = 0.05;
	// how near and far it can be held, and the part of the distance one notch of the wheel is
	private static final double NEAR = 0.7;
	private static final double FAR = 32.0;
	private static final double WHEEL = 0.12;
	// how fast it comes after the look and after the wheel, per second
	private static final double EASE = 9.0;
	private static final double WHEEL_EASE = 3.5;
	// how much larger the icon of a camera is while it can be taken, how much of that it beats by, and how fast
	private static final double ICON = 1.35;
	private static final double ICON_BEAT = 0.2;
	private static final double ICON_RATE = 9.0;

	private final HandThrow handThrow = new HandThrow();
	private final Quaternionf rotation = new Quaternionf();
	private boolean holding;
	private int aimedAt = NONE;
	// how far in front of the eyes it should hang, and how far it does on its way there
	private double wanted;
	private double distance;
	private Vec3 offset = Vec3.ZERO;
	private Vec3 position = Vec3.ZERO;

	boolean isHolding() {
		return this.holding;
	}

	boolean isAiming() {
		return this.aimedAt != NONE;
	}

	/**
	 * @return which camera is pointed at, or was when it was taken
	 */
	int aimedAt() {
		return this.aimedAt;
	}

	/**
	 * @return if the use key belongs to a camera right now, and not to the game
	 */
	boolean wantsUseKey() {
		return this.holding || isAiming();
	}

	Vec3 position() {
		return this.position;
	}

	Quaternionf rotation() {
		return new Quaternionf(this.rotation);
	}

	Vec3 forward() {
		return new Vec3(this.rotation.transform(new Vector3f(0, 0, -1)));
	}

	/**
	 * lets go of the camera without a word on where it lands
	 */
	void release() {
		this.holding = false;
	}

	void reset() {
		this.holding = false;
		this.aimedAt = NONE;
	}

	/**
	 * Finds the camera the player points at, the nearest one if there are several in a line.
	 *
	 * @param positions where each camera is
	 * @param takeable  which of them can be taken at all
	 */
	void aim(Vec3 eyes, Vec3 look, int cameras, IntFunction<Vec3> positions, IntPredicate takeable) {
		this.aimedAt = NONE;
		double nearest = REACH;
		for (int camera = 0; camera < cameras; camera++) {
			Vec3 to = positions.apply(camera).subtract(eyes);
			double along = to.dot(look);
			// far off a camera is a few pixels, what is pointed at there is its icon: as large at any distance
			if (along > 0 && along < nearest && takeable.test(camera) &&
					to.subtract(look.scale(along)).length() < AIM + AIM_PER_BLOCK * along) {
				nearest = along;
				this.aimedAt = camera;
			}
		}
	}

	/**
	 * takes the camera from where and how it is right now, to not have it jump into the hand
	 */
	void take(DesktopCamera.Pose camera, Vec3 eyes) {
		this.holding = true;
		this.offset = camera.position().subtract(eyes);
		this.distance = this.offset.length();
		this.wanted = CamMath.clamp(this.distance, NEAR, FAR);
		this.position = camera.position();
		this.rotation.set(camera.rotation());
		this.handThrow.clear();
	}

	/**
	 * moves the held camera on by one frame
	 */
	void hold(LocalPlayer player, Vec3 eyes, Vec3 look, Subject subject, CameraConfig config, double dt) {
		// It comes after the look and the wheel, it is not nailed to them: a hand is not that steady, and a wheel
		// goes in notches
		double ease = 1.0 - Math.exp(-EASE * dt);
		this.distance += (this.wanted - this.distance) * (1.0 - Math.exp(-WHEEL_EASE * dt));
		this.offset = this.offset.lerp(look.scale(this.distance), ease);
		this.position = shownAt(player, eyes);
		// like a shot does: this close only part of the player fits, and the face is the part worth showing
		double near = config.faceDistance * subject.unit;
		double closeness = near <= 0 ? 0 :
				1.0 - CamMath.smoothstep((this.position.distanceTo(subject.center) - near) / near);
		Quaternionf facing = new Quaternionf(this.rotation);
		if (CamMath.lookRotation(subject.center.lerp(subject.head, closeness).subtract(this.position), facing)) {
			this.rotation.slerp(facing, (float) ease);
		}
		this.handThrow.sample(this.position);
	}

	/**
	 * @return where the held camera is for a player whose eyes are there: in front of them, and not in a wall
	 */
	Vec3 shownAt(LocalPlayer player, Vec3 eyes) {
		return WorldProbe.reach(player, eyes, eyes.add(this.offset));
	}

	/**
	 * @return false if no camera is held, and the wheel is for something else
	 */
	boolean scroll(double notches) {
		if (!this.holding) {
			return false;
		}
		this.wanted = CamMath.clamp(this.wanted * Math.exp(notches * WHEEL), NEAR, FAR);
		return true;
	}

	/**
	 * @param drift velocity of the player, that is not part of the swing
	 * @return how fast the camera was moved when it was let go of, in blocks per second
	 */
	Vec3 swing(Vec3 drift) {
		return this.handThrow.velocity(drift);
	}

	/**
	 * @return how far and where to the camera flies when it is let go of, {@link Vec3#ZERO} if it was not thrown
	 */
	Vec3 thrown(Vec3 drift, double power) {
		return this.handThrow.release(drift, power);
	}

	/**
	 * @param filming the camera that is held, if one is
	 * @return how much larger the icon of a camera is: it beats while the camera can be taken, and stays larger
	 * while it is held
	 */
	double iconSize(int camera, int filming) {
		if (this.holding) {
			return camera == filming ? ICON : 1.0;
		}
		return camera == this.aimedAt ? ICON + ICON_BEAT * Math.sin(System.nanoTime() / 1.0E9 * ICON_RATE) : 1.0;
	}
}
