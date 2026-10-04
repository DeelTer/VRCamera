package ru.deelter.vrcamera.client.rig;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.math.CamMath;

/**
 * block checks for the camera
 */
public final class WorldProbe {

	/**
	 * Checks how far a camera can move from {@code from} to {@code to}, without getting into blocks. Works like the
	 * vanilla third person camera, casts a ray for each corner of a small box around the camera.
	 *
	 * @return fraction of the way that is free, 1 if nothing is in the way
	 */
	public static double armFraction(Subject subject, Vec3 from, Vec3 to, CameraConfig config) {
		double length = from.distanceTo(to);
		if (length < 1.0E-4) {
			return 1.0;
		}
		// the rays start inside the player hitbox, so they don't start in a wall when the player hugs it
		double radius = radius(subject, config);
		double free = length;
		for (int i = 0; i < 8; i++) {
			Vec3 offset = new Vec3(
				((i & 1) * 2 - 1) * radius,
				((i >> 1 & 1) * 2 - 1) * radius,
				((i >> 2 & 1) * 2 - 1) * radius);
			Vec3 start = from.add(offset);
			BlockHitResult hit = subject.player.level().clip(new ClipContext(start, to.add(offset),
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, subject.player));
			if (hit.getType() != HitResult.Type.MISS) {
				free = Math.min(free, hit.getLocation().distanceTo(start));
			}
		}
		if (free >= length) {
			return 1.0;
		}
		return CamMath.clamp((free - config.collisionMargin) / length, 0.0, 1.0);
	}

	private static double radius(Subject subject, CameraConfig config) {
		return Math.min(config.collisionRadius, 0.2 * subject.unit);
	}

	/**
	 * @return if a camera at {@code pos} would be clear of all blocks
	 */
	public static boolean spotFree(Subject subject, Vec3 pos, CameraConfig config) {
		double radius = radius(subject, config);
		return subject.player.level().noCollision(new AABB(
			pos.x - radius, pos.y - radius, pos.z - radius,
			pos.x + radius, pos.y + radius, pos.z + radius));
	}

	/**
	 * @return if what is between the two points is at most a block thick, like a tree, post or the edge of a wall
	 */
	public static boolean thin(Subject subject, Vec3 from, Vec3 to) {
		BlockHitResult front = subject.player.level().clip(new ClipContext(from, to,
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, subject.player));
		if (front.getType() == HitResult.Type.MISS) {
			// only the edges of the camera touch something
			return true;
		}
		BlockHitResult back = subject.player.level().clip(new ClipContext(to, from,
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, subject.player));
		if (back.getType() == HitResult.Type.MISS) {
			return false;
		}
		double thickness = from.distanceTo(to) - front.getLocation().distanceTo(from) -
			back.getLocation().distanceTo(to);
		return thickness <= 1.05;
	}

	/**
	 * @return if {@code to} can be seen from {@code from}
	 */
	public static boolean visible(Subject subject, Vec3 from, Vec3 to) {
		return subject.player.level().clip(new ClipContext(from, to, ClipContext.Block.VISUAL,
			ClipContext.Fluid.NONE, subject.player)).getType() == HitResult.Type.MISS;
	}

	public static boolean inFluid(Subject subject, Vec3 pos) {
		return !subject.player.level().getFluidState(BlockPos.containing(pos)).isEmpty();
	}

	/**
	 * @return how open the space around the player is, 0 is fully enclosed, 1 is an open field
	 */
	public static double openness(Subject subject) {
		double range = 5.0 * subject.unit;
		double sum = 0;
		for (int i = 0; i < 9; i++) {
			// 8 directions around and one straight up
			Vec3 dir = i == 8 ? new Vec3(0, 1, 0) : CamMath.orbit(i * Math.PI / 4.0, 0.15);
			Vec3 end = subject.center.add(dir.scale(range));
			BlockHitResult hit = subject.player.level().clip(new ClipContext(subject.center, end,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, subject.player));
			sum += hit.getType() == HitResult.Type.MISS ? 1.0 :
				hit.getLocation().distanceTo(subject.center) / range;
		}
		return sum / 9.0;
	}
}
