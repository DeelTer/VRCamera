package ru.deelter.vrcamera.client.photo;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.VRData;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.CameraEffects;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Takes photos with the camera and keeps the sheets they are printed on.
 * <p>
 * A sheet that is left lying is forgotten when its chunk unloads or the world is left. Only what the player pinned
 * to a block is kept, in the cache of the world.
 */
public final class PhotoAlbum {
	public static final PhotoAlbum INSTANCE = new PhotoAlbum();

	// Pixels across a sheet. It is a hand wide in the world, more would not be seen, and every sheet stays in memory
	private static final int SHEET_PIXELS = 384;
	// sheets that lie around at once. Pinned ones are not counted, those are wanted
	private static final int MAX_LOOSE = 12;
	// a sheet in the dark still shows its picture
	private static final int MIN_LIGHT = 5;
	private static final double DRAW_DISTANCE = 64.0;
	private static final long DEVELOP_TIMEOUT_NANOS = 3_000_000_000L;

	private final List<PhotoSheet> sheets = new ArrayList<>();
	private int nextTexture;
	private final ArrayDeque<Integer> freeTextures = new ArrayDeque<>();
	// set when drawing failed once. A broken sheet must not take the whole frame of the headset with it every time
	private boolean broken;
	// a photo was taken and its picture has not arrived yet
	private boolean developing;
	private long developingSince;

	private Level level;
	private Path cache;
	private String dimension;
	// counts the worlds and dimensions entered, what was read from disk for an earlier one is thrown away
	private int session;
	// pinned sheets of the other dimensions of this world, kept to be written back
	private List<PhotoStore.Pinned> elsewhere = new ArrayList<>();
	private boolean loaded;
	private boolean unsaved;

	private PhotoAlbum() {
	}

	/**
	 * @return if a photo is on its way out of the camera, the next one has to wait for it
	 */
	public boolean isPrinting() {
		// with a limit, a picture that never arrives must not keep the camera from ever taking another
		if (this.developing && System.nanoTime() - this.developingSince < DEVELOP_TIMEOUT_NANOS) {
			return true;
		}
		for (PhotoSheet sheet : this.sheets) {
			if (sheet.isPrinting()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Takes what the camera films right now. Runs on the render thread.
	 *
	 * @param printSheet if a sheet with the photo comes out of the camera
	 * @return false if there is no camera picture to take
	 */
	public boolean take(boolean printSheet) {
		ClientDataHolderVR dh = ClientDataHolderVR.getInstance();
		RenderTarget picture = dh.vrRenderer == null ? null : dh.vrRenderer.cameraFramebuffer;
		if (picture == null) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		this.developing = true;
		this.developingSince = System.nanoTime();
		Screenshot.takeScreenshot(picture, image -> mc.execute(() -> develop(image, printSheet)));
		return true;
	}

	private void develop(NativeImage image, boolean printSheet) {
		this.developing = false;
		Path file;
		try {
			file = PhotoStore.newPhoto();
		} catch (IOException | RuntimeException e) {
			Vrcamera.LOGGER.error("VRCamera: could not take the photo", e);
			image.close();
			return;
		}
		if (printSheet && this.level != null && this.level == Minecraft.getInstance().level) {
			try {
				print(image, file.getFileName().toString());
			} catch (RuntimeException e) {
				// the photo itself is still worth saving
				Vrcamera.LOGGER.error("VRCamera: could not print the photo", e);
			}
		}
		// the full picture is big, writing it would be a hitch in the headset
		CompletableFuture.runAsync(() -> {
			try (image) {
				image.writeToFile(file);
			} catch (IOException | RuntimeException e) {
				Vrcamera.LOGGER.error("VRCamera: could not save the photo {}", file, e);
			}
		});
	}

	private void print(NativeImage image, String name) {
		int width = Math.min(SHEET_PIXELS, image.getWidth());
		int height = Math.max(1, Math.round(width * image.getHeight() / (float) image.getWidth()));
		NativeImage small = new NativeImage(width, height, false);
		try {
			image.resizeSubRectTo(0, 0, image.getWidth(), image.getHeight(), small);
			// what was filmed through glass or water is not see-through on paper
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					small.setPixel(x, y, small.getPixel(x, y) | 0xFF000000);
				}
			}
		} catch (RuntimeException e) {
			small.close();
			throw e;
		}
		String file = name;
		try {
			PhotoStore.prepare(this.cache);
			small.writeToFile(this.cache.resolve(name));
		} catch (IOException e) {
			// The sheet is shown anyway. Without its picture on disk it can't be kept over a restart though
			Vrcamera.LOGGER.warn("VRCamera: could not cache the sheet {}", name, e);
			file = null;
		}

		int loose = 0;
		for (int i = this.sheets.size() - 1; i >= 0; i--) {
			if (this.sheets.get(i).isLoose() && ++loose >= MAX_LOOSE) {
				remove(i, true);
			}
		}
		add(small, height / (float) width, file);
	}

	private PhotoSheet add(NativeImage picture, float aspect, String file) {
		// Names are used again. Render types are kept per texture name and never forgotten, a new name for
		// every photo would add up over a long session
		int slot = this.freeTextures.isEmpty() ? this.nextTexture++ : this.freeTextures.pop();
		Identifier texture = Identifier.fromNamespaceAndPath(Vrcamera.MOD_ID, "photo/" + slot);
		// the texture owns the picture from here on
		Minecraft.getInstance().getTextureManager().register(texture,
				new DynamicTexture(() -> "VRCamera photo", picture));
		PhotoSheet sheet = new PhotoSheet(texture, slot, aspect, file);
		this.sheets.add(sheet);
		return sheet;
	}

	/**
	 * @param forget if its picture in the cache goes as well. Not for sheets that are only unloaded
	 */
	private void remove(int index, boolean forget) {
		PhotoSheet sheet = this.sheets.remove(index);
		Minecraft.getInstance().getTextureManager().release(sheet.texture);
		this.freeTextures.push(sheet.textureSlot);
		if (forget && sheet.file != null && this.cache != null) {
			Path file = this.cache.resolve(sheet.file);
			CompletableFuture.runAsync(() -> PhotoStore.delete(file));
		}
	}

	/**
	 * the world is left
	 */
	public void clear() {
		leave();
		this.level = null;
		this.session++;
	}

	private void leave() {
		if (this.unsaved && this.loaded) {
			save();
		}
		for (int i = this.sheets.size() - 1; i >= 0; i--) {
			// what was not pinned was not wanted
			remove(i, !this.sheets.get(i).isPinned());
		}
		this.elsewhere = new ArrayList<>();
		this.loaded = false;
		this.unsaved = false;
	}

	private void enter(Level level) {
		leave();
		this.level = level;
		this.cache = PhotoStore.worldCache();
		this.dimension = level.dimension().toString();

		int session = ++this.session;
		Path cache = this.cache;
		String dimension = this.dimension;
		FileTime entered = FileTime.fromMillis(System.currentTimeMillis());
		CompletableFuture.runAsync(() -> {
			List<PhotoStore.Pinned> pinned = PhotoStore.loadPinned(cache);
			// a pinned sheet whose picture is gone is gone
			pinned.removeIf(sheet -> !Files.isRegularFile(cache.resolve(sheet.file)));
			Set<String> keep = new HashSet<>();
			pinned.forEach(sheet -> keep.add(sheet.file));
			PhotoStore.removeStrays(cache, keep, entered);

			Map<PhotoStore.Pinned, NativeImage> here = new LinkedHashMap<>();
			List<PhotoStore.Pinned> elsewhere = new ArrayList<>();
			for (PhotoStore.Pinned sheet : pinned) {
				if (!dimension.equals(sheet.dimension)) {
					elsewhere.add(sheet);
					continue;
				}
				try (InputStream in = Files.newInputStream(cache.resolve(sheet.file))) {
					here.put(sheet, NativeImage.read(in));
				} catch (IOException | RuntimeException e) {
					Vrcamera.LOGGER.warn("VRCamera: can't read the pinned photo {}", sheet.file, e);
				}
			}
			Minecraft.getInstance().execute(() -> restore(session, here, elsewhere));
		}).exceptionally(e -> {
			Vrcamera.LOGGER.error("VRCamera: loading the pinned photos failed", e);
			return null;
		});
	}

	private void restore(int session, Map<PhotoStore.Pinned, NativeImage> here, List<PhotoStore.Pinned> elsewhere) {
		if (session != this.session) {
			here.values().forEach(NativeImage::close);
			return;
		}
		this.elsewhere = elsewhere;
		here.forEach((pinned, picture) -> add(picture, pinned.aspect, pinned.file).restore(
				new Vec3(pinned.x, pinned.y, pinned.z),
				new Quaternionf(pinned.qx, pinned.qy, pinned.qz, pinned.qw)));
		this.loaded = true;
		if (this.unsaved) {
			save();
		}
	}

	/**
	 * Writes where the pinned sheets hang. Not before what was pinned earlier is read, or that would be lost.
	 */
	private void save() {
		this.unsaved = true;
		if (!this.loaded) {
			return;
		}
		this.unsaved = false;
		List<PhotoStore.Pinned> pinned = new ArrayList<>(this.elsewhere);
		for (PhotoSheet sheet : this.sheets) {
			if (!sheet.isPinned() || sheet.file == null) {
				continue;
			}
			PhotoStore.Pinned entry = new PhotoStore.Pinned();
			entry.file = sheet.file;
			entry.dimension = this.dimension;
			entry.x = sheet.position().x;
			entry.y = sheet.position().y;
			entry.z = sheet.position().z;
			entry.qx = sheet.rotation().x;
			entry.qy = sheet.rotation().y;
			entry.qz = sheet.rotation().z;
			entry.qw = sheet.rotation().w;
			entry.aspect = sheet.aspect;
			pinned.add(entry);
		}
		Path cache = this.cache;
		CompletableFuture.runAsync(() -> PhotoStore.savePinned(cache, pinned));
	}

	/**
	 * @return the sheet nearest to the hand that it can take, null if there is none in reach
	 */
	public PhotoSheet nearest(Vec3 hand, double reach) {
		PhotoSheet nearest = null;
		double nearestDistance = reach * reach;
		for (PhotoSheet sheet : this.sheets) {
			double distance = sheet.center().distanceToSqr(hand);
			if (sheet.canGrab() && distance < nearestDistance) {
				nearest = sheet;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	public void grab(PhotoSheet sheet, int hand, VRData vr) {
		boolean wasPinned = sheet.isPinned();
		VRData.VRDevicePose pose = vr.getController(hand);
		sheet.grab(hand, pose.getPosition(), pose.getMatrix().getNormalizedRotation(new Quaternionf()));
		if (wasPinned) {
			save();
		}
	}

	public boolean isHolding(int hand) {
		return held(hand) != null;
	}

	private PhotoSheet held(int hand) {
		for (PhotoSheet sheet : this.sheets) {
			if (sheet.hand() == hand) {
				return sheet;
			}
		}
		return null;
	}

	public void release(int hand) {
		PhotoSheet sheet = held(hand);
		if (sheet == null || this.level == null) {
			return;
		}
		sheet.release(this.level);
		if (sheet.isPinned()) {
			CameraEffects.pinned(this.level, sheet.center());
			save();
		}
	}

	/**
	 * the camera holds on to the sheet it is printing
	 */
	public void hangFrom(Vec3 camera, Quaternionfc cameraRotation, float worldScale) {
		for (PhotoSheet sheet : this.sheets) {
			sheet.hangFrom(camera, cameraRotation, worldScale);
		}
	}

	public void update(Level level, VRData vr, double dt) {
		if (level != this.level) {
			// another world, or another dimension with other things at the same coordinates
			enter(level);
		}
		for (int i = this.sheets.size() - 1; i >= 0; i--) {
			PhotoSheet sheet = this.sheets.get(i);
			if (sheet.hand() >= 0) {
				VRData.VRDevicePose pose = vr.getController(sheet.hand());
				sheet.carry(pose.getPosition(), pose.getMatrix().getNormalizedRotation(new Quaternionf()), dt);
			}
			sheet.update(level, dt);
			if (sheet.isPrinting()) {
				continue;
			}
			BlockPos block = BlockPos.containing(sheet.center());
			if (level.getFluidState(block).is(FluidTags.LAVA) || level.getBlockState(block).is(BlockTags.FIRE)) {
				boolean wasPinned = sheet.isPinned();
				CameraEffects.burned(level, sheet.center());
				remove(i, true);
				if (wasPinned) {
					save();
				}
			} else if (sheet.isGone() || (sheet.isLoose() && !level.isLoaded(block))) {
				// nobody picked it up, and now nobody is there to see it
				remove(i, true);
			}
		}
	}

	/**
	 * @param visitor gets the middle of every sheet that lies around or falls
	 */
	public void forEachLoose(Consumer<Vec3> visitor) {
		for (PhotoSheet sheet : this.sheets) {
			if (sheet.isLoose()) {
				visitor.accept(sheet.center());
			}
		}
	}

	/**
	 * Draws the sheets into the pass that is being rendered: both eyes and the camera.
	 *
	 * @param viewPosition where the pass looks from, the pose stack is relative to that
	 */
	public void render(SubmitNodeCollector output, Vec3 viewPosition, PoseStack poseStack) {
		Level level = Minecraft.getInstance().level;
		if (this.broken || this.sheets.isEmpty() || level == null || level != this.level) {
			return;
		}
		try {
			for (PhotoSheet sheet : this.sheets) {
				if (sheet.position().distanceToSqr(viewPosition) < DRAW_DISTANCE * DRAW_DISTANCE) {
					render(sheet, level, output, viewPosition, poseStack);
				}
			}
		} catch (RuntimeException e) {
			this.broken = true;
			Vrcamera.LOGGER.error("VRCamera: drawing photo sheets failed, they are off until the game restarts", e);
		}
	}

	private static void render(
			PhotoSheet sheet, Level level, SubmitNodeCollector output, Vec3 viewPosition, PoseStack poseStack) {
		float printed = sheet.printed();
		if (printed <= 0) {
			return;
		}
		BlockPos block = BlockPos.containing(sheet.center());
		int light = LightCoordsUtil.pack(Math.max(MIN_LIGHT, level.getBrightness(LightLayer.BLOCK, block)),
				level.getBrightness(LightLayer.SKY, block));
		float half = PhotoSheet.WIDTH / 2.0F;
		// Only what is out of the camera, the lower edge comes first and takes the picture with it
		float bottom = -sheet.height() * printed;
		float topV = 1.0F - printed;

		poseStack.pushPose();
		poseStack.translate(sheet.position().x - viewPosition.x, sheet.position().y - viewPosition.y,
				sheet.position().z - viewPosition.z);
		// as a matrix, a quaternion is not taken by every supported Minecraft version
		poseStack.mulPose(new Matrix4f().rotation(sheet.rotation()));
		output.submitCustomGeometry(poseStack, RenderTypes.entityCutout(sheet.texture), (pose, consumer) -> {
			vertex(consumer, pose, -half, bottom, 0, 1, light);
			vertex(consumer, pose, half, bottom, 1, 1, light);
			vertex(consumer, pose, half, 0, 1, topV, light);
			vertex(consumer, pose, -half, 0, 0, topV, light);
		});
		poseStack.popPose();
	}

	private static void vertex(
			VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float u, float v, int light) {
		consumer.addVertex(pose, x, y, 0)
				.setColor(1.0F, 1.0F, 1.0F, 1.0F)
				.setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light)
				.setNormal(pose, 0, 0, 1);
	}
}
