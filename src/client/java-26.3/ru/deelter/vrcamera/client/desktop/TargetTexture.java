package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.renderer.texture.AbstractTexture;

/**
 * A picture the game drew, as a texture the game can be asked for by name: to draw it onto something in the world
 * the way any texture is drawn.
 */
final class TargetTexture extends AbstractTexture {
	/**
	 * the picture can be another one from frame to frame, when its size was changed
	 */
	void show(RenderTarget picture) {
		texture = picture.getColorTexture();
		textureView = picture.getColorTextureView();
		sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
	}

	@Override
	public void close() {
	}
}
