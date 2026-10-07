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
		this.texture = picture.getColorTexture();
		this.textureView = picture.getColorTextureView();
		this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
	}

	@Override
	public void close() {
		// the picture is not this texture's to throw away
	}
}
