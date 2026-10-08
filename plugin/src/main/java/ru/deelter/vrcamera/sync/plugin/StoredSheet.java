package ru.deelter.vrcamera.sync.plugin;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * a photo pinned in a world
 *
 * @param blockX the block it is pinned to
 * @param custom if its owner said it is a picture from somewhere else, not a photo taken in the game
 */
public record StoredSheet(
		long id, UUID world, UUID owner, String ownerName, int blockX, int blockY, int blockZ, double x, double y,
		double z, float qx, float qy, float qz, float qw, float aspect, long imageHash, boolean custom) {

	@NotNull
	public ChunkKey chunk() {
		return new ChunkKey(world, (int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
	}

	@NotNull
	public BlockKey block() {
		return new BlockKey(world, blockX, blockY, blockZ);
	}

	public double distanceSquared(double px, double py, double pz) {
		final double dx = x - px;
		final double dy = y - py;
		final double dz = z - pz;
		return dx * dx + dy * dy + dz * dz;
	}

	public record ChunkKey(UUID world, int x, int z) {
	}

	public record BlockKey(UUID world, int x, int y, int z) {
	}
}
