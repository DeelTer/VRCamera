package ru.deelter.vrcamera.client.desktop;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.math.CamMath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The cameras of the free mode: each stays where it was put and looks where it was turned, whatever the player
 * does. One of them films. Flown by hand it comes after the keys and the mouse softly, for a move that can be shown.
 * <p>
 * They are kept with the world they stand in, one file per dimension.
 */
final class FreeCamera {

	static final int MOST = 26;
	private static final String FILES = "cameras-";
	private static final String SET_MARK = "@";
	private static final String SHARED = "vrcamera-cameras:";

	private static final double SPEED = 6.0;
	private static final double FAST = 3.0;

	private static final double MOVE_EASE = 5.0;
	private static final double TURN_EASE = 14.0;
	private static final double FOV_EASE = 6.0;

	private static final double MAX_PITCH = 84.0;
	private static final double MIN_FOV = 10.0;
	private static final double MAX_FOV = 120.0;

	private static final double FOV_WHEEL = 0.08;

	private static final double GLIDE_DRAG = 0.7;
	private static final double GONE_AFTER = 48.0;

	private static final double FLIGHT_SPEED = 6.0;
	private static final double FLIGHT_SHORTEST = 1.5;
	private static final double FLIGHT_LONGEST = 8.0;

	private static final double CARRY_EASE = 6.0;

	private static final int FROM_SERVER_MOST = 8;
	private static final Gson GSON = new Gson();
	private final List<Spot> spots = new ArrayList<>();
	private final Set<String> declined = new LinkedHashSet<>();
	private int active;
	private Path file;
	private Vec3 position = Vec3.ZERO;
	private Vec3 velocity = Vec3.ZERO;
	private Vec3 push = Vec3.ZERO;
	private Vec3 glide = Vec3.ZERO;
	private double glided;
	private Spot flightFrom;
	private double flight = 1.0;
	private double flightSeconds;
	private double yaw;
	private double pitch;
	private double fov = 70.0;

	/**
	 * @return the name of the file the cameras of a dimension are kept in
	 */
	static String fileName(String dimension, String set) {
		return FILES + dimension + (set.isEmpty() ? "" : SET_MARK + set) + ".json";
	}

	/**
	 * @return the set a file of cameras belongs to, empty for the one every world starts with
	 */
	private static String setOf(String fileName) {
		final int mark = fileName.indexOf(SET_MARK);
		return mark < 0 ? "" : fileName.substring(mark + 1, fileName.length() - ".json".length());
	}

	/**
	 * @return the sets of cameras there are for a dimension, the one every world starts with left out
	 */
	static List<String> sets(Path folder, String dimension) {
		try (final Stream<Path> files = Files.list(folder)) {
			return files.map(file -> file.getFileName().toString())
					.filter(name -> name.startsWith(FILES + dimension + SET_MARK) && name.endsWith(".json"))
					.map(FreeCamera::setOf).sorted().toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	/**
	 * Writes cameras someone gave as text down as a set of their own.
	 *
	 * @return how many there are, 0 if the text is not cameras
	 */
	static int importTo(Path file, String text) {
		if (!text.trim().startsWith(SHARED)) {
			return 0;
		}
		try {
			final String written = new String(Base64.getDecoder().decode(text.trim().substring(SHARED.length())),
					StandardCharsets.UTF_8);
			final Spot[] given = GSON.fromJson(written, Spot[].class);
			if (given == null || given.length == 0) {
				return 0;
			}
			final List<Spot> kept = new ArrayList<>();
			for (final Spot spot : List.of(given).subList(0, Math.min(given.length, MOST))) {
				spot.name = String.valueOf((char) ('A' + kept.size()));
				spot.id = null;
				kept.add(spot);
			}
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(kept), StandardCharsets.UTF_8);
			return kept.size();
		} catch (IOException | RuntimeException e) {
			return 0;
		}
	}

	private static double bodyYaw(Entity entity, float partialTick) {
		return entity instanceof LivingEntity living ?
				Mth.rotLerp(partialTick, living.yBodyRotO, living.yBodyRot) : entity.getViewYRot(partialTick);
	}

	@NotNull
	private static Vec3 forward(double yaw, double pitch) {
		final double yawRad = Math.toRadians(yaw);
		final double pitchRad = Math.toRadians(pitch);
		return new Vec3(-Math.sin(yawRad) * Math.cos(pitchRad), -Math.sin(pitchRad),
				Math.cos(yawRad) * Math.cos(pitchRad));
	}

	private static double ease(double rate, double dt) {
		return 1.0 - Math.exp(-rate * dt);
	}

	/**
	 * @return the cameras as text, to give to someone who plays on the same map
	 */
	String export() {
		settle();
		return SHARED + Base64.getEncoder().encodeToString(GSON.toJson(spots).getBytes(StandardCharsets.UTF_8));
	}

	boolean isEmpty() {
		return spots.isEmpty();
	}

	int count() {
		return spots.size();
	}

	/**
	 * @return which of them films, starting at 0
	 */
	int active() {
		return active;
	}

	Vec3 position(int camera) {
		return camera == active ? position : spots.get(camera).position();
	}

	Quaternionf rotation(int camera) {
		final Quaternionf rotation = new Quaternionf();
		CamMath.lookRotation(camera == active ? forward(yaw, pitch) : spots.get(camera).forward(),
				rotation);
		return rotation;
	}

	/**
	 * Puts one more camera somewhere, and films with it.
	 *
	 * @param forward where it looks
	 * @return false if there are as many as there can be
	 */
	boolean add(Vec3 position, Vec3 forward, double fov) {
		if (spots.size() >= MOST) {
			return false;
		}
		settle();
		final Spot spot = new Spot();
		spot.name = freeName();

		int at = 0;
		while (at < spots.size() && spots.get(at).name.compareTo(spot.name) < 0) {
			at++;
		}
		spots.add(at, spot);
		active = -1;
		show(at);
		place(position, forward, fov);
		return true;
	}

	String name(int camera) {
		return spots.get(camera).name;
	}

	List<String> names() {
		return spots.stream().map(spot -> spot.name).toList();
	}

	/**
	 * takes all cameras of the world away, those of its other dimensions as well
	 */
	void clear() {
		spots.forEach(this::decline);
		spots.clear();
		active = 0;
		if (file == null) {
			return;
		}
		try (final Stream<Path> files = Files.list(file.getParent())) {
			for (final Path other : files.toList()) {
				final String name = other.getFileName().toString();
				if (name.startsWith(FILES) && setOf(name).equals(setOf(file.getFileName().toString()))) {
					Files.deleteIfExists(other);
				}
			}
		} catch (IOException e) {
			Vrcamera.LOGGER.warn("VRCamera: can't remove the cameras next to {}", file, e);
		}
		if (!declined.isEmpty()) {
			save();
		}
	}

	/**
	 * @return which camera a server calls that, -1 if none
	 */
	int indexOf(String id) {
		for (int camera = 0; camera < spots.size(); camera++) {
			if (id.equals(spots.get(camera).id)) {
				return camera;
			}
		}
		return -1;
	}

	/**
	 * @return what a server calls the camera, null for one the player made
	 */
	String id(int camera) {
		return spots.get(camera).id;
	}

	/**
	 * Puts up a camera a server gives the player, without cutting to it. One they already have stays as they have
	 * it, one they threw away stays gone.
	 *
	 * @param anyway to put it there all the same: the server has it at another place now
	 * @return which camera that is, -1 if it was not put up
	 */
	int place(String id, Vec3 position, double yaw, double pitch, double fov, boolean anyway) {
		if (anyway) {
			declined.remove(id);
		}
		int there = indexOf(id);
		if (there >= 0 && !anyway) {
			return there;
		}
		if (there < 0 && (declined.contains(id) || spots.size() >= MOST ||
				spots.stream().filter(spot -> spot.id != null).count() >= FROM_SERVER_MOST)) {
			return -1;
		}
		settle();
		final Spot filming = spots.isEmpty() ? null : spots.get(active);
		final Spot spot = there >= 0 ? spots.get(there) : new Spot();
		spot.id = id;
		spot.x = position.x;
		spot.y = position.y;
		spot.z = position.z;
		spot.yaw = yaw;
		spot.pitch = CamMath.clamp(pitch, -MAX_PITCH, MAX_PITCH);
		spot.fov = CamMath.clamp(fov, MIN_FOV, MAX_FOV);
		spot.carrier = null;
		if (there < 0) {
			spot.name = freeName();
			there = 0;
			while (there < spots.size() && spots.get(there).name.compareTo(spot.name) < 0) {
				there++;
			}
			spots.add(there, spot);
		}
		if (filming == null || filming == spot) {
			active = -1;
			show(there);
		} else {
			active = spots.indexOf(filming);
		}
		save();
		return there;
	}

	/**
	 * A server takes cameras back that it gave. What the player threw away of them is forgotten with that: the
	 * server may give them again.
	 *
	 * @param exact true for the one with that id, false for all whose id starts with it
	 */
	void takeBack(String id, boolean exact) {
		final Predicate<String> meant = given -> given != null && (exact ? given.equals(id) : given.startsWith(id));
		final boolean forgotten = declined.removeIf(meant);
		settle();
		final Spot filming = spots.isEmpty() ? null : spots.get(active);
		if (!spots.removeIf(spot -> meant.test(spot.id))) {
			if (forgotten) {
				save();
			}
			return;
		}
		int kept = spots.indexOf(filming);
		if (spots.isEmpty()) {
			active = 0;
		} else if (kept >= 0) {
			active = kept;
		} else {
			active = -1;
			show(0);
		}
		save();
	}

	/**
	 * puts the camera that films somewhere else, at once
	 */
	void place(Vec3 position, Vec3 forward, double fov) {
		if (spots.isEmpty()) {
			return;
		}
		final Vec3 look = forward.normalize();
		final Spot spot = spots.get(active);
		flight = 1.0;
		this.position = position;
		spot.yaw = Math.toDegrees(Math.atan2(-look.x, look.z));
		spot.pitch = CamMath.clamp(Math.toDegrees(-Math.asin(CamMath.clamp(look.y, -1.0, 1.0))), -MAX_PITCH, MAX_PITCH);
		spot.fov = CamMath.clamp(fov, MIN_FOV, MAX_FOV);
		show(active);
		save();
	}

	/**
	 * cuts to another camera
	 */
	void show(int camera) {
		settle();
		active = camera;
		flight = 1.0;
		final Spot spot = spots.get(camera);
		position = spot.position();
		velocity = Vec3.ZERO;
		glide = Vec3.ZERO;
		glided = 0;
		yaw = spot.yaw;
		pitch = spot.pitch;
		fov = spot.fov;
	}

	/**
	 * Goes over to another camera in one move, filming all the way: from where and how the one that films is, to
	 * where and how the other one stands. The further, the longer it takes.
	 */
	void flyTo(int camera) {
		settle();
		flightFrom = new Spot();
		flightFrom.x = position.x;
		flightFrom.y = position.y;
		flightFrom.z = position.z;
		flightFrom.yaw = yaw;
		flightFrom.pitch = pitch;
		flightFrom.fov = fov;
		active = camera;
		flight = 0;
		flightSeconds = CamMath.clamp(position.distanceTo(spots.get(camera).position()) / FLIGHT_SPEED,
				FLIGHT_SHORTEST, FLIGHT_LONGEST);
		velocity = Vec3.ZERO;
		glide = Vec3.ZERO;
		glided = 0;
	}

	boolean isInFlight() {
		return flight < 1.0;
	}

	/**
	 * throws the camera that films: it glides on from where it is
	 *
	 * @param velocity blocks per second, {@link Vec3#ZERO} stops it where it is
	 */
	void fling(Vec3 velocity) {
		glide = velocity;
		glided = 0;
		if (velocity.lengthSqr() == 0) {
			save();
		}
	}

	/**
	 * Puts the camera that films onto an entity: from now on it goes where that goes and turns with it, the way
	 * one that is strapped to it would
	 */
	void stick(Entity entity, float partialTick) {
		settle();
		final Spot spot = spots.get(active);
		spot.carrier = entity;
		spot.seat = spot.position().subtract(entity.getPosition(partialTick));
		spot.carrierYaw = bodyYaw(entity, partialTick);
		glide = Vec3.ZERO;
	}

	/**
	 * takes the camera that films off what it sits on, it stays where it is
	 */
	void unstick() {
		if (!spots.isEmpty()) {
			spots.get(active).carrier = null;
		}
	}

	/**
	 * moves every camera that sits on an entity along with it, also the ones that do not film
	 */
	void ride(float partialTick, double dt) {
		for (int camera = 0; camera < spots.size(); camera++) {
			final Spot spot = spots.get(camera);
			final Entity carrier = spot.carrier;
			if (carrier == null) {
				continue;
			}
			if (!carrier.isAlive() || carrier.isRemoved()) {

				spot.carrier = null;
				continue;
			}
			final double turn = Mth.wrapDegrees(bodyYaw(carrier, partialTick) - spot.carrierYaw) * ease(CARRY_EASE, dt);
			final double sin = Math.sin(Math.toRadians(turn));
			final double cos = Math.cos(Math.toRadians(turn));
			spot.carrierYaw += turn;
			spot.seat = new Vec3(spot.seat.x * cos - spot.seat.z * sin, spot.seat.y,
					spot.seat.x * sin + spot.seat.z * cos);
			spot.yaw += turn;
			final Vec3 position = carrier.getPosition(partialTick).add(spot.seat);
			spot.x = position.x;
			spot.y = position.y;
			spot.z = position.z;
			if (camera == active && !isInFlight()) {
				this.position = position;
				yaw += turn;
			}
		}
	}

	/**
	 * @return if the camera that films was thrown too far to be kept
	 */
	boolean isGone() {
		return glided > GONE_AFTER;
	}

	/**
	 * takes the camera that films away, the one before it films then
	 */
	void remove() {
		final int before = Math.max(0, active - 1);
		decline(spots.remove(active));
		active = -1;
		show(before);
		save();
	}

	/**
	 * takes one of the cameras away. The one that films goes on filming, unless it is the one
	 */
	void remove(int camera) {
		if (camera == active) {
			remove();
			return;
		}
		settle();
		Spot filming = spots.get(active);
		decline(spots.remove(camera));
		active = spots.indexOf(filming);
		save();
	}

	/**
	 * @param keys what is held, from -1 to 1: to the right, up, and ahead
	 */
	void fly(Vec3 keys, boolean fast) {
		final Vec3 ahead = forward(yaw, pitch);
		final Vec3 right = new Vec3(-ahead.z, 0, ahead.x);
		final Vec3 way = ahead.scale(keys.z).add(right.lengthSqr() < 1.0E-6 ? Vec3.ZERO : right.normalize().scale(keys.x))
				.add(0, keys.y, 0);
		push = way.lengthSqr() < 1.0E-6 ? Vec3.ZERO : way.normalize().scale(SPEED * (fast ? FAST : 1.0));
		if (push.lengthSqr() > 0) {
			glide = Vec3.ZERO;

			unstick();
		}
	}

	/**
	 * @param yaw   degrees to the right
	 * @param pitch degrees down
	 */
	void turn(double yaw, double pitch) {
		if (!spots.isEmpty()) {
			final Spot spot = spots.get(active);
			spot.yaw += yaw;
			spot.pitch = CamMath.clamp(spot.pitch + pitch, -MAX_PITCH, MAX_PITCH);
		}
	}

	/**
	 * @param notches of the wheel, away from the player zooms in
	 */
	void zoom(double notches) {
		if (!spots.isEmpty()) {
			final Spot spot = spots.get(active);
			spot.fov = CamMath.clamp(spot.fov * Math.exp(-notches * FOV_WHEEL), MIN_FOV, MAX_FOV);
		}
	}

	/**
	 * moves the camera that films on by one frame
	 */
	@NotNull
	DesktopCamera.Pose pose(double dt) {
		final Spot spot = spots.get(active);
		if (isInFlight()) {
			flight = Math.min(1.0, flight + dt / flightSeconds);
			final double along = CamMath.smoothstep(flight);
			final Spot from = flightFrom;
			position = from.position().lerp(spot.position(), along);

			yaw = isInFlight() ? from.yaw + Mth.wrapDegrees(spot.yaw - from.yaw) * along : spot.yaw;
			pitch = from.pitch + (spot.pitch - from.pitch) * along;
			fov = from.fov + (spot.fov - from.fov) * along;
			push = Vec3.ZERO;
			return new DesktopCamera.Pose(position, rotation(active), (float) fov);
		}
		velocity = velocity.lerp(push, ease(MOVE_EASE, dt));
		push = Vec3.ZERO;
		position = position.add(velocity.scale(dt));
		if (glide.lengthSqr() > 0) {
			position = position.add(glide.scale(dt));
			glided += glide.length() * dt;
			glide = glide.scale(Math.exp(-GLIDE_DRAG * dt));
			if (glide.lengthSqr() < 0.01) {
				fling(Vec3.ZERO);
			}
		}
		yaw += (spot.yaw - yaw) * ease(TURN_EASE, dt);
		pitch += (spot.pitch - pitch) * ease(TURN_EASE, dt);
		fov += (spot.fov - fov) * ease(FOV_EASE, dt);
		return new DesktopCamera.Pose(position, rotation(active), (float) fov);
	}

	/**
	 * Goes over to the cameras of another world or dimension. Those of the one before are written down first.
	 */
	void open(Path file) {
		save();
		this.file = file;
		spots.clear();
		declined.clear();
		active = 0;
		if (Files.isRegularFile(file)) {
			try {

				JsonElement written = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
				Spot[] kept;
				if (written.isJsonObject()) {
					final Kept all = GSON.fromJson(written, Kept.class);
					kept = all.cameras;
					if (all.declined != null) {
						declined.addAll(List.of(all.declined));
					}
				} else {
					kept = GSON.fromJson(written, Spot[].class);
				}
				if (kept != null) {
					for (final Spot spot : List.of(kept).subList(0, Math.min(kept.length, MOST))) {
						if (spot.name == null) {
							spot.name = freeName();
						}
						spots.add(spot);
					}
				}
			} catch (IOException | JsonParseException | NullPointerException e) {
				Vrcamera.LOGGER.warn("VRCamera: can't read the cameras in {}", file, e);
			}
		}
		if (!spots.isEmpty()) {
			active = -1;
			show(0);
		}
	}

	/**
	 * lets go of the file the cameras are kept in: nothing is written to it any more
	 */
	void close() {
		file = null;
	}

	/**
	 * writes down where the cameras stand
	 */
	void save() {
		if (file == null) {
			return;
		}
		settle();
		try {
			Files.createDirectories(file.getParent());
			Object written = spots;
			if (!declined.isEmpty()) {
				final Kept all = new Kept();
				all.cameras = spots.toArray(new Spot[0]);
				all.declined = declined.toArray(new String[0]);
				written = all;
			}
			Files.writeString(file, GSON.toJson(written), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Vrcamera.LOGGER.warn("VRCamera: can't write the cameras to {}", file, e);
		}
	}

	/**
	 * @return the first letter no camera has
	 */
	private String freeName() {
		for (char letter = 'A'; letter <= 'Z'; letter++) {
			final String name = String.valueOf(letter);
			if (spots.stream().noneMatch(spot -> name.equals(spot.name))) {
				return name;
			}
		}
		throw new IllegalStateException("more cameras than letters");
	}

	/**
	 * the player took a camera away themselves: one a server gave is not given again
	 */
	private void decline(Spot spot) {
		if (spot.id != null) {
			declined.add(spot.id);
		}
	}

	/**
	 * the camera that films is where it was flown to, its spot has to hear of that
	 */
	private void settle() {

		if (!isInFlight() && active >= 0 && active < spots.size()) {
			final Spot spot = spots.get(active);
			spot.x = position.x;
			spot.y = position.y;
			spot.z = position.z;
		}
	}

	/**
	 * where a camera stands: degrees the way the game counts them for a player
	 */
	private static final class Spot {

		private String name;
		private double x;
		private double y;
		private double z;
		private double yaw;
		private double pitch;
		private double fov;

		private String id;

		private transient Entity carrier;
		private transient Vec3 seat;
		private transient double carrierYaw;

		private @NotNull
		Vec3 position() {
			return new Vec3(x, y, z);
		}

		private Vec3 forward() {
			return FreeCamera.forward(yaw, pitch);
		}
	}

	/**
	 * what is written down of a world, where there is more to it than the cameras
	 */
	private static final class Kept {
		private Spot[] cameras;
		private String[] declined;
	}
}
