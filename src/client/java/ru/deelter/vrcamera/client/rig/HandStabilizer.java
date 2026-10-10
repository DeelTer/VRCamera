package ru.deelter.vrcamera.client.rig;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

/**
 * Steadies a camera held in a hand. A hand trembles, and twitches along with whatever the other hand is doing, and
 * the picture shows all of it.
 * <p>
 * Follows the hand with a lag that gets shorter the further the hand is ahead: small twitches are swallowed, while a
 * move that is meant gets through nearly as fast as it was made.
 */
public final class HandStabilizer {
	private static final double MAX_LAG = 0.3;

	private static final double SOFT_DISTANCE = 0.05;
	private static final double SOFT_ANGLE = Math.toRadians(4);
	private final Quaternionf rotation = new Quaternionf();
	private Vec3 position = Vec3.ZERO;
	private boolean started;

	/**
	 * @return part of the way to go in this frame
	 */
	private static double follow(double dt, double lag) {
		return 1.0 - Math.exp(-dt / lag);
	}

	public Vec3 position() {
		return position;
	}

	public Quaternionf rotation() {
		return rotation;
	}

	/**
	 * the camera was let go of, the next hand starts fresh
	 */
	public void reset() {
		started = false;
	}

	/**
	 * @param handPosition where the hand has the camera
	 * @param strength     0 = right where the hand is, 1 = as steady as it gets
	 */
	public void update(Vec3 handPosition, Quaternionfc handRotation, double dt, double strength) {
		if (!started || strength <= 0) {
			position = handPosition;
			rotation.set(handRotation);
			started = true;
			return;
		}
		final double lag = MAX_LAG * Math.min(strength, 1.0);

		final double distance = handPosition.distanceTo(position);
		position = position.lerp(handPosition, follow(dt, lag / (1.0 + distance / SOFT_DISTANCE)));

		final double dot = rotation.x * handRotation.x() + rotation.y * handRotation.y() +
				rotation.z * handRotation.z() + rotation.w * handRotation.w();
		final double angle = 2.0 * Math.acos(Math.min(1.0, Math.abs(dot)));
		rotation.slerp(handRotation, (float) follow(dt, lag / (1.0 + angle / SOFT_ANGLE)));
	}
}
