package ru.deelter.vrcamera.client;

import org.vivecraft.client_vr.provider.ControllerType;
import org.vivecraft.client_vr.provider.MCVR;
import org.vivecraft.client_vr.provider.openvr_lwjgl.MCOpenVR;
import org.vivecraft.client_vr.provider.openvr_lwjgl.VRInputAction;
import ru.deelter.vrcamera.Vrcamera;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Tells if the hand that holds the camera presses another button than the one it holds the camera with: the
 * trigger while it grips, or the grip while it holds the trigger.
 * <p>
 * Vivecraft can't tell. Trigger and grip are bound to the same interact action, which is down either way. But both
 * are bound to other things as well, the trigger to attacking, the grip to the hotbar. Vivecraft only ignores
 * those while interacting, the VR runtime still reports them. So a button is seen by what else it would do: every
 * action that is down on that hand and was not when the camera was grabbed.
 */
public final class SecondButton {
	private final Set<VRInputAction> heldSinceGrab = new HashSet<>();
	private int hand = -1;
	private Method controllerOfOrigin;
	private boolean unavailable;

	/**
	 * Has to be called every tick the camera is held.
	 *
	 * @param hand the hand that holds the camera
	 * @return if another button is down on that hand
	 */
	public boolean isDown(int hand) {
		if (unavailable || !(MCVR.get() instanceof MCOpenVR vr)) {
			return false;
		}
		try {
			final Set<VRInputAction> down = down(vr, ControllerType.values()[hand]);
			if (hand != this.hand) {

				this.hand = hand;
				heldSinceGrab.clear();
				heldSinceGrab.addAll(down);
				return false;
			}

			heldSinceGrab.retainAll(down);
			return down.size() > heldSinceGrab.size();
		} catch (ReflectiveOperationException | RuntimeException e) {

			Vrcamera.LOGGER.warn("VRCamera: can't read controller buttons, the photo button on the camera hand is off",
					e);
			unavailable = true;
			return false;
		}
	}

	/**
	 * the camera is not held anymore
	 */
	public void reset() {
		hand = -1;
		heldSinceGrab.clear();
	}

	private Set<VRInputAction> down(MCOpenVR vr, ControllerType hand) throws ReflectiveOperationException {
		final Set<VRInputAction> down = new HashSet<>();
		for (final VRInputAction action : vr.getInputActions()) {
			if (!action.type.equals("boolean")) {
				continue;
			}
			if (action.isHanded()) {
				if (action.digitalData[hand.ordinal()].state) {
					down.add(action);
				}
			} else if (action.digitalData[0].state && controllerOf(vr, action.digitalData[0].activeOrigin) == hand) {
				down.add(action);
			}
		}
		return down;
	}

	private ControllerType controllerOf(MCOpenVR vr, long origin) throws ReflectiveOperationException {
		if (controllerOfOrigin == null) {

			controllerOfOrigin = MCOpenVR.class.getDeclaredMethod("getOriginControllerType", long.class);
			controllerOfOrigin.setAccessible(true);
		}
		return (ControllerType) controllerOfOrigin.invoke(vr, origin);
	}
}
