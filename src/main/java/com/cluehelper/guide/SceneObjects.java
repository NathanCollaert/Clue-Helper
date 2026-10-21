package com.cluehelper.guide;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/** The loaded scene's objects a sub-step uses: house furniture by base id (every floor), or the bank at a stop. */
final class SceneObjects
{
	private static final String BANK = "Bank";
	/** Bank chests (the Crafting Guild's) open with Use. */
	private static final String USE = "Use";

	private SceneObjects()
	{
	}

	/** Booths and chests with a Bank option near the bank stop's tile (Nathan, B16: outline the bank, not the tile). */
	static List<TileObject> banksNear(Client client, WorldPoint at, int radius)
	{
		List<TileObject> found = new ArrayList<>();
		WorldView view = client.getTopLevelWorldView();
		if (at == null || view == null || at.getPlane() != view.getPlane())
		{
			return found;
		}
		Tile[][] plane = view.getScene().getTiles()[view.getPlane()];
		for (Tile[] column : plane)
		{
			for (Tile tile : column)
			{
				if (tile == null || tile.getWorldLocation().distanceTo2D(at) > radius)
				{
					continue;
				}
				for (GameObject object : tile.getGameObjects())
				{
					if (object != null && !found.contains(object) && banks(client.getObjectDefinition(object.getId())))
					{
						found.add(object);
					}
				}
			}
		}
		return found;
	}

	/** Bankers near the bank stop's tile. */
	static List<NPC> bankersNear(Client client, WorldPoint at, int radius)
	{
		List<NPC> found = new ArrayList<>();
		WorldView view = client.getTopLevelWorldView();
		if (at == null || view == null)
		{
			return found;
		}
		for (NPC npc : view.npcs())
		{
			NPCComposition composition = npc.getTransformedComposition();
			if (composition != null && hasAction(composition.getActions(), BANK) && npc.getWorldLocation().distanceTo2D(at) <= radius)
			{
				found.add(npc);
			}
		}
		return found;
	}

	/** NPCs in the scene with one of these ids, as spawned or as transformed (Renu, B17). */
	static List<NPC> npcs(Client client, Set<Integer> ids)
	{
		List<NPC> found = new ArrayList<>();
		WorldView view = client.getTopLevelWorldView();
		if (ids.isEmpty() || view == null)
		{
			return found;
		}
		for (NPC npc : view.npcs())
		{
			NPCComposition composition = npc.getTransformedComposition();
			if (ids.contains(npc.getId()) || composition != null && ids.contains(composition.getId()))
			{
				found.add(npc);
			}
		}
		return found;
	}

	private static boolean banks(ObjectComposition composition)
	{
		if (composition == null)
		{
			return false;
		}
		ObjectComposition shown = composition.getImpostorIds() != null ? composition.getImpostor() : composition;
		if (shown == null)
		{
			return false;
		}
		boolean bankChest = shown.getName() != null && shown.getName().toLowerCase().startsWith("bank")
			&& hasAction(shown.getActions(), USE);
		return hasAction(shown.getActions(), BANK) || bankChest;
	}

	private static boolean hasAction(String[] actions, String wanted)
	{
		if (actions == null)
		{
			return false;
		}
		for (String action : actions)
		{
			if (wanted.equalsIgnoreCase(action))
			{
				return true;
			}
		}
		return false;
	}

	static List<TileObject> find(Client client, Set<Integer> ids)
	{
		List<TileObject> found = new ArrayList<>();
		WorldView view = client.getTopLevelWorldView();
		if (ids.isEmpty() || view == null)
		{
			return found;
		}
		Scene scene = view.getScene();
		for (Tile[][] plane : scene.getTiles())
		{
			collect(plane, ids, found);
		}
		return found;
	}

	/** A house entry lands on one floor and moves up the next tick (B14): every floor is searched. */
	private static void collect(Tile[][] plane, Set<Integer> ids, List<TileObject> found)
	{
		for (Tile[] column : plane)
		{
			for (Tile tile : column)
			{
				if (tile == null)
				{
					continue;
				}
				for (GameObject object : tile.getGameObjects())
				{
					if (object != null && ids.contains(object.getId()) && !found.contains(object))
					{
						found.add(object);
					}
				}
				for (TileObject object : new TileObject[]{tile.getWallObject(), tile.getDecorativeObject(), tile.getGroundObject()})
				{
					if (object != null && ids.contains(object.getId()))
					{
						found.add(object);
					}
				}
			}
		}
	}
}
