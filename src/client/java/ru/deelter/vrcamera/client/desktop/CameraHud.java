package ru.deelter.vrcamera.client.desktop;

import ru.deelter.vrcamera.client.config.CameraConfig;

/**
 * The hotbar, hearts, food, armour, boss bars and titles of the player in the picture of the camera in its own
 * window, for the people watching a stream, if the player wants that. Not what is only for the player: the
 * crosshair, chat, the line over the hotbar, the player list, the sidebar, menus, toasts and the debug screen.
 * In the window of the game the camera has the whole HUD already.
 */
public final class CameraHud {
	private CameraHud() {
	}

	/**
	 * @return if the picture of the camera that is drawn right now has the HUD on it
	 */
	public static boolean shown() {
		return isCameraPicture() && wanted();
	}

	/**
	 * @return if the player wants the HUD on the picture of the camera, whether one is drawn right now or not
	 */
	public static boolean wanted() {
		return CameraConfig.current().cameraHud && !ChromaKey.isOn();
	}

	/**
	 * @return if what the game draws right now is the picture of the camera, where the parts of the HUD that are
	 * only for the player are left out
	 */
	public static boolean isCameraPicture() {
		return DirectorPass.isActive() && !DesktopGui.isDrawing();
	}
}
