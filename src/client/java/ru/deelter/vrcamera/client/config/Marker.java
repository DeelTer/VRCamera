package ru.deelter.vrcamera.client.config;

import com.google.gson.annotations.SerializedName;

/**
 * what shows where the camera is, in the headset
 */
public enum Marker {
	@SerializedName("dot")
	DOT,
	/**
	 * the camera model of Vivecraft, with its screen
	 */
	@SerializedName("model")
	MODEL,
	@SerializedName("none")
	NONE
}
