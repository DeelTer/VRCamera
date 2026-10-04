package ru.deelter.vrcamera.client.math;

/**
 * critically damped spring, moves a value towards a target without overshooting
 */
public class Smooth {
	protected double value;
	protected double velocity;

	public double get() {
		return this.value;
	}

	public void reset(double value) {
		this.value = value;
		this.velocity = 0;
	}

	/**
	 * @param target value to move to
	 * @param time   rough time in seconds to reach the target
	 * @param dt     seconds since the last update
	 */
	public double update(double target, double time, double dt) {
		if (dt <= 0) {
			return this.value;
		}
		if (time < 1.0E-4) {
			reset(target);
			return this.value;
		}
		double omega = 2.0 / time;
		double x = omega * dt;
		double exp = 1.0 / (1.0 + x + 0.48 * x * x + 0.235 * x * x * x);
		double change = this.value - target;
		double temp = (this.velocity + omega * change) * dt;
		this.velocity = (this.velocity - omega * temp) * exp;
		this.value = target + (change + temp) * exp;
		return this.value;
	}
}
