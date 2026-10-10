package ru.deelter.vrcamera.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Mutable
	@Accessor("mainRenderTarget")
	void vrcamera$setMainRenderTarget(RenderTarget target);
}
