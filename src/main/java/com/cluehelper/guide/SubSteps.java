package com.cluehelper.guide;

import com.cluehelper.memory.*;
import com.cluehelper.player.*;
import com.cluehelper.route.*;
import com.cluehelper.step.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;

@Singleton
public final class SubSteps
{
	public static final int ARRIVE_TILES = 3;
	/** The Agility Pyramid's course spans its foot; a cave's way in is a tile. */
	private static final int ON_WAY_IN_TILES = 40;
	/** The route is planned a tick behind the player: its first walk may start a step or two from where they stand now. */
	private static final int START_DRIFT_TILES = 4;
	/** The walk from a landing to the place, a way in (a cave entrance) included. */
	private static final Set<SubStep.Kind> WALKS_IN = EnumSet.of(SubStep.Kind.WALK, SubStep.Kind.EXIT, SubStep.Kind.ENTER,
		SubStep.Kind.WALK_TO_CLUE);
	/** Some teleports land anywhere in a small area, beyond the catalog's 0–2 tile spread. */
	static final int LANDING_TILES = 10;
	/** A wandering NPC: being in its area is enough. */
	static final int AREA_ARRIVE_TILES = 15;

	private static final int MAX_STACK = Integer.MAX_VALUE / 2;
	private static final String ENTRANA_SHIP = "Ship: Entrana";
	private static final String ENTRANA_RULE = "Entrana allows no weapons or armour: the deposit box by the monks";
	private static final String DEPOSIT = "Deposit";

	private final Client client;
	private final Supplier<PlayerSnapshot> snapshot;
	private final IntPredicate onlyInBankedKit;
	private final StepNeeds stepNeeds;
	private final Supplier<PlanStart> start;
	private final BooleanSupplier scrollBookOutOfWilderness;
	private final IntPredicate weaponOrArmour;

	@Inject
	SubSteps(Client client, PlayerModel player, BankMemory bankMemory, StepNeeds stepNeeds, RoutePlanner routes, CombatGear gear)
	{
		this(client, player::getSnapshot, bankMemory::onlyInBankedKit, stepNeeds, routes::getStart,
			routes::keepsScrollBookOutOfWilderness, gear::isWeaponOrArmour);
	}

	/**
	 * @param client item names
	 * @param weaponOrArmour what the Entrana monks turn away
	 */
	public SubSteps(Client client, Supplier<PlayerSnapshot> snapshot, IntPredicate onlyInBankedKit, StepNeeds stepNeeds,
		Supplier<PlanStart> start, BooleanSupplier scrollBookOutOfWilderness, IntPredicate weaponOrArmour)
	{
		this.client = client;
		this.snapshot = snapshot;
		this.onlyInBankedKit = onlyInBankedKit;
		this.stepNeeds = stepNeeds;
		this.start = start;
		this.scrollBookOutOfWilderness = scrollBookOutOfWilderness;
		this.weaponOrArmour = weaponOrArmour;
	}

	/** Client thread only. The route's legs as sub-steps, then the step's own acts. */
	public List<SubStep> of(Route route, Step step)
	{
		boolean takeFromStash = takeFromStash(step);
		List<SubStep> legs = new RouteSteps(route, step, use -> withdrawLine(step, use), house(), takeFromStash).build(this::exitOf);
		List<SubStep> steps = withEntranaDeposits(withWanderNote(withGearOnArrival(withWayInEquipped(legs, step.getData())),
			step.getData()));
		boolean wildernessTarget = step.getTargets().stream().anyMatch(tile -> PositionRules.wildernessLevel(tile) > 0);
		return scrollBookOutOfWilderness.getAsBoolean() ? ScrollBook.withBookBanked(steps, carriesScrollBook(), wildernessTarget) : steps;
	}

	/**
	 * Before the ship to Entrana, every weapon and piece of armour carried or worn goes in the deposit box by its monks,
	 * which turn away anyone with one.
	 */
	private List<SubStep> withEntranaDeposits(List<SubStep> steps)
	{
		int ship = IntStream.range(0, steps.size()).filter(i -> isEntranaShip(steps.get(i))).findFirst().orElse(-1);
		PlayerSnapshot carrier = snapshot.get();
		if (ship < 0 || carrier == null)
		{
			return steps;
		}
		Set<Integer> banned = carrier.carriedIds().stream().filter(weaponOrArmour::test).collect(Collectors.toUnmodifiableSet());
		if (banned.isEmpty())
		{
			return steps;
		}
		List<SubStep> out = new ArrayList<>(steps);
		out.add(ship, entranaDeposit(banned));
		return List.copyOf(out);
	}

	private static boolean isEntranaShip(SubStep step)
	{
		return step.getKind() == SubStep.Kind.TELEPORT && step.getRow() != null && ENTRANA_SHIP.equals(step.getRow().getName());
	}

	private SubStep entranaDeposit(Set<Integer> banned)
	{
		List<String> names = banned.stream().map(id -> client.getItemDefinition(id).getName()).sorted().collect(Collectors.toList());
		return SubStep.builder().kind(SubStep.Kind.ACT).text("Deposit in the deposit box:").items(List.copyOf(names))
			.detail(ENTRANA_RULE).actItems(banned).objectIds(Set.of(ObjectID.SARIM_DEPOSIT_BOX)).option(DEPOSIT)
			.done(SubStep.Done.ITEMS_GONE).build();
	}

	/** What to do at the step once there, its STASH items taken out first when they wait there. */
	public List<SubStep> acts(Step step)
	{
		return Acts.of(step, takeFromStash(step));
	}

	/** The step's items wait in its filled STASH unit, and the player has none of them on. */
	public boolean takeFromStash(Step step)
	{
		if (step == null || step.getData().getStash() == null || step.getRequirements() == null)
		{
			return false;
		}
		return stepNeeds.of(step).getStash() == StashMemory.State.FILLED
			&& !step.getRequirements().itemsFulfilledBy(PlayerSnapshot.items(carriedItems()));
	}

	private HouseState house()
	{
		PlayerSnapshot player = snapshot.get();
		return player == null ? HouseState.NONE : player.getHouse();
	}

	private Map<Integer, Integer> carriedItems()
	{
		PlayerSnapshot player = snapshot.get();
		return player == null ? Map.of() : player.carriedItems();
	}

	private boolean carriesScrollBook()
	{
		PlayerSnapshot player = snapshot.get();
		return player != null && player.carriesItemNamed(ScrollBook.BOOK_NAME, id -> client.getItemDefinition(id).getName());
	}

	/** The exit a start leg leaves by, or null for any other walk. */
	private Exit exitOf(Leg leg)
	{
		PlanStart from = start.get();
		boolean fromHere = from != null && from.getTile() != null && leg.getFrom().getPlane() == from.getTile().getPlane()
			&& leg.getFrom().distanceTo2D(from.getTile()) <= START_DRIFT_TILES;
		if (from == null || from.getKind() != PlanStart.Kind.SCENE || !fromHere)
		{
			return null;
		}
		return from.getWalk().exitTo(leg.getTo());
	}

	/** "Green dragon leather x2" while more than one is still to take, else the name alone. */
	/**
	 * A step need's use carries the whole banked stack, so its amount is what the need still lacks; a teleport's use
	 * carries what the teleport spends (its runes).
	 */
	private String withdrawLine(Step step, ItemUse use)
	{
		int itemId = use.getItemId();
		String name = client.getItemDefinition(itemId).getName();
		if (onlyInBankedKit.test(itemId))
		{
			return client.getItemDefinition(ItemID.HUNTSMANS_KIT).getName() + " (" + name + " inside)";
		}
		int amount = isStepNeed(step, itemId) ? stillToTake(step, itemId) : use.getQuantity();
		return amount > 1 ? amount + " x " + name : name;
	}

	private boolean isStepNeed(Step step, int itemId)
	{
		return stepNeeds.of(step).getNeeds().stream().anyMatch(need -> need.getRequirement().fulfilledBy(itemId));
	}

	/** The fewest of the item that, with what is carried, meet every step need it counts towards. */
	private int stillToTake(Step step, int itemId)
	{
		Map<Integer, Integer> carried = carriedItems();
		int most = 0;
		for (RequiredItems.Need need : stepNeeds.of(step).getNeeds())
		{
			ItemRequirement requirement = need.getRequirement();
			if (requirement.fulfilledBy(itemId) && !requirement.fulfilledBy(withMore(carried, itemId, 0)))
			{
				most = Math.max(most, fewestMeeting(requirement, carried, itemId));
			}
		}
		return most;
	}

	/** Doubles, then halves back: needs run from 1 to thousands (coins). 0 when this item alone can't meet it (an outfit). */
	private static int fewestMeeting(ItemRequirement requirement, Map<Integer, Integer> carried, int itemId)
	{
		int high = 1;
		while (high < MAX_STACK && !requirement.fulfilledBy(withMore(carried, itemId, high)))
		{
			high *= 2;
		}
		if (high >= MAX_STACK)
		{
			return 0;
		}
		int low = high / 2;
		while (low + 1 < high)
		{
			int middle = (low + high) / 2;
			if (requirement.fulfilledBy(withMore(carried, itemId, middle)))
			{
				high = middle;
			}
			else
			{
				low = middle;
			}
		}
		return high;
	}

	private static Item[] withMore(Map<Integer, Integer> carried, int itemId, int more)
	{
		Map<Integer, Integer> items = new HashMap<>(carried);
		items.merge(itemId, more, Integer::sum);
		return PlayerSnapshot.items(items);
	}

	/** The sub-steps of one route, built leg by leg: each stage's walk and act in turn, then the step's own acts. */
	private static final class RouteSteps
	{
		private final List<SubStep> steps = new ArrayList<>();
		private final Route route;
		private final Step step;
		private final Function<ItemUse, String> itemLines;
		private final HouseState house;
		private final boolean takeFromStash;
		/** The STASH unit stands outside the step's way in: its items come before going in. */
		private final boolean stashFirst;

		RouteSteps(Route route, Step step, Function<ItemUse, String> itemLines, HouseState house, boolean takeFromStash)
		{
			this.route = route;
			this.step = step;
			this.itemLines = itemLines;
			this.house = house;
			this.takeFromStash = takeFromStash;
			this.stashFirst = takeFromStash && stashOutsideWayIn(step);
		}

		List<SubStep> build(Function<Leg, Exit> exits)
		{
			if (route.isOpenBankStart() && !route.getWithdraw().isEmpty())
			{
				steps.add(LegSteps.withdraw(route, itemLines));
			}
			List<Stage> stages = step.getStages().stream().filter(stage -> !stage.getTiles().isEmpty()).collect(Collectors.toList());
			List<Leg> legs = route.getLegs();
			int stage = 0;
			for (int i = 0; i < legs.size(); i++)
			{
				Leg leg = legs.get(i);
				Leg next = i + 1 < legs.size() ? legs.get(i + 1) : null;
				Exit exit = leg.getKind() == Leg.Kind.WALK ? exits.apply(leg) : null;
				Stage reached = stage < stages.size() - 1 && stages.get(stage).getTiles().contains(leg.getTo()) ? stages.get(stage) : null;
				if (reached != null && leg.getKind() == Leg.Kind.WALK && exit == null)
				{
					steps.add(LegSteps.walkToStage(leg, reached));
				}
				else
				{
					addLeg(leg, next, exit);
				}
				if (reached != null)
				{
					addStageAct(steps, reached);
					stage++;
				}
			}
			addLastActs(stages);
			addActsDoneAnywhere(steps, step);
			return steps;
		}

		private void addLeg(Leg leg, Leg next, Exit exit)
		{
			switch (leg.getKind())
			{
				case WALK:
					addWalk(leg, next, exit);
					break;
				case WILDERNESS_EXIT:
					steps.add(SubStep.builder().kind(SubStep.Kind.WILDERNESS_EXIT).text("Walk south out of deep Wilderness")
						.detail("Teleports work from there").waypoint(leg.getTo()).goal(leg.getTo()).build());
					break;
				case SWAP_TELEPORT:
					steps.add(LegSteps.swap(leg));
					steps.add(LegSteps.teleport(leg, house));
					break;
				case TELEPORT:
					steps.add(LegSteps.teleport(leg, house));
					break;
				case FAIRY_RING:
					steps.addAll(LegSteps.fairyRing(leg));
					break;
				case SPELLBOOK_CHANGE:
					steps.add(LegSteps.change(leg));
					break;
				case BANK:
					steps.add(SubStep.builder().kind(SubStep.Kind.BANK).text("Open the bank")
						.detail(route.isPinIncluded() ? "Your PIN comes first" : null).waypoint(leg.getTo()).build());
					steps.add(LegSteps.withdraw(route, itemLines));
					break;
				default:
					throw new IllegalArgumentException("unhandled leg kind " + leg.getKind());
			}
		}

		/** A walk into the step's area goes in by its way in, with the STASH items first when the unit stands outside. */
		private void addWalk(Leg leg, Leg next, Exit exit)
		{
			boolean wayIn = exit != null && isWayIn(exit, step);
			if ((exit == null || wayIn) && entersStepArea(leg, step))
			{
				if (stashFirst)
				{
					steps.add(LegSteps.stashWalk(stashTile(step)));
					steps.addAll(Acts.fromStash(step));
				}
				steps.add(LegSteps.enter(step.getData()));
			}
			if (exit != null || wayIn || !boardedThere(next))
			{
				steps.add(exit != null && !wayIn ? LegSteps.exit(leg, exit) : LegSteps.walk(leg, next, step, takeFromStash && !stashFirst));
			}
		}

		/** The last stage's act, or the step's own acts once every stage is done. */
		private void addLastActs(List<Stage> stages)
		{
			Stage last = stages.isEmpty() ? null : stages.get(stages.size() - 1);
			if (last != null && last.getKind() != Stage.Kind.FINAL)
			{
				addStageAct(steps, last);
			}
			else
			{
				steps.addAll(Acts.of(step, takeFromStash && !stashFirst));
			}
		}
	}

	/** After the last bank stop, which may be what brings the task's items. */
	private static void addActsDoneAnywhere(List<SubStep> steps, Step step)
	{
		int at = 0;
		for (int i = 0; i < steps.size(); i++)
		{
			if (steps.get(i).getKind() == SubStep.Kind.WITHDRAW)
			{
				at = i + 1;
			}
		}
		for (Stage stage : step.getStages())
		{
			if (stage.getTiles().isEmpty())
			{
				steps.add(at++, stageAct(stage));
			}
		}
	}

	/** Ends when the stage is dropped from the step, which brings a new plan. */
	static SubStep stageAct(Stage stage)
	{
		return SubStep.builder().kind(SubStep.Kind.ACT).text(stage.getAct()).detail(stage.getDetail()).stage(stage)
			.npcIds(stage.getNpcIds()).objectIds(stage.getObjectIds()).option(stage.getOption()).spans(stage.getSpans()).build();
	}

	/** The step's way in, when the player is on it: near its start, any floor, and not yet inside. */
	public static SubStep goingIn(Step step, WorldPoint at)
	{
		StepIndex.WayIn way = step == null ? null : step.getData().getEntry();
		boolean byStart = way != null && at != null && way.getTile() != null && at.distanceTo2D(way.getTile()) <= ON_WAY_IN_TILES;
		boolean onIt = way != null && (byStart || way.isPartWay(at)) && !way.isInside(at);
		return onIt ? LegSteps.enter(step.getData()) : null;
	}

	/**
	 * A fairy ring, ride or spirit tree used where it stands: its own sub-step leads the line there and colours its menu
	 * from the start, so no "Walk to" comes before it.
	 */
	private static boolean boardedThere(Leg next)
	{
		return next != null && (next.getKind() == Leg.Kind.FAIRY_RING
			|| next.getKind() == Leg.Kind.TELEPORT && next.getRow() != null && next.getRow().isLocated() && !next.getRow().isInHouse());
	}

	/** The STASH unit stands outside the step's way in (the Agility Pyramid's, at its foot): its items come first. */
	private static boolean stashOutsideWayIn(Step step)
	{
		WorldPoint stash = stashTile(step);
		StepIndex.WayIn way = step.getData().getEntry();
		return stash != null && way != null && !way.isInside(stash);
	}

	/** An exit leg that is the step's own hand-written way in, named by its text instead of "Take the exit". */
	private static boolean isWayIn(Exit exit, Step step)
	{
		StepIndex.WayIn way = step.getData().getEntry();
		return way != null && exit.getOrigin().equals(way.getTile()) && exit.getDestination().equals(way.getTo());
	}

	static WorldPoint stashTile(Step step)
	{
		return step.getData().getStash() == null ? null : step.getData().getStash().getTile();
	}

	/** The last walk to the step starts outside its hand-written way in. */
	private static boolean entersStepArea(Leg leg, Step step)
	{
		StepIndex.WayIn way = step.getData().getEntry();
		return way != null && step.getTargets().contains(leg.getTo()) && !way.isInside(leg.getFrom());
	}

	/** Before going in, the gear the way in needs worn is put on. */
	private static void addStageAct(List<SubStep> steps, Stage stage)
	{
		steps.addAll(stageActs(stage));
	}

	/** Gear the act wants worn goes on just before, so each part of a three-step can wear its own. */
	static List<SubStep> stageActs(Stage stage)
	{
		List<SubStep> acts = new ArrayList<>(remove(stage.getEmptied()));
		stage.getBefore().forEach(before -> acts.add(SubStep.builder().kind(SubStep.Kind.ACT).text(before.getText())
			.npcIds(Set.copyOf(before.getNpcs())).option(before.getOption()).groundItem(before.getGroundItem())
			.heldWhenDone(Set.copyOf(before.getHeld())).done(SubStep.Done.ITEM_HELD).build()));
		acts.addAll(stage.getWorn().isEmpty() ? List.of() : equip(stage.getWorn()));
		acts.add(stageAct(stage));
		return List.copyOf(acts);
	}

	/** Take off what core's empty-slot requirements forbid; none when there are none. */
	static List<SubStep> remove(List<ItemRequirement> emptied)
	{
		return emptied.isEmpty() ? List.of() : List.of(SubStep.builder().kind(SubStep.Kind.ACT)
			.text("Take off your " + EmptySlots.slotNames(emptied)).emptied(emptied).done(SubStep.Done.SLOTS_EMPTY)
			.tab(StepGuide.Tab.EQUIPMENT).build());
	}

	/** Put on the needs named, done once worn items alone meet them. */
	static List<SubStep> equip(List<String> wornNeeds)
	{
		return List.of(SubStep.builder().kind(SubStep.Kind.ACT).text("Equip the " + String.join(" and ", wornNeeds))
			.wornNeeds(wornNeeds).done(SubStep.Done.GEAR_WORN).tab(StepGuide.Tab.INVENTORY).build());
	}

	/** A wide wanderer is looked for rather than walked to: where it wanders goes under the walk. */
	private static List<SubStep> withWanderNote(List<SubStep> steps, StepIndex.Entry data)
	{
		StepIndex.Wander wander = data == null ? null : data.getWander();
		if (wander == null || wander.getText() == null)
		{
			return steps;
		}
		return steps.stream().map(sub -> sub.getKind() == SubStep.Kind.WALK_TO_CLUE ? sub.toBuilder().detail(wander.getText()).build() : sub)
			.collect(Collectors.toUnmodifiableList());
	}

	/**
	 * The act's gear goes on as soon as the route lands, before walking or going in: a course fails without its Graceful,
	 * Ape Atoll's monkeys and a dust devil's cave hurt without theirs.
	 */
	private static List<SubStep> withGearOnArrival(List<SubStep> steps)
	{
		int equip = IntStream.range(0, steps.size()).filter(i -> steps.get(i).getDone() == SubStep.Done.GEAR_WORN).findFirst().orElse(-1);
		if (equip < 0)
		{
			return steps;
		}
		int landed = equip;
		while (landed > 0 && WALKS_IN.contains(steps.get(landed - 1).getKind()))
		{
			landed--;
		}
		List<SubStep> out = new ArrayList<>(steps);
		out.add(landed, out.remove(equip));
		return List.copyOf(out);
	}

	static List<SubStep> withWayInEquipped(List<SubStep> steps, StepIndex.Entry data)
	{
		List<StepIndex.Requires> worn = data == null ? List.of()
			: data.getRequires().stream().filter(StepIndex.Requires::isWear).collect(Collectors.toList());
		if (worn.isEmpty())
		{
			return steps;
		}
		SubStep equip = SubStep.builder().kind(SubStep.Kind.ACT)
			.text("Equip the " + worn.stream().map(StepIndex.Requires::getName).collect(Collectors.joining(" and ")))
			.actItems(worn.stream().flatMap(need -> need.getIds().stream()).collect(Collectors.toUnmodifiableSet()))
			.done(SubStep.Done.WAY_IN_WORN).tab(StepGuide.Tab.INVENTORY).build();
		List<SubStep> out = new ArrayList<>();
		for (SubStep sub : steps)
		{
			if (sub.getKind() == SubStep.Kind.ENTER)
			{
				out.add(equip);
			}
			out.add(sub);
		}
		return List.copyOf(out);
	}

	/** Client thread only. Weapons and armour as the Entrana monks see them: any attack, defence or strength bonus. */
	@Singleton
	public static class CombatGear
	{
		@Inject
		private ItemManager itemManager;

		public boolean isWeaponOrArmour(int itemId)
		{
			ItemStats stats = itemManager.getItemStats(itemId);
			ItemEquipmentStats bonuses = stats == null || !stats.isEquipable() ? null : stats.getEquipment();
			return bonuses != null && (bonuses.getAstab() != 0 || bonuses.getAslash() != 0 || bonuses.getAcrush() != 0
				|| bonuses.getAmagic() != 0 || bonuses.getArange() != 0 || bonuses.getDstab() != 0 || bonuses.getDslash() != 0
				|| bonuses.getDcrush() != 0 || bonuses.getDmagic() != 0 || bonuses.getDrange() != 0 || bonuses.getStr() != 0
				|| bonuses.getRstr() != 0 || bonuses.getMdmg() != 0);
		}
	}
}
