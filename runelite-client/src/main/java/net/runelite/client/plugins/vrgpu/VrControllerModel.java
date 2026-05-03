/*
 * Copyright (c) 2024, RuneLiteVR contributors
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.vrgpu;

import java.nio.FloatBuffer;

final class VrControllerModel
{
	private static final float ALPHA = 0.96f;

	private VrControllerModel()
	{
	}

	static int put(
		FloatBuffer out,
		boolean left,
		float px, float py, float pz,
		float qx, float qy, float qz, float qw,
		float r, float g, float b,
		float worldScale,
		float stageOffsetY,
		float stageOffsetZ,
		float anchorWorldX,
		float anchorWorldY,
		float anchorWorldZ)
	{
		int start = out.position();
		float baseR = 0.09f;
		float baseG = 0.10f;
		float baseB = 0.12f;

		addBox(out, px, py, pz, qx, qy, qz, qw, worldScale, stageOffsetY, stageOffsetZ, anchorWorldX, anchorWorldY, anchorWorldZ,
			0f, 0f, -0.012f, 0.017f, 0.022f, 0.045f, baseR, baseG, baseB);
		addBox(out, px, py, pz, qx, qy, qz, qw, worldScale, stageOffsetY, stageOffsetZ, anchorWorldX, anchorWorldY, anchorWorldZ,
			0f, 0.002f, -0.064f, 0.013f, 0.016f, 0.014f, 0.18f, 0.18f, 0.20f);
		addBox(out, px, py, pz, qx, qy, qz, qw, worldScale, stageOffsetY, stageOffsetZ, anchorWorldX, anchorWorldY, anchorWorldZ,
			0f, 0.026f, -0.010f, 0.009f, 0.004f, 0.024f, 0.38f, 0.46f, 0.58f);
		addBox(out, px, py, pz, qx, qy, qz, qw, worldScale, stageOffsetY, stageOffsetZ, anchorWorldX, anchorWorldY, anchorWorldZ,
			0f, 0.001f, -0.086f, 0.006f, 0.006f, 0.010f, 0.90f, 0.24f, 0.18f);

		return out.position() - start;
	}

	private static void addBox(
		FloatBuffer out,
		float px, float py, float pz,
		float qx, float qy, float qz, float qw,
		float worldScale,
		float stageOffsetY,
		float stageOffsetZ,
		float anchorWorldX,
		float anchorWorldY,
		float anchorWorldZ,
		float cx, float cy, float cz,
		float hx, float hy, float hz,
		float r, float g, float b)
	{
		float[][] v = {
			{cx - hx, cy - hy, cz - hz}, {cx + hx, cy - hy, cz - hz}, {cx + hx, cy + hy, cz - hz}, {cx - hx, cy + hy, cz - hz},
			{cx - hx, cy - hy, cz + hz}, {cx + hx, cy - hy, cz + hz}, {cx + hx, cy + hy, cz + hz}, {cx - hx, cy + hy, cz + hz},
		};
		addFace(out, px, py, pz, qx, qy, qz, qw, worldScale, stageOffsetY, stageOffsetZ, anchorWorldX, anchorWorldY, anchorWorldZ, v, 0, 1, 2, 3, r * 1.20f, g * 1.20f, b * 1.20f);
		addFace(out, px, py, pz, qx, qy, qz, qw, worldScale, stageOffsetY, stageOffsetZ, anchorWorldX, anchorWorldY, anchorWorldZ, v, 5, 4, 7, 6, r * 0.78f, g * 0.78f, b * 0.78f);
		addFace(out, px, py, pz, qx, qy, qz, qw, worldScale, stageOffsetY, stageOffsetZ, anchorWorldX, anchorWorldY, anchorWorldZ, v, 3, 2, 6, 7, r * 1.05f, g * 1.05f, b * 1.05f);
		addFace(out, px, py, pz, qx, qy, qz, qw, worldScale, stageOffsetY, stageOffsetZ, anchorWorldX, anchorWorldY, anchorWorldZ, v, 4, 5, 1, 0, r * 0.70f, g * 0.70f, b * 0.70f);
		addFace(out, px, py, pz, qx, qy, qz, qw, worldScale, stageOffsetY, stageOffsetZ, anchorWorldX, anchorWorldY, anchorWorldZ, v, 1, 5, 6, 2, r * 0.92f, g * 0.92f, b * 0.92f);
		addFace(out, px, py, pz, qx, qy, qz, qw, worldScale, stageOffsetY, stageOffsetZ, anchorWorldX, anchorWorldY, anchorWorldZ, v, 4, 0, 3, 7, r * 0.86f, g * 0.86f, b * 0.86f);
	}

	private static void addFace(
		FloatBuffer out,
		float px, float py, float pz,
		float qx, float qy, float qz, float qw,
		float worldScale,
		float stageOffsetY,
		float stageOffsetZ,
		float anchorWorldX,
		float anchorWorldY,
		float anchorWorldZ,
		float[][] v,
		int a, int b, int c, int d,
		float r, float g, float blue)
	{
		putVertex(out, px, py, pz, qx, qy, qz, qw, worldScale, stageOffsetY, stageOffsetZ, anchorWorldX, anchorWorldY, anchorWorldZ, v[a], r, g, blue);
		putVertex(out, px, py, pz, qx, qy, qz, qw, worldScale, stageOffsetY, stageOffsetZ, anchorWorldX, anchorWorldY, anchorWorldZ, v[b], r, g, blue);
		putVertex(out, px, py, pz, qx, qy, qz, qw, worldScale, stageOffsetY, stageOffsetZ, anchorWorldX, anchorWorldY, anchorWorldZ, v[c], r, g, blue);
		putVertex(out, px, py, pz, qx, qy, qz, qw, worldScale, stageOffsetY, stageOffsetZ, anchorWorldX, anchorWorldY, anchorWorldZ, v[a], r, g, blue);
		putVertex(out, px, py, pz, qx, qy, qz, qw, worldScale, stageOffsetY, stageOffsetZ, anchorWorldX, anchorWorldY, anchorWorldZ, v[c], r, g, blue);
		putVertex(out, px, py, pz, qx, qy, qz, qw, worldScale, stageOffsetY, stageOffsetZ, anchorWorldX, anchorWorldY, anchorWorldZ, v[d], r, g, blue);
	}

	private static void putVertex(
		FloatBuffer out,
		float px, float py, float pz,
		float qx, float qy, float qz, float qw,
		float worldScale,
		float stageOffsetY,
		float stageOffsetZ,
		float anchorWorldX,
		float anchorWorldY,
		float anchorWorldZ,
		float[] local,
		float r, float g, float b)
	{
		if (out.remaining() < 7)
		{
			return;
		}
		float tx = 2f * (qy * local[2] - qz * local[1]);
		float ty = 2f * (qz * local[0] - qx * local[2]);
		float tz = 2f * (qx * local[1] - qy * local[0]);
		float rx = local[0] + qw * tx + (qy * tz - qz * ty);
		float ry = local[1] + qw * ty + (qz * tx - qx * tz);
		float rz = local[2] + qw * tz + (qx * ty - qy * tx);

		float wx = anchorWorldX - (px + rx) / worldScale;
		float wy = anchorWorldY - ((py + ry) - stageOffsetY) / worldScale;
		float wz = anchorWorldZ + ((pz + rz) - stageOffsetZ) / worldScale;
		out.put(wx).put(wy).put(wz)
			.put(Math.min(r, 1f)).put(Math.min(g, 1f)).put(Math.min(b, 1f)).put(ALPHA);
	}
}
