/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 */
package net.runelite.client.plugins.vrgpu;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.AABB;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

/**
 * Casts VR controller rays through the scene against actor and tile-object model geometry,
 * plus ground tiles for Walk-here resolution. Pure spatial logic — no GL state, no plugin coupling.
 * All public methods must be called on the client thread.
 */
final class VrSceneRaycaster
{
	private final Client client;

	VrSceneRaycaster(Client client)
	{
		this.client = client;
	}

	/**
	 * Cast a ray through the scene, collecting all intersected entities sorted by distance.
	 * Tests actors and tile entities against actual model geometry, plus ground tiles for Walk here.
	 */
	List<VrInteraction.Hit> raycastScene(float ox, float oy, float oz,
		float dx, float dy, float dz, WorldView wv, VrInteraction.GroundHit groundHit)
	{
		List<VrInteraction.Hit> hits = new ArrayList<>();
		int plane = wv.getPlane();

		for (NPC npc : wv.npcs())
		{
			if (npc == null) continue;
			VrInteraction.Hit hit = buildNpcHit(ox, oy, oz, dx, dy, dz, plane, npc);
			if (hit != null)
			{
				hits.add(hit);
			}
		}

		for (Player player : wv.players())
		{
			if (player == null || player == client.getLocalPlayer())
			{
				continue;
			}

			VrInteraction.Hit hit = buildPlayerHit(ox, oy, oz, dx, dy, dz, plane, player);
			if (hit != null)
			{
				hits.add(hit);
			}
		}

		Tile[][][] sceneTiles = wv.getScene().getTiles();
		if (plane >= 0 && plane < sceneTiles.length && sceneTiles[plane] != null)
		{
			for (int scX = 0; scX < wv.getSizeX(); scX++)
			{
				for (int scY = 0; scY < wv.getSizeY(); scY++)
				{
					Tile tile = sceneTiles[plane][scX][scY];
					if (tile == null) continue;

					for (GameObject obj : tile.getGameObjects())
					{
						if (obj == null || obj.getId() == -1) continue;
						if (!obj.getSceneMinLocation().equals(tile.getSceneLocation()))
						{
							continue;
						}
						VrInteraction.Hit hit = buildObjectHit(ox, oy, oz, dx, dy, dz,
							"GameObject",
							clampScene(obj.getX() >> 7, wv.getSizeX()),
							clampScene(obj.getY() >> 7, wv.getSizeY()),
							obj.getId(),
							new VrInteraction.RenderablePlacement(obj.getRenderable(), obj.getModelOrientation() & 2047, obj.getX(), obj.getZ(), obj.getY()));
						if (hit != null)
						{
							hits.add(hit);
						}
					}

					WallObject wall = tile.getWallObject();
					if (wall != null && wall.getId() != -1)
					{
						VrInteraction.Hit hit = buildObjectHit(ox, oy, oz, dx, dy, dz,
							"WallObject",
							scX,
							scY,
							wall.getId(),
							new VrInteraction.RenderablePlacement(wall.getRenderable1(), 0, wall.getX(), wall.getZ(), wall.getY()),
							new VrInteraction.RenderablePlacement(wall.getRenderable2(), 0, wall.getX(), wall.getZ(), wall.getY()));
						if (hit != null)
						{
							hits.add(hit);
						}
					}

					DecorativeObject deco = tile.getDecorativeObject();
					if (deco != null && deco.getId() != -1)
					{
						VrInteraction.Hit hit = buildObjectHit(ox, oy, oz, dx, dy, dz,
							"DecorativeObject",
							scX,
							scY,
							deco.getId(),
							new VrInteraction.RenderablePlacement(deco.getRenderable(), 0,
								deco.getX() + deco.getXOffset(), deco.getZ(), deco.getY() + deco.getYOffset()),
							new VrInteraction.RenderablePlacement(deco.getRenderable2(), 0,
								deco.getX() + deco.getXOffset2(), deco.getZ(), deco.getY() + deco.getYOffset2()));
						if (hit != null)
						{
							hits.add(hit);
						}
					}

					GroundObject ground = tile.getGroundObject();
					if (ground != null && ground.getId() != -1)
					{
						VrInteraction.Hit hit = buildObjectHit(ox, oy, oz, dx, dy, dz,
							"GroundObject",
							scX,
							scY,
							ground.getId(),
							new VrInteraction.RenderablePlacement(ground.getRenderable(), 0, ground.getX(), ground.getZ(), ground.getY()));
						if (hit != null)
						{
							hits.add(hit);
						}
					}

					List<TileItem> items = tile.getGroundItems();
					if (items != null && !items.isEmpty())
					{
						float itemT = groundHit != null && groundHit.sceneX == scX && groundHit.sceneY == scY
							? groundHit.t
							: rayBoxTest(ox, oy, oz, dx, dy, dz,
								scX * 128f, tileHeightAtScene(wv, scX, scY, plane) - 60f, scY * 128f,
								scX * 128f + 128f, tileHeightAtScene(wv, scX, scY, plane) + 16f, scY * 128f + 128f);
						if (itemT <= 0f)
						{
							continue;
						}

						for (TileItem item : items)
						{
							if (item == null) continue;
							VrInteraction.Hit hit = buildGroundItemHit(itemT, scX, scY, item);
							if (hit != null)
							{
								hits.add(hit);
							}
						}
					}
				}
			}
		}

		hits.sort((a, b) -> Float.compare(a.t, b.t));
		return hits;
	}

	VrInteraction.GroundHit intersectGround(float ox, float oy, float oz, float dx, float dy, float dz, WorldView wv, float[] fallbackHit)
	{
		VrInteraction.GroundHit best = null;
		Tile[][][] tiles = wv.getScene().getTiles();
		int plane = wv.getPlane();
		if (plane >= 0 && plane < tiles.length && tiles[plane] != null)
		{
			for (int sceneX = 0; sceneX < wv.getSizeX(); sceneX++)
			{
				for (int sceneY = 0; sceneY < wv.getSizeY(); sceneY++)
				{
					Tile tile = tiles[plane][sceneX][sceneY];
					if (tile == null)
					{
						continue;
					}

					VrInteraction.GroundHit hit = intersectGroundTile(ox, oy, oz, dx, dy, dz, wv, plane, sceneX, sceneY, tile);
					if (hit != null && (best == null || hit.t < best.t))
					{
						best = hit;
					}
				}
			}
		}

		if (best == null && fallbackHit != null)
		{
			VrInteraction.GroundHit fallback = new VrInteraction.GroundHit();
			fallback.x = fallbackHit[0];
			fallback.y = fallbackHit[1];
			fallback.z = fallbackHit[2];
			fallback.sceneX = clampScene((int) fallbackHit[0] >> 7, wv.getSizeX());
			fallback.sceneY = clampScene((int) fallbackHit[2] >> 7, wv.getSizeY());
			fallback.t = distanceAlongRay(ox, oy, oz, dx, dy, dz, fallback.x, fallback.y, fallback.z);
			best = fallback;
		}
		return best;
	}

	private VrInteraction.Hit buildNpcHit(float ox, float oy, float oz, float dx, float dy, float dz, int plane, NPC npc)
	{
		Model model = npc.getModel();
		LocalPoint lp = npc.getLocalLocation();
		if (model == null || lp == null)
		{
			return null;
		}

		float t = rayTestModel(ox, oy, oz, dx, dy, dz, model, npc.getCurrentOrientation() & 2047,
			lp.getX(), Perspective.getTileHeight(client, lp, plane), lp.getY());
		if (t <= 0f)
		{
			return null;
		}

		NPCComposition comp = npc.getTransformedComposition();
		if (comp == null)
		{
			comp = npc.getComposition();
		}

		VrInteraction.Hit hit = new VrInteraction.Hit();
		hit.t = t;
		hit.entityType = "npc";
		hit.entityName = safeName(npc.getName(), "NPC");
		hit.sceneX = lp.getX() >> 7;
		hit.sceneY = lp.getY() >> 7;

		MenuAction[] actions = {
			MenuAction.NPC_FIRST_OPTION,
			MenuAction.NPC_SECOND_OPTION,
			MenuAction.NPC_THIRD_OPTION,
			MenuAction.NPC_FOURTH_OPTION,
			MenuAction.NPC_FIFTH_OPTION
		};
		VrInteraction.addEntries(hit, comp != null ? comp.getActions() : null, actions, npc.getIndex(), 0, 0, hit.entityName);
		VrInteraction.addEntry(hit, "Examine", hit.entityName, MenuAction.EXAMINE_NPC, 0, 0, npc.getIndex(), -1);
		return hit;
	}

	private VrInteraction.Hit buildPlayerHit(float ox, float oy, float oz, float dx, float dy, float dz, int plane, Player player)
	{
		Model model = player.getModel();
		LocalPoint lp = player.getLocalLocation();
		if (model == null || lp == null)
		{
			return null;
		}

		float t = rayTestModel(ox, oy, oz, dx, dy, dz, model, player.getCurrentOrientation() & 2047,
			lp.getX(), Perspective.getTileHeight(client, lp, plane), lp.getY());
		if (t <= 0f)
		{
			return null;
		}

		VrInteraction.Hit hit = new VrInteraction.Hit();
		hit.t = t;
		hit.entityType = "player";
		hit.entityName = safeName(player.getName(), "Player");
		hit.sceneX = lp.getX() >> 7;
		hit.sceneY = lp.getY() >> 7;

		MenuAction[] actions = {
			MenuAction.PLAYER_FIRST_OPTION,
			MenuAction.PLAYER_SECOND_OPTION,
			MenuAction.PLAYER_THIRD_OPTION,
			MenuAction.PLAYER_FOURTH_OPTION,
			MenuAction.PLAYER_FIFTH_OPTION,
			MenuAction.PLAYER_SIXTH_OPTION,
			MenuAction.PLAYER_SEVENTH_OPTION,
			MenuAction.PLAYER_EIGHTH_OPTION
		};
		VrInteraction.addEntries(hit, client.getPlayerOptions(), actions, player.getId(), 0, 0, hit.entityName);
		return hit.entries.isEmpty() ? null : hit;
	}

	private VrInteraction.Hit buildObjectHit(float ox, float oy, float oz, float dx, float dy, float dz,
		String entityType, int sceneX, int sceneY, int objId, VrInteraction.RenderablePlacement... placements)
	{
		ObjectComposition def = client.getObjectDefinition(objId);
		if (def == null)
		{
			return null;
		}
		if (def.getImpostorIds() != null)
		{
			ObjectComposition impostor = def.getImpostor();
			if (impostor != null)
			{
				def = impostor;
			}
		}

		float bestT = Float.MAX_VALUE;
		for (VrInteraction.RenderablePlacement placement : placements)
		{
			if (placement == null || placement.renderable == null)
			{
				continue;
			}

			Model model = placement.renderable.getModel();
			if (model == null)
			{
				continue;
			}

			float t = rayTestModel(ox, oy, oz, dx, dy, dz, model, placement.orientation, placement.x, placement.y, placement.z);
			if (t > 0f && t < bestT)
			{
				bestT = t;
			}
		}

		if (bestT == Float.MAX_VALUE)
		{
			return null;
		}

		VrInteraction.Hit hit = new VrInteraction.Hit();
		hit.t = bestT;
		hit.entityType = entityType;
		hit.entityName = safeName(def.getName(), "Object");
		hit.sceneX = sceneX;
		hit.sceneY = sceneY;

		MenuAction[] actions = {
			MenuAction.GAME_OBJECT_FIRST_OPTION,
			MenuAction.GAME_OBJECT_SECOND_OPTION,
			MenuAction.GAME_OBJECT_THIRD_OPTION,
			MenuAction.GAME_OBJECT_FOURTH_OPTION,
			MenuAction.GAME_OBJECT_FIFTH_OPTION
		};
		VrInteraction.addEntries(hit, def.getActions(), actions, objId, sceneX, sceneY, hit.entityName);
		VrInteraction.addEntry(hit, "Examine", hit.entityName, MenuAction.EXAMINE_OBJECT, sceneX, sceneY, objId, -1);
		return hit;
	}

	private VrInteraction.Hit buildGroundItemHit(float t, int sceneX, int sceneY, TileItem item)
	{
		ItemComposition def = client.getItemDefinition(item.getId());
		if (def == null)
		{
			return null;
		}

		VrInteraction.Hit hit = new VrInteraction.Hit();
		hit.t = t;
		hit.entityType = "ground-item";
		hit.entityName = safeName(def.getName(), "Item");
		hit.sceneX = sceneX;
		hit.sceneY = sceneY;

		VrInteraction.addEntry(hit, "Take", hit.entityName, MenuAction.GROUND_ITEM_FIRST_OPTION, sceneX, sceneY, item.getId(), item.getId());
		VrInteraction.addEntry(hit, "Examine", hit.entityName, MenuAction.EXAMINE_ITEM_GROUND, sceneX, sceneY, item.getId(), item.getId());
		return hit;
	}

	private static VrInteraction.GroundHit intersectGroundTile(float ox, float oy, float oz, float dx, float dy, float dz,
		WorldView wv, int plane, int sceneX, int sceneY, Tile tile)
	{
		SceneTileModel model = tile.getSceneTileModel();
		if (model != null)
		{
			int[] faceX = model.getFaceX();
			int[] faceY = model.getFaceY();
			int[] faceZ = model.getFaceZ();
			int[] vertexX = model.getVertexX();
			int[] vertexY = model.getVertexY();
			int[] vertexZ = model.getVertexZ();
			if (faceX != null && faceY != null && faceZ != null && vertexX != null && vertexY != null && vertexZ != null)
			{
				VrInteraction.GroundHit best = null;
				for (int i = 0; i < faceX.length; i++)
				{
					int a = faceX[i];
					int b = faceY[i];
					int c = faceZ[i];
					float t = rayTriangleMT(ox, oy, oz, dx, dy, dz,
						vertexX[a], vertexY[a], vertexZ[a],
						vertexX[b], vertexY[b], vertexZ[b],
						vertexX[c], vertexY[c], vertexZ[c]);
					if (t > 0f && (best == null || t < best.t))
					{
						best = buildGroundHit(ox, oy, oz, dx, dy, dz, t, sceneX, sceneY);
					}
				}
				if (best != null)
				{
					return best;
				}
			}
		}

		SceneTilePaint paint = tile.getSceneTilePaint();
		if (paint == null)
		{
			return null;
		}

		float baseX = sceneX * 128f;
		float baseZ = sceneY * 128f;
		float swY = tileHeightAtScene(wv, sceneX, sceneY, plane);
		float seY = tileHeightAtScene(wv, sceneX + 1, sceneY, plane);
		float neY = tileHeightAtScene(wv, sceneX + 1, sceneY + 1, plane);
		float nwY = tileHeightAtScene(wv, sceneX, sceneY + 1, plane);

		float t1 = rayTriangleMT(ox, oy, oz, dx, dy, dz,
			baseX, swY, baseZ,
			baseX + 128f, seY, baseZ,
			baseX + 128f, neY, baseZ + 128f);
		float t2 = rayTriangleMT(ox, oy, oz, dx, dy, dz,
			baseX, swY, baseZ,
			baseX + 128f, neY, baseZ + 128f,
			baseX, nwY, baseZ + 128f);

		float t = -1f;
		if (t1 > 0f)
		{
			t = t1;
		}
		if (t2 > 0f && (t < 0f || t2 < t))
		{
			t = t2;
		}
		return t > 0f ? buildGroundHit(ox, oy, oz, dx, dy, dz, t, sceneX, sceneY) : null;
	}

	private static VrInteraction.GroundHit buildGroundHit(float ox, float oy, float oz, float dx, float dy, float dz, float t, int sceneX, int sceneY)
	{
		VrInteraction.GroundHit hit = new VrInteraction.GroundHit();
		hit.t = t;
		hit.x = ox + dx * t;
		hit.y = oy + dy * t;
		hit.z = oz + dz * t;
		hit.sceneX = sceneX;
		hit.sceneY = sceneY;
		return hit;
	}

	private static float tileHeightAtScene(WorldView wv, int sceneX, int sceneY, int plane)
	{
		int clampedX = clampScene(sceneX, wv.getSizeX() + 1);
		int clampedY = clampScene(sceneY, wv.getSizeY() + 1);
		return wv.getTileHeights()[plane][clampedX][clampedY];
	}

	private static String safeName(String value, String fallback)
	{
		return value == null || value.isEmpty() || "null".equals(value) ? fallback : value;
	}

	private static int clampScene(int coord, int size)
	{
		return Math.max(0, Math.min(coord, size - 1));
	}

	private static float distanceAlongRay(float ox, float oy, float oz, float dx, float dy, float dz, float x, float y, float z)
	{
		float vx = x - ox;
		float vy = y - oy;
		float vz = z - oz;
		return vx * dx + vy * dy + vz * dz;
	}

	private static float rayTestModel(float ox, float oy, float oz,
		float dx, float dy, float dz,
		Model m, int orient, int entityX, int entityY, int entityZ)
	{
		AABB aabb = m.getAABB(orient);
		float cx = entityX + aabb.getCenterX();
		float cy = entityY + aabb.getCenterY();
		float cz = entityZ + aabb.getCenterZ();
		float ex = aabb.getExtremeX() + 16f;
		float ey = aabb.getExtremeY() + 16f;
		float ez = aabb.getExtremeZ() + 16f;
		if (rayBoxTest(ox, oy, oz, dx, dy, dz, cx - ex, cy - ey, cz - ez, cx + ex, cy + ey, cz + ez) < 0f)
		{
			return -1f;
		}

		float[] vx = m.getVerticesX();
		float[] vy = m.getVerticesY();
		float[] vz = m.getVerticesZ();
		int[] fi1 = m.getFaceIndices1();
		int[] fi2 = m.getFaceIndices2();
		int[] fi3 = m.getFaceIndices3();
		if (vx == null || vy == null || vz == null || fi1 == null || fi2 == null || fi3 == null)
		{
			return -1f;
		}

		int sin = Perspective.SINE[orient];
		int cos = Perspective.COSINE[orient];
		float minT = Float.MAX_VALUE;
		for (int f = 0; f < m.getFaceCount(); f++)
		{
			int i0 = fi1[f];
			int i1 = fi2[f];
			int i2 = fi3[f];

			float ax = vx[i0];
			float ay = vy[i0];
			float az = vz[i0];
			float wax = entityX + (az * sin + ax * cos) / 65536f;
			float way = entityY + ay;
			float waz = entityZ + (az * cos - ax * sin) / 65536f;

			float bx = vx[i1];
			float by = vy[i1];
			float bz = vz[i1];
			float wbx = entityX + (bz * sin + bx * cos) / 65536f;
			float wby = entityY + by;
			float wbz = entityZ + (bz * cos - bx * sin) / 65536f;

			float cx2 = vx[i2];
			float cy2 = vy[i2];
			float cz2 = vz[i2];
			float wcx = entityX + (cz2 * sin + cx2 * cos) / 65536f;
			float wcy = entityY + cy2;
			float wcz = entityZ + (cz2 * cos - cx2 * sin) / 65536f;

			float t = rayTriangleMT(ox, oy, oz, dx, dy, dz,
				wax, way, waz,
				wbx, wby, wbz,
				wcx, wcy, wcz);
			if (t > 0f && t < minT)
			{
				minT = t;
			}
		}
		return minT < Float.MAX_VALUE ? minT : -1f;
	}

	/**
	 * Möller-Trumbore ray-triangle intersection.
	 * Tests both faces (no backface culling).
	 *
	 * @return ray parameter t &gt; 0 if hit, else -1
	 */
	private static float rayTriangleMT(
		float ox, float oy, float oz, float dx, float dy, float dz,
		float v0x, float v0y, float v0z,
		float v1x, float v1y, float v1z,
		float v2x, float v2y, float v2z)
	{
		float e1x = v1x - v0x, e1y = v1y - v0y, e1z = v1z - v0z;
		float e2x = v2x - v0x, e2y = v2y - v0y, e2z = v2z - v0z;
		float hx = dy * e2z - dz * e2y;
		float hy = dz * e2x - dx * e2z;
		float hz = dx * e2y - dy * e2x;
		float a = e1x * hx + e1y * hy + e1z * hz;
		if (a > -1e-5f && a < 1e-5f) return -1f; // parallel to triangle plane
		float f = 1f / a;
		float sx = ox - v0x, sy = oy - v0y, sz = oz - v0z;
		float u = f * (sx * hx + sy * hy + sz * hz);
		if (u < 0f || u > 1f) return -1f;
		float qx = sy * e1z - sz * e1y;
		float qy = sz * e1x - sx * e1z;
		float qz = sx * e1y - sy * e1x;
		float v = f * (dx * qx + dy * qy + dz * qz);
		if (v < 0f || u + v > 1f) return -1f;
		float t = f * (e2x * qx + e2y * qy + e2z * qz);
		return t > 1e-3f ? t : -1f;
	}

	/**
	 * Ray vs axis-aligned bounding box (slab method).
	 *
	 * @return ray parameter t at entry, or -1 if no intersection with t &gt; 0
	 */
	private static float rayBoxTest(
		float ox, float oy, float oz, float dx, float dy, float dz,
		float minX, float minY, float minZ, float maxX, float maxY, float maxZ)
	{
		float tmin = Float.NEGATIVE_INFINITY;
		float tmax = Float.POSITIVE_INFINITY;

		if (Math.abs(dx) < 1e-6f)
		{
			if (ox < minX || ox > maxX) return -1f;
		}
		else
		{
			float t1 = (minX - ox) / dx;
			float t2 = (maxX - ox) / dx;
			if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
			tmin = Math.max(tmin, t1);
			tmax = Math.min(tmax, t2);
		}

		if (Math.abs(dy) < 1e-6f)
		{
			if (oy < minY || oy > maxY) return -1f;
		}
		else
		{
			float t1 = (minY - oy) / dy;
			float t2 = (maxY - oy) / dy;
			if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
			tmin = Math.max(tmin, t1);
			tmax = Math.min(tmax, t2);
		}

		if (Math.abs(dz) < 1e-6f)
		{
			if (oz < minZ || oz > maxZ) return -1f;
		}
		else
		{
			float t1 = (minZ - oz) / dz;
			float t2 = (maxZ - oz) / dz;
			if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
			tmin = Math.max(tmin, t1);
			tmax = Math.min(tmax, t2);
		}

		if (tmax < tmin || tmax < 0f) return -1f;
		return tmin > 0f ? tmin : tmax;
	}
}
