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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
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
import java.nio.file.Files;
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
	private static final double MAX_FRAME_TIME = 0.25;
	private static final double MAX_GAP = 1.0;
	private static final float DEFAULT_FOV = 70.0F;
	private static final double STEER_TURN = 70.0;
	private static final double STEER_RISE = 40.0;
	private static final double STEER_ZOOM = 1.1;
	private static final double STEER_MIN_DISTANCE = 1.0;
	private static final double MIN_SHOT_DISTANCE = 0.3;
	private static final double FOV_WHEEL = 0.08;
	private static final double MOUSE_TURN = 0.15;
	private static final double DRAG_TURN = 0.12;
	private static final double FREE_AROUND = 64.0;
	private static final double THROW_SPEED = 4.0;
	private static final double GAZE_NEAR = 1.5;
	private static final double INSIDE_IN = 0.55;
	private static final double INSIDE_OUT = 0.8;
	/**
	 * how much sooner the view goes over to the eyes of the player while blocks push the camera up to them: from
	 * that close it shows a back and nothing else
	 */
	private static final double PUSHED_IN = 0.5;
	private static final double MARKER_JUMP = 1.5;
	private static final double REVEAL_SECONDS = 0.5;
	private static final double CUT_PAST_BLOCK = 0.9;
	private static final double CUT_BEFORE_BODY = 0.7;
	private static final double CRAMPED = 0.45;
	private static final double ROOMY = 0.65;
	private static final double REVEAL_SWITCH = 0.05;
	private static final int OPEN_SAMPLES = 2;
	private static final double OPEN_DEPTH = 6.0;
	private static final double OPEN_STEP = 0.5;
	private static final int FROM_SERVER_WAITING = 64;
	private static final double LENT_LONGEST = 600.0;
	private static final double STICK_REACH = 0.5;
	private static final double NAME_SIZE = 0.75;
	private static final double ICON_FULL = 8.0;
	private static final double ICON_SMALLEST = 0.3;
	private static final String CAMERA_ICON = "";
	private final Subject subject = new Subject();
	private final Rig rig = new Rig(true);
	private final FreeCamera free = new FreeCamera();
	private final CameraGrab grab = new CameraGrab();
	private final List<Runnable> fromServer = new ArrayList<>();
	private Mode mode = Mode.OFF;
	private boolean ownView;
	private ClientLevel fromServerLevel;
	private String lentId;
	private long lentUntil;
	private boolean lentShown;
	private Mode lentFrom;
	private String lentBefore;
	private String toldFilming;
	private Mode lastMode = Mode.DIRECTOR;
	private ShotType askedFor;
	private CameraConfig config;
	private Director director;
	private Shot followShot;
	private boolean announceShot;
	private double revealAmount;
	private boolean cramped;
	private boolean shownCramped;
	private Shot steered;
	private boolean flying;
	private boolean filmsSelf = true;
	private boolean steeredFromWindow;
	private int digitDown = -1;
	private ClientLevel freeLevel;
	private int gazeAt = -1;
	private double gazeTime;
	private boolean inside;
	private CameraType viewBefore;
	private long lastNanos;
	private Pose pose;
	private long poseNanos;
	private double poseStep;
	private Vec3 poseSpeed = Vec3.ZERO;
	private int shareTicks;

	private DesktopCamera() {
	}

	private static void say(String key, Object... args) {
		CameraHints.spoke();
		final LocalPlayer player = Minecraft.getInstance().player;
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
	@Nullable
	private static Player find(Minecraft mc, String name) {
		if (name == null || name.isBlank() || mc.level == null) {
			return null;
		}
		for (final Player other : mc.level.players()) {
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
	@Nullable
	private static Entity touched(Vec3 at) {
		final LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return null;
		}
		Entity nearest = null;
		double closest = STICK_REACH * STICK_REACH;
		for (final Entity entity : player.level().getEntities(player, new AABB(at, at).inflate(STICK_REACH),
				entity -> entity.isAlive() && !entity.isSpectator() && entity.isPickable())) {
			final double away = entity.getBoundingBox().distanceToSqr(at);
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

	/**
	 * takes away every free camera of the world the player is in, and puts a first one at their eyes
	 */
	private static String dimension(Minecraft mc) {
		return mc.level.dimension().location().toDebugFileName();
	}

	public Mode mode() {
		return mode;
	}

	public boolean isOn() {
		return mode != Mode.OFF;
	}

	public void cycleMode() {
		setMode(Mode.values()[(mode.ordinal() + 1) % Mode.values().length]);
	}

	public void setMode(Mode mode) {
		final Minecraft mc = Minecraft.getInstance();
		if (mode != Mode.OFF && Vr.isRunning()) {

			return;
		}
		if (mode == this.mode) {
			addCamera();
			return;
		}
		free.save();
		freeLevel = null;
		this.mode = mode;
		if (mode != Mode.OFF) {
			lastMode = mode;
		}
		steered = null;
		flying = false;
		inside = false;
		ownView = false;
		steeredFromWindow = false;
		grab.reset();
		keepView(mc);
		subject.reset();
		rig.reset();
		followShot = null;
		director = null;
		pose = null;
		lastNanos = 0;
		sayMode();
	}

	/**
	 * Takes a free camera away for good: the one the player points at, or the one that films. Not the last one,
	 * the mode has nothing to film with then
	 */
	public void removeCamera() {
		if (mode != Mode.FREE || freeLevel == null || free.isEmpty()) {
			return;
		}
		if (free.count() == 1) {
			say("vrcamera.message.free.last");
			return;
		}
		int camera = grab.isAiming() ? grab.aimedAt() : free.active();
		int next = -1;
		final LocalPlayer player = Minecraft.getInstance().player;
		if (camera == free.active() && player != null) {
			final Vec3 eyes = player.getEyePosition(partialTick());
			double distance = FREE_AROUND * FREE_AROUND;
			for (int other = 0; other < free.count(); other++) {
				final double away = free.position(other).distanceToSqr(eyes);
				if (other != camera && away < distance) {
					distance = away;
					next = other;
				}
			}
			if (next < 0) {
				say("vrcamera.message.free.lastHere");
				return;
			}
		}
		String name = free.name(camera);
		grab.reset();
		free.remove(camera);
		if (next >= 0) {
			free.show(next > camera ? next - 1 : next);
		}
		say("vrcamera.message.free.removed", name);
	}

	/**
	 * The one key between the view of the player and the picture of the camera. A camera with a window of its own
	 * shows the view of the player in it, and stays what it is: the window is what is recorded. One that films into
	 * the game window is turned off, and back on to what it was doing
	 */
	public void toggle() {
		if (!hasOwnWindow()) {
			setMode(mode == Mode.OFF ? lastMode : Mode.OFF);
			return;
		}
		ownView = !ownView;
		if (ownView) {
			say("vrcamera.message.ownview");
		} else {
			sayMode();
		}
	}

	/**
	 * Has the director show a shot, and turns the director on for that if it is not.
	 *
	 * @param type the shot, null for the next one of its own choice
	 */
	public void showShot(ShotType type) {
		if (mode != Mode.DIRECTOR) {
			setMode(Mode.DIRECTOR);
			if (mode != Mode.DIRECTOR) {
				return;
			}
		}
		ownView = false;
		if (type == null) {
			nextShot();
		} else if (director == null) {
			askedFor = type;
		} else {
			director.force(type);
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

	public void nextShot() {

		ownView = false;
		if (mode == Mode.DIRECTOR && director != null) {
			director.next();
			announceShot = true;
		}
	}

	public void toggleHold() {
		if (mode == Mode.DIRECTOR && director != null) {
			say(director.toggleHold() ? "vrcamera.message.hold" : "vrcamera.message.release");
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
		final CameraConfig config = CameraConfig.current();
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
		final CameraConfig config = CameraConfig.current();
		config.filmWith = name == null ? "" : name;
		config.save();
		if (name == null) {
			say("vrcamera.message.with.off");
		} else {
			say("vrcamera.message.with.on", name);
		}
		return true;
	}

	/**
	 * @return the names of the other players around, to pick one to film
	 */
	public List<String> playersAround() {
		final Minecraft mc = Minecraft.getInstance();
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
		final LocalPlayer player = Minecraft.getInstance().player;
		if (mode == Mode.FREE && player != null) {
			final float partialTick = partialTick();
			free.place(player.getEyePosition(partialTick), player.getViewVector(partialTick), ownFov());
		}
	}

	/**
	 * one more free camera, at the eyes of the player, and it films
	 */
	public void addCamera() {
		final LocalPlayer player = Minecraft.getInstance().player;
		if (mode != Mode.FREE || player == null || freeLevel == null) {
			return;
		}
		if (addAtEyes(player, partialTick())) {
			say("vrcamera.message.free.saved", free.name(free.active()));
		} else {
			say("vrcamera.message.free.full", FreeCamera.MOST);
		}
		grab.release();
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
			grab.release();
			free.flyTo(camera);
			say("vrcamera.message.free.point", free.name(camera), free.count());
		}
	}

	/**
	 * @return what the free cameras of the place the player is in are called, none while the mode is another
	 */
	public List<String> cameraNames() {
		return mode == Mode.FREE ? free.names() : List.of();
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

	/**
	 * @return the sets of free cameras there are for where the player is, the usual one left out
	 */
	public List<String> cameraSets() {
		final Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? List.of() : FreeCamera.sets(PhotoStore.worldCache(), dimension(mc));
	}

	/**
	 * @return the name of the set of free cameras that is open, empty for the usual one
	 */
	public String cameraSet() {
		return CameraConfig.current().cameraSet;
	}

	/**
	 * goes over to the set of free cameras after the one that is open, and from the last one to the usual one
	 */
	public void nextCameraSet() {
		final List<String> sets = new ArrayList<>(cameraSets());
		sets.remove("");
		sets.addFirst("");
		useCameraSet(sets.get((sets.indexOf(cameraSet()) + 1) % sets.size()));
	}

	/**
	 * Goes over to another set of free cameras: the same place filmed another way, with cameras that are not in
	 * each other's way. One that is not there yet starts with a camera at the eyes of the player.
	 *
	 * @param set its name, empty for the usual one
	 */
	public void useCameraSet(String set) {
		final CameraConfig config = CameraConfig.current();
		free.save();
		config.cameraSet = set;
		config.save();
		grab.reset();
		freeLevel = null;
		if (mode != Mode.FREE) {
			setMode(Mode.FREE);
		}
		say("vrcamera.message.set", set.isEmpty() ? Component.translatable("vrcamera.message.set.usual") : set);
	}

	/**
	 * copies the free cameras of the set that is open to the clipboard, as text for someone on the same map
	 *
	 * @return false if there are none open
	 */
	public boolean exportCameras() {
		if (mode != Mode.FREE || freeLevel == null || free.isEmpty()) {
			return false;
		}
		Minecraft.getInstance().keyboardHandler.setClipboard(free.export());
		say("vrcamera.message.set.exported", free.count());
		return true;
	}

	/**
	 * makes a set of the cameras that are in the clipboard as text, and goes over to it
	 *
	 * @return false if what is in the clipboard is not cameras
	 */
	public boolean importCameras(String set) {
		final Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return false;
		}
		final Path file = PhotoStore.worldCache().resolve(FreeCamera.fileName(dimension(mc), set));
		if (FreeCamera.importTo(file, mc.keyboardHandler.getClipboard()) == 0) {
			return false;
		}
		useCameraSet(set);
		return true;
	}

	/**
	 * deletes the set of free cameras that is open, and goes over to the usual one
	 *
	 * @return false if the usual one is open: that one stays
	 */
	public boolean deleteCameraSet() {
		final Minecraft mc = Minecraft.getInstance();
		final String set = cameraSet();
		if (set.isEmpty() || mc.level == null) {
			return false;
		}
		final Path file = PhotoStore.worldCache().resolve(FreeCamera.fileName(dimension(mc), set));
		useCameraSet("");
		free.close();
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			Vrcamera.LOGGER.warn("VRCamera: can't delete {}", file, e);
			return false;
		}
		return true;
	}

	public void clearCameras() {
		final LocalPlayer player = Minecraft.getInstance().player;
		if (mode != Mode.FREE || player == null || freeLevel == null) {
			return;
		}
		grab.reset();
		free.clear();
		addAtEyes(player, partialTick());
		say("vrcamera.message.free.cleared");
	}

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
			final boolean known = free.indexOf(id) >= 0;
			int camera = free.place(id, position, yaw, pitch, fov, anyway);
			if (camera < 0) {
				return;
			}
			if (!known) {
				say("vrcamera.message.server.added", free.name(camera));
			}
			if (show && mode == Mode.FREE && camera != free.active()) {
				grab.reset();
				free.show(camera);
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
			grab.reset();
			free.takeBack(id, exact);
			final LocalPlayer player = Minecraft.getInstance().player;
			if (mode == Mode.FREE && free.isEmpty() && player != null) {

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
		if (!CameraConfig.current().serverCameras || mode == Mode.OFF || Vr.isRunning()) {
			return;
		}
		final boolean open = mode == Mode.FREE && freeLevel != null && !free.isEmpty();
		if (!(seconds > 0)) {
			int camera = open ? free.indexOf(id) : -1;
			if (camera >= 0 && camera != free.active()) {
				grab.reset();
				free.show(camera);
			}
			return;
		}
		if (lentId == null) {

			lentFrom = mode;
			lentBefore = open ? free.name(free.active()) : null;
			lentShown = false;
		}
		lentId = id;
		lentUntil = System.nanoTime() + (long) (Math.min(seconds, LENT_LONGEST) * 1.0E9);
		if (open) {
			showLent();
		} else if (mode != Mode.FREE) {

			setMode(Mode.FREE);
		}
	}

	/**
	 * the server is gone, and what it wanted with it
	 */
	public void serverGone() {
		fromServer.clear();
		fromServerLevel = null;
		lentId = null;
	}

	/**
	 * @return if the player has the camera themselves right now, and steers it with the keys they walk with
	 */
	public boolean isSteered() {
		return mode != Mode.OFF && (steered != null || flying);
	}

	/**
	 * The player takes the camera over, or gives it back. Taken over, the keys to walk move it: closer and away,
	 * around the player, up and down, and a free camera flies. Given back it stays where it was put, like a camera
	 * placed by hand in VR: the director keeps it for a while and then goes on, the others keep it for good.
	 */
	public void toggleSteering() {
		if (mode == Mode.OFF || pose == null) {
			return;
		}

		final boolean told = !steeredFromWindow && !(hasOwnWindow() && OutputWindow.isFocused());
		boolean taken;
		if (mode == Mode.FREE) {
			flying = !flying;
			if (!flying) {
				free.save();
			}
			taken = flying;
		} else if (steered != null) {
			handOver(steered);
			steered = null;
			taken = false;
		} else {
			steered = shotFrom(pose.position(), pose.fov(), STEER_MIN_DISTANCE);
			rig.adopt(pose.position(), steered, subject);
			taken = true;
		}
		if (told) {
			say(taken ? "vrcamera.message.steer.on" : "vrcamera.message.steer.off");
		}
	}

	/**
	 * The mouse of a player who flies the free camera from the game window.
	 *
	 * @return false if it is for the player themselves
	 */
	public boolean turn(double yaw, double pitch) {
		if (mode != Mode.FREE || !flying || steeredFromWindow) {
			return false;
		}
		free.turn(yaw * MOUSE_TURN, pitch * MOUSE_TURN);
		return true;
	}

	/**
	 * The mouse wheel while the camera is held: away from the player and back. While it is steered: its zoom.
	 *
	 * @return false if the wheel is for the game
	 */
	public boolean scroll(double amount) {
		if (mode != Mode.OFF && grab.scroll(amount)) {
			return true;
		}
		if (isSteered() && !steeredFromWindow) {
			zoom(amount);
			return true;
		}
		return false;
	}

	/**
	 * @return if the use key belongs to the camera right now: the player holds it, or points at it to take it
	 */
	public boolean wantsUseKey() {
		return mode != Mode.OFF && grab.wantsUseKey();
	}

	/**
	 * @return if the attack key is for a free camera right now, and not for what is behind it
	 */
	public boolean pointsAtFreeCamera() {
		return mode == Mode.FREE && grab.isAiming() && !grab.isHolding();
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
		if (grab.aimedAt() != free.active()) {
			cutTo(grab.aimedAt());
		}
		return true;
	}

	/**
	 * Called while the game sets up its camera for a picture.
	 *
	 * @return where the camera is for that picture, null to leave the view of the game alone
	 */
	@Nullable
	public Pose update(float partialTick) {
		if (!filmsNow() || DesktopGui.isDrawing()) {
			return null;
		}

		if (!DirectorPass.isActive() && !advance(partialTick)) {
			return null;
		}
		return showsOwnView() ? null : pose;
	}

	/**
	 * moves the camera on by one frame
	 *
	 * @return false if it could not be, and is off now
	 */
	public boolean advance(float partialTick) {
		final Minecraft mc = Minecraft.getInstance();
		final LocalPlayer player = mc.player;
		if (mode == Mode.OFF || player == null) {
			return false;
		}
		try {
			final Pose before = pose;
			final long now = System.nanoTime();
			pose = move(mc, player, partialTick);

			final double seconds = (now - poseNanos) / 1.0E9;
			final Vec3 moved = before == null ? Vec3.ZERO : pose.position().subtract(before.position());
			poseSpeed = seconds > 1.0E-4 && seconds < 0.5 && moved.length() < MARKER_JUMP ?
					moved.scale(1.0 / seconds) : Vec3.ZERO;
			poseStep = seconds;
			poseNanos = now;
			return true;
		} catch (RuntimeException e) {

			Vrcamera.LOGGER.error("VRCamera: the camera on the screen failed and was turned off", e);
			setMode(Mode.OFF);
			return false;
		}
	}

	/**
	 * Called once per tick. The players around are told where the camera that films is, like they are about the
	 * one of a player in VR: every other tick, they smooth it out
	 */
	public void tick() {
		final Component hint = CameraHints.next(hintNow());
		final LocalPlayer player = Minecraft.getInstance().player;
		if (hint != null && player != null) {
			player.displayClientMessage(hint, true);
		}
		if (lentId != null && System.nanoTime() > lentUntil) {
			endLent();
		}
		tellFilming();
		final Pose filming = lens();
		if (filming != null && ++shareTicks % 2 == 0 && CameraConfig.current().shareCamera) {
			PhotoSync.INSTANCE.shareCamera(filming.position(), filming.rotation());
		}
	}

	/**
	 * Called once per frame. Filming into the game window, the game has to look from behind the player: that
	 * draws the player, and leaves the hand that is drawn over a first person view out of the picture. Also after
	 * F5 was pressed. With a window of its own the view of the player is theirs
	 */
	public void keepView(Minecraft mc) {
		if (mode != Mode.OFF && Vr.isRunning()) {

			setMode(Mode.OFF);
			return;
		}
		if (mode != Mode.OFF && CameraConfig.current().screenOutput == ScreenOutput.SCREEN) {
			if (viewBefore == null) {
				viewBefore = mc.options.getCameraType();
			}

			final CameraType view = showsOwnView() ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK;
			if (mc.options.getCameraType() != view) {
				mc.options.setCameraType(view);
			}
		} else if (viewBefore != null) {
			mc.options.setCameraType(viewBefore);
			viewBefore = null;
		}
	}

	/**
	 * @return if the one who is filmed is the player themselves
	 */
	public boolean filmsSelf() {
		return filmsSelf;
	}

	/**
	 * @return which of the free cameras films, counted from 0, or -1 if none of them does
	 */
	public int activeFreeCamera() {
		return mode == Mode.FREE ? free.active() : -1;
	}

	/**
	 * @return if the camera is on and films into a window of its own
	 */
	public boolean hasOwnWindow() {
		return mode != Mode.OFF && CameraConfig.current().screenOutput == ScreenOutput.WINDOW;
	}

	/**
	 * @return if what the game draws right now is the picture of this camera: all the time while it films into
	 * the game window, and only in its own turn while it has a window of its own
	 */
	public boolean filmsNow() {
		return mode != Mode.OFF && (DirectorPass.isActive() ||
				CameraConfig.current().screenOutput == ScreenOutput.SCREEN);
	}

	/**
	 * @return if the picture is what the player sees themselves right now. In VR a camera with no room around the
	 * player films from their face. At a screen the view of the player is that already, with their hand and
	 * their menus in it
	 */
	public boolean showsOwnView() {
		return ownView || (filmsSelf && steered == null && mode == Mode.DIRECTOR &&
				director != null && director.current() != null &&
				(director.current().type == ShotType.POV || inside));
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
	@Nullable
	public Pose pose() {
		return filmsNow() && !showsOwnView() && !DesktopGui.isDrawing() ? pose : null;
	}

	/**
	 * @return where the camera is, for a photo that is taken with it. Null if there is none that films from a
	 * place of its own, and a photo is of what the player sees
	 */
	@Nullable
	public Pose lens() {
		return mode == Mode.OFF || showsOwnView() ? null : pose;
	}

	/**
	 * @return what the camera is doing, for the debug overlay
	 */
	public List<String> debugLines() {
		final List<String> lines = new ArrayList<>();
		lines.add("VRCamera on screen: " + mode + (hasOwnWindow() ? ", own window" : ", game window"));
		Shot shot = steered != null ? steered : mode == Mode.FOLLOW || mode == Mode.DRONE ? followShot :
												director == null ? null : director.current();
		if (shot != null) {
			lines.add("shot: " + shot.type + (showsOwnView() ? " (own view of the player)" : ""));
		}
		if (hasOwnWindow()) {
			lines.addAll(DirectorPass.debugLines());
		}
		return lines;
	}

	/**
	 * @param viewPosition where the pass looks from, the pose stack is relative to that
	 */
	public void renderModel(SubmitNodeCollector output, Vec3 viewPosition, PoseStack poseStack) {
		if (!showsMarker()) {
			return;
		}
		RemoteCameras.INSTANCE.drawModel(output, viewPosition, poseStack, markerPosition(), pose.rotation());
		for (int camera = 0; mode == Mode.FREE && camera < free.count(); camera++) {
			if (camera != free.active()) {
				RemoteCameras.INSTANCE.drawModel(output, viewPosition, poseStack, free.position(camera),
						free.rotation(camera));
			}
		}
	}

	/**
	 * The camera icon with the distance to it, like in VR: over the camera, or at the edge of the view on the
	 * side the camera is on. Called while the game collects gizmos for a pass
	 */
	public void drawLabel() {
		final Minecraft mc = Minecraft.getInstance();
		final LocalPlayer player = mc.player;
		if (!showsMarker() || player == null || !CameraConfig.current().indicator) {
			return;
		}
		try {

			final Camera view = mc.gameRenderer.getMainCamera();
			final Vec3 eye = view.getPosition();
			final Vec3 forward = new Vec3(view.getLookVector().x(), view.getLookVector().y(), view.getLookVector().z());
			final Vec3 up = new Vec3(view.getUpVector().x(), view.getUpVector().y(), view.getUpVector().z());
			final UnaryOperator<Vec3> placed = ViewBob.steady(mc, player, eye, forward, up);
			final int filming = mode == Mode.FREE ? free.active() : 0;
			final boolean several = mode == Mode.FREE && free.count() > 1;
			CameraIndicator.draw(CAMERA_ICON, several ? free.name(filming) : "",
					markerPosition(), eye, forward, up, player.getScale(), true, farSize(eye, markerPosition()) * grab.iconSize(filming, filming),
					placed);

			for (int camera = 0; several && camera < free.count(); camera++) {
				if (camera != filming && isAround(camera, eye)) {
					CameraIndicator.draw(free.name(camera), "",
							free.position(camera), eye, forward, up, player.getScale(), false,
							NAME_SIZE * farSize(eye, free.position(camera)) * grab.iconSize(camera, filming), placed);
				}
			}
		} catch (IllegalStateException e) {

		}
	}

	private void sayMode() {
		final CameraConfig config = CameraConfig.current();
		if (mode != Mode.OFF && !config.introShown) {
			config.introShown = true;
			config.save();
			say("vrcamera.hint.intro", CameraHints.keyName("mode"), CameraHints.keyName("toggle"));
		} else if (mode == Mode.DIRECTOR && config.directorManual) {

			say("vrcamera.message.manual", CameraHints.keyName("next"), CameraHints.keyName("toggle"));
		} else {
			say("vrcamera.message.mode",
					Component.translatable("vrcamera.mode." + mode.name().toLowerCase(Locale.ROOT)));
		}
	}

	private boolean addAtEyes(LocalPlayer player, float partialTick) {
		return free.add(player.getEyePosition(partialTick), player.getViewVector(partialTick), ownFov());
	}

	/**
	 * @return the free camera after the one that films that is around the player, -1 if there is none
	 */
	private int nextAround() {
		final LocalPlayer player = Minecraft.getInstance().player;
		if (mode != Mode.FREE || player == null) {
			return -1;
		}
		final Vec3 eyes = player.getEyePosition();
		for (int step = 1; step < free.count(); step++) {
			int camera = (free.active() + step) % free.count();
			if (free.position(camera).distanceToSqr(eyes) < FREE_AROUND * FREE_AROUND) {
				return camera;
			}
		}
		return -1;
	}

	private void cutTo(int camera) {
		grab.release();
		free.show(camera);
		say("vrcamera.message.free.point", free.name(camera), free.count());
	}

	/**
	 * @return if a free camera that does not film is one of those around the player, shown and to be picked. The
	 * ones at another place they built at are not in the way then
	 */
	private boolean isAround(int camera, Vec3 from) {
		final double reach = CameraConfig.current().cameraLabelDistance;
		return camera == free.active() || free.position(camera).distanceToSqr(from) < reach * reach;
	}

	/**
	 * the cameras of the world and dimension the player is in now
	 */
	private void openFree(Minecraft mc, LocalPlayer player, float partialTick) {
		freeLevel = mc.level;
		final Path cache = PhotoStore.worldCache();
		try {
			PhotoStore.prepare(cache);
		} catch (IOException e) {
			Vrcamera.LOGGER.warn("VRCamera: can't make {}", cache, e);
		}
		free.open(cache.resolve(FreeCamera.fileName(dimension(mc), CameraConfig.current().cameraSet)));

		if (fromServerLevel == mc.level) {
			fromServer.forEach(Runnable::run);
		}
		fromServer.clear();
		if (lentId != null && !free.isEmpty()) {

			showLent();
			return;
		}

		final Vec3 eyes = player.getEyePosition(partialTick);
		int nearest = -1;
		double distance = FREE_AROUND * FREE_AROUND;
		for (int camera = 0; camera < free.count(); camera++) {
			final double away = free.position(camera).distanceToSqr(eyes);
			if (away < distance) {
				distance = away;
				nearest = camera;
			}
		}
		if (nearest >= 0) {
			free.show(nearest);
		} else {
			addAtEyes(player, partialTick);
		}
		if (lentId != null) {
			showLent();
		}
	}

	private void showLent() {
		int camera = free.indexOf(lentId);
		if (camera < 0) {

			lentUntil = 0;
			return;
		}
		lentShown = true;
		if (camera != free.active()) {
			grab.reset();
			free.show(camera);
		}
	}

	/**
	 * the time a camera was lent for is over: back to what the player had. Not if they went on by themselves in
	 * the meantime, then that is what they have
	 */
	private void endLent() {
		final String id = lentId;
		lentId = null;
		final boolean untouched = mode == Mode.FREE && freeLevel != null && !free.isEmpty() &&
				id.equals(free.id(free.active()));
		if (lentShown && !untouched) {
			return;
		}
		if (lentFrom != Mode.FREE) {
			if (mode == Mode.FREE) {
				setMode(lentFrom);
			}
			return;
		}
		final int before = lentBefore == null ? -1 : free.names().indexOf(lentBefore);
		if (before >= 0 && untouched) {
			grab.reset();
			free.show(before);
		}
	}

	/**
	 * does something with the free cameras for a server: right away while they are open, and when they are opened
	 * the next time otherwise. Not at all for a player who wants no cameras from servers
	 */
	private void withCameras(Runnable change) {
		final Minecraft mc = Minecraft.getInstance();
		if (!CameraConfig.current().serverCameras || mc.level == null) {
			return;
		}
		if (mode == Mode.FREE && freeLevel == mc.level) {
			change.run();
			return;
		}
		if (fromServerLevel != mc.level) {
			fromServer.clear();
			fromServerLevel = mc.level;
		}
		if (fromServer.size() < FROM_SERVER_WAITING) {
			fromServer.add(change);
		}
	}

	/**
	 * @param closest closer to the player than this many of their sizes the camera has no place of its own, and
	 *                gets one behind them
	 * @return a shot from where the camera is, so that going on from there is not a jump
	 */
	private Shot shotFrom(Vec3 camera, double fov, double closest) {
		final Vec3 offset = camera.subtract(subject.center);
		final ShotConfig place = CameraConfig.defaultPreset();
		if (offset.length() > closest * subject.unit) {
			place.azimuth = Math.toDegrees(CamMath.wrap(CamMath.azimuthOf(offset) - subject.facing));
			place.elevation = Math.toDegrees(CamMath.elevationOf(offset));
			place.distance = Math.max(MIN_SHOT_DISTANCE, offset.length() / subject.unit);
		} else {
			place.azimuth = 180;
			place.elevation = 15;
			place.distance = 3;
		}
		place.fov = fov;
		Shot shot = new Shot(ShotType.CUSTOM, place, 1);
		shot.start(subject, CameraConfig.current());
		return shot;
	}

	/**
	 * a shot the player set up is shown from here on: for a while by the director, for good by the follow camera
	 */
	private void handOver(Shot shot) {
		if (mode == Mode.FOLLOW || mode == Mode.DRONE) {
			followShot = shot;
		} else if (director != null) {
			director.showManual(shot);
		}
	}

	/**
	 * @param notches of the wheel, away from the player zooms in
	 */
	private void zoom(double notches) {
		if (mode == Mode.FREE) {
			free.zoom(notches);
		} else if (steered != null) {
			final ShotConfig place = steered.config;
			place.fov = CamMath.clamp(place.fov * Math.exp(-notches * FOV_WHEEL), 10.0, 120.0);
		}
	}

	private double held(KeyMapping key) {

		boolean down = steeredFromWindow ?
				OutputWindow.isKeyDown(KeyBindingHelper.getBoundKeyOf(key).getValue()) : key.isDown();
		return down ? 1.0 : 0.0;
	}

	private Pose move(Minecraft mc, LocalPlayer player, float partialTick) {
		final CameraConfig config = CameraConfig.current();
		if (config != this.config || director == null) {

			this.config = config;
			director = new Director(config);
			followShot = null;
			if (askedFor != null) {
				director.force(askedFor);
				askedFor = null;
			}
		}

		final double dt = frameTime();
		final Player star = find(mc, config.filmPlayer);
		filmsSelf = star == null || star == player;
		subject.updateWithoutVR(filmsSelf ? player : star, partialTick, dt, dt, config);
		subject.softBlocks = hasOwnWindow() && DitheredBlocks.active();
		subject.seenThrough = config.seeThrough && hasOwnWindow() && OutputWindow.showsThrough();
		final Player partner = find(mc, config.filmWith);
		subject.partner = partner == subject.player ? null : partner;
		if (mode == Mode.FREE && mc.level != freeLevel) {
			openFree(mc, player, partialTick);
		}

		subject.guiCenter = hasOwnWindow() && filmsSelf ? DesktopGui.place(mc, subject, config) : null;
		readWindow(mc);
		reach(mc, player, partialTick, dt, config);
		if (grab.isHolding()) {

			return new Pose(grab.position(), grab.rotation(), pose == null ? DEFAULT_FOV : pose.fov());
		}
		return mode == Mode.FREE ? filmFree(mc, player, partialTick, dt, config) : filmShot(mc, dt, config);
	}

	/**
	 * @return seconds since the camera was moved last
	 */
	private double frameTime() {
		final long now = System.nanoTime();
		final double seconds = lastNanos == 0 ? 0 : (now - lastNanos) / 1.0E9;
		lastNanos = now;
		if (seconds > MAX_GAP) {
			subject.reset();
			return 0;
		}

		return Math.min(seconds, MAX_FRAME_TIME);
	}

	/**
	 * The window of the camera has the keyboard while the player steers from it: going over to it takes the camera,
	 * going back to the game gives it back.
	 */
	private void readWindow(Minecraft mc) {
		final boolean inWindow = hasOwnWindow() && OutputWindow.isFocused();
		if (inWindow != steeredFromWindow && inWindow != isSteered()) {
			toggleSteering();
		}
		steeredFromWindow = inWindow && isSteered();
		pickByDigit(inWindow);

		final double wheel = OutputWindow.scrolled();

		OutputWindow.capture(steeredFromWindow && mode == Mode.FREE);
		final double[] mouse = OutputWindow.mouseMoved();
		if (steeredFromWindow) {

			mc.getFramerateLimitTracker().onInputReceived();
			zoom(wheel);
			free.turn(mouse[0] * DRAG_TURN, mouse[1] * DRAG_TURN);
		}
	}

	/**
	 * the keys 1 to 9 in the window of the camera cut to the free cameras A to I
	 */
	private void pickByDigit(boolean inWindow) {
		int down = -1;
		for (int digit = 0; inWindow && mode == Mode.FREE && digit < 9 && down < 0; digit++) {
			if (OutputWindow.isKeyDown(InputConstants.KEY_1 + digit)) {
				down = digit;
			}
		}
		if (down >= 0 && down != digitDown) {
			showCamera(String.valueOf((char) ('A' + down)));
		}
		digitDown = down;
	}

	/**
	 * the mouse of the player takes the camera they point at, holds it, and lets go of it
	 */
	private void reach(Minecraft mc, LocalPlayer player, float partialTick, double dt, CameraConfig config) {
		if (!hasOwnWindow() || isSteered() || pose == null || mc.screen != null || showsOwnView()) {
			grab.reset();
			return;
		}
		final Vec3 eyes = player.getEyePosition(partialTick);
		final Vec3 look = player.getViewVector(partialTick);
		if (grab.isHolding()) {
			if (mc.options.keyUse.isDown()) {
				grab.hold(player, eyes, look, subject, config, dt);
			} else {
				letGo(config);
			}
			return;
		}
		final boolean several = mode == Mode.FREE;
		grab.aim(eyes, look, several ? free.count() : 1,
				camera -> several ? free.position(camera) : pose.position(),
				camera -> !several || isAround(camera, eyes));
		if (grab.isAiming() && mc.options.keyUse.isDown()) {
			if (several && grab.aimedAt() != free.active()) {

				free.show(grab.aimedAt());
				pose = free.pose(0);
			}
			if (several) {

				free.unstick();
			}
			grab.take(pose, eyes);
		}
	}

	/**
	 * A free camera stays as it was held, and let go of in a swing it glides on from there. The camera of a shot
	 * stays where it was put like one put down by hand in VR, and thrown it flies there.
	 */
	private void letGo(CameraConfig config) {
		grab.release();
		final Vec3 held = grab.position();
		final float fov = pose == null ? DEFAULT_FOV : pose.fov();
		if (mode == Mode.FREE) {
			free.place(held, grab.forward(), fov);
			final Entity touched = touched(held);
			if (touched != null) {
				free.stick(touched, partialTick());
				say("vrcamera.message.free.stuck", free.name(free.active()), touched.getName());
				return;
			}
			final Vec3 swing = grab.swing(subject.velocity);
			if (swing.length() > THROW_SPEED) {
				free.fling(swing.scale(config.throwPower));
			}
			return;
		}
		final Vec3 thrown = grab.thrown(subject.velocity, config.throwPower);
		final boolean wasThrown = thrown.lengthSqr() > 0;
		Vec3 landing = held;
		if (wasThrown) {
			final Vec3 target = held.add(thrown);
			landing = held.lerp(target, WorldProbe.armFraction(subject, held, target, config));
		}
		Shot shot = shotFrom(landing, fov, 0);
		rig.adopt(held, shot, subject);

		rig.lookFrom(grab.aim());
		if (wasThrown) {
			rig.blend();
		}
		handOver(shot);
	}

	/**
	 * @return how far from the camera the player is in the picture that is drawn right now, for the blocks in
	 * between to be drawn see-through. 0 if that is not the picture of a camera that films a player
	 */
	public double ditherReach() {
		return DirectorPass.isActive() && subject.softBlocks && mode != Mode.FREE && pose != null ?
				pose.position().distanceTo(subject.center) : 0.0;
	}

	/**
	 * @return how far the hole the player is seen through behind blocks is open, from 0 to 1
	 */
	public double revealAmount() {
		return hasOwnWindow() && mode != Mode.FREE && !showsOwnView() ? revealAmount : 0.0;
	}

	/**
	 * @return the middle of that hole, and how many blocks across half of it is
	 */
	public Vec3 revealCenter() {
		return subject.center;
	}

	public Vec3 revealFeet() {
		return subject.feet;
	}

	public double revealRadius() {
		return CameraConfig.current().seeThroughRadius * subject.unit;
	}

	/**
	 * @return what is between the camera and the player. With nothing there the picture begins right before the
	 * player, for the hole to close slowly after the last block is out of the way
	 */
	public Blocked blockedBy(Pose lens) {
		final Vec3 ahead = new Vec3(lens.rotation().transform(new Vector3f(0, 0, -1)));
		final Level level = subject.player.level();
		double lastBlock = 0;
		double body = Double.MAX_VALUE;
		for (final Vec3 part : List.of(subject.head.add(0, 0.3 * subject.unit, 0), subject.center, subject.feet)) {
			body = Math.min(body, part.subtract(lens.position()).dot(ahead));
			final BlockHitResult hit = level.clip(new ClipContext(part, lens.position(), ClipContext.Block.COLLIDER,
					ClipContext.Fluid.NONE, subject.player));
			if (hit.getType() != HitResult.Type.MISS) {
				lastBlock = Math.max(lastBlock, hit.getLocation().subtract(lens.position()).dot(ahead));
			}
		}
		final double near = Math.min(lastBlock <= 0 ? Double.MAX_VALUE : lastBlock + CUT_PAST_BLOCK,
				body - CUT_BEFORE_BODY * subject.unit);
		final double open = openBehind(lens, ahead, Math.max(near, 0.0));
		cramped = open < (cramped ? ROOMY : CRAMPED);
		if (revealAmount < REVEAL_SWITCH) {
			shownCramped = cramped;
		}
		return new Blocked(near, shownCramped);
	}

	/**
	 * @return how much of the hole would show something, from 0 to 1, if the picture in it began that far from the
	 * camera. Where there are only solid blocks behind, there is nothing to draw, and the hole is empty
	 */
	private double openBehind(Pose lens, Vec3 ahead, double near) {
		final Level level = subject.player.level();
		final Vec3 right = new Vec3(lens.rotation().transform(new Vector3f(1, 0, 0)));
		final Vec3 up = new Vec3(lens.rotation().transform(new Vector3f(0, 1, 0)));
		final double radius = revealRadius();
		int all = 0;
		int open = 0;
		for (int x = -OPEN_SAMPLES; x <= OPEN_SAMPLES; x++) {
			for (int y = -OPEN_SAMPLES; y <= OPEN_SAMPLES; y++) {
				if (x * x + y * y > OPEN_SAMPLES * OPEN_SAMPLES) {
					continue;
				}
				final Vec3 through = subject.center.add(right.scale(x * radius / OPEN_SAMPLES))
						.add(up.scale(y * radius / OPEN_SAMPLES));
				if (through.y < subject.feet.y) {
					continue;
				}
				final Vec3 way = through.subtract(lens.position()).normalize();
				final double from = near / Math.max(way.dot(ahead), 0.1);
				all++;
				for (double along = from; along < from + OPEN_DEPTH; along += OPEN_STEP) {
					if (!level.getBlockState(BlockPos.containing(lens.position().add(way.scale(along)))).isSolidRender()) {
						open++;
						break;
					}
				}
			}
		}
		return all == 0 ? 1.0 : open / (double) all;
	}

	private Pose filmFree(Minecraft mc, LocalPlayer player, float partialTick, double dt, CameraConfig config) {
		revealAmount = 0;
		free.ride(partialTick, dt);
		if (flying) {
			free.fly(new Vec3(held(mc.options.keyRight) - held(mc.options.keyLeft),
					held(mc.options.keyJump) - held(mc.options.keyShift),
					held(mc.options.keyUp) - held(mc.options.keyDown)), held(mc.options.keySprint) > 0);
		}
		followGaze(player, partialTick, dt, config);
		final Pose filmed = free.pose(dt);
		if (!free.isGone()) {
			return filmed;
		}
		if (free.count() == 1) {

			free.fling(Vec3.ZERO);
			return filmed;
		}
		say("vrcamera.message.free.removed", free.name(free.active()));
		free.remove();
		return free.pose(0);
	}

	/**
	 * The free camera a player turns to films them, like a host who turns to the camera that is live: no key for
	 * it. Once per turn, and only after their look stayed there for a moment. A camera picked by hand stays picked
	 * until they look at another one.
	 */
	private void followGaze(LocalPlayer player, float partialTick, double dt, CameraConfig config) {

		if (!config.freeAutoSwitch || flying || free.count() < 2 || lentId != null) {
			gazeAt = -1;
			return;
		}
		final Vec3 eyes = player.getEyePosition(partialTick);
		final Vec3 look = player.getViewVector(partialTick);
		int found = -1;
		double nearest = Math.cos(Math.toRadians(config.freeAutoSwitchAngle));
		for (int camera = 0; camera < free.count(); camera++) {
			final Vec3 to = free.position(camera).subtract(eyes);
			double distance = to.length();

			if (distance < GAZE_NEAR || !isAround(camera, eyes)) {
				continue;
			}
			final double facing = to.dot(look) / distance;
			if (facing > nearest && WorldProbe.visible(subject, eyes, free.position(camera))) {
				nearest = facing;
				found = camera;
			}
		}
		if (free.isInFlight()) {

			gazeAt = found;
			gazeTime = -1;
			return;
		}
		if (found != gazeAt) {
			gazeAt = found;
			gazeTime = 0;
			return;
		}
		if (found < 0 || gazeTime < 0) {
			return;
		}
		gazeTime += dt;
		if (gazeTime > config.freeAutoSwitchSeconds) {

			gazeTime = -1;
			if (found != free.active()) {
				free.show(found);
			}
		}
	}

	@NotNull
	private Pose filmShot(Minecraft mc, double dt, CameraConfig config) {
		Shot shot;
		if (steered != null) {
			steer(mc, dt);
			steered.update(subject, config, dt);
			shot = steered;
		} else if (mode == Mode.FOLLOW || mode == Mode.DRONE) {
			if (followShot == null || !rig.ready()) {
				followShot = mode == Mode.DRONE ? new Shot(ShotType.DRONE, config.shot(ShotType.DRONE), 1) :
						new Shot(ShotType.CUSTOM, config.preset(), 1);
				followShot.start(subject, config);
				rig.snap(followShot, subject);
			} else if (subject.teleported) {
				rig.rebase(subject);
			}
			followShot.update(subject, config, dt);
			shot = followShot;
		} else {
			director.update(subject, rig, dt);
			shot = director.current();
			if (announceShot) {
				announceShot = false;
				say("vrcamera.message.shot",
						Component.translatable("vrcamera.shot." + shot.type.name().toLowerCase(Locale.ROOT)));
			}
		}
		rig.update(shot, subject, dt, config);
		final double wanted = subject.seenThrough && rig.viewBlocked() && cramped == shownCramped ? 1.0 : 0.0;
		revealAmount += Math.clamp(wanted - revealAmount, -dt / REVEAL_SECONDS, dt / REVEAL_SECONDS);

		final double fromHead = rig.position().distanceTo(subject.head);
		final double pushed = rig.arm() < 0.999 ? PUSHED_IN : 0.0;
		inside = filmsSelf && fromHead < ((inside ? INSIDE_OUT : INSIDE_IN) + pushed) * subject.unit;
		return new Pose(rig.position(), new Quaternionf(rig.rotation()),
				(float) Math.clamp(rig.fov(), 1.0, 179.0));
	}

	/**
	 * moves the shot the player steers the way the keys say
	 */
	private void steer(Minecraft mc, double dt) {
		final ShotConfig place = steered.config;
		final double around = held(mc.options.keyRight) - held(mc.options.keyLeft);
		final double away = held(mc.options.keyDown) - held(mc.options.keyUp);
		final double up = held(mc.options.keyJump) - held(mc.options.keyShift);
		final double turned = around * STEER_TURN * dt;
		place.azimuth = Math.toDegrees(CamMath.wrap(Math.toRadians(place.azimuth + turned)));

		steered.azimuth += Math.toRadians(turned);

		place.distance = CamMath.clamp(place.distance * Math.exp(away * STEER_ZOOM * dt), 0.6, 48.0);
		place.elevation = CamMath.clamp(place.elevation + up * STEER_RISE * dt, -35.0, 85.0);
	}

	/**
	 * tells the server which free camera films, when that is another one than before
	 */
	private void tellFilming() {
		if (mode != Mode.FREE || freeLevel == null || free.isEmpty()) {
			toldFilming = null;
			return;
		}
		int camera = free.active();
		final String filming = free.name(camera);
		if (!filming.equals(toldFilming)) {
			toldFilming = filming;
			PhotoSync.INSTANCE.shareSwitch(filming, free.id(camera), free.position(camera));
		}
	}

	/**
	 * @return what the player is doing with the camera right now, for the hint on what to press. Null while the
	 * camera has no window of its own: in the game window a hint would be in its picture
	 */
	@Nullable
	private CameraHints.Hint hintNow() {
		if (!hasOwnWindow() || pose == null) {
			return null;
		}
		final boolean free = mode == Mode.FREE;
		if (isSteered()) {
			return CameraHints.Hint.STEER;
		}
		if (grab.isHolding()) {
			return free ? CameraHints.Hint.HOLD_FREE : CameraHints.Hint.HOLD;
		}
		if (grab.isAiming()) {
			if (!free) {
				return CameraHints.Hint.AIM;
			}
			return grab.aimedAt() != this.free.active() ? CameraHints.Hint.AIM_OTHER : CameraHints.Hint.AIM_FREE;
		}
		return free ? CameraHints.Hint.IDLE_FREE : CameraHints.Hint.IDLE;
	}

	private boolean showsMarker() {
		return hasOwnWindow() && pose != null && !DirectorPass.isActive() && !showsOwnView();
	}

	/**
	 * @return where the camera is right now. It is moved once per picture it takes, the game draws more frames
	 * than that: shown where it was moved to, it would go in steps
	 */
	private Vec3 markerPosition() {
		final LocalPlayer player = Minecraft.getInstance().player;
		if (grab.isHolding() && player != null &&
				grab.position().distanceToSqr(pose.position()) < 1.0E-6) {

			return grab.shownAt(player, player.getEyePosition(partialTick()));
		}
		final double since = Math.min((System.nanoTime() - poseNanos) / 1.0E9, poseStep * 1.5);
		return pose.position().add(poseSpeed.scale(Math.max(0.0, since)));
	}

	public enum Mode {
		OFF, DIRECTOR, FOLLOW, DRONE, FREE
	}

	/**
	 * what is between the camera and the player
	 *
	 * @param near  how far in front of the camera a picture without it begins: right behind the last block in the
	 *              way, and never as far as the player
	 * @param solid if there is next to nothing but solid blocks around the player, as in a narrow shaft. A picture
	 *              that begins inside those is empty: there the player alone is shown through the blocks, and
	 *              they are left whole
	 */
	public record Blocked(double near, boolean solid) {
	}

	/**
	 * where the camera is and how it looks, for one frame
	 */
	public record Pose(Vec3 position, Quaternionf rotation, float fov) {
	}
}
