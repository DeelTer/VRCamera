package ru.deelter.vrcamera.client.config;

import com.google.gson.annotations.SerializedName;

/**
 * how the camera gets from one shot to the next
 */
public enum Transition {
	/** decided per change of shot */
	@SerializedName("auto")
	AUTO,
	/** always jump */
	@SerializedName("cut")
	CUT,
	/** always swing around the player */
	@SerializedName("blend")
	BLEND
}
