package ru.deelter.vrcamera.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {

	// the picture the game draws into, to have it draw into another one for the camera
	@Mutable
	@Accessor("mainRenderTarget")
	void vrcamera$setMainRenderTarget(RenderTarget target);
}
