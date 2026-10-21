package com.cluehelper.route;

import com.cluehelper.player.Container;
import com.cluehelper.player.ItemUse;
import com.cluehelper.player.PlayerSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.Item;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;

/**
 * Each need is core's own {@link ItemRequirement}, tested only through {@code fulfilledBy}. Built on the client
 * thread; judging is pure.
 */
@Value
@Builder(toBuilder = true)
public class RequiredItems
{
	public static final RequiredItems NONE = RequiredItems.builder().build();

	public enum Stash
	{
		NONE, NOT_BUILT, FILLED, EMPTY, UNKNOWN
	}

	@Builder.Default
	List<Need> needs = List.of();
	@Builder.Default
	Stash stash = Stash.NONE;
	/** The planner doesn't route to the tool leprechaun, it only notes it. */
	boolean spadeAtLeprechaun;

	@Value
	public static class Need
	{
		String name;
		ItemRequirement requirement;
		boolean stepItem;
		/** Core's *clue* bank tag lists it already, so our bank highlights leave it out. */
		boolean inCoreTag;
	}

	@Value
	public static class Verdict
	{
		public static final Verdict MET = new Verdict(true, true, List.of(), List.of());

		/** A STASH known to be filled counts as carried. */
		boolean carried;
		/** Met with the bank added. */
		boolean banked;
		List<ItemUse> withdraw;
		List<Need> missing;
	}

	public boolean isEmpty()
	{
		return needs.isEmpty();
	}

	Verdict judge(PlayerSnapshot player, PlayerSnapshot banked)
	{
		List<Need> open = needs.stream().filter(need -> !(need.stepItem && stash == Stash.FILLED)).collect(Collectors.toList());
		if (open.isEmpty())
		{
			return Verdict.MET;
		}
		Map<Integer, Integer> carried = carried(player);
		if (allMet(open, carried))
		{
			return Verdict.MET;
		}
		if (banked == null || !allMet(open, everything(carried, banked)))
		{
			Map<Integer, Integer> all = banked == null ? carried : everything(carried, banked);
			List<Need> missing = open.stream().filter(need -> !need.requirement.fulfilledBy(items(all))).collect(Collectors.toList());
			return new Verdict(false, false, List.of(), missing);
		}
		return new Verdict(false, true, minimalWithdraw(open, carried, banked), List.of());
	}

	private static List<ItemUse> minimalWithdraw(List<Need> open, Map<Integer, Integer> carried, PlayerSnapshot banked)
	{
		List<ItemUse> candidates = new ArrayList<>();
		addCandidates(open, Container.BANK, banked.getBank(), candidates);
		addCandidates(open, Container.GROUP_STORAGE, banked.getGroupStorage(), candidates);
		if (!allMet(open, with(carried, candidates)))
		{
			candidates.clear();
			banked.getBank().forEach((id, qty) -> candidates.add(new ItemUse(Container.BANK, id, qty)));
			banked.getGroupStorage().forEach((id, qty) -> candidates.add(new ItemUse(Container.GROUP_STORAGE, id, qty)));
		}
		List<ItemUse> kept = new ArrayList<>(candidates);
		for (ItemUse candidate : candidates)
		{
			kept.remove(candidate);
			if (!allMet(open, with(carried, kept)))
			{
				kept.add(candidate);
			}
		}
		return List.copyOf(kept);
	}

	private static void addCandidates(List<Need> open, Container container, Map<Integer, Integer> stored, List<ItemUse> out)
	{
		stored.entrySet().stream()
			.filter(e -> open.stream().anyMatch(need -> need.requirement.fulfilledBy(e.getKey())))
			.sorted(Map.Entry.comparingByKey())
			.forEach(e -> out.add(new ItemUse(container, e.getKey(), e.getValue())));
	}

	private static boolean allMet(List<Need> open, Map<Integer, Integer> items)
	{
		Item[] array = items(items);
		return open.stream().allMatch(need -> need.requirement.fulfilledBy(array));
	}

	private static Map<Integer, Integer> carried(PlayerSnapshot player)
	{
		Map<Integer, Integer> items = new LinkedHashMap<>(player.getInventory());
		player.getWorn().forEach((id, qty) -> items.merge(id, qty, Integer::sum));
		return items;
	}

	private static Map<Integer, Integer> everything(Map<Integer, Integer> carried, PlayerSnapshot banked)
	{
		Map<Integer, Integer> items = new LinkedHashMap<>(carried);
		banked.getBank().forEach((id, qty) -> items.merge(id, qty, Integer::sum));
		banked.getGroupStorage().forEach((id, qty) -> items.merge(id, qty, Integer::sum));
		return items;
	}

	private static Map<Integer, Integer> with(Map<Integer, Integer> carried, List<ItemUse> withdrawn)
	{
		Map<Integer, Integer> items = new LinkedHashMap<>(carried);
		withdrawn.forEach(use -> items.merge(use.getItemId(), use.getQuantity(), Integer::sum));
		return items;
	}

	private static Item[] items(Map<Integer, Integer> items)
	{
		return items.entrySet().stream().map(e -> new Item(e.getKey(), e.getValue())).toArray(Item[]::new);
	}
}
