package ru.deelter.vrcamera.sync.plugin.api;

import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What other plugins can do with the free cameras of players who have the mod. They are cameras of a player at a
 * screen: a player in VR has none, and is told nothing.
 * <p>
 * Everything here is a wish. The mod of the player decides: they can turn cameras from servers off, and a server
 * can give them only a handful.
 */
public interface CameraApi {

    /**
     * @return the API, null while the plugin is not enabled
     */
    static @Nullable CameraApi get() {
        return Bukkit.getServicesManager().load(CameraApi.class);
    }

    /**
     * @return if the player has the mod and it talks to this plugin
     */
    boolean hasMod(@NotNull Player player);

    /**
     * Gives the player a camera. It is theirs from then on, kept with the world on their computer.
     *
     * @param show true to also have it film, if the player is in the free mode right now
     * @return false if the player has no mod, or is in another world than the camera
     */
    boolean placeCamera(@NotNull Player player, @NotNull CameraView view, boolean show);

    default boolean placeCamera(@NotNull Player player, @NotNull CameraView view) {
        return placeCamera(player, view, false);
    }

    /**
     * takes the camera with that id back, wherever the player has moved it
     */
    boolean removeCamera(@NotNull Player player, @NotNull String id);

    /**
     * takes back every camera whose id starts with that, like {@code arena:}. All of them with an empty prefix.
     * Cameras the player made themselves are never touched
     */
    boolean removeCameras(@NotNull Player player, @NotNull String prefix);

    /**
     * cuts to the camera with that id, if the player has it and is in the free mode. They go on from there as
     * they like
     */
    boolean showCamera(@NotNull Player player, @NotNull String id);

    /**
     * Lends the picture to the camera with that id for a while: whatever the camera of the player was doing, it
     * shows that camera, and then goes back to what it did. A player who changes something themselves in the
     * meantime keeps what they changed to. Not for a player whose camera is off, and not for longer than ten
     * minutes.
     */
    boolean showCamera(@NotNull Player player, @NotNull String id, long duration, @NotNull TimeUnit unit);
}
