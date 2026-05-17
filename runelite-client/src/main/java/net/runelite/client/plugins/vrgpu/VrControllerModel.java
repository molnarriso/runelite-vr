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
	// One controller mesh definition shared by world-space and stage-space rendering paths.
	private static final float[][] BOXES = {
		{0f, 0f, -0.012f, 0.017f, 0.022f, 0.045f, 0.09f, 0.10f, 0.12f},
		{0f, 0.002f, -0.064f, 0.013f, 0.016f, 0.014f, 0.18f, 0.18f, 0.20f},
		{0f, 0.026f, -0.010f, 0.009f, 0.004f, 0.024f, 0.38f, 0.46f, 0.58f},
		{0f, 0.001f, -0.086f, 0.006f, 0.006f, 0.010f, 0.90f, 0.24f, 0.18f},
	};

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
		float cameraYaw,
		float anchorWorldX,
		float anchorWorldY,
		float anchorWorldZ)
	{
		float yawSin = (float) Math.sin(cameraYaw);
		float yawCos = (float) Math.cos(cameraYaw);
		// Final sink converts OpenXR stage meters into OSRS world coordinates.
		return putMesh(out, px, py, pz, qx, qy, qz, qw,
			(x, y, z, red, green, blue) ->
			{
				float localX = x * yawCos - (z - stageOffsetZ) * yawSin;
				float localZ = x * yawSin + (z - stageOffsetZ) * yawCos;
				float wx = anchorWorldX - localX / worldScale;
				float wy = anchorWorldY - (y - stageOffsetY) / worldScale;
				float wz = anchorWorldZ + localZ / worldScale;
				putRawVertex(out, wx, wy, wz, red, green, blue);
			});
	}

	static int putStage(
		FloatBuffer out,
		boolean left,
		float px, float py, float pz,
		float qx, float qy, float qz, float qw,
		float r, float g, float b)
	{
		// Splash/menu spectator frames have no OSRS world transform, so vertices stay in stage space.
		return putMesh(out, px, py, pz, qx, qy, qz, qw,
			(x, y, z, red, green, blue) -> putRawVertex(out, x, y, z, red, green, blue));
	}

	static int putEndpointCube(FloatBuffer out, float x, float y, float z, float sizeWorld, float r, float g, float b)
	{
		int start = out.position();
		float h = sizeWorld * 0.5f;
		float[][] v = {
			{x - h, y - h, z - h}, {x + h, y - h, z - h}, {x + h, y + h, z - h}, {x - h, y + h, z - h},
			{x - h, y - h, z + h}, {x + h, y - h, z + h}, {x + h, y + h, z + h}, {x - h, y + h, z + h},
		};
		addWorldFace(out, v, 0, 1, 2, 3, r * 1.20f, g * 1.20f, b * 1.20f);
		addWorldFace(out, v, 5, 4, 7, 6, r * 0.78f, g * 0.78f, b * 0.78f);
		addWorldFace(out, v, 3, 2, 6, 7, r * 1.05f, g * 1.05f, b * 1.05f);
		addWorldFace(out, v, 4, 5, 1, 0, r * 0.70f, g * 0.70f, b * 0.70f);
		addWorldFace(out, v, 1, 5, 6, 2, r * 0.92f, g * 0.92f, b * 0.92f);
		addWorldFace(out, v, 4, 0, 3, 7, r * 0.86f, g * 0.86f, b * 0.86f);
		return out.position() - start;
	}

	private static int putMesh(FloatBuffer out,
		float px, float py, float pz,
		float qx, float qy, float qz, float qw,
		VertexSink sink)
	{
		// Geometry generation is intentionally shared; only VertexSink decides the target space.
		int start = out.position();
		for (float[] box : BOXES)
		{
			addBox(out, px, py, pz, qx, qy, qz, qw,
				box[0], box[1], box[2],
				box[3], box[4], box[5],
				box[6], box[7], box[8],
				sink);
		}
		return out.position() - start;
	}

	private static void addBox(
		FloatBuffer out,
		float px, float py, float pz,
		float qx, float qy, float qz, float qw,
		float cx, float cy, float cz,
		float hx, float hy, float hz,
		float r, float g, float b,
		VertexSink sink)
	{
		float[][] v = {
			{cx - hx, cy - hy, cz - hz}, {cx + hx, cy - hy, cz - hz}, {cx + hx, cy + hy, cz - hz}, {cx - hx, cy + hy, cz - hz},
			{cx - hx, cy - hy, cz + hz}, {cx + hx, cy - hy, cz + hz}, {cx + hx, cy + hy, cz + hz}, {cx - hx, cy + hy, cz + hz},
		};
		addFace(out, px, py, pz, qx, qy, qz, qw, v, 0, 1, 2, 3, r * 1.20f, g * 1.20f, b * 1.20f, sink);
		addFace(out, px, py, pz, qx, qy, qz, qw, v, 5, 4, 7, 6, r * 0.78f, g * 0.78f, b * 0.78f, sink);
		addFace(out, px, py, pz, qx, qy, qz, qw, v, 3, 2, 6, 7, r * 1.05f, g * 1.05f, b * 1.05f, sink);
		addFace(out, px, py, pz, qx, qy, qz, qw, v, 4, 5, 1, 0, r * 0.70f, g * 0.70f, b * 0.70f, sink);
		addFace(out, px, py, pz, qx, qy, qz, qw, v, 1, 5, 6, 2, r * 0.92f, g * 0.92f, b * 0.92f, sink);
		addFace(out, px, py, pz, qx, qy, qz, qw, v, 4, 0, 3, 7, r * 0.86f, g * 0.86f, b * 0.86f, sink);
	}

	private static void addFace(
		FloatBuffer out,
		float px, float py, float pz,
		float qx, float qy, float qz, float qw,
		float[][] v,
		int a, int b, int c, int d,
		float r, float g, float blue,
		VertexSink sink)
	{
		putVertex(out, px, py, pz, qx, qy, qz, qw, v[a], r, g, blue, sink);
		putVertex(out, px, py, pz, qx, qy, qz, qw, v[b], r, g, blue, sink);
		putVertex(out, px, py, pz, qx, qy, qz, qw, v[c], r, g, blue, sink);
		putVertex(out, px, py, pz, qx, qy, qz, qw, v[a], r, g, blue, sink);
		putVertex(out, px, py, pz, qx, qy, qz, qw, v[c], r, g, blue, sink);
		putVertex(out, px, py, pz, qx, qy, qz, qw, v[d], r, g, blue, sink);
	}

	private static void putVertex(
		FloatBuffer out,
		float px, float py, float pz,
		float qx, float qy, float qz, float qw,
		float[] local,
		float r, float g, float b,
		VertexSink sink)
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
		sink.put(px + rx, py + ry, pz + rz, r, g, b);
	}

	private static void addWorldFace(FloatBuffer out, float[][] v, int a, int b, int c, int d, float r, float g, float blue)
	{
		putWorldVertex(out, v[a], r, g, blue);
		putWorldVertex(out, v[b], r, g, blue);
		putWorldVertex(out, v[c], r, g, blue);
		putWorldVertex(out, v[a], r, g, blue);
		putWorldVertex(out, v[c], r, g, blue);
		putWorldVertex(out, v[d], r, g, blue);
	}

	private static void putWorldVertex(FloatBuffer out, float[] world, float r, float g, float b)
	{
		if (out.remaining() < 7)
		{
			return;
		}
		out.put(world[0]).put(world[1]).put(world[2])
			.put(Math.min(r, 1f)).put(Math.min(g, 1f)).put(Math.min(b, 1f)).put(ALPHA);
	}

	private static void putRawVertex(FloatBuffer out, float x, float y, float z, float r, float g, float b)
	{
		if (out.remaining() < 7)
		{
			return;
		}
		out.put(x).put(y).put(z)
			.put(Math.min(r, 1f)).put(Math.min(g, 1f)).put(Math.min(b, 1f)).put(ALPHA);
	}

	private interface VertexSink
	{
		// Receives a fully posed controller vertex in OpenXR stage space or converted world space.
		void put(float x, float y, float z, float r, float g, float b);
	}
}
