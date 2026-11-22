package com.cluehelper.player;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.*;

/**
 * Read from {@code runes.json}, which the test source set's {@code RuneLoader} bakes from the cache's rune tables and the
 * tomes; the daily bake keeps it current with the game cache.
 */
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public final class RuneModel
{
	private static final String FILE = "runes.json";

	final Map<Integer, Integer> pouchRunes;
	/** The rune itself comes first. */
	final Map<Integer, List<Integer>> payersByRune;
	final Map<Integer, Set<Integer>> unlimitingWornItemsByRune;
	final Map<Integer, String> runeNames;
	/** The staves that stand in for runes, all wielded in the one weapon slot; the tomes go in the shield slot. */
	final Set<Integer> staves;

	public static RuneModel read(Gson gson)
	{
		try (InputStream in = RuneModel.class.getResourceAsStream(FILE))
		{
			if (in == null)
			{
				throw new IllegalStateException(FILE + " is missing");
			}
			return gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), RuneModel.class);
		}
		catch (IOException e)
		{
			throw new IllegalStateException("can't read " + FILE, e);
		}
	}

	/** Every item that pays for a rune or stands in for one: runes, combination runes, staves and tomes. */
	public Set<Integer> sourceItemIds()
	{
		Set<Integer> ids = new HashSet<>();
		payersByRune.values().forEach(ids::addAll);
		unlimitingWornItemsByRune.values().forEach(ids::addAll);
		return Set.copyOf(ids);
	}

	public Set<Integer> staves()
	{
		return staves;
	}

	/** -1 for an unknown pouch type. */
	public int pouchRune(int pouchType)
	{
		return pouchRunes.getOrDefault(pouchType, -1);
	}

	public boolean isRune(int itemId)
	{
		return payersByRune.containsKey(itemId);
	}

	private String runeName(int rune)
	{
		return runeNames.getOrDefault(rune, "rune " + rune);
	}

	public Supply supply(int rune, int quantity, PlayerSnapshot player)
	{
		for (int source : unlimitingWornItemsByRune.getOrDefault(rune, Collections.emptySet()))
		{
			if (player.count(Container.WORN, source) > 0)
			{
				return new Supply(true, Integer.MAX_VALUE, List.of(new ItemUse(Container.WORN, source, 1)));
			}
		}
		List<ItemUse> used = new ArrayList<>();
		int have = 0;
		for (Container container : new Container[]{Container.INVENTORY, Container.RUNE_POUCH, Container.BANK, Container.GROUP_STORAGE})
		{
			for (int payer : payersByRune.getOrDefault(rune, List.of(rune)))
			{
				int count = player.count(container, payer);
				if (count > 0 && have < quantity)
				{
					used.add(new ItemUse(container, payer, Math.min(count, quantity - have)));
				}
				have += count;
			}
		}
		return new Supply(have >= quantity, have, have >= quantity ? List.copyOf(used) : List.of());
	}

	public String shortfall(int rune, int quantity, int have)
	{
		return "needs " + quantity + " " + runeName(rune).toLowerCase() + (quantity == 1 ? "" : "s") + " (has " + have + ")";
	}

	@Value
	public static class Supply
	{
		boolean enough;
		/** {@link Integer#MAX_VALUE} for a staff or tome. */
		int have;
		/** Empty unless enough. */
		List<ItemUse> witness;
	}
}
