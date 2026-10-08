package ru.deelter.vrcamera.client.sync;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
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
	private boolean connected;

	private Vec3 sharedAt;
	private final Quaternionf sharedTurn = new Quaternionf();
	private long sharedNanos;
	private Protocol.Limits limits;

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
	private int hellos;
	private boolean shownCustom;
	private long nextReference = 1;
	private int ticks;

	public void init() {
		PayloadTypeRegistry.serverboundPlay().register(SyncPayload.TYPE, SyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SyncPayload.TYPE, SyncPayload.CODEC);
		ClientPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE,
				(payload, context) -> context.client().execute(() -> receive(payload.data())));
		ClientPlayConnectionEvents.JOIN.register((listener, sender, mc) -> {
			reset();

			if (ClientPlayNetworking.canSend(SyncPayload.TYPE)) {
				send(Protocol.clientHello());
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((listener, mc) -> mc.execute(this::reset));
	}

	/**
	 * @return if a server keeps the pinned sheets, and this client does not have to
	 */
	public boolean isConnected() {
		return connected;
	}

	/**
	 * The album dropped all its sheets: the player died and came back, or changed the dimension. The ones the
	 * server told about are still known, and come back from here the next time the player is near them
	 */
	public void sheetsDropped() {
		ghosts.clear();
		sharing.clear();
		packedToShare.clear();
		loaded.clear();
		waiting.clear();
		askedServer.clear();
		pinning.clear();
		pinningSince.clear();
	}

	/**
	 * @param type {@link Protocol#C_SHUTTER} or {@link Protocol#C_PRINT}
	 */
	public void shareCameraSound(byte type, Vec3 position) {
		if (connected) {
			send(Protocol.cameraSound(type, position.x, position.y, position.z));
		}
	}

	/**
	 * tells the players around where this player's camera is
	 */
	public void shareCamera(Vec3 position, Quaternionf rotation) {
		if (!connected) {
			return;
		}

		final long now = System.nanoTime();
		final boolean still = sharedAt != null && position.distanceToSqr(sharedAt) < 1.0E-6 &&
				Math.abs(rotation.dot(sharedTurn)) > 0.99999F;
		if (still && now - sharedNanos < CAMERA_KEEP_ALIVE_NANOS) {
			return;
		}
		sharedAt = position;
		sharedTurn.set(rotation);
		sharedNanos = now;
		send(Protocol.camera(position.x, position.y, position.z, rotation.x, rotation.y, rotation.z, rotation.w));
	}

	/**
	 * tells the server which of the free cameras films now
	 *
	 * @param id what a server that gave the camera calls it, null for one the player made
	 */
	public void shareSwitch(String name, String id, Vec3 position) {
		if (connected) {
			send(Protocol.cameraSwitch(
					new Protocol.Switched(name, id == null ? "" : id, position.x, position.y, position.z)));
		}
	}

	/**
	 * A sheet of this player that is not pinned, to be seen by the others: fresh out of the camera, or taken off
	 * a wall. Refused by the server it is just not shared.
	 *
	 * @param picture the pixels of the sheet, null if it still has what it was sent with before
	 */
	public void shareLoose(PhotoSheet sheet, PhotoCodec.Picture picture) {
		if (!connected || (picture == null && sheet.packed == null)) {
			return;
		}
		final int maxBytes = Math.min(limits.maxImageBytes(), Protocol.MAX_IMAGE_BYTES);
		CompletableFuture.supplyAsync(() -> {
			try {
				return sheet.packed != null && sheet.packed.length <= maxBytes ? sheet.packed :
						PhotoCodec.pack(picture, maxBytes);
			} catch (IOException | RuntimeException e) {
				Vrcamera.LOGGER.warn("VRCamera: the photo could not be packed for the server", e);
				return null;
			}
		}).thenAcceptAsync(image -> {
			if (image == null || !connected || !PhotoAlbum.INSTANCE.has(sheet) || sheet.isPinned()) {
				return;
			}
			sheet.packed = image;

			packedToShare.add(sheet);
		}, Minecraft.getInstance());
	}

	public void dropLoose(long id) {
		if (connected) {
			send(Protocol.looseId(Protocol.C_LOOSE_DROP, id));
		}
	}

	/**
	 * the player picked up a loose sheet of someone else
	 */
	public void takeLoose(long id) {
		looseKnown.remove(id);
		ghosts.remove(id);
		if (connected) {
			send(Protocol.looseId(Protocol.C_LOOSE_TAKE, id));
		}
	}

	/**
	 * Called every client tick. Loads the pictures of the sheets the player came close to, nearest first, and
	 * drops the ones they left behind.
	 */
	public void tick() {
		final Minecraft mc = Minecraft.getInstance();
		final LocalPlayer player = mc.player;
		if (player == null) {
			return;
		}
		ticks++;
		if (!connected) {

			if (hellos < HELLO_TRIES && ticks % HELLO_INTERVAL_TICKS == 0 &&
					ClientPlayNetworking.canSend(SyncPayload.TYPE)) {
				hellos++;
				send(Protocol.clientHello());
			}
			return;
		}

		pinning.entrySet().removeIf(pin -> {
			final boolean overdue = ticks - pinningSince.getOrDefault(pin.getKey(), ticks) > PIN_WAIT_TICKS;
			if (overdue) {
				pinningSince.remove(pin.getKey());
				refuse(pin.getValue(), "vrcamera.message.pin.refused");
			}
			return overdue;
		});
		sharePacked();
		if (ticks % POSE_INTERVAL_TICKS == 0) {

			PhotoAlbum.INSTANCE.forEachShared(sheet -> {
				if (sheet.movedSinceShared()) {
					send(Protocol.loosePose(Protocol.C_LOOSE_POSE, sheet.looseId(), pose(sheet)));
				}
			});
		}
		if (ticks % SCAN_INTERVAL_TICKS != 0) {
			return;
		}

		waiting.values().removeIf(since -> ticks - since > WAIT_TICKS);
		askedServer.retainAll(waiting.keySet());

		final boolean showOthers = CameraConfig.current().showOthersPhotos;
		final boolean showCustom = CameraConfig.current().showCustomPhotos;
		if (showCustom != shownCustom) {

			shownCustom = showCustom;
			new ArrayList<>(loaded).forEach(id -> PhotoAlbum.INSTANCE.removeRemote(id, false));
			new ArrayList<>(ghosts).forEach(PhotoAlbum.INSTANCE::removeLoose);
			loaded.clear();
			ghosts.clear();
		}
		final Vec3 eyes = player.getEyePosition();
		final List<Protocol.Sheet> wanted = new ArrayList<>();
		for (final Protocol.Sheet sheet : known.values()) {
			final double distance = eyes.distanceToSqr(sheet.x(), sheet.y(), sheet.z());
			final boolean shown = showOthers || sheet.owner().equals(player.getUUID());
			if (loaded.contains(sheet.id())) {
				if (!shown || distance > UNLOAD_DISTANCE * UNLOAD_DISTANCE) {
					loaded.remove(sheet.id());
					PhotoAlbum.INSTANCE.removeRemote(sheet.id(), false);
				}
			} else if (shown && distance < LOAD_DISTANCE * LOAD_DISTANCE) {
				if (sheet.custom() && !showCustom && !sheet.owner().equals(player.getUUID())) {

					final PhotoSheet standIn = PhotoAlbum.INSTANCE.addRemote(sheet.id(), false,
							new Vec3(sheet.x(), sheet.y(), sheet.z()),
							new Quaternionf(sheet.qx(), sheet.qy(), sheet.qz(), sheet.qw()), sheet.aspect(), black(),
							null);
					if (standIn != null) {
						standIn.placeholder = true;
						loaded.add(sheet.id());
					}
				} else {
					wanted.add(sheet);
				}
			}
		}
		wanted.sort(Comparator.comparingDouble(sheet -> eyes.distanceToSqr(sheet.x(), sheet.y(), sheet.z())));
		for (final Protocol.Loose loose : looseKnown.values()) {
			final double distance = eyes.distanceToSqr(loose.pose().x(), loose.pose().y(), loose.pose().z());
			if (ghosts.contains(loose.id())) {
				if (!showOthers || distance > UNLOAD_DISTANCE * UNLOAD_DISTANCE) {
					ghosts.remove(loose.id());
					PhotoAlbum.INSTANCE.removeLoose(loose.id());
				}
			} else if (showOthers && distance < LOAD_DISTANCE * LOAD_DISTANCE &&
					loaded.size() + ghosts.size() + waiting.size() < MAX_LOADED) {
				if (loose.custom() && !showCustom) {
					final Protocol.Pose at = loose.pose();
					final PhotoSheet standIn = PhotoAlbum.INSTANCE.addGhost(loose.id(), new Vec3(at.x(), at.y(), at.z()),
							new Quaternionf(at.qx(), at.qy(), at.qz(), at.qw()), loose.aspect(), black(), null);
					if (standIn != null) {
						standIn.placeholder = true;
						ghosts.add(loose.id());
					}
				} else {
					fetch(loose.imageHash());
				}
			}
		}
		for (final Protocol.Sheet sheet : wanted) {
			if (waiting.containsKey(sheet.imageHash())) {
				continue;
			}
			if (!canFetch(sheet.imageHash())) {

				break;
			}
			if (loaded.size() + waiting.size() >= MAX_LOADED) {

				Protocol.Sheet farthest = null;
				double farthestDistance = Math.sqrt(eyes.distanceToSqr(sheet.x(), sheet.y(), sheet.z())) + EVICT_MARGIN;
				for (final long id : loaded) {
					final Protocol.Sheet other = known.get(id);
					final double distance = other == null ? 0 :
							Math.sqrt(eyes.distanceToSqr(other.x(), other.y(), other.z()));
					if (distance > farthestDistance) {
						farthest = other;
						farthestDistance = distance;
					}
				}
				if (farthest == null) {
					break;
				}
				loaded.remove(farthest.id());
				PhotoAlbum.INSTANCE.removeRemote(farthest.id(), false);
			}
			fetch(sheet.imageHash());
		}
	}

	/**
	 * The player pinned a sheet. The server is asked to keep it, and may refuse.
	 *
	 * @param picture the pixels of the sheet, null if it still has what it was sent with before
	 */
	public void pin(PhotoSheet sheet, PhotoCodec.Picture picture) {
		sheet.awaitServer();
		final int maxBytes = Math.min(limits.maxImageBytes(), Protocol.MAX_IMAGE_BYTES);
		CompletableFuture.supplyAsync(() -> {
			try {
				return sheet.packed != null && sheet.packed.length <= maxBytes ? sheet.packed :
						PhotoCodec.pack(picture, maxBytes);
			} catch (IOException | RuntimeException e) {
				Vrcamera.LOGGER.warn("VRCamera: the photo could not be packed for the server", e);
				return null;
			}
		}).thenAcceptAsync(image -> {
			if (image == null || !connected) {
				refuse(sheet, "vrcamera.message.pin.refused");
				return;
			}
			sheet.packed = image;
			final long reference = nextReference++;
			pinning.put(reference, sheet);
			pinningSince.put(reference, ticks);
			send(Protocol.pin(new Protocol.Pin(reference, sheet.support().getX(), sheet.support().getY(),
					sheet.support().getZ(), sheet.position().x, sheet.position().y, sheet.position().z,
					sheet.rotation().x, sheet.rotation().y, sheet.rotation().z, sheet.rotation().w, sheet.aspect,
					image, sheet.custom)));
		}, Minecraft.getInstance());
	}

	/**
	 * the player took a sheet off that the server knows
	 */
	public void unpin(long id) {
		known.remove(id);
		loaded.remove(id);
		if (connected) {
			send(Protocol.unpin(id));
		}
	}

	private static void send(byte[] message) {
		ClientPlayNetworking.send(new SyncPayload(message));
	}

	@NotNull
	private static Protocol.Pose pose(PhotoSheet sheet) {
		return new Protocol.Pose(sheet.position().x, sheet.position().y, sheet.position().z, sheet.rotation().x,
				sheet.rotation().y, sheet.rotation().z, sheet.rotation().w);
	}

	/**
	 * @return the picture as the game wants it. A new one every time: each sheet owns its own, also if two show
	 * the same
	 */
	private static NativeImage black() {
		final NativeImage pixels = new NativeImage(2, 2, false);
		for (int i = 0; i < 4; i++) {
			pixels.setPixel(i % 2, i / 2, 0xFF000000);
		}
		return pixels;
	}

	private static void refuse(PhotoSheet sheet, String message) {
		PhotoAlbum.INSTANCE.pinRefused(sheet);
		final LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			player.sendOverlayMessage(Component.translatable(message));
		}
	}

	private PhotoSync() {
	}

	private void reset() {
		DesktopCamera.INSTANCE.serverGone();
		connected = false;
		limits = null;
		known.clear();
		loaded.clear();
		waiting.clear();
		askedServer.clear();
		pinning.clear();
		pinningSince.clear();
		hellos = 0;
		RemoteCameras.INSTANCE.clear();
		looseKnown.clear();
		ghosts.clear();
		sharing.clear();
		packedToShare.clear();
	}

	private void receive(byte[] message) {
		if (message.length == 0) {
			return;
		}
		try {
			final DataInputStream in = Protocol.body(message);
			switch (message[0]) {
				case Protocol.S_HELLO -> {
					final Protocol.Limits limits = Protocol.readLimits(in);
					if (connected) {
						return;
					}
					if (limits.version() == Protocol.VERSION) {
						this.limits = limits;
						connected = true;
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
					new ArrayList<>(known.keySet()).forEach(this::forget);
					new ArrayList<>(looseKnown.keySet()).forEach(this::looseGone);
				}
				case Protocol.S_LOOSE -> {
					final Protocol.Loose loose = Protocol.readLoose(in);
					if (loose.pose().isSane() && loose.aspect() >= MIN_ASPECT && loose.aspect() <= 1.0F / MIN_ASPECT &&
							looseKnown.size() < MAX_LOOSE_KNOWN) {
						looseKnown.put(loose.id(), new Protocol.Loose(loose.id(), loose.owner(), loose.ownerName(),
								loose.pose().normalized(), loose.aspect(), loose.imageHash(), loose.custom()));
					}
				}
				case Protocol.S_LOOSE_POSE -> {
					final long id = in.readLong();
					final Protocol.Pose pose = Protocol.readPose(in);
					final Protocol.Loose loose = looseKnown.get(id);
					if (loose != null && pose.isSane()) {
						final Protocol.Pose at = pose.normalized();
						looseKnown.put(id, new Protocol.Loose(id, loose.owner(), loose.ownerName(), at,
								loose.aspect(), loose.imageHash(), loose.custom()));
						PhotoAlbum.INSTANCE.moveGhost(id, new Vec3(at.x(), at.y(), at.z()),
								new Quaternionf(at.qx(), at.qy(), at.qz(), at.qw()));
					}
				}
				case Protocol.S_LOOSE_GONE -> looseGone(in.readLong());
				case Protocol.S_LOOSE_RESULT -> shared(Protocol.readLooseResult(in));
				case Protocol.S_IMAGE -> {
					final long hash = in.readLong();
					final byte[] image = Protocol.readImage(in);

					if (askedServer.contains(hash)) {
						gotPacked(hash, image, true);
					}
				}
				case Protocol.S_PIN_RESULT -> pinned(Protocol.readPinResult(in));
				case Protocol.S_CAMERA -> RemoteCameras.INSTANCE.heard(Protocol.readCamera(in, true));
				case Protocol.S_PLACE -> {
					final Protocol.Placed camera = Protocol.readPlaced(in);
					if (camera.isSane()) {
						DesktopCamera.INSTANCE.serverPlace(camera.id(), new Vec3(camera.x(), camera.y(), camera.z()),
								camera.yaw(), camera.pitch(), camera.fov(), camera.anyway(), camera.show());
					}
				}
				case Protocol.S_TAKE -> {
					final String id = in.readUTF();
					if (id.length() <= Protocol.MAX_CAMERA_ID) {
						DesktopCamera.INSTANCE.serverTake(id, in.readBoolean());
					}
				}
				case Protocol.S_SHOW -> DesktopCamera.INSTANCE.serverShow(in.readUTF(), in.readFloat());
				case Protocol.S_SHUTTER, Protocol.S_PRINT -> {
					final Vec3 at = new Vec3(in.readDouble(), in.readDouble(), in.readDouble());
					final LocalPlayer player = Minecraft.getInstance().player;

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
		final float length = (float) Math.sqrt(sheet.qx() * sheet.qx() + sheet.qy() * sheet.qy() +
				sheet.qz() * sheet.qz() + sheet.qw() * sheet.qw());
		final boolean sane = Double.isFinite(sheet.x()) && Double.isFinite(sheet.y()) && Double.isFinite(sheet.z()) &&
				Float.isFinite(length) && length > 1.0E-3F && sheet.aspect() >= MIN_ASPECT &&
				sheet.aspect() <= 1.0F / MIN_ASPECT;
		if (!sane || (known.size() >= MAX_KNOWN && !known.containsKey(sheet.id()))) {
			return;
		}

		known.put(sheet.id(), new Protocol.Sheet(sheet.id(), sheet.owner(), sheet.ownerName(), sheet.x(),
				sheet.y(), sheet.z(), sheet.qx() / length, sheet.qy() / length, sheet.qz() / length,
				sheet.qw() / length, sheet.aspect(), sheet.imageHash(), sheet.removable(), sheet.custom()));
	}

	private void looseGone(long id) {
		looseKnown.remove(id);
		ghosts.remove(id);
		PhotoAlbum.INSTANCE.removeLoose(id);
	}

	private void sharePacked() {
		for (Iterator<PhotoSheet> packed = packedToShare.iterator(); packed.hasNext(); ) {
			final PhotoSheet sheet = packed.next();
			if (!PhotoAlbum.INSTANCE.has(sheet) || sheet.isPinned() || sheet.isGhost()) {
				packed.remove();
			} else if (sheet.position().lengthSqr() > 0) {
				packed.remove();
				final long reference = nextReference++;
				sharing.put(reference, sheet);
				send(Protocol.newLoose(new Protocol.NewLoose(reference, pose(sheet), sheet.packed, sheet.custom)));
			}
		}
	}

	private void shared(Protocol.LooseResult result) {
		final PhotoSheet sheet = sharing.remove(result.reference());
		if (sheet == null || result.id() == 0) {
			return;
		}
		if (!PhotoAlbum.INSTANCE.has(sheet) || sheet.isPinned() || sheet.isGhost()) {

			dropLoose(result.id());
			return;
		}
		sheet.setLooseId(result.id());
		packed.put(result.imageHash(), sheet.packed);
	}

	private void forget(long id) {
		known.remove(id);
		if (loaded.remove(id)) {
			PhotoAlbum.INSTANCE.removeRemote(id, false);
		}
	}

	private void removed(long id, byte reason) {
		known.remove(id);
		if (loaded.remove(id)) {
			PhotoAlbum.INSTANCE.removeRemote(id, reason == Protocol.REMOVED_FELL);
		}
	}

	/**
	 * gets the picture with that hash, from where it is closest: memory, disk, server
	 */
	/**
	 * @return if {@link #fetch} would start to get that picture now
	 */
	private boolean canFetch(long hash) {
		return packed.containsKey(hash) || askedServer.size() < MAX_WAITING;
	}

	private void fetch(long hash) {
		if (waiting.containsKey(hash)) {
			return;
		}
		final byte[] inMemory = packed.get(hash);
		if (inMemory != null) {
			waiting.put(hash, ticks);
			unpack(hash, inMemory);
			return;
		}
		if (askedServer.size() >= MAX_WAITING) {
			return;
		}
		waiting.put(hash, ticks);
		askedServer.add(hash);
		CompletableFuture.supplyAsync(() -> PhotoStore.readRemote(hash))
				.thenAcceptAsync(onDisk -> {
					if (!connected || !waiting.containsKey(hash)) {
						return;
					}
					if (onDisk != null) {
						askedServer.remove(hash);
						gotPacked(hash, onDisk, false);
					} else {
						send(Protocol.imageRequest(hash));
					}
				}, Minecraft.getInstance());
	}

	private void gotPacked(long hash, byte[] image, boolean fromServer) {
		askedServer.remove(hash);
		packed.put(hash, image);
		if (fromServer) {
			CompletableFuture.runAsync(() -> PhotoStore.writeRemote(hash, image));
		}
		if (waiting.containsKey(hash)) {
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
			waiting.remove(hash);
			if (picture != null && connected) {
				show(hash, picture, image);
			}
		}, Minecraft.getInstance());
	}

	/**
	 * puts every known sheet with that picture into the world
	 */
	private void show(long hash, PhotoCodec.Picture picture, byte[] image) {
		for (final Protocol.Loose loose : looseKnown.values()) {
			if (loose.imageHash() == hash && !(loose.custom() && !shownCustom) &&
					ghosts.add(loose.id())) {
				final Protocol.Pose at = loose.pose();
				final PhotoSheet ghost = PhotoAlbum.INSTANCE.addGhost(loose.id(), new Vec3(at.x(), at.y(), at.z()),
						new Quaternionf(at.qx(), at.qy(), at.qz(), at.qw()), loose.aspect(), PhotoAlbum.image(picture), image);
				if (ghost != null) {
					ghost.custom = loose.custom();
				}
			}
		}
		for (final Protocol.Sheet sheet : known.values()) {
			final LocalPlayer player = Minecraft.getInstance().player;
			final boolean own = player != null && sheet.owner().equals(player.getUUID());
			if (sheet.imageHash() != hash || loaded.contains(sheet.id()) ||
					(sheet.custom() && !shownCustom && !own)) {
				continue;
			}
			final PhotoSheet shown = PhotoAlbum.INSTANCE.addRemote(sheet.id(), sheet.removable(),
					new Vec3(sheet.x(), sheet.y(), sheet.z()),
					new Quaternionf(sheet.qx(), sheet.qy(), sheet.qz(), sheet.qw()), sheet.aspect(),
					PhotoAlbum.image(picture), image);
			if (shown != null) {
				shown.custom = sheet.custom();
			}
			loaded.add(sheet.id());
		}
	}

	private void pinned(Protocol.PinResult result) {
		pinningSince.remove(result.reference());
		final PhotoSheet sheet = pinning.remove(result.reference());
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
		loaded.add(result.id());
		if (sheet.packed != null) {
			packed.put(result.imageHash(), sheet.packed);
		}
		final LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {

			known.put(result.id(), new Protocol.Sheet(result.id(), player.getUUID(), player.getName().getString(),
					sheet.position().x, sheet.position().y, sheet.position().z, sheet.rotation().x,
					sheet.rotation().y, sheet.rotation().z, sheet.rotation().w, sheet.aspect, result.imageHash(),
					true, sheet.custom));
		}
	}
}
