package ru.deelter.vrcamera.client.rig;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Watches a camera that is held in the hand, to tell a throw from just letting go of it.
 */
public final class HandThrow {
	// seconds of movement before the release that count
	private static final double WINDOW = 0.12;
	// blocks per second the hand has to move at, slower than that is putting the camera down
	private static final double MIN_SPEED = 2.5;
	// Blocks of flight per squared block per second of speed. Squared, like the range of a real throw: twice as fast
	// goes four times as far
	private static final double RANGE = 0.3;
	private static final double MAX_DISTANCE = 24.0;

	private record Sample(Vec3 pos, long nanos) {
	}

	private final Deque<Sample> trail = new ArrayDeque<>();

	/**
	 * call every frame while the camera is held
	 */
	public void sample(Vec3 pos) {
		long now = System.nanoTime();
		this.trail.addLast(new Sample(pos, now));
		while (this.trail.size() > 2 && (now - this.trail.peekFirst().nanos) / 1.0E9 > WINDOW) {
			this.trail.removeFirst();
		}
	}

	public void clear() {
		this.trail.clear();
	}

	/**
	 * call when the camera was let go of
	 *
	 * @param drift velocity of the player, that is not part of the throw
	 * @param power multiplier for the distance, 0 turns throwing off
	 * @return how far and where to the camera should fly, {@link Vec3#ZERO} if it was not thrown
	 */
	public Vec3 release(Vec3 drift, double power) {
		Sample first = this.trail.peekFirst();
		Sample last = this.trail.peekLast();
		this.trail.clear();
		if (first == null || first == last || power <= 0) {
			return Vec3.ZERO;
		}
		double seconds = (last.nanos - first.nanos) / 1.0E9;
		if (seconds < 0.01) {
			return Vec3.ZERO;
		}
		Vec3 velocity = last.pos.subtract(first.pos).scale(1.0 / seconds).subtract(drift);
		double speed = velocity.length();
		if (speed < MIN_SPEED) {
			return Vec3.ZERO;
		}
		return velocity.normalize().scale(Math.min(RANGE * speed * speed * power, MAX_DISTANCE));
	}
}
