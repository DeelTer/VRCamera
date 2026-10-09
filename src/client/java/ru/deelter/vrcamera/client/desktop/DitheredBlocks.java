package ru.deelter.vrcamera.client.desktop;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.state.BlockState;
import ru.deelter.vrcamera.Vrcamera;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The Block Dithering mod of ZipeStudio, where it is installed. It draws blocks like leaves and glass see-through
 * while they are near the camera of the game. For the camera of this mod "near" is all the way to the player: what
 * is between the two is drawn see-through, and the camera has no need to keep clear of it.
 * <p>
 * Nothing of that mod is in here. It is asked which blocks it draws that way, by the names its classes have, and
 * {@link ru.deelter.vrcamera.mixin.client.BlockDitheringConfigMixin} tells it how far to do so.
 */
public final class DitheredBlocks {
	public static final boolean INSTALLED = FabricLoader.getInstance().isModLoaded("block_dithering");
	/**
	 * blocks in front of the player the blocks are drawn as always again, and the blocks over which they go from
	 * one to the other
	 */
	private static final double BEFORE_PLAYER = 0.75;
	private static final double FADE = 2.0;

	private static MethodHandle isTarget;
	private static MethodHandle config;
	private static MethodHandle enabled;
	private static boolean broken;

	private DitheredBlocks() {
	}

	/**
	 * @return if the mod is there, turned on, and can be asked
	 */
	public static boolean active() {
		if (!INSTALLED || broken) {
			return false;
		}
		try {
			if (config == null) {
				final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
				final Class<?> settings = Class.forName("me.zipestudio.blockdithering.config.LeafyConfig");
				isTarget = lookup.findStatic(Class.forName("me.zipestudio.blockdithering.dithering.DitherBlocks"),
						"isTarget", MethodType.methodType(boolean.class, BlockState.class));
				enabled = lookup.findVirtual(settings, "isModEnabled", MethodType.methodType(boolean.class));
				config = lookup.findStatic(settings, "getInstance", MethodType.methodType(settings));
			}
			return (boolean) enabled.invoke(config.invoke());
		} catch (Throwable e) {
			failed(e);
			return false;
		}
	}

	/**
	 * @return if the mod draws that block see-through
	 */
	public static boolean dithers(BlockState state) {
		if (isTarget == null || broken) {
			return false;
		}
		try {
			return (boolean) isTarget.invoke(state);
		} catch (Throwable e) {
			failed(e);
			return false;
		}
	}

	/**
	 * @param usual how far from the camera the mod draws blocks see-through by its own settings
	 * @return how far it does for the picture that is drawn right now
	 */
	public static double far(double usual) {
		final double reach = DesktopCamera.INSTANCE.ditherReach() - BEFORE_PLAYER;
		return Math.max(usual, reach);
	}

	public static double near(double usual) {
		final double reach = DesktopCamera.INSTANCE.ditherReach() - BEFORE_PLAYER - FADE;
		return Math.max(usual, reach);
	}

	private static void failed(Throwable cause) {
		broken = true;
		Vrcamera.LOGGER.warn("VRCamera: can't work with this version of Block Dithering, the camera keeps clear of "
				+ "blocks as it does without it", cause);
	}
}
