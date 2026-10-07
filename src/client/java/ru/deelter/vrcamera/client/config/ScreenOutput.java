package ru.deelter.vrcamera.client.config;

import com.google.gson.annotations.SerializedName;

/**
 * where the camera of a player without VR films to
 */
public enum ScreenOutput {
	/**
	 * into the game window, in place of the view of the player
	 */
	@SerializedName("screen")
	SCREEN,
	/**
	 * into a window of its own, the player keeps their view
	 */
	@SerializedName("window")
	WINDOW
}
