package ru.deelter.vrcamera.sync.plugin.event;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A player is about to pin a photo to a block, for everyone with the mod to see. Called after the limits of the
 * plugin let it through. Cancelled, the photo is not pinned and the player is told that the server does not allow it.
 */
public final class PhotoPinEvent extends PlayerEvent implements Cancellable {
	private static final HandlerList HANDLERS = new HandlerList();

	private final Location location;
	private final Block block;
	private final boolean custom;
	private boolean cancelled;

	public PhotoPinEvent(@NotNull Player player, @NotNull Location location, @NotNull Block block, boolean custom) {
		super(player);
		this.location = location;
		this.block = block;
		this.custom = custom;
	}

	/**
	 * @return where the middle of the sheet is
	 */
	public @NotNull Location getLocation() {
		return this.location.clone();
	}

	/**
	 * @return the block the sheet is pinned to
	 */
	public @NotNull Block getBlock() {
		return this.block;
	}

	/**
	 * @return if the player says it is a picture from somewhere else, and not a photo taken in the game
	 */
	public boolean isCustom() {
		return this.custom;
	}

	@Override
	public boolean isCancelled() {
		return this.cancelled;
	}

	@Override
	public void setCancelled(boolean cancelled) {
		this.cancelled = cancelled;
	}

	@Override
	public @NotNull HandlerList getHandlers() {
		return HANDLERS;
	}

	public static @NotNull HandlerList getHandlerList() {
		return HANDLERS;
	}
}
