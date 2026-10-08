package ru.deelter.vrcamera.client.rig;

import net.minecraft.world.phys.Vec3;
import org.vivecraft.client_vr.VRData;

/**
 * Lets hands and feet hit a dropped camera: kick it, or keep it in the air like a volleyball. Feet need full body
 * tracking.
 */
public final class LimbStrikes {
	private static final int LIMBS = 4;
	private static final int HANDS = 2;
	private static final double REACH = 0.18;
	// blocks per second limb and camera have to close in on each other
	private static final double MIN_SPEED = 1.2;
	private static final double HAND_ON_GROUND_MIN_SPEED = 2.5;
	// faster than any limb, that is a tracking glitch or a teleport
	private static final double MAX_SPEED = 40.0;
	private static final double REST_AFTER_HIT = 0.25;
	// the hand that let go of the camera is right next to it, without this it would hit it at once
	private static final double REST_AFTER_RELEASE = 0.4;

	private final Vec3[] last = new Vec3[LIMBS];
	private final double[] rest = new double[LIMBS];

	private static Vec3 closest(Vec3 from, Vec3 to, Vec3 point) {
		Vec3 way = to.subtract(from);
		double length = way.lengthSqr();
		if (length < 1.0E-8) {
			return to;
		}
		double along = Math.max(0.0, Math.min(1.0, point.subtract(from).dot(way) / length));
		return from.add(way.scale(along));
	}

	public void reset() {
		for (int i = 0; i < LIMBS; i++) {
			this.last[i] = null;
		}
	}

	public void released(int hand) {
		this.rest[hand] = REST_AFTER_RELEASE;
	}

	public void update(VRData vr, DroppedCamera camera, double dt, double power) {
		VRData.VRDevicePose[] limbs = {vr.getController(0), vr.getController(1), vr.foot_left, vr.foot_right};
		for (int i = 0; i < LIMBS; i++) {
			Vec3 position = limbs[i] == null ? null : limbs[i].getPosition();
			Vec3 previous = this.last[i];
			this.last[i] = position;
			this.rest[i] -= dt;
			if (position == null || previous == null || dt <= 0 || power <= 0 || this.rest[i] > 0) {
				continue;
			}
			// a hand has to mean it to hit a camera on the ground, or reaching for one would knock it away
			double minSpeed = i < HANDS && camera.isResting() ? HAND_ON_GROUND_MIN_SPEED : MIN_SPEED;
			Vec3 velocity = position.subtract(previous).scale(1.0 / dt);
			if (velocity.length() > MAX_SPEED) {
				continue;
			}
			// along the whole way of this frame, a fast foot is past the camera within one
			Vec3 offset = camera.position().subtract(closest(previous, position, camera.position()));
			double distance = offset.length();
			if (distance > REACH * vr.worldScale) {
				continue;
			}
			Vec3 normal = distance < 1.0E-4 ? new Vec3(0, 1, 0) : offset.scale(1.0 / distance);
			if (camera.strike(velocity, normal, power, minSpeed)) {
				this.rest[i] = REST_AFTER_HIT;
			}
		}
	}
}
