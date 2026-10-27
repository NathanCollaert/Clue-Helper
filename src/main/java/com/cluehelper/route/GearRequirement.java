package com.cluehelper.route;

import java.util.*;
import java.util.stream.Collectors;
import lombok.Value;
import net.runelite.api.*;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;

/** Worn gear adding up to a bonus total, as a need: any owned piece that adds to it counts as one of its items. */
@Value
public class GearRequirement implements ItemRequirement
{
	String name;
	int atLeast;
	/** By item id: the owned items that add to the total. */
	Map<Integer, GearPicker.Piece> pieces;

	@Override
	public boolean fulfilledBy(int itemId)
	{
		return pieces.containsKey(itemId);
	}

	@Override
	public boolean fulfilledBy(Item[] items)
	{
		Set<Integer> ids = Arrays.stream(items).map(Item::getId).collect(Collectors.toSet());
		return choose(Set.of(), ids, Set.of()).isPresent();
	}

	@Override
	public String getCollectiveName(Client client)
	{
		return name;
	}

	public Optional<GearPicker.Choice> choose(Set<Integer> worn, Set<Integer> inventory, Set<Integer> stored)
	{
		return GearPicker.pick(pieces.values(), worn, inventory, stored, atLeast);
	}

	public boolean isWorn(Set<Integer> worn)
	{
		return choose(worn, Set.of(), Set.of()).map(choice -> choice.getEquip().isEmpty()).orElse(false);
	}
}
