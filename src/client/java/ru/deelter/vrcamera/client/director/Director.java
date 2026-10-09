package ru.deelter.vrcamera.client.director;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.biome.Biome;
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

import java.util.List;
import java.util.Random;

/**
 * Decides which shot is shown and when it is replaced. Replaces shots instead of steering around obstacles, when the
 * player gets blocked from view, it switches to an angle that can see them.
 */
public final class Director {

	private static final double OCCLUSION_GRACE = 1.0;
	private static final List<TagKey<Biome>> ROUGH_BIOMES = List.of(BiomeTags.IS_MOUNTAIN, BiomeTags.IS_HILL,
			BiomeTags.IS_FOREST, BiomeTags.IS_TAIGA, BiomeTags.IS_JUNGLE, BiomeTags.IS_BADLANDS);
	private static final double TERRAIN_INTERVAL = 1.0;
	private static final double ROUGH_DRONE = 2.5;
	private static final double DRONE_NEAREST = 0.85;
	private static final double DRONE_FURTHEST = 1.15;
	private static final double CLIMBED = 3.0;
	private static final double CLIMB_FORGET = 0.4;
	private static final double ROUGH_CRANE = 1.5;
	private static final double ROUGH_FLYBY = 0.5;
	/**
	 * Seconds a shot of a player at a screen is safe from being cut away from for being blocked. On a slope or
	 * between trees every next shot is blocked too, and the camera would not stop cutting
	 */
	private static final double BLOCKED_REST = 3.0;
	/**
	 * Looking ahead: how often, how many seconds of the way of the player, from which speed on, and how long the
	 * camera stays on a side it swung over to
	 */
	private static final double AHEAD_INTERVAL = 0.2;
	private static final double AHEAD_SECONDS = 1.2;
	private static final double AHEAD_SPEED = 1.5;
	private static final double SWING_REST = 3.0;
	/**
	 * How much more of the way to the camera has to be free for a shot to be picked than for it to be kept. With
	 * the same measure for both, a shot at the edge of it is picked and dropped in turns
	 */
	private static final double ROOM_TO_SPARE = 0.15;

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
	private boolean rough;
	private double aheadTimer;
	private double sinceSwing = SWING_REST;
	/**
	 * if the shot that runs may stay when none is found to go to: its time is up, but nothing is wrong with it
	 */
	private boolean canStay;
	private double terrainTimer;
	private boolean partnered;
	private int asides;
	private double tightTimer;
	private double combatTimer;
	private double mineTime;
	/**
	 * the height the player climbs up from. It comes after the player slowly: only going up fast, as up a
	 * mountain side jump after jump, gets far above it
	 */
	private double climbFrom = Double.NaN;
	private double fallTimer;
	private Event dismissed = Event.NONE;
	private double stillTime;
	private Entity attacked;

	public Director(CameraConfig config) {
		this.config = config;
	}

	/**
	 * @return the context, with all the regular moving around on foot counted as one
	 */
	private static Context activity(Context context) {
		return context == Context.IDLE || context == Context.RUN ? Context.WALK : context;
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

		if (current != null && rig.ready() && !held) {
			lookAhead(subject, rig, dt);
		}
		canStay = false;
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
			} else if (blocked && !(subject.atScreen && current.age < BLOCKED_REST)) {
				reason = "blocked";

				cut = true;
			} else if (finished) {
				reason = "finished";
			} else if (current.age >= current.duration) {
				reason = "time";
				canStay = true;
			} else if (atScreen && subject.feet.y - climbFrom > CLIMBED && current.age > config.minShotTime &&
					current.type != ShotType.DRONE && current.type != ShotType.CUSTOM && !current.forced) {
				reason = "climbing";
				boost = ShotType.DRONE;
				canStay = true;
				climbFrom = subject.feet.y;
			} else if (current.age > config.minShotTime && current.type != ShotType.CUSTOM &&
					!current.forced && !(isHome() && current.type == ShotType.POV)) {
				final Context now = activity(context);
				if (fit(current.type) <= 0) {
					reason = "unfit for " + context;
				} else if (now != Context.WALK && now != activity(shotContext)) {

					reason = "now " + context;
					canStay = true;
				}
			}
		}

		if (reason != null) {
			choose(subject, rig, cut, reason);
		}
		current.update(subject, config, dt);
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
			for (final ShotType type : ShotType.values()) {
				final ShotConfig shotConfig = config.shot(type);
				if (type == ShotType.CUSTOM || !shotConfig.enabled || (type == ShotType.POV && isHome()) ||
						(type == ShotType.DUEL && subject.targetCenter == null) ||
						(type == ShotType.HANDS && !subject.tracksHands)) {
					continue;
				}
				if (atScreen && config.calmShots && (type == ShotType.FRONT || type == ShotType.LOW)) {
					continue;
				}
				if (type == ShotType.DRONE && !atScreen) {
					continue;
				}
				final double weight = shotConfig.weight * fit(type) * (type == boost ? BOOST : 1.0) * terrainFit(type);
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

		Shot next = selection.best;
		if (next == null && canStay && current != null) {
			reason += ", nowhere better";
			next = current;
		} else if (next == null) {
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
	/**
	 * Looks where the player is going. A wall that will be between them and the camera in a moment is no reason
	 * to wait until it is: if the other side of them is free, now and then, the camera swings over to there in
	 * one move
	 */
	private void lookAhead(Subject subject, Rig rig, double dt) {
		sinceSwing += dt;
		aheadTimer -= dt;
		if (aheadTimer > 0) {
			return;
		}
		aheadTimer = AHEAD_INTERVAL;
		final Vec3 travel = new Vec3(subject.velocity.x, 0, subject.velocity.z);
		if (!subject.atScreen || subject.seenThrough || sinceSwing < SWING_REST || travel.length() < AHEAD_SPEED ||
				!current.blends() || current.exactAim() || current.forced || current.type.orbits() ||
				current.type == ShotType.DRONE || current.type == ShotType.CUSTOM) {
			return;
		}
		final Vec3 step = travel.scale(AHEAD_SECONDS);
		final double needed = config.occlusionRatio + ROOM_TO_SPARE;
		if (freeAhead(subject, current, step) >= needed) {
			return;
		}
		final Shot other = new Shot(current.type, current.config, -current.side);
		other.distanceScale = current.distanceScale;
		other.start(subject, config);
		if (freeAhead(subject, other, Vec3.ZERO) < needed || freeAhead(subject, other, step) < needed) {
			return;
		}
		other.age = current.age;
		other.duration = current.duration;
		rig.blend();
		current = other;
		sinceSwing = 0;
		occludedTime = -OCCLUSION_GRACE;
		lastReason = "wall ahead, other side";
	}

	/**
	 * @return how much of the way from the player to the camera of a shot is free, once both are further along
	 */
	private double freeAhead(Subject subject, Shot shot, Vec3 step) {
		return WorldProbe.armFraction(subject, subject.center.add(step), shot.desiredPosition(subject).add(step),
				config);
	}

	/**
	 * @return how much more or less a shot is worth where the ground is steep or grown over: a camera near the
	 * ground is in a slope or behind a tree there, one high above is not
	 */
	/**
	 * @return how far out a shot is, of what its settings say. A drone is not as far every time
	 */
	private double reach(ShotType type, double scale) {
		return type != ShotType.DRONE ? scale :
				scale * (DRONE_NEAREST + (DRONE_FURTHEST - DRONE_NEAREST) * random.nextDouble());
	}

	private double terrainFit(ShotType type) {
		if (!rough) {
			return 1.0;
		}
		return switch (type) {
			case DRONE -> ROUGH_DRONE;
			case CRANE -> ROUGH_CRANE;
			case FLYBY -> ROUGH_FLYBY;
			case LOW -> 0.0;
			default -> 1.0;
		};
	}

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
			shot.distanceScale = reach(forceType, distanceScale);
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
		climbFrom += CLIMB_FORGET * dt;
		if (!(climbFrom < subject.feet.y) || player.isFallFlying() || player.isPassenger() || player.isInWater() ||
				player.getAbilities().flying) {
			climbFrom = subject.feet.y;
		}
		terrainTimer -= dt;
		if (terrainTimer <= 0) {
			terrainTimer = TERRAIN_INTERVAL;
			final Holder<Biome> biome = player.level().getBiome(player.blockPosition());
			rough = ROUGH_BIOMES.stream().anyMatch(biome::is);
		}

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
			final ShotConfig shotConfig = config.shot(type);
			consider(new Shot(type, shotConfig, -1), weight, distanceScale);
			consider(new Shot(type, shotConfig, 1), weight, distanceScale);
		}

		/**
		 * Weighs a shot against the best one so far. One the camera would be cut away from for being blocked is
		 * left out here already, and so is one that is close to that: a shot is picked once, not tried and dropped
		 */
		void consider(Shot shot, double weight, double distanceScale) {
			shot.distanceScale = reach(shot.type, distanceScale);
			shot.start(subject, config);

			final Vec3 center = subject.center;
			final Vec3 wanted = shot.desiredPosition(subject);
			final double free = WorldProbe.armFraction(subject, center, wanted, config);

			if (shot.isWorld() && free < 0.9) {
				return;
			}
			if (forceType == null && !subject.seenThrough && free < config.occlusionRatio + ROOM_TO_SPARE) {
				return;
			}
			final Vec3 actual = center.lerp(wanted,
					subject.seenThrough ? Rig.outOfSolid(subject, wanted, free) : free);
			if (actual.distanceTo(center) < shot.type.minDistance * subject.unit) {
				return;
			}

			double score = weight * (0.35 + 0.65 * free) * (0.8 + 0.4 * random.nextDouble());
			if (!WorldProbe.visible(subject, actual, subject.head)) {
				score *= HIDDEN_HEAD_PENALTY;
			}

			if (WorldProbe.inFluid(subject, actual) != headInFluid) {
				score *= ACROSS_WATER_PENALTY;
			}
			if (shot.type == lastType) {
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

}
