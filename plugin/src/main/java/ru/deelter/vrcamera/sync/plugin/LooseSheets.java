package ru.deelter.vrcamera.sync.plugin;

import org.jetbrains.annotations.NotNull;
import ru.deelter.vrcamera.sync.Protocol;

import java.util.*;

/**
 * The sheets that are not pinned: in someone's hand, falling, lying around. They are here so the players around
 * see them and can pick them up, and only for that: in memory, never on disk, a few per player, gone when their
 * owner leaves or after a while.
 */
public final class LooseSheets {

	private final Map<Long, Sheet> byId = new LinkedHashMap<>();
	private final Map<Long, byte[]> images = new HashMap<>();
	private final Map<Long, Integer> imageUses = new HashMap<>();
	private long nextId = 1;

	public Collection<Sheet> all() {
		return byId.values();
	}

	public Sheet get(long id) {
		return byId.get(id);
	}

	public byte[] image(long hash) {
		return images.get(hash);
	}

	public Sheet add(
			UUID world, UUID owner, String ownerName, Protocol.Pose pose, float aspect, long imageHash, byte[] image,
			boolean custom) {
		final Sheet sheet = new Sheet(nextId++, world, owner, ownerName, pose, aspect, imageHash, custom);
		byId.put(sheet.id, sheet);
		images.putIfAbsent(imageHash, image);
		imageUses.merge(imageHash, 1, Integer::sum);
		return sheet;
	}

	public Sheet remove(long id) {
		final Sheet sheet = byId.remove(id);
		if (sheet != null &&
				imageUses.computeIfPresent(sheet.imageHash, (hash, uses) -> uses > 1 ? uses - 1 : null) == null) {
			images.remove(sheet.imageHash);
		}
		return sheet;
	}

	/**
	 * @return the sheets of that player, oldest first
	 */
	public List<Sheet> of(UUID owner) {
		final List<Sheet> sheets = new ArrayList<>();
		for (final Sheet sheet : byId.values()) {
			if (sheet.owner.equals(owner)) {
				sheets.add(sheet);
			}
		}
		return sheets;
	}

	public static final class Sheet {
		public final long id;
		public final UUID world;
		public final float aspect;
		public final long imageHash;
		public final boolean custom;
		public UUID owner;
		public String ownerName;
		public Protocol.Pose pose;

		public long touched = System.currentTimeMillis();

		Sheet(long id, UUID world, UUID owner, String ownerName, Protocol.Pose pose, float aspect, long imageHash,
		      boolean custom) {
			this.custom = custom;
			this.id = id;
			this.world = world;
			this.owner = owner;
			this.ownerName = ownerName;
			this.pose = pose;
			this.aspect = aspect;
			this.imageHash = imageHash;
		}

		@NotNull
		public Protocol.Loose toProtocol() {
			return new Protocol.Loose(id, owner, ownerName, pose, aspect, imageHash,
					custom);
		}

		public double distanceSquared(double x, double y, double z) {
			final double dx = pose.x() - x;
			final double dy = pose.y() - y;
			final double dz = pose.z() - z;
			return dx * dx + dy * dy + dz * dz;
		}
	}
}
