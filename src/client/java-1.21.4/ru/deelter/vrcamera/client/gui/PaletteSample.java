package ru.deelter.vrcamera.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.photo.Palettes;
import ru.deelter.vrcamera.client.photo.PixelArt;

import java.io.IOException;
import java.io.InputStream;

/**
 * The picture on the screen of the palettes: a photo that comes with the mod, made the way a sheet is printed, in
 * the colours of the palette the player looks at there. To see what a palette does before a photo is taken.
 */
final class PaletteSample {
	static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(Vrcamera.MOD_ID, "palette_sample");
	/**
	 * height of the picture for a width of 1
	 */
	static final float ASPECT = 274.0F / 512.0F;

	private static final ResourceLocation SOURCE = ResourceLocation.fromNamespaceAndPath(Vrcamera.MOD_ID,
			"textures/gui/palette_sample.png");
	private static final int SHEET_PIXELS = 384;

	private static NativeImage source;

	private PaletteSample() {
	}

	/**
	 * @param palette the name of the palette to show it in, {@link Palettes#MAP_COLORS} for none
	 * @return false if there is no picture to show
	 */
	static boolean show(Minecraft mc, String palette) {
		NativeImage sheet = null;
		try {
			if (source == null) {
				try (final InputStream in = mc.getResourceManager().open(SOURCE)) {
					source = NativeImage.read(in);
				}
			}
			final int width = Math.min(SHEET_PIXELS, source.getWidth());
			sheet = new NativeImage(width, Math.max(1, Math.round(width * ASPECT)), false);
			source.resizeSubRectTo(0, 0, source.getWidth(), source.getHeight(), sheet);
			final NativeImage shown = PixelArt.apply(sheet, CameraConfig.current().photoPixels, Palettes.colors(palette));
			sheet = null;
			mc.getTextureManager().register(TEXTURE, new DynamicTexture(shown));
			return true;
		} catch (IOException | RuntimeException e) {
			Vrcamera.LOGGER.warn("VRCamera: can't show the palette on a picture", e);
			if (sheet != null) {
				sheet.close();
			}
			return false;
		}
	}

	/**
	 * the screen is gone, and what the picture took with it
	 */
	static void close(Minecraft mc) {
		mc.getTextureManager().release(TEXTURE);
		if (source != null) {
			source.close();
			source = null;
		}
	}
}
