package ru.deelter.vrcamera.sync.plugin;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * All pinned photos of the server, and where they are on disk. Only used from the server thread, except for what
 * says otherwise.
 * <p>
 * The list is one small file. Pictures are files of their own, named by their hash: the same picture pinned twice
 * is stored once.
 */
public final class SheetStore {
	// 2: sheets say if they are custom pictures
	private static final int FILE_VERSION = 2;
	// pictures kept in memory, the ones asked for last. About 10 KB each
	private static final int CACHED_IMAGES = 256;

	private final Logger logger;
	private final Path listFile;
	private final Path imageDir;

	private final Map<Long, StoredSheet> byId = new HashMap<>();
	private final Map<StoredSheet.ChunkKey, List<StoredSheet>> byChunk = new HashMap<>();
	private final Map<StoredSheet.BlockKey, List<StoredSheet>> byBlock = new HashMap<>();
	private final Map<UUID, Integer> owned = new HashMap<>();
	private final Map<Long, Integer> imageUses = new HashMap<>();
	private final Map<Long, byte[]> imageCache = new LinkedHashMap<>(64, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
			return size() > CACHED_IMAGES;
		}
	};
	private long nextId = 1;
	private boolean dirty;

	public SheetStore(Path dataDir, Logger logger) {
		this.logger = logger;
		this.listFile = dataDir.resolve("sheets.dat");
		this.imageDir = dataDir.resolve("images");
	}

	public int size() {
		return this.byId.size();
	}

	public Collection<StoredSheet> all() {
		return this.byId.values();
	}

	public StoredSheet get(long id) {
		return this.byId.get(id);
	}

	public List<StoredSheet> inChunk(StoredSheet.ChunkKey chunk) {
		return this.byChunk.getOrDefault(chunk, List.of());
	}

	public List<StoredSheet> onBlock(StoredSheet.BlockKey block) {
		return this.byBlock.getOrDefault(block, List.of());
	}

	public int ownedBy(UUID player) {
		return this.owned.getOrDefault(player, 0);
	}

	public long newId() {
		return this.nextId++;
	}

	public boolean isDirty() {
		return this.dirty;
	}

	public void add(StoredSheet sheet) {
		index(sheet);
		this.dirty = true;
	}

	private void index(StoredSheet sheet) {
		this.byId.put(sheet.id(), sheet);
		this.byChunk.computeIfAbsent(sheet.chunk(), key -> new ArrayList<>()).add(sheet);
		this.byBlock.computeIfAbsent(sheet.block(), key -> new ArrayList<>()).add(sheet);
		this.owned.merge(sheet.owner(), 1, Integer::sum);
		this.imageUses.merge(sheet.imageHash(), 1, Integer::sum);
	}

	/**
	 * @return the hash of a picture nothing uses anymore and that can be deleted, 0 if it is still used
	 */
	public long remove(StoredSheet sheet) {
		if (this.byId.remove(sheet.id()) == null) {
			return 0;
		}
		drop(this.byChunk, sheet.chunk(), sheet);
		drop(this.byBlock, sheet.block(), sheet);
		this.owned.computeIfPresent(sheet.owner(), (owner, count) -> count > 1 ? count - 1 : null);
		this.dirty = true;
		Integer left = this.imageUses.computeIfPresent(sheet.imageHash(), (hash, count) -> count > 1 ? count - 1 : null);
		if (left != null) {
			return 0;
		}
		this.imageCache.remove(sheet.imageHash());
		return sheet.imageHash();
	}

	private static <K> void drop(Map<K, List<StoredSheet>> index, K key, StoredSheet sheet) {
		List<StoredSheet> sheets = index.get(key);
		if (sheets != null) {
			sheets.remove(sheet);
			if (sheets.isEmpty()) {
				index.remove(key);
			}
		}
	}

	private Path imageFile(long hash) {
		return this.imageDir.resolve(Long.toHexString(hash) + ".jpg");
	}

	public boolean hasImage(long hash) {
		return this.imageUses.containsKey(hash);
	}

	/**
	 * @return the picture, null if it is not there
	 */
	public byte[] image(long hash) {
		byte[] cached = this.imageCache.get(hash);
		if (cached != null) {
			return cached;
		}
		try {
			byte[] image = Files.readAllBytes(imageFile(hash));
			this.imageCache.put(hash, image);
			return image;
		} catch (IOException e) {
			return null;
		}
	}

	public void cacheImage(long hash, byte[] image) {
		this.imageCache.put(hash, image);
	}

	/**
	 * may be called from any thread
	 */
	public void writeImage(long hash, byte[] image) throws IOException {
		Files.createDirectories(this.imageDir);
		Path file = imageFile(hash);
		if (!Files.exists(file)) {
			Files.write(file, image);
		}
	}

	/**
	 * may be called from any thread
	 */
	public void deleteImage(long hash) {
		try {
			Files.deleteIfExists(imageFile(hash));
		} catch (IOException e) {
			this.logger.log(Level.WARNING, "Can't remove the picture " + Long.toHexString(hash), e);
		}
	}

	public void load() {
		if (!Files.isRegularFile(this.listFile)) {
			return;
		}
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(this.listFile)))) {
			int version = in.readInt();
			if (version < 1 || version > FILE_VERSION) {
				throw new IOException("unknown file version " + version);
			}
			this.nextId = in.readLong();
			int count = in.readInt();
			int missing = 0;
			for (int i = 0; i < count; i++) {
				StoredSheet sheet = new StoredSheet(in.readLong(), new UUID(in.readLong(), in.readLong()),
						new UUID(in.readLong(), in.readLong()), in.readUTF(), in.readInt(), in.readInt(), in.readInt(),
						in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat(),
						in.readFloat(), in.readFloat(), in.readFloat(), in.readLong(),
						version >= 2 && in.readBoolean());
				// a photo without its picture is nothing to show
				if (Files.isRegularFile(imageFile(sheet.imageHash()))) {
					index(sheet);
				} else {
					missing++;
				}
			}
			if (missing > 0) {
				this.logger.warning(missing + " pinned photos were dropped, their pictures are gone");
				this.dirty = true;
			}
		} catch (IOException e) {
			this.logger.log(Level.SEVERE, "Can't read " + this.listFile + ", starting without pinned photos", e);
		}
	}

	/**
	 * @return what has to be written, taken on the server thread to be written on another one
	 */
	public List<StoredSheet> snapshot() {
		this.dirty = false;
		return new ArrayList<>(this.byId.values());
	}

	public long nextIdSnapshot() {
		return this.nextId;
	}

	/**
	 * may be called from any thread, with what {@link #snapshot} gave
	 */
	public void save(List<StoredSheet> sheets, long nextId) {
		try {
			Files.createDirectories(this.listFile.getParent());
			// written next to it first, a crash in the middle must not leave half a list
			Path temp = this.listFile.resolveSibling("sheets.dat.tmp");
			try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
				out.writeInt(FILE_VERSION);
				out.writeLong(nextId);
				out.writeInt(sheets.size());
				for (StoredSheet sheet : sheets) {
					out.writeLong(sheet.id());
					out.writeLong(sheet.world().getMostSignificantBits());
					out.writeLong(sheet.world().getLeastSignificantBits());
					out.writeLong(sheet.owner().getMostSignificantBits());
					out.writeLong(sheet.owner().getLeastSignificantBits());
					out.writeUTF(sheet.ownerName());
					out.writeInt(sheet.blockX());
					out.writeInt(sheet.blockY());
					out.writeInt(sheet.blockZ());
					out.writeDouble(sheet.x());
					out.writeDouble(sheet.y());
					out.writeDouble(sheet.z());
					out.writeFloat(sheet.qx());
					out.writeFloat(sheet.qy());
					out.writeFloat(sheet.qz());
					out.writeFloat(sheet.qw());
					out.writeFloat(sheet.aspect());
					out.writeLong(sheet.imageHash());
					out.writeBoolean(sheet.custom());
				}
			}
			Files.move(temp, this.listFile, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			this.logger.log(Level.SEVERE, "Can't save the pinned photos to " + this.listFile, e);
		}
	}
}
