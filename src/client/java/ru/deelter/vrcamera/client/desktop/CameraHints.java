package ru.deelter.vrcamera.client.desktop;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import ru.deelter.vrcamera.client.config.CameraConfig;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * A few words over the hotbar on what can be pressed right now, for a player who has a camera with a window of its
 * own. Each time something else can be done with the camera, and not over and over: they are read once.
 */
final class CameraHints {
	private static final long AGAIN_NANOS = 30_000_000_000L;
	private static final long QUIET_NANOS = 2_500_000_000L;
	private static final Map<Hint, Long> SHOWN = new EnumMap<>(Hint.class);
	private static Hint last;
	private static long spoke;

	private CameraHints() {
	}

	/**
	 * the camera told the player something, which a hint must not take away at once
	 */
	static void spoke() {
		spoke = System.nanoTime();
	}

	/**
	 * @param now what the player is doing with the camera, null for nothing a hint is worth
	 * @return the hint to show, null for none
	 */
	@Nullable
	static Component next(Hint now) {
		final Hint before = last;
		last = now;
		if (now == null || now == before || !CameraConfig.current().hints) {
			return null;
		}

		if (now.isIdle() && before != null && !before.isIdle()) {
			return null;
		}
		final long time = System.nanoTime();
		final Long shown = SHOWN.get(now);
		if (time - spoke < QUIET_NANOS || (shown != null && time - shown < AGAIN_NANOS)) {
			return null;
		}
		SHOWN.put(now, time);
		final Object[] keys = new Object[now.keys.length];
		for (int key = 0; key < keys.length; key++) {
			keys[key] = keyName(now.keys[key]);
		}
		return Component.translatable("vrcamera.hint." + now.name().toLowerCase(Locale.ROOT).replace('_', '.'), keys);
	}

	/**
	 * @return what the player has a key of the mod on
	 */
	static Component keyName(String name) {
		for (KeyMapping key : Minecraft.getInstance().options.keyMappings) {
			if (key.getName().equals("key.vrcamera." + name)) {
				return key.getTranslatedKeyMessage();
			}
		}
		return Component.literal("?");
	}

	/**
	 * what the player is doing with the camera, and the keys of the mod its hint names
	 */
	enum Hint {
		IDLE("steer"), IDLE_FREE("preset.new", "preset"), AIM, AIM_FREE("preset.remove"), AIM_OTHER, HOLD("turn"),
		HOLD_FREE("turn"), STEER, SHEET_AIM, SHEET_HOLD("turn");

		private final String[] keys;

		Hint(String... keys) {
			this.keys = keys;
		}

		private boolean isIdle() {
			return this == IDLE || this == IDLE_FREE;
		}
	}
}
