package ru.deelter.vrcamera.sync.plugin;

import ru.deelter.vrcamera.sync.Protocol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The sheets that are not pinned: in someone's hand, falling, lying around. They are here so the players around
 * see them and can pick them up, and only for that: in memory, never on disk, a few per player, gone when their
 * owner leaves or after a while.
 */
public final class LooseSheets {

	public static final class Sheet {
		public final long id;
		public final UUID world;
		public final float aspect;
		public final long imageHash;
		public UUID owner;
		public String ownerName;
		public Protocol.Pose pose;
		// when its owner last said something about it
		public long touched = System.currentTimeMillis();

		Sheet(long id, UUID world, UUID owner, String ownerName, Protocol.Pose pose, float aspect, long imageHash) {
			this.id = id;
			this.world = world;
			this.owner = owner;
			this.ownerName = ownerName;
			this.pose = pose;
			this.aspect = aspect;
			this.imageHash = imageHash;
		}

		public Protocol.Loose toProtocol() {
			return new Protocol.Loose(this.id, this.owner, this.ownerName, this.pose, this.aspect, this.imageHash);
		}

		public double distanceSquared(double x, double y, double z) {
			double dx = this.pose.x() - x;
			double dy = this.pose.y() - y;
			double dz = this.pose.z() - z;
			return dx * dx + dy * dy + dz * dz;
		}
	}

	// in the order they were made, the oldest first
	private final Map<Long, Sheet> byId = new LinkedHashMap<>();
	private final Map<Long, byte[]> images = new HashMap<>();
	private final Map<Long, Integer> imageUses = new HashMap<>();
	private long nextId = 1;

	public Collection<Sheet> all() {
		return this.byId.values();
	}

	public Sheet get(long id) {
		return this.byId.get(id);
	}

	public byte[] image(long hash) {
		return this.images.get(hash);
	}

	public Sheet add(
			UUID world, UUID owner, String ownerName, Protocol.Pose pose, float aspect, long imageHash, byte[] image) {
		Sheet sheet = new Sheet(this.nextId++, world, owner, ownerName, pose, aspect, imageHash);
		this.byId.put(sheet.id, sheet);
		this.images.putIfAbsent(imageHash, image);
		this.imageUses.merge(imageHash, 1, Integer::sum);
		return sheet;
	}

	public Sheet remove(long id) {
		Sheet sheet = this.byId.remove(id);
		if (sheet != null &&
				this.imageUses.computeIfPresent(sheet.imageHash, (hash, uses) -> uses > 1 ? uses - 1 : null) == null)
		{
			this.images.remove(sheet.imageHash);
		}
		return sheet;
	}

	/**
	 * @return the sheets of that player, oldest first
	 */
	public List<Sheet> of(UUID owner) {
		List<Sheet> sheets = new ArrayList<>();
		for (Sheet sheet : this.byId.values()) {
			if (sheet.owner.equals(owner)) {
				sheets.add(sheet);
			}
		}
		return sheets;
	}
}
