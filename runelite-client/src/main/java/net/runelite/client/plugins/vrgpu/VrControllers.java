package net.runelite.client.plugins.vrgpu;

import net.runelite.client.plugins.vrgpu.openxr.XrInput;

/**
 * Owns both controller snapshots and resolves the small set of "which hand drives this?"
 * decisions for the VR frame.
 *
 * There is one primary hand for hover/UI/cursor behavior. A context menu temporarily
 * overrides that primary with the hand that opened the menu.
 */
final class VrControllers
{
	private final VrController left;
	private final VrController right;
	private VrController.Hand primaryHand = VrController.Hand.RIGHT;
	private VrController.Hand menuOwnerHand;

	VrControllers(XrInput xrInput)
	{
		this.left = new VrController(VrController.Hand.LEFT, xrInput);
		this.right = new VrController(VrController.Hand.RIGHT, xrInput);
	}

	VrController left()
	{
		return left;
	}

	VrController right()
	{
		return right;
	}

	VrController byHand(VrController.Hand hand)
	{
		return hand == VrController.Hand.LEFT ? left : right;
	}

	void update()
	{
		left.update();
		right.update();
	}

	void computeOsrsRays(float worldScale, float stageOffsetY, float stageOffsetZ,
		float anchorX, float anchorY, float anchorZ)
	{
		left.computeOsrsRay(worldScale, stageOffsetY, stageOffsetZ, anchorX, anchorY, anchorZ);
		right.computeOsrsRay(worldScale, stageOffsetY, stageOffsetZ, anchorX, anchorY, anchorZ);
	}

	void latchEdges()
	{
		left.latchEdges();
		right.latchEdges();
	}

	VrController primary()
	{
		// Menu mode is the only primary override: the opening hand owns hover/click/cancel.
		if (menuOwnerHand != null)
		{
			return byHand(menuOwnerHand);
		}
		return byHand(primaryHand);
	}

	VrController pressedThisFrame()
	{
		// One click per frame. Right wins if both hands press on the same frame.
		if (right.isTriggerPressed() || right.isSqueezePressed())
		{
			return right;
		}
		if (left.isTriggerPressed() || left.isSqueezePressed())
		{
			return left;
		}
		return null;
	}

	void onClickDispatched(VrController.Hand hand)
	{
		// Clicks inside a menu must not change which hand is primary after the menu closes.
		if (menuOwnerHand != null)
		{
			return;
		}
		primaryHand = hand;
	}

	void onMenuOpened()
	{
		// Latch the current primary at open time; later hand activity does not steal menu input.
		menuOwnerHand = primary().hand;
	}

	void onMenuClosed()
	{
		menuOwnerHand = null;
	}

	boolean isMenuOpen()
	{
		return menuOwnerHand != null;
	}
}
