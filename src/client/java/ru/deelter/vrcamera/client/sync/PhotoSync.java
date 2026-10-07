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

	// blocks. Further away than the first a sheet this small is a dot, and what was loaded is kept a bit longer
	// to not load and drop it at the edge
	private static final double LOAD_DISTANCE = 16.0;
	private static final double UNLOAD_DISTANCE = 24.0;
	// pictures of other players' sheets in memory at once, whatever the server allows per chunk
	private static final int MAX_LOADED = 48;
	// pictures asked for and not yet answered. More at once would not arrive any sooner, the server paces them
	private static final int MAX_WAITING = 2;
	// blocks a loaded sheet has to be further than a wanted one to give up its place, or two sheets at about the
	// same distance would take turns
	private static final double EVICT_MARGIN = 2.0;
	private static final int WAIT_TICKS = 100;
	private static final int SCAN_INTERVAL_TICKS = 5;
	private static final int PACKED_CACHE = 64;
	private static final int HELLO_TRIES = 15;
	private static final int HELLO_INTERVAL_TICKS = 40;
	private static final int PIN_WAIT_TICKS = 200;
	// five times per second the others are told where this player's loose sheets are
	private static final int POSE_INTERVAL_TICKS = 4;
	private static final int MAX_LOOSE_KNOWN = 512;
	// sheets a server can make this client remember, and how far from square one may be
	private static final int MAX_KNOWN = 4096;
	private static final float MIN_ASPECT = 0.25F;

	private boolean connected;
	private Protocol.Limits limits;
	// every sheet the server told about and did not take back
	private final Map<Long, Protocol.Sheet> known = new HashMap<>();
	// the ones that are in the album, with their picture
	private final Set<Long> loaded = new HashSet<>();
	// picture hashes that are being fetched from disk, the server or unpacked, and the tick that started
	private final Map<Long, Integer> waiting = new HashMap<>();
	private final Set<Long> askedServer = new HashSet<>();
	private final Map<Long, byte[]> packed = new LinkedHashMap<>(32, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
			return size() > PACKED_CACHE;
		}
	};
	// sheets sent to be pinned, by the number the answer will name
	private final Map<Long, PhotoSheet> pinning = new HashMap<>();
	private final Map<Long, Integer> pinningSince = new HashMap<>();
	// loose sheets of other players the server told about, and the ones of them that are in the album
	private final Map<Long, Protocol.Loose> looseKnown = new HashMap<>();
	private final Set<Long> ghosts = new HashSet<>();
	// sheets of this player that were sent to be shared, by the number the answer will name
	private final Map<Long, PhotoSheet> sharing = new HashMap<>();
	private final List<PhotoSheet> packedToShare = new ArrayList<>();
	private int hellos;
	private boolean shownCustom;
	private long nextReference = 1;
	private int ticks;

	private PhotoSync() {
	}

	public void init() {
		PayloadTypeRegistry.serverboundPlay().register(SyncPayload.TYPE, SyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SyncPayload.TYPE, SyncPayload.CODEC);
		ClientPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE,
				(payload, context) -> context.client().execute(() -> receive(payload.data())));
		ClientPlayConnectionEvents.JOIN.register((listener, sender, mc) -> {
			reset();
			// only a server with the plugin listens on the channel
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
		return this.connected;
	}

	private void reset() {
		this.connected = false;
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
		if (this.connected) {
			send(Protocol.camera(position.x, position.y, position.z, rotation.x, rotation.y, rotation.z,
					rotation.w));
		}
	}

	private static void send(byte[] message) {
		ClientPlayNetworking.send(new SyncPayload(message));
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
					// only what was asked for. A server can't fill memory and disk with pictures nobody wanted
					if (this.askedServer.contains(hash)) {
						gotPacked(hash, image, true);
					}
				}
				case Protocol.S_PIN_RESULT -> pinned(Protocol.readPinResult(in));
				case Protocol.S_CAMERA -> RemoteCameras.INSTANCE.heard(Protocol.readCamera(in, true));
				case Protocol.S_SHUTTER, Protocol.S_PRINT -> {
					Vec3 at = new Vec3(in.readDouble(), in.readDouble(), in.readDouble());
					LocalPlayer player = Minecraft.getInstance().player;
					// only from around here: a server does not get to click and flash anywhere it likes
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
		// as a unit quaternion, anything else would also scale the sheet
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
		CompletableFuture.supplyAsync(() -> {
			try {
				return sheet.packed != null && sheet.packed.length <= maxBytes ? sheet.packed :
						PhotoCodec.pack(picture, maxBytes);
			} catch (IOException | RuntimeException e) {
				Vrcamera.LOGGER.warn("VRCamera: the photo could not be packed for the server", e);
				return null;
			}
		}).thenAcceptAsync(image -> {
			if (image == null || !this.connected || !PhotoAlbum.INSTANCE.has(sheet) || sheet.isPinned()) {
				return;
			}
			sheet.packed = image;
			// Not sent from here. A sheet fresh out of the camera is put in its place by the next frame, this may
			// run before that, and the server would be told it is at the origin of the world
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

	private static Protocol.Pose pose(PhotoSheet sheet) {
		return new Protocol.Pose(sheet.position().x, sheet.position().y, sheet.position().z, sheet.rotation().x,
				sheet.rotation().y, sheet.rotation().z, sheet.rotation().w);
	}

	private void shared(Protocol.LooseResult result) {
		PhotoSheet sheet = this.sharing.remove(result.reference());
		if (sheet == null || result.id() == 0) {
			return;
		}
		if (!PhotoAlbum.INSTANCE.has(sheet) || sheet.isPinned() || sheet.isGhost()) {
			// gone or pinned while the server was thinking about it
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
			// The server says which channels it listens on shortly after the join, not always before it. So the
			// hello is tried a few more times. A server that got it already ignores the rest
			if (this.hellos < HELLO_TRIES && this.ticks % HELLO_INTERVAL_TICKS == 0 &&
					ClientPlayNetworking.canSend(SyncPayload.TYPE)) {
				this.hellos++;
				send(Protocol.clientHello());
			}
			return;
		}
		// A server that does not answer a pin must not leave the sheet stuck where nobody can take it
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
			// only the ones that moved, a sheet that lies still costs nothing
			PhotoAlbum.INSTANCE.forEachShared(sheet -> {
				if (sheet.movedSinceShared()) {
					send(Protocol.loosePose(Protocol.C_LOOSE_POSE, sheet.looseId(), pose(sheet)));
				}
			});
		}
		if (this.ticks % SCAN_INTERVAL_TICKS != 0) {
			return;
		}
		// an answer that never came does not block the others forever
		this.waiting.values().removeIf(since -> this.ticks - since > WAIT_TICKS);
		this.askedServer.retainAll(this.waiting.keySet());

		boolean showOthers = CameraConfig.current().showOthersPhotos;
		boolean showCustom = CameraConfig.current().showCustomPhotos;
		if (showCustom != this.shownCustom) {
			// the player changed their mind: everything is taken out and comes back the other way
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
					// not asked for: a black sheet in its place, the picture is not even fetched
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
				// Nothing more can be asked for right now. Checked before room is made: a sheet must not be dropped
				// for one that is not loaded in its place after all
				break;
			}
			if (this.loaded.size() + this.waiting.size() >= MAX_LOADED) {
				// full: the nearest ones are the ones to see, a loaded one that is clearly further makes room
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
	 * gets the picture with that hash, from where it is closest: memory, disk, server
	 */
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
			}
			this.loaded.add(sheet.id());
		}
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

	/**
	 * The player pinned a sheet. The server is asked to keep it, and may refuse.
	 *
	 * @param picture the pixels of the sheet, null if it still has what it was sent with before
	 */
	public void pin(PhotoSheet sheet, PhotoCodec.Picture picture) {
		sheet.awaitServer();
		int maxBytes = Math.min(this.limits.maxImageBytes(), Protocol.MAX_IMAGE_BYTES);
		CompletableFuture.supplyAsync(() -> {
			try {
				return sheet.packed != null && sheet.packed.length <= maxBytes ? sheet.packed :
						PhotoCodec.pack(picture, maxBytes);
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
			// known like any other, so it is dropped and loaded again by distance like any other
			this.known.put(result.id(), new Protocol.Sheet(result.id(), player.getUUID(), player.getName().getString(),
					sheet.position().x, sheet.position().y, sheet.position().z, sheet.rotation().x,
					sheet.rotation().y, sheet.rotation().z, sheet.rotation().w, sheet.aspect, result.imageHash(),
					true, sheet.custom));
		}
	}

	private static void refuse(PhotoSheet sheet, String message) {
		PhotoAlbum.INSTANCE.pinRefused(sheet);
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			player.sendOverlayMessage(Component.translatable(message));
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
}
