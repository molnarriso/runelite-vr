/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 */
package net.runelite.client.plugins.vrgpu;

import java.awt.event.MouseEvent;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.WorldView;

@Slf4j
final class VrInteraction
{
	private final VrGpuPlugin plugin;

	VrInteraction(VrGpuPlugin plugin)
	{
		this.plugin = plugin;
	}

	static final class Hit
	{
		float t;
		String entityType;
		String entityName;
		int sceneX;
		int sceneY;
	}

	static final class GroundHit
	{
		float t;
		float x;
		float y;
		float z;
		int sceneX;
		int sceneY;
	}

	static final class RenderablePlacement
	{
		final net.runelite.api.Renderable renderable;
		final int orientation;
		final int x;
		final int y;
		final int z;

		RenderablePlacement(net.runelite.api.Renderable renderable, int orientation, int x, int y, int z)
		{
			this.renderable = renderable;
			this.orientation = orientation;
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}

	void onPostClientTick()
	{
		processHover();
	}

	void handleClick(int button, float[] clickRay, float[] clickHit)
	{
		Client client = plugin.getClient();
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}

		float ox = clickRay[0], oy = clickRay[1], oz = clickRay[2];
		float dx = clickRay[3], dy = clickRay[4], dz = clickRay[5];

		GroundHit groundHit = plugin.getSceneRaycaster().intersectGround(ox, oy, oz, dx, dy, dz, wv, clickHit);
		List<Hit> hits = plugin.getSceneRaycaster().raycastScene(ox, oy, oz, dx, dy, dz, wv, groundHit);
		logRay(button, hits, groundHit, ox, oy, oz, dx, dy, dz, clickHit);
		plugin.setLastGroundHit(groundHit);
		plugin.updateClientWalkDiagnostics(wv);

		if (button == MouseEvent.BUTTON1)
		{
			dispatchTopLiveMenuEntry(groundHit, wv);
		}
		else if (button == MouseEvent.BUTTON3)
		{
			openLiveMenuAtCurrentMouse(groundHit);
		}
	}

	private void logRay(int button, List<Hit> hits, GroundHit groundHit, float ox, float oy, float oz, float dx, float dy, float dz, float[] clickHit)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("VR ").append(button == MouseEvent.BUTTON1 ? "LMB" : "RMB")
			.append(" ray (").append(hits.size()).append(" spatial hits)");

		if (groundHit != null)
		{
			sb.append(" ground=(").append(groundHit.sceneX).append(",").append(groundHit.sceneY)
				.append(") t=").append(String.format("%.1f", groundHit.t))
				.append(" local=(").append(String.format("%.1f", groundHit.x)).append(',')
				.append(String.format("%.1f", groundHit.y)).append(',')
				.append(String.format("%.1f", groundHit.z)).append(')');
		}
		sb.append('\n');

		for (Hit hit : hits)
		{
			sb.append(String.format("  %s %s [scene=%d,%d t=%.1f]\n",
				hit.entityType, hit.entityName, hit.sceneX, hit.sceneY, hit.t));
		}

		log.info("{}", sb);
		log.info("VR click diag: rayOrigin=({},{},{}) rayDir=({},{},{}) depthHit={} groundHit={}",
			String.format("%.1f", ox), String.format("%.1f", oy), String.format("%.1f", oz),
			String.format("%.4f", dx), String.format("%.4f", dy), String.format("%.4f", dz),
			clickHit == null ? "null" : String.format("(%.1f,%.1f,%.1f)", clickHit[0], clickHit[1], clickHit[2]),
			groundHit == null ? "null" : String.format("(scene=%d,%d local=%.1f,%.1f,%.1f t=%.1f)",
				groundHit.sceneX, groundHit.sceneY, groundHit.x, groundHit.y, groundHit.z, groundHit.t));
	}

	private void dispatchTopLiveMenuEntry(GroundHit groundHit, WorldView wv)
	{
		MenuEntry liveEntry = getTopLiveMenuEntry();
		if (liveEntry == null || liveEntry.getType() == MenuAction.CANCEL)
		{
			return;
		}

		int p0 = liveEntry.getParam0();
		int p1 = liveEntry.getParam1();
		MenuAction action = liveEntry.getType();
		if (action == MenuAction.WALK)
		{
			plugin.setLastWalkParams(p0, p1);
			plugin.clearLastDispatchSceneTile();
		}
		else
		{
			plugin.clearLastWalkParams();
			plugin.setLastDispatchSceneTile(p0, p1);
		}

		net.runelite.api.Point mousePoint = plugin.getClient().getMouseCanvasPosition();
		if (mousePoint != null)
		{
			plugin.setVrDesktopClickMarker(mousePoint.getX(), mousePoint.getY(), MouseEvent.BUTTON1);
		}

		// VR once synthesized its own menu actions from 3D ray hits, but that competed with the visible OSRS hint.
		// LMB now dispatches only the top live vanilla menu entry; revisit synthesis only for a future fully VR-native menu.
		log.info("VR LMB dispatch (top live menu entry): {} {} action={} p0={} p1={} id={} itemId={}",
			liveEntry.getOption(), liveEntry.getTarget(), action, p0, p1, liveEntry.getIdentifier(), liveEntry.getItemId());
		plugin.getClient().menuAction(p0, p1, action, liveEntry.getIdentifier(), liveEntry.getItemId(), liveEntry.getOption(), liveEntry.getTarget());

		if (action == MenuAction.WALK)
		{
			plugin.updateClientWalkDiagnostics(wv);
			plugin.logClientWalkState("after live walk dispatch", wv);
		}
	}

	private void openLiveMenuAtCurrentMouse(GroundHit groundHit)
	{
		net.runelite.api.Point mousePoint = plugin.getClient().getMouseCanvasPosition();
		if (mousePoint == null)
		{
			return;
		}

		if (groundHit != null)
		{
			plugin.setVrPendingMenuAnchor(groundHit.x, groundHit.y + VrGpuPlugin.VR_MENU_WORLD_Y_OFFSET, groundHit.z);
		}
		plugin.logVrMenuEntries("RMB before open");
		plugin.dispatchCanvasMouseClick(mousePoint.getX(), mousePoint.getY(), MouseEvent.BUTTON3);
		plugin.setVrDesktopClickMarker(mousePoint.getX(), mousePoint.getY(), MouseEvent.BUTTON3);
		log.info("VR RMB dispatch: open live desktop menu at canvas=({}, {})", mousePoint.getX(), mousePoint.getY());
	}

	private MenuEntry getTopLiveMenuEntry()
	{
		MenuEntry[] entries = plugin.getClient().getMenuEntries();
		return entries == null || entries.length == 0 ? null : entries[entries.length - 1];
	}

	void processHover()
	{
		if (plugin.hasPendingClickRay())
		{
			return;
		}

		float[] hoverRay = plugin.getPendingHoverRay();
		if (hoverRay == null)
		{
			plugin.clearHoverTarget();
			return;
		}

		long now = System.currentTimeMillis();
		if (now - plugin.getLastHoverProcessMs() < VrGpuPlugin.VR_CONTEXT_HINT_HOVER_INTERVAL_MS)
		{
			return;
		}
		plugin.setLastHoverProcessMs(now);

		Client client = plugin.getClient();
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			plugin.clearHoverTarget();
			return;
		}

		float ox = hoverRay[0];
		float oy = hoverRay[1];
		float oz = hoverRay[2];
		float dx = hoverRay[3];
		float dy = hoverRay[4];
		float dz = hoverRay[5];
		float[] depthHit = new float[]{hoverRay[6], hoverRay[7], hoverRay[8]};

		GroundHit groundHit = plugin.getSceneRaycaster().intersectGround(ox, oy, oz, dx, dy, dz, wv, depthHit);
		List<Hit> hits = plugin.getSceneRaycaster().raycastScene(ox, oy, oz, dx, dy, dz, wv, groundHit);
		Hit hit = !hits.isEmpty() ? hits.get(0) : null;

		float t;
		int sceneX;
		int sceneY;
		if (hit != null)
		{
			t = hit.t;
			sceneX = hit.sceneX;
			sceneY = hit.sceneY;
		}
		else if (groundHit != null)
		{
			t = groundHit.t;
			sceneX = groundHit.sceneX;
			sceneY = groundHit.sceneY;
		}
		else
		{
			plugin.clearHoverTarget();
			return;
		}

		float hitX = ox + dx * t;
		float hitY = oy + dy * t;
		float hitZ = oz + dz * t;
		plugin.aimDesktopCameraAtLocal(hitX, hitY, hitZ, sceneX, sceneY, false);

		net.runelite.api.Point canvasPoint = plugin.projectVrHoverCanvasPoint(wv, hitX, hitY, hitZ, groundHit);
		if (canvasPoint == null)
		{
			plugin.clearHoverTarget();
			return;
		}
		int canvasX = Math.max(0, Math.min(client.getCanvasWidth() - 1, canvasPoint.getX()));
		int canvasY = Math.max(0, Math.min(client.getCanvasHeight() - 1, canvasPoint.getY()));
		plugin.primeCanvasMouseForWalk(canvasX, canvasY);

		MenuEntry liveEntry = getTopLiveMenuEntry();
		if (liveEntry != null)
		{
			plugin.setHoverMarkerAction(liveEntry.getType(), liveEntry.getOption());
		}
		plugin.setHoverTarget(hitX, hitY + VrGpuPlugin.VR_CONTEXT_HINT_WORLD_Y_OFFSET, hitZ, now);
	}
}
