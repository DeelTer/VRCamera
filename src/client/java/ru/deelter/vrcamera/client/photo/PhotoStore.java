package ru.deelter.vrcamera.client.photo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;
import ru.deelter.vrcamera.Vrcamera;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Where photos go on disk.
 * <p>
 * The photos themselves are the player's and are never removed: {@code screenshots/vrcamera}. What the sheets in a
 * world need, small copies of the photos and where the pinned ones hang, is a cache per world in
 * {@code vrcamera/sheets}, and goes when the world does.
 */
public final class PhotoStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

	private static final String OWNER_FILE = "world.txt";
	private static final String PINNED_FILE = "pinned.json";
	private static final String LOCAL = "local:";
	private static final String SERVER = "server:";

	private static final int REMOTE_FILES = 1000;

	private PhotoStore() {
	}

	/**
	 * @return a file for a photo taken now, that does not exist yet
	 */
	public static Path newPhoto() throws IOException {
		final Path dir = gameDir().resolve("screenshots").resolve("vrcamera");
		Files.createDirectories(dir);
		final String name = LocalDateTime.now().format(FILE_TIME);
		Path file = dir.resolve(name + ".png");
		for (int i = 2; Files.exists(file); i++) {
			file = dir.resolve(name + "_" + i + ".png");
		}
		return file;
	}

	/**
	 * keeps a picture that was loaded from the internet the way it came, next to the photos
	 */
	public static void saveCustom(byte[] original, String format) {
		try {
			final Path dir = gameDir().resolve("screenshots").resolve("vrcamera").resolve("custom");
			Files.createDirectories(dir);
			final String name = LocalDateTime.now().format(FILE_TIME);

			Path file = dir.resolve(name + "." + format.replaceAll("[^a-z0-9]", ""));
			for (int i = 2; Files.exists(file); i++) {
				file = dir.resolve(name + "_" + i + "." + format.replaceAll("[^a-z0-9]", ""));
			}
			Files.write(file, original);
		} catch (IOException | RuntimeException e) {
			Vrcamera.LOGGER.warn("VRCamera: can't keep the loaded picture", e);
		}
	}

	/**
	 * @return the cache folder of the world the player is in, which may not exist yet
	 */
	public static Path worldCache() {
		final String owner = currentWorld();

		return cacheRoot().resolve(owner.replaceAll("[^A-Za-z0-9._-]", "_") + "-" +
				Integer.toHexString(owner.hashCode()));
	}

	/**
	 * makes sure the cache folder is there and says which world it belongs to
	 */
	public static void prepare(Path cache) throws IOException {
		if (!Files.isDirectory(cache)) {
			Files.createDirectories(cache);
			Files.writeString(cache.resolve(OWNER_FILE), currentWorld(), StandardCharsets.UTF_8);
		}
	}

	public static List<Pinned> loadPinned(Path cache) {
		final Path file = cache.resolve(PINNED_FILE);
		if (!Files.isRegularFile(file)) {
			return new ArrayList<>();
		}
		try (final Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			final List<Pinned> pinned = GSON.fromJson(reader, new TypeToken<List<Pinned>>() {
			}.getType());
			if (pinned == null) {
				return new ArrayList<>();
			}
			pinned.removeIf(sheet -> sheet == null || sheet.file == null || sheet.dimension == null);
			return pinned;
		} catch (IOException | JsonParseException e) {
			Vrcamera.LOGGER.warn("VRCamera: can't read {}, the pinned photos of this world are lost", file, e);
			return new ArrayList<>();
		}
	}

	public static void savePinned(Path cache, List<Pinned> pinned) {
		try {
			prepare(cache);
			try (final Writer writer = Files.newBufferedWriter(cache.resolve(PINNED_FILE), StandardCharsets.UTF_8)) {
				GSON.toJson(pinned, writer);
			}
		} catch (IOException e) {
			Vrcamera.LOGGER.warn("VRCamera: can't save the pinned photos to {}", cache, e);
		}
	}

	/**
	 * Removes the small pictures nothing refers to: sheets that were left lying when the game closed or crashed.
	 */
	public static void removeStrays(Path cache, Set<String> keep, FileTime before) {
		if (!Files.isDirectory(cache)) {
			return;
		}
		try (final Stream<Path> files = Files.list(cache)) {
			for (final Path file : files.toList()) {
				final String name = file.getFileName().toString();

				if (name.endsWith(".png") && !keep.contains(name) &&
						Files.getLastModifiedTime(file).compareTo(before) < 0) {
					Files.deleteIfExists(file);
				}
			}
		} catch (IOException e) {
			Vrcamera.LOGGER.warn("VRCamera: can't clean up {}", cache, e);
		}
	}

	public static void delete(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			Vrcamera.LOGGER.warn("VRCamera: can't remove {}", file, e);
		}
	}

	/**
	 * @return a picture a server sent before, null if it is not on disk
	 */
	@Nullable
	public static byte[] readRemote(long hash) {
		try {
			final Path file = remoteFile(hash);
			return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	public static void writeRemote(long hash, byte[] image) {
		try {
			final Path file = remoteFile(hash);
			Files.createDirectories(file.getParent());
			Files.write(file, image);
		} catch (IOException | RuntimeException e) {
			Vrcamera.LOGGER.warn("VRCamera: can't keep a photo of the server on disk", e);
		}
	}

	/**
	 * Keeps the pictures of servers from piling up: only the ones used last stay.
	 */
	public static void trimRemote() {
		final Path dir = gameDir().resolve("vrcamera").resolve("remote");
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (final Stream<Path> files = Files.list(dir)) {
			final List<Path> oldestLast = files.filter(file -> file.toString().endsWith(".jpg"))
					.sorted(Comparator.comparingLong((Path file) -> file.toFile().lastModified()).reversed())
					.toList();
			for (final Path file : oldestLast.subList(Math.min(REMOTE_FILES, oldestLast.size()), oldestLast.size())) {
				Files.deleteIfExists(file);
			}
		} catch (IOException | RuntimeException e) {
			Vrcamera.LOGGER.warn("VRCamera: can't clean up {}", dir, e);
		}
	}

	/**
	 * Removes the caches of singleplayer worlds that are gone. A server can't be told from one that is just not
	 * joined right now, so those stay.
	 */
	public static void removeDeletedWorlds() {
		final Path root = cacheRoot();
		if (!Files.isDirectory(root)) {
			return;
		}
		List<Path> caches;
		try (final Stream<Path> dirs = Files.list(root)) {
			caches = dirs.filter(Files::isDirectory).toList();
		} catch (IOException e) {
			Vrcamera.LOGGER.warn("VRCamera: can't look through {}", root, e);
			return;
		}
		final Path saves = gameDir().resolve("saves");
		for (final Path cache : caches) {
			try {
				final Path ownerFile = cache.resolve(OWNER_FILE);
				if (!Files.isRegularFile(ownerFile)) {
					continue;
				}
				final String owner = Files.readString(ownerFile, StandardCharsets.UTF_8).trim();
				if (owner.startsWith(LOCAL) && !Files.isDirectory(saves.resolve(owner.substring(LOCAL.length())))) {
					deleteTree(cache);
					Vrcamera.LOGGER.info("VRCamera: removed the photo sheets of the deleted world {}", owner);
				}
			} catch (IOException | RuntimeException e) {
				Vrcamera.LOGGER.warn("VRCamera: can't clean up {}", cache, e);
			}
		}
	}

	private static Path gameDir() {
		return Minecraft.getInstance().gameDirectory.toPath();
	}

	private static Path cacheRoot() {
		return gameDir().resolve("vrcamera").resolve("sheets");
	}

	private static String currentWorld() {
		final Minecraft mc = Minecraft.getInstance();
		final IntegratedServer local = mc.getSingleplayerServer();
		if (local != null) {
			return LOCAL + local.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
		}
		final ServerData server = mc.getCurrentServer();
		return SERVER + (server == null ? "unknown" : server.ip);
	}

	private static Path remoteFile(long hash) {
		return gameDir().resolve("vrcamera").resolve("remote").resolve(Long.toHexString(hash) + ".jpg");
	}

	private static void deleteTree(Path dir) throws IOException {
		try (final Stream<Path> files = Files.walk(dir)) {
			for (final Path file : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(file);
			}
		}
	}

	/**
	 * a sheet pinned to a block, as it is written to disk
	 */
	public static final class Pinned {
		public String file;
		public String dimension;
		public double x, y, z;
		public float qx, qy, qz, qw;
		public float aspect;
		public boolean custom;
	}
}
