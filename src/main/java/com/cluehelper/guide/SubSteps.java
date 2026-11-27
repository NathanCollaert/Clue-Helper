package com.cluehelper.guide;

import com.cluehelper.memory.*;
import com.cluehelper.player.*;
import com.cluehelper.route.*;
import com.cluehelper.step.*;
import com.cluehelper.transport.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;
import javax.inject.*;
import lombok.RequiredArgsConstructor;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.game.*;
import net.runelite.client.plugins.cluescrolls.clues.item.*;
import static java.util.stream.Collectors.*;
import com.cluehelper.guide.SubStep.Kind;
import com.cluehelper.guide.StepGuide.Tab;

@Singleton
@RequiredArgsConstructor
public final class SubSteps
{
	private static final int DUNGEON_OFFSET = 6400;
	private static final int DUNGEON_SOUTH_EDGE = 8800;
	public static final int ARRIVE_TILES = 3;
	/** The Agility Pyramid's course spans its foot; a cave's way in is a tile. */
	private static final int ON_WAY_IN_TILES = 40;
	/** The route is planned a tick behind the player: its first walk may start a step or two from where they stand now. */
	private static final int START_DRIFT_TILES = 4;
	private static final Set<Kind> BRINGS_WHAT_THE_TASK_NEEDS = EnumSet.of(Kind.WITHDRAW, Kind.CHANGE_BOOK,
		Kind.SPELLBOOK_SWAP);
	/** The walk from a landing to the place, a way in (a cave entrance) included. */
	private static final Set<Kind> WALKS_IN = EnumSet.of(Kind.WALK, Kind.EXIT, Kind.ENTER,
		Kind.WALK_TO_CLUE);
	/** Some teleports land anywhere in a small area, beyond the catalog's 0–2 tile spread. */
	static final int LANDING_TILES = 10;
	/** A wandering NPC: being in its area is enough. */
	static final int AREA_ARRIVE_TILES = 15;

	private static final String ENTRANA_SHIP = "Ship: Entrana";
	private static final String ENTRANA = "Entrana";
	private static final String DIG = "Dig";
	private static final String WALK_TO_DIG = "Walk to the dig spot";
	private static final String DEPOSIT = "Deposit";

	/** For item names. */
	final Client client;
	final Supplier<PlayerSnapshot> snapshot;
	final IntPredicate onlyInBankedKit;
	final StepNeeds stepNeeds;
	final Supplier<PlanStart> start;
	final BooleanSupplier scrollBookOutOfWilderness;
	/** What the Entrana monks turn away. */
	final IntPredicate weaponOrArmour;
	/** The teleports, for the spirit tree a planted-tree task travels to. */
	final Supplier<List<CatalogRow>> catalog;
	/** The walk table's exit a leg goes through within so many tiles of walking, or null. */
	final BiFunction<Leg, Integer, Exit> exitBetween;

	@Inject
	SubSteps(Client client, PlayerModel player, BankMemory bankMemory, StepNeeds stepNeeds, RoutePlanner routes, CombatGear gear,
		AvailabilityService availability)
	{
		this(client, player::now, bankMemory::onlyInBankedKit, stepNeeds, routes::getStart,
			routes::keepsScrollBookOutOfWilderness, gear::isWeaponOrArmour, availability::getCatalog, routes::exitBetween);
	}



	/** Client thread only. The route's legs as sub-steps, then the step's own acts. */
	public List<SubStep> of(Route route, Step step)
	{
		boolean takeFromStash = takeFromStash(step);
		List<SubStep> legs = new RouteSteps(route, step, takeFromStash).build();
		List<SubStep> wayIn = withWalkToWayIn(withWayInEquipped(legs, step.getData()), step.getData());
		List<SubStep> steps = withRideItemsWorn(route, withEntranaDeposits(withGearOnArrival(wayIn)));
		return withWalksNamedByWhatTheyLeadTo(withTrackPlayedOnTheWay(withBankTasksShown(scrollBookOutOfWilderness.getAsBoolean()
			? ScrollBook.withBookBanked(steps, carriesScrollBook(), intoWilderness(route, step)) : steps)));
	}

	/**
	 * A walk reads as what it leads to, the act or the way in after it past further walks: "Kill a hellhound", not "Walk
	 * to the clue". Not when gear goes on between, which would read out of order, nor for a dig: "Dig" reads as now, so
	 * its walk says where it goes. The line and outlines stay the walk's.
	 */
	private static List<SubStep> withWalksNamedByWhatTheyLeadTo(List<SubStep> steps)
	{
		List<SubStep> named = new ArrayList<>(steps);
		for (int i = 0; i < named.size(); i++)
		{
			SubStep walk = named.get(i);
			SubStep led = isWalk(walk) ? ledTo(steps, i + 1) : null;
			if (led != null)
			{
				named.set(i, walk.toBuilder().text(led.getText().equals(DIG) ? WALK_TO_DIG : led.getText()).build());
			}
		}
		return List.copyOf(named);
	}

	/**
	 * The exit a walk to a kill jumps through, farther than the walk's time covers (the whirlpool down to the Ancient
	 * Cavern), a dungeon counted where it lies under the surface; null for a plain walk. Its far side isn't loaded, so
	 * the player's own walk search doesn't know it yet.
	 */
	private Exit jumpedBy(Leg leg)
	{
		boolean jumps = aboveGround(leg.getFrom()).distanceTo2D(aboveGround(leg.getTo())) > leg.getHalfTicks();
		return jumps ? exitBetween.apply(leg, leg.getHalfTicks()) : null;
	}

	/** The surface tile over a dungeon's: the game lays most dungeons out this far north of where they lie. */
	private static WorldPoint aboveGround(WorldPoint tile)
	{
		return tile.getY() >= DUNGEON_SOUTH_EDGE ? new WorldPoint(tile.getX(), tile.getY() - DUNGEON_OFFSET, tile.getPlane()) : tile;
	}

	private static SubStep ledTo(List<SubStep> steps, int from)
	{
		for (int i = from; i < steps.size(); i++)
		{
			SubStep sub = steps.get(i);
			if (!isWalk(sub))
			{
				return !sub.isPreparation() && (sub.getKind() == Kind.ACT || sub.getKind() == Kind.ENTER) ? sub : null;
			}
		}
		return null;
	}

	static boolean isWalk(SubStep sub)
	{
		return sub.getKind() == Kind.WALK || sub.getKind() == Kind.WALK_TO_CLUE;
	}

	/** The walk to Cecilia asks for the track already: it can be picked while running there. */
	private static List<SubStep> withTrackPlayedOnTheWay(List<SubStep> steps)
	{
		SubStep play = steps.stream().filter(sub -> sub.getDone() == SubStep.Done.TRACK_PLAYING).findFirst().orElse(null);
		if (play == null)
		{
			return steps;
		}
		return steps.stream().map(sub -> sub.getKind() == Kind.WALK_TO_CLUE ? sub.toBuilder().text(play.getText()).tab(Tab.MUSIC).build() : sub)
			.collect(toUnmodifiableList());
	}

	/**
	 * From the spirit tree at this tile, the teleport to the first of the player's grown trees in the tree's list; null
	 * when none is known grown.
	 */
	private CatalogRow plantedTreeTeleport(WorldPoint tree)
	{
		Set<String> grown = snapshot.get().getPlantedTrees();
		return catalog.get().stream()
			.filter(row -> row.getCategory() == Category.SPIRIT_TREE && tree.equals(row.getOrigin()))
			.filter(row -> row.getRequirements().getPlantedTrees().stream().anyMatch(grown::contains))
			.min(Comparator.comparing(row -> row.getClicks().toString())).orElse(null);
	}

	/** Where the route goes, or with no legs yet the step's targets: a step with spawns outside it (green dragons) may not. */
	private static boolean intoWilderness(Route route, Step step)
	{
		List<Leg> legs = route == null ? List.of() : route.getLegs();
		return legs.isEmpty() ? step.getTargets().stream().anyMatch(tile -> PositionRules.wildernessLevel(tile) > 0)
			: legs.stream().anyMatch(leg -> leg.getTo() != null && PositionRules.wildernessLevel(leg.getTo()) > 0);
	}

	/**
	 * Before the ship or the balloon to Entrana, every weapon and piece of armour carried or worn is left behind: in the bank
	 * at the route's last bank stop before it, else in the deposit box by the monks for the ship, or at a bank first for the
	 * balloon. Both turn away anyone with one.
	 */
	private List<SubStep> withEntranaDeposits(List<SubStep> steps)
	{
		int crossing = IntStream.range(0, steps.size()).filter(i -> isEntranaCrossing(steps.get(i))).findFirst().orElse(-1);
		if (crossing < 0)
		{
			return steps;
		}
		Set<Integer> banned = snapshot.get().carriedIds().stream().filter(weaponOrArmour::test).collect(toUnmodifiableSet());
		if (banned.isEmpty())
		{
			return steps;
		}
		List<SubStep> out = new ArrayList<>(steps);
		int bank = IntStream.range(0, crossing).filter(i -> steps.get(i).getKind() == Kind.BANK).reduce((first, last) -> last).orElse(-1);
		if (bank >= 0)
		{
			out.add(bank + 1, bankDeposit(banned));
		}
		else if (ENTRANA_SHIP.equals(steps.get(crossing).getRow().getName()))
		{
			out.add(crossing, entranaDeposit(banned));
		}
		else
		{
			out.addAll(0, List.of(SubStep.of(Kind.BANK, "Open the bank").build(), bankDeposit(banned),
				SubStep.of(Kind.WITHDRAW, "Close the bank").build()));
		}
		return List.copyOf(out);
	}

	/**
	 * What a ride takes worn (a fairy ring's staff, the ghost captain's ghostspeak amulet) goes on before its first use
	 * in the route and stays on for the rest, so a chained code's later hops follow each other with nothing between them.
	 */
	private List<SubStep> withRideItemsWorn(Route route, List<SubStep> steps)
	{
		PlayerSnapshot player = snapshot.get();
		Set<Integer> withdrawn = withdrawnIds(route);
		Set<Set<Integer>> putOn = new HashSet<>();
		List<SubStep> out = new ArrayList<>();
		for (SubStep sub : steps)
		{
			for (Set<Integer> items : toWear(sub.getRow(), player))
			{
				if (putOn.add(items))
				{
					out.add(equip(items, withdrawn));
				}
			}
			out.add(sub);
		}
		return List.copyOf(out);
	}

	/**
	 * Each of the row's must-be-worn clauses, as its items, that the player wears none of and cannot do without (the
	 * Lumbridge elite diary lets them use fairy rings with no staff).
	 */
	private static List<Set<Integer>> toWear(CatalogRow row, PlayerSnapshot player)
	{
		if (row == null)
		{
			return List.of();
		}
		return row.getRequirements().getItems().stream()
			.filter(ItemClause::isMustBeWorn)
			.filter(clause -> !AvailabilityEngine.isWaived(clause, player, AvailabilityService.nowMinutes()))
			.map(clause -> clause.ids().collect(toUnmodifiableSet()))
			.filter(items -> items.stream().noneMatch(player.getWorn()::containsKey))
			.collect(toList());
	}

	private static Set<Integer> withdrawnIds(Route route)
	{
		return route == null ? Set.of() : route.getWithdraw().stream().map(ItemUse::getItemId).collect(toSet());
	}

	/** Each sub-step, with the ones {@code before} gives for it ahead of it. */
	private static List<SubStep> withBefore(List<SubStep> steps, Function<SubStep, List<SubStep>> before)
	{
		List<SubStep> out = new ArrayList<>();
		for (SubStep sub : steps)
		{
			out.addAll(before.apply(sub));
			out.add(sub);
		}
		return List.copyOf(out);
	}

	/** Named by the piece the player has on them or the route withdraws, else by the lowest id of {@code gear}. */
	private SubStep equip(Set<Integer> gear, Set<Integer> withdrawn)
	{
		Set<Integer> carried = snapshot.get().carriedIds();
		int piece = gear.stream().filter(id -> carried.contains(id) || withdrawn.contains(id))
			.findFirst().orElse(gear.stream().sorted().findFirst().orElseThrow());
		return SubStep.of(Kind.ACT, "Equip the " + client.getItemDefinition(piece).getName()).actItems(gear)
			.worn(List.of(anyOf(client.getItemDefinition(piece).getName(), gear))).done(SubStep.Done.WORN).tab(Tab.INVENTORY).build();
	}

	/** The ship or a balloon: every row landing on Entrana. */
	private static boolean isEntranaCrossing(SubStep step)
	{
		return step.getKind() == Kind.TELEPORT && step.getRow() != null && ENTRANA.equals(step.getRow().getPick());
	}

	private SubStep entranaDeposit(Set<Integer> banned)
	{
		return SubStep.of(Kind.ACT, "Deposit in the deposit box:").items(itemNames(banned))
			.actItems(banned).objectIds(Set.of(ObjectID.SARIM_DEPOSIT_BOX)).option(DEPOSIT)
			.done(SubStep.Done.ITEMS_GONE).build();
	}

	private SubStep bankDeposit(Set<Integer> banned)
	{
		return SubStep.of(Kind.ACT, "Deposit in the bank:").items(itemNames(banned)).actItems(banned)
			.tab(Tab.INVENTORY).done(SubStep.Done.ITEMS_GONE).build();
	}

	private List<String> itemNames(Set<Integer> ids)
	{
		return ids.stream().map(id -> client.getItemDefinition(id).getName()).sorted().collect(toUnmodifiableList());
	}

	/** What to do at the step once there, its STASH items taken out first when they wait there. */
	public List<SubStep> acts(Step step)
	{
		return Acts.of(step, takeFromStash(step), handInItems(step));
	}

	/** What the step's NPC is handed, by name: Charlie's item, or the one Falo the Bard asks for. */
	private List<String> handInItems(Step step)
	{
		StepRequirements requirements = step.getRequirements();
		if (requirements == null)
		{
			return List.of();
		}
		List<ItemRequirement> handedIn = ClueTypes.FALO.equals(step.getType()) ? requirements.getItems() : requirements.getOwnItems();
		return handedIn.stream().map(item -> item.getCollectiveName(client)).collect(toList());
	}

	/** The step's items wait in its filled STASH unit, and the player has none of them on. */
	public boolean takeFromStash(Step step)
	{
		if (step == null || step.getData().getStash() == null)
		{
			return false;
		}
		return stepNeeds.of(step).getStash() == StashMemory.State.FILLED
			&& !step.getRequirements().itemsFulfilledBy(PlayerSnapshot.items(snapshot.get().carriedItems()));
	}

	private boolean carriesScrollBook()
	{
		return snapshot.get().carriesItemNamed(ScrollBook.BOOK_NAME, id -> client.getItemDefinition(id).getName());
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

	/**
	 * "2 x Green dragon leather" while more than one is still to take, else the name alone. A step need's use carries the whole banked stack, so its amount is what the need still lacks; a teleport's use
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

	/** What the step data says core asks for, less what is carried, while a step need it counts towards is unmet. */
	private int stillToTake(Step step, int itemId)
	{
		Map<Integer, Integer> carried = snapshot.get().carriedItems();
		Item[] carriedNow = PlayerSnapshot.items(carried);
		boolean unmet = stepNeeds.of(step).getNeeds().stream().map(RequiredItems.Need::getRequirement)
			.anyMatch(requirement -> requirement.fulfilledBy(itemId) && !requirement.fulfilledBy(carriedNow));
		return unmet ? step.getData().getAmounts().getOrDefault(itemId, 1) - carried.getOrDefault(itemId, 0) : 0;
	}

	/** The sub-steps of one route, built leg by leg: each stage's walk and act in turn, then the step's own acts. */
	@RequiredArgsConstructor
	private final class RouteSteps
	{
		final List<SubStep> steps = new ArrayList<>();
		final HouseState house = snapshot.get().getHouse();
		final Route route;
		final Step step;
		final boolean takeFromStash;

		/** The STASH unit stands outside the step's way in: its items come before going in. */
		private boolean stashFirst()
		{
			return takeFromStash && stashOutsideWayIn(step);
		}

		List<SubStep> build()
		{
			if (route.isOpenBankStart() && !route.getWithdraw().isEmpty())
			{
				steps.add(LegSteps.withdraw(route, use -> withdrawLine(step, use)));
			}
			List<Stage> stages = step.getStages().stream().filter(stage -> !stage.getTiles().isEmpty()).collect(toList());
			List<Leg> legs = route.getLegs();
			int stage = 0;
			WorldPoint start = legs.isEmpty() ? null : legs.get(0).getFrom();
			if (start != null && stages.size() > 1 && stages.get(0).getTiles().contains(start))
			{
				addStageReached(start, stages.get(0), route.getStartBook());
				stage++;
			}
			for (int i = 0; i < legs.size(); i++)
			{
				Leg leg = legs.get(i);
				Leg next = i + 1 < legs.size() ? legs.get(i + 1) : null;
				Exit exit = leg.getKind() == Leg.Kind.WALK ? exitOf(leg) : null;
				Stage reached = stage < stages.size() - 1 && stages.get(stage).getTiles().contains(leg.getTo()) ? stages.get(stage) : null;
				if (reached != null && leg.getKind() == Leg.Kind.WALK && exit == null)
				{
					if (entersStepArea(leg, step))
					{
						steps.add(LegSteps.enter(step.getData()));
					}
					Exit jump = reached.getKind() == Stage.Kind.KEY ? jumpedBy(leg) : null;
					if (jump != null)
					{
						steps.add(LegSteps.jump(jump));
					}
					steps.add(LegSteps.walkToStage(leg, reached, step.getData()));
				}
				else
				{
					addLeg(leg, next, exit);
				}
				if (reached != null)
				{
					addStageReached(leg.getTo(), reached, leg.getBookAfter());
					stage++;
				}
			}
			addLastActs(stages);
			addActsDoneAnywhere(steps, step);
			return steps;
		}

		/**
		 * The stage's act where the route reaches it; a route can also start on it (planned again on arriving in the house
		 * whose spirit tree is the stage).
		 */
		private void addStageReached(WorldPoint at, Stage reached, Book book)
		{
			CatalogRow toPlanted = step.getData().isToPlantedTree() ? plantedTreeTeleport(at) : null;
			if (toPlanted != null)
			{
				steps.add(LegSteps.teleport(Leg.teleport(at, toPlanted, toPlanted.getDuration(), book), house));
			}
			else
			{
				addStageAct(steps, reached.atPlace(at));
			}
		}

		private void addLeg(Leg leg, Leg next, Exit exit)
		{
			switch (leg.getKind())
			{
				case WALK:
					addWalk(leg, next, exit);
					break;
				case WILDERNESS_EXIT:
					steps.add(SubStep.of(Kind.WILDERNESS_EXIT, "Walk south out of deep Wilderness")
						.waypoint(leg.getTo()).goal(leg.getTo()).build());
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
					steps.add(SubStep.of(Kind.BANK, "Open the bank").waypoint(leg.getTo()).build());
					steps.add(LegSteps.withdraw(route, use -> withdrawLine(step, use)));
					break;
				default:
					throw new IllegalArgumentException("unhandled leg kind " + leg.getKind());
			}
		}

		/** A walk into the step's area goes in by its way in, with the STASH items first when the unit stands outside. */
		private void addWalk(Leg leg, Leg next, Exit exit)
		{
			boolean wayIn = exit != null && isWayIn(exit, step.getData().getEntry());
			if ((exit == null || wayIn) && entersStepArea(leg, step))
			{
				if (stashFirst())
				{
					steps.add(LegSteps.stashWalk(stashTile(step)));
					steps.addAll(Acts.fromStash(step));
				}
				steps.add(LegSteps.enter(step.getData()));
			}
			if (exit != null || wayIn || !boardedThere(next))
			{
				steps.add(exit != null && !wayIn ? LegSteps.exit(leg, exit) : LegSteps.walk(leg, next, step, takeFromStash && !stashFirst()));
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
				steps.addAll(Acts.of(step, takeFromStash && !stashFirst(), handInItems(step)));
			}
		}
	}

	/** After the last bank stop and book change, which may be what brings the task's items or spellbook. */
	private static void addActsDoneAnywhere(List<SubStep> steps, Step step)
	{
		int at = 0;
		for (int i = 0; i < steps.size(); i++)
		{
			if (BRINGS_WHAT_THE_TASK_NEEDS.contains(steps.get(i).getKind()))
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
		return SubStep.of(Kind.ACT, stage.getAct()).stage(stage)
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
	 * A bank, fairy ring, ride or spirit tree used where it stands: its own sub-step leads the line there and outlines it
	 * or colours its menu from the start, so no "Walk to" comes before it.
	 */
	private static boolean boardedThere(Leg next)
	{
		return next != null && (next.getKind() == Leg.Kind.FAIRY_RING || next.getKind() == Leg.Kind.BANK
			|| next.getKind() == Leg.Kind.TELEPORT && next.getRow() != null && next.getRow().isLocated() && !next.getRow().isInHouse());
	}

	/**
	 * A bank stop shows what is done in the bank from the way there on, while its line and outline lead to the bank. A
	 * bank opened only to close it again shows itself.
	 */
	private static List<SubStep> withBankTasksShown(List<SubStep> steps)
	{
		List<SubStep> out = new ArrayList<>(steps);
		for (int i = 0; i + 1 < out.size(); i++)
		{
			SubStep bank = out.get(i);
			SubStep task = out.get(i + 1);
			boolean onlyCloses = task.getKind() == Kind.WITHDRAW && task.getItems().isEmpty();
			if (bank.getKind() == Kind.BANK && !onlyCloses)
			{
				out.set(i, bank.toBuilder().text(task.getText()).items(task.getItems())
					.detail(task.getDetail() != null ? task.getDetail() : bank.getDetail()).build());
			}
		}
		return List.copyOf(out);
	}

	/** The STASH unit stands outside the step's way in (the Agility Pyramid's, at its foot): its items come first. */
	private static boolean stashOutsideWayIn(Step step)
	{
		WorldPoint stash = stashTile(step);
		StepIndex.WayIn way = step.getData().getEntry();
		return stash != null && way != null && !way.isInside(stash);
	}

	/** The way in's own row, or another row through its object to where it lands (the crevice's rows from each tile round it). */
	static boolean isWayIn(Exit exit, StepIndex.WayIn way)
	{
		return way != null && exit.getDestination().equals(way.getTo())
			&& (exit.getOrigin().equals(way.getTile()) || way.getObject() >= 0 && exit.getObjectId() == way.getObject());
	}

	static WorldPoint stashTile(Step step)
	{
		return step.getData().getStash() == null ? null : step.getData().getStash().getTile();
	}

	/**
	 * A walk from outside the step's hand-written way in to its target, or to anything else inside it (a STASH unit
	 * behind the same slope). A task's way in leads to its places, not to Sherlock.
	 */
	private static boolean entersStepArea(Leg leg, Step step)
	{
		StepIndex.WayIn way = step.getData().getEntry();
		boolean toTarget = step.getTargets().contains(leg.getTo()) && !hasPlacedStages(step);
		return way != null && (toTarget || way.isInside(leg.getTo())) && !way.isInside(leg.getFrom()) && canPass(leg, way);
	}

	/**
	 * A walk covers at most a tile a half-tick, so one that starts further from the way in than its time allows goes in
	 * another way (into an altar out of the Abyss, not through its ruins). Dungeon tiles are measured from above them:
	 * up through the Trollheim cave to the Trollweiss slope is a short walk.
	 */
	private static boolean canPass(Leg leg, StepIndex.WayIn way)
	{
		return way.getTile() == null || aboveGround(leg.getFrom()).distanceTo2D(aboveGround(way.getTile())) <= leg.getHalfTicks();
	}

	private static boolean hasPlacedStages(Step step)
	{
		return step.getStages().stream().anyMatch(stage -> stage.getKind() != Stage.Kind.FINAL && !stage.getTiles().isEmpty());
	}

	/** The stage's acts after the walk there; what it does anywhere goes before that walk. */
	private static void addStageAct(List<SubStep> steps, Stage stage)
	{
		int walk = steps.size();
		while (walk > 0 && (isWalk(steps.get(walk - 1)) || steps.get(walk - 1).getKind() == Kind.ENTER))
		{
			walk--;
		}
		steps.addAll(walk, stage.getBeforeAnywhere());
		steps.addAll(stageActs(stage));
	}

	/** Gear the act wants worn goes on just before, so each part of a three-step can wear its own. */
	static List<SubStep> stageActs(Stage stage)
	{
		List<SubStep> acts = new ArrayList<>(remove(stage.getEmptied()));
		acts.addAll(stage.getBeforeThere());
		acts.addAll(stage.getWorn().isEmpty() ? List.of() : equip(stage.getWorn()));
		acts.add(stageAct(stage));
		return List.copyOf(acts);
	}

	/** Take off what core's empty-slot requirements forbid; none when there are none. */
	static List<SubStep> remove(List<ItemRequirement> emptied)
	{
		return emptied.isEmpty() ? List.of() : List.of(takeOff(emptied).build());
	}

	private static SubStep.SubStepBuilder takeOff(List<ItemRequirement> emptied)
	{
		String slots = EmptySlots.slotNames(emptied);
		return SubStep.of(Kind.ACT, "Take off " + (slots.equals(EmptySlots.EVERYTHING) ? slots : "your " + slots)).emptied(emptied).done(SubStep.Done.SLOTS_EMPTY)
			.tab(Tab.EQUIPMENT);
	}

	/**
	 * Put on the needs named, done once worn items alone meet them. A need is listed by name ("A negative prayer bonus")
	 * until the pieces that meet it are picked.
	 */
	static List<SubStep> equip(List<String> wornNeeds)
	{
		return List.of(SubStep.of(Kind.ACT, "Equip:").items(wornNeeds)
			.wornNeeds(wornNeeds).done(SubStep.Done.WORN).tab(Tab.INVENTORY).build());
	}

	/**
	 * The act's gear goes on as soon as the route lands, before walking or going in: a course fails without its Graceful,
	 * Ape Atoll's monkeys and a dust devil's cave hurt without theirs.
	 */
	private static List<SubStep> withGearOnArrival(List<SubStep> steps)
	{
		int equip = IntStream.range(0, steps.size()).filter(i -> !steps.get(i).getWornNeeds().isEmpty()).findFirst().orElse(-1);
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

	List<SubStep> withWayInEquipped(List<SubStep> steps, StepIndex.Entry data)
	{
		PlayerSnapshot player = snapshot.get();
		List<StepIndex.Requires> worn = data == null ? List.of() : data.wornRequiresFor(player::finished, player.carriedIds());
		if (worn.isEmpty())
		{
			return steps;
		}
		List<SubStep> equip = new ArrayList<>();
		worn.stream().filter(need -> need.getPutOn() != null).forEach(need -> equip.addAll(putOn(need)));
		List<StepIndex.Requires> ownWear = worn.stream().filter(need -> need.getPutOn() == null).collect(toList());
		if (!ownWear.isEmpty())
		{
			equip.add(SubStep.of(Kind.ACT, "Equip the " + ownWear.stream().map(StepIndex.Requires::getName).collect(joining(" and ")))
				.actItems(ownWear.stream().flatMap(need -> need.getIds().stream()).collect(toUnmodifiableSet()))
				.wornWayIn(true).wayIn(data.getEntry()).done(SubStep.Done.WORN).tab(Tab.INVENTORY).build());
		}
		return withBefore(steps, sub -> sub.getKind() == Kind.ENTER ? equip : List.of());
	}

	/** The slots the NPC's gear goes in are emptied first; the NPC then puts it on, so the gear itself is never clicked. */
	private static List<SubStep> putOn(StepIndex.Requires need)
	{
		StepIndex.PutOn by = need.getPutOn();
		List<SubStep> acts = new ArrayList<>();
		if (!by.getFree().isEmpty())
		{
			List<ItemRequirement> emptied = List.of(new SlotLimitationRequirement(by.getText(), by.getFree().toArray(EquipmentInventorySlot[]::new)));
			acts.add(takeOff(emptied).wornWayIn(true).build());
		}
		acts.add(SubStep.of(Kind.ACT, by.getText()).npcIds(Set.copyOf(by.getNpcs())).option(by.getOption())
			.wornWayIn(true).done(SubStep.Done.WORN).build());
		return acts;
	}

	static ItemRequirement anyOf(String name, Collection<Integer> ids)
	{
		return new AnyRequirementCollection(name, ids.stream().sorted().map(SingleItemRequirement::new).toArray(ItemRequirement[]::new));
	}

	/**
	 * On a route to the way in, its gear goes on first, then the walk there: the walk reads as going in. Gear that only
	 * works there goes on at the way in instead: a sled can only be ridden at the top of Trollweiss Mountain. Already on
	 * the way (diving to the Mogre Camp's rocks), there is nothing to walk back to. Gear an NPC puts on goes on where the
	 * NPC stands (Larry, at the Iceberg's landing).
	 */
	private static List<SubStep> withWalkToWayIn(List<SubStep> steps, StepIndex.Entry data)
	{
		StepIndex.WayIn way = data == null ? null : data.getEntry();
		Predicate<SubStep> equip = sub -> sub.getWayIn() != null && sub.getKind() == Kind.ACT;
		if (way == null || steps.stream().noneMatch(equip))
		{
			return steps;
		}
		Predicate<SubStep> walkBefore = way.isGearThere() ? equip : sub -> sub.getKind() == Kind.ENTER;
		return withBefore(steps, sub -> walkBefore.test(sub)
			? List.of(LegSteps.walkTo("Walk to " + way.getText(), way.getTile(), ARRIVE_TILES).wayIn(way).build()) : List.of());
	}

	/** Client thread only. Weapons and armour as the Entrana monks see them: any attack, defence or strength bonus. */
	@Singleton
	public static class CombatGear
	{
		@Inject
		ItemManager itemManager;

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
