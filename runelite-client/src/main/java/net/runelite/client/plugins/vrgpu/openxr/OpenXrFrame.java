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
import org.lwjgl.openxr.XrView;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Owns the per-frame OpenXR lifecycle above the raw context and per-eye
 * swapchains: begin, locate views, acquire both eye images, release, and submit.
 */
public class OpenXrFrame
{
	private final XrContext context;
	private final XrInput input;
	private final XrEyeSwapchain[] eyeSwapchains = new XrEyeSwapchain[2];
	private Frame currentFrame;

	public OpenXrFrame(XrContext context, XrInput input)
	{
		this.context = context;
		this.input = input;
	}

	public void initEyeSwapchains(int format)
	{
		for (int eye = 0; eye < 2; eye++)
		{
			eyeSwapchains[eye] = new XrEyeSwapchain();
			eyeSwapchains[eye].init(
				context.getInstance(),
				context.getSession(),
				context.getEyeWidth()[eye],
				context.getEyeHeight()[eye],
				format);
		}
	}

	public void begin()
	{
		boolean shouldRender = context.beginXrFrame();
		if (!shouldRender)
		{
			currentFrame = new Frame(false, null, -1, -1);
			return;
		}

		XrView.Buffer views = context.locateViews();
		if (input != null)
		{
			input.sync(context.getSession(), context.getStageSpace(), context.getPendingDisplayTime());
		}

		Frame frame = new Frame(true, views, -1, -1);
		try
		{
			frame.leftFbo = eyeSwapchains[0].acquireImage();
			frame.rightFbo = eyeSwapchains[1].acquireImage();
			currentFrame = frame;
		}
		catch (RuntimeException ex)
		{
			release(frame);
			throw ex;
		}
	}

	public boolean hasFrame()
	{
		return currentFrame != null;
	}

	public boolean isRendering()
	{
		return currentFrame != null && currentFrame.shouldRender && currentFrame.hasEyeImages();
	}

	public XrView.Buffer views()
	{
		return currentFrame != null ? currentFrame.views : null;
	}

	public int fbo(int eye)
	{
		return currentFrame != null ? currentFrame.fbo(eye) : -1;
	}

	public void clearEyes()
	{
		if (!isRendering())
		{
			return;
		}
		for (int eye = 0; eye < 2; eye++)
		{
			glBindFramebuffer(GL_FRAMEBUFFER, currentFrame.fbo(eye));
			glViewport(0, 0, width(eye), height(eye));
			glDisable(GL_SCISSOR_TEST);
			glDisable(GL_DEPTH_TEST);
			glClearColor(0, 0, 0, 1);
			glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
		}
	}

	public void submit()
	{
		Frame frame = currentFrame;
		if (frame == null)
		{
			return;
		}

		currentFrame = null;
		if (frame.shouldRender && frame.hasEyeImages() && frame.views != null)
		{
			submitStereo(frame);
		}
		else
		{
			submitEmpty(frame);
		}
	}

	public void submitEmpty()
	{
		Frame frame = currentFrame;
		if (frame == null)
		{
			return;
		}

		currentFrame = null;
		submitEmpty(frame);
	}

	private void submitStereo(Frame frame)
	{
		release(frame);
		context.endXrFrameStereo(
			frame.views,
			eyeSwapchains[0].getSwapchain(),
			eyeSwapchains[1].getSwapchain());
	}

	private void submitEmpty(Frame frame)
	{
		release(frame);
		context.endXrFrame();
	}

	public void release(Frame frame)
	{
		if (frame == null)
		{
			return;
		}
		if (frame.rightFbo != -1)
		{
			eyeSwapchains[1].releaseImage();
			frame.rightFbo = -1;
		}
		if (frame.leftFbo != -1)
		{
			eyeSwapchains[0].releaseImage();
			frame.leftFbo = -1;
		}
	}

	public int width(int eye)
	{
		return eyeSwapchains[eye].getWidth();
	}

	public int height(int eye)
	{
		return eyeSwapchains[eye].getHeight();
	}

	public void destroy()
	{
		release(currentFrame);
		currentFrame = null;
		for (int eye = 0; eye < 2; eye++)
		{
			if (eyeSwapchains[eye] != null)
			{
				eyeSwapchains[eye].destroy();
				eyeSwapchains[eye] = null;
			}
		}
	}

	@Getter
	public static final class Frame
	{
		private final boolean shouldRender;
		private final XrView.Buffer views;
		private int leftFbo;
		private int rightFbo;

		private Frame(boolean shouldRender, XrView.Buffer views, int leftFbo, int rightFbo)
		{
			this.shouldRender = shouldRender;
			this.views = views;
			this.leftFbo = leftFbo;
			this.rightFbo = rightFbo;
		}

		public boolean hasEyeImages()
		{
			return leftFbo != -1 && rightFbo != -1;
		}

		public int fbo(int eye)
		{
			return eye == 0 ? leftFbo : rightFbo;
		}
	}
}
