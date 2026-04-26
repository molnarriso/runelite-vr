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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import net.runelite.client.plugins.vrgpu.template.Template;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

final class VrBillboardRenderer
{
	private static final Shader PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vrbillboard_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "vrbillboard_frag.glsl");
	private static final int VERTEX_FLOATS = 9;
	private static final int QUAD_VERTEX_COUNT = 6;

	private final FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(QUAD_VERTEX_COUNT * VERTEX_FLOATS);
	private final List<Billboard> billboards = new ArrayList<>(64);

	private int program;
	private int vao;
	private int vbo;
	private int uniWorldProj;
	private int uniTex;
	private int uniUseTexture;

	static final class Billboard
	{
		float x;
		float y;
		float z;
		float widthM;
		float heightM;
		float localOffsetXM;
		float localOffsetYM;
		float red = 1f;
		float green = 1f;
		float blue = 1f;
		float alpha = 1f;
		int textureId;
	}

	void init(Template template) throws ShaderException
	{
		program = PROGRAM.compile(template);
		uniWorldProj = glGetUniformLocation(program, "worldProj");
		uniTex = glGetUniformLocation(program, "tex");
		uniUseTexture = glGetUniformLocation(program, "useTexture");

		vao = glGenVertexArrays();
		vbo = glGenBuffers();

		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		glBufferData(GL_ARRAY_BUFFER, (long) QUAD_VERTEX_COUNT * VERTEX_FLOATS * Float.BYTES, GL_STREAM_DRAW);

		glVertexAttribPointer(0, 3, GL_FLOAT, false, VERTEX_FLOATS * Float.BYTES, 0L);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(1, 2, GL_FLOAT, false, VERTEX_FLOATS * Float.BYTES, 3L * Float.BYTES);
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(2, 4, GL_FLOAT, false, VERTEX_FLOATS * Float.BYTES, 5L * Float.BYTES);
		glEnableVertexAttribArray(2);

		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	void destroy()
	{
		clear();
		if (vbo != 0)
		{
			glDeleteBuffers(vbo);
			vbo = 0;
		}
		if (vao != 0)
		{
			glDeleteVertexArrays(vao);
			vao = 0;
		}
		if (program != 0)
		{
			glDeleteProgram(program);
			program = 0;
		}
	}

	void clear()
	{
		for (Billboard billboard : billboards)
		{
			if (billboard.textureId != 0)
			{
				glDeleteTextures(billboard.textureId);
			}
		}
		billboards.clear();
	}

	void addImage(float x, float y, float z, float widthM, float heightM, float offsetXM, float offsetYM, int[] argbPixels, int width, int height)
	{
		if (program == 0 || width <= 0 || height <= 0 || argbPixels.length < width * height)
		{
			return;
		}

		Billboard billboard = new Billboard();
		billboard.x = x;
		billboard.y = y;
		billboard.z = z;
		billboard.widthM = widthM;
		billboard.heightM = heightM;
		billboard.localOffsetXM = offsetXM;
		billboard.localOffsetYM = offsetYM;
		billboard.textureId = uploadTexture(argbPixels, width, height);
		billboards.add(billboard);
	}

	void render(
		int framebuffer,
		int viewportWidth,
		int viewportHeight,
		float[] worldProj,
		float centerEyeX,
		float centerEyeY,
		float centerEyeZ,
		float worldScale,
		float stageOffsetY,
		float stageOffsetZ,
		float anchorWorldX,
		float anchorWorldY,
		float anchorWorldZ)
	{
		if (program == 0 || billboards.isEmpty())
		{
			return;
		}

		glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
		glViewport(0, 0, viewportWidth, viewportHeight);
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glDisable(GL_CULL_FACE);
		glEnable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

		glUseProgram(program);
		glUniformMatrix4fv(uniWorldProj, false, worldProj);
		glUniform1i(uniTex, 0);
		glBindVertexArray(vao);

		for (Billboard billboard : billboards)
		{
			drawBillboard(billboard, centerEyeX, centerEyeY, centerEyeZ, worldScale, stageOffsetY, stageOffsetZ, anchorWorldX, anchorWorldY, anchorWorldZ);
		}

		glBindVertexArray(0);
		glBindTexture(GL_TEXTURE_2D, 0);
		glUseProgram(0);
		glDisable(GL_BLEND);
		glDepthMask(true);
	}

	private void drawBillboard(
		Billboard billboard,
		float eyeX,
		float eyeY,
		float eyeZ,
		float worldScale,
		float stageOffsetY,
		float stageOffsetZ,
		float anchorWorldX,
		float anchorWorldY,
		float anchorWorldZ)
	{
		float stageX = -(billboard.x - anchorWorldX) * worldScale;
		float stageY = -(billboard.y - anchorWorldY) * worldScale + stageOffsetY;
		float stageZ = (billboard.z - anchorWorldZ) * worldScale + stageOffsetZ;

		float fx = eyeX - stageX;
		float fy = eyeY - stageY;
		float fz = eyeZ - stageZ;
		float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
		if (fl < 1e-5f)
		{
			fx = 0f;
			fy = 0f;
			fz = 1f;
		}
		else
		{
			fx /= fl;
			fy /= fl;
			fz /= fl;
		}

		float rx = fz;
		float ry = 0f;
		float rz = -fx;
		float rl = (float) Math.sqrt(rx * rx + rz * rz);
		if (rl < 1e-5f)
		{
			rx = 1f;
			rz = 0f;
		}
		else
		{
			rx /= rl;
			rz /= rl;
		}

		float ux = 0f;
		float uy = 1f;
		float uz = 0f;

		putQuadVertex(billboard, -0.5f, -0.5f, 0f, 1f, rx, ry, rz, ux, uy, uz, worldScale);
		putQuadVertex(billboard, 0.5f, -0.5f, 1f, 1f, rx, ry, rz, ux, uy, uz, worldScale);
		putQuadVertex(billboard, 0.5f, 0.5f, 1f, 0f, rx, ry, rz, ux, uy, uz, worldScale);
		putQuadVertex(billboard, -0.5f, -0.5f, 0f, 1f, rx, ry, rz, ux, uy, uz, worldScale);
		putQuadVertex(billboard, 0.5f, 0.5f, 1f, 0f, rx, ry, rz, ux, uy, uz, worldScale);
		putQuadVertex(billboard, -0.5f, 0.5f, 0f, 0f, rx, ry, rz, ux, uy, uz, worldScale);
		vertexBuffer.flip();

		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		glBufferSubData(GL_ARRAY_BUFFER, 0, vertexBuffer);
		glUniform1i(uniUseTexture, 1);
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, billboard.textureId);
		glDrawArrays(GL_TRIANGLES, 0, QUAD_VERTEX_COUNT);
		vertexBuffer.clear();
	}

	private void putQuadVertex(
		Billboard billboard,
		float xMul,
		float yMul,
		float u,
		float v,
		float rx,
		float ry,
		float rz,
		float ux,
		float uy,
		float uz,
		float worldScale)
	{
		float localX = billboard.localOffsetXM + xMul * billboard.widthM;
		float localY = billboard.localOffsetYM + yMul * billboard.heightM;
		float stageDx = rx * localX + ux * localY;
		float stageDy = ry * localX + uy * localY;
		float stageDz = rz * localX + uz * localY;

		vertexBuffer
			.put(billboard.x - stageDx / worldScale)
			.put(billboard.y - stageDy / worldScale)
			.put(billboard.z + stageDz / worldScale)
			.put(u).put(v)
			.put(billboard.red).put(billboard.green).put(billboard.blue).put(billboard.alpha);
	}

	private static int uploadTexture(int[] argbPixels, int width, int height)
	{
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
		for (int pixel : argbPixels)
		{
			buffer.put((byte) ((pixel >> 16) & 0xff));
			buffer.put((byte) ((pixel >> 8) & 0xff));
			buffer.put((byte) (pixel & 0xff));
			buffer.put((byte) ((pixel >>> 24) & 0xff));
		}
		buffer.flip();

		int textureId = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, textureId);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
		return textureId;
	}
}
