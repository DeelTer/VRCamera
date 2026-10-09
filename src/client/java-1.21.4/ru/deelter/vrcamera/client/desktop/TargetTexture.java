package ru.deelter.vrcamera.client.desktop;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.texture.AbstractTexture;

/**
 * A picture the game drew into, as a texture to draw onto something in the world. The picture stays the one of
 * whoever made it: giving this up does not delete it.
 */
final class TargetTexture extends AbstractTexture {

	void show(RenderTarget picture) {
		this.id = picture.getColorTextureId();
	}

	@Override
	public void releaseId() {
		this.id = NOT_ASSIGNED;
	}

	@Override
	public void close() {
		this.id = NOT_ASSIGNED;
	}
}
