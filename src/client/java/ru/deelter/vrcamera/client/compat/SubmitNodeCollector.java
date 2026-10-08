package ru.deelter.vrcamera.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import java.util.function.BiConsumer;

/**
 * What the mod draws its own things into. Newer versions of the game collect what is to be drawn and draw it later,
 * under this name; this version draws right away, into the buffers of the game. The code that draws is the same.
 */
public record SubmitNodeCollector(MultiBufferSource buffers) {


	public void submitCustomGeometry(
			PoseStack poseStack, RenderType type, BiConsumer<PoseStack.Pose, VertexConsumer> geometry) {
		geometry.accept(poseStack.last(), this.buffers.getBuffer(type));
	}
}
