package ru.deelter.vrcamera.client.config;

import com.google.gson.annotations.SerializedName;

import ru.deelter.vrcamera.client.shot.ShotType;

/**
 * How fast the director works: a set of values for how long shots last and how readily the camera moves. Picking
 * one writes these values into the config, where each of them can still be changed on its own.
 */
public enum Pace {
	/**
	 * as the mod first came: short shots, many changes
	 */
	@SerializedName("alpha")
	ALPHA(2.5, 14, 20, 0.9, 12, 0.5, new double[][]{
			{6, 11}, {5, 9}, {8, 14}, {4, 8}, {6, 10}, {4, 7}, {4, 7}, {4, 8}, {5, 10}, {6, 10}}),
	/**
	 * long steady shots: easy to follow, and long pieces to cut a video from
	 */
	@SerializedName("default")
	DEFAULT(4.0, 10, 30, 1.1, 16, 0.6, new double[][]{
			{9, 16}, {7, 12}, {10, 18}, {5, 9}, {8, 13}, {5, 8}, {5, 9}, {6, 11}, {6, 12}, {8, 14}}),
	/**
	 * in between: more changes for short videos, less to cut from
	 */
	@SerializedName("faster")
	FASTER(3.0, 12, 25, 1.0, 14, 0.6, new double[][]{
			{7, 13}, {6, 10}, {9, 16}, {4, 8}, {7, 11}, {4, 7}, {4, 8}, {5, 9}, {5, 11}, {7, 12}});

	private static final ShotType[] SHOTS = {ShotType.SHOULDER, ShotType.FRONT, ShotType.ORBIT, ShotType.FLYBY,
			ShotType.CRANE, ShotType.LOW, ShotType.HANDS, ShotType.DUEL, ShotType.POV, ShotType.MENU};

	private final double minShotTime;
	private final double orbitSpeed;
	private final double manualHoldSeconds;
	private final double turnLag;
	private final double turnDeadzone;
	private final double handStabilize;
	private final double[][] durations;

	Pace(
			double minShotTime, double orbitSpeed, double manualHoldSeconds, double turnLag, double turnDeadzone,
			double handStabilize, double[][] durations) {
		this.minShotTime = minShotTime;
		this.orbitSpeed = orbitSpeed;
		this.manualHoldSeconds = manualHoldSeconds;
		this.turnLag = turnLag;
		this.turnDeadzone = turnDeadzone;
		this.handStabilize = handStabilize;
		this.durations = durations;
	}

	public void apply(CameraConfig config) {
		config.pace = this;
		config.minShotTime = minShotTime;
		config.orbitSpeed = orbitSpeed;
		config.manualHoldSeconds = manualHoldSeconds;
		config.turnLag = turnLag;
		config.turnDeadzone = turnDeadzone;
		config.handStabilize = handStabilize;
		for (int i = 0; i < SHOTS.length; i++) {
			final ShotConfig shot = config.shot(SHOTS[i]);
			shot.minDuration = durations[i][0];
			shot.maxDuration = durations[i][1];
		}
	}
}
