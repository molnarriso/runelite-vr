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
import org.lwjgl.openxr.KHROpenGLEnable;
import org.lwjgl.openxr.XrSession;
import org.lwjgl.openxr.XrSwapchain;
import org.lwjgl.openxr.XrSwapchainCreateInfo;
import org.lwjgl.openxr.XrSwapchainImageAcquireInfo;
import org.lwjgl.openxr.XrSwapchainImageBaseHeader;
import org.lwjgl.openxr.XrSwapchainImageOpenGLKHR;
import org.lwjgl.openxr.XrSwapchainImageReleaseInfo;
import org.lwjgl.openxr.XrSwapchainImageWaitInfo;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.openxr.KHROpenGLEnable.XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_KHR;
import static org.lwjgl.openxr.XR10.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Manages a single-eye OpenXR swapchain and the OpenGL FBOs backed by its images.
 * <p>
 * One instance per eye. Usage per frame:
 * <pre>
 *   int fbo = swapchain.acquireImage();
 *   glBindFramebuffer(GL_FRAMEBUFFER, fbo);
 *   // ... render eye ...
 *   swapchain.releaseImage();
 * </pre>
 * <p>
 * Must be created and destroyed on the GL-owning thread.
 */
@Slf4j
public class XrEyeSwapchain
{
	/** The OpenXR swapchain handle. */
	@Getter
	private XrSwapchain swapchain;

	/** Width of each swapchain image, in pixels. */
	@Getter
	private int width;

	/** Height of each swapchain image, in pixels. */
	@Getter
	private int height;

	/**
	 * GL framebuffer object handles — one per swapchain image.
	 * Each FBO has the swapchain image texture as color attachment 0
	 * and a shared depth renderbuffer.
	 */
	private int[] fbos;

	/** Shared depth renderbuffer used by all FBOs in this swapchain. */
	private int depthRbo;

	/** Index of the most recently acquired swapchain image. */
	private int currentImageIndex = -1;

	// -------------------------------------------------------------------------
	// Public API
	// -------------------------------------------------------------------------

	/**
	 * Create the XrSwapchain and all backing GL framebuffer objects.
	 * <p>
	 * Must be called after the XR session is running and on the GL thread.
	 *
	 * @param session XrSession to create the swapchain on
	 * @param width   recommended image width (from XrViewConfigurationView)
	 * @param height  recommended image height (from XrViewConfigurationView)
	 * @param format  GL internal format to request (e.g. {@code GL_RGBA8} or {@code GL_SRGB8_ALPHA8})
	 */
	public void init(XrSession session, int width, int height, int format)
	{
		this.width = width;
		this.height = height;

		createSwapchain(session, width, height, format);
		createFbos(width, height);

		log.info("XrEyeSwapchain created — {}x{} format=0x{} images={}",
			width, height, Integer.toHexString(format), fbos.length);
	}

	/**
	 * Acquire the next swapchain image, wait for it to be available, and return
	 * the GL FBO handle backed by that image.
	 * <p>
	 * Must be paired with a subsequent {@link #releaseImage()} call.
	 *
	 * @return GL framebuffer object handle ready for rendering
	 */
	public int acquireImage()
	{
		try (MemoryStack stack = stackPush())
		{
			IntBuffer indexBuf = stack.mallocInt(1);
			XrSwapchainImageAcquireInfo acquireInfo = XrSwapchainImageAcquireInfo.calloc(stack)
				.type(XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO);
			checkXr("xrAcquireSwapchainImage", xrAcquireSwapchainImage(swapchain, acquireInfo, indexBuf));
			currentImageIndex = indexBuf.get(0);

			XrSwapchainImageWaitInfo waitInfo = XrSwapchainImageWaitInfo.calloc(stack)
				.type(XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO)
				.timeout(XR_INFINITE_DURATION);
			checkXr("xrWaitSwapchainImage", xrWaitSwapchainImage(swapchain, waitInfo));
		}
		return fbos[currentImageIndex];
	}

	/**
	 * Release the currently-acquired swapchain image back to the compositor.
	 * Must be called after rendering to the FBO returned by {@link #acquireImage()}.
	 */
	public void releaseImage()
	{
		try (MemoryStack stack = stackPush())
		{
			XrSwapchainImageReleaseInfo releaseInfo = XrSwapchainImageReleaseInfo.calloc(stack)
				.type(XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO);
			checkXr("xrReleaseSwapchainImage", xrReleaseSwapchainImage(swapchain, releaseInfo));
		}
		currentImageIndex = -1;
	}

	/**
	 * Destroy all GL objects and the XrSwapchain.
	 * Must be called on the GL thread before the GL context is destroyed.
	 */
	public void destroy()
	{
		if (fbos != null)
		{
			glDeleteFramebuffers(fbos);
			fbos = null;
		}
		if (depthRbo != 0)
		{
			glDeleteRenderbuffers(depthRbo);
			depthRbo = 0;
		}
		if (swapchain != null)
		{
			xrDestroySwapchain(swapchain);
			swapchain = null;
		}
	}

	// -------------------------------------------------------------------------
	// Private helpers
	// -------------------------------------------------------------------------

	private void createSwapchain(XrSession session, int width, int height, int format)
	{
		try (MemoryStack stack = stackPush())
		{
			XrSwapchainCreateInfo createInfo = XrSwapchainCreateInfo.calloc(stack)
				.type(XR_TYPE_SWAPCHAIN_CREATE_INFO)
				.usageFlags(XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT)
				.format(format)
				.sampleCount(1)
				.width(width)
				.height(height)
				.faceCount(1)
				.arraySize(1)
				.mipCount(1);

			PointerBuffer pp = stack.mallocPointer(1);
			checkXr("xrCreateSwapchain", xrCreateSwapchain(session, createInfo, pp));
			swapchain = new XrSwapchain(pp.get(0), session);
		}
	}

	private void createFbos(int width, int height)
	{
		// Enumerate swapchain images to get the GL texture names.
		int[] textures;
		try (MemoryStack stack = stackPush())
		{
			IntBuffer count = stack.mallocInt(1);
			checkXr("xrEnumerateSwapchainImages (count)",
				xrEnumerateSwapchainImages(swapchain, count, null));

			int imageCount = count.get(0);
			XrSwapchainImageOpenGLKHR.Buffer images =
				XrSwapchainImageOpenGLKHR.calloc(imageCount, stack);
			for (int i = 0; i < imageCount; i++)
			{
				images.get(i).type(XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_KHR);
			}

			checkXr("xrEnumerateSwapchainImages",
				xrEnumerateSwapchainImages(swapchain, count,
					XrSwapchainImageBaseHeader.create(images.address(), imageCount)));

			textures = new int[imageCount];
			for (int i = 0; i < imageCount; i++)
			{
				textures[i] = images.get(i).image();
			}
		}

		// Create a shared depth renderbuffer used by all FBOs.
		depthRbo = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, depthRbo);
		glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, width, height);
		glBindRenderbuffer(GL_RENDERBUFFER, 0);

		// Create one FBO per swapchain image.
		fbos = new int[textures.length];
		glGenFramebuffers(fbos);
		for (int i = 0; i < textures.length; i++)
		{
			glBindFramebuffer(GL_FRAMEBUFFER, fbos[i]);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
				GL_TEXTURE_2D, textures[i], 0);
			glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
				GL_RENDERBUFFER, depthRbo);

			int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
			if (status != GL_FRAMEBUFFER_COMPLETE)
			{
				throw new RuntimeException(
					"XrEyeSwapchain FBO[" + i + "] incomplete: 0x" + Integer.toHexString(status));
			}
		}
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
	}

	private static void checkXr(String call, int result)
	{
		if (result != XR_SUCCESS)
		{
			throw new RuntimeException(call + " failed with XrResult=" + result);
		}
	}
}
