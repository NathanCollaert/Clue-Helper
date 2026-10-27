package com.cluehelper.guide;

import java.util.*;
import java.util.function.Predicate;
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
		for (Tile tile : at == null ? List.<Tile>of() : tilesOnFloor())
		{
			WorldPoint where = tile.getWorldLocation();
			if (where.getPlane() != at.getPlane() || where.distanceTo2D(at) > radius)
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
		return found;
	}

	List<NPC> bankersNear(WorldPoint at, int radius)
	{
		return npcsNear(at, radius, BANK);
	}

	/** The NPCs within {@code radius} of {@code at} that have {@code option}. */
	List<NPC> npcsNear(WorldPoint at, int radius, String option)
	{
		return at == null ? List.of() : npcs(npc -> offers(npc, option) && npc.getWorldLocation().distanceTo2D(at) <= radius);
	}

	/** By the names menus show, normalised. */
	List<NPC> npcsNamed(Set<String> names)
	{
		return npcs(npc -> npc.getName() != null && names.contains(MenuLabels.normalise(npc.getName())));
	}

	List<NPC> npcs(Set<Integer> ids)
	{
		return npcs(npc -> ids.contains(npc.getId())
			|| npc.getTransformedComposition() != null && ids.contains(npc.getTransformedComposition().getId()));
	}

	/** The loaded NPCs that {@code wanted} accepts. */
	List<NPC> npcs(Predicate<NPC> wanted)
	{
		List<NPC> found = new ArrayList<>();
		WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			return found;
		}
		for (NPC npc : view.npcs())
		{
			if (wanted.test(npc))
			{
				found.add(npc);
			}
		}
		return found;
	}

	private static boolean offers(NPC npc, String option)
	{
		NPCComposition composition = npc.getTransformedComposition();
		return composition != null && Widgets.hasAction(composition.getActions(), option);
	}

	private static boolean banks(ObjectComposition composition)
	{
		if (composition == null)
		{
			return false;
		}
		ObjectComposition shown = LeftClicks.shownForm(composition);
		boolean bankChest = shown.getName() != null && shown.getName().toLowerCase().startsWith("bank")
			&& Widgets.hasAction(shown.getActions(), USE);
		return Widgets.hasAction(shown.getActions(), BANK) || bankChest;
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
					if (object != null && isFormOf(object.getId(), ids) && !found.contains(object))
					{
						found.add(object);
					}
				}
				for (TileObject object : new TileObject[]{tile.getWallObject(), tile.getDecorativeObject(), tile.getGroundObject()})
				{
					if (object != null && isFormOf(object.getId(), ids))
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
	boolean isFormOf(int objectId, Set<Integer> ids)
	{
		if (ids.contains(objectId))
		{
			return true;
		}
		int[] forms = client.getObjectDefinition(objectId).getImpostorIds();
		return forms != null && Arrays.stream(forms).anyMatch(ids::contains);
	}

	/** Every loaded tile on the player's floor with that item on the ground. */
	List<WorldPoint> groundItemTiles(int itemId)
	{
		List<WorldPoint> found = new ArrayList<>();
		for (Tile tile : tilesOnFloor())
		{
			List<TileItem> items = tile.getGroundItems();
			if (items != null && items.stream().anyMatch(item -> item.getId() == itemId))
			{
				found.add(tile.getWorldLocation());
			}
		}
		return List.copyOf(found);
	}

	/** The loaded tiles on the player's floor; none while no scene is loaded. */
	private List<Tile> tilesOnFloor()
	{
		List<Tile> tiles = new ArrayList<>();
		WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			return tiles;
		}
		for (Tile[] column : view.getScene().getTiles()[view.getPlane()])
		{
			for (Tile tile : column)
			{
				if (tile != null)
				{
					tiles.add(tile);
				}
			}
		}
		return tiles;
	}
}
