package com.cluehelper.guide;

import java.util.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;

/** Client thread only. */
@Singleton
final class SceneObjects
{
	private static final String BANK = "Bank";
	/** Some bank chests (the Crafting Guild's) open with Use. */
	private static final String USE = "Use";

	@Inject
	private Client client;

	List<TileObject> banksNear(WorldPoint at, int radius)
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

	List<NPC> bankersNear(WorldPoint at, int radius)
	{
		return npcsNear(at, radius, BANK);
	}

	/** The NPCs within {@code radius} of {@code at} that have {@code option}. */
	List<NPC> npcsNear(WorldPoint at, int radius, String option)
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
			if (composition != null && hasAction(composition.getActions(), option) && npc.getWorldLocation().distanceTo2D(at) <= radius)
			{
				found.add(npc);
			}
		}
		return found;
	}

	/** By the names menus show, normalised. */
	List<NPC> npcsNamed(Set<String> names)
	{
		List<NPC> found = new ArrayList<>();
		WorldView view = client.getTopLevelWorldView();
		if (names.isEmpty() || view == null)
		{
			return found;
		}
		for (NPC npc : view.npcs())
		{
			if (npc.getName() != null && names.contains(MenuLabels.normalise(npc.getName())))
			{
				found.add(npc);
			}
		}
		return found;
	}

	List<NPC> npcs(Set<Integer> ids)
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

	/** A house entry lands on one floor and moves up the next tick, so every floor is searched. */
	List<TileObject> find(Set<Integer> ids)
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

	private void collect(Tile[][] plane, Set<Integer> ids, List<TileObject> found)
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
					if (object != null && isOneOf(object, ids) && !found.contains(object))
					{
						found.add(object);
					}
				}
				for (TileObject object : new TileObject[]{tile.getWallObject(), tile.getDecorativeObject(), tile.getGroundObject()})
				{
					if (object != null && isOneOf(object, ids))
					{
						found.add(object);
					}
				}
			}
		}
	}

	/**
	 * By its own id, or any form it can show: a trapdoor (12266) shows as closed (12267) or open (12268), and stays the
	 * step's while it is open.
	 */
	private boolean isOneOf(TileObject object, Set<Integer> ids)
	{
		if (ids.contains(object.getId()))
		{
			return true;
		}
		int[] forms = client.getObjectDefinition(object.getId()).getImpostorIds();
		return forms != null && Arrays.stream(forms).anyMatch(ids::contains);
	}
}
