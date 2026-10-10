package ru.deelter.vrcamera.client.sync;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.CameraEffects;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;
import ru.deelter.vrcamera.client.photo.PhotoSheet;
import ru.deelter.vrcamera.client.photo.PhotoStore;
import ru.deelter.vrcamera.sync.Protocol;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Shares pinned photo sheets with the other players, on servers that run the VRCameraSync plugin. Without it
 * nothing here does anything, and sheets are kept on this client alone.
 * <p>
 * The server tells about the sheets around in a few bytes each. Their pictures are only asked for when the player
 * is close enough to see them, one after the other, kept on disk by their hash to never be asked for twice, and
 * dropped from memory when the player walks away.
 */
public final class PhotoSync {
	public static final PhotoSync INSTANCE = new PhotoSync();

	private static final double LOAD_DISTANCE = 16.0;
	private static final double UNLOAD_DISTANCE = 24.0;
	private static final int MAX_LOADED = 48;
	private static final int MAX_WAITING = 2;
	private static final double EVICT_MARGIN = 2.0;
	private static final int WAIT_TICKS = 100;
	private static final int SCAN_INTERVAL_TICKS = 5;
	private static final int PACKED_CACHE = 64;
	private static final int HELLO_TRIES = 15;
	private static final int HELLO_INTERVAL_TICKS = 40;
	private static final int PIN_WAIT_TICKS = 200;
	private static final int POSE_INTERVAL_TICKS = 4;
	private static final int MAX_LOOSE_KNOWN = 512;
	private static final int MAX_KNOWN = 4096;
	private static final float MIN_ASPECT = 0.25F;

	private static final long CAMERA_KEEP_ALIVE_NANOS = 1_000_000_000L;
	private final Quaternionf sharedTurn = new Quaternionf();
	private final Map<Long, Protocol.Sheet> known = new HashMap<>();
	private final Set<Long> loaded = new HashSet<>();
	private final Map<Long, Integer> waiting = new HashMap<>();
	private final Set<Long> askedServer = new HashSet<>();
	private final Map<Long, byte[]> packed = new LinkedHashMap<>(32, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
			return size() > PACKED_CACHE;
		}
	};
	private final Map<Long, PhotoSheet> pinning = new HashMap<>();
	private final Map<Long, Integer> pinningSince = new HashMap<>();
	private final Map<Long, Protocol.Loose> looseKnown = new HashMap<>();
	private final Set<Long> ghosts = new HashSet<>();
	private final Map<Long, PhotoSheet> sharing = new HashMap<>();
	private final List<PhotoSheet> packedToShare = new ArrayList<>();
	private boolean connected;
	private float photoCooldown;
	private boolean takesIndexed;
	private Protocol.Rules rules = Protocol.Rules.NONE;
	private long lastPhoto;
	private Vec3 sharedAt;
	private long sharedNanos;
	private Protocol.Limits limits;
	private int hellos;
	private boolean shownCustom;
	private long nextReference = 1;
	private int ticks;

	private PhotoSync() {
	}

	private static void send(byte[] message) {
		ClientPlayNetworking.send(new SyncPayload(message));
	}

	private static Protocol.Pose pose(PhotoSheet sheet) {
		return new Protocol.Pose(sheet.position().x, sheet.position().y, sheet.position().z, sheet.rotation().x,
				sheet.rotation().y, sheet.rotation().z, sheet.rotation().w);
	}

	/**
	 * @return the picture as the game wants it. A new one every time: each sheet owns its own, also if two show
	 * the same
	 */
	private static NativeImage black() {
		NativeImage pixels = new NativeImage(2, 2, false);
		for (int i = 0; i < 4; i++) {
			pixels.setPixel(i % 2, i / 2, 0xFF000000);
		}
		return pixels;
	}

	private static void refuse(PhotoSheet sheet, String message) {
		PhotoAlbum.INSTANCE.pinRefused(sheet);
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			player.displayClientMessage(Component.translatable(message), true);
		}
	}

	public void init() {
		PayloadTypeRegistry.playC2S().register(SyncPayload.TYPE, SyncPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(SyncPayload.TYPE, SyncPayload.CODEC);
		ClientPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE,
				(payload, context) -> context.client().execute(() -> receive(payload.data())));
		ClientPlayConnectionEvents.JOIN.register((listener, sender, mc) -> {
			reset();
			if (ClientPlayNetworking.canSend(SyncPayload.TYPE)) {
				send(Protocol.clientHello(Vrcamera.version()));
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((listener, mc) -> mc.execute(this::reset));
	}

	/**
	 * @return if a server keeps the pinned sheets, and this client does not have to
	 */
	public boolean isConnected() {
		return this.connected;
	}

	private void reset() {
		DesktopCamera.INSTANCE.serverGone();
		this.connected = false;
		photoCooldown = 0.0F;
		takesIndexed = false;
		rules = Protocol.Rules.NONE;
		this.limits = null;
		this.known.clear();
		this.loaded.clear();
		this.waiting.clear();
		this.askedServer.clear();
		this.pinning.clear();
		this.pinningSince.clear();
		this.hellos = 0;
		RemoteCameras.INSTANCE.clear();
		this.looseKnown.clear();
		this.ghosts.clear();
		this.sharing.clear();
		this.packedToShare.clear();
	}

	/**
	 * The album dropped all its sheets: the player died and came back, or changed the dimension. The ones the
	 * server told about are still known, and come back from here the next time the player is near them
	 */
	public void sheetsDropped() {
		this.ghosts.clear();
		this.sharing.clear();
		this.packedToShare.clear();
		this.loaded.clear();
		this.waiting.clear();
		this.askedServer.clear();
		this.pinning.clear();
		this.pinningSince.clear();
	}

	/**
	 * @param type {@link Protocol#C_SHUTTER} or {@link Protocol#C_PRINT}
	 */
	public void shareCameraSound(byte type, Vec3 position) {
		if (this.connected) {
			send(Protocol.cameraSound(type, position.x, position.y, position.z));
		}
	}

	/**
	 * tells the players around where this player's camera is
	 */
	public void shareCamera(Vec3 position, Quaternionf rotation) {
		if (!this.connected) {
			return;
		}
		long now = System.nanoTime();
		boolean still = this.sharedAt != null && position.distanceToSqr(this.sharedAt) < 1.0E-6 &&
				Math.abs(rotation.dot(this.sharedTurn)) > 0.99999F;
		if (still && now - this.sharedNanos < CAMERA_KEEP_ALIVE_NANOS) {
			return;
		}
		this.sharedAt = position;
		this.sharedTurn.set(rotation);
		this.sharedNanos = now;
		send(Protocol.camera(position.x, position.y, position.z, rotation.x, rotation.y, rotation.z, rotation.w));
	}

	/**
	 * tells the server which of the free cameras films now
	 *
	 * @param id what a server that gave the camera calls it, null for one the player made
	 */
	public void shareSwitch(String name, String id, Vec3 position) {
		if (this.connected) {
			send(Protocol.cameraSwitch(
					new Protocol.Switched(name, id == null ? "" : id, position.x, position.y, position.z)));
		}
	}

	private void receive(byte[] message) {
		if (message.length == 0) {
			return;
		}
		try {
			DataInputStream in = Protocol.body(message);
			switch (message[0]) {
				case Protocol.S_HELLO -> {
					Protocol.Limits limits = Protocol.readLimits(in);
					photoCooldown = Protocol.readPhotoCooldown(in);
					takesIndexed = Protocol.readTakesIndexed(in);
					if (this.connected) {
						return;
					}
					if (limits.version() == Protocol.VERSION) {
						this.limits = limits;
						this.connected = true;
						PhotoAlbum.INSTANCE.serverTookOver();
						Vrcamera.LOGGER.info("VRCamera: this server shares photos");
					} else {
						Vrcamera.LOGGER.warn("VRCamera: the server speaks photo protocol {}, this mod {}. Photos are not shared",
								limits.version(), Protocol.VERSION);
					}
				}
				case Protocol.S_SHEETS -> Protocol.readSheets(in).forEach(this::learn);
				case Protocol.S_REMOVE -> removed(in.readLong(), in.readByte());
				case Protocol.S_FORGET -> Protocol.readForget(in).forEach(this::forget);
				case Protocol.S_RESET -> {
					new ArrayList<>(this.known.keySet()).forEach(this::forget);
					new ArrayList<>(this.looseKnown.keySet()).forEach(this::looseGone);
				}
				case Protocol.S_LOOSE -> {
					Protocol.Loose loose = Protocol.readLoose(in);
					if (loose.pose().isSane() && loose.aspect() >= MIN_ASPECT && loose.aspect() <= 1.0F / MIN_ASPECT &&
							this.looseKnown.size() < MAX_LOOSE_KNOWN) {
						this.looseKnown.put(loose.id(), new Protocol.Loose(loose.id(), loose.owner(), loose.ownerName(),
								loose.pose().normalized(), loose.aspect(), loose.imageHash(), loose.custom()));
					}
				}
				case Protocol.S_LOOSE_POSE -> {
					long id = in.readLong();
					Protocol.Pose pose = Protocol.readPose(in);
					Protocol.Loose loose = this.looseKnown.get(id);
					if (loose != null && pose.isSane()) {
						Protocol.Pose at = pose.normalized();
						this.looseKnown.put(id, new Protocol.Loose(id, loose.owner(), loose.ownerName(), at,
								loose.aspect(), loose.imageHash(), loose.custom()));
						PhotoAlbum.INSTANCE.moveGhost(id, new Vec3(at.x(), at.y(), at.z()),
								new Quaternionf(at.qx(), at.qy(), at.qz(), at.qw()));
					}
				}
				case Protocol.S_LOOSE_GONE -> looseGone(in.readLong());
				case Protocol.S_LOOSE_RESULT -> shared(Protocol.readLooseResult(in));
				case Protocol.S_IMAGE -> {
					long hash = in.readLong();
					byte[] image = Protocol.readImage(in);
					if (this.askedServer.contains(hash)) {
						gotPacked(hash, image, true);
					}
				}
				case Protocol.S_PIN_RESULT -> pinned(Protocol.readPinResult(in));
				case Protocol.S_CAMERA -> RemoteCameras.INSTANCE.heard(Protocol.readCamera(in, true));
				case Protocol.S_PLACE -> {
					Protocol.Placed camera = Protocol.readPlaced(in);
					if (camera.isSane()) {
						DesktopCamera.INSTANCE.serverPlace(camera.id(), new Vec3(camera.x(), camera.y(), camera.z()),
								camera.yaw(), camera.pitch(), camera.fov(), camera.anyway(), camera.show());
					}
				}
				case Protocol.S_TAKE -> {
					String id = in.readUTF();
					if (id.length() <= Protocol.MAX_CAMERA_ID) {
						DesktopCamera.INSTANCE.serverTake(id, in.readBoolean());
					}
				}
				case Protocol.S_SHOW -> DesktopCamera.INSTANCE.serverShow(in.readUTF(), in.readFloat());
				case Protocol.S_RULES -> rules = Protocol.readRules(in);
				case Protocol.S_SHUTTER, Protocol.S_PRINT -> {
					Vec3 at = new Vec3(in.readDouble(), in.readDouble(), in.readDouble());
					LocalPlayer player = Minecraft.getInstance().player;
					if (player != null && Double.isFinite(at.lengthSqr()) && at.distanceTo(player.position()) < 64) {
						if (message[0] == Protocol.S_SHUTTER) {
							CameraEffects.shutter(player.level(), at);
						} else {
							CameraEffects.printing(player.level(), at);
						}
					}
				}
				default -> {
				}
			}
		} catch (IOException | RuntimeException e) {
			Vrcamera.LOGGER.warn("VRCamera: could not read a message of the photo server", e);
		}
	}

	/**
	 * Takes a sheet the server tells about, if it makes sense. The server is not trusted blindly either: it may
	 * be broken, or not the plugin at all
	 */
	private void learn(Protocol.Sheet sheet) {
		float length = (float) Math.sqrt(sheet.qx() * sheet.qx() + sheet.qy() * sheet.qy() +
				sheet.qz() * sheet.qz() + sheet.qw() * sheet.qw());
		boolean sane = Double.isFinite(sheet.x()) && Double.isFinite(sheet.y()) && Double.isFinite(sheet.z()) &&
				Float.isFinite(length) && length > 1.0E-3F && sheet.aspect() >= MIN_ASPECT &&
				sheet.aspect() <= 1.0F / MIN_ASPECT;
		if (!sane || (this.known.size() >= MAX_KNOWN && !this.known.containsKey(sheet.id()))) {
			return;
		}
		this.known.put(sheet.id(), new Protocol.Sheet(sheet.id(), sheet.owner(), sheet.ownerName(), sheet.x(),
				sheet.y(), sheet.z(), sheet.qx() / length, sheet.qy() / length, sheet.qz() / length,
				sheet.qw() / length, sheet.aspect(), sheet.imageHash(), sheet.removable(), sheet.custom()));
	}

	private void looseGone(long id) {
		this.looseKnown.remove(id);
		this.ghosts.remove(id);
		PhotoAlbum.INSTANCE.removeLoose(id);
	}

	/**
	 * A sheet of this player that is not pinned, to be seen by the others: fresh out of the camera, or taken off
	 * a wall. Refused by the server it is just not shared.
	 *
	 * @param picture the pixels of the sheet, null if it still has what it was sent with before
	 */
	public void shareLoose(PhotoSheet sheet, PhotoCodec.Picture picture) {
		if (!this.connected || (picture == null && sheet.packed == null)) {
			return;
		}
		int maxBytes = Math.min(this.limits.maxImageBytes(), Protocol.MAX_IMAGE_BYTES);
		final boolean indexed = takesIndexed;
		CompletableFuture.supplyAsync(() -> {
			try {
				return sheet.packed != null && sheet.packed.length <= maxBytes ? sheet.packed :
						PhotoCodec.pack(picture, maxBytes, indexed);
			} catch (IOException | RuntimeException e) {
				Vrcamera.LOGGER.warn("VRCamera: the photo could not be packed for the server", e);
				return null;
			}
		}).thenAcceptAsync(image -> {
			if (image == null || !this.connected || !PhotoAlbum.INSTANCE.has(sheet) || sheet.isPinned()) {
				return;
			}
			sheet.packed = image;
			this.packedToShare.add(sheet);
		}, Minecraft.getInstance());
	}

	private void sharePacked() {
		for (Iterator<PhotoSheet> packed = this.packedToShare.iterator(); packed.hasNext(); ) {
			PhotoSheet sheet = packed.next();
			if (!PhotoAlbum.INSTANCE.has(sheet) || sheet.isPinned() || sheet.isGhost()) {
				packed.remove();
			} else if (sheet.position().lengthSqr() > 0) {
				packed.remove();
				long reference = this.nextReference++;
				this.sharing.put(reference, sheet);
				send(Protocol.newLoose(new Protocol.NewLoose(reference, pose(sheet), sheet.packed, sheet.custom)));
			}
		}
	}

	private void shared(Protocol.LooseResult result) {
		PhotoSheet sheet = this.sharing.remove(result.reference());
		if (sheet == null || result.id() == 0) {
			return;
		}
		if (!PhotoAlbum.INSTANCE.has(sheet) || sheet.isPinned() || sheet.isGhost()) {
			dropLoose(result.id());
			return;
		}
		sheet.setLooseId(result.id());
		this.packed.put(result.imageHash(), sheet.packed);
	}

	public void dropLoose(long id) {
		if (this.connected) {
			send(Protocol.looseId(Protocol.C_LOOSE_DROP, id));
		}
	}

	/**
	 * the player picked up a loose sheet of someone else
	 */
	public void takeLoose(long id) {
		this.looseKnown.remove(id);
		this.ghosts.remove(id);
		if (this.connected) {
			send(Protocol.looseId(Protocol.C_LOOSE_TAKE, id));
		}
	}

	private void forget(long id) {
		this.known.remove(id);
		if (this.loaded.remove(id)) {
			PhotoAlbum.INSTANCE.removeRemote(id, false);
		}
	}

	/**
	 * gets the picture with that hash, from where it is closest: memory, disk, server
	 */

	private void removed(long id, byte reason) {
		this.known.remove(id);
		if (this.loaded.remove(id)) {
			PhotoAlbum.INSTANCE.removeRemote(id, reason == Protocol.REMOVED_FELL);
		}
	}

	/**
	 * Called every client tick. Loads the pictures of the sheets the player came close to, nearest first, and
	 * drops the ones they left behind.
	 */
	public void tick() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) {
			return;
		}
		this.ticks++;
		if (!this.connected) {
			if (this.hellos < HELLO_TRIES && this.ticks % HELLO_INTERVAL_TICKS == 0 &&
					ClientPlayNetworking.canSend(SyncPayload.TYPE)) {
				this.hellos++;
				send(Protocol.clientHello(Vrcamera.version()));
			}
			return;
		}
		this.pinning.entrySet().removeIf(pin -> {
			boolean overdue = this.ticks - this.pinningSince.getOrDefault(pin.getKey(), this.ticks) > PIN_WAIT_TICKS;
			if (overdue) {
				this.pinningSince.remove(pin.getKey());
				refuse(pin.getValue(), "vrcamera.message.pin.refused");
			}
			return overdue;
		});
		sharePacked();
		if (this.ticks % POSE_INTERVAL_TICKS == 0) {
			PhotoAlbum.INSTANCE.forEachShared(sheet -> {
				if (sheet.movedSinceShared()) {
					send(Protocol.loosePose(Protocol.C_LOOSE_POSE, sheet.looseId(), pose(sheet)));
				}
			});
		}
		if (this.ticks % SCAN_INTERVAL_TICKS != 0) {
			return;
		}
		this.waiting.values().removeIf(since -> this.ticks - since > WAIT_TICKS);
		this.askedServer.retainAll(this.waiting.keySet());

		boolean showOthers = CameraConfig.current().showOthersPhotos;
		boolean showCustom = CameraConfig.current().showCustomPhotos;
		if (showCustom != this.shownCustom) {
			this.shownCustom = showCustom;
			new ArrayList<>(this.loaded).forEach(id -> PhotoAlbum.INSTANCE.removeRemote(id, false));
			new ArrayList<>(this.ghosts).forEach(PhotoAlbum.INSTANCE::removeLoose);
			this.loaded.clear();
			this.ghosts.clear();
		}
		Vec3 eyes = player.getEyePosition();
		List<Protocol.Sheet> wanted = new ArrayList<>();
		for (Protocol.Sheet sheet : this.known.values()) {
			double distance = eyes.distanceToSqr(sheet.x(), sheet.y(), sheet.z());
			boolean shown = showOthers || sheet.owner().equals(player.getUUID());
			if (this.loaded.contains(sheet.id())) {
				if (!shown || distance > UNLOAD_DISTANCE * UNLOAD_DISTANCE) {
					this.loaded.remove(sheet.id());
					PhotoAlbum.INSTANCE.removeRemote(sheet.id(), false);
				}
			} else if (shown && distance < LOAD_DISTANCE * LOAD_DISTANCE) {
				if (sheet.custom() && !showCustom && !sheet.owner().equals(player.getUUID())) {
					PhotoSheet standIn = PhotoAlbum.INSTANCE.addRemote(sheet.id(), false,
							new Vec3(sheet.x(), sheet.y(), sheet.z()),
							new Quaternionf(sheet.qx(), sheet.qy(), sheet.qz(), sheet.qw()), sheet.aspect(), black(),
							null);
					if (standIn != null) {
						standIn.placeholder = true;
						this.loaded.add(sheet.id());
					}
				} else {
					wanted.add(sheet);
				}
			}
		}
		wanted.sort(Comparator.comparingDouble(sheet -> eyes.distanceToSqr(sheet.x(), sheet.y(), sheet.z())));
		for (Protocol.Loose loose : this.looseKnown.values()) {
			double distance = eyes.distanceToSqr(loose.pose().x(), loose.pose().y(), loose.pose().z());
			if (this.ghosts.contains(loose.id())) {
				if (!showOthers || distance > UNLOAD_DISTANCE * UNLOAD_DISTANCE) {
					this.ghosts.remove(loose.id());
					PhotoAlbum.INSTANCE.removeLoose(loose.id());
				}
			} else if (showOthers && distance < LOAD_DISTANCE * LOAD_DISTANCE &&
					this.loaded.size() + this.ghosts.size() + this.waiting.size() < MAX_LOADED) {
				if (loose.custom() && !showCustom) {
					Protocol.Pose at = loose.pose();
					PhotoSheet standIn = PhotoAlbum.INSTANCE.addGhost(loose.id(), new Vec3(at.x(), at.y(), at.z()),
							new Quaternionf(at.qx(), at.qy(), at.qz(), at.qw()), loose.aspect(), black(), null);
					if (standIn != null) {
						standIn.placeholder = true;
						this.ghosts.add(loose.id());
					}
				} else {
					fetch(loose.imageHash());
				}
			}
		}
		for (Protocol.Sheet sheet : wanted) {
			if (this.waiting.containsKey(sheet.imageHash())) {
				continue;
			}
			if (!canFetch(sheet.imageHash())) {
				break;
			}
			if (this.loaded.size() + this.waiting.size() >= MAX_LOADED) {
				Protocol.Sheet farthest = null;
				double farthestDistance = Math.sqrt(eyes.distanceToSqr(sheet.x(), sheet.y(), sheet.z())) + EVICT_MARGIN;
				for (long id : this.loaded) {
					Protocol.Sheet other = this.known.get(id);
					double distance = other == null ? 0 :
							Math.sqrt(eyes.distanceToSqr(other.x(), other.y(), other.z()));
					if (distance > farthestDistance) {
						farthest = other;
						farthestDistance = distance;
					}
				}
				if (farthest == null) {
					break;
				}
				this.loaded.remove(farthest.id());
				PhotoAlbum.INSTANCE.removeRemote(farthest.id(), false);
			}
			fetch(sheet.imageHash());
		}
	}

	/**
	 * @return if {@link #fetch} would start to get that picture now
	 */
	private boolean canFetch(long hash) {
		return this.packed.containsKey(hash) || this.askedServer.size() < MAX_WAITING;
	}

	private void fetch(long hash) {
		if (this.waiting.containsKey(hash)) {
			return;
		}
		byte[] inMemory = this.packed.get(hash);
		if (inMemory != null) {
			this.waiting.put(hash, this.ticks);
			unpack(hash, inMemory);
			return;
		}
		if (this.askedServer.size() >= MAX_WAITING) {
			return;
		}
		this.waiting.put(hash, this.ticks);
		this.askedServer.add(hash);
		CompletableFuture.supplyAsync(() -> PhotoStore.readRemote(hash))
				.thenAcceptAsync(onDisk -> {
					if (!this.connected || !this.waiting.containsKey(hash)) {
						return;
					}
					if (onDisk != null) {
						this.askedServer.remove(hash);
						gotPacked(hash, onDisk, false);
					} else {
						send(Protocol.imageRequest(hash));
					}
				}, Minecraft.getInstance());
	}

	private void gotPacked(long hash, byte[] image, boolean fromServer) {
		this.askedServer.remove(hash);
		this.packed.put(hash, image);
		if (fromServer) {
			CompletableFuture.runAsync(() -> PhotoStore.writeRemote(hash, image));
		}
		if (this.waiting.containsKey(hash)) {
			unpack(hash, image);
		}
	}

	private void unpack(long hash, byte[] image) {
		CompletableFuture.supplyAsync(() -> {
			try {
				return PhotoCodec.unpack(image);
			} catch (IOException e) {
				Vrcamera.LOGGER.warn("VRCamera: a photo of the server can't be shown", e);
				return null;
			}
		}).thenAcceptAsync(picture -> {
			this.waiting.remove(hash);
			if (picture != null && this.connected) {
				show(hash, picture, image);
			}
		}, Minecraft.getInstance());
	}

	/**
	 * puts every known sheet with that picture into the world
	 */
	private void show(long hash, PhotoCodec.Picture picture, byte[] image) {
		for (Protocol.Loose loose : this.looseKnown.values()) {
			if (loose.imageHash() == hash && !(loose.custom() && !this.shownCustom) &&
					this.ghosts.add(loose.id())) {
				Protocol.Pose at = loose.pose();
				PhotoSheet ghost = PhotoAlbum.INSTANCE.addGhost(loose.id(), new Vec3(at.x(), at.y(), at.z()),
						new Quaternionf(at.qx(), at.qy(), at.qz(), at.qw()), loose.aspect(), PhotoAlbum.image(picture), image);
				if (ghost != null) {
					ghost.custom = loose.custom();
				}
			}
		}
		for (Protocol.Sheet sheet : this.known.values()) {
			LocalPlayer player = Minecraft.getInstance().player;
			boolean own = player != null && sheet.owner().equals(player.getUUID());
			if (sheet.imageHash() != hash || this.loaded.contains(sheet.id()) ||
					(sheet.custom() && !this.shownCustom && !own)) {
				continue;
			}
			PhotoSheet shown = PhotoAlbum.INSTANCE.addRemote(sheet.id(), sheet.removable(),
					new Vec3(sheet.x(), sheet.y(), sheet.z()),
					new Quaternionf(sheet.qx(), sheet.qy(), sheet.qz(), sheet.qw()), sheet.aspect(),
					PhotoAlbum.image(picture), image);
			if (shown != null) {
				shown.custom = sheet.custom();
				shown.own = isOwn(sheet.owner());
			}
			this.loaded.add(sheet.id());
		}
	}

	/**
	 * The player pinned a sheet. The server is asked to keep it, and may refuse.
	 *
	 * @param picture the pixels of the sheet, null if it still has what it was sent with before
	 */
	public void pin(PhotoSheet sheet, PhotoCodec.Picture picture) {
		sheet.awaitServer();
		int maxBytes = Math.min(this.limits.maxImageBytes(), Protocol.MAX_IMAGE_BYTES);
		final boolean indexed = takesIndexed;
		CompletableFuture.supplyAsync(() -> {
			try {
				return sheet.packed != null && sheet.packed.length <= maxBytes ? sheet.packed :
						PhotoCodec.pack(picture, maxBytes, indexed);
			} catch (IOException | RuntimeException e) {
				Vrcamera.LOGGER.warn("VRCamera: the photo could not be packed for the server", e);
				return null;
			}
		}).thenAcceptAsync(image -> {
			if (image == null || !this.connected) {
				refuse(sheet, "vrcamera.message.pin.refused");
				return;
			}
			sheet.packed = image;
			long reference = this.nextReference++;
			this.pinning.put(reference, sheet);
			this.pinningSince.put(reference, this.ticks);
			send(Protocol.pin(new Protocol.Pin(reference, sheet.support().getX(), sheet.support().getY(),
					sheet.support().getZ(), sheet.position().x, sheet.position().y, sheet.position().z,
					sheet.rotation().x, sheet.rotation().y, sheet.rotation().z, sheet.rotation().w, sheet.aspect,
					image, sheet.custom)));
		}, Minecraft.getInstance());
	}

	private void pinned(Protocol.PinResult result) {
		this.pinningSince.remove(result.reference());
		PhotoSheet sheet = this.pinning.remove(result.reference());
		if (sheet == null) {
			return;
		}
		if (result.result() != Protocol.PIN_OK) {
			refuse(sheet, switch (result.result()) {
				case Protocol.PIN_TOO_MANY -> "vrcamera.message.pin.too_many";
				case Protocol.PIN_CHUNK_FULL -> "vrcamera.message.pin.chunk_full";
				case Protocol.PIN_TOO_FAST -> "vrcamera.message.pin.too_fast";
				case Protocol.PIN_NOT_ALLOWED -> "vrcamera.message.pin.not_allowed";
				default -> "vrcamera.message.pin.refused";
			});
			return;
		}
		sheet.setRemote(result.id(), true);
		this.loaded.add(result.id());
		if (sheet.packed != null) {
			this.packed.put(result.imageHash(), sheet.packed);
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			this.known.put(result.id(), new Protocol.Sheet(result.id(), player.getUUID(), player.getName().getString(),
					sheet.position().x, sheet.position().y, sheet.position().z, sheet.rotation().x,
					sheet.rotation().y, sheet.rotation().z, sheet.rotation().w, sheet.aspect, result.imageHash(),
					true, sheet.custom));
		}
	}

	/**
	 * the player took a sheet off that the server knows
	 */
	public void unpin(long id) {
		this.known.remove(id);
		this.loaded.remove(id);
		if (this.connected) {
			send(Protocol.unpin(id));
		}
	}

	private static boolean isOwn(UUID owner) {
		final LocalPlayer player = Minecraft.getInstance().player;
		return player != null && owner.equals(player.getUUID());
	}

	/**
	 * @return what the server lets the camera of the player do, everything where there is no such server
	 */
	public Protocol.Rules rules() {
		return connected ? rules : Protocol.Rules.NONE;
	}

	/**
	 * @return if the server lets the player take a photo right now. Says how long to wait where it does not
	 */
	public boolean mayTakePhoto() {
		if (!rules.cameraAllowed()) {
			final LocalPlayer denied = Minecraft.getInstance().player;
			if (denied != null) {
				denied.displayClientMessage(Component.translatable("vrcamera.message.denied"), true);
			}
			return false;
		}
		final long now = System.nanoTime();
		final double left = connected && lastPhoto != 0 ? photoCooldown - (now - lastPhoto) / 1.0E9 : 0.0;
		if (left > 0) {
			final LocalPlayer player = Minecraft.getInstance().player;
			if (player != null) {
				player.displayClientMessage(Component.translatable("vrcamera.message.photo.wait",
						String.format(Locale.ROOT, "%.1f", left)), true);
			}
			return false;
		}
		lastPhoto = now;
		return true;
	}
}
