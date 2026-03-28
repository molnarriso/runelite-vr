/*
 * Copyright (c) 2024, RuneLiteVR contributors
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.vrgpu.openxr;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.PointerBuffer;
import org.lwjgl.openxr.XrAction;
import org.lwjgl.openxr.XrActionCreateInfo;
import org.lwjgl.openxr.XrActionSet;
import org.lwjgl.openxr.XrActionSetCreateInfo;
import org.lwjgl.openxr.XrActionSpaceCreateInfo;
import org.lwjgl.openxr.XrActionStateFloat;
import org.lwjgl.openxr.XrActionStateGetInfo;
import org.lwjgl.openxr.XrActionStatePose;
import org.lwjgl.openxr.XrActionSuggestedBinding;
import org.lwjgl.openxr.XrActionsSyncInfo;
import org.lwjgl.openxr.XrActiveActionSet;
import org.lwjgl.openxr.XrInstance;
import org.lwjgl.openxr.XrInteractionProfileSuggestedBinding;
import org.lwjgl.openxr.XrPosef;
import org.lwjgl.openxr.XrSession;
import org.lwjgl.openxr.XrSessionActionSetsAttachInfo;
import org.lwjgl.openxr.XrSpace;
import org.lwjgl.openxr.XrSpaceLocation;
import org.lwjgl.system.MemoryStack;

import java.nio.LongBuffer;

import static org.lwjgl.openxr.XR10.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Manages OpenXR controller input: aim poses and trigger values for both hands.
 * <p>
 * Call {@link #init(XrInstance, XrSession)} right after session creation and
 * before the first {@code xrBeginSession} (i.e. before the first pollEvents).
 * Call {@link #sync(XrSession, XrSpace, long)} once per frame after beginXrFrame.
 */
@Slf4j
public class XrInput
{
	private XrInstance instance;
	private XrActionSet actionSet;
	private XrAction aimAction;
	private XrAction triggerAction;
	private XrAction squeezeAction;
	private XrSpace leftAimSpace;
	private XrSpace rightAimSpace;
	private long pathHandLeft;
	private long pathHandRight;

	/** Whether the left controller pose was valid this frame. */
	@Getter private boolean leftActive;
	/** Whether the right controller pose was valid this frame. */
	@Getter private boolean rightActive;

	/** Left trigger value 0..1. */
	@Getter private float leftTrigger;
	/** Right trigger value 0..1. */
	@Getter private float rightTrigger;

	/** Left squeeze/grip value 0..1. */
	@Getter private float leftSqueeze;
	/** Right squeeze/grip value 0..1. */
	@Getter private float rightSqueeze;

	/** Left controller aim origin in stage space (metres). */
	@Getter private float leftPosX, leftPosY, leftPosZ;
	/** Left controller aim direction (unit vector) in stage space. */
	@Getter private float leftDirX, leftDirY, leftDirZ;

	/** Right controller aim origin in stage space (metres). */
	@Getter private float rightPosX, rightPosY, rightPosZ;
	/** Right controller aim direction (unit vector) in stage space. */
	@Getter private float rightDirX, rightDirY, rightDirZ;

	// -------------------------------------------------------------------------
	// Public API
	// -------------------------------------------------------------------------

	/**
	 * Create the action set, actions, suggest bindings for common controller
	 * profiles, and attach the action set to the session.
	 * <p>
	 * Must be called before the first {@code xrBeginSession}.
	 */
	public void init(XrInstance instance, XrSession session)
	{
		this.instance = instance;
		try (MemoryStack stack = stackPush())
		{
			LongBuffer lb = stack.mallocLong(1);
			PointerBuffer pp = stack.mallocPointer(1);

			// Resolve hand subaction paths
			checkXr("xrStringToPath /user/hand/left",
				xrStringToPath(instance, "/user/hand/left", lb));
			pathHandLeft = lb.get(0);

			checkXr("xrStringToPath /user/hand/right",
				xrStringToPath(instance, "/user/hand/right", lb));
			pathHandRight = lb.get(0);

			// Create action set
			checkXr("xrCreateActionSet", xrCreateActionSet(instance,
				XrActionSetCreateInfo.calloc(stack)
					.type(XR_TYPE_ACTION_SET_CREATE_INFO)
					.actionSetName(stack.UTF8("gameplay"))
					.localizedActionSetName(stack.UTF8("Gameplay"))
					.priority(0),
				pp));
			actionSet = new XrActionSet(pp.get(0), instance);

			LongBuffer subPaths = stack.longs(pathHandLeft, pathHandRight);

			// Aim pose action (both hands via subaction paths)
			checkXr("xrCreateAction aim", xrCreateAction(actionSet,
				XrActionCreateInfo.calloc(stack)
					.type(XR_TYPE_ACTION_CREATE_INFO)
					.actionName(stack.UTF8("aim_pose"))
					.actionType(XR_ACTION_TYPE_POSE_INPUT)
					.localizedActionName(stack.UTF8("Aim Pose"))
					.countSubactionPaths(2)
					.subactionPaths(subPaths),
				pp));
			aimAction = new XrAction(pp.get(0), actionSet);

			// Trigger / select action (both hands)
			checkXr("xrCreateAction trigger", xrCreateAction(actionSet,
				XrActionCreateInfo.calloc(stack)
					.type(XR_TYPE_ACTION_CREATE_INFO)
					.actionName(stack.UTF8("trigger_value"))
					.actionType(XR_ACTION_TYPE_FLOAT_INPUT)
					.localizedActionName(stack.UTF8("Trigger"))
					.countSubactionPaths(2)
					.subactionPaths(subPaths),
				pp));
			triggerAction = new XrAction(pp.get(0), actionSet);

			// Squeeze / grip action (both hands) — used for right-click
			checkXr("xrCreateAction squeeze", xrCreateAction(actionSet,
				XrActionCreateInfo.calloc(stack)
					.type(XR_TYPE_ACTION_CREATE_INFO)
					.actionName(stack.UTF8("squeeze_value"))
					.actionType(XR_ACTION_TYPE_FLOAT_INPUT)
					.localizedActionName(stack.UTF8("Squeeze"))
					.countSubactionPaths(2)
					.subactionPaths(subPaths),
				pp));
			squeezeAction = new XrAction(pp.get(0), actionSet);

			suggestBindings(stack);

			// Attach action sets — must happen before xrBeginSession
			checkXr("xrAttachSessionActionSets", xrAttachSessionActionSets(session,
				XrSessionActionSetsAttachInfo.calloc(stack)
					.type(XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO)
					.actionSets(stack.pointers(actionSet))));

			// Create an action space for each hand's aim pose
			XrPosef identityPose = XrPosef.calloc(stack);
			identityPose.orientation().set(0f, 0f, 0f, 1f);

			checkXr("xrCreateActionSpace left", xrCreateActionSpace(session,
				XrActionSpaceCreateInfo.calloc(stack)
					.type(XR_TYPE_ACTION_SPACE_CREATE_INFO)
					.action(aimAction)
					.subactionPath(pathHandLeft)
					.poseInActionSpace(identityPose),
				pp));
			leftAimSpace = new XrSpace(pp.get(0), session);

			checkXr("xrCreateActionSpace right", xrCreateActionSpace(session,
				XrActionSpaceCreateInfo.calloc(stack)
					.type(XR_TYPE_ACTION_SPACE_CREATE_INFO)
					.action(aimAction)
					.subactionPath(pathHandRight)
					.poseInActionSpace(identityPose),
				pp));
			rightAimSpace = new XrSpace(pp.get(0), session);
		}
		log.info("XrInput initialised");
	}

	/**
	 * Sync action states and sample controller poses for this frame.
	 * Call once per frame after {@code xrBeginFrame}.
	 *
	 * @param session     the active XrSession
	 * @param stageSpace  stage reference space (Y=0 at floor)
	 * @param displayTime predicted display time from this frame's {@code xrBeginFrame}
	 */
	public void sync(XrSession session, XrSpace stageSpace, long displayTime)
	{
		try (MemoryStack stack = stackPush())
		{
			XrActiveActionSet.Buffer active = XrActiveActionSet.calloc(1, stack);
			active.get(0).actionSet(actionSet).subactionPath(XR_NULL_PATH);

			int result = xrSyncActions(session,
				XrActionsSyncInfo.calloc(stack)
					.type(XR_TYPE_ACTIONS_SYNC_INFO)
					.activeActionSets(active));

			if (result == XR_SESSION_NOT_FOCUSED || result != XR_SUCCESS)
			{
				leftActive = false;
				rightActive = false;
				return;
			}

			leftActive = samplePose(stack, session, stageSpace, displayTime,
				leftAimSpace, pathHandLeft, true);
			rightActive = samplePose(stack, session, stageSpace, displayTime,
				rightAimSpace, pathHandRight, false);

			leftTrigger  = sampleFloat(stack, session, triggerAction, pathHandLeft);
			rightTrigger = sampleFloat(stack, session, triggerAction, pathHandRight);
			leftSqueeze  = sampleFloat(stack, session, squeezeAction, pathHandLeft);
			rightSqueeze = sampleFloat(stack, session, squeezeAction, pathHandRight);
		}
	}

	/** Destroy all OpenXR objects in reverse creation order. */
	public void destroy()
	{
		if (leftAimSpace != null) { xrDestroySpace(leftAimSpace); leftAimSpace = null; }
		if (rightAimSpace != null) { xrDestroySpace(rightAimSpace); rightAimSpace = null; }
		if (aimAction != null) { xrDestroyAction(aimAction); aimAction = null; }
		if (triggerAction != null) { xrDestroyAction(triggerAction); triggerAction = null; }
		if (squeezeAction != null) { xrDestroyAction(squeezeAction); squeezeAction = null; }
		if (actionSet != null) { xrDestroyActionSet(actionSet); actionSet = null; }
	}

	// -------------------------------------------------------------------------
	// Private helpers
	// -------------------------------------------------------------------------

	private void suggestBindings(MemoryStack stack)
	{
		LongBuffer lb = stack.mallocLong(1);

		long aimL  = path(lb, "/user/hand/left/input/aim/pose");
		long aimR  = path(lb, "/user/hand/right/input/aim/pose");
		long trigL = path(lb, "/user/hand/left/input/trigger/value");
		long trigR = path(lb, "/user/hand/right/input/trigger/value");
		long sqzL  = path(lb, "/user/hand/left/input/squeeze/value");
		long sqzR  = path(lb, "/user/hand/right/input/squeeze/value");
		long sqzLc = path(lb, "/user/hand/left/input/squeeze/click");
		long sqzRc = path(lb, "/user/hand/right/input/squeeze/click");
		long selL  = path(lb, "/user/hand/left/input/select/click");
		long selR  = path(lb, "/user/hand/right/input/select/click");

		// Oculus / Meta Touch
		suggestProfile(stack, lb, "/interaction_profiles/oculus/touch_controller",
			new long[]{aimL, aimR, trigL, trigR, sqzL, sqzR},
			new XrAction[]{aimAction, aimAction, triggerAction, triggerAction, squeezeAction, squeezeAction});

		// Valve Index
		suggestProfile(stack, lb, "/interaction_profiles/valve/index_controller",
			new long[]{aimL, aimR, trigL, trigR, sqzL, sqzR},
			new XrAction[]{aimAction, aimAction, triggerAction, triggerAction, squeezeAction, squeezeAction});

		// Windows Mixed Reality (squeeze is boolean click)
		suggestProfile(stack, lb, "/interaction_profiles/microsoft/motion_controller",
			new long[]{aimL, aimR, trigL, trigR, sqzLc, sqzRc},
			new XrAction[]{aimAction, aimAction, triggerAction, triggerAction, squeezeAction, squeezeAction});

		// KHR Simple (fallback — no squeeze; trigger=select/click, no right-click)
		suggestProfile(stack, lb, "/interaction_profiles/khr/simple_controller",
			new long[]{aimL, aimR, selL, selR},
			new XrAction[]{aimAction, aimAction, triggerAction, triggerAction});
	}

	private long path(LongBuffer lb, String str)
	{
		lb.clear();
		int r = xrStringToPath(instance, str, lb);
		return r == XR_SUCCESS ? lb.get(0) : XR_NULL_PATH;
	}

	private void suggestProfile(MemoryStack stack, LongBuffer lb,
		String profileStr, long[] bindings, XrAction[] actions)
	{
		lb.clear();
		checkXr("xrStringToPath " + profileStr, xrStringToPath(instance, profileStr, lb));
		long profile = lb.get(0);

		XrActionSuggestedBinding.Buffer suggested =
			XrActionSuggestedBinding.calloc(bindings.length, stack);
		for (int i = 0; i < bindings.length; i++)
		{
			suggested.get(i).action(actions[i]).binding(bindings[i]);
		}

		int result = xrSuggestInteractionProfileBindings(instance,
			XrInteractionProfileSuggestedBinding.calloc(stack)
				.type(XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING)
				.interactionProfile(profile)
				.suggestedBindings(suggested));
		if (result != XR_SUCCESS)
		{
			log.debug("xrSuggestInteractionProfileBindings {}: result={}", profileStr, result);
		}
	}

	/**
	 * Sample one controller's pose. Returns true if pose was valid and fields were updated.
	 */
	private boolean samplePose(MemoryStack stack, XrSession session, XrSpace stageSpace,
		long displayTime, XrSpace aimSpace, long subPath, boolean isLeft)
	{
		XrActionStatePose poseState = XrActionStatePose.calloc(stack)
			.type(XR_TYPE_ACTION_STATE_POSE);
		xrGetActionStatePose(session,
			XrActionStateGetInfo.calloc(stack)
				.type(XR_TYPE_ACTION_STATE_GET_INFO)
				.action(aimAction)
				.subactionPath(subPath),
			poseState);

		if (!poseState.isActive())
		{
			return false;
		}

		XrSpaceLocation loc = XrSpaceLocation.calloc(stack)
			.type(XR_TYPE_SPACE_LOCATION);
		xrLocateSpace(aimSpace, stageSpace, displayTime, loc);

		long flags = loc.locationFlags();
		if ((flags & XR_SPACE_LOCATION_POSITION_VALID_BIT) == 0
			|| (flags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) == 0)
		{
			return false;
		}

		float px = loc.pose().position$().x();
		float py = loc.pose().position$().y();
		float pz = loc.pose().position$().z();

		float qx = loc.pose().orientation().x();
		float qy = loc.pose().orientation().y();
		float qz = loc.pose().orientation().z();
		float qw = loc.pose().orientation().w();

		// Aim direction = rotate (0, 0, -1) by the pose quaternion.
		// Formula derived from q * (0,0,-1,0) * q^-1:
		float dx = -2f * (qw * qy + qz * qx);
		float dy =  2f * (qw * qx - qz * qy);
		float dz = -1f + 2f * (qx * qx + qy * qy);

		if (isLeft)
		{
			leftPosX = px; leftPosY = py; leftPosZ = pz;
			leftDirX = dx; leftDirY = dy; leftDirZ = dz;
		}
		else
		{
			rightPosX = px; rightPosY = py; rightPosZ = pz;
			rightDirX = dx; rightDirY = dy; rightDirZ = dz;
		}
		return true;
	}

	private float sampleFloat(MemoryStack stack, XrSession session, XrAction action, long subPath)
	{
		XrActionStateFloat state = XrActionStateFloat.calloc(stack)
			.type(XR_TYPE_ACTION_STATE_FLOAT);
		xrGetActionStateFloat(session,
			XrActionStateGetInfo.calloc(stack)
				.type(XR_TYPE_ACTION_STATE_GET_INFO)
				.action(action)
				.subactionPath(subPath),
			state);
		return state.isActive() ? state.currentState() : 0f;
	}

	private static void checkXr(String call, int result)
	{
		if (result != XR_SUCCESS)
		{
			throw new RuntimeException(call + " failed: XrResult=" + result);
		}
	}
}
