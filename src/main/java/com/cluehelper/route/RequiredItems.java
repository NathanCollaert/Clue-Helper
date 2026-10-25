package com.cluehelper.route;

import com.cluehelper.memory.StashMemory;
import com.cluehelper.player.Container;
import com.cluehelper.player.ItemUse;
import com.cluehelper.player.PlayerSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.Item;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;

/**
 * Each need is core's own {@link ItemRequirement}, tested through {@code fulfilledBy}; only our own
 * {@link AlternativeRequirement} and {@link GearRequirement} are taken apart, for their other ways and gear picks. Built
 * on the client thread; judging is pure.
 */
@Value
@Builder(toBuilder = true)
public class RequiredItems
{
	public static final RequiredItems NONE = RequiredItems.builder().build();

	@Builder.Default
	List<Need> needs = List.of();
	@Builder.Default
	StashMemory.State stash = StashMemory.State.NONE;
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
		/** The step can't be done without it (an emote's items, Charlie's item), so there is no route while it is missing. */
		boolean blocksRoute;
	}

	@Value
	public static class Verdict
	{
		private static final Verdict MET = new Verdict(true, true, List.of(), List.of(), Map.of());

		/** A STASH known to be filled counts as carried. */
		boolean carried;
		/** Met with the bank added. */
		boolean banked;
		List<ItemUse> withdraw;
		List<Need> missing;
		/** For a missing need with another way to it: the parts of that way also missing. */
		Map<Need, List<String>> missingParts;

		private Verdict(boolean carried, boolean banked, List<ItemUse> withdraw, List<Need> missing, Map<Need, List<String>> missingParts)
		{
			this.carried = carried;
			this.banked = banked;
			this.withdraw = withdraw;
			this.missing = missing;
			this.missingParts = missingParts;
		}

		/** Not met even with the bank: these needs are owned nowhere. */
		static Verdict missing(List<Need> missing, Map<Need, List<String>> missingParts)
		{
			return new Verdict(false, false, List.of(), missing, missingParts);
		}

		/** Met once these are withdrawn at a bank stop. */
		static Verdict fromBank(List<ItemUse> withdraw)
		{
			return new Verdict(false, true, withdraw, List.of(), Map.of());
		}

		/** Met only with a bank stop. */
		public boolean isFromBank()
		{
			return !carried && banked;
		}
	}

	public boolean isEmpty()
	{
		return needs.isEmpty();
	}

	Verdict judge(PlayerSnapshot player, PlayerSnapshot banked)
	{
		List<Need> open = needs.stream().filter(need -> !(need.stepItem && stash == StashMemory.State.FILLED)).collect(Collectors.toList());
		if (open.isEmpty())
		{
			return Verdict.MET;
		}
		Map<Integer, Integer> carried = player.carriedItems();
		if (allMet(open, carried))
		{
			return Verdict.MET;
		}
		if (banked == null || !allMet(open, everything(carried, banked)))
		{
			Map<Integer, Integer> all = banked == null ? carried : everything(carried, banked);
			Item[] owned = PlayerSnapshot.items(all);
			List<Need> missing = open.stream().filter(need -> !need.requirement.fulfilledBy(owned)).collect(Collectors.toList());
			Map<Need, List<String>> parts = missing.stream().filter(need -> need.requirement instanceof AlternativeRequirement)
				.collect(Collectors.toMap(need -> need, need -> ((AlternativeRequirement) need.requirement).missingParts(owned)));
			return Verdict.missing(missing, parts);
		}
		return Verdict.fromBank(minimalWithdraw(open, player, carried, banked));
	}

	/**
	 * Drops what isn't needed, trying the gear picker's own picks last so they are the ones kept, and before them the items
	 * meeting the most needs alone: a made item (a net trap, an unfinished potion) is kept over its parts.
	 */
	private static List<ItemUse> minimalWithdraw(List<Need> open, PlayerSnapshot player, Map<Integer, Integer> carried,
		PlayerSnapshot banked)
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
		Set<Integer> picked = gearPicks(open, player, banked);
		candidates.sort(Comparator.comparing((ItemUse candidate) -> picked.contains(candidate.getItemId()))
			.thenComparingLong(candidate -> needsMetAlone(open, candidate.getItemId())));
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

	private static long needsMetAlone(List<Need> open, int itemId)
	{
		return open.stream().filter(need -> need.requirement instanceof AlternativeRequirement
			? ((AlternativeRequirement) need.requirement).wholeBy(itemId) : need.requirement.fulfilledBy(itemId)).count();
	}

	private static Set<Integer> gearPicks(List<Need> open, PlayerSnapshot player, PlayerSnapshot banked)
	{
		Set<Integer> stored = banked.storedIds();
		Set<Integer> picked = new HashSet<>();
		for (Need need : open)
		{
			if (need.requirement instanceof GearRequirement)
			{
				((GearRequirement) need.requirement).choose(player.getWorn().keySet(), player.getInventory().keySet(), stored)
					.ifPresent(choice -> picked.addAll(choice.getWithdraw()));
			}
		}
		return picked;
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
		Item[] array = PlayerSnapshot.items(items);
		return open.stream().allMatch(need -> need.requirement.fulfilledBy(array));
	}

	private static Map<Integer, Integer> everything(Map<Integer, Integer> carried, PlayerSnapshot banked)
	{
		Map<Integer, Integer> items = new LinkedHashMap<>(carried);
		banked.storedItems().forEach((id, qty) -> items.merge(id, qty, Integer::sum));
		return items;
	}

	private static Map<Integer, Integer> with(Map<Integer, Integer> carried, List<ItemUse> withdrawn)
	{
		Map<Integer, Integer> items = new LinkedHashMap<>(carried);
		withdrawn.forEach(use -> items.merge(use.getItemId(), use.getQuantity(), Integer::sum));
		return items;
	}

}
