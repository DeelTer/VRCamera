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

	private static final double MIN_SPEED = 1.2;
	private static final double HAND_ON_GROUND_MIN_SPEED = 2.5;

	private static final double MAX_SPEED = 40.0;
	private static final double REST_AFTER_HIT = 0.25;

	private static final double REST_AFTER_RELEASE = 0.4;

	private final Vec3[] last = new Vec3[LIMBS];
	private final double[] rest = new double[LIMBS];

	private static Vec3 closest(Vec3 from, Vec3 to, Vec3 point) {
		final Vec3 way = to.subtract(from);
		final double length = way.lengthSqr();
		if (length < 1.0E-8) {
			return to;
		}
		final double along = Math.max(0.0, Math.min(1.0, point.subtract(from).dot(way) / length));
		return from.add(way.scale(along));
	}

	public void reset() {
		for (int i = 0; i < LIMBS; i++) {
			last[i] = null;
		}
	}

	public void released(int hand) {
		rest[hand] = REST_AFTER_RELEASE;
	}

	public void update(VRData vrData, DroppedCamera camera, double dt, double power) {
		final VRData.VRDevicePose[] limbs = {vrData.getController(0), vrData.getController(1), vrData.foot_left,
				vrData.foot_right};
		for (int i = 0; i < LIMBS; i++) {
			final Vec3 position = limbs[i] == null ? null : limbs[i].getPosition();
			final Vec3 previous = last[i];
			last[i] = position;
			rest[i] -= dt;
			if (position == null || previous == null || dt <= 0 || power <= 0 || rest[i] > 0) {
				continue;
			}

			final double minSpeed = i < HANDS && camera.isResting() ? HAND_ON_GROUND_MIN_SPEED : MIN_SPEED;
			final Vec3 velocity = position.subtract(previous).scale(1.0 / dt);
			if (velocity.length() > MAX_SPEED) {
				continue;
			}

			final Vec3 offset = camera.position().subtract(closest(previous, position, camera.position()));
			final double distance = offset.length();
			if (distance > REACH * vrData.worldScale) {
				continue;
			}
			final Vec3 normal = distance < 1.0E-4 ? new Vec3(0, 1, 0) : offset.scale(1.0 / distance);
			if (camera.strike(velocity, normal, power, minSpeed)) {
				rest[i] = REST_AFTER_HIT;
			}
		}
	}
}
