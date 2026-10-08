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
	// seconds of lag for the smallest moves, at full strength
	private static final double MAX_LAG = 0.3;
	// this far ahead of the camera the hand gets through twice as fast, in blocks and radians
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
		return this.position;
	}

	public Quaternionf rotation() {
		return this.rotation;
	}

	/**
	 * the camera was let go of, the next hand starts fresh
	 */
	public void reset() {
		this.started = false;
	}

	/**
	 * @param handPosition where the hand has the camera
	 * @param strength     0 = right where the hand is, 1 = as steady as it gets
	 */
	public void update(Vec3 handPosition, Quaternionfc handRotation, double dt, double strength) {
		if (!this.started || strength <= 0) {
			this.position = handPosition;
			this.rotation.set(handRotation);
			this.started = true;
			return;
		}
		double lag = MAX_LAG * Math.min(strength, 1.0);

		double distance = handPosition.distanceTo(this.position);
		this.position = this.position.lerp(handPosition, follow(dt, lag / (1.0 + distance / SOFT_DISTANCE)));

		double dot = this.rotation.x * handRotation.x() + this.rotation.y * handRotation.y() +
				this.rotation.z * handRotation.z() + this.rotation.w * handRotation.w();
		double angle = 2.0 * Math.acos(Math.min(1.0, Math.abs(dot)));
		this.rotation.slerp(handRotation, (float) follow(dt, lag / (1.0 + angle / SOFT_ANGLE)));
	}
}
