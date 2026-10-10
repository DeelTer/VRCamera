package ru.deelter.vrcamera.client.rig;

import org.joml.Quaternionf;

import ru.deelter.vrcamera.client.math.CamMath;

/**
 * What a camera held in a hand does on top of what the hand does: it moves with the breath, bobs with every step and
 * jolts when the one holding it gets hurt. A tracked controller alone is too steady for that.
 */
public final class HandheldShake {
	private static final double JOLT_TIME = 0.35;

	private static final double FULL_STEP_SPEED = 5.0;

	private final Quaternionf offset = new Quaternionf();
	private double time;
	private double jolt;

	/**
	 * @param speed    how fast the player moves, in blocks per second
	 * @param hurt     if the player is being hurt right now
	 * @param strength multiplier for all of it, 0 turns it off
	 * @return rotation to add to the camera, around its own axes
	 */
	public Quaternionf update(double dt, double speed, boolean hurt, double strength) {
		time += dt;
		if (hurt) {
			jolt = 1.0;
		}
		jolt *= Math.exp(-dt / JOLT_TIME);
		final double steps = CamMath.clamp(speed / FULL_STEP_SPEED, 0.0, 1.4);

		final double pitch = wave(0.27, 0) * 0.45 + wave(2.3, 0) * 1.1 * steps + wave(11, 0) * 3.5 * jolt;
		final double yaw = wave(1.15, 0.7) * 0.6 * steps + wave(9, 2) * 2.5 * jolt;
		final double roll = wave(0.19, 1) * 0.3 + wave(1.15, 0) * 0.9 * steps + wave(13, 4) * 4.0 * jolt;

		final float scale = (float) Math.toRadians(strength);
		return offset.rotationYXZ((float) yaw * scale, (float) pitch * scale, (float) roll * scale);
	}

	/**
	 * @param frequency swings per second
	 * @param phase     where in the swing it starts, in radians
	 */
	private double wave(double frequency, double phase) {
		return Math.sin(time * Math.PI * 2.0 * frequency + phase);
	}
}
