package ru.deelter.vrcamera.client.config;

/**
 * what the camera of a player at a screen does while they jump
 */
public enum JumpSteady {
	// goes up and down with every jump
	OFF,
	// goes along with a single jump, and holds still from the second one in a row
	SERIES,
	// holds still for every jump
	ALWAYS
}
