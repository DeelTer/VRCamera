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
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;
import ru.deelter.vrcamera.client.math.PoseTrail;
import ru.deelter.vrcamera.sync.Protocol;

import java.util.*;

/**
 * The cameras of the other players around, as their servers pass them on: drawn where they are, with the name of
 * their owner. Whoever has the mod sees them, in VR or not.
 */
public final class RemoteCameras {
	public static final RemoteCameras INSTANCE = new RemoteCameras();

	private static final long GONE_NANOS = 1_500_000_000L;

	private static final long DELAY_NANOS = 200_000_000L;
	private static final long MIN_SPACING_NANOS = 50_000_000L;
	private static final long MAX_AHEAD_NANOS = 150_000_000L;

	private static final double JUMP = 6.0;

	private static final float MODEL_SCALE = 0.25F;
	private static final float MODEL_UP = 0.25F;
	private static final float MODEL_BACK = 0.28F;
	private static final int MAX_CAMERAS = 64;

	private static final Identifier CAMERA_MODEL = Identifier.fromNamespaceAndPath(Vrcamera.MOD_ID, "camera");
	private static final int LABEL_COLOR = 0xFFFFFFFF;

	private static final String CAMERA_ICON = "";
	private final Map<UUID, Camera> cameras = new HashMap<>();
	private final ItemStackRenderState model = new ItemStackRenderState();
	private boolean broken;

	private RemoteCameras() {
	}

	public void clear() {
		cameras.clear();
	}

	public void heard(Protocol.Camera heard) {
		final float length = (float) Math.sqrt(heard.qx() * heard.qx() + heard.qy() * heard.qy() +
				heard.qz() * heard.qz() + heard.qw() * heard.qw());
		if (!Double.isFinite(heard.x()) || !Double.isFinite(heard.y()) || !Double.isFinite(heard.z()) ||
				!Float.isFinite(length) || length < 1.0E-3F) {
			return;
		}
		Camera camera = cameras.get(heard.owner());
		if (camera == null) {
			if (cameras.size() >= MAX_CAMERAS) {
				return;
			}
			camera = new Camera();
			cameras.put(heard.owner(), camera);
		}
		camera.ownerName = heard.ownerName().length() > 32 ? heard.ownerName().substring(0, 32) : heard.ownerName();
		final Vec3 position = new Vec3(heard.x(), heard.y(), heard.z());
		final Quaternionf rotation = new Quaternionf(heard.qx() / length, heard.qy() / length, heard.qz() / length,
				heard.qw() / length);
		camera.heardNanos = System.nanoTime();
		final Vec3 last = camera.trail.last();
		if (camera.position == null || (last != null && last.distanceTo(position) > JUMP)) {
			camera.trail.clear();
			camera.position = position;
			camera.rotation.set(rotation);
		}
		camera.trail.add(position, rotation, camera.position, camera.rotation);
	}

	/**
	 * @param viewPosition where the pass looks from, the pose stack is relative to that
	 */
	public void render(SubmitNodeCollector output, Vec3 viewPosition, PoseStack poseStack) {
		final ClientLevel level = Minecraft.getInstance().level;
		if (broken || cameras.isEmpty() || level == null) {
			return;
		}
		try {
			update();
			final Minecraft mc = Minecraft.getInstance();
			for (final Camera camera : shown()) {
				submitModel(mc, level, output, viewPosition, poseStack, camera.position, camera.rotation);
			}
		} catch (RuntimeException e) {
			broken = true;
			Vrcamera.LOGGER.error("VRCamera: drawing other players' cameras failed, " +
					"they are off until the game restarts", e);
		}
	}

	/**
	 * draws the model of a camera that is not one of another player: the own one of a player without VR
	 */
	public void drawModel(
			SubmitNodeCollector output, Vec3 viewPosition, PoseStack poseStack, Vec3 position, Quaternionf rotation) {
		final Minecraft mc = Minecraft.getInstance();
		if (broken || mc.level == null) {
			return;
		}
		try {
			submitModel(mc, mc.level, output, viewPosition, poseStack, position, rotation);
		} catch (RuntimeException e) {
			broken = true;
			Vrcamera.LOGGER.error("VRCamera: drawing the camera failed, it is off until the game restarts", e);
		}
	}

	/**
	 * the names over the cameras. Called while the game collects gizmos for a pass
	 */
	public void drawLabels() {
		if (broken || cameras.isEmpty()) {
			return;
		}
		try {
			for (final Camera camera : shown()) {
				Gizmos.billboardText(CAMERA_ICON + " " + camera.ownerName, camera.position.add(0, 0.28, 0),
						TextGizmo.Style.forColorAndCentered(LABEL_COLOR).withScale(0.12F));
			}
		} catch (IllegalStateException e) {
		}
	}

	/**
	 * puts every camera where it was a moment ago
	 */
	private void update() {
		final long now = System.nanoTime();
		cameras.values().removeIf(camera -> now - camera.heardNanos > GONE_NANOS);
		for (final Camera camera : cameras.values()) {
			final Vec3 shown = camera.trail.follow(camera.rotation);
			if (shown != null) {
				camera.position = shown;
			}
		}
	}

	/**
	 * @return the cameras that are shown: the nearest ones, as many as the settings say. Where many players film,
	 * the rest would only be in the way
	 */
	private List<Camera> shown() {
		final Minecraft mc = Minecraft.getInstance();
		final int most = (int) Math.round(CameraConfig.current().othersCameras);

		if (most <= 0 || mc.player == null || DesktopCamera.INSTANCE.filmsNow()) {
			return List.of();
		}
		final Vec3 eyes = mc.player.getEyePosition();
		return cameras.values().stream().filter(camera -> camera.position != null)
				.sorted(Comparator.comparingDouble(camera -> camera.position.distanceToSqr(eyes))).limit(most).toList();
	}

	private void submitModel(
			Minecraft mc, ClientLevel level, SubmitNodeCollector output, Vec3 viewPosition, PoseStack poseStack,
			Vec3 position, Quaternionf rotation) {
		model.clear();
		mc.getModelManager().getItemModel(CAMERA_MODEL).update(model, ItemStack.EMPTY,
				mc.getItemModelResolver(), ItemDisplayContext.GROUND, null, null, 0);
		if (model.isEmpty()) {
			return;
		}
		final BlockPos block = BlockPos.containing(position);
		final int light = LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, block),
				level.getBrightness(LightLayer.SKY, block));
		poseStack.pushPose();
		poseStack.translate(position.x - viewPosition.x, position.y - viewPosition.y, position.z - viewPosition.z);
		poseStack.mulPose(new Matrix4f().rotation(rotation));
		poseStack.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);
		poseStack.translate(0.0F, MODEL_UP, MODEL_BACK);
		model.submit(poseStack, output, light, OverlayTexture.NO_OVERLAY, 0);
		poseStack.popPose();
	}

	private static final class Camera {
		private final PoseTrail trail = new PoseTrail(DELAY_NANOS, MIN_SPACING_NANOS, MAX_AHEAD_NANOS, 0, 0);
		private final Quaternionf rotation = new Quaternionf();
		private String ownerName;
		private Vec3 position;
		private long heardNanos;
	}
}
