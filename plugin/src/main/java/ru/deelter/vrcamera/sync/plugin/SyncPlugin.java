package ru.deelter.vrcamera.sync.plugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import ru.deelter.vrcamera.sync.Protocol;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Keeps the photos players pinned with the VRCamera mod, and tells the clients around about them.
 * <p>
 * Built to be cheap. A client is told about a photo in a few bytes when it comes near, and asks for the picture
 * itself only when it is close enough to see it. Pictures are small, checked before they are kept, limited per
 * player and per chunk, and sent a few per second at most.
 */
public final class SyncPlugin extends JavaPlugin implements PluginMessageListener, Listener {
	private static final double PIN_REACH = 8.0;
	private static final int RANGE_INTERVAL_TICKS = 10;
	private static final int SAVE_INTERVAL_TICKS = 200;
	// What a client may send: this many messages per second, and this many at once. An honest one sends a
	// handful per minute, and ten per second while its camera is on
	private static final double MESSAGES_PER_SECOND = 40.0;
	private static final double MESSAGES_BURST = 50.0;
	// blocks a camera can be from the player it belongs to
	private static final double CAMERA_LEASH = 48.0;
	// blocks a sheet a player threw or dropped can be from them while they still move it
	private static final double LOOSE_LEASH = 64.0;
	private static final long LOOSE_COOLDOWN = 700;
	// after this many messages over that, the client is not listened to for a while
	private static final int DROPPED_BEFORE_IGNORED = 200;
	private static final long IGNORED_MILLIS = 60_000;
	// the largest message a client can send at all
	private static final int MAX_MESSAGE_BYTES = 32767;
	private static final float[] QUALITIES = {0.8F, 0.65F, 0.5F, 0.35F, 0.25F};

	/**
	 * what is known about a player that has the mod
	 */
	private static final class Client {
		// the photos this client was told about and not told to forget
		final Set<Long> known = new HashSet<>();
		// the same for the sheets that are not pinned, its own included
		final Set<Long> knownLoose = new HashSet<>();
		boolean sharingLoose;
		long lastLoose;
		final ArrayDeque<Long> wantedImages = new ArrayDeque<>();
		long lastPin;
		// one pin at a time is looked at, the rest of them wait in the client
		boolean pinning;
		// messages it may still send, filled up again over time
		double allowance = MESSAGES_BURST;
		long allowanceAt = System.nanoTime();
		int dropped;
		long ignoredUntil;
	}

	private SheetStore store;
	private final LooseSheets loose = new LooseSheets();
	private final Map<UUID, Client> clients = new HashMap<>();

	private int maxPerPlayer;
	private int maxPerChunk;
	private int maxTotal;
	private int maxImageBytes;
	private long pinCooldown;
	private double sendRange;
	private double forgetRange;
	private int imageQueue;
	private boolean anyoneTakesOff;
	private boolean shareCameras;
	private double cameraRange;
	private int maxLoose;
	private boolean allowCustom;
	private long looseLifetime;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		readConfig();
		// pictures are checked in memory, no temp files for that
		ImageIO.setUseCache(false);

		this.store = new SheetStore(getDataFolder().toPath(), getLogger());
		this.store.load();
		getLogger().info(this.store.size() + " pinned photos loaded");

		getServer().getMessenger().registerIncomingPluginChannel(this, Protocol.CHANNEL, this);
		getServer().getMessenger().registerOutgoingPluginChannel(this, Protocol.CHANNEL);
		getServer().getPluginManager().registerEvents(this, this);

		int imagesPerSecond = Math.max(1, Math.min(20, getConfig().getInt("network.images-per-second", 4)));
		Bukkit.getScheduler().runTaskTimer(this, this::updateRanges, RANGE_INTERVAL_TICKS, RANGE_INTERVAL_TICKS);
		Bukkit.getScheduler().runTaskTimer(this, this::sendImages, 1, Math.max(1, 20 / imagesPerSecond));
		Bukkit.getScheduler().runTaskTimer(this, this::saveIfChanged, SAVE_INTERVAL_TICKS, SAVE_INTERVAL_TICKS);
	}

	@Override
	public void onDisable() {
		if (this.store != null && this.store.isDirty()) {
			this.store.save(this.store.snapshot(), this.store.nextIdSnapshot());
		}
		this.clients.clear();
	}

	private void readConfig() {
		reloadConfig();
		this.maxPerPlayer = Math.max(0, getConfig().getInt("limits.per-player", 64));
		this.maxPerChunk = Math.max(0, getConfig().getInt("limits.per-chunk", 16));
		this.maxTotal = Math.max(0, getConfig().getInt("limits.total", 20000));
		this.maxImageBytes = Math.max(1024, Math.min(Protocol.MAX_IMAGE_BYTES,
				getConfig().getInt("limits.image-bytes", 20000)));
		this.pinCooldown = Math.max(0, getConfig().getLong("limits.pin-cooldown-ms", 1500));
		this.sendRange = Math.max(8.0, getConfig().getDouble("range.send", 32));
		this.forgetRange = Math.max(this.sendRange + 8.0, getConfig().getDouble("range.forget", 48));
		this.imageQueue = Math.max(1, getConfig().getInt("network.image-queue", 32));
		this.anyoneTakesOff = getConfig().getBoolean("anyone-takes-off", false);
		this.shareCameras = getConfig().getBoolean("cameras.share", true);
		this.cameraRange = Math.max(4.0, getConfig().getDouble("cameras.range", 32));
		this.maxLoose = Math.max(0, getConfig().getInt("limits.loose-per-player", 8));
		this.allowCustom = getConfig().getBoolean("custom-pictures", true);
		this.looseLifetime = Math.max(1, getConfig().getLong("limits.loose-minutes", 10)) * 60_000L;
	}

	private void saveIfChanged() {
		if (!this.store.isDirty()) {
			return;
		}
		List<StoredSheet> sheets = this.store.snapshot();
		long nextId = this.store.nextIdSnapshot();
		Bukkit.getScheduler().runTaskAsynchronously(this, () -> this.store.save(sheets, nextId));
	}

	private void send(Player player, byte[] message) {
		player.sendPluginMessage(this, Protocol.CHANNEL, message);
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		if (!Protocol.CHANNEL.equals(channel) || message.length == 0 || message.length > MAX_MESSAGE_BYTES) {
			return;
		}
		Client client = this.clients.get(player.getUniqueId());
		if (client != null && !mayTalk(player, client)) {
			return;
		}
		try {
			DataInputStream in = Protocol.body(message);
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
					LooseSheets.Sheet dropped = client == null ? null : this.loose.get(in.readLong());
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
				case Protocol.C_IMAGE -> {
					if (client != null) {
						wantImage(client, in.readLong());
					}
				}
				default -> {
				}
			}
		} catch (IOException | RuntimeException e) {
			// a client that sends nonsense is ignored, not kicked: it may just be a newer or older mod
			getLogger().log(Level.FINE, "Bad message from " + player.getName(), e);
		}
	}

	private void hello(Player player, int version) {
		if (version != Protocol.VERSION) {
			// it gets no answer and plays on its own, as on a server without this plugin
			getLogger().info(player.getName() + " has a VRCamera mod that speaks protocol " + version + ", this is " +
					Protocol.VERSION);
			return;
		}
		// Known once per connection, or every hello would make the server tell about all sheets around again.
		// Answered every time: the first answer is lost if the client says hello before it said which channels it
		// listens on, the server does not send into a channel nobody listens on
		if (this.clients.putIfAbsent(player.getUniqueId(), new Client()) == null) {
			getLogger().info(player.getName() + " has the VRCamera mod");
		}
		sendHello(player);
	}

	private void sendHello(Player player) {
		send(player, Protocol.serverHello(new Protocol.Limits(Protocol.VERSION, this.maxPerPlayer, this.maxPerChunk,
				this.maxImageBytes)));
	}

	/**
	 * passes on where the camera of a player is, to the players around who have the mod
	 */
	private void camera(Player player, Protocol.Camera camera) {
		float length = (float) Math.sqrt(camera.qx() * camera.qx() + camera.qy() * camera.qy() +
				camera.qz() * camera.qz() + camera.qw() * camera.qw());
		Location at = player.getLocation();
		double dx = camera.x() - at.getX();
		double dy = camera.y() - at.getY();
		double dz = camera.z() - at.getZ();
		// A camera stays near its player. One far off is a lie, and could be put in front of anyone's face
		if (!this.shareCameras || !Float.isFinite(length) || length < 1.0E-3F ||
				!(dx * dx + dy * dy + dz * dz <= CAMERA_LEASH * CAMERA_LEASH))
		{
			return;
		}
		byte[] message = null;
		for (UUID other : this.clients.keySet()) {
			Player watcher = other.equals(player.getUniqueId()) ? null : Bukkit.getPlayer(other);
			if (watcher == null || watcher.getWorld() != player.getWorld() ||
					watcher.getLocation().distanceSquared(at) > this.cameraRange * this.cameraRange)
			{
				continue;
			}
			if (message == null) {
				message = Protocol.camera(new Protocol.Camera(player.getUniqueId(), player.getName(), camera.x(),
						camera.y(), camera.z(), camera.qx() / length, camera.qy() / length, camera.qz() / length,
						camera.qw() / length));
			}
			send(watcher, message);
		}
	}

	/**
	 * A player made a photo and its sheet is out in the world, not pinned to anything yet. Kept so the others see
	 * it and can pick it up. The player can't be told no in a way that matters: refused, the sheet is only theirs
	 */
	private void newLoose(Player player, Client client, Protocol.NewLoose sheet) {
		long now = System.currentTimeMillis();
		Location at = player.getLocation();
		double dx = sheet.pose().x() - at.getX();
		double dy = sheet.pose().y() - at.getY();
		double dz = sheet.pose().z() - at.getZ();
		// it comes out of the camera, which is somewhere around its player
		boolean refused = this.maxLoose == 0 || client.sharingLoose || now - client.lastLoose < LOOSE_COOLDOWN ||
				!player.hasPermission("vrcamera.pin") || (sheet.custom() && !mayCustom(player)) ||
				!sheet.pose().isSane() ||
				!(dx * dx + dy * dy + dz * dz <= CAMERA_LEASH * CAMERA_LEASH) ||
				sheet.image().length < 4 || sheet.image().length > this.maxImageBytes;
		if (refused) {
			send(player, Protocol.looseResult(new Protocol.LooseResult(sheet.reference(), 0, 0)));
			return;
		}
		client.lastLoose = now;
		client.sharingLoose = true;
		UUID owner = player.getUniqueId();
		UUID world = player.getWorld().getUID();
		Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
			CleanPicture clean = clean(sheet.image(), this.maxImageBytes);
			Bukkit.getScheduler().runTask(this, () -> {
				Player still = Bukkit.getPlayer(owner);
				Client stillClient = this.clients.get(owner);
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
				// more than a player may have lying around: the oldest go, for everyone and for the player too
				List<LooseSheets.Sheet> own = this.loose.of(owner);
				for (int i = 0; i <= own.size() - this.maxLoose; i++) {
					removeLoose(own.get(i), true);
				}
				long hash = hash(clean.jpeg);
				LooseSheets.Sheet added = this.loose.add(world, owner, still.getName(), sheet.pose().normalized(),
						clean.aspect, hash, clean.jpeg, sheet.custom());
				stillClient.knownLoose.add(added.id);
				send(still, Protocol.looseResult(new Protocol.LooseResult(sheet.reference(), added.id, hash)));
			});
		});
	}

	private void moveLoose(Player player, long id, Protocol.Pose pose) {
		LooseSheets.Sheet sheet = this.loose.get(id);
		if (sheet == null || !sheet.owner.equals(player.getUniqueId()) || !pose.isSane()) {
			return;
		}
		Location at = player.getLocation();
		double dx = pose.x() - at.getX();
		double dy = pose.y() - at.getY();
		double dz = pose.z() - at.getZ();
		if (!(dx * dx + dy * dy + dz * dz <= LOOSE_LEASH * LOOSE_LEASH)) {
			return;
		}
		sheet.pose = pose.normalized();
		sheet.touched = System.currentTimeMillis();
		byte[] message = Protocol.loosePose(Protocol.S_LOOSE_POSE, id, sheet.pose);
		for (Map.Entry<UUID, Client> entry : this.clients.entrySet()) {
			if (!entry.getKey().equals(sheet.owner) && entry.getValue().knownLoose.contains(id)) {
				Player watcher = Bukkit.getPlayer(entry.getKey());
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
		LooseSheets.Sheet sheet = this.loose.get(id);
		Location at = player.getLocation();
		boolean allowed = sheet != null && !sheet.owner.equals(player.getUniqueId()) &&
				sheet.world.equals(player.getWorld().getUID()) &&
				sheet.distanceSquared(at.getX(), at.getY(), at.getZ()) <= PIN_REACH * PIN_REACH;
		if (!allowed) {
			// Someone else was faster, or it is gone. The client took it already and has to let go of it again
			client.knownLoose.remove(id);
			send(player, Protocol.looseId(Protocol.S_LOOSE_GONE, id));
			return;
		}
		Player before = Bukkit.getPlayer(sheet.owner);
		Client beforeClient = this.clients.get(sheet.owner);
		if (before != null && beforeClient != null) {
			// forgotten, so the next look at who is near what tells them about it again, as someone else's
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
		this.loose.remove(sheet.id);
		byte[] message = Protocol.looseId(Protocol.S_LOOSE_GONE, sheet.id);
		for (Map.Entry<UUID, Client> entry : this.clients.entrySet()) {
			if (entry.getValue().knownLoose.remove(sheet.id) && (alsoOwner || !entry.getKey().equals(sheet.owner))) {
				Player watcher = Bukkit.getPlayer(entry.getKey());
				if (watcher != null) {
					send(watcher, message);
				}
			}
		}
	}

	/**
	 * @return if the player may put up pictures that are not photos taken in the game. Whether one is, is what
	 * its client says: the server can't tell a screenshot from any other picture
	 */
	private boolean mayCustom(Player player) {
		return this.allowCustom && player.hasPermission("vrcamera.custom");
	}

	private void wantImage(Client client, long hash) {
		// only so many wait at once, a client can't make the server queue up without end
		if (client.wantedImages.size() >= this.imageQueue || client.wantedImages.contains(hash)) {
			return;
		}
		// only pictures of sheets it was told about, not whatever hash it comes up with
		for (long id : client.known) {
			StoredSheet sheet = this.store.get(id);
			if (sheet != null && sheet.imageHash() == hash) {
				client.wantedImages.add(hash);
				return;
			}
		}
		for (long id : client.knownLoose) {
			LooseSheets.Sheet sheet = this.loose.get(id);
			if (sheet != null && sheet.imageHash == hash) {
				client.wantedImages.add(hash);
				return;
			}
		}
	}

	private void sendImages() {
		for (Map.Entry<UUID, Client> entry : this.clients.entrySet()) {
			Long hash = entry.getValue().wantedImages.poll();
			Player player = hash == null ? null : Bukkit.getPlayer(entry.getKey());
			if (player == null) {
				continue;
			}
			byte[] image = this.loose.image(hash);
			if (image == null) {
				image = this.store.image(hash);
			}
			if (image != null) {
				send(player, Protocol.image(hash, image));
			}
		}
	}

	private void pin(Player player, Client client, Protocol.Pin pin) {
		byte refusal = refusal(player, client, pin);
		if (refusal != Protocol.PIN_OK) {
			getLogger().info("Photo of " + player.getName() + " not pinned, reason " + refusal);
			send(player, Protocol.pinResult(new Protocol.PinResult(pin.reference(), refusal, 0, 0)));
			return;
		}
		client.lastPin = System.currentTimeMillis();
		client.pinning = true;
		UUID world = player.getWorld().getUID();
		UUID owner = player.getUniqueId();
		String ownerName = player.getName();
		// reading the picture and writing it to disk is not for the server thread
		Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
			long hash = 0;
			CleanPicture clean = clean(pin.image(), this.maxImageBytes);
			if (clean != null) {
				try {
					hash = hash(clean.jpeg);
					this.store.writeImage(hash, clean.jpeg);
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
		Player player = Bukkit.getPlayer(owner);
		Client client = this.clients.get(owner);
		if (client != null) {
			client.pinning = false;
		}
		// The turn as a unit quaternion. Anything else also scales, and a sheet could be made to fill the sky
		float length = (float) Math.sqrt(pin.qx() * pin.qx() + pin.qy() * pin.qy() + pin.qz() * pin.qz() +
				pin.qw() * pin.qw());
		if (length < 1.0E-3F) {
			imageHash = 0;
			length = 1.0F;
		}
		byte result = imageHash == 0 ? Protocol.PIN_BAD_IMAGE : Protocol.PIN_OK;
		// asked again: two pins of one player can be on their way at once
		if (result == Protocol.PIN_OK && this.store.ownedBy(owner) >= this.maxPerPlayer) {
			result = Protocol.PIN_TOO_MANY;
		}
		// the shape of the sheet is the shape of the picture, not what the client says it is
		StoredSheet sheet = new StoredSheet(this.store.newId(), world, owner, ownerName, pin.blockX(), pin.blockY(),
				pin.blockZ(), pin.x(), pin.y(), pin.z(), pin.qx() / length, pin.qy() / length, pin.qz() / length,
				pin.qw() / length, clean == null ? 1.0F : clean.aspect, imageHash, pin.custom());
		if (result == Protocol.PIN_OK && this.store.inChunk(sheet.chunk()).size() >= this.maxPerChunk) {
			result = Protocol.PIN_CHUNK_FULL;
		}
		long kept = imageHash;
		if (result != Protocol.PIN_OK || player == null || client == null) {
			getLogger().info("Photo of " + ownerName + " not pinned, reason " + result);
			if (kept != 0 && !this.store.hasImage(kept)) {
				Bukkit.getScheduler().runTaskAsynchronously(this, () -> this.store.deleteImage(kept));
			}
			if (player != null) {
				send(player, Protocol.pinResult(new Protocol.PinResult(pin.reference(), result, 0, 0)));
			}
			return;
		}
		this.store.add(sheet);
		getLogger().info(ownerName + " pinned photo " + sheet.id() + " at " + pin.blockX() + " " + pin.blockY() + " " +
				pin.blockZ());
		this.store.cacheImage(imageHash, clean.jpeg);
		// The one who pinned it has it already. The others get it with the next look at who is near what
		client.known.add(sheet.id());
		send(player, Protocol.pinResult(new Protocol.PinResult(pin.reference(), Protocol.PIN_OK, sheet.id(),
				imageHash)));
	}

	/**
	 * @return why the player can't pin this, {@link Protocol#PIN_OK} if they can
	 */
	private byte refusal(Player player, Client client, Protocol.Pin pin) {
		if (!player.hasPermission("vrcamera.pin") || (pin.custom() && !mayCustom(player))) {
			return Protocol.PIN_NOT_ALLOWED;
		}
		if (client.pinning || System.currentTimeMillis() - client.lastPin < this.pinCooldown) {
			return Protocol.PIN_TOO_FAST;
		}
		boolean sane = Double.isFinite(pin.x()) && Double.isFinite(pin.y()) && Double.isFinite(pin.z()) &&
				Float.isFinite(pin.qx()) && Float.isFinite(pin.qy()) && Float.isFinite(pin.qz()) &&
				Float.isFinite(pin.qw());
		if (!sane) {
			return Protocol.PIN_BAD_IMAGE;
		}
		// Within reach of the player, and on the block it claims to be on. Or anyone could pin anywhere
		Location location = player.getLocation();
		double dx = pin.x() - location.getX();
		double dy = pin.y() - location.getY();
		double dz = pin.z() - location.getZ();
		double bx = pin.blockX() + 0.5 - pin.x();
		double by = pin.blockY() + 0.5 - pin.y();
		double bz = pin.blockZ() + 0.5 - pin.z();
		if (dx * dx + dy * dy + dz * dz > PIN_REACH * PIN_REACH || bx * bx + by * by + bz * bz > 4.0) {
			return Protocol.PIN_TOO_FAR;
		}
		// loaded for sure, it is within reach of a player
		if (player.getWorld().getBlockAt(pin.blockX(), pin.blockY(), pin.blockZ()).getType().isAir()) {
			return Protocol.PIN_TOO_FAR;
		}
		if (this.store.size() >= this.maxTotal) {
			return Protocol.PIN_SERVER_FULL;
		}
		if (this.store.ownedBy(player.getUniqueId()) >= this.maxPerPlayer) {
			return Protocol.PIN_TOO_MANY;
		}
		UUID world = player.getWorld().getUID();
		StoredSheet.ChunkKey chunk = new StoredSheet.ChunkKey(world, (int) Math.floor(pin.x()) >> 4,
				(int) Math.floor(pin.z()) >> 4);
		if (this.store.inChunk(chunk).size() >= this.maxPerChunk) {
			return Protocol.PIN_CHUNK_FULL;
		}
		if (pin.image().length < 4 || pin.image().length > this.maxImageBytes) {
			return Protocol.PIN_BAD_IMAGE;
		}
		return Protocol.PIN_OK;
	}

	/**
	 * a picture the server made itself, and its height by its width
	 */
	private record CleanPicture(byte[] jpeg, float aspect) {}

	/**
	 * Every client near the sheet will get this picture and unpack it. So no client ever gets the bytes another
	 * client sent: the picture is unpacked here and packed again. What comes out is a plain small JPEG, whatever
	 * went in, without anything hidden in or appended to the file. Its size in pixels is looked at before it
	 * is unpacked, a few bytes can claim to be a picture of a billion pixels.
	 *
	 * @return null if it is not a small JPEG
	 */
	private static CleanPicture clean(byte[] image, int maxBytes) {
		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(image))) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext()) {
				return null;
			}
			ImageReader reader = readers.next();
			BufferedImage read;
			try {
				reader.setInput(in);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (!reader.getFormatName().toLowerCase().contains("jp") || width < 8 || height < 8 ||
						width > Protocol.MAX_IMAGE_SIDE || height > Protocol.MAX_IMAGE_SIDE)
				{
					return null;
				}
				read = reader.read(0);
			} finally {
				reader.dispose();
			}
			BufferedImage plain = new BufferedImage(read.getWidth(), read.getHeight(), BufferedImage.TYPE_INT_RGB);
			plain.getGraphics().drawImage(read, 0, 0, null);
			for (float quality : QUALITIES) {
				byte[] jpeg = jpeg(plain, quality);
				if (jpeg.length <= maxBytes) {
					return new CleanPicture(jpeg, plain.getHeight() / (float) plain.getWidth());
				}
			}
			return null;
		} catch (IOException | RuntimeException e) {
			// the readers of Java throw all kinds of things at broken files
			return null;
		}
	}

	private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
		if (!writers.hasNext()) {
			throw new IOException("this Java can't write JPEG");
		}
		ImageWriter writer = writers.next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(16_384);
		try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);
			writer.setOutput(out);
			writer.write(null, new IIOImage(image, null, null), param);
		} finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	/**
	 * @return if the client did not send more than it may. One that keeps at it is not listened to for a while
	 */
	private boolean mayTalk(Player player, Client client) {
		long now = System.currentTimeMillis();
		if (now < client.ignoredUntil) {
			return false;
		}
		long nanos = System.nanoTime();
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

	private static long hash(byte[] image) {
		try {
			long hash = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(image)).getLong();
			// 0 stands for no picture
			return hash == 0 ? 1 : hash;
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private void unpin(Player player, long id) {
		StoredSheet sheet = this.store.get(id);
		if (sheet == null) {
			// already gone, the client did not get that yet
			send(player, Protocol.remove(id, Protocol.REMOVED_TAKEN));
			return;
		}
		if (sheet.owner().equals(player.getUniqueId()) || this.anyoneTakesOff ||
				player.hasPermission("vrcamera.remove.others"))
		{
			remove(sheet, Protocol.REMOVED_TAKEN);
		}
	}

	private void remove(StoredSheet sheet, byte reason) {
		long unused = this.store.remove(sheet);
		byte[] message = Protocol.remove(sheet.id(), reason);
		for (Map.Entry<UUID, Client> entry : this.clients.entrySet()) {
			if (entry.getValue().known.remove(sheet.id())) {
				Player player = Bukkit.getPlayer(entry.getKey());
				if (player != null) {
					send(player, message);
				}
			}
		}
		if (unused != 0) {
			Bukkit.getScheduler().runTaskAsynchronously(this, () -> this.store.deleteImage(unused));
		}
	}

	/**
	 * tells every client about the photos that came into its range, and to forget the ones that are far away
	 */
	private void updateRanges() {
		// nobody said anything about it for too long: its owner is gone in some way that was not noticed
		long expired = System.currentTimeMillis() - this.looseLifetime;
		for (LooseSheets.Sheet sheet : new ArrayList<>(this.loose.all())) {
			if (sheet.touched < expired) {
				removeLoose(sheet, true);
			}
		}
		double send = this.sendRange * this.sendRange;
		double forget = this.forgetRange * this.forgetRange;
		int chunks = (int) Math.ceil(this.sendRange / 16.0);
		for (Map.Entry<UUID, Client> entry : this.clients.entrySet()) {
			Player player = Bukkit.getPlayer(entry.getKey());
			if (player == null) {
				continue;
			}
			Client client = entry.getValue();
			Location at = player.getLocation();
			UUID world = player.getWorld().getUID();
			boolean removesOthers = this.anyoneTakesOff || player.hasPermission("vrcamera.remove.others");

			List<Protocol.Sheet> entered = new ArrayList<>();
			int chunkX = at.getBlockX() >> 4;
			int chunkZ = at.getBlockZ() >> 4;
			for (int x = chunkX - chunks; x <= chunkX + chunks; x++) {
				for (int z = chunkZ - chunks; z <= chunkZ + chunks; z++) {
					for (StoredSheet sheet : this.store.inChunk(new StoredSheet.ChunkKey(world, x, z))) {
						if (sheet.distanceSquared(at.getX(), at.getY(), at.getZ()) <= send &&
								client.known.add(sheet.id()))
						{
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

			for (LooseSheets.Sheet sheet : this.loose.all()) {
				if (sheet.world.equals(world) && sheet.distanceSquared(at.getX(), at.getY(), at.getZ()) <= send &&
						client.knownLoose.add(sheet.id))
				{
					send(player, Protocol.loose(sheet.toProtocol()));
				}
			}
			for (Iterator<Long> known = client.knownLoose.iterator(); known.hasNext(); ) {
				LooseSheets.Sheet sheet = this.loose.get(known.next());
				if (sheet == null) {
					known.remove();
				} else if (!sheet.owner.equals(player.getUniqueId()) && (!sheet.world.equals(world) ||
						sheet.distanceSquared(at.getX(), at.getY(), at.getZ()) > forget))
				{
					// its own are the client's to keep track of, wherever it walks
					send(player, Protocol.looseId(Protocol.S_LOOSE_GONE, sheet.id));
					known.remove();
				}
			}

			List<Long> left = new ArrayList<>();
			for (Iterator<Long> known = client.known.iterator(); known.hasNext(); ) {
				StoredSheet sheet = this.store.get(known.next());
				if (sheet == null) {
					known.remove();
				} else if (!sheet.world().equals(world) ||
						sheet.distanceSquared(at.getX(), at.getY(), at.getZ()) > forget)
				{
					left.add(sheet.id());
					known.remove();
				}
			}
			if (!left.isEmpty()) {
				send(player, Protocol.forget(left));
			}
		}
	}

	@EventHandler
	public void onChannel(PlayerRegisterChannelEvent event) {
		// its hello came before this, and the answer to that went nowhere
		if (Protocol.CHANNEL.equals(event.getChannel()) && this.clients.containsKey(event.getPlayer().getUniqueId())) {
			sendHello(event.getPlayer());
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		this.loose.of(event.getPlayer().getUniqueId()).forEach(sheet -> removeLoose(sheet, false));
		this.clients.remove(event.getPlayer().getUniqueId());
	}

	@EventHandler
	public void onWorldChange(PlayerChangedWorldEvent event) {
		// what a player left lying in the other world is not theirs to move anymore
		this.loose.of(event.getPlayer().getUniqueId()).forEach(sheet -> removeLoose(sheet, false));
		Client client = this.clients.get(event.getPlayer().getUniqueId());
		if (client != null) {
			client.known.clear();
			client.knownLoose.clear();
			client.wantedImages.clear();
			send(event.getPlayer(), Protocol.reset());
		}
	}

	/**
	 * what a photo is pinned to is gone, it falls
	 */
	private void blockGone(Block block) {
		List<StoredSheet> pinned = this.store.onBlock(new StoredSheet.BlockKey(block.getWorld().getUID(),
				block.getX(), block.getY(), block.getZ()));
		if (!pinned.isEmpty()) {
			for (StoredSheet sheet : new ArrayList<>(pinned)) {
				remove(sheet, Protocol.REMOVED_FELL);
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBreak(BlockBreakEvent event) {
		blockGone(event.getBlock());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBurn(BlockBurnEvent event) {
		blockGone(event.getBlock());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDecay(LeavesDecayEvent event) {
		blockGone(event.getBlock());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBlockExplode(BlockExplodeEvent event) {
		event.blockList().forEach(this::blockGone);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onEntityExplode(EntityExplodeEvent event) {
		event.blockList().forEach(this::blockGone);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPistonExtend(BlockPistonExtendEvent event) {
		event.getBlocks().forEach(this::blockGone);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPistonRetract(BlockPistonRetractEvent event) {
		event.getBlocks().forEach(this::blockGone);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 1 && args[0].equalsIgnoreCase("stats")) {
			sender.sendMessage("VRCameraSync: " + this.store.size() + " pinned photos, " + this.clients.size() +
					" players with the mod online");
			return true;
		}
		if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
			readConfig();
			sender.sendMessage("VRCameraSync: config read again. Limits apply to new pins, timings after a restart");
			return true;
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("purge")) {
			UUID owner = Bukkit.getOfflinePlayer(args[1]).getUniqueId();
			sender.sendMessage("VRCameraSync: removed " + purge(sheet -> sheet.owner().equals(owner) ||
					sheet.ownerName().equalsIgnoreCase(args[1])) + " photos of " + args[1]);
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
			Location at = player.getLocation();
			UUID world = player.getWorld().getUID();
			sender.sendMessage("VRCameraSync: removed " + purge(sheet -> sheet.world().equals(world) &&
					sheet.distanceSquared(at.getX(), at.getY(), at.getZ()) <= radius * radius) + " photos");
			return true;
		}
		return false;
	}

	private int purge(java.util.function.Predicate<StoredSheet> which) {
		List<StoredSheet> gone = new ArrayList<>();
		for (StoredSheet sheet : this.store.all()) {
			if (which.test(sheet)) {
				gone.add(sheet);
			}
		}
		gone.forEach(sheet -> remove(sheet, Protocol.REMOVED_TAKEN));
		return gone.size();
	}
}
