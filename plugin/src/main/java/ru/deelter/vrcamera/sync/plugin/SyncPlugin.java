package ru.deelter.vrcamera.sync.plugin;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import ru.deelter.vrcamera.sync.Jpeg;
import ru.deelter.vrcamera.sync.Protocol;
import ru.deelter.vrcamera.sync.plugin.api.CameraApi;
import ru.deelter.vrcamera.sync.plugin.api.CameraView;
import ru.deelter.vrcamera.sync.plugin.event.CameraSwitchEvent;
import ru.deelter.vrcamera.sync.plugin.event.PhotoPinEvent;
import ru.deelter.vrcamera.sync.plugin.event.PhotoTakeEvent;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Keeps the photos players pinned with the VRCamera mod, and tells the clients around about them.
 * <p>
 * Built to be cheap. A client is told about a photo in a few bytes when it comes near, and asks for the picture
 * itself only when it is close enough to see it. Pictures are small, checked before they are kept, limited per
 * player and per chunk, and sent a few per second at most.
 */
public final class SyncPlugin extends JavaPlugin implements PluginMessageListener, Listener, CameraApi {
	private static final double PIN_REACH = 8.0;

	private static final int LIST_MOST = 30;
	private static final int RANGE_INTERVAL_TICKS = 10;
	private static final int SAVE_INTERVAL_TICKS = 200;

	private static final double MESSAGES_PER_SECOND = 40.0;
	private static final double MESSAGES_BURST = 50.0;

	private static final double CAMERA_LEASH = 48.0;

	private static final double LOOSE_LEASH = 64.0;
	private static final long LOOSE_COOLDOWN = 700;
	private static final long SHUTTER_COOLDOWN = 500;
	private static final long SWITCH_COOLDOWN = 200;

	private static final int DROPPED_BEFORE_IGNORED = 200;
	private static final long IGNORED_MILLIS = 60_000;

	private static final int MAX_MESSAGE_BYTES = 32767;
	private static final float[] QUALITIES = {0.8F, 0.65F, 0.5F, 0.35F, 0.25F};
	private final LooseSheets loose = new LooseSheets();
	private final Map<UUID, Client> clients = new HashMap<>();
	private final Set<String> worlds = new HashSet<>();
	private SheetStore store;
	private int maxPerPlayer;
	private int maxPerChunk;
	private int maxTotal;
	private int maxImageBytes;
	private long pinCooldown;
	private long photoCooldown;
	private double sendRange;
	private double forgetRange;
	private int imageQueue;
	private boolean anyoneTakesOff;
	private boolean shareCameras;
	private double cameraRange;
	private int cameraWatchers;
	private int maxLoose;
	private boolean allowCustom;
	private boolean protectBlocks;
	private boolean worldsListed;
	private long looseLifetime;

	/**
	 * Every client near the sheet will get this picture and unpack it. So no client ever gets the bytes another
	 * client sent: the picture is unpacked here and packed again. What comes out is a plain small JPEG, whatever
	 * went in, without anything hidden in or appended to the file. Its size in pixels is looked at before it
	 * is unpacked, a few bytes can claim to be a picture of a billion pixels.
	 *
	 * @return null if it is not a small JPEG
	 */
	@Nullable
	private static CleanPicture clean(byte[] image, int maxBytes) {
		try (final ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(image))) {
			final Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext()) {
				return null;
			}
			final ImageReader reader = readers.next();
			BufferedImage read;
			try {
				reader.setInput(in);
				final int width = reader.getWidth(0);
				final int height = reader.getHeight(0);
				if (!reader.getFormatName().toLowerCase().contains("jp") || width < 8 || height < 8 ||
						width > Protocol.MAX_IMAGE_SIDE || height > Protocol.MAX_IMAGE_SIDE) {
					return null;
				}
				read = reader.read(0);
			} finally {
				reader.dispose();
			}
			final BufferedImage plain = new BufferedImage(read.getWidth(), read.getHeight(), BufferedImage.TYPE_INT_RGB);
			plain.getGraphics().drawImage(read, 0, 0, null);
			for (final float quality : QUALITIES) {
				final byte[] jpeg = Jpeg.encode(plain, quality);
				if (jpeg.length <= maxBytes) {
					return new CleanPicture(jpeg, plain.getHeight() / (float) plain.getWidth());
				}
			}
			return null;
		} catch (IOException | RuntimeException e) {

			return null;
		}
	}

	private static long hash(byte[] image) {
		try {
			long hash = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(image)).getLong();

			return hash == 0 ? 1 : hash;
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void sound(StoredSheet sheet, Sound sound) {
		final World world = Bukkit.getWorld(sheet.world());
		if (world != null) {
			world.playSound(new Location(world, sheet.x(), sheet.y(), sheet.z()), sound, SoundCategory.PLAYERS, 0.6F,
					1.3F);
		}
	}

	@Override
	public void onEnable() {
		saveDefaultConfig();
		readConfig();

		ImageIO.setUseCache(false);

		store = new SheetStore(getDataFolder().toPath(), getLogger());
		store.load();
		getLogger().info(store.size() + " pinned photos loaded");

		getServer().getMessenger().registerIncomingPluginChannel(this, Protocol.CHANNEL, this);
		getServer().getMessenger().registerOutgoingPluginChannel(this, Protocol.CHANNEL);
		getServer().getPluginManager().registerEvents(this, this);
		getServer().getServicesManager().register(CameraApi.class, this, this, ServicePriority.Normal);

		final int imagesPerSecond = Math.clamp(getConfig().getInt("network.images-per-second", 4), 1, 20);
		Bukkit.getScheduler().runTaskTimer(this, this::updateRanges, RANGE_INTERVAL_TICKS, RANGE_INTERVAL_TICKS);
		Bukkit.getScheduler().runTaskTimer(this, this::sendImages, 1, Math.max(1, 20 / imagesPerSecond));
		Bukkit.getScheduler().runTaskTimer(this, this::saveIfChanged, SAVE_INTERVAL_TICKS, SAVE_INTERVAL_TICKS);
	}

	@Override
	public void onDisable() {
		if (store != null && store.isDirty()) {
			store.save(store.snapshot(), store.nextIdSnapshot());
		}
		clients.clear();
	}

	@Override
	public void onPluginMessageReceived(@NonNull String channel, @NonNull Player player, byte[] message) {
		if (!Protocol.CHANNEL.equals(channel) || message.length == 0 || message.length > MAX_MESSAGE_BYTES) {
			return;
		}
		final Client client = clients.get(player.getUniqueId());
		if (client != null && !mayTalk(player, client)) {
			return;
		}
		try {
			final DataInputStream in = Protocol.body(message);
			switch (message[0]) {
				case Protocol.C_HELLO -> hello(player, in.readInt());
				case Protocol.C_PIN -> {
					if (client != null) {
						pin(player, client, Protocol.readPin(in));
					}
				}
				case Protocol.C_UNPIN -> {
					if (client != null) {
						unpin(player, in.readLong());
					}
				}
				case Protocol.C_LOOSE_NEW -> {
					if (client != null) {
						newLoose(player, client, Protocol.readNewLoose(in));
					}
				}
				case Protocol.C_LOOSE_POSE -> {
					if (client != null) {
						moveLoose(player, in.readLong(), Protocol.readPose(in));
					}
				}
				case Protocol.C_LOOSE_DROP -> {
					final LooseSheets.Sheet dropped = client == null ? null : loose.get(in.readLong());
					if (dropped != null && dropped.owner.equals(player.getUniqueId())) {
						removeLoose(dropped, false);
					}
				}
				case Protocol.C_LOOSE_TAKE -> {
					if (client != null) {
						takeLoose(player, client, in.readLong());
					}
				}
				case Protocol.C_CAMERA -> {
					if (client != null) {
						camera(player, Protocol.readCamera(in, false));
					}
				}
				case Protocol.C_SHUTTER -> {
					if (client != null) {
						if (System.currentTimeMillis() - client.lastShutter >= photoWait(player)) {
							client.lastShutter = System.currentTimeMillis();
							double x = in.readDouble();
							final double y = in.readDouble();
							double z = in.readDouble();
							if (cameraSound(player, Protocol.S_SHUTTER, x, y, z)) {
								Bukkit.getPluginManager().callEvent(
										new PhotoTakeEvent(player, new Location(player.getWorld(), x, y, z)));
							}
						}
					}
				}
				case Protocol.C_PRINT -> {
					if (client != null && System.currentTimeMillis() - client.lastPrint >= photoWait(player)) {
						client.lastPrint = System.currentTimeMillis();
						cameraSound(player, Protocol.S_PRINT, in.readDouble(), in.readDouble(), in.readDouble());
					}
				}
				case Protocol.C_SWITCH -> {
					final Protocol.Switched camera = Protocol.readSwitched(in);
					final long now = System.currentTimeMillis();
					if (client != null && camera.isSane() && now - client.lastSwitch >= SWITCH_COOLDOWN) {
						client.lastSwitch = now;
						Bukkit.getPluginManager().callEvent(new CameraSwitchEvent(player, camera.name(),
								camera.id().isEmpty() ? null : camera.id(),
								new Location(player.getWorld(), camera.x(), camera.y(), camera.z())));
					}
				}
				case Protocol.C_IMAGE -> {
					if (client != null) {
						wantImage(client, in.readLong());
					}
				}
				default -> {
				}
			}
		} catch (IOException | RuntimeException e) {

			getLogger().log(Level.FINE, "Bad message from " + player.getName(), e);
		}
	}

	@Override
	public boolean hasMod(@NonNull Player player) {
		return clients.containsKey(player.getUniqueId());
	}

	@Override
	public boolean placeCamera(@NonNull Player player, @NonNull CameraView view, boolean show) {
		final Location at = view.location();
		if (!hasMod(player) || at.getWorld() != player.getWorld()) {
			return false;
		}
		send(player, Protocol.place(new Protocol.Placed(view.id(), at.getX(), at.getY(), at.getZ(), at.getYaw(),
				at.getPitch(), view.fov(), view.replace(), show)));
		return true;
	}

	@Override
	public boolean removeCamera(@NonNull Player player, @NonNull String id) {
		return take(player, id, true);
	}

	@Override
	public boolean removeCameras(@NonNull Player player, @NonNull String prefix) {
		return take(player, prefix, false);
	}

	@Override
	public boolean showCamera(@NonNull Player player, @NonNull String id) {
		return show(player, id, 0);
	}

	@Override
	public boolean showCamera(@NonNull Player player, @NonNull String id, long duration, @NonNull TimeUnit unit) {
		return duration > 0 && show(player, id, unit.toMillis(duration) / 1000.0F);
	}

	private void readConfig() {
		reloadConfig();
		maxPerPlayer = Math.max(0, getConfig().getInt("limits.per-player", 64));
		maxPerChunk = Math.max(0, getConfig().getInt("limits.per-chunk", 16));
		maxTotal = Math.max(0, getConfig().getInt("limits.total", 20000));
		maxImageBytes = Math.clamp(
				getConfig().getInt("limits.image-bytes", 20000), 1024, Protocol.MAX_IMAGE_BYTES);
		pinCooldown = Math.max(0, getConfig().getLong("limits.pin-cooldown-ms", 1500));
		photoCooldown = Math.clamp(Math.round(getConfig().getDouble("photos.cooldown-seconds", 3.0) * 1000.0), 0L,
				(long) (Protocol.MAX_PHOTO_COOLDOWN * 1000.0F));
		sendRange = Math.max(8.0, getConfig().getDouble("range.send", 32));
		forgetRange = Math.max(sendRange + 8.0, getConfig().getDouble("range.forget", 48));
		imageQueue = Math.max(1, getConfig().getInt("network.image-queue", 32));
		anyoneTakesOff = getConfig().getBoolean("anyone-takes-off", false);
		shareCameras = getConfig().getBoolean("cameras.share", true);
		cameraRange = Math.max(4.0, getConfig().getDouble("cameras.range", 32));
		cameraWatchers = Math.max(1, getConfig().getInt("cameras.max-watchers", 24));
		maxLoose = Math.max(0, getConfig().getInt("limits.loose-per-player", 8));
		allowCustom = getConfig().getBoolean("custom-pictures", true);
		protectBlocks = getConfig().getBoolean("photos-protect-blocks", false);
		worldsListed = "allow".equalsIgnoreCase(getConfig().getString("worlds.mode", "deny"));
		worlds.clear();
		for (final String world : getConfig().getStringList("worlds.list")) {
			worlds.add(world.toLowerCase(Locale.ROOT));
		}
		looseLifetime = Math.max(1, getConfig().getLong("limits.loose-minutes", 10)) * 60_000L;
	}

	private void saveIfChanged() {
		if (!store.isDirty()) {
			return;
		}
		final List<StoredSheet> sheets = store.snapshot();
		final long nextId = store.nextIdSnapshot();
		Bukkit.getScheduler().runTaskAsynchronously(this, () -> store.save(sheets, nextId));
	}

	private void send(Player player, byte[] message) {
		player.sendPluginMessage(this, Protocol.CHANNEL, message);
	}

	private void hello(Player player, int version) {
		if (version != Protocol.VERSION) {

			getLogger().info(player.getName() + " has a VRCamera mod that speaks protocol " + version + ", this is " +
					Protocol.VERSION);
			return;
		}

		if (clients.putIfAbsent(player.getUniqueId(), new Client()) == null) {
			getLogger().info(player.getName() + " has the VRCamera mod");
		}
		sendHello(player);
	}

	/**
	 * passes what a camera does on to the players around who have the mod: the click and flash of a photo, the
	 * whirr of printing it
	 */

	private void sendHello(Player player) {
		send(player, Protocol.serverHello(new Protocol.Limits(Protocol.VERSION, maxPerPlayer, maxPerChunk,
				maxImageBytes), waitsBetweenPhotos(player) ? photoCooldown / 1000.0F : 0.0F));
	}

	private boolean waitsBetweenPhotos(Player player) {
		return photoCooldown > 0 && !player.hasPermission("vrcamera.photo.nocooldown");
	}

	/**
	 * @return milliseconds between two camera sounds of that player the others get to hear. A little less than
	 * the mod of the player waits itself: its messages do not arrive as evenly as it sends them
	 */
	private long photoWait(Player player) {
		return waitsBetweenPhotos(player) ? Math.max(SHUTTER_COOLDOWN, photoCooldown * 9 / 10) : SHUTTER_COOLDOWN;
	}

	/**
	 * passes on where the camera of a player is, to the players around who have the mod
	 */
	private void camera(Player player, Protocol.Camera camera) {
		float length = (float) Math.sqrt(camera.qx() * camera.qx() + camera.qy() * camera.qy() +
				camera.qz() * camera.qz() + camera.qw() * camera.qw());
		final Location at = player.getLocation();
		final double dx = camera.x() - at.getX();
		final double dy = camera.y() - at.getY();
		final double dz = camera.z() - at.getZ();

		if (!shareCameras || !Float.isFinite(length) || length < 1.0E-3F ||
				!(dx * dx + dy * dy + dz * dz <= CAMERA_LEASH * CAMERA_LEASH)) {
			return;
		}
		final List<Player> watchers = watchers(player, at);
		if (watchers.isEmpty()) {
			return;
		}
		final byte[] message = Protocol.camera(new Protocol.Camera(player.getUniqueId(), player.getName(), camera.x(),
				camera.y(), camera.z(), camera.qx() / length, camera.qy() / length, camera.qz() / length,
				camera.qw() / length));
		watchers.forEach(watcher -> send(watcher, message));
	}

	/**
	 * @return false if the camera is too far from its player to be theirs, and nobody was told
	 */
	private boolean cameraSound(Player player, byte type, double x, double y, double z) {
		final Location at = player.getLocation();
		final double dx = x - at.getX();
		final double dy = y - at.getY();
		final double dz = z - at.getZ();

		if (!(dx * dx + dy * dy + dz * dz <= CAMERA_LEASH * CAMERA_LEASH)) {
			return false;
		}
		final byte[] message = Protocol.cameraSound(type, x, y, z);
		watchers(player, at).forEach(watcher -> send(watcher, message));
		return true;
	}

	/**
	 * @return the other players with the mod who are close enough to be told about the camera of this one
	 */
	private List<Player> watchers(Player player, Location at) {
		final List<Player> watchers = new ArrayList<>();
		for (final UUID other : clients.keySet()) {
			final Player watcher = other.equals(player.getUniqueId()) ? null : Bukkit.getPlayer(other);
			if (watcher != null && watcher.getWorld() == player.getWorld() &&
					watcher.getLocation().distanceSquared(at) <= cameraRange * cameraRange) {
				watchers.add(watcher);
			}
		}

		if (watchers.size() > cameraWatchers) {
			watchers.sort(Comparator.comparingDouble(watcher -> watcher.getLocation().distanceSquared(at)));
			return watchers.subList(0, cameraWatchers);
		}
		return watchers;
	}

	private boolean take(Player player, String id, boolean exact) {
		if (!hasMod(player) || id.length() > Protocol.MAX_CAMERA_ID) {
			return false;
		}
		send(player, Protocol.take(id, exact));
		return true;
	}

	private boolean show(Player player, String id, float seconds) {
		if (!hasMod(player) || id.isEmpty() || id.length() > Protocol.MAX_CAMERA_ID) {
			return false;
		}
		send(player, Protocol.show(id, seconds));
		return true;
	}

	/**
	 * /vrcamsync camera place|remove|clear|show: the same for an admin by hand
	 */
	private boolean cameraCommand(CommandSender sender, String[] args) {
		final Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : null;
		if (target == null) {
			return false;
		}
		final String id = args.length >= 4 ? args[3] : "";
		boolean done;
		switch (args[1].toLowerCase(Locale.ROOT)) {
			case "place" -> {
				if (!(sender instanceof Player admin) || id.isBlank() || id.length() > Protocol.MAX_CAMERA_ID) {
					return false;
				}

				done = placeCamera(target, CameraView.builder(id).location(admin.getEyeLocation()).replace(true).build());
			}
			case "remove" -> done = !id.isEmpty() && removeCamera(target, id);
			case "clear" -> done = removeCameras(target, id);
			case "show" -> {
				float seconds = 0;
				try {
					seconds = args.length >= 5 ? Float.parseFloat(args[4]) : 0;
				} catch (NumberFormatException e) {
					return false;
				}
				done = show(target, id, Math.max(0, seconds));
			}
			default -> {
				return false;
			}
		}
		sender.sendMessage(done ? "VRCameraSync: told the mod of " + target.getName() :
				"VRCameraSync: " + target.getName() + " has no mod that listens, or is in another world");
		return true;
	}

	/**
	 * A player made a photo and its sheet is out in the world, not pinned to anything yet. Kept so the others see
	 * it and can pick it up. The player can't be told no in a way that matters: refused, the sheet is only theirs
	 */
	private void newLoose(Player player, Client client, Protocol.NewLoose sheet) {
		final long now = System.currentTimeMillis();
		final Location at = player.getLocation();
		final double dx = sheet.pose().x() - at.getX();
		final double dy = sheet.pose().y() - at.getY();
		final double dz = sheet.pose().z() - at.getZ();

		final boolean refused = maxLoose == 0 || client.sharingLoose || now - client.lastLoose < LOOSE_COOLDOWN ||
				!player.hasPermission("vrcamera.pin") || (sheet.custom() && !mayCustom(player)) ||
				!worldAllowed(player.getWorld()) || !sheet.pose().isSane() ||
				!(dx * dx + dy * dy + dz * dz <= CAMERA_LEASH * CAMERA_LEASH) ||
				sheet.image().length < 4 || sheet.image().length > maxImageBytes;
		if (refused) {
			send(player, Protocol.looseResult(new Protocol.LooseResult(sheet.reference(), 0, 0)));
			return;
		}
		client.lastLoose = now;
		client.sharingLoose = true;
		final UUID owner = player.getUniqueId();
		final UUID world = player.getWorld().getUID();
		Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
			final CleanPicture clean = clean(sheet.image(), maxImageBytes);
			Bukkit.getScheduler().runTask(this, () -> {
				final Player still = Bukkit.getPlayer(owner);
				final Client stillClient = clients.get(owner);
				if (stillClient != null) {
					stillClient.sharingLoose = false;
				}
				if (still == null || stillClient == null) {
					return;
				}
				if (clean == null || !still.getWorld().getUID().equals(world)) {
					send(still, Protocol.looseResult(new Protocol.LooseResult(sheet.reference(), 0, 0)));
					return;
				}

				final List<LooseSheets.Sheet> own = loose.of(owner);
				for (int i = 0; i <= own.size() - maxLoose; i++) {
					removeLoose(own.get(i), true);
				}
				long hash = hash(clean.jpeg);
				final LooseSheets.Sheet added = loose.add(world, owner, still.getName(), sheet.pose().normalized(),
						clean.aspect, hash, clean.jpeg, sheet.custom());
				stillClient.knownLoose.add(added.id);
				send(still, Protocol.looseResult(new Protocol.LooseResult(sheet.reference(), added.id, hash)));
			});
		});
	}

	private void moveLoose(Player player, long id, Protocol.Pose pose) {
		final LooseSheets.Sheet sheet = loose.get(id);
		if (sheet == null || !sheet.owner.equals(player.getUniqueId()) || !pose.isSane()) {
			return;
		}
		final Location at = player.getLocation();
		final double dx = pose.x() - at.getX();
		final double dy = pose.y() - at.getY();
		final double dz = pose.z() - at.getZ();
		if (!(dx * dx + dy * dy + dz * dz <= LOOSE_LEASH * LOOSE_LEASH)) {
			return;
		}
		sheet.pose = pose.normalized();
		sheet.touched = System.currentTimeMillis();
		final byte[] message = Protocol.loosePose(Protocol.S_LOOSE_POSE, id, sheet.pose);
		for (final Map.Entry<UUID, Client> entry : clients.entrySet()) {
			if (!entry.getKey().equals(sheet.owner) && entry.getValue().knownLoose.contains(id)) {
				final Player watcher = Bukkit.getPlayer(entry.getKey());
				if (watcher != null) {
					send(watcher, message);
				}
			}
		}
	}

	/**
	 * A player picked up a sheet that was someone else's. It is theirs from here on. Who had it loses it: for
	 * them it is one of the sheets of others now
	 */
	private void takeLoose(Player player, Client client, long id) {
		final LooseSheets.Sheet sheet = loose.get(id);
		final Location at = player.getLocation();
		final boolean allowed = sheet != null && !sheet.owner.equals(player.getUniqueId()) &&
				sheet.world.equals(player.getWorld().getUID()) &&
				sheet.distanceSquared(at.getX(), at.getY(), at.getZ()) <= PIN_REACH * PIN_REACH;
		if (!allowed) {

			client.knownLoose.remove(id);
			send(player, Protocol.looseId(Protocol.S_LOOSE_GONE, id));
			return;
		}
		final Player before = Bukkit.getPlayer(sheet.owner);
		final Client beforeClient = clients.get(sheet.owner);
		if (before != null && beforeClient != null) {

			beforeClient.knownLoose.remove(id);
			send(before, Protocol.looseId(Protocol.S_LOOSE_GONE, id));
		}
		sheet.owner = player.getUniqueId();
		sheet.ownerName = player.getName();
		sheet.touched = System.currentTimeMillis();
		client.knownLoose.add(id);
	}

	/**
	 * @param alsoOwner if the one it belongs to is told as well. Not when they said so themselves
	 */
	private void removeLoose(LooseSheets.Sheet sheet, boolean alsoOwner) {
		loose.remove(sheet.id);
		final byte[] message = Protocol.looseId(Protocol.S_LOOSE_GONE, sheet.id);
		for (final Map.Entry<UUID, Client> entry : clients.entrySet()) {
			if (entry.getValue().knownLoose.remove(sheet.id) && (alsoOwner || !entry.getKey().equals(sheet.owner))) {
				final Player watcher = Bukkit.getPlayer(entry.getKey());
				if (watcher != null) {
					send(watcher, message);
				}
			}
		}
	}

	/**
	 * @return if photos can be pinned and shared in that world. What hangs there already stays
	 */
	private boolean worldAllowed(World world) {
		return worlds.contains(world.getName().toLowerCase(Locale.ROOT)) == worldsListed;
	}

	/**
	 * @return if the player may put up pictures that are not photos taken in the game. Whether one is, is what
	 * its client says: the server can't tell a screenshot from any other picture
	 */
	private boolean mayCustom(Player player) {
		return allowCustom && player.hasPermission("vrcamera.custom");
	}

	private void wantImage(Client client, long hash) {

		if (client.wantedImages.size() >= imageQueue || client.wantedImages.contains(hash)) {
			return;
		}

		for (final long id : client.known) {
			final StoredSheet sheet = store.get(id);
			if (sheet != null && sheet.imageHash() == hash) {
				client.wantedImages.add(hash);
				return;
			}
		}
		for (final long id : client.knownLoose) {
			final LooseSheets.Sheet sheet = loose.get(id);
			if (sheet != null && sheet.imageHash == hash) {
				client.wantedImages.add(hash);
				return;
			}
		}
	}

	private void sendImages() {
		for (final Map.Entry<UUID, Client> entry : clients.entrySet()) {
			Long hash = entry.getValue().wantedImages.poll();
			final Player player = hash == null ? null : Bukkit.getPlayer(entry.getKey());
			if (player == null) {
				continue;
			}
			byte[] image = loose.image(hash);
			if (image == null) {
				image = store.image(hash);
			}
			if (image != null) {
				send(player, Protocol.image(hash, image));
			}
		}
	}

	private void pin(Player player, Client client, Protocol.Pin pin) {
		final byte refusal = refusal(player, client, pin);
		if (refusal != Protocol.PIN_OK) {
			getLogger().info("Photo of " + player.getName() + " not pinned, reason " + refusal);
			send(player, Protocol.pinResult(new Protocol.PinResult(pin.reference(), refusal, 0, 0)));
			return;
		}
		final PhotoPinEvent event = new PhotoPinEvent(player, new Location(player.getWorld(), pin.x(), pin.y(), pin.z()),
				player.getWorld().getBlockAt(pin.blockX(), pin.blockY(), pin.blockZ()), pin.custom());
		if (!event.callEvent()) {

			send(player, Protocol.pinResult(new Protocol.PinResult(pin.reference(), Protocol.PIN_NOT_ALLOWED, 0, 0)));
			return;
		}
		client.lastPin = System.currentTimeMillis();
		client.pinning = true;
		final UUID world = player.getWorld().getUID();
		final UUID owner = player.getUniqueId();
		final String ownerName = player.getName();

		Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
			long hash = 0;
			final CleanPicture clean = clean(pin.image(), maxImageBytes);
			if (clean != null) {
				try {
					hash = hash(clean.jpeg);
					store.writeImage(hash, clean.jpeg);
				} catch (IOException e) {
					getLogger().log(Level.WARNING, "Can't keep a picture of " + ownerName, e);
					hash = 0;
				}
			}
			long imageHash = hash;
			Bukkit.getScheduler().runTask(this, () -> finishPin(owner, ownerName, world, pin, clean, imageHash));
		});
	}

	private void finishPin(
			UUID owner, String ownerName, UUID world, Protocol.Pin pin, CleanPicture clean, long imageHash) {
		final Player player = Bukkit.getPlayer(owner);
		final Client client = clients.get(owner);
		if (client != null) {
			client.pinning = false;
		}

		float length = (float) Math.sqrt(pin.qx() * pin.qx() + pin.qy() * pin.qy() + pin.qz() * pin.qz() +
				pin.qw() * pin.qw());
		if (length < 1.0E-3F) {
			imageHash = 0;
			length = 1.0F;
		}
		byte result = imageHash == 0 ? Protocol.PIN_BAD_IMAGE : Protocol.PIN_OK;

		if (result == Protocol.PIN_OK && store.ownedBy(owner) >= maxPerPlayer) {
			result = Protocol.PIN_TOO_MANY;
		}

		final StoredSheet sheet = new StoredSheet(store.newId(), world, owner, ownerName, pin.blockX(), pin.blockY(),
				pin.blockZ(), pin.x(), pin.y(), pin.z(), pin.qx() / length, pin.qy() / length, pin.qz() / length,
				pin.qw() / length, clean == null ? 1.0F : clean.aspect, imageHash, pin.custom());
		if (result == Protocol.PIN_OK && store.inChunk(sheet.chunk()).size() >= maxPerChunk) {
			result = Protocol.PIN_CHUNK_FULL;
		}
		final long kept = imageHash;
		if (result != Protocol.PIN_OK || player == null || client == null) {
			getLogger().info("Photo of " + ownerName + " not pinned, reason " + result);
			if (kept != 0 && !store.hasImage(kept)) {
				Bukkit.getScheduler().runTaskAsynchronously(this, () -> store.deleteImage(kept));
			}
			if (player != null) {
				send(player, Protocol.pinResult(new Protocol.PinResult(pin.reference(), result, 0, 0)));
			}
			return;
		}
		store.add(sheet);
		getLogger().info(ownerName + " pinned photo " + sheet.id() + " at " + pin.blockX() + " " + pin.blockY() + " " +
				pin.blockZ());
		store.cacheImage(imageHash, clean.jpeg);

		sound(sheet, Sound.ENTITY_ITEM_FRAME_PLACE);

		client.known.add(sheet.id());
		send(player, Protocol.pinResult(new Protocol.PinResult(pin.reference(), Protocol.PIN_OK, sheet.id(),
				imageHash)));
	}

	/**
	 * @return why the player can't pin this, {@link Protocol#PIN_OK} if they can
	 */
	private byte refusal(Player player, Client client, Protocol.Pin pin) {
		if (!player.hasPermission("vrcamera.pin") || (pin.custom() && !mayCustom(player)) ||
				!worldAllowed(player.getWorld())) {
			return Protocol.PIN_NOT_ALLOWED;
		}
		if (client.pinning || System.currentTimeMillis() - client.lastPin < pinCooldown) {
			return Protocol.PIN_TOO_FAST;
		}
		final boolean sane = Double.isFinite(pin.x()) && Double.isFinite(pin.y()) && Double.isFinite(pin.z()) &&
				Float.isFinite(pin.qx()) && Float.isFinite(pin.qy()) && Float.isFinite(pin.qz()) &&
				Float.isFinite(pin.qw());
		if (!sane) {
			return Protocol.PIN_BAD_IMAGE;
		}

		final Location location = player.getLocation();
		final double dx = pin.x() - location.getX();
		final double dy = pin.y() - location.getY();
		final double dz = pin.z() - location.getZ();
		final double bx = pin.blockX() + 0.5 - pin.x();
		final double by = pin.blockY() + 0.5 - pin.y();
		final double bz = pin.blockZ() + 0.5 - pin.z();
		if (dx * dx + dy * dy + dz * dz > PIN_REACH * PIN_REACH || bx * bx + by * by + bz * bz > 4.0) {
			return Protocol.PIN_TOO_FAR;
		}

		if (player.getWorld().getBlockAt(pin.blockX(), pin.blockY(), pin.blockZ()).getType().isAir()) {
			return Protocol.PIN_TOO_FAR;
		}
		if (store.size() >= maxTotal) {
			return Protocol.PIN_SERVER_FULL;
		}
		if (store.ownedBy(player.getUniqueId()) >= maxPerPlayer) {
			return Protocol.PIN_TOO_MANY;
		}
		final UUID world = player.getWorld().getUID();
		final StoredSheet.ChunkKey chunk = new StoredSheet.ChunkKey(world, (int) Math.floor(pin.x()) >> 4,
				(int) Math.floor(pin.z()) >> 4);
		if (store.inChunk(chunk).size() >= maxPerChunk) {
			return Protocol.PIN_CHUNK_FULL;
		}
		if (pin.image().length < 4 || pin.image().length > maxImageBytes) {
			return Protocol.PIN_BAD_IMAGE;
		}
		return Protocol.PIN_OK;
	}

	@EventHandler
	public void onChannel(PlayerRegisterChannelEvent event) {

		if (Protocol.CHANNEL.equals(event.getChannel()) && clients.containsKey(event.getPlayer().getUniqueId())) {
			sendHello(event.getPlayer());
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		loose.of(event.getPlayer().getUniqueId()).forEach(sheet -> removeLoose(sheet, false));
		clients.remove(event.getPlayer().getUniqueId());
	}

	@EventHandler
	public void onWorldChange(PlayerChangedWorldEvent event) {

		loose.of(event.getPlayer().getUniqueId()).forEach(sheet -> removeLoose(sheet, false));
		final Client client = clients.get(event.getPlayer().getUniqueId());
		if (client != null) {
			client.known.clear();
			client.knownLoose.clear();
			client.wantedImages.clear();
			send(event.getPlayer(), Protocol.reset());
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void protectFromFire(BlockBurnEvent event) {
		if (holdsPhoto(event.getBlock())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void protectFromDecay(LeavesDecayEvent event) {
		if (holdsPhoto(event.getBlock())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void protectFromBlockExplosion(BlockExplodeEvent event) {
		if (protectBlocks) {
			event.blockList().removeIf(this::holdsPhoto);
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void protectFromEntityExplosion(EntityExplodeEvent event) {
		if (protectBlocks) {
			event.blockList().removeIf(this::holdsPhoto);
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void protectFromPiston(BlockPistonExtendEvent event) {
		if (protectBlocks && event.getBlocks().stream().anyMatch(this::holdsPhoto)) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void protectFromPiston(BlockPistonRetractEvent event) {
		if (protectBlocks && event.getBlocks().stream().anyMatch(this::holdsPhoto)) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBreak(@NonNull BlockBreakEvent event) {
		blockGone(event.getBlock());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBurn(@NonNull BlockBurnEvent event) {
		blockGone(event.getBlock());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDecay(@NonNull LeavesDecayEvent event) {
		blockGone(event.getBlock());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBlockExplode(@NonNull BlockExplodeEvent event) {
		event.blockList().forEach(this::blockGone);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onEntityExplode(@NonNull EntityExplodeEvent event) {
		event.blockList().forEach(this::blockGone);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPistonExtend(@NonNull BlockPistonExtendEvent event) {
		event.getBlocks().forEach(this::blockGone);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPistonRetract(@NonNull BlockPistonRetractEvent event) {
		event.getBlocks().forEach(this::blockGone);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String @NonNull [] args) {
		if (args.length == 1 && args[0].equalsIgnoreCase("stats")) {
			sender.sendMessage("VRCameraSync: " + store.size() + " pinned photos, " + clients.size() +
					" players with the mod online");
			return true;
		}
		if (args.length >= 3 && args[0].equalsIgnoreCase("camera")) {
			return cameraCommand(sender, args);
		}
		if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
			readConfig();
			for (final UUID id : clients.keySet()) {
				final Player online = Bukkit.getPlayer(id);
				if (online != null) {
					sendHello(online);
				}
			}
			sender.sendMessage("VRCameraSync: config read again. Limits apply to new pins, timings after a restart");
			return true;
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("purge")) {
			final UUID owner = Bukkit.getOfflinePlayer(args[1]).getUniqueId();
			sender.sendMessage("VRCameraSync: removed " + purge(sheet -> sheet.owner().equals(owner) ||
					sheet.ownerName().equalsIgnoreCase(args[1])) + " photos of " + args[1]);
			return true;
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("list")) {
			final UUID owner = Bukkit.getOfflinePlayer(args[1]).getUniqueId();
			int found = 0;
			for (final StoredSheet sheet : store.all()) {
				if (!sheet.owner().equals(owner) && !sheet.ownerName().equalsIgnoreCase(args[1])) {
					continue;
				}
				final World world = Bukkit.getWorld(sheet.world());
				if (++found <= LIST_MOST) {
					sender.sendMessage("  " + (world == null ? sheet.world() : world.getName()) + " " + sheet.blockX() +
							" " + sheet.blockY() + " " + sheet.blockZ() + (sheet.custom() ? " (custom picture)" : ""));
				}
			}
			sender.sendMessage("VRCameraSync: " + found + " photos of " + args[1] +
					(found > LIST_MOST ? ", the first " + LIST_MOST + " shown" : ""));
			return true;
		}
		if (args.length == 1 && args[0].equalsIgnoreCase("purgecustom")) {
			sender.sendMessage("VRCameraSync: removed " + purge(StoredSheet::custom) + " custom pictures");
			return true;
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("purgenear") && sender instanceof Player player) {
			double radius;
			try {
				radius = Double.parseDouble(args[1]);
			} catch (NumberFormatException e) {
				return false;
			}
			final Location at = player.getLocation();
			final UUID world = player.getWorld().getUID();
			sender.sendMessage("VRCameraSync: removed " + purge(sheet -> sheet.world().equals(world) &&
					sheet.distanceSquared(at.getX(), at.getY(), at.getZ()) <= radius * radius) + " photos");
			return true;
		}
		return false;
	}

	/**
	 * @return if the client did not send more than it may. One that keeps at it is not listened to for a while
	 */
	private boolean mayTalk(Player player, Client client) {
		final long now = System.currentTimeMillis();
		if (now < client.ignoredUntil) {
			return false;
		}
		final long nanos = System.nanoTime();
		client.allowance = Math.min(MESSAGES_BURST,
				client.allowance + (nanos - client.allowanceAt) / 1.0E9 * MESSAGES_PER_SECOND);
		client.allowanceAt = nanos;
		if (client.allowance >= 1.0) {
			client.allowance -= 1.0;
			return true;
		}
		if (++client.dropped >= DROPPED_BEFORE_IGNORED) {
			client.dropped = 0;
			client.ignoredUntil = now + IGNORED_MILLIS;
			client.wantedImages.clear();
			getLogger().warning(player.getName() + " floods the photo channel and is ignored for a minute");
		}
		return false;
	}

	private void unpin(Player player, long id) {
		final StoredSheet sheet = store.get(id);
		if (sheet == null) {

			send(player, Protocol.remove(id, Protocol.REMOVED_TAKEN));
			return;
		}
		if (sheet.owner().equals(player.getUniqueId()) || anyoneTakesOff ||
				player.hasPermission("vrcamera.remove.others")) {
			sound(sheet, Sound.ENTITY_ITEM_FRAME_REMOVE_ITEM);
			remove(sheet, Protocol.REMOVED_TAKEN);
		}
	}

	private void remove(StoredSheet sheet, byte reason) {
		final long unused = store.remove(sheet);
		final byte[] message = Protocol.remove(sheet.id(), reason);
		for (final Map.Entry<UUID, Client> entry : clients.entrySet()) {
			if (entry.getValue().known.remove(sheet.id())) {
				final Player player = Bukkit.getPlayer(entry.getKey());
				if (player != null) {
					send(player, message);
				}
			}
		}
		if (unused != 0) {
			Bukkit.getScheduler().runTaskAsynchronously(this, () -> store.deleteImage(unused));
		}
	}

	/**
	 * tells every client about the photos that came into its range, and to forget the ones that are far away
	 */
	private void updateRanges() {

		final long expired = System.currentTimeMillis() - looseLifetime;
		for (final LooseSheets.Sheet sheet : new ArrayList<>(loose.all())) {
			if (sheet.touched < expired) {
				removeLoose(sheet, true);
			}
		}
		final double send = sendRange * sendRange;
		final double forget = forgetRange * forgetRange;
		final int chunks = (int) Math.ceil(sendRange / 16.0);
		for (final Map.Entry<UUID, Client> entry : clients.entrySet()) {
			final Player player = Bukkit.getPlayer(entry.getKey());
			if (player == null) {
				continue;
			}
			final Client client = entry.getValue();
			final Location at = player.getLocation();
			final UUID world = player.getWorld().getUID();
			final boolean removesOthers = anyoneTakesOff || player.hasPermission("vrcamera.remove.others");

			final List<Protocol.Sheet> entered = new ArrayList<>();
			final int chunkX = at.getBlockX() >> 4;
			final int chunkZ = at.getBlockZ() >> 4;
			for (int x = chunkX - chunks; x <= chunkX + chunks; x++) {
				for (int z = chunkZ - chunks; z <= chunkZ + chunks; z++) {
					for (final StoredSheet sheet : store.inChunk(new StoredSheet.ChunkKey(world, x, z))) {
						if (sheet.distanceSquared(at.getX(), at.getY(), at.getZ()) <= send &&
								client.known.add(sheet.id())) {
							entered.add(new Protocol.Sheet(sheet.id(), sheet.owner(), sheet.ownerName(), sheet.x(),
									sheet.y(), sheet.z(), sheet.qx(), sheet.qy(), sheet.qz(), sheet.qw(),
									sheet.aspect(), sheet.imageHash(),
									removesOthers || sheet.owner().equals(player.getUniqueId()), sheet.custom()));
						}
					}
				}
			}
			for (int from = 0; from < entered.size(); from += Protocol.MAX_SHEETS_PER_MESSAGE) {
				send(player, Protocol.sheets(entered.subList(from,
						Math.min(entered.size(), from + Protocol.MAX_SHEETS_PER_MESSAGE))));
			}

			for (final LooseSheets.Sheet sheet : loose.all()) {
				if (sheet.world.equals(world) && sheet.distanceSquared(at.getX(), at.getY(), at.getZ()) <= send &&
						client.knownLoose.add(sheet.id)) {
					send(player, Protocol.loose(sheet.toProtocol()));
				}
			}
			for (Iterator<Long> known = client.knownLoose.iterator(); known.hasNext(); ) {
				final LooseSheets.Sheet sheet = loose.get(known.next());
				if (sheet == null) {
					known.remove();
				} else if (!sheet.owner.equals(player.getUniqueId()) && (!sheet.world.equals(world) ||
						sheet.distanceSquared(at.getX(), at.getY(), at.getZ()) > forget)) {

					send(player, Protocol.looseId(Protocol.S_LOOSE_GONE, sheet.id));
					known.remove();
				}
			}

			final List<Long> left = new ArrayList<>();
			for (Iterator<Long> known = client.known.iterator(); known.hasNext(); ) {
				final StoredSheet sheet = store.get(known.next());
				if (sheet == null) {
					known.remove();
				} else if (!sheet.world().equals(world) ||
						sheet.distanceSquared(at.getX(), at.getY(), at.getZ()) > forget) {
					left.add(sheet.id());
					known.remove();
				}
			}
			if (!left.isEmpty()) {
				send(player, Protocol.forget(left));
			}
		}
	}

	/**
	 * what a photo is pinned to is gone, it falls
	 */
	private void blockGone(Block block) {
		final List<StoredSheet> pinned = store.onBlock(new StoredSheet.BlockKey(block.getWorld().getUID(),
				block.getX(), block.getY(), block.getZ()));
		if (!pinned.isEmpty()) {
			for (final StoredSheet sheet : new ArrayList<>(pinned)) {
				sound(sheet, Sound.ENTITY_ITEM_FRAME_BREAK);
				remove(sheet, Protocol.REMOVED_FELL);
			}
		}
	}

	private boolean holdsPhoto(Block block) {
		return protectBlocks && !store.onBlock(new StoredSheet.BlockKey(block.getWorld().getUID(),
				block.getX(), block.getY(), block.getZ())).isEmpty();
	}

	private int purge(java.util.function.Predicate<StoredSheet> which) {
		final List<StoredSheet> gone = new ArrayList<>();
		for (final StoredSheet sheet : store.all()) {
			if (which.test(sheet)) {
				gone.add(sheet);
			}
		}
		gone.forEach(sheet -> remove(sheet, Protocol.REMOVED_TAKEN));
		return gone.size();
	}

	/**
	 * what is known about a player that has the mod
	 */
	private static final class Client {

		private final Set<Long> known = new HashSet<>();

		private final Set<Long> knownLoose = new HashSet<>();
		private final ArrayDeque<Long> wantedImages = new ArrayDeque<>();
		private boolean sharingLoose;
		private long lastLoose;
		private long lastShutter;
		private long lastSwitch;
		private long lastPrint;
		private long lastPin;

		private boolean pinning;

		private double allowance = MESSAGES_BURST;
		private long allowanceAt = System.nanoTime();
		private int dropped;
		private long ignoredUntil;
	}

	/**
	 * a picture the server made itself, and its height by its width
	 */
	private record CleanPicture(byte[] jpeg, float aspect) {
	}
}
