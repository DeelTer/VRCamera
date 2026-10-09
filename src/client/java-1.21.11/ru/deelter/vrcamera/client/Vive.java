package ru.deelter.vrcamera.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.vivecraft.api.client.VRClientAPI;
import org.vivecraft.api.client.data.RenderPass;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.VRData;
import org.vivecraft.client_vr.render.rendertypes.VRRenderTypes;
import org.vivecraft.client_xr.render_pass.RenderPassType;
import ru.deelter.vrcamera.client.gui.CameraDebug;
import ru.deelter.vrcamera.client.photo.PhotoAlbum;

import java.util.List;

/**
 * What the parts of the mod that also work without Vivecraft need of it. Only to be called after {@link Vr} said
 * that Vivecraft is installed: this class is not to be loaded without it.
 */
public final class Vive {

	private Vive() {
	}

	static boolean isRunning() {
		return CameraController.isVRRunning();
	}

	static boolean isVanillaPass() {
		return RenderPassType.isVanilla();
	}

	/**
	 * the camera and what the hands do with it become part of Vivecraft
	 */
	static void register(CameraController controller) {
		VRClientAPI.instance().addClientRegistrationHandler(event -> {
			event.registerTrackers(controller);
			event.registerInteractModules(new CameraPull(controller), new CameraShutter(controller), new SheetGrab());
		});
	}

	/**
	 * @return if the picture that is drawn right now is the one of the camera
	 */
	public static boolean isCameraPass() {
		return ClientDataHolderVR.getInstance().currentPass == RenderPass.CAMERA;
	}

	public static Vec3 cameraPosition() {
		return ClientDataHolderVR.getInstance().cameraTracker.getPosition();
	}

	/**
	 * what only the eyes of the player get to see: none of it should show up in the recording
	 */
	public static void drawHeadsetAids() {
		final ClientDataHolderVR dh = ClientDataHolderVR.getInstance();
		if (dh.currentPass == RenderPass.LEFT || dh.currentPass == RenderPass.RIGHT) {
			CameraController.INSTANCE.drawHeadsetAids(dh.vrPlayer.vrdata_world_render);
		}
	}

	/**
	 * @return the picture the camera films right now, null if there is none
	 */
	@Nullable
	public static RenderTarget cameraPicture() {
		final ClientDataHolderVR dh = ClientDataHolderVR.getInstance();
		return dh.vrRenderer == null ? null : dh.vrRenderer.cameraFramebuffer;
	}

	@NotNull
	public static PhotoAlbum.Hands hands(VRData vr) {
		return new PhotoAlbum.Hands() {
			@Override
			public Vec3 position(int hand) {
				return vr.getController(hand).getPosition();
			}

			@Override
			public Quaternionf rotation(int hand) {
				return vr.getController(hand).getMatrix().getNormalizedRotation(new Quaternionf());
			}
		};
	}

	/**
	 * @return if the camera is in a mode, whether it films right now or waits for VR
	 */
	public static boolean isCameraOn() {
		return CameraController.INSTANCE.mode() != CameraController.Mode.OFF;
	}

	public static List<String> debugLines() {
		return CameraDebug.lines(CameraController.INSTANCE);
	}

	public static void reloadConfig() {
		CameraController.INSTANCE.reloadConfig();
	}

	/**
	 * @return how to draw a picture onto something in the world, from both sides and only where it is not see-through
	 */
	public static RenderType pictureLayer(RenderTarget picture) {
		return VRRenderTypes.entityCutoutNoCardinalLightLinear(picture.getColorTextureView(), false);
	}
}
