package ru.deelter.vrcamera.client.math;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

public class SmoothVec {
	private final Smooth x = new Smooth();
	private final Smooth y = new Smooth();
	private final Smooth z = new Smooth();

	@NotNull
	public Vec3 get() {
		return new Vec3(x.get(), y.get(), z.get());
	}

	public void reset(Vec3 value) {
		x.reset(value.x);
		y.reset(value.y);
		z.reset(value.z);
	}

	@NotNull
	public Vec3 update(Vec3 target, double time, double dt) {
		return new Vec3(x.update(target.x, time, dt), y.update(target.y, time, dt),
				z.update(target.z, time, dt));
	}
}
