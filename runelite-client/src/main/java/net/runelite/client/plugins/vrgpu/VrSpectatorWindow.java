/*
 * Copyright (c) 2024, RuneLiteVR contributors
 * All rights reserved.
 */
package net.runelite.client.plugins.vrgpu;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import static org.lwjgl.opengl.GL33C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL33C.GL_PACK_ALIGNMENT;
import static org.lwjgl.opengl.GL33C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL33C.GL_RGBA;
import static org.lwjgl.opengl.GL33C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL33C.glBindFramebuffer;
import static org.lwjgl.opengl.GL33C.glPixelStorei;
import static org.lwjgl.opengl.GL33C.glReadBuffer;
import static org.lwjgl.opengl.GL33C.glReadPixels;

final class VrSpectatorWindow
{
	private JFrame frame;
	private ImagePanel panel;
	private BufferedImage image;
	private ByteBuffer pixels;
	private int[] argb;
	private int width;
	private int height;
	private int sourceWidth;
	private int sourceHeight;
	private int displayWidth;
	private int displayHeight;

	void present(int framebuffer, int sourceWidth, int sourceHeight, float aspectRatio,
		float cropLeft, float cropRight, float cropTop, float cropBottom,
		int requestedDisplayWidth, int requestedDisplayHeight)
	{
		if (framebuffer == -1 || sourceWidth <= 0 || sourceHeight <= 0)
		{
			return;
		}

		Crop crop = computeCrop(sourceWidth, sourceHeight, aspectRatio,
			cropLeft, cropRight, cropTop, cropBottom);
		int cropX = crop.x;
		int cropY = crop.y;
		int cropW = crop.width;
		int cropH = crop.height;
		if (cropW <= 0 || cropH <= 0)
		{
			return;
		}

		// Read back the native spectator eye image, then crop/scale only for desktop capture.
		int targetDisplayWidth = requestedDisplayWidth > 0 ? requestedDisplayWidth : cropW;
		int targetDisplayHeight = requestedDisplayHeight > 0 ? requestedDisplayHeight : cropH;
		ensureBuffers(sourceWidth, sourceHeight, cropW, cropH, targetDisplayWidth, targetDisplayHeight);

		pixels.clear();
		glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
		glReadBuffer(GL_COLOR_ATTACHMENT0);
		glPixelStorei(GL_PACK_ALIGNMENT, 1);
		glReadPixels(0, 0, sourceWidth, sourceHeight, GL_RGBA, GL_UNSIGNED_BYTE, pixels);

		for (int y = 0; y < cropH; y++)
		{
			int srcRow = (sourceHeight - 1 - (cropY + y)) * sourceWidth * 4;
			int dstRow = y * cropW;
			for (int x = 0; x < cropW; x++)
			{
				int src = srcRow + (cropX + x) * 4;
				int r = pixels.get(src) & 0xff;
				int g = pixels.get(src + 1) & 0xff;
				int b = pixels.get(src + 2) & 0xff;
				int a = pixels.get(src + 3) & 0xff;
				argb[dstRow + x] = (a << 24) | (r << 16) | (g << 8) | b;
			}
		}

		image.setRGB(0, 0, cropW, cropH, argb, 0, cropW);
		SwingUtilities.invokeLater(() ->
		{
			ensureWindow();
			panel.repaint();
		});
	}

	void close()
	{
		JFrame closing = frame;
		frame = null;
		panel = null;
		image = null;
		pixels = null;
		argb = null;
		width = 0;
		height = 0;
		sourceWidth = 0;
		sourceHeight = 0;
		displayWidth = 0;
		displayHeight = 0;
		if (closing != null)
		{
			SwingUtilities.invokeLater(closing::dispose);
		}
	}

	private void ensureBuffers(int newSourceWidth, int newSourceHeight, int outputWidth, int outputHeight,
		int newDisplayWidth, int newDisplayHeight)
	{
		if (image != null && sourceWidth == newSourceWidth && sourceHeight == newSourceHeight
			&& width == outputWidth && height == outputHeight
			&& displayWidth == newDisplayWidth && displayHeight == newDisplayHeight)
		{
			return;
		}

		sourceWidth = newSourceWidth;
		sourceHeight = newSourceHeight;
		width = outputWidth;
		height = outputHeight;
		displayWidth = newDisplayWidth;
		displayHeight = newDisplayHeight;
		image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		pixels = ByteBuffer.allocateDirect(sourceWidth * sourceHeight * 4);
		argb = new int[width * height];

		SwingUtilities.invokeLater(() ->
		{
			ensureWindow();
			panel.setPreferredSize(new Dimension(displayWidth, displayHeight));
			frame.pack();
		});
	}

	private void ensureWindow()
	{
		if (frame != null && frame.isDisplayable())
		{
			if (!frame.isVisible())
			{
				frame.setVisible(true);
			}
			return;
		}

		panel = new ImagePanel();
		panel.setPreferredSize(new Dimension(displayWidth, displayHeight));
		frame = new JFrame("RuneLite VR Spectator");
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		frame.setContentPane(panel);
		frame.pack();
		frame.setLocationByPlatform(true);
		frame.setVisible(true);
	}

	private static float clamp01(float value)
	{
		return Math.max(0f, Math.min(0.95f, value));
	}

	private static Crop computeCrop(int sourceWidth, int sourceHeight, float aspectRatio,
		float cropLeft, float cropRight, float cropTop, float cropBottom)
	{
		// First letterbox-crop to the requested capture ratio, then apply small user crop margins.
		int baseX = 0;
		int baseY = 0;
		int baseW = sourceWidth;
		int baseH = sourceHeight;
		if (aspectRatio > 0f)
		{
			float sourceAspect = sourceWidth / (float) sourceHeight;
			if (sourceAspect > aspectRatio)
			{
				baseW = Math.max(1, Math.round(sourceHeight * aspectRatio));
				baseX = (sourceWidth - baseW) / 2;
			}
			else if (sourceAspect < aspectRatio)
			{
				baseH = Math.max(1, Math.round(sourceWidth / aspectRatio));
				baseY = (sourceHeight - baseH) / 2;
			}
		}

		int left = Math.round(baseW * clamp01(cropLeft));
		int right = Math.round(baseW * clamp01(cropRight));
		int top = Math.round(baseH * clamp01(cropTop));
		int bottom = Math.round(baseH * clamp01(cropBottom));
		return new Crop(baseX + left, baseY + bottom, baseW - left - right, baseH - top - bottom);
	}

	private static final class Crop
	{
		final int x;
		final int y;
		final int width;
		final int height;

		private Crop(int x, int y, int width, int height)
		{
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}
	}

	private final class ImagePanel extends JPanel
	{
		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			BufferedImage current = image;
			if (current != null)
			{
				g.drawImage(current, 0, 0, getWidth(), getHeight(), null);
			}
		}
	}
}
