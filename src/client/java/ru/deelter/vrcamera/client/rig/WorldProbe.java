package ru.deelter.vrcamera.client.rig;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.desktop.DitheredBlocks;
import ru.deelter.vrcamera.client.math.CamMath;

/**
 * block checks for the camera
 */
public final class WorldProbe {
	private static final int MOST_SOFT_BLOCKS = 48;
	private static final double SOFT_STEP = 0.25;

	/**
	 * @return where the first block is that is in the way from one place to another, null if there is none. Blocks
	 * that are drawn see-through for the camera are not in its way
	 */
	private static Vec3 firstInTheWay(Subject subject, Vec3 from, Vec3 to) {
		final Level level = subject.player.level();
		final Vec3 way = to.subtract(from);
		final double length = way.length();
		Vec3 start = from;
		for (int passed = 0; passed < MOST_SOFT_BLOCKS; passed++) {
			final BlockHitResult hit = level.clip(new ClipContext(start, to, ClipContext.Block.COLLIDER,
					ClipContext.Fluid.NONE, subject.player));
			if (hit.getType() == HitResult.Type.MISS) {
				return null;
			}
			if (!subject.softBlocks || !DitheredBlocks.dithers(level.getBlockState(hit.getBlockPos()))) {
				return hit.getLocation();
			}
			Vec3 behind = hit.getLocation();
			do {
				behind = behind.add(way.scale(SOFT_STEP / length));
			} while (BlockPos.containing(behind).equals(hit.getBlockPos()));
			if (behind.distanceToSqr(from) >= length * length) {
				return null;
			}
			start = behind;
		}
		return null;
	}

	/**
	 * Checks how far a camera can move from {@code from} to {@code to}, without getting into blocks. Works like the
	 * vanilla third person camera, casts a ray for each corner of a small box around the camera.
	 *
	 * @return fraction of the way that is free, 1 if nothing is in the way
	 */
	public static double armFraction(Subject subject, Vec3 from, Vec3 to, CameraConfig config) {
		final double length = from.distanceTo(to);
		if (length < 1.0E-4) {
			return 1.0;
		}

		final double radius = radius(subject, config);
		double free = length;
		for (int i = 0; i < 8; i++) {
			final Vec3 offset = new Vec3(
					((i & 1) * 2 - 1) * radius,
					((i >> 1 & 1) * 2 - 1) * radius,
					((i >> 2 & 1) * 2 - 1) * radius);
			final Vec3 start = from.add(offset);
			final Vec3 hit = firstInTheWay(subject, start, to.add(offset));
			if (hit != null) {
				free = Math.min(free, hit.distanceTo(start));
			}
		}
		double fraction = free >= length ? 1.0 : CamMath.clamp((free - config.collisionMargin) / length, 0.0, 1.0);

		if (blinding(subject, from.lerp(to, fraction)) && !blinding(subject, subject.head)) {
			final double step = 0.5 / length;
			do {
				fraction -= step;
			} while (fraction > 0 && blinding(subject, from.lerp(to, fraction)));
			fraction = Math.max(0.0, fraction);
		}
		return fraction;
	}

	/**
	 * @return if a camera at {@code pos} would only see the inside of what it is in
	 */
	public static boolean blinding(Subject subject, Vec3 pos) {
		final BlockPos blockPos = BlockPos.containing(pos);
		return subject.player.level().getFluidState(blockPos).is(FluidTags.LAVA) ||
				subject.player.level().getBlockState(blockPos).getBlock() == Blocks.POWDER_SNOW;
	}

	/**
	 * @param max furthest to look down
	 * @return blocks of air below the feet of the player, {@code max} if there is no ground in that range
	 */
	public static double groundDistance(Subject subject, double max) {
		final BlockHitResult hit = subject.player.level().clip(new ClipContext(subject.feet,
				subject.feet.add(0, -max, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, subject.player));
		return hit.getType() == HitResult.Type.MISS ? max : subject.feet.y - hit.getLocation().y;
	}

	/**
	 * @return if a camera at {@code pos} would be clear of all blocks
	 */
	public static boolean spotFree(Subject subject, Vec3 pos, CameraConfig config) {
		final double radius = radius(subject, config);
		return subject.player.level().noCollision(new AABB(
				pos.x - radius, pos.y - radius, pos.z - radius,
				pos.x + radius, pos.y + radius, pos.z + radius));
	}

	/**
	 * @return if what is between the two points is at most a block thick, like a tree, post or the edge of a wall
	 */
	public static boolean thin(Subject subject, Vec3 from, Vec3 to) {
		final BlockHitResult front = subject.player.level().clip(new ClipContext(from, to,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, subject.player));
		if (front.getType() == HitResult.Type.MISS) {
			return true;
		}
		final BlockHitResult back = subject.player.level().clip(new ClipContext(to, from,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, subject.player));
		if (back.getType() == HitResult.Type.MISS) {
			return false;
		}
		final double thickness = from.distanceTo(to) - front.getLocation().distanceTo(from) -
				back.getLocation().distanceTo(to);
		return thickness <= 1.05;
	}

	/**
	 * @return how far something small can get from {@code from} towards {@code to}, stops short of the first block
	 */
	public static Vec3 reach(LocalPlayer player, Vec3 from, Vec3 to) {
		final BlockHitResult hit = player.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, player));
		if (hit.getType() == HitResult.Type.MISS) {
			return to;
		}
		final double free = Math.max(0.0, hit.getLocation().distanceTo(from) - 0.3);
		return from.add(to.subtract(from).normalize().scale(free));
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
		final double range = 5.0 * subject.unit;
		double sum = 0;
		for (int i = 0; i < 9; i++) {
			final Vec3 dir = i == 8 ? new Vec3(0, 1, 0) : CamMath.orbit(i * Math.PI / 4.0, 0.15);
			final Vec3 end = subject.center.add(dir.scale(range));
			final BlockHitResult hit = subject.player.level().clip(new ClipContext(subject.center, end,
					ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, subject.player));
			sum += hit.getType() == HitResult.Type.MISS ? 1.0 :
					hit.getLocation().distanceTo(subject.center) / range;
		}
		return sum / 9.0;
	}

	private static double radius(Subject subject, CameraConfig config) {
		return Math.min(config.collisionRadius, 0.2 * subject.unit);
	}
}
