/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 */
package net.runelite.client.plugins.vrgpu;

import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;

@Slf4j
final class VrInteraction
{
	private final VrGpuPlugin plugin;
	private Entry pendingStagedEntry;
	private int pendingStagedButton = MouseEvent.BUTTON1;
	private boolean pendingWalkMousePrimed;
	private int pendingWalkDelayTicks;

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
		List<Entry> entries = new ArrayList<>();
	}

	static final class Entry
	{
		String option;
		String target;
		MenuAction action;
		int p0;
		int p1;
		int id;
		int itemId = -1;
		float x;
		float y;
		float z;
		float t;
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
		processPendingStagedWalk();
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
		logMenu(button, hits, groundHit, ox, oy, oz, dx, dy, dz, clickHit);
		plugin.setLastGroundHit(groundHit);
		plugin.updateClientWalkDiagnostics(wv);

		Entry defaultEntry = !hits.isEmpty() && !hits.get(0).entries.isEmpty()
			? hits.get(0).entries.get(0)
			: null;
		if (defaultEntry == null && groundHit != null)
		{
			defaultEntry = addEntry(null, "Walk here", "", MenuAction.WALK, groundHit.sceneX, groundHit.sceneY, 0, 0);
			copyGroundHitToEntry(defaultEntry, groundHit);
		}

		if (button == MouseEvent.BUTTON1 && defaultEntry != null)
		{
			if (defaultEntry.action == MenuAction.WALK)
			{
				beginStagedWalkDispatch(defaultEntry);
			}
			else
			{
				dispatch(defaultEntry);
			}
		}
		else if (button == MouseEvent.BUTTON3)
		{
			Entry rmbEntry = defaultEntry;
			if (rmbEntry == null && groundHit != null)
			{
				rmbEntry = addEntry(null, "Walk here", "", MenuAction.WALK, groundHit.sceneX, groundHit.sceneY, 0, 0);
			}
			if (rmbEntry != null)
			{
				// Hit entries carry only p0/p1; back-fill sceneX/Y and local x/y/z so
				// camera aim and canvas projection target the actual click location.
				if (groundHit != null)
				{
					copyGroundHitToEntry(rmbEntry, groundHit);
				}
				else if (!hits.isEmpty())
				{
					rmbEntry.sceneX = hits.get(0).sceneX;
					rmbEntry.sceneY = hits.get(0).sceneY;
				}
				beginStagedRightClickDispatch(rmbEntry);
			}
		}
	}

	private void beginStagedRightClickDispatch(Entry entry)
	{
		cancelPendingWalkInspection();
		plugin.aimDesktopCameraAtLocal(entry.x, entry.y, entry.z, entry.sceneX, entry.sceneY, true);
		pendingStagedEntry = entry;
		pendingStagedButton = MouseEvent.BUTTON3;
		pendingWalkDelayTicks = 1;
		log.info("VR staged RMB begin: scene=({}, {}) local=({},{},{}) delayTicks={}",
			entry.sceneX, entry.sceneY,
			String.format("%.1f", entry.x), String.format("%.1f", entry.y), String.format("%.1f", entry.z),
			pendingWalkDelayTicks);
	}

	private void logMenu(int button, List<Hit> hits, GroundHit groundHit, float ox, float oy, float oz, float dx, float dy, float dz, float[] clickHit)
	{
		StringBuilder menuLog = new StringBuilder();
		menuLog.append("VR ").append(button == MouseEvent.BUTTON1 ? "LMB" : "RMB")
			.append(" menu (").append(hits.size()).append(" hits)");

		if (groundHit != null)
		{
			menuLog.append(" ground=(").append(groundHit.sceneX).append(",").append(groundHit.sceneY)
				.append(") t=").append(String.format("%.1f", groundHit.t))
				.append(" local=(").append(String.format("%.1f", groundHit.x)).append(',')
				.append(String.format("%.1f", groundHit.y)).append(',')
				.append(String.format("%.1f", groundHit.z)).append(')');
		}
		menuLog.append('\n');

		int entryCount = 0;
		for (Hit hit : hits)
		{
			for (Entry entry : hit.entries)
			{
				menuLog.append(String.format("  %2d. %s %s  [t=%.1f, %s]\n",
					++entryCount, entry.option, entry.target, hit.t, hit.entityType));
			}
		}

		if (groundHit != null)
		{
			menuLog.append(String.format("  %2d. Walk here  [scene=%d,%d, t=%.1f]\n",
				++entryCount, groundHit.sceneX, groundHit.sceneY, groundHit.t));
		}
		menuLog.append(String.format("  %2d. Cancel\n", ++entryCount));
		log.info("{}", menuLog);
		log.info("VR click diag: rayOrigin=({},{},{}) rayDir=({},{},{}) depthHit={} groundHit={}",
			String.format("%.1f", ox), String.format("%.1f", oy), String.format("%.1f", oz),
			String.format("%.4f", dx), String.format("%.4f", dy), String.format("%.4f", dz),
			clickHit == null ? "null" : String.format("(%.1f,%.1f,%.1f)", clickHit[0], clickHit[1], clickHit[2]),
			groundHit == null ? "null" : String.format("(scene=%d,%d local=%.1f,%.1f,%.1f t=%.1f)",
				groundHit.sceneX, groundHit.sceneY, groundHit.x, groundHit.y, groundHit.z, groundHit.t));
	}

	void processHover()
	{
		if (pendingStagedEntry != null || plugin.hasPendingClickRay())
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
		Entry defaultEntry = hit != null && !hit.entries.isEmpty()
			? hit.entries.get(0)
			: null;
		if (defaultEntry == null && groundHit != null)
		{
			defaultEntry = addEntry(null, "Walk here", "", MenuAction.WALK, groundHit.sceneX, groundHit.sceneY, 0, 0);
		}

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

		if (defaultEntry != null)
		{
			plugin.setHoverMarkerAction(defaultEntry.action, defaultEntry.option);
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

		plugin.setHoverTarget(hitX, hitY + VrGpuPlugin.VR_CONTEXT_HINT_WORLD_Y_OFFSET, hitZ, now);
	}

	private void beginStagedWalkDispatch(Entry entry)
	{
		cancelPendingWalkInspection();
		plugin.clearWalkDiagnosticsForDispatch(entry.sceneX, entry.sceneY);
		plugin.aimDesktopCameraAtLocal(entry.x, entry.y, entry.z, entry.sceneX, entry.sceneY, true);

		// WALK dispatch p0/p1 are canvas coordinates, so fill them after the camera/mouse prime settles.
		pendingStagedEntry = entry;
		pendingStagedButton = MouseEvent.BUTTON1;
		pendingWalkDelayTicks = 1;
		log.info("VR staged walk begin: scene=({}, {}) local=({},{},{}) delayTicks={}",
			entry.sceneX, entry.sceneY,
			String.format("%.1f", entry.x), String.format("%.1f", entry.y), String.format("%.1f", entry.z),
			pendingWalkDelayTicks);
	}

	private void processPendingStagedWalk()
	{
		Entry pendingEntry = pendingStagedEntry;
		if (pendingEntry == null)
		{
			return;
		}

		Client client = plugin.getClient();
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			cancelPendingWalkInspection();
			return;
		}

		if (pendingWalkDelayTicks > 0)
		{
			int ticksRemainingAfterThis = pendingWalkDelayTicks - 1;
			pendingWalkDelayTicks--;
			if (ticksRemainingAfterThis > 0)
			{
				return;
			}
		}

		net.runelite.api.Point canvasPoint;
		if (pendingStagedButton == MouseEvent.BUTTON3 && pendingEntry.action != MenuAction.WALK)
		{
			// For RMB on an entity/object, place the cursor on the entity so the vanilla menu shows its actions.
			canvasPoint = Perspective.localToCanvas(client, wv.getId(),
				Math.round(pendingEntry.x), Math.round(pendingEntry.z), Math.round(pendingEntry.y));
			if (canvasPoint == null)
			{
				canvasPoint = plugin.projectStagedWalkCanvasPoint(pendingEntry, wv);
			}
		}
		else
		{
			canvasPoint = plugin.projectStagedWalkCanvasPoint(pendingEntry, wv);
		}
		if (canvasPoint == null)
		{
			log.info("VR staged action: projection failed button={} scene=({}, {}) local=({},{},{})",
				pendingStagedButton, pendingEntry.sceneX, pendingEntry.sceneY,
				String.format("%.1f", pendingEntry.x), String.format("%.1f", pendingEntry.y), String.format("%.1f", pendingEntry.z));
			cancelPendingWalkInspection();
			return;
		}

		net.runelite.api.Point rawProjected = Perspective.localToCanvas(
			client,
			wv.getId(),
			Math.round(pendingEntry.x),
			Math.round(pendingEntry.z),
			Math.round(pendingEntry.y));
		float[] desktopRayHit = plugin.reconstructDesktopScreenRayGroundHit(canvasPoint.getX(), canvasPoint.getY(), wv);
		plugin.setLastDesktopRayHit(desktopRayHit);
		plugin.setPendingWalkCanvasPoint(canvasPoint.getX(), canvasPoint.getY());
		plugin.setLastWalkParams(canvasPoint.getX(), canvasPoint.getY());
		net.runelite.api.Point mouseBefore = client.getMouseCanvasPosition();
		if (!pendingWalkMousePrimed)
		{
			plugin.primeCanvasMouseForWalk(canvasPoint.getX(), canvasPoint.getY());
			pendingWalkMousePrimed = true;
			pendingWalkDelayTicks = 1;
			log.info("VR staged walk mouse prime: scene=({}, {}) dispatchCanvas=({}, {}) mouseBefore={} mouseAfter={} delayTicks={}",
				pendingEntry.sceneX, pendingEntry.sceneY,
				canvasPoint.getX(), canvasPoint.getY(),
				mouseBefore,
				client.getMouseCanvasPosition(),
				pendingWalkDelayTicks);
			return;
		}
		log.info("VR staged dispatch: button={} scene=({}, {}) rawCanvas={} dispatchCanvas=({}, {}) desktopRayHit={} local=({},{},{})",
			pendingStagedButton, pendingEntry.sceneX, pendingEntry.sceneY,
			rawProjected,
			canvasPoint.getX(), canvasPoint.getY(),
			desktopRayHit == null ? "null" : String.format("(%.1f,%.1f,%.1f)", desktopRayHit[0], desktopRayHit[1], desktopRayHit[2]),
			String.format("%.1f", pendingEntry.x), String.format("%.1f", pendingEntry.y), String.format("%.1f", pendingEntry.z));
		if (pendingStagedButton == MouseEvent.BUTTON3)
		{
			plugin.setVrPendingMenuAnchor(pendingEntry.x, pendingEntry.y + VrGpuPlugin.VR_MENU_WORLD_Y_OFFSET, pendingEntry.z);
			plugin.dispatchCanvasMouseClick(canvasPoint.getX(), canvasPoint.getY(), MouseEvent.BUTTON3);
			plugin.setVrDesktopClickMarker(canvasPoint.getX(), canvasPoint.getY(), MouseEvent.BUTTON3);
		}
		else
		{
			pendingEntry.p0 = canvasPoint.getX();
			pendingEntry.p1 = canvasPoint.getY();
			dispatch(pendingEntry);
			plugin.updateClientWalkDiagnostics(wv);
			plugin.logClientWalkState("after staged walk dispatch", wv);
		}
		cancelPendingWalkInspection();
	}

	private void cancelPendingWalkInspection()
	{
		pendingStagedEntry = null;
		pendingStagedButton = MouseEvent.BUTTON1;
		plugin.setPendingWalkCanvasPoint(null);
		pendingWalkMousePrimed = false;
		pendingWalkDelayTicks = 0;
	}

	static Entry addEntry(Hit hit, String option, String target, MenuAction action, int p0, int p1, int id, int itemId)
	{
		Entry entry = new Entry();
		entry.option = option;
		entry.target = target;
		entry.action = action;
		entry.p0 = p0;
		entry.p1 = p1;
		entry.id = id;
		entry.itemId = itemId;
		if (hit != null)
		{
			hit.entries.add(entry);
		}
		return entry;
	}

	static void addEntries(Hit hit, String[] options, MenuAction[] actions, int id, int p0, int p1, String target)
	{
		if (options == null)
		{
			return;
		}

		for (int i = 0; i < options.length && i < actions.length; i++)
		{
			String option = options[i];
			if (option == null || option.isEmpty() || "null".equals(option))
			{
				continue;
			}
			addEntry(hit, option, target, actions[i], p0, p1, id, -1);
		}
	}

	static void copyGroundHitToEntry(Entry entry, GroundHit groundHit)
	{
		entry.x = groundHit.x;
		entry.y = groundHit.y;
		entry.z = groundHit.z;
		entry.t = groundHit.t;
		entry.sceneX = groundHit.sceneX;
		entry.sceneY = groundHit.sceneY;
	}

	private void dispatch(Entry vrEntry)
	{
		// The live client menu is authoritative for exact dispatch params; VR entries only identify the intended target.
		MenuEntry liveEntry = findMatchingLiveMenuEntry(vrEntry);
		int p0 = liveEntry != null ? liveEntry.getParam0() : vrEntry.p0;
		int p1 = liveEntry != null ? liveEntry.getParam1() : vrEntry.p1;
		MenuAction action = liveEntry != null ? liveEntry.getType() : vrEntry.action;
		int id = liveEntry != null ? liveEntry.getIdentifier() : vrEntry.id;
		int itemId = liveEntry != null ? liveEntry.getItemId() : vrEntry.itemId;
		String option = liveEntry != null ? liveEntry.getOption() : vrEntry.option;
		String target = liveEntry != null ? liveEntry.getTarget() : vrEntry.target;

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
		log.info("VR LMB dispatch ({}): {} {} action={} p0={} p1={} id={} itemId={}",
			liveEntry != null ? "matched vanilla menu entry" : "synthesized VR entry",
			option, target, action, p0, p1, id, itemId);
		plugin.getClient().menuAction(p0, p1, action, id, itemId, option, target);
	}

	private MenuEntry findMatchingLiveMenuEntry(Entry vrEntry)
	{
		MenuEntry[] liveEntries = plugin.getClient().getMenuEntries();
		if (liveEntries == null)
		{
			log.info("VR live menu match miss: client menu entries are null for {} {} action={} p0={} p1={} id={} itemId={}",
				vrEntry.option, vrEntry.target, vrEntry.action, vrEntry.p0, vrEntry.p1, vrEntry.id, vrEntry.itemId);
			return null;
		}

		for (MenuEntry liveEntry : liveEntries)
		{
			if (liveEntry != null && liveMenuEntryMatchesVrEntry(liveEntry, vrEntry))
			{
				log.info("VR live menu matched: option={} target={} action={} p0={} p1={} id={} itemId={}",
					liveEntry.getOption(), liveEntry.getTarget(), liveEntry.getType(),
					liveEntry.getParam0(), liveEntry.getParam1(), liveEntry.getIdentifier(), liveEntry.getItemId());
				return liveEntry;
			}
		}

		logLiveMenuMatchMiss(vrEntry, liveEntries);
		return null;
	}

	private static boolean liveMenuEntryMatchesVrEntry(MenuEntry liveEntry, Entry vrEntry)
	{
		if (!safeEquals(liveEntry.getOption(), vrEntry.option))
		{
			return false;
		}

		// WALK uses canvas coordinates; the target tile is resolved by the client from p0/p1.
		if (vrEntry.action == MenuAction.WALK)
		{
			return liveEntry.getType() == MenuAction.WALK
				&& liveEntry.getParam0() == vrEntry.p0
				&& liveEntry.getParam1() == vrEntry.p1;
		}

		return liveEntry.getIdentifier() == vrEntry.id
			&& liveEntry.getParam0() == vrEntry.p0
			&& liveEntry.getParam1() == vrEntry.p1
			&& targetMatches(liveEntry.getTarget(), vrEntry.target);
	}

	private void logLiveMenuMatchMiss(Entry vrEntry, MenuEntry[] liveEntries)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("VR live menu match miss for ")
			.append(vrEntry.option).append(' ').append(vrEntry.target)
			.append(" action=").append(vrEntry.action)
			.append(" p0=").append(vrEntry.p0)
			.append(" p1=").append(vrEntry.p1)
			.append(" id=").append(vrEntry.id)
			.append(" itemId=").append(vrEntry.itemId)
			.append("; live entries=").append(liveEntries.length);
		int logged = 0;
		for (MenuEntry liveEntry : liveEntries)
		{
			if (liveEntry == null)
			{
				continue;
			}
			if (logged++ >= 8)
			{
				sb.append("\n  ...");
				break;
			}
			sb.append("\n  ")
				.append(liveEntry.getOption()).append(' ').append(liveEntry.getTarget())
				.append(" action=").append(liveEntry.getType())
				.append(" p0=").append(liveEntry.getParam0())
				.append(" p1=").append(liveEntry.getParam1())
				.append(" id=").append(liveEntry.getIdentifier())
				.append(" itemId=").append(liveEntry.getItemId());
			if (isPotentialLiveMenuCandidate(vrEntry, liveEntry))
			{
				sb.append(" diffs=").append(describeLiveMenuDiff(vrEntry, liveEntry));
			}
		}
		log.info("{}", sb);
	}

	private static boolean isPotentialLiveMenuCandidate(Entry vrEntry, MenuEntry liveEntry)
	{
		return liveEntry.getType() == vrEntry.action
			|| liveEntry.getIdentifier() == vrEntry.id
			|| safeEquals(liveEntry.getOption(), vrEntry.option)
			|| targetMatches(liveEntry.getTarget(), vrEntry.target);
	}

	private static String describeLiveMenuDiff(Entry vrEntry, MenuEntry liveEntry)
	{
		List<String> diffs = new ArrayList<>();
		if (!safeEquals(liveEntry.getOption(), vrEntry.option))
		{
			diffs.add("option live='" + liveEntry.getOption() + "' vr='" + vrEntry.option + "'");
		}
		if (!safeEquals(liveEntry.getTarget(), vrEntry.target) && !targetMatches(liveEntry.getTarget(), vrEntry.target))
		{
			diffs.add("target live='" + liveEntry.getTarget() + "' vr='" + vrEntry.target + "'");
		}
		if (liveEntry.getType() != vrEntry.action)
		{
			diffs.add("action live=" + liveEntry.getType() + " vr=" + vrEntry.action);
		}
		if (liveEntry.getParam0() != vrEntry.p0)
		{
			diffs.add("p0 live=" + liveEntry.getParam0() + " vr=" + vrEntry.p0);
		}
		if (liveEntry.getParam1() != vrEntry.p1)
		{
			diffs.add("p1 live=" + liveEntry.getParam1() + " vr=" + vrEntry.p1);
		}
		if (liveEntry.getIdentifier() != vrEntry.id)
		{
			diffs.add("id live=" + liveEntry.getIdentifier() + " vr=" + vrEntry.id);
		}
		if (liveEntry.getItemId() != vrEntry.itemId)
		{
			diffs.add("itemId live=" + liveEntry.getItemId() + " vr=" + vrEntry.itemId);
		}
		return diffs.isEmpty() ? "none" : String.join(", ", diffs);
	}

	private static boolean safeEquals(String a, String b)
	{
		return a == null ? b == null : a.equals(b);
	}

	private static boolean targetMatches(String liveTarget, String vrTarget)
	{
		return safeEquals(liveTarget, vrTarget)
			|| (liveTarget != null && vrTarget != null && liveTarget.contains(vrTarget));
	}
}
