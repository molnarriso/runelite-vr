/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 */
package net.runelite.client.plugins.vrgpu;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.AABB;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ItemLayer;
import net.runelite.api.ItemComposition;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Scene;
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
@Slf4j
final class VrSceneRaycaster
{
	private final Client client;

	VrSceneRaycaster(Client client)
	{
		this.client = client;
	}

	/**
	 * Cast a ray through the scene and return the closest intersected entity, if any.
	 * Tests actors and tile entities against actual model geometry.
	 */
	VrInteraction.Hit raycastScene(float ox, float oy, float oz,
		float dx, float dy, float dz, WorldView wv, VrInteraction.GroundHit groundHit, boolean logRaycast)
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

		Tile[][] sceneTiles = getPlaneTiles(wv);
		if (sceneTiles != null)
		{
			for (int scX = 0; scX < wv.getSizeX(); scX++)
			{
				for (int scY = 0; scY < wv.getSizeY(); scY++)
				{
					Tile tile = getTile(sceneTiles, scX, scY);
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
							obj,
							logRaycast,
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
							wall,
							logRaycast,
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
							deco,
							logRaycast,
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
							ground,
							logRaycast,
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
							VrInteraction.Hit hit = buildGroundItemHit(itemT, scX, scY, tile.getItemLayer(), item);
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
		if (logRaycast)
		{
			logRaycast(hits, groundHit);
		}
		return hits.isEmpty() ? null : hits.get(0);
	}

	private void logRaycast(List<VrInteraction.Hit> hits, VrInteraction.GroundHit groundHit)
	{
		StringBuilder sb = new StringBuilder("VR raycast (")
			.append(hits.size()).append(" spatial hits)");

		if (groundHit != null)
		{
			sb.append(" ground=(").append(groundHit.sceneX).append(",").append(groundHit.sceneY)
				.append(") t=").append(String.format("%.1f", groundHit.t))
				.append(" local=(").append(String.format("%.1f", groundHit.x)).append(',')
				.append(String.format("%.1f", groundHit.y)).append(',')
				.append(String.format("%.1f", groundHit.z)).append(')');
		}
		sb.append('\n');

		for (VrInteraction.Hit hit : hits)
		{
			sb.append(String.format("  %s %s [scene=%d,%d t=%.1f]\n",
				hit.entityType, hit.entityName, hit.sceneX, hit.sceneY, hit.t));
		}

		log.info("{}", sb);
	}

	VrInteraction.GroundHit intersectGround(float ox, float oy, float oz, float dx, float dy, float dz, WorldView wv)
	{
		VrInteraction.GroundHit best = null;
		int plane = wv.getPlane();
		Tile[][] tiles = getPlaneTiles(wv);
		if (tiles != null)
		{
			for (int sceneX = 0; sceneX < wv.getSizeX(); sceneX++)
			{
				for (int sceneY = 0; sceneY < wv.getSizeY(); sceneY++)
				{
					Tile tile = getTile(tiles, sceneX, sceneY);
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

		return best;
	}

	private static Tile[][] getPlaneTiles(WorldView wv)
	{
		if (wv == null)
		{
			return null;
		}

		Scene scene = wv.getScene();
		if (scene == null)
		{
			return null;
		}

		Tile[][][] tiles = scene.getTiles();
		int plane = wv.getPlane();
		if (tiles == null || plane < 0 || plane >= tiles.length)
		{
			return null;
		}

		return tiles[plane];
	}

	private static Tile getTile(Tile[][] tiles, int sceneX, int sceneY)
	{
		if (sceneX < 0 || sceneX >= tiles.length || tiles[sceneX] == null
			|| sceneY < 0 || sceneY >= tiles[sceneX].length)
		{
			return null;
		}

		return tiles[sceneX][sceneY];
	}

	private VrInteraction.Hit buildNpcHit(float ox, float oy, float oz, float dx, float dy, float dz, int plane, NPC npc)
	{
		Model model = npc.getModel();
		LocalPoint lp = npc.getLocalLocation();
		if (model == null || lp == null)
		{
			return null;
		}

		float t = rayTestModelAabb(ox, oy, oz, dx, dy, dz, model, npc.getCurrentOrientation() & 2047,
			lp.getX(), Perspective.getTileHeight(client, lp, plane), lp.getY());
		if (t <= 0f)
		{
			return null;
		}

		VrInteraction.Hit hit = new VrInteraction.Hit();
		hit.t = t;
		hit.entityType = "npc";
		hit.entityName = safeName(npc.getName(), "NPC");
		hit.sceneX = lp.getX() >> 7;
		hit.sceneY = lp.getY() >> 7;
		hit.npc = npc;
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

		float t = rayTestModelAabb(ox, oy, oz, dx, dy, dz, model, player.getCurrentOrientation() & 2047,
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
		hit.player = player;
		return hit;
	}

	private VrInteraction.Hit buildObjectHit(float ox, float oy, float oz, float dx, float dy, float dz,
		String entityType, int sceneX, int sceneY, int objId, net.runelite.api.TileObject object,
		boolean logMiss, VrInteraction.RenderablePlacement... placements)
	{
		ObjectComposition def = client.getObjectDefinition(objId);
		if (def == null)
		{
			if (logMiss)
			{
				log.info("VR raycast object miss: {} id={} scene=({},{}), object definition is null",
					entityType, objId, sceneX, sceneY);
			}
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
		int renderablePlacements = 0;
		int modelPlacements = 0;
		for (VrInteraction.RenderablePlacement placement : placements)
		{
			if (placement == null || placement.renderable == null)
			{
				continue;
			}
			renderablePlacements++;

			// Mirror RuneLite's outline renderer: a Renderable can already be the Model.
			Model model = placement.renderable instanceof Model
				? (Model) placement.renderable
				: placement.renderable.getModel();
			if (model == null)
			{
				continue;
			}
			modelPlacements++;

			float t = rayTestModelAabb(ox, oy, oz, dx, dy, dz, model, placement.orientation, placement.x, placement.y, placement.z);
			if (t > 0f && t < bestT)
			{
				bestT = t;
			}
		}

		if (bestT == Float.MAX_VALUE)
		{
			if (logMiss)
			{
				String reason;
				if (renderablePlacements == 0)
				{
					reason = "no renderable placements";
				}
				else if (modelPlacements == 0)
				{
					reason = "no renderable models";
				}
				else
				{
					reason = "ray missed all model AABBs";
				}
				log.info("VR raycast object miss: {} id={} name={} scene=({},{}), {}",
					entityType, objId, safeName(def.getName(), "Object"), sceneX, sceneY, reason);
			}
			return null;
		}

		VrInteraction.Hit hit = new VrInteraction.Hit();
		hit.t = bestT;
		hit.entityType = entityType;
		hit.entityName = safeName(def.getName(), "Object");
		hit.sceneX = sceneX;
		hit.sceneY = sceneY;
		hit.tileObject = object;
		return hit;
	}

	private VrInteraction.Hit buildGroundItemHit(float t, int sceneX, int sceneY, ItemLayer layer, TileItem item)
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
		hit.tileObject = layer;
		hit.itemLayer = layer;
		hit.tileItem = item;
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

	private static float rayTestModelAabb(float ox, float oy, float oz,
		float dx, float dy, float dz,
		Model m, int orient, int entityX, int entityY, int entityZ)
	{
		AABB aabb = m.getAABB(orient);
		float cx = entityX + aabb.getCenterX();
		float cy = entityY + aabb.getCenterY();
		float cz = entityZ + aabb.getCenterZ();
		// VR picking intentionally uses a padded model AABB as the identity target.
		// The desktop client still supplies the final action; this only answers "what entity is the controller pointing at?"
		float ex = aabb.getExtremeX() + 16f;
		float ey = aabb.getExtremeY() + 16f;
		float ez = aabb.getExtremeZ() + 16f;
		return rayBoxTest(ox, oy, oz, dx, dy, dz, cx - ex, cy - ey, cz - ez, cx + ex, cy + ey, cz + ez);
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
