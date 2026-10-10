package ru.deelter.vrcamera.client.photo;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.config.CameraConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The colours a photo on a sheet is made of, if the player wants other ones than those of a map of the game.
 * Each palette is a file in {@code config/vrcamera/palettes}, the way lospec.com hands them out: HEX, GIMP GPL,
 * JASC PAL or Paint.net TXT. One can be fetched from there by its link.
 * <p>
 * The photographer picks: the others get the sheet in the colours it was printed in.
 */
public final class Palettes {
	/**
	 * no palette of the player: the colours of a map
	 */
	public static final String MAP_COLORS = "";

	private static final Path FOLDER = FabricLoader.getInstance().getConfigDir().resolve("vrcamera").resolve("palettes");
	private static final String[] KINDS = {".hex", ".gpl", ".pal", ".txt"};
	public static final String BUILT_IN = "justparchment8";
	private static final String SITE = "https://lospec.com/palette-list";
	/**
	 * the palettes the folder starts with, from lospec.com: JustParchment8 by JustJimmy, Carob Treat by
	 * SurrealEmber, Gothic Bit by HiroHi and smoky 09 by green guy
	 */
	private static final Map<String, List<String>> STARTERS = Map.of(
			BUILT_IN, List.of("292418", "524839", "73654a", "8b7d62", "a48d6a", "bda583", "cdba94", "e6ceac"),
			"carob-treat", List.of("271a1e", "432c2a", "6b4439", "906954", "b4936e", "d6bd94", "e3e2c8"),
			"gothic-bit", List.of("0e0e12", "1a1a24", "333346", "535373", "8080a4", "a6a6bf", "c1c1d2", "e6e6ec"),
			"smoky-09", List.of("fafafa", "d4d8e0", "acacac", "918b8c", "6b615e", "3b342e", "24211a", "0e0d0a",
					"030201"));
	private static final int MOST_COLORS = 256;
	private static final int FETCH_SECONDS = 15;
	private static final int MOST_ANSWER = 100_000;
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
	private static final Pattern LINK = Pattern.compile(
			"(?:https?://)?(?:www\\.)?lospec\\.com/palette-list/([a-z0-9-]{1,64})(?:\\.[a-z]+)?/?");
	private static final Pattern HEX_LINE = Pattern.compile("(?:#|0x)?(?:[0-9a-fA-F]{2})?([0-9a-fA-F]{6})");
	private static final Pattern NUMBERS_LINE = Pattern.compile("(\\d{1,3})\\s+(\\d{1,3})\\s+(\\d{1,3})(?:\\s.*)?");

	private static String readName;
	private static long readTime;
	private static int[] read;

	private Palettes() {
	}

	/**
	 * @return the name of the palette the player picked, {@link #MAP_COLORS} for none
	 */
	public static String selected() {
		final String name = CameraConfig.current().photoPalette;
		return name == null ? MAP_COLORS : name;
	}

	public static void select(String name) {
		CameraConfig.current().photoPalette = name;
		CameraConfig.current().save();
	}

	/**
	 * @return the colours of the palette the player picked as RGB, null for those of a map or if its file is gone
	 */
	public static int @Nullable [] current() {
		final String name = selected();
		final Path file = name.isEmpty() ? null : file(name);
		if (file == null) {
			return null;
		}
		try {
			final long time = Files.getLastModifiedTime(file).toMillis();
			if (!name.equals(readName) || time != readTime) {
				read = read(file);
				readName = name;
				readTime = time;
			}
			return read;
		} catch (IOException | RuntimeException e) {
			Vrcamera.LOGGER.warn("VRCamera: can't read the palette {}", file, e);
			return null;
		}
	}

	/**
	 * @return the colours of a palette in the folder as RGB, null if it has no file that can be read
	 */
	public static int @Nullable [] colors(String name) {
		final Path file = file(name);
		try {
			return file == null ? null : read(file);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * @return the names of the palettes in the folder, in the order of the alphabet
	 */
	public static List<String> names() {
		prepare();
		final TreeSet<String> names = new TreeSet<>();
		try (final Stream<Path> files = Files.list(FOLDER)) {
			files.map(file -> file.getFileName().toString()).forEach(file -> {
				final int dot = file.lastIndexOf('.');
				final String name = dot < 0 ? file : file.substring(0, dot);
				if (dot >= 0 && List.of(KINDS).contains(file.substring(dot).toLowerCase(Locale.ROOT)) &&
						NAME.matcher(name).matches()) {
					names.add(name);
				}
			});
		} catch (IOException | RuntimeException e) {
			Vrcamera.LOGGER.warn("VRCamera: can't list the palettes in {}", FOLDER, e);
		}
		return new ArrayList<>(names);
	}

	/**
	 * Gets a palette from lospec.com and puts it in the folder. Nothing else is ever asked for than that site.
	 *
	 * @param address the link to the palette there, or just its name in that link
	 * @return the name of the palette, fails if there is no such one
	 */
	public static CompletableFuture<String> fetch(String address) {
		String name = address.trim().toLowerCase(Locale.ROOT);
		final Matcher link = LINK.matcher(name);
		if (link.matches()) {
			name = link.group(1);
		}
		if (!NAME.matcher(name).matches()) {
			return CompletableFuture.failedFuture(new IOException("not a palette of lospec.com: " + address));
		}
		final String slug = name;
		final HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(FETCH_SECONDS))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		final HttpRequest request = HttpRequest.newBuilder(URI.create("https://lospec.com/palette-list/" + slug + ".json"))
				.timeout(Duration.ofSeconds(FETCH_SECONDS))
				.header("User-Agent", "VRCamera/" + Vrcamera.version())
				.build();
		return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			try {
				if (response.statusCode() != 200 || response.body().length() > MOST_ANSWER) {
					throw new IOException("lospec.com answered " + response.statusCode());
				}
				final JsonObject palette = new Gson().fromJson(response.body(), JsonObject.class);
				final List<String> lines = new ArrayList<>();
				for (final JsonElement color : palette.getAsJsonArray("colors")) {
					if (HEX_LINE.matcher(color.getAsString()).matches() && lines.size() < MOST_COLORS) {
						lines.add(color.getAsString());
					}
				}
				if (lines.isEmpty()) {
					throw new IOException("no colours in the palette " + slug);
				}
				prepare();
				Files.write(FOLDER.resolve(slug + KINDS[0]), lines);
				return slug;
			} catch (IOException | RuntimeException e) {
				Vrcamera.LOGGER.warn("VRCamera: could not get the palette {}", slug, e);
				throw new IllegalStateException(e);
			}
		});
	}

	/**
	 * shows the folder of the palettes in the file manager of the system
	 */
	public static void openFolder() {
		prepare();
		open(FOLDER.toAbsolutePath().toString());
	}

	/**
	 * shows the palettes of lospec.com in the browser of the system, to find one there
	 */
	public static void openSite() {
		open(SITE);
	}

	private static void open(String target) {
		final String system = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		final List<String> opener = system.contains("win") ? List.of("rundll32", "url.dll,FileProtocolHandler", target) :
				List.of(system.contains("mac") ? "open" : "xdg-open", target);
		try {
			new ProcessBuilder(opener).start();
		} catch (IOException | RuntimeException e) {
			Vrcamera.LOGGER.warn("VRCamera: could not open {}", target, e);
		}
	}

	/**
	 * makes the folder, with the {@link #STARTERS} in it
	 */
	private static void prepare() {
		if (Files.isDirectory(FOLDER)) {
			return;
		}
		try {
			Files.createDirectories(FOLDER);
			for (final Map.Entry<String, List<String>> starter : STARTERS.entrySet()) {
				Files.write(FOLDER.resolve(starter.getKey() + KINDS[0]), starter.getValue());
			}
		} catch (IOException | RuntimeException e) {
			Vrcamera.LOGGER.warn("VRCamera: could not make {}", FOLDER, e);
		}
	}

	@Nullable
	private static Path file(String name) {
		if (!NAME.matcher(name).matches()) {
			return null;
		}
		prepare();
		for (final String kind : KINDS) {
			final Path file = FOLDER.resolve(name + kind);
			if (Files.isRegularFile(file)) {
				return file;
			}
		}
		return null;
	}

	/**
	 * @return the colours in a file, null if there is none in it. A line is a colour as three numbers or in hex,
	 * every other line is skipped
	 */
	private static int @Nullable [] read(Path file) throws IOException {
		final List<Integer> colors = new ArrayList<>();
		for (final String line : Files.readAllLines(file)) {
			final Integer color = color(line.trim());
			if (color != null && colors.size() < MOST_COLORS) {
				colors.add(color);
			}
		}
		return colors.isEmpty() ? null : colors.stream().mapToInt(Integer::intValue).toArray();
	}

	/**
	 * @return the colour a line of a palette file stands for as RGB, null if it is not one
	 */
	@Nullable
	private static Integer color(String line) {
		final Matcher numbers = NUMBERS_LINE.matcher(line);
		if (numbers.matches()) {
			final int red = Integer.parseInt(numbers.group(1));
			final int green = Integer.parseInt(numbers.group(2));
			final int blue = Integer.parseInt(numbers.group(3));
			return red < 256 && green < 256 && blue < 256 ? Integer.valueOf(red << 16 | green << 8 | blue) : null;
		}
		final Matcher hex = HEX_LINE.matcher(line);
		return hex.matches() ? Integer.valueOf(Integer.parseInt(hex.group(1), 16)) : null;
	}
}
