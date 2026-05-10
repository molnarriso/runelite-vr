package net.runelite.client.plugins.vrgpu;

import net.runelite.client.plugins.vrgpu.openxr.XrInput;

/**
 * Passive per-frame snapshot of one physical XR controller.
 *
 * It owns hand-specific XrInput reads, button hysteresis/edge detection, and the
 * derived OSRS-space ray. It deliberately does not know which hand is primary or
 * what a click/menu/UI action means.
 */
final class VrController
{
	enum Hand
	{
		LEFT,
		RIGHT
	}

	enum StickAction
	{
		CENTERED,
		UP,
		DOWN,
		LEFT,
		RIGHT
	}

	private static final float BUTTON_PRESS_THRESHOLD = 0.7f;
	private static final float BUTTON_RELEASE_THRESHOLD = 0.5f;
	private static final float STICK_ACTION_THRESHOLD = 0.65f;
	private static final float STICK_DEAD_ZONE = 0.30f;

	public final Hand hand;
	private final XrInput xrInput;

	public boolean active;
	public float posX, posY, posZ;
	public float dirX, dirY, dirZ;
	public float oriX, oriY, oriZ, oriW;
	public float trigger, squeeze;
	public float thumbstickX, thumbstickY;
	// Resolved button state uses hysteresis so analog values near the threshold do not flicker.
	public boolean triggerDown;
	public boolean squeezeDown;
	public StickAction stickAction = StickAction.CENTERED;
	public float[] osrsRay;
	private boolean prevTriggerDown;
	private boolean prevSqueezeDown;

	VrController(Hand hand, XrInput xrInput)
	{
		this.hand = hand;
		this.xrInput = xrInput;
	}

	boolean isTriggerPressed()
	{
		return triggerDown && !prevTriggerDown;
	}

	boolean isSqueezePressed()
	{
		return squeezeDown && !prevSqueezeDown;
	}

	void update()
	{
		// Keep the verbose left/right XrInput branching in one place.
		if (hand == Hand.LEFT)
		{
			active = xrInput.isLeftActive();
			posX = xrInput.getLeftPosX();
			posY = xrInput.getLeftPosY();
			posZ = xrInput.getLeftPosZ();
			dirX = xrInput.getLeftDirX();
			dirY = xrInput.getLeftDirY();
			dirZ = xrInput.getLeftDirZ();
			oriX = xrInput.getLeftOriX();
			oriY = xrInput.getLeftOriY();
			oriZ = xrInput.getLeftOriZ();
			oriW = xrInput.getLeftOriW();
			trigger = xrInput.getLeftTrigger();
			squeeze = xrInput.getLeftSqueeze();
			thumbstickX = xrInput.getLeftThumbstickX();
			thumbstickY = xrInput.getLeftThumbstickY();
		}
		else
		{
			active = xrInput.isRightActive();
			posX = xrInput.getRightPosX();
			posY = xrInput.getRightPosY();
			posZ = xrInput.getRightPosZ();
			dirX = xrInput.getRightDirX();
			dirY = xrInput.getRightDirY();
			dirZ = xrInput.getRightDirZ();
			oriX = xrInput.getRightOriX();
			oriY = xrInput.getRightOriY();
			oriZ = xrInput.getRightOriZ();
			oriW = xrInput.getRightOriW();
			trigger = xrInput.getRightTrigger();
			squeeze = xrInput.getRightSqueeze();
			thumbstickX = xrInput.getRightThumbstickX();
			thumbstickY = xrInput.getRightThumbstickY();
		}

		triggerDown = applyHysteresis(triggerDown, trigger);
		squeezeDown = applyHysteresis(squeezeDown, squeeze);
		stickAction = resolveStickAction(stickAction, thumbstickX, thumbstickY);
	}

	void computeOsrsRay(float worldScale, float stageOffsetY, float stageOffsetZ,
		float anchorWorldX, float anchorWorldY, float anchorWorldZ)
	{
		// Null means this hand cannot drive world/UI hit testing this frame.
		if (!active)
		{
			osrsRay = null;
			return;
		}

		float ox = anchorWorldX - posX / worldScale;
		float oy = anchorWorldY - (posY - stageOffsetY) / worldScale;
		float oz = anchorWorldZ + (posZ - stageOffsetZ) / worldScale;

		// Canonical scene-interaction ray: raw XR controller direction mapped into OSRS coords.
		float rdx = -dirX;
		float rdy = -dirY;
		float rdz = dirZ;
		float len = (float) Math.sqrt(rdx * rdx + rdy * rdy + rdz * rdz);
		if (len <= 1e-6f)
		{
			osrsRay = null;
			return;
		}

		osrsRay = new float[]{ox, oy, oz, rdx / len, rdy / len, rdz / len};
	}

	void latchEdges()
	{
		// Consumers read is*Pressed() before this call; then the current state becomes baseline.
		prevTriggerDown = triggerDown;
		prevSqueezeDown = squeezeDown;
	}

	private static boolean applyHysteresis(boolean wasDown, float value)
	{
		if (wasDown)
		{
			return value >= BUTTON_RELEASE_THRESHOLD;
		}
		return value >= BUTTON_PRESS_THRESHOLD;
	}

	private static StickAction resolveStickAction(StickAction current, float x, float y)
	{
		if (Math.abs(x) <= STICK_DEAD_ZONE && Math.abs(y) <= STICK_DEAD_ZONE)
		{
			return StickAction.CENTERED;
		}
		if (current != StickAction.CENTERED)
		{
			return current;
		}

		float absX = Math.abs(x);
		float absY = Math.abs(y);
		if (absX < STICK_ACTION_THRESHOLD && absY < STICK_ACTION_THRESHOLD)
		{
			return StickAction.CENTERED;
		}
		if (absY >= absX)
		{
			return y > 0f ? StickAction.UP : StickAction.DOWN;
		}
		return x > 0f ? StickAction.RIGHT : StickAction.LEFT;
	}
}
