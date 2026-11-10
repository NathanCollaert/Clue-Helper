package com.cluehelper.guide;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;

/** Client thread only. */
@Singleton
public final class SceneObjects
{
	private static final String BANK = "Bank";
	/** Some bank chests open with Use. */
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
		Set<Integer> forms = withNpcForms(ids);
		return npcs(npc -> isNpcOf(npc, forms));
	}

	/**
	 * The ids with the NPCs their multi-NPC forms show as: a clue's Viggora (6122) is the Mysterious ghost (3455) that
	 * spawns.
	 */
	Set<Integer> withNpcForms(Set<Integer> ids)
	{
		Set<Integer> forms = new HashSet<>(ids);
		for (int id : ids)
		{
			NPCComposition npc = client.getNpcDefinition(id);
			int[] configs = npc == null ? null : npc.getConfigs();
			if (configs != null)
			{
				Arrays.stream(configs).filter(form -> form >= 0).forEach(forms::add);
			}
		}
		return forms;
	}

	static boolean isNpcOf(NPC npc, Set<Integer> ids)
	{
		return ids.contains(npc.getId()) || npc.getTransformedComposition() != null && ids.contains(npc.getTransformedComposition().getId());
	}

	List<NPC> npcs(Predicate<NPC> wanted)
	{
		WorldView view = client.getTopLevelWorldView();
		return view == null ? List.of() : view.npcs().stream().filter(wanted).collect(Collectors.toList());
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
		WorldView view = client.getTopLevelWorldView();
		if (ids.isEmpty() || view == null)
		{
			return List.of();
		}
		return Arrays.stream(view.getScene().getTiles()).flatMap(SceneObjects::loaded).flatMap(tile -> objectsOn(tile).stream())
			.filter(object -> isFormOf(object.getId(), ids)).distinct().collect(Collectors.toList());
	}

	/** The tile's game objects, then its wall, ground and decorative object: a large object is on each tile it covers. */
	public static List<TileObject> objectsOn(Tile tile)
	{
		Stream<TileObject> others = Stream.of(tile.getWallObject(), tile.getGroundObject(), tile.getDecorativeObject());
		return Stream.concat(Arrays.stream(tile.getGameObjects()), others).filter(Objects::nonNull).collect(Collectors.toList());
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
		return tilesOnFloor().stream()
			.filter(tile -> tile.getGroundItems() != null && tile.getGroundItems().stream().anyMatch(item -> item.getId() == itemId))
			.map(Tile::getWorldLocation).collect(Collectors.toUnmodifiableList());
	}

	/** The loaded tiles on the player's floor; none while no scene is loaded. */
	private List<Tile> tilesOnFloor()
	{
		WorldView view = client.getTopLevelWorldView();
		return view == null ? List.of() : loaded(view.getScene().getTiles()[view.getPlane()]).collect(Collectors.toList());
	}

	private static Stream<Tile> loaded(Tile[][] plane)
	{
		return Arrays.stream(plane).flatMap(Arrays::stream).filter(Objects::nonNull);
	}
}
