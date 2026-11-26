package com.cluehelper.route;

import java.util.List;
import java.util.function.BooleanSupplier;
import lombok.Value;
import net.runelite.api.*;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;
import static java.util.stream.Collectors.*;

/**
 * Core's item, or every one of the parts that make it (a vial of water and a cadantine for the unfinished potion). An item
 * made there (black d'hide on Entrana, which turns armour away) is met by its parts, and by itself only once the player is
 * there: one in the bank, or carried from elsewhere, is never taken.
 */
final class AlternativeRequirement implements ItemRequirement
{
	@Value
	static class Part
	{
		String name;
		ItemRequirement requirement;
	}

	final ItemRequirement item;
	final List<Part> parts;
	final boolean madeThere;
	final BooleanSupplier atThePlace;

	AlternativeRequirement(ItemRequirement item, List<Part> parts, boolean madeThere, BooleanSupplier atThePlace)
	{
		this.item = item;
		this.parts = List.copyOf(parts);
		this.madeThere = madeThere;
		this.atThePlace = atThePlace;
	}

	@Override
	public boolean fulfilledBy(int itemId)
	{
		return !madeThere && item.fulfilledBy(itemId) || parts.stream().anyMatch(part -> part.requirement.fulfilledBy(itemId));
	}

	@Override
	public boolean fulfilledBy(Item[] items)
	{
		boolean itemCounts = !madeThere || atThePlace.getAsBoolean();
		return itemCounts && item.fulfilledBy(items) || parts.stream().allMatch(part -> part.requirement.fulfilledBy(items));
	}

	@Override
	public String getCollectiveName(Client client)
	{
		return item.getCollectiveName(client);
	}

	/** Meets the need alone: core's item, or a one-part other way (a net trap for its net and rope). */
	boolean wholeBy(int itemId)
	{
		return !madeThere && item.fulfilledBy(itemId) || parts.size() == 1 && parts.get(0).requirement.fulfilledBy(itemId);
	}

	/** Core's own item where it is made there: a banked one is no use, as it can't be brought. */
	boolean leftBehind(int itemId)
	{
		return madeThere && item.fulfilledBy(itemId);
	}

	/** The parts these items lack, by name. */
	List<String> missingParts(Item[] items)
	{
		return parts.stream().filter(part -> !part.requirement.fulfilledBy(items)).map(Part::getName).collect(toList());
	}
}
