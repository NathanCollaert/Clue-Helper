package com.cluehelper.guide;

import com.cluehelper.player.*;
import com.cluehelper.route.*;
import com.cluehelper.step.*;
import java.util.*;
import java.util.stream.Collectors;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.plugins.cluescrolls.clues.item.*;

/** Acts that put gear on or take it off: what to change now, from what is carried and worn, and when it is done. */
@Singleton
final class GearActs
{
	private static final String EQUIP_TEXT = "Equip:";
	private static final String TAKE_OFF_TEXT = "Take off:";

	@Inject
	private Client client;
	@Inject
	private PlayerModel player;
	@Inject
	private StepNeeds stepNeeds;
	@Inject
	private LeftClicks leftClicks;

	/** A gear act as the pieces to put on or take off now; any other sub-step, or one there is no step for, as it is. */
	SubStep refresh(SubStep act, Step step)
	{
		switch (act.getDone())
		{
			case WAY_IN_WORN:
				return step == null || !act.getNpcIds().isEmpty() ? act : equipWhatIsCarried(act, step);
			case GEAR_WORN:
				return step == null ? act : equipGear(act, step);
			case SLOTS_EMPTY:
			case WAY_IN_SLOTS_EMPTY:
				return removeGear(act);
			default:
				return act;
		}
	}

	/**
	 * For an act done by {@link SubStep.Done#EQUIPPED}, {@code WAY_IN_WORN}, {@code WAY_IN_SLOTS_EMPTY}, {@code GEAR_WORN},
	 * {@code SLOTS_EMPTY} or {@code ITEM_WORN}.
	 */
	boolean isDone(SubStep act, Step step)
	{
		switch (act.getDone())
		{
			case EQUIPPED:
				return wearsStepItems(step);
			case WAY_IN_WORN:
				return wearsWayInGear(step);
			case GEAR_WORN:
				return step != null && wearsGear(act, step);
			case WAY_IN_SLOTS_EMPTY:
				return wearsWayInGear(step) || slotsEmptied(act);
			case SLOTS_EMPTY:
				return slotsEmptied(act);
			case ITEM_WORN:
				return act.getActItems().stream().anyMatch(player.wornIds()::contains);
			default:
				throw new IllegalArgumentException("not a gear act: " + act.getDone());
		}
	}

	/**
	 * The way in's gear as carried: one item that covers every requirement (the Medallion of the Deep) when the player
	 * has it, else per requirement the item they carry.
	 */
	private SubStep equipWhatIsCarried(SubStep act, Step step)
	{
		List<StepIndex.Requires> worn = step.getData().wornRequiresFor(player::finished, player.carriedIds());
		Set<Integer> carried = player.carriedIds();
		Integer coversAll = worn.get(0).getIds().stream().filter(carried::contains)
			.filter(id -> worn.stream().allMatch(need -> need.getIds().contains(id))).findFirst().orElse(null);
		List<Integer> chosen = coversAll != null ? List.of(coversAll) : worn.stream()
			.map(need -> need.getIds().stream().filter(carried::contains).findFirst().orElse(need.getIds().get(0)))
			.distinct().collect(Collectors.toList());
		return equipping(act, chosen);
	}

	/**
	 * The gear a part's act wants, from what is carried: the fewest pieces to put on, listed by name. Worn pieces that
	 * count against a bonus come off first; what to put on is picked again once they are off.
	 */
	private SubStep equipGear(SubStep act, Step step)
	{
		Set<Integer> worn = player.wornIds();
		Set<Integer> inventory = player.inventoryIds();
		List<Integer> off = wornNeeds(act, step).stream().flatMap(need -> toRemove(need.getRequirement(), worn, inventory).stream())
			.distinct().collect(Collectors.toList());
		if (!off.isEmpty())
		{
			return act.toBuilder().text(TAKE_OFF_TEXT).items(names(off)).actItems(Set.copyOf(off)).tab(StepGuide.Tab.EQUIPMENT).build();
		}
		List<Integer> chosen = wornNeeds(act, step).stream().flatMap(need -> toEquip(need.getRequirement(), worn, inventory).stream())
			.distinct().collect(Collectors.toList());
		return chosen.isEmpty() ? act : equipping(act, chosen);
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
			.collect(Collectors.toUnmodifiableSet());
		return act.toBuilder().actItems(off).build();
	}

	private boolean slotsEmptied(SubStep act)
	{
		return act.getEmptied().stream().allMatch(requirement -> requirement.fulfilledBy(wornBySlot()));
	}

	private SubStep equipping(SubStep act, List<Integer> itemIds)
	{
		return act.toBuilder().text(EQUIP_TEXT).items(names(itemIds)).actItems(Set.copyOf(itemIds)).build();
	}

	private List<String> names(List<Integer> itemIds)
	{
		return itemIds.stream().map(id -> client.getItemDefinition(id).getName()).collect(Collectors.toList());
	}

	/** Core lists items that can't be worn among an emote's (the axe hut's lockpick): carrying those is enough. */
	private boolean wearsStepItems(Step step)
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		if (step == null || step.getRequirements() == null || snapshot == null)
		{
			return false;
		}
		Map<Integer, Integer> wornOrUnwearable = new HashMap<>(snapshot.getWorn());
		snapshot.getInventory().forEach((id, count) ->
		{
			if (!leftClicks.wears(id))
			{
				wornOrUnwearable.merge(id, count, Integer::sum);
			}
		});
		Item[] items = PlayerSnapshot.items(wornOrUnwearable);
		Item[] bySlot = wornBySlot();
		return step.getRequirements().getItems().stream().allMatch(requirement -> EmptySlots.metByEmptySlots(requirement)
			? requirement.fulfilledBy(bySlot) : requirement.fulfilledBy(items));
	}

	private boolean wearsWayInGear(Step step)
	{
		if (step == null)
		{
			return true;
		}
		Set<Integer> on = player.wornIds();
		return step.getData().wornRequiresFor(player::finished, player.carriedIds()).stream().allMatch(need -> need.getIds().stream().anyMatch(on::contains));
	}

	private boolean wearsGear(SubStep act, Step step)
	{
		Set<Integer> worn = player.wornIds();
		return wornNeeds(act, step).stream().allMatch(need -> need.getRequirement() instanceof GearRequirement
			? ((GearRequirement) need.getRequirement()).isWorn(worn) : need.getRequirement().fulfilledBy(PlayerSnapshot.oneOfEach(worn)));
	}

	private List<RequiredItems.Need> wornNeeds(SubStep act, Step step)
	{
		return stepNeeds.of(step).getNeeds().stream().filter(need -> act.getWornNeeds().contains(need.getName()))
			.collect(Collectors.toList());
	}

	private static List<Integer> toRemove(ItemRequirement requirement, Set<Integer> worn, Set<Integer> inventory)
	{
		return requirement instanceof GearRequirement
			? ((GearRequirement) requirement).choose(worn, inventory, Set.of()).map(GearPicker.Choice::getRemove).orElse(List.of())
			: List.of();
	}

	private static List<Integer> toEquip(ItemRequirement requirement, Set<Integer> worn, Set<Integer> inventory)
	{
		if (requirement instanceof GearRequirement)
		{
			return ((GearRequirement) requirement).choose(worn, inventory, Set.of()).map(GearPicker.Choice::getEquip).orElse(List.of());
		}
		if (requirement.fulfilledBy(PlayerSnapshot.oneOfEach(worn)))
		{
			return List.of();
		}
		List<Integer> fitting = inventory.stream().filter(requirement::fulfilledBy).sorted().collect(Collectors.toList());
		boolean outfit = requirement instanceof AllRequirementsCollection;
		return outfit || fitting.isEmpty() ? fitting : List.of(fitting.get(0));
	}

	private Item[] wornBySlot()
	{
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		return worn == null ? new Item[0] : worn.getItems();
	}
}
