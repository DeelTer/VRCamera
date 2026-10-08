package ru.deelter.vrcamera.sync.plugin.api;

import java.util.Objects;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/**
 * A place for a free camera of the mod, as a server gives it to a player: where it stands, where it looks and how
 * far it is zoomed in. Made with {@link #builder(String)}.
 * <p>
 * The camera becomes the player's own. They can move it, film with it and throw it away, and one they threw away
 * does not come back unless it is {@linkplain Builder#replace(boolean) put there anyway}.
 */
public final class CameraView {
    private final String id;
    private final Location location;
    private final float fov;
    private final boolean replace;

    /**
     * @param id what the server calls this camera, like {@code arena:north}. A player has one camera per id: the
     *           same view sent again changes nothing, and cameras are taken back by it. At most 64 characters
     */
    public static @NotNull Builder builder(@NotNull String id) {
        return new Builder(id);
    }

    public @NotNull String id() {
        return id;
    }

    /**
     * @return where it stands, with the way it looks as yaw and pitch
     */
    public @NotNull Location location() {
        return location.clone();
    }

    public float fov() {
        return fov;
    }

    public boolean replace() {
        return replace;
    }

    private CameraView(String id, Location location, float fov, boolean replace) {
        this.id = id;
        this.location = location;
        this.fov = fov;
        this.replace = replace;
    }

    public static final class Builder {
        private final String id;
        private Location location;
        private Location lookAt;
        private Float yaw;
        private Float pitch;
        private float fov = 70.0F;
        private boolean replace;

        /**
         * @param location where the camera stands. It looks the way the location does, unless told otherwise
         */
        public @NotNull Builder location(@NotNull Location location) {
            this.location = location.clone();
            return this;
        }

        /**
         * @param yaw degrees the way the game counts them: 0 is south, 90 is west
         */
        public @NotNull Builder yaw(float yaw) {
            this.yaw = yaw;
            return this;
        }

        /**
         * @param pitch degrees down from level, negative to look up
         */
        public @NotNull Builder pitch(float pitch) {
            this.pitch = pitch;
            return this;
        }

        /**
         * turns the camera to a place, instead of giving yaw and pitch
         */
        public @NotNull Builder lookAt(@NotNull Location target) {
            lookAt = target.clone();
            return this;
        }

        /**
         * @param fov field of view in degrees, from 10 to 120. Small is zoomed in
         */
        public @NotNull Builder fov(float fov) {
            this.fov = Math.clamp(fov, 10.0F, 120.0F);
            return this;
        }

        /**
         * @param replace true to put the camera there also if the player has moved or thrown away the one with
         *                this id: for a map that changed
         */
        public @NotNull Builder replace(boolean replace) {
            this.replace = replace;
            return this;
        }

        public @NotNull CameraView build() {
            final Location at = Objects.requireNonNull(location, "a camera needs a location").clone();
            if (lookAt != null) {
                final double dx = lookAt.getX() - at.getX();
                final double dy = lookAt.getY() - at.getY();
                final double dz = lookAt.getZ() - at.getZ();
                at.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                at.setPitch((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
            }
            if (yaw != null) {
                at.setYaw(yaw);
            }
            if (pitch != null) {
                at.setPitch(pitch);
            }
            return new CameraView(id, at, fov, replace);
        }

        private Builder(String id) {
            if (id.isBlank() || id.length() > 64) {
                throw new IllegalArgumentException("a camera id has 1 to 64 characters");
            }
            this.id = id;
        }
    }
}
