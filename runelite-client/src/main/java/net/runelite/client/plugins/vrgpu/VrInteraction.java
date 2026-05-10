/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 */
package net.runelite.client.plugins.vrgpu;

import java.awt.event.MouseEvent;
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
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
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
		Hit hit = plugin.getSceneRaycaster().raycastScene(ox, oy, oz, dx, dy, dz, wv, groundHit, false);
		plugin.setLastGroundHit(groundHit);
		plugin.setLastSceneRaycastHit(hit, ox, oy, oz, dx, dy, dz);
		plugin.updateClientWalkDiagnostics(wv);

		String btn = button == MouseEvent.BUTTON1 ? "LMB" : "RMB";

		// First intersection along the ray is what the VR cursor actually landed on:
		// the closer of (closest entity hit, ground hit). Compute one 3D point and one scene tile
		// from that, and treat the rest of the flow uniformly regardless of whether an entity was hit.
		float px, py, pz;
		int sceneX, sceneY;
		if (hit != null && (groundHit == null || hit.t <= groundHit.t))
		{
			px = ox + dx * hit.t; py = oy + dy * hit.t; pz = oz + dz * hit.t;
			sceneX = hit.sceneX; sceneY = hit.sceneY;
		}
		else if (groundHit != null)
		{
			// VR ray's first intersection was the ground; ignore any further-away entity hit.
			hit = null;
			px = groundHit.x; py = groundHit.y; pz = groundHit.z;
			sceneX = groundHit.sceneX; sceneY = groundHit.sceneY;
		}
		else
		{
			log.info("VR {} ignored: no entity hit and no ground hit", btn);
			return;
		}

		plugin.aimDesktopCameraAtLocal(px, py, pz, sceneX, sceneY, true);

		// Activation pixel is always the canvas projection of the VR cursor's hit point.
		net.runelite.api.Point projected = Perspective.localToCanvas(
			client, Math.round(px), Math.round(pz), Math.round(py));
		if (projected == null)
		{
			log.info("VR {} ignored: could not project hit point to canvas (scene={},{})", btn, sceneX, sceneY);
			return;
		}
		int canvasX = Math.max(0, Math.min(client.getCanvasWidth() - 1, projected.getX()));
		int canvasY = Math.max(0, Math.min(client.getCanvasHeight() - 1, projected.getY()));
		plugin.setVrDesktopActivationMarker(canvasX, canvasY);
		plugin.primeCanvasMouse(canvasX, canvasY);

		if (button == MouseEvent.BUTTON3)
		{
			plugin.setVrPendingMenuAnchor(px, py + VrGpuPlugin.VR_MENU_WORLD_Y_OFFSET, pz);
			plugin.logVrMenuEntries("RMB before open");
			plugin.dispatchCanvasMouseClick(canvasX, canvasY, MouseEvent.BUTTON3);
			log.info("VR RMB dispatch: open menu at canvas=({},{})", canvasX, canvasY);
			return;
		}

		// LMB: walk the menu in priority order, dispatch the first entry that concerns our hit.
		MenuEntry click = findClickEntry(hit, sceneX, sceneY);
		String hitDesc = hit == null ? "ground=(" + sceneX + "," + sceneY + ")"
			: hit.entityType + " " + hit.entityName + " scene=(" + sceneX + "," + sceneY + ")";
		if (click == null)
		{
			MenuEntry top = getTopLiveMenuEntry();
			log.info("VR LMB verify FAIL for {}; no entry concerns the hit (top={} {} action={})",
				hitDesc,
				top == null ? "null" : top.getOption(),
				top == null ? "" : top.getTarget(),
				top == null ? null : top.getType());
			if (hit != null) logHitActionsAndLiveMenu(hit);
			return;
		}
		log.info("VR LMB verify OK for {}: picked {} {} ({}) p0={} p1={} id={}",
			hitDesc,
			click.getOption(), click.getTarget(), click.getType(),
			click.getParam0(), click.getParam1(), click.getIdentifier());
		logLiveMenu("LMB");
		dispatchLiveMenuEntry(click, wv);
	}

	private void logLiveMenu(String label)
	{
		MenuEntry[] entries = plugin.getClient().getMenu().getMenuEntries();
		if (entries == null || entries.length == 0)
		{
			log.info("VR {} live menu: empty", label);
			return;
		}
		StringBuilder sb = new StringBuilder("VR ").append(label).append(" live menu (")
			.append(entries.length).append("):");
		for (int i = entries.length - 1; i >= 0; i--)
		{
			MenuEntry e = entries[i];
			sb.append("\n  ").append(entries.length - i).append(". ")
				.append(e.getOption()).append(' ').append(e.getTarget())
				.append(" action=").append(e.getType())
				.append(" p0=").append(e.getParam0())
				.append(" p1=").append(e.getParam1())
				.append(" id=").append(e.getIdentifier())
				.append(" itemId=").append(e.getItemId())
				.append(" itemOP=").append(e.getItemOp())
				.append(" LeftClickForce=").append(e.isForceLeftClick());

		}
		log.info("{}", sb);
	}

	private MenuEntry getTopLiveMenuEntry()
	{
		MenuEntry[] entries = plugin.getClient().getMenu().getMenuEntries();
		return entries == null || entries.length == 0 ? null : entries[entries.length - 1];
	}

	/**
	/**
	 * Walk the OSRS live menu in priority order and pick the first entry that concerns the VR hit.
	 *
	 * Rules:
	 *   - CANCEL              → skip (never a click action)
	 *   - WALK                → dispatch (canvas pixel was already primed onto the hit point)
	 *   - entry for an entity → dispatch iff it resolves to our hit's entity (impostor-aware
	 *                            for tile objects); skip otherwise. If the VR ray hit ground only,
	 *                            tile-object/ground-item entries fall back to a tile match against
	 *                            the hit's scene tile, since OSRS placed them on that tile.
	 *
	 * The desktop camera ray is at a different angle from the VR ray and can intersect entities
	 * the VR ray didn't, putting their action on top of the menu. Skipping non-matching entries
	 * lets us reach the entry that actually corresponds to where the user pointed.
	 */
	private MenuEntry findClickEntry(Hit hit, int hitSceneX, int hitSceneY)
	{
		MenuEntry[] entries = plugin.getClient().getMenu().getMenuEntries();
		if (entries == null) return null;
		for (int i = entries.length - 1; i >= 0; i--)
		{
			MenuEntry e = entries[i];
			if (e == null) continue;
			MenuAction a = e.getType();
			if (a == MenuAction.CANCEL) continue;
			if (a == MenuAction.WALK) return e;
			if (entryConcernsHit(e, hit, hitSceneX, hitSceneY)) return e;
		}
		return null;
	}

	private boolean entryConcernsHit(MenuEntry e, Hit hit, int hitSceneX, int hitSceneY)
	{
		// If we have an entity hit, defer to the strict resolver — it does the impostor walk for
		// tile objects, npc/player ref equality, and item-layer checks for ground items.
		if (hit != null && (hit.npc != null || hit.player != null
			|| hit.tileObject != null || hit.tileItem != null))
		{
			return liveEntryMismatchReason(e, hit) == null;
		}

		// Ground-only hit: accept tile-object and ground-item entries on the same tile.
		// These are the action types whose param0/param1 are the entry's scene tile coords.
		switch (e.getType())
		{
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case EXAMINE_OBJECT:
			case WIDGET_TARGET_ON_GAME_OBJECT:
			case GROUND_ITEM_FIRST_OPTION:
			case GROUND_ITEM_SECOND_OPTION:
			case GROUND_ITEM_THIRD_OPTION:
			case GROUND_ITEM_FOURTH_OPTION:
			case GROUND_ITEM_FIFTH_OPTION:
			case EXAMINE_ITEM_GROUND:
			case WIDGET_TARGET_ON_GROUND_ITEM:
				return e.getParam0() == hitSceneX && e.getParam1() == hitSceneY;
			default:
				return false;
		}
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

		log.info("VR LMB dispatch: {} {} ({})", liveEntry.getOption(), liveEntry.getTarget(), action);
		plugin.getClient().menuAction(p0, p1, action, liveEntry.getIdentifier(), liveEntry.getItemId(), liveEntry.getOption(), liveEntry.getTarget());

		if (action == MenuAction.WALK)
		{
			plugin.updateClientWalkDiagnostics(wv);
		}
	}

	/**
	 * Returns null if the live menu entry resolves back to the VR-picked entity;
	 * otherwise a short reason describing why the comparison rejected it.
	 */
	private String liveEntryMismatchReason(MenuEntry entry, Hit hit)
	{
		if (entry == null)
		{
			return "entry=null";
		}
		MenuAction action = entry.getType();
		if (action == MenuAction.CANCEL)
		{
			return "entry=CANCEL";
		}

		if (hit.npc != null)
		{
			NPC entryNpc = entry.getNpc();
			if (entryNpc == null)
			{
				return "entry has no NPC (action=" + action + ")";
			}
			if (entryNpc != hit.npc)
			{
				return "entry NPC mismatch: hit id=" + hit.npc.getId() + " name=" + hit.npc.getName()
					+ " vs entry id=" + entryNpc.getId() + " name=" + entryNpc.getName();
			}
			return null;
		}
		if (hit.player != null)
		{
			Player entryPlayer = entry.getPlayer();
			if (entryPlayer == null)
			{
				return "entry has no Player (action=" + action + ")";
			}
			if (entryPlayer != hit.player)
			{
				return "entry Player mismatch: hit=" + hit.player.getName()
					+ " vs entry=" + entryPlayer.getName();
			}
			return null;
		}
		if (hit.tileItem != null)
		{
			if (action != MenuAction.GROUND_ITEM_FIRST_OPTION
				&& action != MenuAction.GROUND_ITEM_SECOND_OPTION
				&& action != MenuAction.GROUND_ITEM_THIRD_OPTION
				&& action != MenuAction.GROUND_ITEM_FOURTH_OPTION
				&& action != MenuAction.GROUND_ITEM_FIFTH_OPTION
				&& action != MenuAction.EXAMINE_ITEM_GROUND
				&& action != MenuAction.WIDGET_TARGET_ON_GROUND_ITEM)
			{
				return "entry action " + action + " not a ground-item action; hit is TileItem id="
					+ hit.tileItem.getId() + " at scene=(" + hit.sceneX + "," + hit.sceneY + ")";
			}
			WorldView wv = plugin.getClient().getWorldView(entry.getWorldViewId());
			ItemLayer layer = wv == null ? null : findItemLayer(wv, entry.getParam0(), entry.getParam1());
			if (layer != hit.itemLayer)
			{
				return "entry tile=(" + entry.getParam0() + "," + entry.getParam1()
					+ ") layer differs from hit scene=(" + hit.sceneX + "," + hit.sceneY + ")";
			}
			if (findItem(layer, entry.getIdentifier()) != hit.tileItem)
			{
				return "entry item id=" + entry.getIdentifier() + " not the same TileItem as hit (id="
					+ hit.tileItem.getId() + ")";
			}
			return null;
		}
		if (hit.tileObject != null)
		{
			if (action != MenuAction.GAME_OBJECT_FIRST_OPTION
				&& action != MenuAction.GAME_OBJECT_SECOND_OPTION
				&& action != MenuAction.GAME_OBJECT_THIRD_OPTION
				&& action != MenuAction.GAME_OBJECT_FOURTH_OPTION
				&& action != MenuAction.GAME_OBJECT_FIFTH_OPTION
				&& action != MenuAction.EXAMINE_OBJECT
				&& action != MenuAction.WIDGET_TARGET_ON_GAME_OBJECT)
			{
				return "entry action " + action + " not a tile-object action; hit is "
					+ hit.entityType + " id=" + hit.tileObject.getId()
					+ " at scene=(" + hit.sceneX + "," + hit.sceneY + ")";
			}
			WorldView wv = plugin.getClient().getWorldView(entry.getWorldViewId());
			if (wv == null)
			{
				return "entry worldViewId=" + entry.getWorldViewId() + " not resolvable";
			}
			TileObject entryObj = findTileObject(wv, entry.getParam0(), entry.getParam1(), entry.getIdentifier());
			if (entryObj == null)
			{
				return "entry tile=(" + entry.getParam0() + "," + entry.getParam1()
					+ ") id=" + entry.getIdentifier() + " did not resolve to any TileObject"
					+ " (hit was id=" + hit.tileObject.getId() + " at scene="
					+ hit.sceneX + "," + hit.sceneY + ")";
			}
			if (entryObj != hit.tileObject)
			{
				return "entry resolved to different TileObject (entry id=" + entry.getIdentifier()
					+ " tile=" + entry.getParam0() + "," + entry.getParam1()
					+ " vs hit id=" + hit.tileObject.getId() + " scene="
					+ hit.sceneX + "," + hit.sceneY + ")";
			}
			return null;
		}
		return "hit had no entity reference (npc/player/tileObject/tileItem all null)";
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
			if (gameObject != null && objIdsMatch(gameObject.getId(), id))
			{
				return gameObject;
			}
		}

		WallObject wallObject = tile.getWallObject();
		if (wallObject != null && objIdsMatch(wallObject.getId(), id))
		{
			return wallObject;
		}

		DecorativeObject decorativeObject = tile.getDecorativeObject();
		if (decorativeObject != null && objIdsMatch(decorativeObject.getId(), id))
		{
			return decorativeObject;
		}

		GroundObject groundObject = tile.getGroundObject();
		return groundObject != null && objIdsMatch(groundObject.getId(), id) ? groundObject : null;
	}

	/**
	 * True if {@code aId} and {@code bId} refer to the same OSRS object once parent/impostor resolution
	 * is taken into account. Verified case (bush): parent id 31633 has impostorIds=[31620,31619,31620]
	 * and getImpostor() resolves to 31620; the menu reports 31620, the placed GameObject reports 31633.
	 */
	private boolean objIdsMatch(int aId, int bId)
	{
		if (aId == bId)
		{
			return true;
		}
		Client client = plugin.getClient();
		ObjectComposition aDef = client.getObjectDefinition(aId);
		if (aDef != null && aDef.getImpostorIds() != null)
		{
			ObjectComposition aImp = aDef.getImpostor();
			if (aImp != null && aImp.getId() == bId)
			{
				return true;
			}
		}
		ObjectComposition bDef = client.getObjectDefinition(bId);
		if (bDef != null && bDef.getImpostorIds() != null)
		{
			ObjectComposition bImp = bDef.getImpostor();
			if (bImp != null && bImp.getId() == aId)
			{
				return true;
			}
		}
		return false;
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

	private void logHitActionsAndLiveMenu(Hit hit)
	{
		Client client = plugin.getClient();

		if (hit.tileObject != null)
		{
			int objId = hit.tileObject.getId();
			log.info("VR hit object id={} {}", objId, describeObjectDef(objId));
			// Also dump every TileObject actually present on the hit tile, so we can see the full inventory
			// of objects there (including parents/impostors) and tell from data whether the menu's id
			// corresponds to one of them via impostor resolution or is unrelated.
			logTileContents(hit);
			// Cross-reference each menu entry's tile-object id, so we can compare its impostor structure
			// against the hit's id directly in the log.
			MenuEntry[] entries = client.getMenu().getMenuEntries();
			if (entries != null)
			{
				for (MenuEntry e : entries)
				{
					MenuAction a = e.getType();
					if (a == MenuAction.GAME_OBJECT_FIRST_OPTION || a == MenuAction.GAME_OBJECT_SECOND_OPTION
						|| a == MenuAction.GAME_OBJECT_THIRD_OPTION || a == MenuAction.GAME_OBJECT_FOURTH_OPTION
						|| a == MenuAction.GAME_OBJECT_FIFTH_OPTION || a == MenuAction.EXAMINE_OBJECT)
					{
						log.info("VR menu obj entry id={} action={} {}",
							e.getIdentifier(), a, describeObjectDef(e.getIdentifier()));
					}
				}
			}
		}
		else if (hit.npc != null)
		{
			log.info("VR hit npc id={} name={} actions={}",
				hit.npc.getId(), hit.npc.getName(),
				hit.npc.getComposition() == null ? "null"
					: java.util.Arrays.toString(hit.npc.getComposition().getActions()));
		}

		MenuEntry[] entries = client.getMenu().getMenuEntries();
		StringBuilder sb = new StringBuilder("VR live menu (").append(entries == null ? 0 : entries.length).append("):\n");
		if (entries != null)
		{
			for (int i = entries.length - 1; i >= 0; i--)
			{
				MenuEntry e = entries[i];
				sb.append("  ").append(entries.length - i).append(". ")
					.append(e.getOption()).append(' ').append(e.getTarget())
					.append(" action=").append(e.getType())
					.append(" p0=").append(e.getParam0())
					.append(" p1=").append(e.getParam1())
					.append(" id=").append(e.getIdentifier())
					.append('\n');
			}
		}
		log.info("{}", sb);
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
		Hit hit = plugin.getSceneRaycaster().raycastScene(ox, oy, oz, dx, dy, dz, wv, groundHit, false);
		plugin.setInteractionHoverRaycastOutputs(hit, groundHit, ox, oy, oz, dx, dy, dz, now);

		// Same first-intersection rule as click: pick whichever the VR ray actually hit first.
		float px, py, pz;
		int sceneX, sceneY;
		if (hit != null && (groundHit == null || hit.t <= groundHit.t))
		{
			px = ox + dx * hit.t; py = oy + dy * hit.t; pz = oz + dz * hit.t;
			sceneX = hit.sceneX; sceneY = hit.sceneY;
		}
		else if (groundHit != null)
		{
			hit = null;
			px = groundHit.x; py = groundHit.y; pz = groundHit.z;
			sceneX = groundHit.sceneX; sceneY = groundHit.sceneY;
		}
		else
		{
			plugin.clearHoverTarget();
			return;
		}

		plugin.aimDesktopCameraAtLocal(px, py, pz, sceneX, sceneY, false);
		net.runelite.api.Point projected = Perspective.localToCanvas(
			client, Math.round(px), Math.round(pz), Math.round(py));
		if (projected == null)
		{
			plugin.clearHoverTarget();
			return;
		}
		int canvasX = Math.max(0, Math.min(client.getCanvasWidth() - 1, projected.getX()));
		int canvasY = Math.max(0, Math.min(client.getCanvasHeight() - 1, projected.getY()));
		plugin.setVrDesktopActivationMarker(canvasX, canvasY);
		plugin.primeCanvasMouse(canvasX, canvasY);

		// Hint mirrors the LMB dispatch picker so the label can't disagree with what fires.
		MenuEntry hint = findClickEntry(hit, sceneX, sceneY);
		if (hint == null)
		{
			plugin.clearHoverTarget();
			return;
		}
		plugin.setHoverMarkerAction(hint.getType(), hint.getOption());
		plugin.setHoverTarget(px, py + VrGpuPlugin.VR_CONTEXT_HINT_WORLD_Y_OFFSET, pz, now);
	}

	private static float[] pointOnRay(float ox, float oy, float oz, float dx, float dy, float dz, float t)
	{
		return new float[]{ox + dx * t, oy + dy * t, oz + dz * t};
	}

	private String describeObjectDef(int id)
	{
		ObjectComposition def = plugin.getClient().getObjectDefinition(id);
		if (def == null)
		{
			return "def=null";
		}
		StringBuilder sb = new StringBuilder();
		sb.append("name=").append(def.getName())
			.append(" actions=").append(def.getActions() == null ? "null" : java.util.Arrays.toString(def.getActions()))
			.append(" impostorIds=").append(def.getImpostorIds() == null ? "null" : java.util.Arrays.toString(def.getImpostorIds()));
		if (def.getImpostorIds() != null)
		{
			ObjectComposition imp = def.getImpostor();
			if (imp != null)
			{
				sb.append(" resolvedImpostor: id=").append(imp.getId())
					.append(" name=").append(imp.getName())
					.append(" actions=").append(imp.getActions() == null ? "null" : java.util.Arrays.toString(imp.getActions()));
			}
			else
			{
				sb.append(" resolvedImpostor=null");
			}
		}
		return sb.toString();
	}

	private void logTileContents(Hit hit)
	{
		WorldView wv = plugin.getClient().getTopLevelWorldView();
		if (wv == null) return;
		int offset = wv.isTopLevel() ? (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2 : 0;
		int ex = hit.sceneX + offset;
		int ey = hit.sceneY + offset;
		Tile[][][] tiles = wv.getScene().getExtendedTiles();
		if (wv.getPlane() < 0 || wv.getPlane() >= tiles.length || tiles[wv.getPlane()] == null
			|| ex < 0 || ey < 0 || ex >= tiles[wv.getPlane()].length || ey >= tiles[wv.getPlane()][ex].length)
		{
			log.info("VR tile contents at scene=({},{}): out of bounds", hit.sceneX, hit.sceneY);
			return;
		}
		Tile tile = tiles[wv.getPlane()][ex][ey];
		if (tile == null)
		{
			log.info("VR tile contents at scene=({},{}): tile=null", hit.sceneX, hit.sceneY);
			return;
		}
		StringBuilder sb = new StringBuilder("VR tile contents at scene=(")
			.append(hit.sceneX).append(',').append(hit.sceneY).append("):");
		for (GameObject go : tile.getGameObjects())
		{
			if (go != null) sb.append("\n  GameObject id=").append(go.getId()).append(' ').append(describeObjectDef(go.getId()));
		}
		WallObject wo = tile.getWallObject();
		if (wo != null) sb.append("\n  WallObject id=").append(wo.getId()).append(' ').append(describeObjectDef(wo.getId()));
		DecorativeObject deco = tile.getDecorativeObject();
		if (deco != null) sb.append("\n  DecorativeObject id=").append(deco.getId()).append(' ').append(describeObjectDef(deco.getId()));
		GroundObject grnd = tile.getGroundObject();
		if (grnd != null) sb.append("\n  GroundObject id=").append(grnd.getId()).append(' ').append(describeObjectDef(grnd.getId()));
		log.info("{}", sb);
	}
}
