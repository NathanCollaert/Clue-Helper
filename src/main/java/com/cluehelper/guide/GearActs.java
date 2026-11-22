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
		Integer coversAll = unmet.size() < 2 ? null : inventory.stream().sorted()
			.filter(id -> unmet.stream().allMatch(requirement -> requirement.fulfilledBy(PlayerSnapshot.oneOfEach(Set.of(id)))))
			.findFirst().orElse(null);
		List<Integer> chosen = new ArrayList<>();
		List<String> names = new ArrayList<>();
		for (ItemRequirement requirement : coversAll != null ? List.<ItemRequirement>of() : unmet)
		{
			List<Integer> carried = toEquip(requirement, worn, inventory);
			chosen.addAll(carried);
			names.addAll(carried.isEmpty() ? List.of(requirement.getCollectiveName(client)) : names(carried));
		}
		if (coversAll != null)
		{
			chosen.add(coversAll);
			names.addAll(names(chosen));
		}
		return act.toBuilder().text(EQUIP_TEXT).items(names.stream().distinct().collect(toList()))
			.actItems(Set.copyOf(chosen)).build();
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
		return outfit || fitting.isEmpty() ? fitting : List.of(fitting.get(0));
	}

	private Item[] wornBySlot()
	{
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		return worn == null ? new Item[0] : worn.getItems();
	}
}
