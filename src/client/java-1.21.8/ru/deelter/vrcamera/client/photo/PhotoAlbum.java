package ru.deelter.vrcamera.client.photo;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import ru.deelter.vrcamera.Vrcamera;
import ru.deelter.vrcamera.client.CameraEffects;
import ru.deelter.vrcamera.client.Vive;
import ru.deelter.vrcamera.client.Vr;
import ru.deelter.vrcamera.client.compat.Gizmos;
import ru.deelter.vrcamera.client.compat.SubmitNodeCollector;
import ru.deelter.vrcamera.client.compat.TextGizmo;
import ru.deelter.vrcamera.client.config.CameraConfig;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;
import ru.deelter.vrcamera.client.desktop.DirectorPass;
import ru.deelter.vrcamera.client.sync.PhotoCodec;
import ru.deelter.vrcamera.client.sync.PhotoSync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.*;
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
	private static final double MAX_FRAME_TIME = 0.1;

	private static final int SHEET_PIXELS = 384;
	private static final int MAX_LOOSE = 12;
	private static final int MAX_LOOSE_WITHOUT_VR = 3;
	private static final int MIN_LIGHT = 7;
	private static final double BRIGHTEST_GAMMA = 0.5;

	private static final double DRAW_DISTANCE = 64.0;
	private static final double LABEL_DISTANCE = 5.0;
	private static final float VEIL_GAP = 0.0015F;
	private static final long DEVELOP_TIMEOUT_NANOS = 3_000_000_000L;
	private static final double BLAST_REACH = 2.5;
	private static final double BLAST_SPEED = 9.0;
	private static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath(Vrcamera.MOD_ID,
			"textures/misc/white.png");

	private final List<PhotoSheet> sheets = new ArrayList<>();
	private final ArrayDeque<Integer> freeTextures = new ArrayDeque<>();
	private long frameNanos;
	private int nextTexture;
	private boolean broken;
	private boolean developing;
	private boolean loadingCustom;
	private long developingSince;

	private Level level;
	private Path cache;
	private String dimension;
	private int session;
	private List<PhotoStore.Pinned> elsewhere = new ArrayList<>();
	private boolean loaded;
	private boolean unsaved;

	private PhotoAlbum() {
	}

	/**
	 * @return the pixels of the sheet as it is shown, null if they are gone
	 */
	private static PhotoCodec.Picture pixels(PhotoSheet sheet) {
		AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(sheet.texture);
		if (!(texture instanceof DynamicTexture dynamic) || dynamic.getPixels() == null) {
			return null;
		}
		NativeImage image = dynamic.getPixels();
		return new PhotoCodec.Picture(image.getWidth(), image.getHeight(), image.getPixels());
	}

	/**
	 * @return the picture as the game wants it. A new one every time: each sheet owns its own
	 */
	public static NativeImage image(PhotoCodec.Picture picture) {
		NativeImage image = new NativeImage(picture.width(), picture.height(), false);
		int[] argb = picture.argb();
		for (int y = 0, i = 0; y < picture.height(); y++) {
			for (int x = 0; x < picture.width(); x++, i++) {
				image.setPixel(x, y, argb[i] | 0xFF000000);
			}
		}
		return image;
	}

	/**
	 * The picture of a camera with a window of its own is drawn as large as the game window and squeezed into the
	 * shape of its own. A photo of it has to be squeezed the same way.
	 *
	 * @param shape width and height of what the picture is shown in
	 */
	private static NativeImage reshape(NativeImage image, int[] shape) {
		int width = Math.min(image.getWidth(), Math.max(1, Math.round(image.getHeight() * shape[0] / (float) shape[1])));
		int height = Math.max(1, Math.round(width * shape[1] / (float) shape[0]));
		if (width == image.getWidth() && height == image.getHeight()) {
			return image;
		}
		NativeImage shaped = new NativeImage(width, height, false);
		try (image) {
			image.resizeSubRectTo(0, 0, image.getWidth(), image.getHeight(), shaped);
		} catch (RuntimeException e) {
			shaped.close();
			throw e;
		}
		return shaped;
	}

	private static void render(
			PhotoSheet sheet, Level level, SubmitNodeCollector output, Vec3 viewPosition, PoseStack poseStack) {
		float printed = sheet.printed();
		if (printed <= 0) {
			return;
		}
		BlockPos block = BlockPos.containing(sheet.center());
		int light = LightTexture.pack(Math.max(MIN_LIGHT, level.getBrightness(LightLayer.BLOCK, block)),
				level.getBrightness(LightLayer.SKY, block));
		float half = PhotoSheet.WIDTH / 2.0F;
		float bottom = -sheet.height() * printed;
		float topV = 1.0F - printed;

		poseStack.pushPose();
		poseStack.translate(sheet.position().x - viewPosition.x, sheet.position().y - viewPosition.y,
				sheet.position().z - viewPosition.z);
		poseStack.mulPose(new Matrix4f().rotation(sheet.rotation()));
		output.submitCustomGeometry(poseStack, RenderType.entityCutout(sheet.texture), (pose, consumer) -> {
			vertex(consumer, pose, -half, bottom, 0, 0, 1, light, 1.0F);
			vertex(consumer, pose, half, bottom, 0, 1, 1, light, 1.0F);
			vertex(consumer, pose, half, 0, 0, 1, topV, light, 1.0F);
			vertex(consumer, pose, -half, 0, 0, 0, topV, light, 1.0F);
		});
		output.submitCustomGeometry(poseStack, RenderType.entityCutout(WHITE), (pose, consumer) -> {
			vertex(consumer, pose, -half, 0, 0, 0, 0, light, 1.0F);
			vertex(consumer, pose, half, 0, 0, 1, 0, light, 1.0F);
			vertex(consumer, pose, half, bottom, 0, 1, 1, light, 1.0F);
			vertex(consumer, pose, -half, bottom, 0, 0, 1, light, 1.0F);
		});
		float veil = sheet.veil();
		if (veil > 0.01F) {
			output.submitCustomGeometry(poseStack, RenderType.entityTranslucent(WHITE), (pose, consumer) -> {
				vertex(consumer, pose, -half, bottom, VEIL_GAP, 0, 1, light, veil);
				vertex(consumer, pose, half, bottom, VEIL_GAP, 1, 1, light, veil);
				vertex(consumer, pose, half, 0, VEIL_GAP, 1, 0, light, veil);
				vertex(consumer, pose, -half, 0, VEIL_GAP, 0, 0, light, veil);
			});
		}
		poseStack.popPose();
	}

	private static void vertex(
			VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, float u, float v, int light,
			float alpha) {
		consumer.addVertex(pose, x, y, z)
				.setColor(1.0F, 1.0F, 1.0F, alpha)
				.setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light)
				.setNormal(0, 1, 0);
	}

	/**
	 * @return if a photo is on its way out of the camera, the next one has to wait for it
	 */
	public boolean isPrinting() {
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
		RenderTarget picture = Vive.cameraPicture();
		if (picture == null) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		this.developing = true;
		this.developingSince = System.nanoTime();
		Screenshot.takeScreenshot(picture, image -> mc.execute(() -> develop(image, printSheet)));
		return true;
	}

	/**
	 * @return the sheet the photo was printed on, null if there is none
	 */
	private PhotoSheet develop(NativeImage image, boolean printSheet) {
		this.developing = false;
		Path file;
		try {
			file = PhotoStore.newPhoto();
		} catch (IOException | RuntimeException e) {
			Vrcamera.LOGGER.error("VRCamera: could not take the photo", e);
			image.close();
			return null;
		}
		PhotoSheet sheet = null;
		if (printSheet && this.level != null && this.level == Minecraft.getInstance().level) {
			try {
				sheet = print(image, file.getFileName().toString());
				LocalPlayer player = Minecraft.getInstance().player;
				if (player != null) {
					CameraEffects.ownPrinting(player);
				}
			} catch (RuntimeException e) {
				Vrcamera.LOGGER.error("VRCamera: could not print the photo", e);
			}
		}
		CompletableFuture.runAsync(() -> {
			try (image) {
				image.writeToFile(file);
			} catch (IOException | RuntimeException e) {
				Vrcamera.LOGGER.error("VRCamera: could not save the photo {}", file, e);
			}
		});
		return sheet;
	}

	private PhotoSheet print(NativeImage image, String name) {
		int width = Math.min(SHEET_PIXELS, image.getWidth());
		int height = Math.max(1, Math.round(width * image.getHeight() / (float) image.getWidth()));
		NativeImage small = new NativeImage(width, height, false);
		double brightness = Math.clamp(CameraConfig.current().photoBrightness, 0.0, 1.0);
		int[] brighter = new int[256];
		for (int i = 0; i < 256; i++) {
			brighter[i] = (int) Math.round(255.0 * Math.pow(i / 255.0, 1.0 - (1.0 - BRIGHTEST_GAMMA) * brightness));
		}
		try {
			image.resizeSubRectTo(0, 0, image.getWidth(), image.getHeight(), small);
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					int pixel = small.getPixel(x, y);
					small.setPixel(x, y, 0xFF000000 | brighter[pixel >> 16 & 0xFF] << 16 |
							brighter[pixel >> 8 & 0xFF] << 8 | brighter[pixel & 0xFF]);
				}
			}
		} catch (RuntimeException e) {
			small.close();
			throw e;
		}
		small = PixelArt.apply(small, CameraConfig.current().photoPixels);
		String file = name;
		try {
			PhotoStore.prepare(this.cache);
			small.writeToFile(this.cache.resolve(name));
		} catch (IOException e) {
			Vrcamera.LOGGER.warn("VRCamera: could not cache the sheet {}", name, e);
			file = null;
		}

		makeRoom();
		PhotoSheet sheet = add(small, height / (float) width, file);
		PhotoSync.INSTANCE.shareLoose(sheet, pixels(sheet));
		return sheet;
	}

	/**
	 * one more loose sheet is coming, the oldest go if that is too many
	 */
	private void makeRoom() {
		int maxLoose = Vr.isRunning() ? MAX_LOOSE : MAX_LOOSE_WITHOUT_VR;
		int loose = 0;
		for (int i = this.sheets.size() - 1; i >= 0; i--) {
			if (this.sheets.get(i).isLoose() && ++loose >= maxLoose) {
				remove(i, true);
			}
		}
	}

	private PhotoSheet add(NativeImage picture, float aspect, String file) {
		int slot = this.freeTextures.isEmpty() ? this.nextTexture++ : this.freeTextures.pop();
		ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(Vrcamera.MOD_ID, "photo/" + slot);
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
		if (sheet.looseId() != 0 && !sheet.isGhost()) {
			PhotoSync.INSTANCE.dropLoose(sheet.looseId());
		}
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
			remove(i, !this.sheets.get(i).isPinned());
		}
		this.elsewhere = new ArrayList<>();
		this.loaded = false;
		this.unsaved = false;
	}

	private void enter(Level level) {
		leave();
		PhotoSync.INSTANCE.sheetsDropped();
		this.level = level;
		this.cache = PhotoStore.worldCache();
		this.dimension = level.dimension().toString();

		int session = ++this.session;
		Path cache = this.cache;
		String dimension = this.dimension;
		FileTime entered = FileTime.fromMillis(System.currentTimeMillis());
		CompletableFuture.runAsync(() -> {
			List<PhotoStore.Pinned> pinned = PhotoStore.loadPinned(cache);
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
		if (session != this.session || PhotoSync.INSTANCE.isConnected()) {
			here.values().forEach(NativeImage::close);
			this.loaded = session == this.session || this.loaded;
			return;
		}
		this.elsewhere = elsewhere;
		here.forEach((pinned, picture) -> {
			PhotoSheet sheet = add(picture, pinned.aspect, pinned.file);
			sheet.restore(new Vec3(pinned.x, pinned.y, pinned.z),
					new Quaternionf(pinned.qx, pinned.qy, pinned.qz, pinned.qw));
			sheet.custom = pinned.custom;
		});
		this.loaded = true;
		if (this.unsaved) {
			save();
		}
	}

	/**
	 * Writes where the pinned sheets hang. Not before what was pinned earlier is read, or that would be lost.
	 */
	private void save() {
		if (PhotoSync.INSTANCE.isConnected()) {
			return;
		}
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
			entry.custom = sheet.custom;
			pinned.add(entry);
		}
		Path cache = this.cache;
		CompletableFuture.runAsync(() -> PhotoStore.savePinned(cache, pinned));
	}

	/**
	 * A server keeps the pinned sheets from here on. What this client kept for this server itself, from before
	 * the server had the plugin, is not shown: the server would not know about it and nobody else would see it
	 */
	public void serverTookOver() {
		this.session++;
		this.loaded = true;
		this.unsaved = false;
		for (int i = this.sheets.size() - 1; i >= 0; i--) {
			if (this.sheets.get(i).isPinned()) {
				remove(i, false);
			}
		}
	}

	/**
	 * a sheet the server told about, pinned by this player earlier or by someone else
	 *
	 * @param picture owned by the sheet from here on
	 */
	public PhotoSheet addRemote(
			long id, boolean removable, Vec3 position, Quaternionfc rotation, float aspect, NativeImage picture,
			byte[] packed) {
		if (this.level == null) {
			picture.close();
			return null;
		}
		PhotoSheet sheet = add(picture, aspect, null);
		sheet.restore(position, rotation);
		sheet.setRemote(id, removable);
		sheet.packed = packed;
		return sheet;
	}

	/**
	 * @param fell if what it was pinned to is gone: it falls, and is this client's own loose sheet from then on
	 */
	public void removeRemote(long id, boolean fell) {
		for (int i = this.sheets.size() - 1; i >= 0; i--) {
			PhotoSheet sheet = this.sheets.get(i);
			if (sheet.remoteId() != id) {
				continue;
			}
			if (fell && sheet.isPinned()) {
				sheet.setRemote(0, true);
				sheet.blowOff(new Vec3(Math.random() - 0.5, 0.3, Math.random() - 0.5));
			} else {
				remove(i, false);
			}
		}
	}

	/**
	 * the server did not take a sheet the player pinned, it comes off again
	 */
	public void pinRefused(PhotoSheet sheet) {
		sheet.setRemote(0, true);
		sheet.blowOff(Vec3.ZERO);
	}

	/**
	 * @return the sheet nearest to the hand that it can take, null if there is none in reach
	 */
	public PhotoSheet nearest(Vec3 hand, int handIndex, double reach) {
		PhotoSheet nearest = null;
		double nearestDistance = reach * reach;
		for (PhotoSheet sheet : this.sheets) {
			double distance = sheet.center().distanceToSqr(hand);
			if (sheet.canGrab(handIndex) && distance < nearestDistance) {
				nearest = sheet;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	public void grab(PhotoSheet sheet, int hand, Hands hands) {
		boolean wasPinned = sheet.isPinned();
		boolean wasGhost = sheet.isGhost();
		sheet.grab(hand, hands.position(hand), hands.rotation(hand));
		if (wasGhost) {
			PhotoSync.INSTANCE.takeLoose(sheet.looseId());
		} else if (wasPinned && sheet.remoteId() != 0) {
			PhotoSync.INSTANCE.unpin(sheet.remoteId());
			sheet.setRemote(0, true);
			PhotoSync.INSTANCE.shareLoose(sheet, sheet.packed == null ? pixels(sheet) : null);
		} else if (wasPinned) {
			CameraEffects.takenOff(this.level, sheet.center());
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
			if (PhotoSync.INSTANCE.isConnected()) {
				if (sheet.looseId() != 0) {
					PhotoSync.INSTANCE.dropLoose(sheet.looseId());
					sheet.setLooseId(0);
				}
				PhotoSync.INSTANCE.pin(sheet, sheet.packed == null ? pixels(sheet) : null);
			} else {
				CameraEffects.pinned(this.level, sheet.center());
				save();
			}
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

	/**
	 * One frame for a player who is not in VR, where the camera of Vivecraft does this otherwise: the sheets move
	 * on, and the one that is being printed hangs from the camera on the screen.
	 */
	public void frameWithoutVR(Minecraft mc) {
		long now = System.nanoTime();
		double dt = this.frameNanos == 0 ? 0 : Math.min((now - this.frameNanos) / 1.0E9, MAX_FRAME_TIME);
		this.frameNanos = now;
		if (mc.player == null || Vr.isRunning()) {
			return;
		}
		update(mc.player.level(), null, mc.isPaused() ? 0 : dt);
		DesktopCamera.Pose lens = DesktopCamera.INSTANCE.lens();
		if (lens != null) {
			hangFrom(lens.position(), lens.rotation(), mc.player.getScale());
		}
	}

	public void update(Level level, Hands hands, double dt) {
		if (level != this.level) {
			enter(level);
		}
		for (int i = this.sheets.size() - 1; i >= 0; i--) {
			PhotoSheet sheet = this.sheets.get(i);
			if (sheet.hand() >= 0 && hands != null) {
				sheet.carry(hands.position(sheet.hand()), hands.rotation(sheet.hand()), dt);
			}
			boolean wasPinned = sheet.isPinned();
			sheet.update(level, dt);
			if (wasPinned && !sheet.isPinned()) {
				CameraEffects.tornOff(level, sheet.center());
				save();
			}
			if (sheet.isPrinting()) {
				continue;
			}
			BlockPos block = BlockPos.containing(sheet.center());
			boolean burns = level.getFluidState(block).is(FluidTags.LAVA) ||
					level.getBlockState(block).is(BlockTags.FIRE);
			if (burns && !sheet.isServerOwned() && !sheet.isGhost()) {
				boolean burnedPinned = sheet.isPinned();
				CameraEffects.burned(level, sheet.center());
				remove(i, true);
				if (burnedPinned) {
					save();
				}
			} else if (sheet.isGone() || (sheet.isLoose() && !level.isLoaded(block))) {
				remove(i, true);
			}
		}
	}

	/**
	 * An explosion blows sheets off what they are pinned to and away from where they lie. Each a bit differently,
	 * paper does not fly in formation.
	 */
	public void explosion(Vec3 center, float radius) {
		double reach = radius * BLAST_REACH;
		boolean unpinned = false;
		for (PhotoSheet sheet : this.sheets) {
			Vec3 away = sheet.center().subtract(center);
			double distance = away.length();
			if (distance > reach || sheet.isServerOwned() || !(sheet.isPinned() || sheet.isLoose())) {
				continue;
			}
			double force = BLAST_SPEED * (1.0 - distance / reach) * (0.6 + Math.random() * 0.8);
			Vec3 direction = distance < 1.0E-3 ? new Vec3(0, 1, 0) : away.scale(1.0 / distance);
			Vec3 scatter = new Vec3(Math.random() - 0.5, Math.random() * 0.8, Math.random() - 0.5);
			if (sheet.isPinned()) {
				unpinned = true;
				CameraEffects.tornOff(this.level, sheet.center());
			}
			sheet.blowOff(direction.add(scatter).scale(force));
		}
		if (unpinned) {
			save();
		}
	}

	/**
	 * a loose sheet of another player, shown where their client says it is
	 *
	 * @param picture owned by the sheet from here on
	 */
	public PhotoSheet addGhost(
			long looseId, Vec3 position, Quaternionfc rotation, float aspect, NativeImage picture, byte[] packed) {
		if (this.level == null) {
			picture.close();
			return null;
		}
		PhotoSheet sheet = add(picture, aspect, null);
		sheet.makeGhost(looseId, position, rotation);
		sheet.packed = packed;
		return sheet;
	}

	/**
	 * Puts a picture from the internet on a sheet and drops it in front of the player. The address is opened
	 * by this client alone.
	 *
	 * @param feedback told how it went, on the game thread
	 */
	public void loadCustom(String address, Consumer<Component> feedback) {
		if (this.level == null || this.loadingCustom) {
			feedback.accept(Component.translatable("vrcamera.message.load.busy"));
			return;
		}
		this.loadingCustom = true;
		feedback.accept(Component.translatable("vrcamera.message.load.start"));
		Level level = this.level;
		CompletableFuture.supplyAsync(() -> {
			try {
				CustomPictures.Loaded loaded = CustomPictures.load(address);
				PhotoStore.saveCustom(loaded.original(), loaded.format());
				return loaded.picture();
			} catch (IOException e) {
				throw new java.util.concurrent.CompletionException(e);
			}
		}).whenCompleteAsync((picture, error) -> {
			this.loadingCustom = false;
			LocalPlayer player = Minecraft.getInstance().player;
			if (error != null || picture == null) {
				Throwable cause = error != null && error.getCause() != null ? error.getCause() : error;
				feedback.accept(Component.translatable("vrcamera.message.load.failed",
						cause == null || cause.getMessage() == null ? "?" : cause.getMessage()));
				return;
			}
			if (player == null || this.level != level) {
				return;
			}
			NativeImage pixels = image(picture);
			String file = "custom_" + System.currentTimeMillis() + ".png";
			try {
				PhotoStore.prepare(this.cache);
				pixels.writeToFile(this.cache.resolve(file));
			} catch (IOException e) {
				Vrcamera.LOGGER.warn("VRCamera: could not cache the sheet {}", file, e);
				file = null;
			}
			makeRoom();
			PhotoSheet sheet = add(pixels, picture.height() / (float) picture.width(), file);
			sheet.custom = true;
			Vec3 look = player.getLookAngle();
			Vec3 forward = new Vec3(look.x, 0, look.z);
			forward = forward.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : forward.normalize();
			sheet.toss(player.getEyePosition().add(forward.scale(0.7)),
					new Quaternionf().rotationY((float) Math.atan2(-forward.x, -forward.z)), forward.scale(1.5));
			PhotoSync.INSTANCE.shareLoose(sheet, picture);
			feedback.accept(Component.translatable("vrcamera.message.load.done"));
		}, Minecraft.getInstance());
	}

	/**
	 * Says what the black sheets are: custom pictures of others that are not shown. Called while the game
	 * collects gizmos for a pass
	 */
	public void drawLabels() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || this.sheets.isEmpty()) {
			return;
		}
		try {
			Vec3 eyes = player.getEyePosition();
			for (PhotoSheet sheet : this.sheets) {
				if (sheet.placeholder && sheet.center().distanceToSqr(eyes) < LABEL_DISTANCE * LABEL_DISTANCE) {
					Gizmos.billboardText(Component.translatable("vrcamera.label.custom").getString(),
							sheet.center().add(0, 0.02, 0),
							TextGizmo.Style.forColorAndCentered(0xFFFFFFFF).withScale(0.045F));
					Gizmos.billboardText(Component.translatable("vrcamera.label.custom.hint").getString(),
							sheet.center().add(0, -0.02, 0),
							TextGizmo.Style.forColorAndCentered(0xFFC0C0C0).withScale(0.03F));
				}
			}
		} catch (IllegalStateException e) {
		}
	}

	public void moveGhost(long looseId, Vec3 position, Quaternionfc rotation) {
		for (PhotoSheet sheet : this.sheets) {
			if (sheet.looseId() == looseId && sheet.isGhost()) {
				sheet.ghostTo(position, rotation);
			}
		}
	}

	/**
	 * The server says this loose sheet is gone, for this player: someone picked it up, its owner left, or it
	 * was one too many. Whether it was a sheet of this player or one of someone else
	 */
	public void removeLoose(long looseId) {
		for (int i = this.sheets.size() - 1; i >= 0; i--) {
			PhotoSheet sheet = this.sheets.get(i);
			if (sheet.looseId() == looseId && !sheet.isPinned()) {
				sheet.setLooseId(0);
				remove(i, true);
			}
		}
	}

	public boolean has(PhotoSheet sheet) {
		return this.sheets.contains(sheet);
	}

	/**
	 * @param visitor gets every sheet of this player the others see as a loose one
	 */
	public void forEachShared(Consumer<PhotoSheet> visitor) {
		for (PhotoSheet sheet : this.sheets) {
			if (sheet.looseId() != 0 && !sheet.isGhost() && !sheet.isPinned()) {
				visitor.accept(sheet);
			}
		}
	}

	/**
	 * A photo by a player who is not in VR. With a camera on their screen that films: what that one sees, and the
	 * sheet comes out of it. With none: what they see themselves, as a sheet that drops in front of them. They
	 * can't pick it up again, someone in VR can.
	 *
	 * @return false if the last one is still on its way
	 */
	public boolean takeWithoutCamera(LocalPlayer player, boolean printSheet) {
		if (isPrinting()) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		DesktopCamera.Pose lens = DesktopCamera.INSTANCE.lens();
		RenderTarget ofCamera = lens == null ? null : DirectorPass.picture();
		int[] shape = ofCamera == null ? null : DirectorPass.shape();
		this.developing = true;
		this.developingSince = System.nanoTime();
		Screenshot.takeScreenshot(ofCamera == null ? mc.getMainRenderTarget() : ofCamera, image -> mc.execute(() -> {
			NativeImage photo = shape == null ? image : reshape(image, shape);
			if (CameraConfig.current().photoClipboard) {
				PhotoClipboard.copy(photo.getPixels(), photo.getWidth(), photo.getHeight());
			}
			PhotoSheet sheet = develop(photo, printSheet);
			LocalPlayer now = mc.player;
			if (sheet == null || now == null || lens != null) {
				return;
			}
			Vec3 from = now.getEyePosition();
			Vec3 look = now.getLookAngle();
			Vec3 forward = new Vec3(look.x, 0, look.z);
			forward = forward.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : forward.normalize();
			Quaternionf rotation = new Quaternionf().rotationY((float) Math.atan2(-forward.x, -forward.z));
			sheet.toss(from.add(forward.scale(0.7)), rotation, forward.scale(1.5));
		}));
		return true;
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

	/**
	 * where the hands of a player in VR are
	 */
	public interface Hands {
		Vec3 position(int hand);

		Quaternionf rotation(int hand);
	}
}
