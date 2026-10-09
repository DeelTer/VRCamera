package ru.deelter.vrcamera.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import ru.deelter.vrcamera.client.Vive;
import ru.deelter.vrcamera.client.Vr;
import ru.deelter.vrcamera.client.desktop.ChromaKey;
import ru.deelter.vrcamera.client.desktop.DesktopCamera;
import ru.deelter.vrcamera.client.desktop.DesktopGui;
import ru.deelter.vrcamera.client.desktop.DirectorPass;
import ru.deelter.vrcamera.client.photo.CameraFlashes;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;
import ru.deelter.vrcamera.client.sync.RemoteCameras;

/**
 * Where the mod draws into a picture of the world. Newer versions of the game are mixed into for this, this one has
 * events of Fabric for it: the things of the mod with the entities, its texts after everything else.
 */
public final class WorldDrawing {
	private WorldDrawing() {
	}

	public static void register() {
		WorldRenderEvents.BEFORE_ENTITIES.register(context -> ChromaKey.paintOver());
		WorldRenderEvents.AFTER_ENTITIES.register(WorldDrawing::drawThings);
		WorldRenderEvents.LAST.register(WorldDrawing::drawTexts);
	}

	private static void drawThings(WorldRenderContext context) {
		if (context.consumers() != null && context.matrixStack() != null) {
			SubmitNodeCollector output = new SubmitNodeCollector(context.consumers());
			Vec3 from = context.camera().getPosition();
			PoseStack poseStack = context.matrixStack();
			PhotoAlbum.INSTANCE.render(output, from, poseStack);
			RemoteCameras.INSTANCE.render(output, from, poseStack);
			CameraFlashes.INSTANCE.render(output, from, poseStack);
			if (Vr.isVanillaPass()) {
				DesktopCamera.INSTANCE.renderModel(output, from, poseStack);
				if (DirectorPass.isActive()) {
					DesktopGui.render(output, from, poseStack);
				}
			}
		}
		if (ChromaKey.applies()) {
			Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
			ChromaKey.shutOut();
		}
	}

	private static void drawTexts(WorldRenderContext context) {
		RemoteCameras.INSTANCE.drawLabels();
		PhotoAlbum.INSTANCE.drawLabels();
		DesktopCamera.INSTANCE.drawLabel();
		if (Vr.isRunning()) {
			Vive.drawHeadsetAids();
		}
		MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
		Gizmos.render(new PoseStack(), buffers, context.camera());
		buffers.endBatch();
	}
}
