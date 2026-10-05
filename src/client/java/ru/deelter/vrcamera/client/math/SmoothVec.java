package ru.deelter.vrcamera.client.math;

import net.minecraft.world.phys.Vec3;

public class SmoothVec {
	private final Smooth x = new Smooth();
	private final Smooth y = new Smooth();
	private final Smooth z = new Smooth();

	public Vec3 get() {
		return new Vec3(this.x.get(), this.y.get(), this.z.get());
	}

	public void reset(Vec3 value) {
		this.x.reset(value.x);
		this.y.reset(value.y);
		this.z.reset(value.z);
	}

	public Vec3 update(Vec3 target, double time, double dt) {
		return new Vec3(this.x.update(target.x, time, dt), this.y.update(target.y, time, dt),
				this.z.update(target.z, time, dt));
	}
}
