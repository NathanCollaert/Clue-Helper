package com.cluehelper.route;

import java.util.List;
import java.util.stream.Collectors;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;

/** Core's item, or every one of the parts that make it (a vial of water and a cadantine for the unfinished potion). */
final class AlternativeRequirement implements ItemRequirement
{
	@Value
	static class Part
	{
		String name;
		ItemRequirement requirement;
	}

	private final ItemRequirement item;
	private final List<Part> parts;

	AlternativeRequirement(ItemRequirement item, List<Part> parts)
	{
		this.item = item;
		this.parts = List.copyOf(parts);
	}

	@Override
	public boolean fulfilledBy(int itemId)
	{
		return item.fulfilledBy(itemId) || parts.stream().anyMatch(part -> part.requirement.fulfilledBy(itemId));
	}

	@Override
	public boolean fulfilledBy(Item[] items)
	{
		return item.fulfilledBy(items) || parts.stream().allMatch(part -> part.requirement.fulfilledBy(items));
	}

	@Override
	public String getCollectiveName(Client client)
	{
		return item.getCollectiveName(client);
	}

	/** Meets the need alone: core's item, or a one-part other way (a net trap for its net and rope). */
	boolean wholeBy(int itemId)
	{
		return item.fulfilledBy(itemId) || parts.size() == 1 && parts.get(0).requirement.fulfilledBy(itemId);
	}

	/** The parts these items lack, by name. */
	List<String> missingParts(Item[] items)
	{
		return parts.stream().filter(part -> !part.requirement.fulfilledBy(items)).map(Part::getName).collect(Collectors.toList());
	}
}
