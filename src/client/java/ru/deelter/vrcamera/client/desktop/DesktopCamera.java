package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.CameraIndicator;
import ru.deelter.vrcamera.client.Vr;
import ru.deelter.vrcamera.client.compat.SubmitNodeCollector;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.config.ScreenOutput;
import ru.deelter.vrcamera.client.config.ShotConfig;
import ru.deelter.vrcamera.client.director.Director;
import ru.deelter.vrcamera.client.math.CamMath;
import ru.deelter.vrcamera.client.photo.PhotoStore;
import ru.deelter.vrcamera.client.rig.Rig;
import ru.deelter.vrcamera.client.rig.Subject;
import ru.deelter.vrcamera.client.rig.WorldProbe;
import ru.deelter.vrcamera.client.shot.Shot;
import ru.deelter.vrcamera.client.shot.ShotType;
import ru.deelter.vrcamera.client.sync.PhotoSync;
import ru.deelter.vrcamera.client.sync.RemoteCameras;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * The camera of a player without VR. The director and the follow camera have the same shots as in VR, picked the
 * same way from what the game knows of a player at a screen. The free cameras stay where they are put. Either films
 * into the game window in place of the view of the player, or into a window of its own.
 * <p>
 * Apart from everything that has to do with VR. It only runs while VR does not, and is off until it is asked for.
 */
public final class DesktopCamera {
	public static final DesktopCamera INSTANCE = new DesktopCamera();
	// a gap between frames this long means the game stood still, not a slow frame
	private static final double MAX_FRAME_TIME = 0.25;
	private static final double MAX_GAP = 1.0;
	private static final float DEFAULT_FOV = 70.0F;
	// steering: degrees per second around the player and up or down, and the part of the distance per second
	private static final double STEER_TURN = 70.0;
	private static final double STEER_RISE = 40.0;
	private static final double STEER_ZOOM = 1.1;
	// closer to the player than this the camera has no place around them to start from
	private static final double STEER_MIN_DISTANCE = 1.0;
	// a shot the camera is put into by hand is not closer to the player than this many of their sizes
	private static final double MIN_SHOT_DISTANCE = 0.3;
	// the part of the field of view one notch of the wheel is, for a shot that is steered
	private static final double FOV_WHEEL = 0.08;
	// Degrees the free camera turns: per unit of what the game makes of the mouse, as it turns a player, and per
	// pixel the mouse is moved in the window of the camera
	private static final double MOUSE_TURN = 0.15;
	private static final double DRAG_TURN = 0.12;
	// blocks from the player a free camera is still one of the place they are at
	private static final double FREE_AROUND = 64.0;
	// blocks per second a free camera has to be let go of at, to glide on
	private static final double THROW_SPEED = 4.0;
	// blocks a free camera has to be away from the player for their look to pick it
	private static final double GAZE_NEAR = 1.5;
	// closer to the head than this the camera is inside of the player, and further than the second it is out again
	private static final double INSIDE_IN = 0.55;
	private static final double INSIDE_OUT = 0.8;
	// further than this in one step the camera cut to another shot, it did not fly there
	private static final double MARKER_JUMP = 1.5;
	// what a server may have waiting for the free cameras to be opened
	private static final int FROM_SERVER_WAITING = 64;
	// seconds a server can have a camera film at most, before the player has theirs back
	private static final double LENT_LONGEST = 600.0;
	// blocks a free camera may be away from an entity to be put onto it
	private static final double STICK_REACH = 0.5;
	// how large the letter of a free camera is, next to the camera icon
	private static final double NAME_SIZE = 0.75;
	// up to this many blocks away an icon has its full size, and however far away it is not smaller than this part
	private static final double ICON_FULL = 8.0;
	private static final double ICON_SMALLEST = 0.3;
	// the camera glyph of the mod, see assets/minecraft/font/default.json
	private static final String CAMERA_ICON = "";
	private final Subject subject = new Subject();
	private final Rig rig = new Rig(true);
	private final FreeCamera free = new FreeCamera();
	private final CameraGrab grab = new CameraGrab();
	// What a server did with the cameras it gives, while those of the world were not open: done when they are. And
	// the world that was meant for
	private final List<Runnable> fromServer = new ArrayList<>();
	private Mode mode = Mode.OFF;
	// if the window of the camera shows the view of the player for now, because they asked for that
	private boolean ownView;
	private ClientLevel fromServerLevel;
	// A camera a server has film for a while: what the server calls it, until when, if it got to film at all, and
	// the mode and the camera the player had before
	private String lentId;
	private long lentUntil;
	private boolean lentShown;
	private Mode lentFrom;
	private String lentBefore;
	// the free camera the server was told films
	private String toldFilming;
	// what it is turned back on to
	private Mode lastMode = Mode.DIRECTOR;
	// a shot that was asked for before there was a director to show it
	private ShotType askedFor;
	private CameraConfig config;
	private Director director;
	private Shot followShot;
	// the shot the player steers themselves, null while the camera works on its own
	private Shot steered;
	private boolean flying;
	// if the one who is filmed is the player themselves, and not someone the settings name
	private boolean filmsSelf = true;
	private boolean steeredFromWindow;
	// which of the keys 1 to 9 is held in the window of the camera, counted from 0, or -1
	private int digitDown = -1;
	// the level the free cameras that are open belong to
	private ClientLevel freeLevel;
	// the free camera the player looks at, and for how long. Negative once that was acted on
	private int gazeAt = -1;
	private double gazeTime;
	private boolean inside;
	private CameraType viewBefore;
	private long lastNanos;
	private Pose pose;
	// when the camera was moved last, how long that step was and how fast it went
	private long poseNanos;
	private double poseStep;
	private Vec3 poseSpeed = Vec3.ZERO;
	private int shareTicks;

	private DesktopCamera() {
	}

	private static void say(String key, Object... args) {
		CameraHints.spoke();
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			player.displayClientMessage(Component.translatable(key, args), true);
		}
	}

	private static float partialTick() {
		return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
	}

	/**
	 * @return the player of that name the game knows of right now, null if there is none or the name is empty
	 */
	private static Player find(Minecraft mc, String name) {
		if (name == null || name.isBlank() || mc.level == null) {
			return null;
		}
		for (Player other : mc.level.players()) {
			if (other.isAlive() && other.getGameProfile().getName().equalsIgnoreCase(name)) {
				return other;
			}
		}
		return null;
	}

	private static double ownFov() {
		return Minecraft.getInstance().options.fov().get();
	}

	/**
	 * @return the entity a camera that is let go of there is put onto: the nearest one it touches, null for none.
	 * Never the player themselves, a camera that goes with them is what following is for
	 */
	private static Entity touched(Vec3 at) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return null;
		}
		Entity nearest = null;
		double closest = STICK_REACH * STICK_REACH;
		for (Entity entity : player.level().getEntities(player, new AABB(at, at).inflate(STICK_REACH),
				entity -> entity.isAlive() && !entity.isSpectator() && entity.isPickable())) {
			double away = entity.getBoundingBox().distanceToSqr(at);
			if (away < closest) {
				closest = away;
				nearest = entity;
			}
		}
		return nearest;
	}

	/**
	 * @return how large the icon of a camera that far away is. It gets a bit smaller with the distance, to tell the
	 * near cameras from the far ones, but stays large enough to be read and pointed at
	 */
	private static double farSize(Vec3 eye, Vec3 camera) {
		return Math.clamp(Math.sqrt(ICON_FULL / Math.max(eye.distanceTo(camera), 1.0E-3)), ICON_SMALLEST, 1.0);
	}

	public Mode mode() {
		return this.mode;
	}

	public boolean isOn() {
		return this.mode != Mode.OFF;
	}

	public void cycleMode() {
		setMode(Mode.values()[(this.mode.ordinal() + 1) % Mode.values().length]);
	}

	public void setMode(Mode mode) {
		Minecraft mc = Minecraft.getInstance();
		if (mode != Mode.OFF && Vr.isRunning()) {
			// in VR the camera of Vivecraft does this
			return;
		}
		if (mode == this.mode) {
			addCamera();
			return;
		}
		this.free.save();
		this.freeLevel = null;
		this.mode = mode;
		if (mode != Mode.OFF) {
			this.lastMode = mode;
		}
		this.steered = null;
		this.flying = false;
		this.inside = false;
		this.ownView = false;
		this.steeredFromWindow = false;
		this.grab.reset();
		keepView(mc);
		this.subject.reset();
		this.rig.reset();
		this.followShot = null;
		this.director = null;
		this.pose = null;
		this.lastNanos = 0;
		sayMode();
	}

	/**
	 * The one key between the view of the player and the picture of the camera. A camera with a window of its own
	 * shows the view of the player in it, and stays what it is: the window is what is recorded. One that films into
	 * the game window is turned off, and back on to what it was doing
	 */
	public void toggle() {
		if (!hasOwnWindow()) {
			setMode(this.mode == Mode.OFF ? this.lastMode : Mode.OFF);
			return;
		}
		this.ownView = !this.ownView;
		if (this.ownView) {
			say("vrcamera.message.ownview");
		} else {
			sayMode();
		}
	}

	private void sayMode() {
		if (this.mode == Mode.DIRECTOR && CameraConfig.current().directorManual) {
			// the two keys that are all there is to it
			say("vrcamera.message.manual", CameraHints.keyName("next"), CameraHints.keyName("toggle"));
		} else {
			say("vrcamera.message.mode",
					Component.translatable("vrcamera.mode." + this.mode.name().toLowerCase(Locale.ROOT)));
		}
	}

	/**
	 * Has the director show a shot, and turns the director on for that if it is not.
	 *
	 * @param type the shot, null for the next one of its own choice
	 */
	public void showShot(ShotType type) {
		if (this.mode != Mode.DIRECTOR) {
			setMode(Mode.DIRECTOR);
			if (this.mode != Mode.DIRECTOR) {
				return;
			}
		}
		this.ownView = false;
		if (type == null) {
			nextShot();
		} else if (this.director == null) {
			this.askedFor = type;
		} else {
			this.director.force(type);
			say("vrcamera.message.shot", Component.translatable("vrcamera.shot." + type.name().toLowerCase(Locale.ROOT)));
		}
	}

	/**
	 * the picture can't be drawn or shown: off, and the player is told why
	 */
	public void failed(String message) {
		setMode(Mode.OFF);
		say(message);
	}

	// ---- who is filmed

	public void nextShot() {
		// asked for a shot, the player wants to see it
		this.ownView = false;
		if (this.mode == Mode.DIRECTOR && this.director != null) {
			this.director.next();
			say("vrcamera.message.next");
		}
	}

	public void toggleHold() {
		if (this.mode == Mode.DIRECTOR && this.director != null) {
			say(this.director.toggleHold() ? "vrcamera.message.hold" : "vrcamera.message.release");
		}
	}

	/**
	 * The director and the follow camera film another player in place of the one at the keyboard, for as long as
	 * that player is around.
	 *
	 * @param name null to film oneself again
	 * @return false if no player of that name is around
	 */
	public boolean film(String name) {
		if (name != null && find(Minecraft.getInstance(), name) == null) {
			return false;
		}
		CameraConfig config = CameraConfig.current();
		config.filmPlayer = name == null ? "" : name;
		config.save();
		if (name == null) {
			say("vrcamera.message.film.self");
		} else {
			say("vrcamera.message.film.other", name);
		}
		return true;
	}

	/**
	 * A friend to have in the picture: the director shows the two of them together in between its other shots.
	 *
	 * @param name null for no one
	 * @return false if no player of that name is around
	 */
	public boolean filmWith(String name) {
		if (name != null && find(Minecraft.getInstance(), name) == null) {
			return false;
		}
		CameraConfig config = CameraConfig.current();
		config.filmWith = name == null ? "" : name;
		config.save();
		if (name == null) {
			say("vrcamera.message.with.off");
		} else {
			say("vrcamera.message.with.on", name);
		}
		return true;
	}

	// ---- the free cameras

	/**
	 * @return the names of the other players around, to pick one to film
	 */
	public List<String> playersAround() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return List.of();
		}
		return mc.level.players().stream().filter(other -> other != mc.player)
				.map(other -> other.getGameProfile().getName()).toList();
	}

	/**
	 * puts the free camera that films at the eyes of the player: it films what they look at right now, and stays
	 */
	public void summon() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (this.mode == Mode.FREE && player != null) {
			float partialTick = partialTick();
			this.free.place(player.getEyePosition(partialTick), player.getViewVector(partialTick), ownFov());
		}
	}

	/**
	 * one more free camera, at the eyes of the player, and it films
	 */
	public void addCamera() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (this.mode != Mode.FREE || player == null || this.freeLevel == null) {
			return;
		}
		if (addAtEyes(player, partialTick())) {
			say("vrcamera.message.free.saved", this.free.name(this.free.active()));
		} else {
			say("vrcamera.message.free.full", FreeCamera.MOST);
		}
		this.grab.release();
	}

	private boolean addAtEyes(LocalPlayer player, float partialTick) {
		return this.free.add(player.getEyePosition(partialTick), player.getViewVector(partialTick), ownFov());
	}

	/**
	 * Cuts to the next free camera of the place the player is at, in the order of the alphabet. The ones they left
	 * somewhere else are passed over: by their name they can still be cut to
	 */
	public void nextPoint() {
		int camera = nextAround();
		if (camera >= 0) {
			cutTo(camera);
		}
	}

	/**
	 * the same, but the camera that films flies over to the next one and films on the way
	 */
	public void flyToNext() {
		int camera = nextAround();
		if (camera >= 0) {
			this.grab.release();
			this.free.flyTo(camera);
			say("vrcamera.message.free.point", this.free.name(camera), this.free.count());
		}
	}

	/**
	 * @return the free camera after the one that films that is around the player, -1 if there is none
	 */
	private int nextAround() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (this.mode != Mode.FREE || player == null) {
			return -1;
		}
		Vec3 eyes = player.getEyePosition();
		for (int step = 1; step < this.free.count(); step++) {
			int camera = (this.free.active() + step) % this.free.count();
			if (this.free.position(camera).distanceToSqr(eyes) < FREE_AROUND * FREE_AROUND) {
				return camera;
			}
		}
		return -1;
	}

	/**
	 * @return what the free cameras of the place the player is in are called, none while the mode is another
	 */
	public List<String> cameraNames() {
		return this.mode == Mode.FREE ? this.free.names() : List.of();
	}

	/**
	 * cuts to the free camera of that name
	 *
	 * @return false if there is none
	 */
	public boolean showCamera(String name) {
		int camera = cameraNames().indexOf(name.toUpperCase(Locale.ROOT));
		if (camera < 0) {
			return false;
		}
		cutTo(camera);
		return true;
	}

	private void cutTo(int camera) {
		this.grab.release();
		this.free.show(camera);
		say("vrcamera.message.free.point", this.free.name(camera), this.free.count());
	}

	/**
	 * takes away every free camera of the world the player is in, and puts a first one at their eyes
	 */
	public void clearCameras() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (this.mode != Mode.FREE || player == null || this.freeLevel == null) {
			return;
		}
		this.grab.reset();
		this.free.clear();
		addAtEyes(player, partialTick());
		say("vrcamera.message.free.cleared");
	}

	/**
	 * @return if a free camera that does not film is one of those around the player, shown and to be picked. The
	 * ones at another place they built at are not in the way then
	 */
	private boolean isAround(int camera, Vec3 from) {
		double reach = CameraConfig.current().cameraLabelDistance;
		return camera == this.free.active() || this.free.position(camera).distanceToSqr(from) < reach * reach;
	}

	/**
	 * the cameras of the world and dimension the player is in now
	 */
	private void openFree(Minecraft mc, LocalPlayer player, float partialTick) {
		this.freeLevel = mc.level;
		Path cache = PhotoStore.worldCache();
		try {
			PhotoStore.prepare(cache);
		} catch (IOException e) {
			Vrcamera.LOGGER.warn("VRCamera: can't make {}", cache, e);
		}
		this.free.open(cache.resolve(FreeCamera.fileName(mc.level.dimension().location().toDebugFileName())));
		// what a server gave while the cameras of this world were not open
		if (this.fromServerLevel == mc.level) {
			this.fromServer.forEach(Runnable::run);
		}
		this.fromServer.clear();
		if (this.lentId != null && !this.free.isEmpty()) {
			// A server has one of them film for a while, and the mode was turned on for that alone: no camera is
			// made at the eyes of the player, who did not ask for one
			showLent();
			return;
		}
		// The one nearest to the player films. With none around a new one is put at their eyes: the ones they left
		// at another place are not what they turned the mode on for, and are a long flight away
		Vec3 eyes = player.getEyePosition(partialTick);
		int nearest = -1;
		double distance = FREE_AROUND * FREE_AROUND;
		for (int camera = 0; camera < this.free.count(); camera++) {
			double away = this.free.position(camera).distanceToSqr(eyes);
			if (away < distance) {
				distance = away;
				nearest = camera;
			}
		}
		if (nearest >= 0) {
			this.free.show(nearest);
		} else {
			addAtEyes(player, partialTick);
		}
		if (this.lentId != null) {
			showLent();
		}
	}

	// ---- cameras a server gives the player

	/**
	 * A server puts up a free camera for the player. It is theirs from then on.
	 *
	 * @param id     what the server calls it
	 * @param anyway also if the player has moved or thrown away the one with that id
	 * @param show   if it films right away, for a player who is in the free mode
	 */
	public void serverPlace(
			String id, Vec3 position, float yaw, float pitch, float fov, boolean anyway, boolean show) {
		withCameras(() -> {
			boolean known = this.free.indexOf(id) >= 0;
			int camera = this.free.place(id, position, yaw, pitch, fov, anyway);
			if (camera < 0) {
				return;
			}
			if (!known) {
				say("vrcamera.message.server.added", this.free.name(camera));
			}
			if (show && this.mode == Mode.FREE && camera != this.free.active()) {
				this.grab.reset();
				this.free.show(camera);
			}
		});
	}

	/**
	 * a server takes cameras back that it gave
	 *
	 * @param exact true for the one with that id, false for all whose id starts with it
	 */
	public void serverTake(String id, boolean exact) {
		withCameras(() -> {
			this.grab.reset();
			this.free.takeBack(id, exact);
			LocalPlayer player = Minecraft.getInstance().player;
			if (this.mode == Mode.FREE && this.free.isEmpty() && player != null) {
				// the mode has nothing to film with otherwise
				addAtEyes(player, partialTick());
			}
		});
	}

	/**
	 * A server has one of the cameras it gave film.
	 *
	 * @param seconds 0 to cut to it, for a player in the free mode, who goes on from there as they like. More to
	 *                lend it for that long: whatever the camera of the player was doing, it shows that camera, and
	 *                then goes back to what it did. Never for a camera that is off, a server does not turn it on
	 */
	public void serverShow(String id, double seconds) {
		if (!CameraConfig.current().serverCameras || this.mode == Mode.OFF || Vr.isRunning()) {
			return;
		}
		boolean open = this.mode == Mode.FREE && this.freeLevel != null && !this.free.isEmpty();
		if (!(seconds > 0)) {
			int camera = open ? this.free.indexOf(id) : -1;
			if (camera >= 0 && camera != this.free.active()) {
				this.grab.reset();
				this.free.show(camera);
			}
			return;
		}
		if (this.lentId == null) {
			// what to go back to. Lent again while it is lent, that is still what was there before the first time
			this.lentFrom = this.mode;
			this.lentBefore = open ? this.free.name(this.free.active()) : null;
			this.lentShown = false;
		}
		this.lentId = id;
		this.lentUntil = System.nanoTime() + (long) (Math.min(seconds, LENT_LONGEST) * 1.0E9);
		if (open) {
			showLent();
		} else if (this.mode != Mode.FREE) {
			// shown once the cameras of this world are open, which is in the next frame
			setMode(Mode.FREE);
		}
	}

	private void showLent() {
		int camera = this.free.indexOf(this.lentId);
		if (camera < 0) {
			// the player does not have it: back at once
			this.lentUntil = 0;
			return;
		}
		this.lentShown = true;
		if (camera != this.free.active()) {
			this.grab.reset();
			this.free.show(camera);
		}
	}

	/**
	 * the time a camera was lent for is over: back to what the player had. Not if they went on by themselves in
	 * the meantime, then that is what they have
	 */
	private void endLent() {
		String id = this.lentId;
		this.lentId = null;
		boolean untouched = this.mode == Mode.FREE && this.freeLevel != null && !this.free.isEmpty() &&
				id.equals(this.free.id(this.free.active()));
		if (this.lentShown && !untouched) {
			return;
		}
		if (this.lentFrom != Mode.FREE) {
			if (this.mode == Mode.FREE) {
				setMode(this.lentFrom);
			}
			return;
		}
		int before = this.lentBefore == null ? -1 : this.free.names().indexOf(this.lentBefore);
		if (before >= 0 && untouched) {
			this.grab.reset();
			this.free.show(before);
		}
	}

	/**
	 * the server is gone, and what it wanted with it
	 */
	public void serverGone() {
		this.fromServer.clear();
		this.fromServerLevel = null;
		this.lentId = null;
	}

	/**
	 * does something with the free cameras for a server: right away while they are open, and when they are opened
	 * the next time otherwise. Not at all for a player who wants no cameras from servers
	 */
	private void withCameras(Runnable change) {
		Minecraft mc = Minecraft.getInstance();
		if (!CameraConfig.current().serverCameras || mc.level == null) {
			return;
		}
		if (this.mode == Mode.FREE && this.freeLevel == mc.level) {
			change.run();
			return;
		}
		if (this.fromServerLevel != mc.level) {
			this.fromServer.clear();
			this.fromServerLevel = mc.level;
		}
		if (this.fromServer.size() < FROM_SERVER_WAITING) {
			this.fromServer.add(change);
		}
	}

	// ---- what the keys and the mouse of the player do with the camera

	/**
	 * @return if the player has the camera themselves right now, and steers it with the keys they walk with
	 */
	public boolean isSteered() {
		return this.mode != Mode.OFF && (this.steered != null || this.flying);
	}

	/**
	 * The player takes the camera over, or gives it back. Taken over, the keys to walk move it: closer and away,
	 * around the player, up and down, and a free camera flies. Given back it stays where it was put, like a camera
	 * placed by hand in VR: the director keeps it for a while and then goes on, the others keep it for good.
	 */
	public void toggleSteering() {
		if (this.mode == Mode.OFF || this.pose == null) {
			return;
		}
		// no word on the screen for a window the player went to or left
		boolean told = !this.steeredFromWindow && !(hasOwnWindow() && OutputWindow.isFocused());
		boolean taken;
		if (this.mode == Mode.FREE) {
			this.flying = !this.flying;
			if (!this.flying) {
				this.free.save();
			}
			taken = this.flying;
		} else if (this.steered != null) {
			handOver(this.steered);
			this.steered = null;
			taken = false;
		} else {
			this.steered = shotFrom(this.pose.position(), this.pose.fov(), STEER_MIN_DISTANCE);
			this.rig.adopt(this.pose.position(), this.steered, this.subject);
			taken = true;
		}
		if (told) {
			say(taken ? "vrcamera.message.steer.on" : "vrcamera.message.steer.off");
		}
	}

	/**
	 * @param closest closer to the player than this many of their sizes the camera has no place of its own, and
	 *                gets one behind them
	 * @return a shot from where the camera is, so that going on from there is not a jump
	 */
	private Shot shotFrom(Vec3 camera, double fov, double closest) {
		Vec3 offset = camera.subtract(this.subject.center);
		ShotConfig place = CameraConfig.defaultPreset();
		if (offset.length() > closest * this.subject.unit) {
			place.azimuth = Math.toDegrees(CamMath.wrap(CamMath.azimuthOf(offset) - this.subject.facing));
			place.elevation = Math.toDegrees(CamMath.elevationOf(offset));
			place.distance = Math.max(MIN_SHOT_DISTANCE, offset.length() / this.subject.unit);
		} else {
			place.azimuth = 180;
			place.elevation = 15;
			place.distance = 3;
		}
		place.fov = fov;
		Shot shot = new Shot(ShotType.CUSTOM, place, 1);
		shot.start(this.subject, CameraConfig.current());
		return shot;
	}

	/**
	 * a shot the player set up is shown from here on: for a while by the director, for good by the follow camera
	 */
	private void handOver(Shot shot) {
		if (this.mode == Mode.FOLLOW) {
			this.followShot = shot;
		} else if (this.director != null) {
			this.director.showManual(shot);
		}
	}

	/**
	 * The mouse of a player who flies the free camera from the game window.
	 *
	 * @return false if it is for the player themselves
	 */
	public boolean turn(double yaw, double pitch) {
		if (this.mode != Mode.FREE || !this.flying || this.steeredFromWindow) {
			return false;
		}
		this.free.turn(yaw * MOUSE_TURN, pitch * MOUSE_TURN);
		return true;
	}

	/**
	 * The mouse wheel while the camera is held: away from the player and back. While it is steered: its zoom.
	 *
	 * @return false if the wheel is for the game
	 */
	public boolean scroll(double amount) {
		if (this.mode != Mode.OFF && this.grab.scroll(amount)) {
			return true;
		}
		if (isSteered() && !this.steeredFromWindow) {
			zoom(amount);
			return true;
		}
		return false;
	}

	/**
	 * @return if the use key belongs to the camera right now: the player holds it, or points at it to take it
	 */
	public boolean wantsUseKey() {
		return this.mode != Mode.OFF && this.grab.wantsUseKey();
	}

	/**
	 * @return if the attack key is for a free camera right now, and not for what is behind it
	 */
	public boolean pointsAtFreeCamera() {
		return this.mode == Mode.FREE && this.grab.isAiming() && !this.grab.isHolding();
	}

	/**
	 * The attack key on a free camera: that one films now.
	 *
	 * @return false if the key is for the game
	 */
	public boolean select() {
		if (!pointsAtFreeCamera()) {
			return false;
		}
		if (this.grab.aimedAt() != this.free.active()) {
			cutTo(this.grab.aimedAt());
		}
		return true;
	}

	/**
	 * @param notches of the wheel, away from the player zooms in
	 */
	private void zoom(double notches) {
		if (this.mode == Mode.FREE) {
			this.free.zoom(notches);
		} else if (this.steered != null) {
			ShotConfig place = this.steered.config;
			place.fov = CamMath.clamp(place.fov * Math.exp(-notches * FOV_WHEEL), 10.0, 120.0);
		}
	}

	private double held(KeyMapping key) {
		// in the window of the camera the game does not hear the keys, they are asked for there
		boolean down = this.steeredFromWindow ?
				OutputWindow.isKeyDown(KeyBindingHelper.getBoundKeyOf(key).getValue()) : key.isDown();
		return down ? 1.0 : 0.0;
	}

	// ---- one frame of the camera

	/**
	 * Called while the game sets up its camera for a picture.
	 *
	 * @return where the camera is for that picture, null to leave the view of the game alone
	 */
	public Pose update(float partialTick) {
		if (!filmsNow() || DesktopGui.isDrawing()) {
			return null;
		}
		// Filming into the game window this is where the camera moves, once per frame. With a window of its own
		// it was moved already, before its picture is drawn
		if (!DirectorPass.isActive() && !advance(partialTick)) {
			return null;
		}
		return showsOwnView() ? null : this.pose;
	}

	/**
	 * moves the camera on by one frame
	 *
	 * @return false if it could not be, and is off now
	 */
	public boolean advance(float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (this.mode == Mode.OFF || player == null) {
			return false;
		}
		try {
			Pose before = this.pose;
			long now = System.nanoTime();
			this.pose = move(mc, player, partialTick);
			// how fast it moves, for where to show it between two of its pictures
			double seconds = (now - this.poseNanos) / 1.0E9;
			Vec3 moved = before == null ? Vec3.ZERO : this.pose.position().subtract(before.position());
			this.poseSpeed = seconds > 1.0E-4 && seconds < 0.5 && moved.length() < MARKER_JUMP ?
					moved.scale(1.0 / seconds) : Vec3.ZERO;
			this.poseStep = seconds;
			this.poseNanos = now;
			return true;
		} catch (RuntimeException e) {
			// this runs in the middle of a frame, a crash here would take the game with it
			Vrcamera.LOGGER.error("VRCamera: the camera on the screen failed and was turned off", e);
			setMode(Mode.OFF);
			return false;
		}
	}

	private Pose move(Minecraft mc, LocalPlayer player, float partialTick) {
		CameraConfig config = CameraConfig.current();
		if (config != this.config || this.director == null) {
			// the settings were read again
			this.config = config;
			this.director = new Director(config);
			this.followShot = null;
			if (this.askedFor != null) {
				this.director.force(this.askedFor);
				this.askedFor = null;
			}
		}
		// like in VR the camera keeps moving while the game is paused, to get to the pause menu
		double dt = frameTime();
		Player star = find(mc, config.filmPlayer);
		this.filmsSelf = star == null || star == player;
		this.subject.updateWithoutVR(this.filmsSelf ? player : star, partialTick, dt, dt, config);
		Player partner = find(mc, config.filmWith);
		this.subject.partner = partner == this.subject.player ? null : partner;
		if (this.mode == Mode.FREE && mc.level != this.freeLevel) {
			openFree(mc, player, partialTick);
		}
		// Only for a camera with a window of its own. Filming into the game window, the menu covers the picture
		this.subject.guiCenter = hasOwnWindow() && this.filmsSelf ? DesktopGui.place(mc, this.subject, config) : null;
		readWindow(mc);
		reach(mc, player, partialTick, dt, config);
		if (this.grab.isHolding()) {
			// in the hand of the player, which is where they look: no shot has a say in that
			return new Pose(this.grab.position(), this.grab.rotation(), this.pose == null ? DEFAULT_FOV : this.pose.fov());
		}
		return this.mode == Mode.FREE ? filmFree(mc, player, partialTick, dt, config) : filmShot(mc, dt, config);
	}

	/**
	 * @return seconds since the camera was moved last
	 */
	private double frameTime() {
		long now = System.nanoTime();
		double seconds = this.lastNanos == 0 ? 0 : (now - this.lastNanos) / 1.0E9;
		this.lastNanos = now;
		if (seconds > MAX_GAP) {
			this.subject.reset();
			return 0;
		}
		// a camera that takes few pictures per second has long steps, but not longer than this
		return Math.min(seconds, MAX_FRAME_TIME);
	}

	/**
	 * The window of the camera has the keyboard while the player steers from it: going over to it takes the camera,
	 * going back to the game gives it back.
	 */
	private void readWindow(Minecraft mc) {
		boolean inWindow = hasOwnWindow() && OutputWindow.isFocused();
		if (inWindow != this.steeredFromWindow && inWindow != isSteered()) {
			toggleSteering();
		}
		this.steeredFromWindow = inWindow && isSteered();
		pickByDigit(inWindow);
		// asked for in any case: what the mouse did there while the camera was not steered is not kept for later
		double wheel = OutputWindow.scrolled();
		// a free camera is turned with the mouse, which has to stay in the window for that
		OutputWindow.capture(this.steeredFromWindow && this.mode == Mode.FREE);
		double[] mouse = OutputWindow.mouseMoved();
		if (this.steeredFromWindow) {
			// the game takes a player who presses nothing in its own window for gone, and draws fewer frames
			mc.getFramerateLimitTracker().onInputReceived();
			zoom(wheel);
			this.free.turn(mouse[0] * DRAG_TURN, mouse[1] * DRAG_TURN);
		}
	}

	/**
	 * the keys 1 to 9 in the window of the camera cut to the free cameras A to I
	 */
	private void pickByDigit(boolean inWindow) {
		int down = -1;
		for (int digit = 0; inWindow && this.mode == Mode.FREE && digit < 9 && down < 0; digit++) {
			if (OutputWindow.isKeyDown(InputConstants.KEY_1 + digit)) {
				down = digit;
			}
		}
		if (down >= 0 && down != this.digitDown) {
			showCamera(String.valueOf((char) ('A' + down)));
		}
		this.digitDown = down;
	}

	/**
	 * the mouse of the player takes the camera they point at, holds it, and lets go of it
	 */
	private void reach(Minecraft mc, LocalPlayer player, float partialTick, double dt, CameraConfig config) {
		if (!hasOwnWindow() || isSteered() || this.pose == null || mc.screen != null || showsOwnView()) {
			this.grab.reset();
			return;
		}
		Vec3 eyes = player.getEyePosition(partialTick);
		Vec3 look = player.getViewVector(partialTick);
		if (this.grab.isHolding()) {
			if (mc.options.keyUse.isDown()) {
				this.grab.hold(player, eyes, look, this.subject, config, dt);
			} else {
				letGo(config);
			}
			return;
		}
		boolean several = this.mode == Mode.FREE;
		this.grab.aim(eyes, look, several ? this.free.count() : 1,
				camera -> several ? this.free.position(camera) : this.pose.position(),
				camera -> !several || isAround(camera, eyes));
		if (this.grab.isAiming() && mc.options.keyUse.isDown()) {
			if (several && this.grab.aimedAt() != this.free.active()) {
				// the one in the hand is the one that films
				this.free.show(this.grab.aimedAt());
				this.pose = this.free.pose(0);
			}
			if (several) {
				// in the hand it is off whatever it sat on
				this.free.unstick();
			}
			this.grab.take(this.pose, eyes);
		}
	}

	/**
	 * A free camera stays as it was held, and let go of in a swing it glides on from there. The camera of a shot
	 * stays where it was put like one put down by hand in VR, and thrown it flies there.
	 */
	private void letGo(CameraConfig config) {
		this.grab.release();
		Vec3 held = this.grab.position();
		float fov = this.pose == null ? DEFAULT_FOV : this.pose.fov();
		if (this.mode == Mode.FREE) {
			this.free.place(held, this.grab.forward(), fov);
			Entity touched = touched(held);
			if (touched != null) {
				this.free.stick(touched, partialTick());
				say("vrcamera.message.free.stuck", this.free.name(this.free.active()), touched.getName());
				return;
			}
			Vec3 swing = this.grab.swing(this.subject.velocity);
			if (swing.length() > THROW_SPEED) {
				this.free.fling(swing.scale(config.throwPower));
			}
			return;
		}
		Vec3 thrown = this.grab.thrown(this.subject.velocity, config.throwPower);
		boolean wasThrown = thrown.lengthSqr() > 0;
		Vec3 landing = held;
		if (wasThrown) {
			Vec3 target = held.add(thrown);
			landing = held.lerp(target, WorldProbe.armFraction(this.subject, held, target, config));
		}
		Shot shot = shotFrom(landing, fov, 0);
		this.rig.adopt(held, shot, this.subject);
		// held close it looked at the face, and goes on looking there
		this.rig.lookFrom(this.grab.aim());
		if (wasThrown) {
			this.rig.blend();
		}
		handOver(shot);
	}

	private Pose filmFree(Minecraft mc, LocalPlayer player, float partialTick, double dt, CameraConfig config) {
		this.free.ride(partialTick, dt);
		if (this.flying) {
			this.free.fly(new Vec3(held(mc.options.keyRight) - held(mc.options.keyLeft),
					held(mc.options.keyJump) - held(mc.options.keyShift),
					held(mc.options.keyUp) - held(mc.options.keyDown)), held(mc.options.keySprint) > 0);
		}
		followGaze(player, partialTick, dt, config);
		Pose filmed = this.free.pose(dt);
		if (!this.free.isGone()) {
			return filmed;
		}
		if (this.free.count() == 1) {
			// the last one is not thrown away, the mode has nothing to film with then
			this.free.fling(Vec3.ZERO);
			return filmed;
		}
		say("vrcamera.message.free.removed", this.free.name(this.free.active()));
		this.free.remove();
		return this.free.pose(0);
	}

	/**
	 * The free camera a player turns to films them, like a host who turns to the camera that is live: no key for
	 * it. Once per turn, and only after their look stayed there for a moment. A camera picked by hand stays picked
	 * until they look at another one.
	 */
	private void followGaze(LocalPlayer player, float partialTick, double dt, CameraConfig config) {
		// a camera a server has film for a while is not looked away from
		if (!config.freeAutoSwitch || this.flying || this.free.count() < 2 || this.lentId != null) {
			this.gazeAt = -1;
			return;
		}
		Vec3 eyes = player.getEyePosition(partialTick);
		Vec3 look = player.getViewVector(partialTick);
		int found = -1;
		double nearest = Math.cos(Math.toRadians(config.freeAutoSwitchAngle));
		for (int camera = 0; camera < this.free.count(); camera++) {
			Vec3 to = this.free.position(camera).subtract(eyes);
			double distance = to.length();
			// not the one at their own eyes, they look into that one whatever they do
			if (distance < GAZE_NEAR || !isAround(camera, eyes)) {
				continue;
			}
			double facing = to.dot(look) / distance;
			if (facing > nearest && WorldProbe.visible(this.subject, eyes, this.free.position(camera))) {
				nearest = facing;
				found = camera;
			}
		}
		if (this.free.isInFlight()) {
			// On its way to another camera it is not cut away from: at the start it is right where the one it left
			// stands, which the player may well look at. What they look at when it arrives counts as seen already
			this.gazeAt = found;
			this.gazeTime = -1;
			return;
		}
		if (found != this.gazeAt) {
			this.gazeAt = found;
			this.gazeTime = 0;
			return;
		}
		if (found < 0 || this.gazeTime < 0) {
			return;
		}
		this.gazeTime += dt;
		if (this.gazeTime > config.freeAutoSwitchSeconds) {
			// done for this turn of the head
			this.gazeTime = -1;
			if (found != this.free.active()) {
				this.free.show(found);
			}
		}
	}

	private Pose filmShot(Minecraft mc, double dt, CameraConfig config) {
		Shot shot;
		if (this.steered != null) {
			steer(mc, dt);
			this.steered.update(this.subject, config, dt);
			shot = this.steered;
		} else if (this.mode == Mode.FOLLOW) {
			if (this.followShot == null || !this.rig.ready()) {
				this.followShot = new Shot(ShotType.CUSTOM, config.preset(), 1);
				this.followShot.start(this.subject, config);
				this.rig.snap(this.followShot, this.subject);
			} else if (this.subject.teleported) {
				this.rig.rebase(this.subject);
			}
			this.followShot.update(this.subject, config, dt);
			shot = this.followShot;
		} else {
			this.director.update(this.subject, this.rig, dt);
			shot = this.director.current();
		}
		this.rig.update(shot, this.subject, dt, config);
		// Walls can push the camera all the way into the player, before the director has another shot. Their own
		// view is the picture for that long, the inside of their head is none
		double fromHead = this.rig.position().distanceTo(this.subject.head);
		this.inside = this.filmsSelf && fromHead < (this.inside ? INSIDE_OUT : INSIDE_IN) * this.subject.unit;
		return new Pose(this.rig.position(), new Quaternionf(this.rig.rotation()),
				(float) Math.clamp(this.rig.fov(), 1.0, 179.0));
	}

	/**
	 * moves the shot the player steers the way the keys say
	 */
	private void steer(Minecraft mc, double dt) {
		ShotConfig place = this.steered.config;
		double around = held(mc.options.keyRight) - held(mc.options.keyLeft);
		double away = held(mc.options.keyDown) - held(mc.options.keyUp);
		double up = held(mc.options.keyJump) - held(mc.options.keyShift);
		double turned = around * STEER_TURN * dt;
		place.azimuth = Math.toDegrees(CamMath.wrap(Math.toRadians(place.azimuth + turned)));
		// Right away, and not when the shot has caught up with it: a shot follows where it should be slowly, to
		// not swing with every turn of the player
		this.steered.azimuth += Math.toRadians(turned);
		// by a part of the distance, not by blocks: a far camera would crawl and a near one jump
		place.distance = CamMath.clamp(place.distance * Math.exp(away * STEER_ZOOM * dt), 0.6, 48.0);
		place.elevation = CamMath.clamp(place.elevation + up * STEER_RISE * dt, -35.0, 85.0);
	}

	/**
	 * Called once per tick. The players around are told where the camera that films is, like they are about the
	 * one of a player in VR: every other tick, they smooth it out
	 */
	public void tick() {
		Component hint = CameraHints.next(hintNow());
		LocalPlayer player = Minecraft.getInstance().player;
		if (hint != null && player != null) {
			player.displayClientMessage(hint, true);
		}
		if (this.lentId != null && System.nanoTime() > this.lentUntil) {
			endLent();
		}
		tellFilming();
		Pose filming = lens();
		if (filming != null && ++this.shareTicks % 2 == 0 && CameraConfig.current().shareCamera) {
			PhotoSync.INSTANCE.shareCamera(filming.position(), filming.rotation());
		}
	}

	/**
	 * tells the server which free camera films, when that is another one than before
	 */
	private void tellFilming() {
		if (this.mode != Mode.FREE || this.freeLevel == null || this.free.isEmpty()) {
			this.toldFilming = null;
			return;
		}
		int camera = this.free.active();
		String filming = this.free.name(camera);
		if (!filming.equals(this.toldFilming)) {
			this.toldFilming = filming;
			PhotoSync.INSTANCE.shareSwitch(filming, this.free.id(camera), this.free.position(camera));
		}
	}

	/**
	 * @return what the player is doing with the camera right now, for the hint on what to press. Null while the
	 * camera has no window of its own: in the game window a hint would be in its picture
	 */
	private CameraHints.Hint hintNow() {
		if (!hasOwnWindow() || this.pose == null) {
			return null;
		}
		boolean free = this.mode == Mode.FREE;
		if (isSteered()) {
			return CameraHints.Hint.STEER;
		}
		if (this.grab.isHolding()) {
			return free ? CameraHints.Hint.HOLD_FREE : CameraHints.Hint.HOLD;
		}
		if (this.grab.isAiming()) {
			return free && this.grab.aimedAt() != this.free.active() ? CameraHints.Hint.AIM_OTHER :
					CameraHints.Hint.AIM;
		}
		return free ? CameraHints.Hint.IDLE_FREE : CameraHints.Hint.IDLE;
	}

	/**
	 * Called once per frame. Filming into the game window, the game has to look from behind the player: that
	 * draws the player, and leaves the hand that is drawn over a first person view out of the picture. Also after
	 * F5 was pressed. With a window of its own the view of the player is theirs
	 */
	public void keepView(Minecraft mc) {
		if (this.mode != Mode.OFF && Vr.isRunning()) {
			// in VR the camera of Vivecraft does all of this
			setMode(Mode.OFF);
			return;
		}
		if (this.mode != Mode.OFF && CameraConfig.current().screenOutput == ScreenOutput.SCREEN) {
			if (this.viewBefore == null) {
				this.viewBefore = mc.options.getCameraType();
			}
			// with no room for the camera the picture is the first person view of the game itself
			CameraType view = showsOwnView() ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK;
			if (mc.options.getCameraType() != view) {
				mc.options.setCameraType(view);
			}
		} else if (this.viewBefore != null) {
			mc.options.setCameraType(this.viewBefore);
			this.viewBefore = null;
		}
	}

	/**
	 * @return if the one who is filmed is the player themselves
	 */
	public boolean filmsSelf() {
		return this.filmsSelf;
	}

	// ---- what the camera films, and where it is

	/**
	 * @return which of the free cameras films, counted from 0, or -1 if none of them does
	 */
	public int activeFreeCamera() {
		return this.mode == Mode.FREE ? this.free.active() : -1;
	}

	/**
	 * @return if the camera is on and films into a window of its own
	 */
	public boolean hasOwnWindow() {
		return this.mode != Mode.OFF && CameraConfig.current().screenOutput == ScreenOutput.WINDOW;
	}

	/**
	 * @return if what the game draws right now is the picture of this camera: all the time while it films into
	 * the game window, and only in its own turn while it has a window of its own
	 */
	public boolean filmsNow() {
		return this.mode != Mode.OFF && (DirectorPass.isActive() ||
				CameraConfig.current().screenOutput == ScreenOutput.SCREEN);
	}

	/**
	 * @return if the picture is what the player sees themselves right now. In VR a camera with no room around the
	 * player films from their face. At a screen the view of the player is that already, with their hand and
	 * their menus in it
	 */
	public boolean showsOwnView() {
		return this.ownView || (this.filmsSelf && this.steered == null && this.mode == Mode.DIRECTOR &&
				this.director != null && this.director.current() != null &&
				(this.director.current().type == ShotType.POV || this.inside));
	}

	/**
	 * @return if the lines that help to frame a picture are on it: while the player has the camera, never after
	 */
	public boolean showsGrid() {
		return isSteered() && hasOwnWindow();
	}

	/**
	 * @return where the camera was put by the last {@link #update}, null while it does not film
	 */
	public Pose pose() {
		return filmsNow() && !showsOwnView() && !DesktopGui.isDrawing() ? this.pose : null;
	}

	/**
	 * @return where the camera is, for a photo that is taken with it. Null if there is none that films from a
	 * place of its own, and a photo is of what the player sees
	 */
	public Pose lens() {
		return this.mode == Mode.OFF || showsOwnView() ? null : this.pose;
	}

	/**
	 * @return what the camera is doing, for the debug overlay
	 */
	public List<String> debugLines() {
		List<String> lines = new ArrayList<>();
		lines.add("VRCamera on screen: " + this.mode + (hasOwnWindow() ? ", own window" : ", game window"));
		Shot shot = this.steered != null ? this.steered : this.mode == Mode.FOLLOW ? this.followShot :
														  this.director == null ? null : this.director.current();
		if (shot != null) {
			lines.add("shot: " + shot.type + (showsOwnView() ? " (own view of the player)" : ""));
		}
		if (hasOwnWindow()) {
			lines.addAll(DirectorPass.debugLines());
		}
		return lines;
	}

	private boolean showsMarker() {
		return hasOwnWindow() && this.pose != null && !DirectorPass.isActive() && !showsOwnView();
	}

	// ---- shown to the player: where the cameras are, while they film into a window and can't be told from the view

	/**
	 * @return where the camera is right now. It is moved once per picture it takes, the game draws more frames
	 * than that: shown where it was moved to, it would go in steps
	 */
	private Vec3 markerPosition() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (this.grab.isHolding() && player != null &&
				this.grab.position().distanceToSqr(this.pose.position()) < 1.0E-6) {
			// held, it goes with the eyes of the player: with those of this very frame
			return this.grab.shownAt(player, player.getEyePosition(partialTick()));
		}
		double since = Math.min((System.nanoTime() - this.poseNanos) / 1.0E9, this.poseStep * 1.5);
		return this.pose.position().add(this.poseSpeed.scale(Math.max(0.0, since)));
	}

	/**
	 * @param viewPosition where the pass looks from, the pose stack is relative to that
	 */
	public void renderModel(SubmitNodeCollector output, Vec3 viewPosition, PoseStack poseStack) {
		if (!showsMarker()) {
			return;
		}
		RemoteCameras.INSTANCE.drawModel(output, viewPosition, poseStack, markerPosition(), this.pose.rotation());
		for (int camera = 0; this.mode == Mode.FREE && camera < this.free.count(); camera++) {
			if (camera != this.free.active()) {
				RemoteCameras.INSTANCE.drawModel(output, viewPosition, poseStack, this.free.position(camera),
						this.free.rotation(camera));
			}
		}
	}

	/**
	 * The camera icon with the distance to it, like in VR: over the camera, or at the edge of the view on the
	 * side the camera is on. Called while the game collects gizmos for a pass
	 */
	public void drawLabel() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (!showsMarker() || player == null || !CameraConfig.current().indicator) {
			return;
		}
		try {
			// From where the game looks in this very frame. From where the player was when the camera was moved
			// last, the icon would shake with every step
			Camera view = mc.gameRenderer.getMainCamera();
			Vec3 eye = view.getPosition();
			Vec3 forward = new Vec3(view.getLookVector().x(), view.getLookVector().y(), view.getLookVector().z());
			Vec3 up = new Vec3(view.getUpVector().x(), view.getUpVector().y(), view.getUpVector().z());
			UnaryOperator<Vec3> placed = ViewBob.steady(mc, player, eye, forward, up);
			int filming = this.mode == Mode.FREE ? this.free.active() : 0;
			boolean several = this.mode == Mode.FREE && this.free.count() > 1;
			CameraIndicator.draw(CAMERA_ICON, several ? this.free.name(filming) : "",
					markerPosition(), eye, forward, up, player.getScale(), true, farSize(eye, markerPosition()) * this.grab.iconSize(filming, filming),
					placed);
			// the free cameras that do not film have their name for an icon, and no place at the edge of the view
			for (int camera = 0; several && camera < this.free.count(); camera++) {
				if (camera != filming && isAround(camera, eye)) {
					CameraIndicator.draw(this.free.name(camera), "",
							this.free.position(camera), eye, forward, up, player.getScale(), false,
							NAME_SIZE * farSize(eye, this.free.position(camera)) * this.grab.iconSize(camera, filming), placed);
				}
			}
		} catch (IllegalStateException e) {
			// no gizmo collection is running, nothing to draw into
		}
	}

	public enum Mode {
		OFF, DIRECTOR, FOLLOW, FREE
	}

	/**
	 * where the camera is and how it looks, for one frame
	 */
	public record Pose(Vec3 position, Quaternionf rotation, float fov) {
	}
}
