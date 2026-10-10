package ru.deelter.vrcamera.client.rig;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Watches a camera that is held in the hand, to tell a throw from just letting go of it.
 */
public final class HandThrow {
	private static final double WINDOW = 0.12;

	private static final double MIN_SPEED = 2.5;

	private static final double RANGE = 0.3;
	private static final double MAX_DISTANCE = 24.0;
	private final Deque<Sample> trail = new ArrayDeque<>();

	/**
	 * call every frame while the camera is held
	 */
	public void sample(Vec3 pos) {
		final long now = System.nanoTime();
		trail.addLast(new Sample(pos, now));
		while (trail.size() > 2 && (now - trail.peekFirst().nanos) / 1.0E9 > WINDOW) {
			trail.removeFirst();
		}
	}

	public void clear() {
		trail.clear();
	}

	/**
	 * call when the camera was let go of
	 *
	 * @param drift velocity of the player, that is not part of the throw
	 * @param power multiplier for the distance, 0 turns throwing off
	 * @return how far and where to the camera should fly, {@link Vec3#ZERO} if it was not thrown
	 */
	public Vec3 release(Vec3 drift, double power) {
		final Vec3 velocity = velocity(drift);
		final double speed = velocity.length();
		if (speed < MIN_SPEED || power <= 0) {
			return Vec3.ZERO;
		}
		return velocity.normalize().scale(Math.min(RANGE * speed * speed * power, MAX_DISTANCE));
	}

	/**
	 * call when the camera was let go of
	 *
	 * @param drift velocity of the player, that is not part of the movement of the hand
	 * @return how fast the hand was moving, in blocks per second
	 */
	public Vec3 velocity(Vec3 drift) {
		final Sample first = trail.peekFirst();
		final Sample last = trail.peekLast();
		trail.clear();
		if (first == null || first == last) {
			return Vec3.ZERO;
		}
		final double seconds = (last.nanos - first.nanos) / 1.0E9;
		if (seconds < 0.01) {
			return Vec3.ZERO;
		}
		return last.pos.subtract(first.pos).scale(1.0 / seconds).subtract(drift);
	}

	private record Sample(Vec3 pos, long nanos) {
	}
}
