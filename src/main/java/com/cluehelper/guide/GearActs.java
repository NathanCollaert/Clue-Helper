package com.cluehelper.guide;

import com.cluehelper.player.*;
import com.cluehelper.route.*;
import com.cluehelper.step.*;
import java.util.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.plugins.cluescrolls.clues.item.*;
import static java.util.stream.Collectors.*;

/** Acts that put gear on or take it off: what to change now, from what is carried and worn, and when it is done. */
@Singleton
final class GearActs
{
	private static final String EQUIP_TEXT = "Equip:";
	private static final String TAKE_OFF_TEXT = "Take off:";

	@Inject
	Client client;
	@Inject
	PlayerModel player;
	@Inject
	StepNeeds stepNeeds;
	@Inject
	LeftClicks leftClicks;

	/** A gear act as the pieces to put on or take off now; any other sub-step, or one there is no step for, as it is. */
	SubStep refresh(SubStep act, Step step)
	{
		switch (act.getDone())
		{
			case WORN:
				return step == null || !act.getNpcIds().isEmpty() ? act : equip(act, step);
			case SLOTS_EMPTY:
				return removeGear(act);
			default:
				return act;
		}
	}

	/** For an act done by {@link SubStep.Done#WORN} or {@code SLOTS_EMPTY}. */
	boolean isDone(SubStep act, Step step)
	{
		switch (act.getDone())
		{
			case WORN:
				return step != null && player.getSnapshot() != null && unmet(act, step).isEmpty();
			case SLOTS_EMPTY:
				return slotsEmptied(act) || act.isWornWayIn() && step != null && player.getSnapshot() != null
					&& unmet(act, step).isEmpty();
			default:
				throw new IllegalArgumentException("not a gear act: " + act.getDone());
		}
	}

	/**
	 * The pieces still to put on, by name: worn pieces that count against a bonus come off first; one carried item that
	 * meets every need (the Medallion of the Deep) when there is one; else per need the carried piece, or the need's own
	 * name when none is carried. Empty slots are the earlier take-off act's.
	 */
	private SubStep equip(SubStep act, Step step)
	{
		Set<Integer> worn = player.wornIds();
		Set<Integer> inventory = player.inventoryIds();
		List<ItemRequirement> wanted = wanted(act, step);
		List<Integer> off = wanted.stream().flatMap(requirement -> toRemove(requirement, worn, inventory).stream())
			.distinct().collect(toList());
		if (!off.isEmpty())
		{
			return act.toBuilder().text(TAKE_OFF_TEXT).items(names(off)).actItems(Set.copyOf(off)).tab(StepGuide.Tab.EQUIPMENT).build();
		}
		List<ItemRequirement> unmet = unmet(act, step).stream().filter(requirement -> !EmptySlots.metByEmptySlots(requirement))
			.collect(toList());
		if (unmet.isEmpty())
		{
			return act;
		}
		Integer carriedCover = unmet.size() < 2 ? null : inventory.stream().sorted()
			.filter(id -> unmet.stream().allMatch(requirement -> requirement.fulfilledBy(PlayerSnapshot.oneOfEach(Set.of(id)))))
			.findFirst().orElse(null);
		if (carriedCover != null)
		{
			return equipping(act, List.of(carriedCover), names(List.of(carriedCover)));
		}
		Map<ItemRequirement, List<Integer>> carried = new LinkedHashMap<>();
		unmet.forEach(requirement -> carried.put(requirement, toEquip(requirement, worn, inventory)));
		long notCarried = carried.values().stream().filter(List::isEmpty).count();
		Integer cover = notCarried > 1 ? wayInCover(act, step) : null;
		if (cover != null)
		{
			return equipping(act, List.of(), names(List.of(cover)));
		}
		List<Integer> chosen = new ArrayList<>();
		List<String> names = new ArrayList<>();
		carried.forEach((requirement, pieces) ->
		{
			chosen.addAll(pieces);
			names.addAll(pieces.isEmpty() ? List.of(requirement.getCollectiveName(client)) : names(pieces));
		});
		return equipping(act, chosen, names);
	}

	private static SubStep equipping(SubStep act, List<Integer> chosen, List<String> names)
	{
		return act.toBuilder().text(EQUIP_TEXT).items(names.stream().distinct().collect(toList())).actItems(Set.copyOf(chosen)).build();
	}

	/**
	 * One item that is every one of the way in's needs at once (the Medallion of the Deep for the diving helmet and
	 * apparatus); null for none. The fewest items to fetch when the player has two or more of the set to find.
	 */
	private Integer wayInCover(SubStep act, Step step)
	{
		if (!act.isWornWayIn())
		{
			return null;
		}
		int cover = step.getData().getCover();
		boolean severalNeeds = step.getData().wornRequiresFor(player::finished, player.carriedIds()).size() > 1;
		return severalNeeds && cover >= 0 ? cover : null;
	}

	/** The worn items an act wants off, refreshed as each comes off. */
	private SubStep removeGear(SubStep act)
	{
		Item[] worn = wornBySlot();
		Set<Integer> off = Arrays.stream(EquipmentInventorySlot.values())
			.filter(slot -> slot.getSlotIdx() < worn.length && worn[slot.getSlotIdx()].getId() != -1)
			.filter(slot -> act.getEmptied().stream().anyMatch(requirement -> !EmptySlots.allows(requirement, slot,
				worn[slot.getSlotIdx()].getId())))
			.map(slot -> worn[slot.getSlotIdx()].getId())
			.collect(toUnmodifiableSet());
		return act.toBuilder().actItems(off).build();
	}

	private boolean slotsEmptied(SubStep act)
	{
		return act.getEmptied().stream().allMatch(requirement -> requirement.fulfilledBy(wornBySlot()));
	}

	private List<String> names(List<Integer> itemIds)
	{
		return itemIds.stream().map(id -> client.getItemDefinition(id).getName()).collect(toList());
	}

	/** The act's needs not met yet. */
	private List<ItemRequirement> unmet(SubStep act, Step step)
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		Item[] wornItems = PlayerSnapshot.items(snapshot.getWorn());
		Item[] bySlot = wornBySlot();
		Set<Integer> worn = snapshot.getWorn().keySet();
		return wanted(act, step).stream().filter(requirement -> requirement instanceof GearRequirement
			? !((GearRequirement) requirement).isWorn(worn)
			: EmptySlots.metByEmptySlots(requirement) ? !requirement.fulfilledBy(bySlot)
			: !requirement.fulfilledBy(wornItems) && !(act.isUnwearableCarried() && carriesUnwearable(requirement, snapshot)))
			.collect(toList());
	}

	/**
	 * A single item that can't be worn is carried for it. Only a single item's requirement can tell that none of its items
	 * can be worn: in a requirement of several, the one carried may be a noted robe or an old sceptre with no Wield, where
	 * another of its items is the one to wear. Whether an item can be worn comes from its menu, not its left click, which
	 * may be Use.
	 */
	private boolean carriesUnwearable(ItemRequirement requirement, PlayerSnapshot snapshot)
	{
		return requirement instanceof SingleItemRequirement && snapshot.getInventory().keySet().stream()
			.anyMatch(id -> requirement.fulfilledBy(id) && leftClicks.wearOption(id) == null);
	}

	/** The act's own requirements, then the way in's and the step's needs it names, as judged now. */
	private List<ItemRequirement> wanted(SubStep act, Step step)
	{
		List<ItemRequirement> wanted = new ArrayList<>(act.getWorn());
		if (act.isWornWayIn())
		{
			step.getData().wornRequiresFor(player::finished, player.carriedIds())
				.forEach(need -> wanted.add(SubSteps.anyOf(need.getName(), need.getIds())));
		}
		stepNeeds.of(step).getNeeds().stream().filter(need -> act.getWornNeeds().contains(need.getName()))
			.forEach(need -> wanted.add(need.getRequirement()));
		return wanted;
	}

	private static List<Integer> toRemove(ItemRequirement requirement, Set<Integer> worn, Set<Integer> inventory)
	{
		return requirement instanceof GearRequirement
			? ((GearRequirement) requirement).choose(worn, inventory, Set.of()).map(GearPicker.Choice::getRemove).orElse(List.of())
			: List.of();
	}

	/** The carried pieces to put on for it: only those with a Wear or Wield, not a noted one or an old id that has none. */
	private List<Integer> toEquip(ItemRequirement requirement, Set<Integer> worn, Set<Integer> inventory)
	{
		if (requirement instanceof GearRequirement)
		{
			return ((GearRequirement) requirement).choose(worn, inventory, Set.of()).map(GearPicker.Choice::getEquip).orElse(List.of());
		}
		if (requirement.fulfilledBy(PlayerSnapshot.oneOfEach(worn)))
		{
			return List.of();
		}
		List<Integer> fitting = inventory.stream().filter(requirement::fulfilledBy).filter(id -> leftClicks.wearOption(id) != null)
			.sorted().collect(toList());
		boolean outfit = requirement instanceof AllRequirementsCollection;
		return outfit || fitting.isEmpty() ? fitting : fewestCompleting(requirement, worn, fitting);
	}

	/**
	 * The fewest of {@code pieces} that meet the need along with {@code worn}: one item for a choice of items, every
	 * missing piece for a choice of sets (any full Barrows set). The lowest id is kept where any one would do; the first
	 * piece alone when even all of them fall short.
	 */
	static List<Integer> fewestCompleting(ItemRequirement requirement, Set<Integer> worn, List<Integer> pieces)
	{
		List<Integer> chosen = new ArrayList<>(pieces);
		if (!meets(requirement, worn, chosen))
		{
			return List.of(pieces.get(0));
		}
		for (int i = pieces.size() - 1; i >= 0; i--)
		{
			List<Integer> without = new ArrayList<>(chosen);
			without.remove(pieces.get(i));
			if (meets(requirement, worn, without))
			{
				chosen = without;
			}
		}
		return List.copyOf(chosen);
	}

	/** The carried items that meet one need: one item, or a whole set for a choice of sets. */
	static List<Integer> meetingNeed(ItemRequirement need, Set<Integer> carried)
	{
		List<Integer> fitting = carried.stream().filter(need::fulfilledBy).sorted().collect(toList());
		return fitting.isEmpty() ? List.of() : fewestCompleting(need, Set.of(), fitting);
	}

	private static boolean meets(ItemRequirement requirement, Set<Integer> worn, List<Integer> pieces)
	{
		Set<Integer> all = new HashSet<>(worn);
		all.addAll(pieces);
		return requirement.fulfilledBy(PlayerSnapshot.oneOfEach(all));
	}

	private Item[] wornBySlot()
	{
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		return worn == null ? new Item[0] : worn.getItems();
	}
}
