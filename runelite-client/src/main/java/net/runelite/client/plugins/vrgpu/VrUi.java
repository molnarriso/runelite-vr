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
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.vrgpu;

import java.nio.FloatBuffer;
import net.runelite.client.plugins.vrgpu.template.Template;
import org.lwjgl.BufferUtils;
import org.lwjgl.openxr.XrFovf;
import org.lwjgl.openxr.XrPosef;
import org.lwjgl.openxr.XrView;
import static org.lwjgl.opengl.GL33C.*;

final class VrUi
{
	// --- Tunable first-pass canvas panel placement ---
	static final float PANEL_DISTANCE_M = 1.35f;
	static final float PANEL_WIDTH_M = 1.25f;
	static final float PANEL_HORIZONTAL_OFFSET_M = 0.0f;
	static final float PANEL_VERTICAL_OFFSET_M = 1.0f;

	private static final Shader PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vrui_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "vrui_frag.glsl");
	private static final int VERTEX_FLOATS = 5;
	private static final int VERTEX_COUNT = 4;

	private int program;
	private int vao;
	private int vbo;
	private int uniWorldProj;
	private int uniTex;
	private int uniAlphaOverlay;
	private final FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(VERTEX_COUNT * VERTEX_FLOATS);

	void init(Template template) throws ShaderException
	{
		program = PROGRAM.compile(template);
		uniWorldProj = glGetUniformLocation(program, "worldProj");
		uniTex = glGetUniformLocation(program, "tex");
		uniAlphaOverlay = glGetUniformLocation(program, "alphaOverlay");

		vao = glGenVertexArrays();
		vbo = glGenBuffers();

		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		glBufferData(GL_ARRAY_BUFFER, (long) VERTEX_COUNT * VERTEX_FLOATS * Float.BYTES, GL_STREAM_DRAW);

		glVertexAttribPointer(0, 3, GL_FLOAT, false, VERTEX_FLOATS * Float.BYTES, 0L);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(1, 2, GL_FLOAT, false, VERTEX_FLOATS * Float.BYTES, 3L * Float.BYTES);
		glEnableVertexAttribArray(1);

		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	void destroy()
	{
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

	void renderCanvasPanel(
		int framebuffer,
		int viewportWidth,
		int viewportHeight,
		XrView.Buffer views,
		int eye,
		int interfaceTexture,
		int canvasWidth,
		int canvasHeight,
		int overlayColor)
	{
		if (program == 0 || views == null || canvasWidth <= 0 || canvasHeight <= 0)
		{
			return;
		}

		updatePanelVertices(views, canvasWidth, canvasHeight);

		glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
		glViewport(0, 0, viewportWidth, viewportHeight);
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glDisable(GL_CULL_FACE);
		glEnable(GL_BLEND);
		glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

		glUseProgram(program);
		float[] worldProj = buildProjection(views.get(eye).fov(), views.get(eye).pose());
		glUniformMatrix4fv(uniWorldProj, false, worldProj);
		glUniform1i(uniTex, 0);
		glUniform4f(uniAlphaOverlay,
			(overlayColor >> 16 & 0xFF) / 255f,
			(overlayColor >> 8 & 0xFF) / 255f,
			(overlayColor & 0xFF) / 255f,
			(overlayColor >>> 24) / 255f
		);

		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

		glBindVertexArray(vao);
		glDrawArrays(GL_TRIANGLE_FAN, 0, VERTEX_COUNT);

		glBindVertexArray(0);
		glBindTexture(GL_TEXTURE_2D, 0);
		glUseProgram(0);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glDisable(GL_BLEND);
		glDepthMask(true);
	}

	private void updatePanelVertices(XrView.Buffer views, int canvasWidth, int canvasHeight)
	{
		float aspect = canvasWidth / (float) canvasHeight;
		float halfW = PANEL_WIDTH_M * 0.5f;
		float halfH = (PANEL_WIDTH_M / aspect) * 0.5f;
		float centerX = PANEL_HORIZONTAL_OFFSET_M;
		float centerY = PANEL_VERTICAL_OFFSET_M;
		float centerZ = -PANEL_DISTANCE_M;

		float[] vertices = {
			centerX - halfW, centerY + halfH, centerZ, 0f, 0f,
			centerX + halfW, centerY + halfH, centerZ, 1f, 0f,
			centerX + halfW, centerY - halfH, centerZ, 1f, 1f,
			centerX - halfW, centerY - halfH, centerZ, 0f, 1f,
		};

		vertexBuffer.clear();
		vertexBuffer.put(vertices);
		vertexBuffer.flip();

		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		glBufferSubData(GL_ARRAY_BUFFER, 0, vertexBuffer);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	private static float[] buildProjection(XrFovf fov, XrPosef pose)
	{
		float[] proj = buildVrProjection(fov, 0.05f);
		Mat4.mul(proj, buildInvEyePose(pose));
		return proj;
	}

	private static float[] buildVrProjection(XrFovf fov, float near)
	{
		float tanL = (float) Math.tan(fov.angleLeft());
		float tanR = (float) Math.tan(fov.angleRight());
		float tanU = (float) Math.tan(fov.angleUp());
		float tanD = (float) Math.tan(fov.angleDown());

		float a = 2.0f / (tanR - tanL);
		float b = (tanR + tanL) / (tanR - tanL);
		float c = 2.0f / (tanU - tanD);
		float d = (tanU + tanD) / (tanU - tanD);
		float n2 = 2.0f * near;

		return new float[]{
			a,  0,  0,  0,
			0,  c,  0,  0,
			b,  d,  0, -1,
			0,  0, n2,  0,
		};
	}

	private static float[] buildInvEyePose(XrPosef pose)
	{
		float qx = pose.orientation().x();
		float qy = pose.orientation().y();
		float qz = pose.orientation().z();
		float qw = pose.orientation().w();
		float px = pose.position$().x();
		float py = pose.position$().y();
		float pz = pose.position$().z();

		float r00 = 1 - 2 * (qy * qy + qz * qz);
		float r01 = 2 * (qx * qy + qw * qz);
		float r02 = 2 * (qx * qz - qw * qy);
		float r10 = 2 * (qx * qy - qw * qz);
		float r11 = 1 - 2 * (qx * qx + qz * qz);
		float r12 = 2 * (qy * qz + qw * qx);
		float r20 = 2 * (qx * qz + qw * qy);
		float r21 = 2 * (qy * qz - qw * qx);
		float r22 = 1 - 2 * (qx * qx + qy * qy);

		float tx = -(r00 * px + r01 * py + r02 * pz);
		float ty = -(r10 * px + r11 * py + r12 * pz);
		float tz = -(r20 * px + r21 * py + r22 * pz);

		return new float[]{
			r00, r10, r20, 0,
			r01, r11, r21, 0,
			r02, r12, r22, 0,
			tx,  ty,  tz,  1,
		};
	}
}
