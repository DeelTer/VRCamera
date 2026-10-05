package ru.deelter.vrcamera.client.config;

import com.google.gson.annotations.SerializedName;

/**
 * how a photo is taken with a held camera
 */
public enum PhotoGesture {
	/**
	 * the hand that holds the camera holds its other button: the trigger while gripping, or the other way around
	 */
	@SerializedName("same_hand")
	SAME_HAND,
	/**
	 * the other hand comes to the camera and holds its interact button
	 */
	@SerializedName("other_hand")
	OTHER_HAND,
	/**
	 * only by key or command
	 */
	@SerializedName("off")
	OFF
}
