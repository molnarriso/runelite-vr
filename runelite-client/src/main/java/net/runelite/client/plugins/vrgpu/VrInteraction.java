/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 */
package net.runelite.client.plugins.vrgpu;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.MouseEvent;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ItemLayer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Node;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
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
		NPC npc;
		Player player;
		TileObject tileObject;
		ItemLayer itemLayer;
		TileItem tileItem;
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

	void handleClick(int button, float[] clickRay)
	{
		Client client = plugin.getClient();
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}

		float ox = clickRay[0], oy = clickRay[1], oz = clickRay[2];
		float dx = clickRay[3], dy = clickRay[4], dz = clickRay[5];

		GroundHit groundHit = plugin.getSceneRaycaster().intersectGround(ox, oy, oz, dx, dy, dz, wv);
		List<Hit> hits = plugin.getSceneRaycaster().raycastScene(ox, oy, oz, dx, dy, dz, wv, groundHit);
		Hit hit = hits.isEmpty() ? null : hits.get(0);
		logRay(button, hits, groundHit, ox, oy, oz, dx, dy, dz);
		plugin.setLastGroundHit(groundHit);
		plugin.setLastSceneRaycastHit(hit, ox, oy, oz, dx, dy, dz);
		plugin.updateClientWalkDiagnostics(wv);

		if (hit == null)
		{
			log.info("VR {} ignored: no AABB entity hit", button == MouseEvent.BUTTON1 ? "LMB" : "RMB");
			return;
		}

		if (!primeDesktopForHit(hit, ox, oy, oz, dx, dy, dz, true))
		{
			log.info("VR {} ignored: no stable desktop activation pixel for {} {}",
				button == MouseEvent.BUTTON1 ? "LMB" : "RMB", hit.entityType, hit.entityName);
			return;
		}

		MenuEntry liveEntry = getTopLiveMenuEntry();
		if (!liveEntryMatchesHit(liveEntry, hit))
		{
			log.info("VR {} ignored: live menu verification failed for {} {}; top={} {} action={} p0={} p1={} id={}",
				button == MouseEvent.BUTTON1 ? "LMB" : "RMB", hit.entityType, hit.entityName,
				liveEntry == null ? "null" : liveEntry.getOption(),
				liveEntry == null ? "" : liveEntry.getTarget(),
				liveEntry == null ? null : liveEntry.getType(),
				liveEntry == null ? 0 : liveEntry.getParam0(),
				liveEntry == null ? 0 : liveEntry.getParam1(),
				liveEntry == null ? 0 : liveEntry.getIdentifier());
			return;
		}

		if (button == MouseEvent.BUTTON1)
		{
			dispatchLiveMenuEntry(liveEntry, wv);
		}
		else if (button == MouseEvent.BUTTON3)
		{
			openLiveMenuAtCurrentMouse(hit, ox, oy, oz, dx, dy, dz);
		}
	}

	private void logRay(int button, List<Hit> hits, GroundHit groundHit, float ox, float oy, float oz, float dx, float dy, float dz)
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
		log.info("VR click diag: rayOrigin=({},{},{}) rayDir=({},{},{}) groundHit={}",
			String.format("%.1f", ox), String.format("%.1f", oy), String.format("%.1f", oz),
			String.format("%.4f", dx), String.format("%.4f", dy), String.format("%.4f", dz),
			groundHit == null ? "null" : String.format("(scene=%d,%d local=%.1f,%.1f,%.1f t=%.1f)",
				groundHit.sceneX, groundHit.sceneY, groundHit.x, groundHit.y, groundHit.z, groundHit.t));
	}

	private void dispatchLiveMenuEntry(MenuEntry liveEntry, WorldView wv)
	{
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

	private void openLiveMenuAtCurrentMouse(Hit hit, float ox, float oy, float oz, float dx, float dy, float dz)
	{
		net.runelite.api.Point mousePoint = plugin.getClient().getMouseCanvasPosition();
		if (mousePoint == null)
		{
			return;
		}

		float[] anchor = pointOnRay(ox, oy, oz, dx, dy, dz, hit.t);
		plugin.setVrPendingMenuAnchor(anchor[0], anchor[1] + VrGpuPlugin.VR_MENU_WORLD_Y_OFFSET, anchor[2]);
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
		GroundHit groundHit = plugin.getSceneRaycaster().intersectGround(ox, oy, oz, dx, dy, dz, wv);
		List<Hit> hits = plugin.getSceneRaycaster().raycastScene(ox, oy, oz, dx, dy, dz, wv, groundHit);
		Hit hit = !hits.isEmpty() ? hits.get(0) : null;
		plugin.setInteractionHoverDebug(hit, groundHit, ox, oy, oz, dx, dy, dz, now);

		if (hit == null)
		{
			plugin.clearHoverTarget();
			return;
		}

		if (!primeDesktopForHit(hit, ox, oy, oz, dx, dy, dz, false))
		{
			plugin.clearHoverTarget();
			return;
		}

		MenuEntry liveEntry = getTopLiveMenuEntry();
		if (!liveEntryMatchesHit(liveEntry, hit))
		{
			plugin.clearHoverTarget();
			return;
		}

		float[] p = pointOnRay(ox, oy, oz, dx, dy, dz, hit.t);
		plugin.setHoverMarkerAction(liveEntry.getType(), liveEntry.getOption());
		plugin.setHoverTarget(p[0], p[1] + VrGpuPlugin.VR_CONTEXT_HINT_WORLD_Y_OFFSET, p[2], now);
	}

	private boolean primeDesktopForHit(Hit hit, float ox, float oy, float oz, float dx, float dy, float dz, boolean verbose)
	{
		Client client = plugin.getClient();
		float[] p = pointOnRay(ox, oy, oz, dx, dy, dz, hit.t);
		plugin.aimDesktopCameraAtLocal(p[0], p[1], p[2], hit.sceneX, hit.sceneY, verbose);

		// The VR ray chooses entity identity; it does not provide the final desktop click pixel.
		// AABB entry points are often edge cases, so use the entity's current desktop clickbox/hull and verify the live menu.
		net.runelite.api.Point activation = activationPoint(hit);
		if (activation == null)
		{
			return false;
		}

		int canvasX = Math.max(0, Math.min(client.getCanvasWidth() - 1, activation.getX()));
		int canvasY = Math.max(0, Math.min(client.getCanvasHeight() - 1, activation.getY()));
		plugin.setVrDesktopActivationMarker(canvasX, canvasY);
		plugin.primeCanvasMouseForWalk(canvasX, canvasY);
		return true;
	}

	private net.runelite.api.Point activationPoint(Hit hit)
	{
		Shape shape = null;
		if (hit.npc != null)
		{
			shape = hit.npc.getConvexHull();
		}
		else if (hit.player != null)
		{
			shape = hit.player.getConvexHull();
		}
		else if (hit.tileObject != null)
		{
			shape = hit.tileObject.getClickbox();
			if (shape == null)
			{
				shape = hit.tileObject.getCanvasTilePoly();
			}
		}
		return centerOf(shape);
	}

	private static net.runelite.api.Point centerOf(Shape shape)
	{
		if (shape == null)
		{
			return null;
		}
		Rectangle bounds = shape.getBounds();
		if (bounds.isEmpty())
		{
			return null;
		}
		return new net.runelite.api.Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
	}

	private boolean liveEntryMatchesHit(MenuEntry entry, Hit hit)
	{
		// Dispatch only live vanilla menu entries, and only after they resolve back to the VR-picked entity.
		// This keeps RuneLite/OSRS menu ordering, plugins, and selected-item/spell state as the authority.
		if (entry == null || entry.getType() == MenuAction.CANCEL)
		{
			return false;
		}
		if (hit.npc != null)
		{
			return entry.getNpc() == hit.npc;
		}
		if (hit.player != null)
		{
			return entry.getPlayer() == hit.player;
		}
		if (hit.tileItem != null)
		{
			WorldView wv = plugin.getClient().getWorldView(entry.getWorldViewId());
			ItemLayer layer = wv == null ? null : findItemLayer(wv, entry.getParam0(), entry.getParam1());
			return layer == hit.itemLayer && findItem(layer, entry.getIdentifier()) == hit.tileItem;
		}
		if (hit.tileObject != null)
		{
			WorldView wv = plugin.getClient().getWorldView(entry.getWorldViewId());
			return wv != null && findTileObject(wv, entry.getParam0(), entry.getParam1(), entry.getIdentifier()) == hit.tileObject;
		}
		return false;
	}

	private TileObject findTileObject(WorldView wv, int x, int y, int id)
	{
		int offset = wv.isTopLevel() ? (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2 : 0;
		int ex = x + offset;
		int ey = y + offset;
		Scene scene = wv.getScene();
		Tile[][][] tiles = scene.getExtendedTiles();
		if (wv.getPlane() < 0 || wv.getPlane() >= tiles.length
			|| ex < 0 || ey < 0 || tiles[wv.getPlane()] == null
			|| ex >= tiles[wv.getPlane()].length || ey >= tiles[wv.getPlane()][ex].length)
		{
			return null;
		}

		Tile tile = tiles[wv.getPlane()][ex][ey];
		if (tile == null)
		{
			return null;
		}

		for (GameObject gameObject : tile.getGameObjects())
		{
			if (gameObject != null && gameObject.getId() == id)
			{
				return gameObject;
			}
		}

		WallObject wallObject = tile.getWallObject();
		if (wallObject != null && wallObject.getId() == id)
		{
			return wallObject;
		}

		DecorativeObject decorativeObject = tile.getDecorativeObject();
		if (decorativeObject != null && decorativeObject.getId() == id)
		{
			return decorativeObject;
		}

		GroundObject groundObject = tile.getGroundObject();
		return groundObject != null && groundObject.getId() == id ? groundObject : null;
	}

	private ItemLayer findItemLayer(WorldView wv, int x, int y)
	{
		int offset = wv.isTopLevel() ? (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2 : 0;
		int ex = x + offset;
		int ey = y + offset;
		Scene scene = wv.getScene();
		Tile[][][] tiles = scene.getExtendedTiles();
		if (wv.getPlane() < 0 || wv.getPlane() >= tiles.length
			|| ex < 0 || ey < 0 || tiles[wv.getPlane()] == null
			|| ex >= tiles[wv.getPlane()].length || ey >= tiles[wv.getPlane()][ex].length)
		{
			return null;
		}

		Tile tile = tiles[wv.getPlane()][ex][ey];
		return tile == null ? null : tile.getItemLayer();
	}

	private static TileItem findItem(ItemLayer layer, int id)
	{
		if (layer == null)
		{
			return null;
		}

		Node current = layer.getTop();
		while (current instanceof TileItem)
		{
			TileItem item = (TileItem) current;
			current = current.getNext();
			if (item.getId() == id)
			{
				return item;
			}
		}
		return null;
	}

	private static float[] pointOnRay(float ox, float oy, float oz, float dx, float dy, float dz, float t)
	{
		return new float[]{ox + dx * t, oy + dy * t, oz + dz * t};
	}
}
