package ru.deelter.vrcamera.client.photo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
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
	// Names the world a cache folder belongs to. The folder name itself can't, not every world name is a file name
	private static final String OWNER_FILE = "world.txt";
	private static final String PINNED_FILE = "pinned.json";
	private static final String LOCAL = "local:";
	private static final String SERVER = "server:";

	/**
	 * a sheet pinned to a block, as it is written to disk
	 */
	public static final class Pinned {
		public String file;
		public String dimension;
		public double x, y, z;
		public float qx, qy, qz, qw;
		public float aspect;
	}

	private PhotoStore() {
	}

	private static Path gameDir() {
		return Minecraft.getInstance().gameDirectory.toPath();
	}

	private static Path cacheRoot() {
		return gameDir().resolve("vrcamera").resolve("sheets");
	}

	/**
	 * @return a file for a photo taken now, that does not exist yet
	 */
	public static Path newPhoto() throws IOException {
		Path dir = gameDir().resolve("screenshots").resolve("vrcamera");
		Files.createDirectories(dir);
		String name = LocalDateTime.now().format(FILE_TIME);
		Path file = dir.resolve(name + ".png");
		for (int i = 2; Files.exists(file); i++) {
			file = dir.resolve(name + "_" + i + ".png");
		}
		return file;
	}

	/**
	 * @return the cache folder of the world the player is in, which may not exist yet
	 */
	public static Path worldCache() {
		String owner = currentWorld();
		// with a hash, two names can end up the same once what is not allowed in a file name is taken out
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

	private static String currentWorld() {
		Minecraft mc = Minecraft.getInstance();
		IntegratedServer local = mc.getSingleplayerServer();
		if (local != null) {
			return LOCAL + local.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
		}
		ServerData server = mc.getCurrentServer();
		return SERVER + (server == null ? "unknown" : server.ip);
	}

	public static List<Pinned> loadPinned(Path cache) {
		Path file = cache.resolve(PINNED_FILE);
		if (!Files.isRegularFile(file)) {
			return new ArrayList<>();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			List<Pinned> pinned = GSON.fromJson(reader, new TypeToken<List<Pinned>>() {}.getType());
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
			try (Writer writer = Files.newBufferedWriter(cache.resolve(PINNED_FILE), StandardCharsets.UTF_8)) {
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
		try (Stream<Path> files = Files.list(cache)) {
			for (Path file : files.toList()) {
				String name = file.getFileName().toString();
				// not what was printed since, this runs next to the game
				if (name.endsWith(".png") && !keep.contains(name) &&
						Files.getLastModifiedTime(file).compareTo(before) < 0)
				{
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
	 * Removes the caches of singleplayer worlds that are gone. A server can't be told from one that is just not
	 * joined right now, so those stay.
	 */
	public static void removeDeletedWorlds() {
		Path root = cacheRoot();
		if (!Files.isDirectory(root)) {
			return;
		}
		List<Path> caches;
		try (Stream<Path> dirs = Files.list(root)) {
			caches = dirs.filter(Files::isDirectory).toList();
		} catch (IOException e) {
			Vrcamera.LOGGER.warn("VRCamera: can't look through {}", root, e);
			return;
		}
		Path saves = gameDir().resolve("saves");
		for (Path cache : caches) {
			try {
				Path ownerFile = cache.resolve(OWNER_FILE);
				if (!Files.isRegularFile(ownerFile)) {
					continue;
				}
				String owner = Files.readString(ownerFile, StandardCharsets.UTF_8).trim();
				if (owner.startsWith(LOCAL) && !Files.isDirectory(saves.resolve(owner.substring(LOCAL.length())))) {
					deleteTree(cache);
					Vrcamera.LOGGER.info("VRCamera: removed the photo sheets of the deleted world {}", owner);
				}
			} catch (IOException | RuntimeException e) {
				Vrcamera.LOGGER.warn("VRCamera: can't clean up {}", cache, e);
			}
		}
	}

	private static void deleteTree(Path dir) throws IOException {
		try (Stream<Path> files = Files.walk(dir)) {
			for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(file);
			}
		}
	}
}
