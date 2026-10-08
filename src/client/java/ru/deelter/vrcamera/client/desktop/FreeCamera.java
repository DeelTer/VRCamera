package ru.deelter.vrcamera.client.desktop;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.math.CamMath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The cameras of the free mode: each stays where it was put and looks where it was turned, whatever the player
 * does. One of them films. Flown by hand it comes after the keys and the mouse softly, for a move that can be shown.
 * <p>
 * They are kept with the world they stand in, one file per dimension.
 */
final class FreeCamera {
	// one letter of the alphabet for each
	static final int MOST = 26;
	private static final String FILES = "cameras-";
	// blocks per second, and how many times that with the sprint key
	private static final double SPEED = 6.0;
	private static final double FAST = 3.0;
	// how fast it comes after what is asked of it, per second
	private static final double MOVE_EASE = 5.0;
	private static final double TURN_EASE = 14.0;
	private static final double FOV_EASE = 6.0;
	// straight up and down the picture has no way to be level
	private static final double MAX_PITCH = 84.0;
	private static final double MIN_FOV = 10.0;
	private static final double MAX_FOV = 120.0;
	// the part of the field of view one notch of the wheel is
	private static final double FOV_WHEEL = 0.08;
	// Thrown, a camera glides on and slows down by this part of its speed per second. One that got further than
	// this many blocks that way is gone
	private static final double GLIDE_DRAG = 0.7;
	private static final double GONE_AFTER = 48.0;
	// a flight from one camera to another: blocks per second, and the seconds it takes at least and at most
	private static final double FLIGHT_SPEED = 6.0;
	private static final double FLIGHT_SHORTEST = 1.5;
	private static final double FLIGHT_LONGEST = 8.0;
	// how fast a camera on an entity comes after the turns of it, per second: a mob jerks its body around
	private static final double CARRY_EASE = 6.0;
	// how many cameras of a world a server may have given
	private static final int FROM_SERVER_MOST = 8;
	private static final Gson GSON = new Gson();
	private final List<Spot> spots = new ArrayList<>();
	// cameras of a server the player threw away: not to be given again
	private final Set<String> declined = new LinkedHashSet<>();
	private int active;
	private Path file;
	// the one that films, on its way to where its spot says
	private Vec3 position = Vec3.ZERO;
	private Vec3 velocity = Vec3.ZERO;
	private Vec3 push = Vec3.ZERO;
	private Vec3 glide = Vec3.ZERO;
	private double glided;
	// The flight from one camera to another: where it started, how far along it is from 0 to 1, and how long it
	// takes. At 1 there is none
	private Spot flightFrom;
	private double flight = 1.0;
	private double flightSeconds;
	private double yaw;
	private double pitch;
	private double fov = 70.0;

	/**
	 * @return the name of the file the cameras of a dimension are kept in
	 */
	static String fileName(String dimension) {
		return FILES + dimension + ".json";
	}

	private static double bodyYaw(Entity entity, float partialTick) {
		return entity instanceof LivingEntity living ?
				Mth.rotLerp(partialTick, living.yBodyRotO, living.yBodyRot) : entity.getViewYRot(partialTick);
	}

	private static Vec3 forward(double yaw, double pitch) {
		double yawRad = Math.toRadians(yaw);
		double pitchRad = Math.toRadians(pitch);
		return new Vec3(-Math.sin(yawRad) * Math.cos(pitchRad), -Math.sin(pitchRad),
				Math.cos(yawRad) * Math.cos(pitchRad));
	}

	private static double ease(double rate, double dt) {
		return 1.0 - Math.exp(-rate * dt);
	}

	boolean isEmpty() {
		return this.spots.isEmpty();
	}

	int count() {
		return this.spots.size();
	}

	/**
	 * @return which of them films, starting at 0
	 */
	int active() {
		return this.active;
	}

	Vec3 position(int camera) {
		return camera == this.active ? this.position : this.spots.get(camera).position();
	}

	Quaternionf rotation(int camera) {
		Quaternionf rotation = new Quaternionf();
		CamMath.lookRotation(camera == this.active ? forward(this.yaw, this.pitch) : this.spots.get(camera).forward(),
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
		if (this.spots.size() >= MOST) {
			return false;
		}
		settle();
		Spot spot = new Spot();
		spot.name = freeName();
		// in the order of the alphabet, which is the order they are gone through in
		int at = 0;
		while (at < this.spots.size() && this.spots.get(at).name.compareTo(spot.name) < 0) {
			at++;
		}
		this.spots.add(at, spot);
		this.active = -1;
		show(at);
		place(position, forward, fov);
		return true;
	}

	/**
	 * @return the first letter no camera has
	 */
	private String freeName() {
		for (char letter = 'A'; letter <= 'Z'; letter++) {
			String name = String.valueOf(letter);
			if (this.spots.stream().noneMatch(spot -> name.equals(spot.name))) {
				return name;
			}
		}
		throw new IllegalStateException("more cameras than letters");
	}

	String name(int camera) {
		return this.spots.get(camera).name;
	}

	List<String> names() {
		return this.spots.stream().map(spot -> spot.name).toList();
	}

	/**
	 * takes all cameras of the world away, those of its other dimensions as well
	 */
	void clear() {
		this.spots.forEach(this::decline);
		this.spots.clear();
		this.active = 0;
		if (this.file == null) {
			return;
		}
		try (Stream<Path> files = Files.list(this.file.getParent())) {
			for (Path other : files.toList()) {
				if (other.getFileName().toString().startsWith(FILES)) {
					Files.deleteIfExists(other);
				}
			}
		} catch (IOException e) {
			Vrcamera.LOGGER.warn("VRCamera: can't remove the cameras next to {}", this.file, e);
		}
		if (!this.declined.isEmpty()) {
			save();
		}
	}

	/**
	 * the player took a camera away themselves: one a server gave is not given again
	 */
	private void decline(Spot spot) {
		if (spot.id != null) {
			this.declined.add(spot.id);
		}
	}

	/**
	 * @return which camera a server calls that, -1 if none
	 */
	int indexOf(String id) {
		for (int camera = 0; camera < this.spots.size(); camera++) {
			if (id.equals(this.spots.get(camera).id)) {
				return camera;
			}
		}
		return -1;
	}

	/**
	 * @return what a server calls the camera, null for one the player made
	 */
	String id(int camera) {
		return this.spots.get(camera).id;
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
			this.declined.remove(id);
		}
		int there = indexOf(id);
		if (there >= 0 && !anyway) {
			return there;
		}
		if (there < 0 && (this.declined.contains(id) || this.spots.size() >= MOST ||
				this.spots.stream().filter(spot -> spot.id != null).count() >= FROM_SERVER_MOST)) {
			return -1;
		}
		settle();
		Spot filming = this.spots.isEmpty() ? null : this.spots.get(this.active);
		Spot spot = there >= 0 ? this.spots.get(there) : new Spot();
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
			while (there < this.spots.size() && this.spots.get(there).name.compareTo(spot.name) < 0) {
				there++;
			}
			this.spots.add(there, spot);
		}
		if (filming == null || filming == spot) {
			this.active = -1;
			show(there);
		} else {
			this.active = this.spots.indexOf(filming);
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
		Predicate<String> meant = given -> given != null && (exact ? given.equals(id) : given.startsWith(id));
		boolean forgotten = this.declined.removeIf(meant);
		settle();
		Spot filming = this.spots.isEmpty() ? null : this.spots.get(this.active);
		if (!this.spots.removeIf(spot -> meant.test(spot.id))) {
			if (forgotten) {
				save();
			}
			return;
		}
		int kept = this.spots.indexOf(filming);
		if (this.spots.isEmpty()) {
			this.active = 0;
		} else if (kept >= 0) {
			this.active = kept;
		} else {
			this.active = -1;
			show(0);
		}
		save();
	}

	/**
	 * puts the camera that films somewhere else, at once
	 */
	void place(Vec3 position, Vec3 forward, double fov) {
		if (this.spots.isEmpty()) {
			return;
		}
		Vec3 look = forward.normalize();
		Spot spot = this.spots.get(this.active);
		this.flight = 1.0;
		this.position = position;
		spot.yaw = Math.toDegrees(Math.atan2(-look.x, look.z));
		spot.pitch = CamMath.clamp(Math.toDegrees(-Math.asin(CamMath.clamp(look.y, -1.0, 1.0))), -MAX_PITCH, MAX_PITCH);
		spot.fov = CamMath.clamp(fov, MIN_FOV, MAX_FOV);
		show(this.active);
		save();
	}

	/**
	 * cuts to another camera
	 */
	void show(int camera) {
		settle();
		this.active = camera;
		this.flight = 1.0;
		Spot spot = this.spots.get(camera);
		this.position = spot.position();
		this.velocity = Vec3.ZERO;
		this.glide = Vec3.ZERO;
		this.glided = 0;
		this.yaw = spot.yaw;
		this.pitch = spot.pitch;
		this.fov = spot.fov;
	}

	/**
	 * Goes over to another camera in one move, filming all the way: from where and how the one that films is, to
	 * where and how the other one stands. The further, the longer it takes.
	 */
	void flyTo(int camera) {
		settle();
		this.flightFrom = new Spot();
		this.flightFrom.x = this.position.x;
		this.flightFrom.y = this.position.y;
		this.flightFrom.z = this.position.z;
		this.flightFrom.yaw = this.yaw;
		this.flightFrom.pitch = this.pitch;
		this.flightFrom.fov = this.fov;
		this.active = camera;
		this.flight = 0;
		this.flightSeconds = CamMath.clamp(this.position.distanceTo(this.spots.get(camera).position()) / FLIGHT_SPEED,
				FLIGHT_SHORTEST, FLIGHT_LONGEST);
		this.velocity = Vec3.ZERO;
		this.glide = Vec3.ZERO;
		this.glided = 0;
	}

	boolean isInFlight() {
		return this.flight < 1.0;
	}

	/**
	 * throws the camera that films: it glides on from where it is
	 *
	 * @param velocity blocks per second, {@link Vec3#ZERO} stops it where it is
	 */
	void fling(Vec3 velocity) {
		this.glide = velocity;
		this.glided = 0;
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
		Spot spot = this.spots.get(this.active);
		spot.carrier = entity;
		spot.seat = spot.position().subtract(entity.getPosition(partialTick));
		spot.carrierYaw = bodyYaw(entity, partialTick);
		this.glide = Vec3.ZERO;
	}

	/**
	 * takes the camera that films off what it sits on, it stays where it is
	 */
	void unstick() {
		if (!this.spots.isEmpty()) {
			this.spots.get(this.active).carrier = null;
		}
	}

	/**
	 * moves every camera that sits on an entity along with it, also the ones that do not film
	 */
	void ride(float partialTick, double dt) {
		for (int camera = 0; camera < this.spots.size(); camera++) {
			Spot spot = this.spots.get(camera);
			Entity carrier = spot.carrier;
			if (carrier == null) {
				continue;
			}
			if (!carrier.isAlive() || carrier.isRemoved()) {
				// gone, or too far away to be known of: the camera stays where it was last
				spot.carrier = null;
				continue;
			}
			double turn = Mth.wrapDegrees(bodyYaw(carrier, partialTick) - spot.carrierYaw) * ease(CARRY_EASE, dt);
			double sin = Math.sin(Math.toRadians(turn));
			double cos = Math.cos(Math.toRadians(turn));
			spot.carrierYaw += turn;
			spot.seat = new Vec3(spot.seat.x * cos - spot.seat.z * sin, spot.seat.y,
					spot.seat.x * sin + spot.seat.z * cos);
			spot.yaw += turn;
			Vec3 position = carrier.getPosition(partialTick).add(spot.seat);
			spot.x = position.x;
			spot.y = position.y;
			spot.z = position.z;
			if (camera == this.active && !isInFlight()) {
				this.position = position;
				this.yaw += turn;
			}
		}
	}

	/**
	 * @return if the camera that films was thrown too far to be kept
	 */
	boolean isGone() {
		return this.glided > GONE_AFTER;
	}

	/**
	 * takes the camera that films away, the one before it films then
	 */
	void remove() {
		int before = Math.max(0, this.active - 1);
		decline(this.spots.remove(this.active));
		this.active = -1;
		show(before);
		save();
	}

	/**
	 * @param keys what is held, from -1 to 1: to the right, up, and ahead
	 */
	void fly(Vec3 keys, boolean fast) {
		Vec3 ahead = forward(this.yaw, this.pitch);
		Vec3 right = new Vec3(-ahead.z, 0, ahead.x);
		Vec3 way = ahead.scale(keys.z).add(right.lengthSqr() < 1.0E-6 ? Vec3.ZERO : right.normalize().scale(keys.x))
				.add(0, keys.y, 0);
		this.push = way.lengthSqr() < 1.0E-6 ? Vec3.ZERO : way.normalize().scale(SPEED * (fast ? FAST : 1.0));
		if (this.push.lengthSqr() > 0) {
			this.glide = Vec3.ZERO;
			// flown, it leaves what it sat on
			unstick();
		}
	}

	/**
	 * @param yaw   degrees to the right
	 * @param pitch degrees down
	 */
	void turn(double yaw, double pitch) {
		if (!this.spots.isEmpty()) {
			Spot spot = this.spots.get(this.active);
			spot.yaw += yaw;
			spot.pitch = CamMath.clamp(spot.pitch + pitch, -MAX_PITCH, MAX_PITCH);
		}
	}

	/**
	 * @param notches of the wheel, away from the player zooms in
	 */
	void zoom(double notches) {
		if (!this.spots.isEmpty()) {
			Spot spot = this.spots.get(this.active);
			spot.fov = CamMath.clamp(spot.fov * Math.exp(-notches * FOV_WHEEL), MIN_FOV, MAX_FOV);
		}
	}

	/**
	 * moves the camera that films on by one frame
	 */
	DesktopCamera.Pose pose(double dt) {
		Spot spot = this.spots.get(this.active);
		if (isInFlight()) {
			this.flight = Math.min(1.0, this.flight + dt / this.flightSeconds);
			double along = CamMath.smoothstep(this.flight);
			Spot from = this.flightFrom;
			this.position = from.position().lerp(spot.position(), along);
			// the short way around, and by the end it is the number the spot has: a turn more or less looks the same
			this.yaw = isInFlight() ? from.yaw + Mth.wrapDegrees(spot.yaw - from.yaw) * along : spot.yaw;
			this.pitch = from.pitch + (spot.pitch - from.pitch) * along;
			this.fov = from.fov + (spot.fov - from.fov) * along;
			this.push = Vec3.ZERO;
			return new DesktopCamera.Pose(this.position, rotation(this.active), (float) this.fov);
		}
		this.velocity = this.velocity.lerp(this.push, ease(MOVE_EASE, dt));
		this.push = Vec3.ZERO;
		this.position = this.position.add(this.velocity.scale(dt));
		if (this.glide.lengthSqr() > 0) {
			this.position = this.position.add(this.glide.scale(dt));
			this.glided += this.glide.length() * dt;
			this.glide = this.glide.scale(Math.exp(-GLIDE_DRAG * dt));
			if (this.glide.lengthSqr() < 0.01) {
				fling(Vec3.ZERO);
			}
		}
		this.yaw += (spot.yaw - this.yaw) * ease(TURN_EASE, dt);
		this.pitch += (spot.pitch - this.pitch) * ease(TURN_EASE, dt);
		this.fov += (spot.fov - this.fov) * ease(FOV_EASE, dt);
		return new DesktopCamera.Pose(this.position, rotation(this.active), (float) this.fov);
	}

	/**
	 * Goes over to the cameras of another world or dimension. Those of the one before are written down first.
	 */
	void open(Path file) {
		save();
		this.file = file;
		this.spots.clear();
		this.declined.clear();
		this.active = 0;
		if (Files.isRegularFile(file)) {
			try {
				// a list of cameras, or with more than cameras to it what is written down in save
				JsonElement written = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
				Spot[] kept;
				if (written.isJsonObject()) {
					Kept all = GSON.fromJson(written, Kept.class);
					kept = all.cameras;
					if (all.declined != null) {
						this.declined.addAll(List.of(all.declined));
					}
				} else {
					kept = GSON.fromJson(written, Spot[].class);
				}
				if (kept != null) {
					for (Spot spot : List.of(kept).subList(0, Math.min(kept.length, MOST))) {
						if (spot.name == null) {
							spot.name = freeName();
						}
						this.spots.add(spot);
					}
				}
			} catch (IOException | JsonParseException | NullPointerException e) {
				Vrcamera.LOGGER.warn("VRCamera: can't read the cameras in {}", file, e);
			}
		}
		if (!this.spots.isEmpty()) {
			this.active = -1;
			show(0);
		}
	}

	/**
	 * writes down where the cameras stand
	 */
	void save() {
		if (this.file == null) {
			return;
		}
		settle();
		try {
			Files.createDirectories(this.file.getParent());
			Object written = this.spots;
			if (!this.declined.isEmpty()) {
				Kept all = new Kept();
				all.cameras = this.spots.toArray(new Spot[0]);
				all.declined = this.declined.toArray(new String[0]);
				written = all;
			}
			Files.writeString(this.file, GSON.toJson(written), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Vrcamera.LOGGER.warn("VRCamera: can't write the cameras to {}", this.file, e);
		}
	}

	/**
	 * the camera that films is where it was flown to, its spot has to hear of that
	 */
	private void settle() {
		// on its way to another camera it is not where that one stands
		if (!isInFlight() && this.active >= 0 && this.active < this.spots.size()) {
			Spot spot = this.spots.get(this.active);
			spot.x = this.position.x;
			spot.y = this.position.y;
			spot.z = this.position.z;
		}
	}

	/**
	 * where a camera stands: degrees the way the game counts them for a player
	 */
	private static final class Spot {
		// a letter. It stays with the camera for as long as there is one, whatever happens to the others
		String name;
		double x;
		double y;
		double z;
		double yaw;
		double pitch;
		double fov;
		// what a server that gave the camera calls it, null for one the player made
		String id;
		// What the camera sits on, where on it, and which way that faced when it was looked at last. Not kept
		// with the world: an entity is not the same one the next time
		transient Entity carrier;
		transient Vec3 seat;
		transient double carrierYaw;

		Vec3 position() {
			return new Vec3(this.x, this.y, this.z);
		}

		Vec3 forward() {
			return FreeCamera.forward(this.yaw, this.pitch);
		}
	}

	/**
	 * what is written down of a world, where there is more to it than the cameras
	 */
	private static final class Kept {
		Spot[] cameras;
		String[] declined;
	}
}
