package com.cluehelper.route;

import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.player.RuneModel;
import com.cluehelper.step.Stage;
import com.cluehelper.step.Step;
import com.cluehelper.transport.ItemClause;
import com.cluehelper.transport.Requirements;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;

/**
 * The stored items a route could use. A recording keeps only these of the bank, so it plans the same route without
 * holding the rest of the player's bank.
 */
public final class UsableItems
{
	private UsableItems()
	{
	}

	/**
	 * Any item a teleport, a spellbook change or a walk gate asks for, runes and what stands in for them, and stored items
	 * that meet one of the step's needs or stages.
	 */
	public static Set<Integer> of(PlanInput input, RuneModel runes, RequiredItems required, Step travel)
	{
		Set<Integer> usable = new HashSet<>(runes.sourceItemIds());
		input.getCatalog().getTeleports().forEach(row -> addItems(row.getRequirements(), usable));
		input.getCatalog().getSpellbookChanges().forEach(change -> addItems(change.getRequirements(), usable));
		input.getTable().getGates().forEach(gate -> addItems(gate, usable));
		usable.add(ItemID.HUNTSMANS_KIT);
		if (input.getBanked() != null)
		{
			for (int id : input.getBanked().storedIds())
			{
				if (meetsANeed(required, id) || travel.getStages().stream().anyMatch(stage -> meets(stage, id)))
				{
					usable.add(id);
				}
			}
		}
		return usable;
	}

	/** Carried and worn items stay whole; bank and group storage keep only usable items, charges only for kept ones. */
	public static PlayerSnapshot cutDown(PlayerSnapshot snapshot, Set<Integer> usable)
	{
		if (snapshot == null)
		{
			return null;
		}
		Map<Integer, Integer> bank = only(snapshot.getBank(), usable);
		Map<Integer, Integer> groupStorage = only(snapshot.getGroupStorage(), usable);
		Set<Integer> kept = new HashSet<>(snapshot.carriedIds());
		kept.addAll(snapshot.getPouch().keySet());
		kept.addAll(bank.keySet());
		kept.addAll(groupStorage.keySet());
		return snapshot.toBuilder()
			.bank(bank)
			.groupStorage(groupStorage)
			.charges(only(snapshot.getCharges(), kept))
			.build();
	}

	private static boolean meetsANeed(RequiredItems required, int itemId)
	{
		return required.getNeeds().stream().map(RequiredItems.Need::getRequirement).anyMatch(need -> need.fulfilledBy(itemId));
	}

	private static boolean meets(Stage stage, int itemId)
	{
		ItemRequirement item = stage.getItem();
		return item != null && item.fulfilledBy(itemId);
	}

	private static void addItems(Requirements requirements, Set<Integer> ids)
	{
		if (requirements == null)
		{
			return;
		}
		for (ItemClause clause : requirements.getItems())
		{
			clause.getBranches().forEach(branch -> ids.addAll(branch.getIds()));
		}
	}

	private static <V> Map<Integer, V> only(Map<Integer, V> items, Set<Integer> ids)
	{
		return items.entrySet().stream().filter(item -> ids.contains(item.getKey()))
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, TreeMap::new));
	}
}
