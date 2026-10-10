package ru.deelter.vrcamera.sync.plugin.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A player went over to another of their free cameras: that one films now. Also called for the first one when they
 * turn the free mode on. Only from players whose mod talks to the plugin, and not more often than a few times per
 * second.
 */
public final class CameraSwitchEvent extends PlayerEvent {
	private static final HandlerList HANDLERS = new HandlerList();

	private final String name;
	private final String id;
	private final Location camera;

	public CameraSwitchEvent(
			@NotNull Player player, @NotNull String name, @Nullable String id, @NotNull Location camera) {
		super(player);
		this.name = name;
		this.id = id;
		this.camera = camera;
	}

	public static @NotNull HandlerList getHandlerList() {
		return HANDLERS;
	}

	/**
	 * @return the letter the player knows the camera by
	 */
	public @NotNull String getCameraName() {
		return name;
	}

	/**
	 * @return the id a plugin gave the camera, null for one the player made themselves
	 */
	public @Nullable String getCameraId() {
		return id;
	}

	/**
	 * @return where the camera is, as the mod of the player says. Not checked
	 */
	public @NotNull Location getCamera() {
		return camera.clone();
	}

	@Override
	public @NotNull HandlerList getHandlers() {
		return HANDLERS;
	}
}
