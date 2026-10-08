package ru.deelter.vrcamera.client.director;

import net.minecraft.client.Minecraft;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.config.ShotConfig;
import ru.deelter.vrcamera.client.config.Transition;
import ru.deelter.vrcamera.client.math.CamMath;
import ru.deelter.vrcamera.client.rig.Rig;
import ru.deelter.vrcamera.client.rig.Subject;
import ru.deelter.vrcamera.client.rig.WorldProbe;
import ru.deelter.vrcamera.client.shot.Shot;
import ru.deelter.vrcamera.client.shot.ShotType;

import java.util.Random;

/**
 * Decides which shot is shown and when it is replaced. Replaces shots instead of steering around obstacles, when the
 * player gets blocked from view, it switches to an angle that can see them.
 */
public final class Director {

	private static final double OCCLUSION_GRACE = 1.0;

	private static final double FLY_DISTANCE_SCALE = 1.6;
	private static final double TIGHT_DISTANCE_SCALE = 0.7;

	private static final double FALLBACK_DISTANCE_SCALE = 0.6;
	private static final double BOOST = 4.0;

	private static final double TIGHT_POV_FIT = 0.6;
	private static final double SCREEN_POV_FIT = 0.2;

	private static final double PARTNER_FIT = 3.0;

	private static final int HOME_ASIDES = 2;

	private static final double COMBAT_DURATION_SCALE = 0.6;

	private static final double HIDDEN_HEAD_PENALTY = 0.6;
	private static final double ACROSS_WATER_PENALTY = 0.25;
	private static final double SAME_TYPE_PENALTY = 0.25;
	private static final double SIMILAR_VIEW_PENALTY = 0.5;
	private static final double CROSSED_LINE_PENALTY = 0.35;

	private static final double SIMILAR_VIEW_DOT = Math.cos(Math.toRadians(30));

	/**
	 * something happening to the player, that gets its own shot for as long as it lasts
	 */
	public enum Event {
		NONE(null),
		DEATH(ShotType.DEATH),
		FALL(ShotType.FALL),
		/**
		 * the player has an inventory or chest open
		 */
		MENU(ShotType.MENU);

		final ShotType shot;

		Event(ShotType shot) {
			this.shot = shot;
		}
	}

	private final CameraConfig config;
	private final Random random = new Random();

	private Shot current;
	private ShotType lastType;
	private boolean hold;
	private boolean forceNext;

	private ShotType forceType;
	private double occludedTime;
	private String lastReason = "";

	private Context context = Context.IDLE;

	private Context shotContext = Context.IDLE;
	private Event event = Event.NONE;

	private ShotType boost;
	private boolean tight;
	private boolean atScreen;
	private boolean partnered;

	private int asides;
	private double tightTimer;
	private double combatTimer;

	private double mineTime;
	private double fallTimer;

	private Event dismissed = Event.NONE;
	private double stillTime;

	private Entity attacked;

	public Director(CameraConfig config) {
		this.config = config;
	}

	public Shot current() {
		return current;
	}

	public Context context() {
		return context;
	}

	public Event event() {
		return event;
	}

	public boolean isTight() {
		return tight;
	}

	public boolean isHolding() {
		return hold;
	}

	public double occludedTime() {
		return occludedTime;
	}

	/**
	 * @return why the current shot was picked
	 */
	public String lastReason() {
		return lastReason;
	}

	public void reset() {
		current = null;
		hold = false;
		forceNext = false;
		forceType = null;
		event = Event.NONE;
		dismissed = Event.NONE;
		boost = null;
		attacked = null;
		combatTimer = 0;
		mineTime = 0;
		fallTimer = 0;
	}

	/**
	 * replaces the current shot on the next update
	 */
	public void next() {
		forceNext = true;
	}

	/**
	 * the player hit something
	 */
	public void onAttack(Entity entity) {
		attacked = entity;
	}

	/**
	 * replaces the current shot with one of the given type on the next update
	 */
	public void force(ShotType type) {
		forceType = type;
		forceNext = true;
	}

	/**
	 * @return if the current shot is now kept until released
	 */
	public boolean toggleHold() {
		hold = !hold;
		return hold;
	}

	/**
	 * shows a hand placed shot for a while, before the regular rotation continues
	 */
	public void showManual(Shot shot) {
		shot.duration = config.manualHoldSeconds;
		current = shot;
		lastType = shot.type;
		shotContext = context;
		occludedTime = -OCCLUSION_GRACE;
		lastReason = "manual";
	}

	public void update(Subject subject, Rig rig, double dt) {
		updateContext(subject, dt);

		final boolean held = hold || config.directorManual;
		final Event detected = held ? Event.NONE : detectEvent(subject, dt);
		if (detected != dismissed) {

			dismissed = Event.NONE;
		}
		if (forceNext && detected != Event.NONE) {

			dismissed = detected;
		}
		final Event newEvent = detected == dismissed ? Event.NONE : detected;
		final boolean eventOver = newEvent == Event.NONE && event != Event.NONE;
		if (newEvent != event) {
			event = newEvent;
			if (newEvent != Event.NONE) {
				startEvent(subject, rig);
			}
		}
		if (event != Event.NONE && current != null && rig.ready()) {

			if (subject.teleported) {
				rig.rebase(subject);
			}
			current.update(subject, config, dt);
			return;
		}

		String reason = null;
		boolean cut = false;
		if (current == null || !rig.ready()) {
			reason = "start";
			cut = true;
		} else if (subject.teleported && current.isWorld()) {
			reason = "teleport";
			cut = true;
		} else {
			if (subject.teleported) {

				rig.rebase(subject);
				occludedTime = -OCCLUSION_GRACE;
			}
			final double limit = current.isWorld() ? 0.97 : config.occlusionRatio;
			if (rig.arm() < limit) {
				occludedTime += dt;
			} else {

				occludedTime = Math.min(0, occludedTime + dt);
			}
			final boolean blocked = occludedTime > config.occlusionCutTime;
			final boolean finished = current.finished(subject);

			if (forceNext) {
				reason = forceType != null ? "asked for " + forceType : "key";
			} else if (eventOver) {
				reason = "event over";
			} else if (held) {

				if (current.isWorld() && (blocked || finished)) {
					reason = "held shot ended";
					cut = true;
				}
			} else if (blocked) {
				reason = "blocked";

				cut = true;
			} else if (finished) {
				reason = "finished";
			} else if (current.age >= current.duration) {
				reason = "time";
			} else if (current.age > config.minShotTime && current.type != ShotType.CUSTOM &&
					!current.forced && !(isHome() && current.type == ShotType.POV)) {
				final Context now = activity(context);
				if (fit(current.type) <= 0) {
					reason = "unfit for " + context;
				} else if (now != Context.WALK && now != activity(shotContext)) {

					reason = "now " + context;
				}
			}
		}

		if (reason != null) {
			choose(subject, rig, cut, reason);
		}
		current.update(subject, config, dt);
	}

	/**
	 * @return the context, with all the regular moving around on foot counted as one
	 */
	private static Context activity(Context context) {
		return context == Context.IDLE || context == Context.RUN ? Context.WALK : context;
	}

	private Event detectEvent(Subject subject, double dt) {
		if (!config.events) {
			return Event.NONE;
		}
		final Player player = subject.player;
		if (player.isDeadOrDying()) {
			return Event.DEATH;
		}
		boolean falling = false;
		if (!player.onGround() && !player.isFallFlying() && !player.isInWater() && !player.isPassenger() &&
				!player.getAbilities().flying) {
			if (player.fallDistance > 5.0) {
				falling = true;
			} else if (player.getDeltaMovement().y < -0.3) {

				falling = WorldProbe.groundDistance(subject, 24.0) > 6.0 * subject.unit;
			}
		}
		if (falling) {

			fallTimer = 1.0;
		} else {
			fallTimer -= dt;
		}
		if (fallTimer > 0) {
			return Event.FALL;
		}
		return subject.guiCenter != null ? Event.MENU : Event.NONE;
	}

	private void startEvent(Subject subject, Rig rig) {
		final Shot shot = eventShot(subject);

		if (event != Event.FALL && shot.blends() && current != null && rig.ready() && current.blends() &&
				!subject.teleported && config.transition != Transition.CUT) {
			rig.blend();
		} else {
			rig.snap(shot, subject);
		}
		current = shot;
		lastType = shot.type;
		shotContext = context;
		occludedTime = -OCCLUSION_GRACE;
		lastReason = "event " + event + (shot.type == event.shot ? "" : ", no room");
	}

	/**
	 * @return the shot for the event that just started
	 */
	private Shot eventShot(Subject subject) {
		final ShotType type = event.shot;
		final ShotConfig shotConfig = config.shot(type);
		if (event != Event.MENU) {
			final Shot shot = new Shot(type, shotConfig, randomSide());
			shot.start(subject, config);
			return shot;
		}

		Shot best = null;
		double bestRoom = -1;
		for (int side = -1; side <= 1; side += 2) {
			final Shot shot = new Shot(type, shotConfig, side);
			shot.start(subject, config);
			final Vec3 wanted = shot.desiredPosition(subject);
			final double room = wanted.distanceTo(subject.center) *
					WorldProbe.armFraction(subject, subject.center, wanted, config);
			if (room > bestRoom) {
				bestRoom = room;
				best = shot;
			}
		}
		if (bestRoom < type.minDistance * subject.unit && config.shot(ShotType.POV).enabled) {

			final Shot shot = new Shot(ShotType.POV, config.shot(ShotType.POV), 1);
			shot.start(subject, config);
			return shot;
		}
		return best;
	}

	private void choose(Subject subject, Rig rig, boolean cut, String reason) {
		final Selection selection = new Selection(subject, rig);
		final double distanceScale = (context == Context.FLY ? FLY_DISTANCE_SCALE : 1.0) *
				(tight ? TIGHT_DISTANCE_SCALE : 1.0);

		if (forceType == ShotType.CUSTOM) {
			selection.consider(new Shot(ShotType.CUSTOM, config.preset(), 1), 1.0, 1.0);
		} else if (forceType != null) {

			selection.considerBothSides(forceType, 1.0, distanceScale);
		} else if (isHome() && asides <= 0 && (current == null || current.type != ShotType.POV)) {
			selection.considerBothSides(ShotType.POV, 1.0, distanceScale);
		} else {
			if (!considerOpeningShot(selection, distanceScale)) {
				for (final ShotType type : ShotType.values()) {
					final ShotConfig shotConfig = config.shot(type);
					if (type == ShotType.CUSTOM || !shotConfig.enabled || (type == ShotType.POV && isHome()) ||
							(type == ShotType.DUEL && subject.targetCenter == null) ||
							(type == ShotType.HANDS && !subject.tracksHands)) {
						continue;
					}
					final double weight = shotConfig.weight * fit(type) * (type == boost ? BOOST : 1.0);
					if (weight > 0) {
						selection.considerBothSides(type, weight, distanceScale);
					}
				}
				if (config.customInRotation) {

					for (final ShotConfig preset : config.presets) {
						if (preset.enabled && preset.weight > 0) {
							selection.consider(new Shot(ShotType.CUSTOM, preset, 1), preset.weight, 1.0);
						}
					}
				}
			}
		}

		Shot next = selection.best;
		if (next == null) {
			reason += ", no room";
			next = fallback(subject, distanceScale);
		}

		next.forced = forceType != null;
		next.duration = CamMath.lerp(next.config.minDuration, next.config.maxDuration, random.nextDouble()) *
				(context == Context.COMBAT ? COMBAT_DURATION_SCALE : 1.0);
		if (isHome() && !next.forced) {
			if (next.type == ShotType.POV) {
				next.duration = config.povHomeSeconds;
				asides = HOME_ASIDES + random.nextInt(2);
			} else {
				asides--;
			}
		}

		if (next == current) {

			next.age = 0;
			reason += ", kept";
		} else if (!cut && next.blends() && current != null && current.blends() && wantsBlend(next)) {
			rig.blend();
			reason += ", blend";
		} else {
			rig.snap(next, subject);
			reason += ", cut";
		}

		current = next;
		lastType = next.type;
		shotContext = context;
		boost = null;
		forceNext = false;
		forceType = null;
		occludedTime = -OCCLUSION_GRACE;
		lastReason = reason;
	}

	private boolean considerOpeningShot(Selection selection, double distanceScale) {
		if (current != null || forceNext || tight || partnered || activity(context) != Context.WALK) {
			return false;
		}
		final ShotConfig shotConfig = config.shot(ShotType.FLYBY);
		if (!shotConfig.enabled || shotConfig.weight <= 0) {
			return false;
		}

		selection.considerBothSides(ShotType.FLYBY, shotConfig.weight, distanceScale);
		return selection.best != null;
	}

	/**
	 * @return if first person is where the director of a player at a screen comes back to and stays, with a few
	 * other shots in between
	 */
	private boolean isHome() {
		return config.povHome && atScreen;
	}

	/**
	 * @return how well a shot fits what the player is doing and where, 0 means it should not be used
	 */
	private double fit(ShotType type) {
		if (type == ShotType.POV) {

			return tight ? TIGHT_POV_FIT : atScreen ? SCREEN_POV_FIT : 0.0;
		}
		if (type == ShotType.DUEL && partnered) {

			return PARTNER_FIT;
		}
		return type.weight(context) * (tight ? type.tightFactor : 1.0);
	}

	/**
	 * @return the shot to show when none has room. The rig keeps the camera out of the walls on any shot, but it
	 * does that by moving it closer to the player, up to inside of them
	 */
	private Shot fallback(Subject subject, double distanceScale) {
		if (forceType != null) {

			final Shot shot = new Shot(forceType, config.shot(forceType),
					forceType == ShotType.CUSTOM ? 1 : randomSide());
			shot.distanceScale = distanceScale;
			shot.start(subject, config);
			return shot;
		}

		final boolean firstPerson = config.shot(ShotType.POV).enabled;
		final ShotType type = firstPerson ? ShotType.POV : ShotType.SHOULDER;
		if (current != null && current.type == type && !current.forced) {
			return current;
		}
		final Shot shot = new Shot(type, config.shot(type), randomSide());
		shot.distanceScale = firstPerson ? 1.0 : FALLBACK_DISTANCE_SCALE;
		shot.start(subject, config);
		return shot;
	}

	private int randomSide() {
		return random.nextBoolean() ? 1 : -1;
	}

	/**
	 * rates the shots that could come next, and keeps the best one
	 */
	private final class Selection {
		private final Subject subject;

		private final Vec3 viewDir;

		private final Vec3 right;
		private final int currentSide;
		private final boolean headInFluid;

		private Shot best;
		private double bestScore;

		Selection(Subject subject, Rig rig) {
			this.subject = subject;
			Vec3 travel = new Vec3(subject.velocity.x, 0, subject.velocity.z);
			travel = travel.length() > 1.0 ? travel.normalize() : CamMath.forward(subject.facing);
			right = new Vec3(-travel.z, 0, travel.x);
			headInFluid = WorldProbe.inFluid(subject, subject.head);
			if (rig.ready()) {
				viewDir = subject.center.subtract(rig.position()).normalize();
				currentSide = side(rig.position());
			} else {
				viewDir = null;
				currentSide = 0;
			}
		}

		void considerBothSides(ShotType type, double weight, double distanceScale) {
			final ShotConfig shotConfig = Director.this.config.shot(type);
			consider(new Shot(type, shotConfig, -1), weight, distanceScale);
			consider(new Shot(type, shotConfig, 1), weight, distanceScale);
		}

		void consider(Shot shot, double weight, double distanceScale) {
			shot.distanceScale = distanceScale;
			shot.start(subject, Director.this.config);

			final Vec3 center = subject.center;
			final Vec3 wanted = shot.desiredPosition(subject);
			final double free = WorldProbe.armFraction(subject, center, wanted, Director.this.config);

			if (shot.isWorld() && free < 0.9) {
				return;
			}
			final Vec3 actual = center.lerp(wanted, free);
			if (actual.distanceTo(center) < shot.type.minDistance * subject.unit) {
				return;
			}

			double score = weight * (0.35 + 0.65 * free) * (0.8 + 0.4 * Director.this.random.nextDouble());
			if (!WorldProbe.visible(subject, actual, subject.head)) {
				score *= HIDDEN_HEAD_PENALTY;
			}

			if (WorldProbe.inFluid(subject, actual) != headInFluid) {
				score *= ACROSS_WATER_PENALTY;
			}
			if (shot.type == Director.this.lastType) {
				score *= SAME_TYPE_PENALTY;
			}

			if (viewDir != null && viewDir.dot(center.subtract(actual).normalize()) > SIMILAR_VIEW_DOT) {
				score *= SIMILAR_VIEW_PENALTY;
			}
			if (!shot.type.orbits() && currentSide * side(actual) < 0) {
				score *= CROSSED_LINE_PENALTY;
			}
			if (score > bestScore) {
				bestScore = score;
				best = shot;
			}
		}

		/**
		 * @return on which side of the line the player moves along a camera position is: -1, 1, or 0 if it is
		 * close to that line
		 */
		private int side(Vec3 cameraPos) {
			final Vec3 offset = cameraPos.subtract(subject.center);
			final double length = offset.length();
			if (length < 1.0E-3) {
				return 0;
			}
			final double lateral = offset.dot(right) / length;
			return Math.abs(lateral) < 0.2 ? 0 : (int) Math.signum(lateral);
		}
	}

	private boolean wantsBlend(Shot next) {
		return switch (config.transition) {
			case CUT -> false;
			case BLEND -> true;

			case AUTO -> random.nextDouble() < config.blendChance &&
					Math.abs(CamMath.wrap(next.azimuth - current.azimuth)) < Math.toRadians(130);
		};
	}

	private void updateContext(Subject subject, double dt) {
		final Minecraft mc = Minecraft.getInstance();
		final Player player = subject.player;
		partnered = subject.partner != null && subject.target == null && subject.targetCenter != null;

		combatTimer -= dt;
		final Entity attacked = this.attacked;
		this.attacked = null;
		if (attacked instanceof LivingEntity && attacked.isAlive()) {

			combatTimer = 5.0;
			subject.target = attacked;
		} else if (player.hurtTime > 0 || player.isDeadOrDying()) {

			final DamageSource source = player.getLastDamageSource();
			final Entity attacker = source == null ? null : source.getEntity();
			if (attacker instanceof LivingEntity && attacker != player && attacker.isAlive()) {
				combatTimer = 5.0;
				subject.target = attacker;
			}
		}
		if (player.isDeadOrDying() && subject.target != null) {

			combatTimer = 5.0;
		}
		if (subject.target != null && (combatTimer <= 0 ||
				subject.target.distanceTo(player) > 16.0 + 8.0 * subject.unit
		)) {
			subject.target = null;
		}

		if (mc.gameMode != null && mc.gameMode.isDestroying()) {
			mineTime = Math.min(3.0, mineTime + dt);
		} else {
			mineTime = Math.max(0.0, mineTime - 0.5 * dt);
		}
		stillTime = subject.speed > 0.5 ? 0 : stillTime + dt;

		final Context previous = context;
		if (player.isFallFlying()) {
			context = Context.FLY;
		} else if (player.isPassenger()) {
			context = Context.RIDE;
		} else if (combatTimer > 0) {
			context = Context.COMBAT;
		} else if (mineTime > 1.0) {
			context = Context.MINE;
		} else if (player.isSwimming() || player.isInWater()) {
			context = Context.SWIM;
		} else if (subject.speed > 4.8) {
			context = Context.RUN;
		} else if (stillTime > 1.2) {
			context = Context.IDLE;
		} else if (subject.speed > 0.5 || context != Context.IDLE) {

			context = Context.WALK;
		}
		if (context == Context.FLY && previous != Context.FLY) {

			boost = ShotType.FLYBY;
		}

		tightTimer -= dt;
		if (tightTimer <= 0) {
			tightTimer = 0.5;
			tight = WorldProbe.openness(subject) < 0.55;
		}
		atScreen = !subject.tracksHands;
	}

}
