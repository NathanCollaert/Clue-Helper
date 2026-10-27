package com.cluehelper.route;

import com.cluehelper.player.*;
import java.util.*;

/** What a route spends: items, charges, cooldowns and cape uses, each with the units available before and after a bank stop. */
final class PlanResources
{
	/** Assumed: no route uses one resource more often than this. */
	private static final int MOST_USES_PER_ROUTE = 8;

	private final Map<String, Integer> ids = new LinkedHashMap<>();
	private final List<int[]> available = new ArrayList<>();
	private final List<Integer> mostPerUse = new ArrayList<>();
	int cape = -1;

	int item(int itemId, PlayerSnapshot player, int bankState)
	{
		int have = 0;
		for (Container container : Container.values())
		{
			have += player.count(container, itemId);
		}
		return set("item:" + itemId, bankState, have);
	}

	int charge(int itemId, PlayerSnapshot player, int bankState)
	{
		Charge charge = player.getCharges().get(itemId);
		int perItem = charge == null ? 1 : Math.max(0, charge.getCharges());
		int held = player.carried(itemId) + player.stored(itemId);
		return set("charge:" + itemId, bankState, Math.max(1, held) * perItem);
	}

	int once(String key, int bankState)
	{
		return set(key, bankState, 1);
	}

	int capeUses(int left, int bankState)
	{
		cape = set("cape", bankState, left);
		return cape;
	}

	int available(int resource, int bankState)
	{
		return available.get(resource)[bankState];
	}

	void used(int resource, int units)
	{
		mostPerUse.set(resource, Math.max(mostPerUse.get(resource), units));
	}

	boolean tracked(int resource)
	{
		int most = Math.max(available.get(resource)[0], available.get(resource)[1]);
		return resource == cape || most < MOST_USES_PER_ROUTE * mostPerUse.get(resource);
	}

	String name(int resource)
	{
		return new ArrayList<>(ids.keySet()).get(resource);
	}

	private int set(String key, int bankState, int units)
	{
		Integer id = ids.get(key);
		if (id == null)
		{
			id = ids.size();
			ids.put(key, id);
			available.add(new int[2]);
			mostPerUse.add(0);
		}
		available.get(id)[bankState] = Math.max(available.get(id)[bankState], units);
		return id;
	}
}
