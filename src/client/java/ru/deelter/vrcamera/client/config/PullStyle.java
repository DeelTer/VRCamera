package ru.deelter.vrcamera.client.config;

import com.google.gson.annotations.SerializedName;

/**
 * how a camera that is pulled from afar gets into the hand
 */
public enum PullStyle {
	/**
	 * it comes for as long as the button is held, and stays where it got to when the button is let go of
	 */
	@SerializedName("telekinesis")
	TELEKINESIS,
	/**
	 * the button is held for the whole time first, then it comes at once
	 */
	@SerializedName("instant")
	INSTANT
}
