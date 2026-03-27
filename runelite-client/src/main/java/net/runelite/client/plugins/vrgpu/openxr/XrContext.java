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
import org.lwjgl.openxr.EXTDebugUtils;
import org.lwjgl.openxr.KHROpenGLEnable;
import org.lwjgl.openxr.XrApplicationInfo;
import org.lwjgl.openxr.XrGraphicsRequirementsOpenGLKHR;
import org.lwjgl.openxr.XrEventDataBuffer;
import org.lwjgl.openxr.XrEventDataSessionStateChanged;
import org.lwjgl.openxr.XrGraphicsBindingOpenGLWin32KHR;
import org.lwjgl.openxr.XrInstance;
import org.lwjgl.openxr.XrInstanceCreateInfo;
import org.lwjgl.openxr.XrCompositionLayerProjection;
import org.lwjgl.openxr.XrCompositionLayerProjectionView;
import org.lwjgl.openxr.XrPosef;
import org.lwjgl.openxr.XrReferenceSpaceCreateInfo;
import org.lwjgl.openxr.XrSession;
import org.lwjgl.openxr.XrFrameBeginInfo;
import org.lwjgl.openxr.XrFrameEndInfo;
import org.lwjgl.openxr.XrFrameState;
import org.lwjgl.openxr.XrFrameWaitInfo;
import org.lwjgl.openxr.XrSessionBeginInfo;
import org.lwjgl.openxr.XrSessionCreateInfo;
import org.lwjgl.openxr.XrSpace;
import org.lwjgl.openxr.XrSwapchain;
import org.lwjgl.openxr.XrSystemGetInfo;
import org.lwjgl.openxr.XrView;
import org.lwjgl.openxr.XrViewConfigurationView;
import org.lwjgl.openxr.XrViewLocateInfo;
import org.lwjgl.openxr.XrViewState;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.openxr.KHROpenGLEnable.XR_TYPE_GRAPHICS_BINDING_OPENGL_WIN32_KHR;
import static org.lwjgl.openxr.KHROpenGLEnable.XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_KHR;
import static org.lwjgl.openxr.KHROpenGLEnable.xrGetOpenGLGraphicsRequirementsKHR;
import static org.lwjgl.openxr.XR10.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Manages the OpenXR instance, system, session, and reference space lifecycle.
 * <p>
 * Call {@link #init(long, long)} after {@code awtContext.createGLContext()} on the
 * GL-owning thread.  Poll {@link #pollEvents()} each frame before rendering.
 * Call {@link #destroy()} in plugin shutdown.
 */
@Slf4j
public class XrContext
{
	/** The XrInstance handle. */
	@Getter
	private XrInstance instance;

	/** The XR system id (HMD). */
	@Getter
	private long systemId;

	/** The XrSession handle. */
	@Getter
	private XrSession session;

	/** STAGE reference space — Y=0 at floor, matching "world on the desk" model. */
	@Getter
	private XrSpace stageSpace;

	/**
	 * The current XrSessionState enum value (XR_SESSION_STATE_* constant from XR10).
	 * Updated by {@link #pollEvents()}.
	 */
	@Getter
	private int sessionState = XR_SESSION_STATE_UNKNOWN;

	/** Whether xrBeginSession has been called and xrEndSession has not. */
	@Getter
	private boolean sessionRunning = false;

	/** Display time (ns) captured by {@link #beginXrFrame()}, consumed by {@link #endXrFrame()}. */
	@Getter
	private long pendingDisplayTime;

	/** Recommended swapchain width per eye (index 0 = left, 1 = right). */
	@Getter
	private final int[] eyeWidth = new int[2];

	/** Recommended swapchain height per eye (index 0 = left, 1 = right). */
	@Getter
	private final int[] eyeHeight = new int[2];

	/**
	 * Per-eye views located by {@link #locateViews()} during the current frame.
	 * Allocated once (off-heap) on first use, reused every frame, freed in {@link #destroy()}.
	 */
	@Getter
	private XrView.Buffer views;

	// -------------------------------------------------------------------------
	// Public API
	// -------------------------------------------------------------------------

	/**
	 * Create the XR instance, get the HMD system, create a session bound to
	 * the current Win32 GL context, create a STAGE reference space, and query
	 * the recommended per-eye render resolution.
	 * <p>
	 * Must be called on the thread that owns the GL context.
	 *
	 * @param hglrc Win32 HGLRC obtained via {@code WGL.wglGetCurrentContext()}
	 * @param hdc   Win32 HDC  obtained via {@code WGL.wglGetCurrentDC()}
	 */
	public void init(long hglrc, long hdc)
	{
		createInstance();
		getSystem();
		createSession(hglrc, hdc);
		createStageSpace();
		queryViewConfiguration();
		log.info("XrContext initialised — systemId={} eyeSize[0]={}x{} eyeSize[1]={}x{}",
			systemId, eyeWidth[0], eyeHeight[0], eyeWidth[1], eyeHeight[1]);
	}

	/**
	 * Destroy all OpenXR objects in reverse creation order.
	 * Must be called on the GL-owning thread before the GL context is destroyed.
	 */
	public void destroy()
	{
		if (views != null)
		{
			views.free();
			views = null;
		}
		if (sessionRunning)
		{
			checkXr("xrEndSession (destroy)", xrEndSession(session));
			sessionRunning = false;
		}
		if (stageSpace != null)
		{
			xrDestroySpace(stageSpace);
			stageSpace = null;
		}
		if (session != null)
		{
			xrDestroySession(session);
			session = null;
		}
		if (instance != null)
		{
			xrDestroyInstance(instance);
			instance = null;
		}
	}

	/**
	 * Drain all pending XR events and update {@link #sessionState}.
	 * Responds automatically to READY (begins session) and STOPPING (ends session).
	 *
	 * @return {@code true} while the session is alive;
	 *         {@code false} when the runtime signals EXITING or LOSS_PENDING
	 *         (caller should shut the plugin down).
	 */
	public boolean pollEvents()
	{
		try (MemoryStack stack = stackPush())
		{
			XrEventDataBuffer event = XrEventDataBuffer.calloc(stack)
				.type(XR_TYPE_EVENT_DATA_BUFFER);

			int result;
			while ((result = xrPollEvent(instance, event)) == XR_SUCCESS)
			{
				switch (event.type())
				{
					case XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED:
					{
						XrEventDataSessionStateChanged stateEvent =
							XrEventDataSessionStateChanged.create(event.address());
						int newState = stateEvent.state();
						log.debug("XR session state {} → {}", sessionState, newState);
						sessionState = newState;

						if (newState == XR_SESSION_STATE_READY && !sessionRunning)
						{
							beginSession();
						}
						else if (newState == XR_SESSION_STATE_STOPPING && sessionRunning)
						{
							endSession();
						}
						else if (newState == XR_SESSION_STATE_EXITING
							|| newState == XR_SESSION_STATE_LOSS_PENDING)
						{
							return false;
						}
						break;
					}
					case XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING:
						log.warn("XR instance loss pending — tearing down");
						return false;
					default:
						break;
				}

				// Re-arm the event buffer for the next poll
				event.clear();
				event.type(XR_TYPE_EVENT_DATA_BUFFER);
			}

			if (result != XR_EVENT_UNAVAILABLE)
			{
				log.warn("xrPollEvent returned unexpected result {}", result);
			}
		}
		return true;
	}

	/**
	 * Block until the compositor is ready for a new frame, then begin it.
	 * Stores the predicted display time for use by {@link #endXrFrame()}.
	 *
	 * @return {@code true} if the compositor wants rendered content this frame
	 *         ({@code false} means call {@link #endXrFrame()} with no layers anyway)
	 */
	public boolean beginXrFrame()
	{
		try (MemoryStack stack = stackPush())
		{
			XrFrameWaitInfo waitInfo = XrFrameWaitInfo.calloc(stack)
				.type(XR_TYPE_FRAME_WAIT_INFO);
			XrFrameState frameState = XrFrameState.calloc(stack)
				.type(XR_TYPE_FRAME_STATE);
			checkXr("xrWaitFrame", xrWaitFrame(session, waitInfo, frameState));

			pendingDisplayTime = frameState.predictedDisplayTime();

			XrFrameBeginInfo beginInfo = XrFrameBeginInfo.calloc(stack)
				.type(XR_TYPE_FRAME_BEGIN_INFO);
			checkXr("xrBeginFrame", xrBeginFrame(session, beginInfo));

			return frameState.shouldRender();
		}
	}

	/**
	 * Signal frame end to the compositor with no projection layers.
	 * Used when the 3D scene has not been rendered (e.g. login screen).
	 */
	public void endXrFrame()
	{
		try (MemoryStack stack = stackPush())
		{
			XrFrameEndInfo endInfo = XrFrameEndInfo.calloc(stack)
				.type(XR_TYPE_FRAME_END_INFO)
				.displayTime(pendingDisplayTime)
				.environmentBlendMode(XR_ENVIRONMENT_BLEND_MODE_OPAQUE);
			checkXr("xrEndFrame", xrEndFrame(session, endInfo));
		}
	}

	/**
	 * Locate both eye views (pose + FOV) for the current frame into {@link #views}.
	 * Must be called after {@link #beginXrFrame()} and before rendering each eye.
	 * The returned buffer is reused each frame — do not hold a reference across frames.
	 *
	 * @return 2-element buffer with left (index 0) and right (index 1) eye views
	 */
	public XrView.Buffer locateViews()
	{
		if (views == null)
		{
			views = XrView.calloc(2);
			views.get(0).type(XR_TYPE_VIEW);
			views.get(1).type(XR_TYPE_VIEW);
		}
		try (MemoryStack stack = stackPush())
		{
			XrViewLocateInfo locateInfo = XrViewLocateInfo.calloc(stack)
				.type(XR_TYPE_VIEW_LOCATE_INFO)
				.viewConfigurationType(XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO)
				.displayTime(pendingDisplayTime)
				.space(stageSpace);
			XrViewState viewState = XrViewState.calloc(stack)
				.type(XR_TYPE_VIEW_STATE);
			IntBuffer viewCount = stack.mallocInt(1);
			checkXr("xrLocateViews",
				xrLocateViews(session, locateInfo, viewState, viewCount, views));
		}
		views.position(0);
		return views;
	}

	/**
	 * Signal frame end to the compositor with a stereo projection layer.
	 * Must be called after both eye swapchain images have been released.
	 *
	 * @param locatedViews  the 2-element view buffer from {@link #locateViews()}
	 * @param leftSwapchain XrSwapchain handle for the left eye
	 * @param rightSwapchain XrSwapchain handle for the right eye
	 */
	public void endXrFrameStereo(XrView.Buffer locatedViews,
		XrSwapchain leftSwapchain, XrSwapchain rightSwapchain)
	{
		try (MemoryStack stack = stackPush())
		{
			XrCompositionLayerProjectionView.Buffer projViews =
				XrCompositionLayerProjectionView.calloc(2, stack);

			for (int i = 0; i < 2; i++)
			{
				XrSwapchain sc = (i == 0) ? leftSwapchain : rightSwapchain;
				int w = eyeWidth[i];
				int h = eyeHeight[i];
				XrCompositionLayerProjectionView pv = projViews.get(i);
				pv.type(XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW)
					.pose(locatedViews.get(i).pose())
					.fov(locatedViews.get(i).fov());
				pv.subImage().swapchain(sc);
				pv.subImage().imageRect().offset().x(0).y(0);
				pv.subImage().imageRect().extent().width(w).height(h);
			}

			XrCompositionLayerProjection layer = XrCompositionLayerProjection.calloc(stack)
				.type(XR_TYPE_COMPOSITION_LAYER_PROJECTION)
				.space(stageSpace)
				.views(projViews);

			PointerBuffer layers = stack.pointers(layer.address());

			XrFrameEndInfo endInfo = XrFrameEndInfo.calloc(stack)
				.type(XR_TYPE_FRAME_END_INFO)
				.displayTime(pendingDisplayTime)
				.environmentBlendMode(XR_ENVIRONMENT_BLEND_MODE_OPAQUE)
				.layers(layers);

			checkXr("xrEndFrame", xrEndFrame(session, endInfo));
		}
	}

	// -------------------------------------------------------------------------
	// Private helpers
	// -------------------------------------------------------------------------

	private void createInstance()
	{
		try (MemoryStack stack = stackPush())
		{
			XrApplicationInfo appInfo = XrApplicationInfo.calloc(stack)
				.applicationName(stack.UTF8("RuneLiteVR"))
				.applicationVersion(1)
				.engineName(stack.UTF8("RuneLite"))
				.engineVersion(0)
				.apiVersion(XR_MAKE_VERSION(1, 0, 0));

			PointerBuffer extensions = stack.pointers(
				stack.UTF8("XR_KHR_opengl_enable")
			);

			XrInstanceCreateInfo createInfo = XrInstanceCreateInfo.calloc(stack)
				.type(XR_TYPE_INSTANCE_CREATE_INFO)
				.applicationInfo(appInfo)
				.enabledExtensionNames(extensions);

			PointerBuffer pp = stack.mallocPointer(1);
			checkXr("xrCreateInstance", xrCreateInstance(createInfo, pp));
			instance = new XrInstance(pp.get(0), createInfo);
		}
	}

	private void getSystem()
	{
		try (MemoryStack stack = stackPush())
		{
			XrSystemGetInfo sysInfo = XrSystemGetInfo.calloc(stack)
				.type(XR_TYPE_SYSTEM_GET_INFO)
				.formFactor(XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY);

			LongBuffer lb = stack.mallocLong(1);
			checkXr("xrGetSystem", xrGetSystem(instance, sysInfo, lb));
			systemId = lb.get(0);
		}
	}

	private void createSession(long hglrc, long hdc)
	{
		try (MemoryStack stack = stackPush())
		{
			// Required by XR_KHR_opengl_enable spec: must call before xrCreateSession
			// or the runtime returns XR_ERROR_GRAPHICS_REQUIREMENTS_CALL_MISSING (-50).
			XrGraphicsRequirementsOpenGLKHR reqs = XrGraphicsRequirementsOpenGLKHR.calloc(stack)
				.type(XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_KHR);
			checkXr("xrGetOpenGLGraphicsRequirementsKHR",
				xrGetOpenGLGraphicsRequirementsKHR(instance, systemId, reqs));
			log.debug("OpenGL requirements — minVersion={} maxVersion={}",
				reqs.minApiVersionSupported(), reqs.maxApiVersionSupported());

			XrGraphicsBindingOpenGLWin32KHR glBinding =
				XrGraphicsBindingOpenGLWin32KHR.calloc(stack)
					.type(XR_TYPE_GRAPHICS_BINDING_OPENGL_WIN32_KHR)
					.hDC(hdc)
					.hGLRC(hglrc);

			XrSessionCreateInfo sessionInfo = XrSessionCreateInfo.calloc(stack)
				.type(XR_TYPE_SESSION_CREATE_INFO)
				.next(glBinding.address())
				.systemId(systemId);

			PointerBuffer pp = stack.mallocPointer(1);
			checkXr("xrCreateSession", xrCreateSession(instance, sessionInfo, pp));
			session = new XrSession(pp.get(0), instance);
		}
	}

	private void createStageSpace()
	{
		try (MemoryStack stack = stackPush())
		{
			// Identity pose: no offset, identity quaternion (x=0 y=0 z=0 w=1)
			XrPosef identityPose = XrPosef.calloc(stack);
			identityPose.orientation().set(0.0f, 0.0f, 0.0f, 1.0f);
			identityPose.position$().set(0.0f, 0.0f, 0.0f);

			XrReferenceSpaceCreateInfo spaceInfo = XrReferenceSpaceCreateInfo.calloc(stack)
				.type(XR_TYPE_REFERENCE_SPACE_CREATE_INFO)
				.referenceSpaceType(XR_REFERENCE_SPACE_TYPE_STAGE)
				.poseInReferenceSpace(identityPose);

			PointerBuffer pp = stack.mallocPointer(1);
			checkXr("xrCreateReferenceSpace", xrCreateReferenceSpace(session, spaceInfo, pp));
			stageSpace = new XrSpace(pp.get(0), session);
		}
	}

	private void queryViewConfiguration()
	{
		try (MemoryStack stack = stackPush())
		{
			IntBuffer count = stack.mallocInt(1);
			checkXr("xrEnumerateViewConfigurationViews (count)",
				xrEnumerateViewConfigurationViews(instance, systemId,
					XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, count, null));

			int viewCount = count.get(0);
			if (viewCount < 2)
			{
				throw new RuntimeException(
					"Expected ≥ 2 views for PRIMARY_STEREO, got " + viewCount);
			}

			XrViewConfigurationView.Buffer views =
				XrViewConfigurationView.calloc(viewCount, stack);
			for (int i = 0; i < viewCount; i++)
			{
				views.get(i).type(XR_TYPE_VIEW_CONFIGURATION_VIEW);
			}

			checkXr("xrEnumerateViewConfigurationViews",
				xrEnumerateViewConfigurationViews(instance, systemId,
					XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, count, views));

			for (int i = 0; i < 2; i++)
			{
				eyeWidth[i] = views.get(i).recommendedImageRectWidth();
				eyeHeight[i] = views.get(i).recommendedImageRectHeight();
			}
		}
	}

	private void beginSession()
	{
		try (MemoryStack stack = stackPush())
		{
			XrSessionBeginInfo beginInfo = XrSessionBeginInfo.calloc(stack)
				.type(XR_TYPE_SESSION_BEGIN_INFO)
				.primaryViewConfigurationType(XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO);
			checkXr("xrBeginSession", xrBeginSession(session, beginInfo));
			sessionRunning = true;
			log.info("XR session begun");
		}
	}

	private void endSession()
	{
		checkXr("xrEndSession", xrEndSession(session));
		sessionRunning = false;
		log.info("XR session ended");
	}

	private static void checkXr(String call, int result)
	{
		if (result != XR_SUCCESS)
		{
			throw new RuntimeException(call + " failed with XrResult=" + result);
		}
	}
}
