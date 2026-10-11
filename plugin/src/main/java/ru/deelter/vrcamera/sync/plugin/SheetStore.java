package ru.deelter.vrcamera.sync.plugin;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.file.DirectoryStream;
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
	/**
	 * how picture files end. Kept for the ones that are a PNG too: the name says nothing, what is in it does
	 */
	private static final String IMAGE_KIND = ".jpg";
	private static final int FILE_VERSION = 2;

	private static final int CACHED_IMAGES = 256;

	private final Logger logger;
	private final Path listFile;
	private final Path backupFile;
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
		listFile = dataDir.resolve("sheets.dat");
		backupFile = dataDir.resolve("sheets.dat.bak");
		imageDir = dataDir.resolve("images");
	}

	private static <K> void drop(Map<K, List<StoredSheet>> index, K key, StoredSheet sheet) {
		final List<StoredSheet> sheets = index.get(key);
		if (sheets != null) {
			sheets.remove(sheet);
			if (sheets.isEmpty()) {
				index.remove(key);
			}
		}
	}

	public int size() {
		return byId.size();
	}

	public Collection<StoredSheet> all() {
		return byId.values();
	}

	public StoredSheet get(long id) {
		return byId.get(id);
	}

	public List<StoredSheet> inChunk(StoredSheet.ChunkKey chunk) {
		return byChunk.getOrDefault(chunk, List.of());
	}

	public List<StoredSheet> onBlock(StoredSheet.BlockKey block) {
		return byBlock.getOrDefault(block, List.of());
	}

	public int ownedBy(UUID player) {
		return owned.getOrDefault(player, 0);
	}

	public long newId() {
		return nextId++;
	}

	public boolean isDirty() {
		return dirty;
	}

	public void add(StoredSheet sheet) {
		index(sheet);
		dirty = true;
	}

	/**
	 * @return the hash of a picture nothing uses anymore and that can be deleted, 0 if it is still used
	 */
	public long remove(StoredSheet sheet) {
		if (byId.remove(sheet.id()) == null) {
			return 0;
		}
		drop(byChunk, sheet.chunk(), sheet);
		drop(byBlock, sheet.block(), sheet);
		owned.computeIfPresent(sheet.owner(), (owner, count) -> count > 1 ? count - 1 : null);
		dirty = true;
		final Integer left = imageUses.computeIfPresent(sheet.imageHash(),
				(hash, count) -> count > 1 ? count - 1 : null);
		if (left != null) {
			return 0;
		}
		imageCache.remove(sheet.imageHash());
		return sheet.imageHash();
	}

	public boolean hasImage(long hash) {
		return imageUses.containsKey(hash);
	}

	/**
	 * @return the picture, null if it is not there
	 */
	@Nullable
	public byte[] image(long hash) {
		final byte[] cached = imageCache.get(hash);
		if (cached != null) {
			return cached;
		}
		try {
			final byte[] image = Files.readAllBytes(imageFile(hash));
			imageCache.put(hash, image);
			return image;
		} catch (IOException e) {
			return null;
		}
	}

	public void cacheImage(long hash, byte[] image) {
		imageCache.put(hash, image);
	}

	/**
	 * may be called from any thread
	 */
	public void writeImage(long hash, byte[] image) throws IOException {
		Files.createDirectories(imageDir);
		final Path file = imageFile(hash);
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
			logger.log(Level.WARNING, "Can't remove the picture " + Long.toHexString(hash), e);
		}
	}

	/**
	 * Reads the list. One that can't be read is never written over: it is put aside under another name, and the
	 * copy of the save before it is tried instead.
	 */
	public void load() {
		if (!Files.isRegularFile(listFile) && !Files.isRegularFile(backupFile)) {
			return;
		}
		if (Files.isRegularFile(listFile)) {
			try {
				read(listFile);
				sweep();
				return;
			} catch (IOException e) {
				logger.log(Level.SEVERE, "Can't read " + listFile, e);
				putAside();
			}
		}
		if (!Files.isRegularFile(backupFile)) {
			logger.severe("Starting without pinned photos");
			return;
		}
		try {
			read(backupFile);

			dirty = true;
			logger.warning("Loaded " + size() + " pinned photos from the copy " + backupFile.getFileName() +
					", what was pinned after it was written is lost");
		} catch (IOException e) {
			logger.log(Level.SEVERE, "Can't read " + backupFile + " either, starting without pinned photos",
					e);
		}
	}

	/**
	 * Removes the pictures no pinned photo uses: left by a server that stopped before the list was written or
	 * before a picture was deleted. Only after the list was read whole, or every picture would look unused.
	 */
	private void sweep() {
		if (!Files.isDirectory(imageDir)) {
			return;
		}
		int removed = 0;
		try (final DirectoryStream<Path> files = Files.newDirectoryStream(imageDir, "*" + IMAGE_KIND)) {
			for (final Path file : files) {
				final long hash = hashOf(file.getFileName().toString());
				if (hash != 0 && !imageUses.containsKey(hash) && Files.deleteIfExists(file)) {
					removed++;
				}
			}
		} catch (IOException | RuntimeException e) {
			logger.log(Level.WARNING, "Can't look for unused pictures in " + imageDir, e);
		}
		if (removed > 0) {
			logger.info(removed + " pictures no pinned photo uses were removed");
		}
	}

	/**
	 * @return the hash a picture file is named after, 0 if it is not one of these files
	 */
	private static long hashOf(String fileName) {
		try {
			return Long.parseUnsignedLong(fileName.substring(0, fileName.length() - IMAGE_KIND.length()), 16);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/**
	 * @return what has to be written, taken on the server thread to be written on another one
	 */
	@NotNull
	public List<StoredSheet> snapshot() {
		dirty = false;
		return new ArrayList<>(byId.values());
	}

	public long nextIdSnapshot() {
		return nextId;
	}

	/**
	 * may be called from any thread, with what {@link #snapshot} gave
	 */
	public void save(List<StoredSheet> sheets, long nextId) {
		try {
			Files.createDirectories(listFile.getParent());

			final Path temp = listFile.resolveSibling("sheets.dat.tmp");
			final OutputStream file = new BufferedOutputStream(Files.newOutputStream(temp));
			try (final DataOutputStream out = new DataOutputStream(file)) {
				out.writeInt(FILE_VERSION);
				out.writeLong(nextId);
				out.writeInt(sheets.size());
				for (final StoredSheet sheet : sheets) {
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
			if (Files.isRegularFile(listFile)) {
				Files.copy(listFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
			}
			Files.move(temp, listFile, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			logger.log(Level.SEVERE, "Can't save the pinned photos to " + listFile, e);
		}
	}

	private void index(StoredSheet sheet) {
		byId.put(sheet.id(), sheet);
		byChunk.computeIfAbsent(sheet.chunk(), key -> new ArrayList<>()).add(sheet);
		byBlock.computeIfAbsent(sheet.block(), key -> new ArrayList<>()).add(sheet);
		owned.merge(sheet.owner(), 1, Integer::sum);
		imageUses.merge(sheet.imageHash(), 1, Integer::sum);
	}

	private Path imageFile(long hash) {
		return imageDir.resolve(Long.toHexString(hash) + IMAGE_KIND);
	}

	private void putAside() {
		final Path aside = listFile.resolveSibling("sheets.dat.broken-" + System.currentTimeMillis());
		try {
			Files.move(listFile, aside);
			logger.severe("It was kept as " + aside.getFileName());
		} catch (IOException e) {
			logger.log(Level.SEVERE, "Can't put it aside, it will be written over", e);
		}
	}

	private void read(Path file) throws IOException {
		try (final DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			final int version = in.readInt();
			if (version < 1 || version > FILE_VERSION) {
				throw new IOException("unknown file version " + version);
			}
			final long nextId = in.readLong();
			final int count = in.readInt();
			if (count < 0) {
				throw new IOException("a list of " + count + " photos");
			}

			final List<StoredSheet> sheets = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				sheets.add(new StoredSheet(in.readLong(), new UUID(in.readLong(), in.readLong()),
						new UUID(in.readLong(), in.readLong()), in.readUTF(), in.readInt(), in.readInt(), in.readInt(),
						in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat(),
						in.readFloat(), in.readFloat(), in.readFloat(), in.readLong(),
						version >= 2 && in.readBoolean()));
			}
			this.nextId = nextId;
			int missing = 0;
			for (final StoredSheet sheet : sheets) {
				if (Files.isRegularFile(imageFile(sheet.imageHash()))) {
					index(sheet);
				} else {
					missing++;
				}
			}
			if (missing > 0) {
				logger.warning(missing + " pinned photos were dropped, their pictures are gone");
				dirty = true;
			}
		}
	}
}
