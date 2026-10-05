package ru.deelter.vrcamera.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ru.deelter.vrcamera.client.math.CamMath;
import ru.deelter.vrcamera.client.rig.DroppedCamera;

import java.util.Random;

/**
 * particles and sounds around the camera. All of it only exists on this client
 */
public final class CameraEffects {
	private static final Random RANDOM = new Random();

	/**
	 * Dust where the camera hit something, of the block that was hit, and the sound of it.
	 * <p>
	 * Particles are part of the world and can not be hidden from the camera, so they are only put behind its lens,
	 * out of its view. The camera can still turn around to them, but most do not live that long.
	 *
	 * @param lens direction the camera looks in
	 */
	public static void impact(Level level, DroppedCamera.Impact impact, Vec3 lens) {
		ParticleOptions particle = ParticleTypes.POOF;
		if (impact.block() != null) {
			BlockState state = level.getBlockState(impact.block());
			if (!state.isAir()) {
				particle = new BlockParticleOption(ParticleTypes.BLOCK, state);
				Vec3 at = impact.position();
				level.playLocalSound(at.x, at.y, at.z, state.getSoundType().getHitSound(), SoundSource.PLAYERS,
						(float) CamMath.clamp(impact.speed() * 0.08, 0.15, 0.8), 1.2F, false);
			}
		}
		int count = (int) CamMath.clamp(2.0 + impact.speed() * 1.2, 3.0, 12.0);
		for (int i = 0; i < count; i++) {
			Vec3 direction = new Vec3(RANDOM.nextDouble() - 0.5, RANDOM.nextDouble() - 0.5, RANDOM.nextDouble() - 0.5)
					.normalize();
			double ahead = direction.dot(lens);
			if (ahead > 0) {
				// mirror it to behind the lens
				direction = direction.subtract(lens.scale(2.0 * ahead));
			}
			Vec3 position = impact.position().add(direction.scale(0.15));
			Vec3 velocity = direction.scale(0.8).add(impact.normal().scale(0.6));
			level.addParticle(particle, position.x, position.y, position.z, velocity.x, velocity.y, velocity.z);
		}
	}

	public static void shutter(LocalPlayer player) {
		player.playSound(SoundEvents.LEVER_CLICK, 0.6F, 1.9F);
		player.playSound(SoundEvents.BOOK_PAGE_TURN, 0.5F, 1.2F);
	}

	public static void pinned(Level level, Vec3 position) {
		level.playLocalSound(position.x, position.y, position.z, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.PLAYERS,
				0.6F, 1.3F, false);
	}

	public static void burned(Level level, Vec3 position) {
		level.playLocalSound(position.x, position.y, position.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS,
				0.3F, 1.6F, false);
		for (int i = 0; i < 6; i++) {
			level.addParticle(i % 2 == 0 ? ParticleTypes.FLAME : ParticleTypes.SMOKE,
					position.x + (RANDOM.nextDouble() - 0.5) * 0.15, position.y + RANDOM.nextDouble() * 0.1,
					position.z + (RANDOM.nextDouble() - 0.5) * 0.15, 0, 0.03, 0);
		}
	}

	public static boolean inWater(Level level, Vec3 position) {
		return level.getFluidState(BlockPos.containing(position)).is(FluidTags.WATER);
	}

	public static void splash(Level level, Vec3 position, Vec3 lens) {
		level.playLocalSound(position.x, position.y, position.z, SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS,
				0.25F, 1.4F, false);
		for (int i = 0; i < 8; i++) {
			bubble(level, position, lens);
		}
	}

	/**
	 * behind the lens like the dust, a bubble right in front of it would fill the picture
	 */
	public static void bubble(Level level, Vec3 position, Vec3 lens) {
		Vec3 offset = new Vec3(RANDOM.nextDouble() - 0.5, RANDOM.nextDouble() - 0.5, RANDOM.nextDouble() - 0.5)
				.scale(0.3);
		double ahead = offset.dot(lens);
		if (ahead > 0) {
			offset = offset.subtract(lens.scale(2.0 * ahead));
		}
		level.addParticle(ParticleTypes.BUBBLE, position.x + offset.x, position.y + offset.y, position.z + offset.z,
				0, 0.1, 0);
	}

	/**
	 * the camera starts to fly to the hand
	 */
	public static void pullStart(LocalPlayer player) {
		player.playSound(SoundEvents.ITEM_PICKUP, 0.6F, 0.7F);
	}

	/**
	 * a trail behind the camera while it flies to the hand
	 */
	public static void pullTrail(Level level, Vec3 position) {
		level.addParticle(ParticleTypes.ELECTRIC_SPARK,
				position.x + (RANDOM.nextDouble() - 0.5) * 0.1,
				position.y + (RANDOM.nextDouble() - 0.5) * 0.1,
				position.z + (RANDOM.nextDouble() - 0.5) * 0.1, 0, 0, 0);
	}

	/**
	 * the camera landed in the hand
	 */
	public static void pullArrive(LocalPlayer player, Vec3 position) {
		player.playSound(SoundEvents.ITEM_PICKUP, 0.8F, 1.3F);
		for (int i = 0; i < 6; i++) {
			player.level().addParticle(ParticleTypes.ELECTRIC_SPARK, position.x, position.y, position.z,
					(RANDOM.nextDouble() - 0.5) * 0.6, (RANDOM.nextDouble() - 0.5) * 0.6, (RANDOM.nextDouble() - 0.5) * 0.6);
		}
	}
}
