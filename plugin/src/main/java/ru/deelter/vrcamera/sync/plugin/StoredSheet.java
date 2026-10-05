package ru.deelter.vrcamera.sync.plugin;

import java.util.UUID;

/**
 * a photo pinned in a world
 *
 * @param blockX the block it is pinned to
 */
public record StoredSheet(
		long id, UUID world, UUID owner, String ownerName, int blockX, int blockY, int blockZ, double x, double y,
		double z, float qx, float qy, float qz, float qw, float aspect, long imageHash) {

	public ChunkKey chunk() {
		return new ChunkKey(this.world, (int) Math.floor(this.x) >> 4, (int) Math.floor(this.z) >> 4);
	}

	public BlockKey block() {
		return new BlockKey(this.world, this.blockX, this.blockY, this.blockZ);
	}

	public double distanceSquared(double px, double py, double pz) {
		double dx = this.x - px;
		double dy = this.y - py;
		double dz = this.z - pz;
		return dx * dx + dy * dy + dz * dz;
	}

	public record ChunkKey(UUID world, int x, int z) {}

	public record BlockKey(UUID world, int x, int y, int z) {}
}
