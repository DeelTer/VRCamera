package ru.deelter.vrcamera.client.shot;

import net.minecraft.world.phys.Vec3;

import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.config.ShotConfig;
import ru.deelter.vrcamera.client.math.CamMath;
import ru.deelter.vrcamera.client.math.SmoothVec;
import ru.deelter.vrcamera.client.rig.Subject;

/**
 * A running shot. Describes where the camera wants to be, the {@link ru.deelter.vrcamera.client.rig.Rig} then moves
 * the camera there and keeps it out of walls.
 */
public final class Shot {

	private static final double POV_AIM_LAG = 0.25;

	public final ShotType type;
	public final ShotConfig config;
	/**
	 * -1 or 1, which side of the player the shot is on
	 */
	public final int side;
	private final SmoothVec aim = new SmoothVec();
	/**
	 * seconds this shot is running
	 */
	public double age;
	/**
	 * seconds this shot should run
	 */
	public double duration = Double.MAX_VALUE;
	public double distanceScale = 1.0;
	/**
	 * asked for by the player, stays even if it does not fit what they are doing
	 */
	public boolean forced;
	public double azimuth;
	public double elevation;
	public double distance;
	public double fov;
	public Vec3 lookTarget = Vec3.ZERO;
	/**
	 * fixed camera position, only for shots that don't move with the player
	 */
	public Vec3 worldPos;
	private double maxRange;
	private double stillTime;
	private boolean hadTarget;

	public Shot(ShotType type, ShotConfig config, int side) {
		this.type = type;
		this.config = config;
		this.side = side;
	}

	/**
	 * @return if the camera stays in place, instead of moving with the player
	 */
	public boolean isWorld() {
		return type == ShotType.FLYBY;
	}

	/**
	 * @return if the camera should aim exactly where the shot says, without leaving room in front of the player
	 */
	public boolean exactAim() {
		return type == ShotType.HANDS || type == ShotType.DUEL || type == ShotType.DEATH ||
				type == ShotType.POV || type == ShotType.MENU;
	}

	/**
	 * @return if the camera can swing over from or to this shot, instead of jumping
	 */
	public boolean blends() {
		return !isWorld() && !type.cutsOnly();
	}

	/**
	 * @return azimuth the camera should be at, for where the player is and looks right now
	 */
	private double wantedAzimuth(Subject subject) {
		double reference = subject.facing;
		final Vec3 focus = focus(subject);
		if (focus != null) {
			final Vec3 toFocus = focus.subtract(subject.center);

			if (toFocus.horizontalDistance() > 0.5 * subject.unit) {

				reference = CamMath.azimuthOf(toFocus);
			}
		}
		return reference + Math.toRadians(config.azimuth) * side;
	}

	/**
	 * @return what this shot shows together with the player, null for the shots that only show the player
	 */
	private Vec3 focus(Subject subject) {
		return switch (type) {
			case DUEL -> subject.targetCenter;
			case MENU -> subject.guiCenter;
			default -> null;
		};
	}

	public void start(Subject subject, CameraConfig config) {
		age = 0;
		stillTime = 0;
		azimuth = wantedAzimuth(subject);
		aim.reset(subject.headDir);

		if (type == ShotType.FLYBY) {

			Vec3 dir = CamMath.forward(subject.facing);
			final Vec3 horizontal = new Vec3(subject.velocity.x, 0, subject.velocity.z);
			if (horizontal.length() > 1.0) {
				dir = horizontal.normalize();
			}
			final Vec3 right = new Vec3(-dir.z, 0, dir.x);
			final double base = this.config.distance * subject.unit * distanceScale;
			final double lead = base * CamMath.clamp(0.6 + subject.speed / 8.0, 0.6, 2.2);
			worldPos = subject.center
					.add(dir.scale(lead))
					.add(right.scale(side * 0.4 * base))
					.add(0, 0.3 * subject.unit, 0);
			maxRange = Math.max(lead * 2.2, 10.0 * subject.unit);
		}
		update(subject, config, 0);
	}

	public void update(Subject subject, CameraConfig config, double dt) {
		age += dt;
		stillTime = subject.speed < 0.5 ? stillTime + dt : Math.max(0, stillTime - 2.0 * dt);

		final double scale = type == ShotType.POV ? 1.0 : distanceScale;
		final double baseDistance = this.config.distance * subject.unit * scale;
		elevation = Math.toRadians(this.config.elevation);
		distance = baseDistance;
		fov = this.config.fov;
		lookTarget = subject.center;

		if (type == ShotType.FLYBY) {
			final Vec3 offset = worldPos.subtract(subject.center);
			distance = offset.length();
			azimuth = CamMath.azimuthOf(offset);
			elevation = CamMath.elevationOf(offset);

			fov = CamMath.clamp(Math.toDegrees(2.0 * Math.atan(2.9 * subject.unit / distance)), 20.0,
					this.config.fov);
			return;
		}

		if (type.orbits()) {
			azimuth += side * Math.toRadians(config.orbitSpeed) * dt;
		} else {

			final boolean tight = focus(subject) != null;
			final double error = CamMath.wrap(wantedAzimuth(subject) - azimuth);
			final double deadzone = Math.toRadians(tight ? 4.0 : config.turnDeadzone);
			final double lag = Math.max(0.01, tight ? config.turnLag * 0.6 : config.turnLag);
			if (Math.abs(error) > deadzone && dt > 0) {
				azimuth += (error - Math.signum(error) * deadzone) * (1.0 - Math.exp(-dt / lag));
			}
		}

		switch (type) {
			case CRANE -> {
				final double progress = CamMath.smoothstep(age / Math.max(1.0, this.config.maxDuration));
				elevation *= CamMath.lerp(0.5, 1.0, progress);
				distance *= CamMath.lerp(0.6, 1.0, progress);
			}
			case FRONT -> distance *= CamMath.lerp(1.0, 0.72, CamMath.smoothstep(stillTime / 8.0));
			case DEATH -> {
				distance *= CamMath.lerp(1.0, 1.6, CamMath.smoothstep(age / 8.0));
				if (subject.targetCenter != null) {

					lookTarget = subject.center.lerp(subject.targetCenter, 0.35);
				}
			}
			case POV -> {
				final Vec3 aim = this.aim.update(subject.headDir, POV_AIM_LAG, dt);
				lookTarget = subject.head.add(aim.scale(8.0 * subject.unit));
			}
			case MENU -> {
				if (subject.guiCenter != null) {

					lookTarget = subject.center.lerp(subject.guiCenter, 0.8);
				}
			}
			case HANDS -> {
				if (subject.hands.distanceTo(subject.center) < 1.5 * subject.unit) {
					lookTarget = subject.center.lerp(subject.hands, 0.7);
				}
			}
			case DUEL -> {
				if (subject.targetCenter != null) {
					hadTarget = true;

					distance += 0.35 * subject.targetCenter.distanceTo(subject.center);
					lookTarget = subject.center.lerp(subject.targetCenter, 0.4);
				}
			}
			default -> {
			}
		}

		if (config.speedFov) {
			fov += CamMath.clamp((subject.speed - 6.0) * 0.8, 0.0, 14.0);
		}
	}

	/**
	 * @return if the shot has nothing more to show
	 */
	public boolean finished(Subject subject) {
		return switch (type) {
			case FLYBY -> age > 1.5 && distance > maxRange;
			case DUEL -> hadTarget && subject.targetCenter == null;
			default -> false;
		};
	}

	/**
	 * @return camera position this shot would have for the given orbit values
	 */
	public Vec3 position(Vec3 center, Subject subject, double azimuth, double elevation, double distance) {
		if (type == ShotType.POV) {

			final Vec3 aim = this.aim.get();
			Vec3 ahead = new Vec3(aim.x, 0, aim.z);
			ahead = ahead.length() < 0.2 ? CamMath.forward(subject.facing) : ahead.normalize();

			return subject.head.add(center.subtract(subject.center)).add(ahead.scale(distance));
		}
		Vec3 pos = center.add(CamMath.orbit(azimuth, elevation).scale(distance));
		if (type == ShotType.LOW) {

			pos = new Vec3(pos.x, Math.max(pos.y, subject.feet.y + 0.25 * subject.unit), pos.z);
		}
		return pos;
	}

	public Vec3 desiredPosition(Subject subject) {
		return isWorld() ? worldPos :
				position(subject.center, subject, azimuth, elevation, distance);
	}
}
