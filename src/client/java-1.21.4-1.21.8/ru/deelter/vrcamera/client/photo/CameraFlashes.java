package ru.deelter.vrcamera.client.photo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.compat.SubmitNodeCollector;

import java.util.ArrayList;
import java.util.List;

/**
 * The flash of a camera that takes a photo: a soft white glow at its lens for a moment, turned to whoever looks.
 * It lights nothing, the photo is as dark as it was.
 */
public final class CameraFlashes {
	public static final CameraFlashes INSTANCE = new CameraFlashes();

	private static final ResourceLocation GLOW = ResourceLocation.fromNamespaceAndPath(Vrcamera.MOD_ID, "textures/misc/flash.png");
	private static final long DURATION_NANOS = 160_000_000L;
	private static final float SIZE = 0.45F;
	private static final float BRIGHTEST = 0.85F;
	private static final int FULL_LIGHT = 0xF000F0;
	private static final int MAX_FLASHES = 16;
	private static final double DRAW_DISTANCE = 48.0;
	private static final double TOO_CLOSE = 0.3;
	private final List<Flash> flashes = new ArrayList<>();

	private CameraFlashes() {
	}

	private static void vertex(
			VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float u, float v, float alpha) {
		consumer.addVertex(pose, x, y, 0)
				.setColor(1.0F, 1.0F, 1.0F, alpha)
				.setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(FULL_LIGHT)
				.setNormal(0, 1, 0);
	}

	public void add(Vec3 position) {
		if (this.flashes.size() < MAX_FLASHES) {
			this.flashes.add(new Flash(position, System.nanoTime()));
		}
	}

	/**
	 * @param viewPosition where the pass looks from, the pose stack is relative to that
	 */
	public void render(SubmitNodeCollector output, Vec3 viewPosition, PoseStack poseStack) {
		if (this.flashes.isEmpty()) {
			return;
		}
		long now = System.nanoTime();
		this.flashes.removeIf(flash -> now - flash.nanos > DURATION_NANOS);
		for (Flash flash : this.flashes) {
			Vec3 toView = viewPosition.subtract(flash.position);
			double distance = toView.length();
			if (distance < TOO_CLOSE || distance > DRAW_DISTANCE) {
				continue;
			}
			float left = 1.0F - (now - flash.nanos) / (float) DURATION_NANOS;
			float alpha = BRIGHTEST * left * left;
			float half = SIZE / 2.0F;
			Vector3f facing = new Vector3f((float) (toView.x / distance), (float) (toView.y / distance),
					(float) (toView.z / distance));
			poseStack.pushPose();
			poseStack.translate(flash.position.x - viewPosition.x, flash.position.y - viewPosition.y,
					flash.position.z - viewPosition.z);
			poseStack.mulPose(new Matrix4f().rotation(new Quaternionf().rotationTo(new Vector3f(0, 0, 1), facing)));
			output.submitCustomGeometry(poseStack, RenderType.entityTranslucentEmissive(GLOW), (pose, consumer) -> {
				vertex(consumer, pose, -half, -half, 0, 1, alpha);
				vertex(consumer, pose, half, -half, 1, 1, alpha);
				vertex(consumer, pose, half, half, 1, 0, alpha);
				vertex(consumer, pose, -half, half, 0, 0, alpha);
			});
			poseStack.popPose();
		}
	}

	private record Flash(Vec3 position, long nanos) {
	}
}
