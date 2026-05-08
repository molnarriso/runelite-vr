/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 */
package net.runelite.client.plugins.vrgpu;

import java.awt.event.MouseEvent;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

@Slf4j
final class VrInteraction
{
	private static final boolean VR_CHECK_CLICKBOX_DEBUG = true;
	private static final long VR_CHECK_CLICKBOX_DEBUG_INTERVAL_MS = 1000L;

	private final VrGpuPlugin plugin;
	private long lastCheckClickboxDebugMs;

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

		GroundHit groundHit = plugin.getSceneRaycaster().intersectGround(ox, oy, oz, dx, dy, dz, wv);
		List<Hit> hits = plugin.getSceneRaycaster().raycastScene(ox, oy, oz, dx, dy, dz, wv, groundHit);
		logRay(button, hits, groundHit, ox, oy, oz, dx, dy, dz, clickHit);
		plugin.setLastGroundHit(groundHit);
		plugin.setLastSceneRaycastHit(hits.isEmpty() ? null : hits.get(0), ox, oy, oz, dx, dy, dz);
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
		GroundHit groundHit = plugin.getSceneRaycaster().intersectGround(ox, oy, oz, dx, dy, dz, wv);
		List<Hit> hits = plugin.getSceneRaycaster().raycastScene(ox, oy, oz, dx, dy, dz, wv, groundHit);
		Hit hit = !hits.isEmpty() ? hits.get(0) : null;
		plugin.setInteractionHoverDebug(hit, groundHit, ox, oy, oz, dx, dy, dz, now);

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
			debugCheckClickbox(liveEntry, now);
		}
		plugin.setHoverTarget(hitX, hitY + VrGpuPlugin.VR_CONTEXT_HINT_WORLD_Y_OFFSET, hitZ, now);
	}

	private void debugCheckClickbox(MenuEntry entry, long now)
	{
		if (!VR_CHECK_CLICKBOX_DEBUG || now - lastCheckClickboxDebugMs < VR_CHECK_CLICKBOX_DEBUG_INTERVAL_MS)
		{
			return;
		}
		lastCheckClickboxDebugMs = now;

		Client client = plugin.getClient();
		WorldView wv = client.getWorldView(entry.getWorldViewId());
		if (wv == null)
		{
			return;
		}

		MenuAction action = entry.getType();
		switch (action)
		{
			case WIDGET_TARGET_ON_GAME_OBJECT:
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case EXAMINE_OBJECT:
			{
				TileObject object = findTileObject(wv, entry.getParam0(), entry.getParam1(), entry.getIdentifier());
				if (object != null)
				{
					debugCheckClickboxObject(client, wv, entry, object);
				}
				break;
			}
			case WIDGET_TARGET_ON_NPC:
			case NPC_FIRST_OPTION:
			case NPC_SECOND_OPTION:
			case NPC_THIRD_OPTION:
			case NPC_FOURTH_OPTION:
			case NPC_FIFTH_OPTION:
			case EXAMINE_NPC:
			{
				NPC npc = entry.getNpc();
				if (npc != null)
				{
					debugCheckClickboxActor(client, wv, entry, npc.getModel(), npc.getCurrentOrientation() & 2047,
						npc.getLocalLocation(), syntheticEntityHash(wv, npc.getId(), 1, npc.getLocalLocation()));
				}
				break;
			}
			case WIDGET_TARGET_ON_PLAYER:
			case PLAYER_FIRST_OPTION:
			case PLAYER_SECOND_OPTION:
			case PLAYER_THIRD_OPTION:
			case PLAYER_FOURTH_OPTION:
			case PLAYER_FIFTH_OPTION:
			case PLAYER_SIXTH_OPTION:
			case PLAYER_SEVENTH_OPTION:
			case PLAYER_EIGHTH_OPTION:
			{
				Player player = entry.getPlayer();
				if (player != null)
				{
					debugCheckClickboxActor(client, wv, entry, player.getModel(), player.getCurrentOrientation() & 2047,
						player.getLocalLocation(), syntheticEntityHash(wv, player.getId(), 0, player.getLocalLocation()));
				}
				break;
			}
		}
	}

	private void debugCheckClickboxObject(Client client, WorldView wv, MenuEntry entry, TileObject object)
	{
		Projection projection = wv.getCanvasProjection();
		long hash = object.getHash();
		if (object instanceof GameObject)
		{
			GameObject gameObject = (GameObject) object;
			debugCheckClickboxRenderable(client, projection, entry, "GameObject", gameObject.getRenderable(),
				gameObject.getModelOrientation() & 2047, gameObject.getX(), gameObject.getZ(), gameObject.getY(), hash);
		}
		else if (object instanceof WallObject)
		{
			WallObject wallObject = (WallObject) object;
			debugCheckClickboxRenderable(client, projection, entry, "WallObject.1", wallObject.getRenderable1(),
				0, wallObject.getX(), wallObject.getZ(), wallObject.getY(), hash);
			debugCheckClickboxRenderable(client, projection, entry, "WallObject.2", wallObject.getRenderable2(),
				0, wallObject.getX(), wallObject.getZ(), wallObject.getY(), hash);
		}
		else if (object instanceof DecorativeObject)
		{
			DecorativeObject decorativeObject = (DecorativeObject) object;
			debugCheckClickboxRenderable(client, projection, entry, "DecorativeObject.1", decorativeObject.getRenderable(),
				0, decorativeObject.getX() + decorativeObject.getXOffset(), decorativeObject.getZ(),
				decorativeObject.getY() + decorativeObject.getYOffset(), hash);
			debugCheckClickboxRenderable(client, projection, entry, "DecorativeObject.2", decorativeObject.getRenderable2(),
				0, decorativeObject.getX() + decorativeObject.getXOffset2(), decorativeObject.getZ(),
				decorativeObject.getY() + decorativeObject.getYOffset2(), hash);
		}
		else if (object instanceof GroundObject)
		{
			GroundObject groundObject = (GroundObject) object;
			debugCheckClickboxRenderable(client, projection, entry, "GroundObject", groundObject.getRenderable(),
				0, groundObject.getX(), groundObject.getZ(), groundObject.getY(), hash);
		}
	}

	private void debugCheckClickboxRenderable(Client client, Projection projection, MenuEntry entry, String label,
		Renderable renderable, int orientation, int x, int y, int z, long hash)
	{
		if (renderable == null)
		{
			return;
		}
		Model model = renderable.getModel();
		if (model == null)
		{
			return;
		}

		log.info("VR checkClickbox debug: {} option={} target={} action={} id={} hash={} orient={} pos=({},{},{}) modelVerts={} useAabb={}",
			label, entry.getOption(), entry.getTarget(), entry.getType(), entry.getIdentifier(), hash,
			orientation, x, y, z, model.getVerticesCount(), model.useBoundingBox());
		client.checkClickbox(projection, model, orientation, x, y, z, hash);
	}

	private void debugCheckClickboxActor(Client client, WorldView wv, MenuEntry entry, Model model,
		int orientation, LocalPoint lp, long hash)
	{
		if (model == null || lp == null)
		{
			return;
		}

		int y = Perspective.getTileHeight(client, lp, wv.getPlane());
		log.info("VR checkClickbox debug: Actor option={} target={} action={} id={} syntheticHash={} orient={} pos=({},{},{}) modelVerts={} useAabb={}",
			entry.getOption(), entry.getTarget(), entry.getType(), entry.getIdentifier(), hash,
			orientation, lp.getX(), y, lp.getY(), model.getVerticesCount(), model.useBoundingBox());
		client.checkClickbox(wv.getCanvasProjection(), model, orientation, lp.getX(), y, lp.getY(), hash);
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

	private static long syntheticEntityHash(WorldView wv, int id, int type, LocalPoint lp)
	{
		if (lp == null)
		{
			return 0L;
		}
		long worldView = (long) wv.getId() & 4095L;
		long sceneX = (lp.getX() >> 7) & 127L;
		long sceneY = (lp.getY() >> 7) & 127L;
		long plane = wv.getPlane() & 3L;
		return worldView << 52 | ((long) id & 0xffffffffL) << 20 | ((long) type & 7L) << 16 | plane << 14 | sceneY << 7 | sceneX;
	}
}
