package ru.deelter.vrcamera.mixin.client;

import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {

	@Accessor("fogRenderer")
	FogRenderer vrcamera$fogRenderer();

	@Accessor("guiRenderer")
	GuiRenderer vrcamera$guiRenderer();

	@Accessor("guiRenderState")
	GuiRenderState vrcamera$guiRenderState();
}
