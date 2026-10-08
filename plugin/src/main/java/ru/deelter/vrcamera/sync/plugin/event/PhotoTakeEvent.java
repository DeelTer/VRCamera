package ru.deelter.vrcamera.sync.plugin.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A player took a photo with the camera of the mod. The photo itself stays on their computer: the server only hears
 * the shutter, to let the players around hear it. Not more often than the shutter is passed on, and only from
 * players whose mod talks to the plugin.
 */
public final class PhotoTakeEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Location camera;

    public PhotoTakeEvent(@NotNull Player player, @NotNull Location camera) {
        super(player);
        this.camera = camera;
    }

    /**
     * @return where the camera was, as the mod of the player says. Not checked beyond being near the player
     */
    public @NotNull Location getCamera() {
        return camera.clone();
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
