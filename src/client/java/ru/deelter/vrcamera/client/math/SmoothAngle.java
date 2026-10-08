package ru.deelter.vrcamera.client.math;

/**
 * {@link Smooth} for angles in radians, always takes the short way around
 */
public class SmoothAngle extends Smooth {
	@Override
	public double update(double target, double time, double dt) {
		return super.update(value + CamMath.wrap(target - value), time, dt);
	}
}
