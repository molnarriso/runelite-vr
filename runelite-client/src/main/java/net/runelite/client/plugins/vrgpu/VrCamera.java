/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice
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

import java.util.Arrays;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Projection;
import org.lwjgl.openxr.XrFovf;
import org.lwjgl.openxr.XrPosef;
import org.lwjgl.openxr.XrView;

final class VrCamera
{
	private final Pose[] spectatorEyePoses = new Pose[2];

	void reset()
	{
		Arrays.fill(spectatorEyePoses, null);
	}

	Pose livePose(XrPosef livePose)
	{
		// HMD rendering must always use the current OpenXR pose directly.
		return Pose.from(livePose);
	}

	Pose smoothedEyePose(int eye, XrPosef livePose, float smoothing)
	{
		// Spectator-only temporal smoothing; this stored pose never feeds input or HMD rendering.
		Pose live = Pose.from(livePose);
		Pose smoothed = spectatorEyePoses[eye];
		if (smoothed == null)
		{
			spectatorEyePoses[eye] = live;
			return live;
		}

		float keepPrevious = Math.max(0f, Math.min(0.99f, smoothing));
		smoothed.lerpTo(live, 1f - keepPrevious);
		return smoothed;
	}

	float[] computeVrWorldProj(XrView view, Pose pose,
		float worldAnchorY, float worldScale, float stageCharacterOffsetZ,
		float anchorWorldX, float anchorWorldY, float anchorWorldZ)
	{
		// Stage-space eye projection first, then the shared OSRS-world anchor transform.
		float[] proj = computeStageProjection(view, pose);
		Mat4.mul(proj, Mat4.translate(0f, worldAnchorY, stageCharacterOffsetZ));
		Mat4.mul(proj, Mat4.scale(-worldScale, -worldScale, worldScale));
		Mat4.mul(proj, Mat4.translate(-anchorWorldX, -anchorWorldY, -anchorWorldZ));
		return proj;
	}

	float[] computeStageProjection(XrView view, Pose pose)
	{
		// Used for UI-only frames where there is no world replay, such as splash/menu.
		float[] proj = buildVrProjection(view.fov(), 0.05f);
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

	private static float[] buildInvEyePose(Pose pose)
	{
		float qx = pose.qx;
		float qy = pose.qy;
		float qz = pose.qz;
		float qw = pose.qw;
		float px = pose.x;
		float py = pose.y;
		float pz = pose.z;

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

	Projection buildVrSorterProjection(XrView view, Pose pose,
		float worldAnchorY, float worldScale, float stageCharacterOffsetZ,
		float anchorWorldX, float anchorWorldY, float anchorWorldZ)
	{
		// Mirrors computeVrWorldProj for billboard sorting without allocating a full matrix per point.
		XrFovf fov = view.fov();

		float qx = pose.qx;
		float qy = pose.qy;
		float qz = pose.qz;
		float qw = pose.qw;
		float px = pose.x;
		float py = pose.y;
		float pz = pose.z;

		final float r00 = 1 - 2 * (qy * qy + qz * qz);
		final float r01 = 2 * (qx * qy + qw * qz);
		final float r02 = 2 * (qx * qz - qw * qy);
		final float r10 = 2 * (qx * qy - qw * qz);
		final float r11 = 1 - 2 * (qx * qx + qz * qz);
		final float r12 = 2 * (qy * qz + qw * qx);
		final float r20 = 2 * (qx * qz + qw * qy);
		final float r21 = 2 * (qy * qz - qw * qx);
		final float r22 = 1 - 2 * (qx * qx + qy * qy);

		final float tx = -(r00 * px + r01 * py + r02 * pz);
		final float ty = -(r10 * px + r11 * py + r12 * pz);
		final float tz = -(r20 * px + r21 * py + r22 * pz);

		float tanL = (float) Math.tan(fov.angleLeft());
		float tanR = (float) Math.tan(fov.angleRight());
		float tanU = (float) Math.tan(fov.angleUp());
		float tanD = (float) Math.tan(fov.angleDown());
		final float a = 2f / (tanR - tanL);
		final float b = (tanR + tanL) / (tanR - tanL);
		final float c = 2f / (tanU - tanD);
		final float d = (tanU + tanD) / (tanU - tanD);

		return new Projection()
		{
			@Override
			public float[] project(float wx, float wy, float wz)
			{
				return project(wx, wy, wz, new float[3]);
			}

			@Override
			public float[] project(float wx, float wy, float wz, float[] out)
			{
				float vx = -(wx - anchorWorldX) * worldScale;
				float vy = -(wy - anchorWorldY) * worldScale + worldAnchorY;
				float vz = (wz - anchorWorldZ) * worldScale + stageCharacterOffsetZ;

				float ex = r00 * vx + r01 * vy + r02 * vz + tx;
				float ey = r10 * vx + r11 * vy + r12 * vz + ty;
				float ez = r20 * vx + r21 * vy + r22 * vz + tz;
				float clipW = -ez;

				out[0] = -(a * ex + b * ez);
				out[1] = c * ey + d * ez;
				out[2] = clipW / worldScale;
				return out;
			}
		};
	}

	static Projection buildDesktopSorterProjection(float cameraYaw, float cameraPitch, float cameraX, float cameraY, float cameraZ)
	{
		final float pitchSin = (float) Math.sin(cameraPitch);
		final float pitchCos = (float) Math.cos(cameraPitch);
		final float yawSin = (float) Math.sin(cameraYaw);
		final float yawCos = (float) Math.cos(cameraYaw);

		return new Projection()
		{
			@Override
			public float[] project(float wx, float wy, float wz)
			{
				return project(wx, wy, wz, new float[3]);
			}

			@Override
			public float[] project(float wx, float wy, float wz, float[] out)
			{
				final float fx = wx - cameraX;
				final float fy = wz - cameraZ;
				final float fz = wy - cameraY;

				final float x1 = fx * yawCos + fy * yawSin;
				final float y1 = fy * yawCos - fx * yawSin;
				final float y2 = fz * pitchCos - y1 * pitchSin;
				final float z1 = y1 * pitchCos + fz * pitchSin;

				out[0] = x1;
				out[1] = y2;
				out[2] = z1;
				return out;
			}
		};
	}

	static float[] computeDesktopWorldProj(float scale, int viewportWidth, int viewportHeight,
		float cameraYaw, float cameraPitch, float cameraX, float cameraY, float cameraZ)
	{
		float[] projectionMatrix = Mat4.scale(scale, scale, 1);
		Mat4.mul(projectionMatrix, Mat4.projection(viewportWidth, viewportHeight, 50));
		Mat4.mul(projectionMatrix, Mat4.rotateX(cameraPitch));
		Mat4.mul(projectionMatrix, Mat4.rotateY(cameraYaw));
		Mat4.mul(projectionMatrix, Mat4.translate(-cameraX, -cameraY, -cameraZ));
		return projectionMatrix;
	}

	static float safeDesktopCameraYawRad(Client client)
	{
		double yaw = client.getCameraFpYaw();
		if (yaw < 0)
		{
			yaw = client.getCameraYaw() * Perspective.UNIT;
		}
		return (float) yaw;
	}

	static float safeDesktopCameraPitchRad(Client client)
	{
		double pitch = client.getCameraFpPitch();
		if (pitch < 0)
		{
			pitch = client.getCameraPitch() * Perspective.UNIT;
		}
		return (float) pitch;
	}

	static int radiansToJau(double radians)
	{
		int angle = (int) Math.round(radians / Perspective.UNIT);
		angle %= 2048;
		if (angle < 0)
		{
			angle += 2048;
		}
		return angle;
	}

	static final class Pose
	{
		float x;
		float y;
		float z;
		float qx;
		float qy;
		float qz;
		float qw;

		private Pose(float x, float y, float z, float qx, float qy, float qz, float qw)
		{
			this.x = x;
			this.y = y;
			this.z = z;
			this.qx = qx;
			this.qy = qy;
			this.qz = qz;
			this.qw = qw;
		}

		private static Pose from(XrPosef pose)
		{
			return new Pose(
				pose.position$().x(),
				pose.position$().y(),
				pose.position$().z(),
				pose.orientation().x(),
				pose.orientation().y(),
				pose.orientation().z(),
				pose.orientation().w());
		}

		private void lerpTo(Pose target, float amount)
		{
			x += (target.x - x) * amount;
			y += (target.y - y) * amount;
			z += (target.z - z) * amount;

			float dot = qx * target.qx + qy * target.qy + qz * target.qz + qw * target.qw;
			float sign = dot < 0f ? -1f : 1f;
			qx += (target.qx * sign - qx) * amount;
			qy += (target.qy * sign - qy) * amount;
			qz += (target.qz * sign - qz) * amount;
			qw += (target.qw * sign - qw) * amount;
			normalizeQuaternion();
		}

		private void normalizeQuaternion()
		{
			float len = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
			if (len == 0f)
			{
				qw = 1f;
				return;
			}

			qx /= len;
			qy /= len;
			qz /= len;
			qw /= len;
		}
	}
}
