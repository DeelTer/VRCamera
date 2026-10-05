package ru.deelter.vrcamera.client.sync;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.vivecraft.client_vr.gameplay.trackers.CameraTracker;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.sync.Protocol;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The cameras of the other players around, as their servers pass them on: drawn where they are, with the name of
 * their owner. Whoever has the mod sees them, in VR or not.
 */
public final class RemoteCameras {
	public static final RemoteCameras INSTANCE = new RemoteCameras();

	// A camera not heard of for this long is off, or its owner walked away. Nothing else says so
	private static final long GONE_NANOS = 1_500_000_000L;
	// A camera is shown where it was this long ago, between two places it was heard to be. It is heard of ten
	// times per second and not evenly: chasing the last place moves in jerks, this moves along the path it took
	private static final long DELAY_NANOS = 200_000_000L;
	// messages that arrive in a bunch were not sent in one
	private static final long MIN_SPACING_NANOS = 50_000_000L;
	private static final long MAX_AHEAD_NANOS = 150_000_000L;
	private static final int MAX_SAMPLES = 8;
	// further than this it jumped: its owner teleported, or called it
	private static final double JUMP = 6.0;
	// the model of the Vivecraft camera, sized and set off the way Vivecraft does it
	private static final float MODEL_SCALE = 0.25F;
	private static final float MODEL_UP = 0.25F;
	private static final float MODEL_BACK = 0.28F;
	private static final int MAX_CAMERAS = 64;
	private static final int LABEL_COLOR = 0xFFFFFFFF;
	// the camera glyph of the mod, see assets/minecraft/font/default.json
	private static final String CAMERA_ICON = "";

	private record Sample(long nanos, Vec3 position, Quaternionf rotation) {}

	private static final class Camera {
		String ownerName;
		final ArrayDeque<Sample> samples = new ArrayDeque<>();
		Vec3 position;
		final Quaternionf rotation = new Quaternionf();
		long heardNanos;
	}

	private final Map<UUID, Camera> cameras = new HashMap<>();
	private final ItemStackRenderState model = new ItemStackRenderState();
	private boolean broken;

	private RemoteCameras() {
	}

	public void clear() {
		this.cameras.clear();
	}

	public void heard(Protocol.Camera heard) {
		float length = (float) Math.sqrt(heard.qx() * heard.qx() + heard.qy() * heard.qy() +
				heard.qz() * heard.qz() + heard.qw() * heard.qw());
		if (!Double.isFinite(heard.x()) || !Double.isFinite(heard.y()) || !Double.isFinite(heard.z()) ||
				!Float.isFinite(length) || length < 1.0E-3F)
		{
			return;
		}
		Camera camera = this.cameras.get(heard.owner());
		if (camera == null) {
			if (this.cameras.size() >= MAX_CAMERAS) {
				return;
			}
			camera = new Camera();
			this.cameras.put(heard.owner(), camera);
		}
		camera.ownerName = heard.ownerName().length() > 32 ? heard.ownerName().substring(0, 32) : heard.ownerName();
		Vec3 position = new Vec3(heard.x(), heard.y(), heard.z());
		Quaternionf rotation = new Quaternionf(heard.qx() / length, heard.qy() / length, heard.qz() / length,
				heard.qw() / length);
		long now = System.nanoTime();
		camera.heardNanos = now;
		Sample last = camera.samples.peekLast();
		if (camera.position == null || (last != null && last.position.distanceTo(position) > JUMP)) {
			camera.samples.clear();
			camera.position = position;
			camera.rotation.set(rotation);
			last = null;
		}
		long nanos = last == null ? now :
				Math.min(Math.max(now, last.nanos + MIN_SPACING_NANOS), now + MAX_AHEAD_NANOS);
		camera.samples.addLast(new Sample(nanos, position, rotation));
		if (camera.samples.size() > MAX_SAMPLES) {
			camera.samples.removeFirst();
		}
	}

	/**
	 * puts every camera where it was a moment ago
	 */
	private void update() {
		long now = System.nanoTime();
		this.cameras.values().removeIf(camera -> now - camera.heardNanos > GONE_NANOS);
		long shown = now - DELAY_NANOS;
		for (Camera camera : this.cameras.values()) {
			ArrayDeque<Sample> samples = camera.samples;
			while (samples.size() > 2 && second(samples).nanos <= shown) {
				samples.removeFirst();
			}
			Sample from = samples.peekFirst();
			if (from == null || shown <= from.nanos) {
				continue;
			}
			Sample to = samples.size() > 1 ? second(samples) : from;
			float along = to.nanos <= from.nanos ? 1.0F :
					(float) Math.min(1.0, (shown - from.nanos) / (double) (to.nanos - from.nanos));
			camera.position = from.position.lerp(to.position, along);
			from.rotation.slerp(to.rotation, along, camera.rotation);
		}
	}

	private static Sample second(ArrayDeque<Sample> samples) {
		Iterator<Sample> all = samples.iterator();
		all.next();
		return all.next();
	}

	/**
	 * @param viewPosition where the pass looks from, the pose stack is relative to that
	 */
	public void render(SubmitNodeCollector output, Vec3 viewPosition, PoseStack poseStack) {
		ClientLevel level = Minecraft.getInstance().level;
		if (this.broken || this.cameras.isEmpty() || level == null) {
			return;
		}
		try {
			update();
			Minecraft mc = Minecraft.getInstance();
			for (Camera camera : this.cameras.values()) {
				this.model.clear();
				mc.getModelManager().getItemModel(CameraTracker.CAMERA_MODEL).update(this.model, ItemStack.EMPTY,
						mc.getItemModelResolver(), ItemDisplayContext.GROUND, null, null, 0);
				if (this.model.isEmpty()) {
					continue;
				}
				BlockPos block = BlockPos.containing(camera.position);
				int light = LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, block),
						level.getBrightness(LightLayer.SKY, block));
				poseStack.pushPose();
				poseStack.translate(camera.position.x - viewPosition.x, camera.position.y - viewPosition.y,
						camera.position.z - viewPosition.z);
				poseStack.mulPose(new Matrix4f().rotation(camera.rotation));
				poseStack.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);
				poseStack.translate(0.0F, MODEL_UP, MODEL_BACK);
				this.model.submit(poseStack, output, light, OverlayTexture.NO_OVERLAY, 0);
				poseStack.popPose();
			}
		} catch (RuntimeException e) {
			this.broken = true;
			Vrcamera.LOGGER.error("VRCamera: drawing other players' cameras failed, they are off until the game restarts",
					e);
		}
	}

	/**
	 * the names over the cameras. Called while the game collects gizmos for a pass
	 */
	public void drawLabels() {
		if (this.broken || this.cameras.isEmpty()) {
			return;
		}
		try {
			for (Camera camera : this.cameras.values()) {
				if (camera.position != null) {
					Gizmos.billboardText(CAMERA_ICON + " " + camera.ownerName, camera.position.add(0, 0.28, 0),
							TextGizmo.Style.forColorAndCentered(LABEL_COLOR).withScale(0.12F));
				}
			}
		} catch (IllegalStateException e) {
			// no gizmo collection is running, nothing to draw into
		}
	}
}
