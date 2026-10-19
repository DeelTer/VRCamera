package ru.deelter.vrcamera.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderPass;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.deelter.vrcamera.client.desktop.HudWindow;

/**
 * What the HUD cuts out of the picture of the camera is worked out for the window it is laid out for, which is
 * smaller than the picture: it is put where it is in the picture, and never out of it
 */
@Mixin(GuiRenderer.class)
public class GuiRendererMixin {
	@WrapOperation(method = "enableScissor", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;enableScissor(IIII)V"), require = 0)
	private void vrcamera$cutInPicture(RenderPass pass, int x, int y, int width, int height, Operation<Void> original) {
		final float[] scissor = HudWindow.scissor();
		if (scissor == null) {
			original.call(pass, x, y, width, height);
			return;
		}
		final int left = Math.clamp((long) Math.floor(x * scissor[0]), 0, (int) scissor[2]);
		final int bottom = Math.clamp((long) Math.floor(y * scissor[1]), 0, (int) scissor[3]);
		final int right = Math.clamp((long) Math.ceil((x + width) * scissor[0]), left, (int) scissor[2]);
		final int top = Math.clamp((long) Math.ceil((y + height) * scissor[1]), bottom, (int) scissor[3]);
		original.call(pass, left, bottom, right - left, top - bottom);
	}
}
