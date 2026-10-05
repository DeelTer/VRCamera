package ru.deelter.vrcamera.client.math;

/**
 * {@link Smooth} for angles in radians, always takes the short way around
 */
public class SmoothAngle extends Smooth {
	@Override
	public double update(double target, double time, double dt) {
		return super.update(this.value + CamMath.wrap(target - this.value), time, dt);
	}
}
