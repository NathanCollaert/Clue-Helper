package com.cluehelper.link;

import com.cluehelper.*;
import com.cluehelper.guide.SubStep;
import com.cluehelper.memory.*;
import com.cluehelper.route.*;
import com.cluehelper.step.*;
import java.util.*;
import java.util.stream.Stream;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.cluescrolls.clues.ClueScroll;
import static java.util.Comparator.comparingInt;
import static java.util.stream.Collectors.toList;

/**
 * Client thread. Juggling master three-steps: the quickest one put down is kept aside, and the torn pieces of its parts
 * are combined onto slower three-steps. The game tells nothing of a master clue until it is read, so the kept clue's
 * parts come from one read and its place is followed through every drop, pick-up and bank visit after it. While the kept
 * clue is quicker than the three-step held, the step guided is the swap, not core's.
 */
@Singleton
public class ThreeStepSwap
{
	static final String KEY = "threeStepSwap";
	/** A dropped item lasts 61 minutes of logged-in time. */
	private static final int FLOOR_TICKS = 6100;
	private static final int REMIND_TICKS = 500;
	private static final int TICKS_PER_MINUTE = 100;
	/** A drop's ground item can show a tick after the clue leaves the inventory. */
	private static final int DROP_TICKS = 2;
	private static final int NODE_SEARCH_TILES = 15;
	private static final int CLUE = ItemID.TRAIL_CLUE_MASTER;
	/** By part, in core's order. */
	private static final List<Integer> PIECES = List.of(ItemID.TRAIL_MASTER_PART1, ItemID.TRAIL_MASTER_PART2,
		ItemID.TRAIL_MASTER_PART3);
	private static final String MASTER = "master";
	private static final String COMBINE_OPTION = "Combine";
	private static final SubStep DROP = act("Drop the master clue").actItems(Set.of(CLUE)).actOption("Drop").build();
	private static final SubStep PICK_UP = act("Pick up the master clue").groundItem(CLUE).build();
	/** Beside a dropped clue, in the order tried. */
	private static final List<int[]> BESIDE = List.of(new int[]{1, 0}, new int[]{-1, 0}, new int[]{0, 1}, new int[]{0, -1});
	private static final SubStep WITHDRAW = act("Withdraw the master clue").bankItems(Set.of(CLUE)).build();
	private static final SubStep COMBINE = act("Combine the torn clue scrolls").actItems(Set.copyOf(PIECES))
		.actOption(COMBINE_OPTION).build();

	@Inject
	Client client;
	@Inject
	ClueHelperConfig config;
	@Inject
	BankMemory bankMemory;
	@Inject
	StepResolver resolver;
	@Inject
	ThreeStepScorer scorer;
	@Inject
	RoutePlanner routes;
	MemorySlot<Clues> remembered;
	/** Whether a master clue was in the inventory at the last tick, and how many pieces. */
	boolean held;
	int pieces;
	/** The parts of the three-step held, as core read them, when it is neither tracked clue; null for none. */
	List<String> readParts;
	List<String> comparedParts;
	ThreeStepKeep comparedBy;
	/** Whether {@link #readParts} are quicker than the kept clue's; null until compared. */
	Boolean readQuicker;
	/** This tick's master clues laid on the ground, by where they lie. */
	final Map<TileItem, WorldPoint> spawned = new LinkedHashMap<>();
	TileItem despawned;
	Clue dropping;
	int droppedTick;
	/** Where the player stood once all three pieces were held: the read clue is dropped there. */
	WorldPoint piecesTile;

	@Inject
	void init(MemoryStore memory)
	{
		remembered = memory.slot(KEY, Clues.class, Clues::new);
	}

	public void onItemSpawned(ItemSpawned event)
	{
		if (event.getItem().getId() == CLUE)
		{
			spawned.put(event.getItem(), event.getTile().getWorldLocation());
		}
	}

	public void onItemDespawned(ItemDespawned event)
	{
		if (event.getItem().getId() == CLUE)
		{
			despawned = event.getItem();
		}
	}

	/** Logging out pauses the floor's timer, and a scene load lays every ground item anew. */
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING)
		{
			tracked(remembered.get()).forEach(clue -> clue.item = null);
		}
		else if (event.getGameState() != GameState.LOGGED_IN)
		{
			held = false;
			pieces = 0;
			remembered.saveHeld();
		}
	}

	/** Call from a GameTick handler after core's, with core's clue and its step. */
	public void onGameTick(ClueScroll coreClue, Step coreStep)
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory == null)
		{
			return;
		}
		Clues clues = remembered.get();
		boolean heldNow = inventory.contains(CLUE);
		int piecesNow = piecesHeld().size();
		boolean piecesUsed = held && pieces > 0 && piecesNow == 0 && !bankMemory.isBankOpen();
		if (piecesUsed)
		{
			combined(clues);
		}
		else if (heldNow && !held)
		{
			taken(clues);
		}
		else if (!heldNow && held)
		{
			putDown(clues, piecesNow == PIECES.size());
		}
		confirmDrop(clues);
		attachSpawned(clues);
		read(clues, coreClue, coreStep);
		countDown(clues);
		compare(clues);
		held = heldNow;
		pieces = piecesNow;
		piecesTile = piecesNow < PIECES.size() ? null : piecesTile != null ? piecesTile : playerTile();
		spawned.clear();
		despawned = null;
	}

	/** The step to guide: the swap's while one is under way, else core's. */
	public Step guided(Step coreStep)
	{
		Clues clues = remembered.get();
		Clue kept = clues.kept;
		if (!config.threeStepSwaps() || kept == null || kept.place == null)
		{
			return coreStep;
		}
		Step swap = swapStep(kept, clues.onto != null && clues.onto.place != null ? clues.onto : null, heldClue(clues),
			piecesHeld(), coreStep);
		return swap != null ? swap : coreStep;
	}

	/** The box's line above the rest when the three-step just read is quicker than the kept one; null otherwise. */
	public String headline()
	{
		Clue kept = remembered.get().kept;
		boolean readQuickerThanKept = config.threeStepSwaps() && Boolean.TRUE.equals(readQuicker) && kept != null
			&& kept.place != Place.HELD;
		return readQuickerThanKept ? "Quicker than your kept three-step" : null;
	}

	/** The box's warnings about the kept clue. */
	public List<String> notes()
	{
		Clue kept = remembered.get().kept;
		if (!config.threeStepSwaps() || kept == null)
		{
			return List.of();
		}
		if (kept.place != Place.FLOOR || kept.ticksLeft > REMIND_TICKS)
		{
			return List.of();
		}
		int minutes = (kept.ticksLeft + TICKS_PER_MINUTE - 1) / TICKS_PER_MINUTE;
		return List.of("Your kept clue vanishes in " + minutes + " min: pick it up and drop it again");
	}

	/**
	 * Call from a PostMenuSort handler after the guide's. Pieces meant for a slower three-step must not go onto the kept
	 * clue by a left click, so their next option takes Combine's place on top; Combine stays in the menu.
	 */
	public void onPostMenuSort()
	{
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		int top = entries.length - 1;
		if (client.isMenuOpen() || top < 1 || !holdsQuickest() || !COMBINE_OPTION.equals(entries[top].getOption())
			|| !PIECES.contains(entries[top].getItemId()) || entries[top - 1].getItemId() != entries[top].getItemId())
		{
			return;
		}
		MenuEntry combine = entries[top];
		entries[top] = entries[top - 1];
		entries[top - 1] = combine;
		client.getMenu().setMenuEntries(entries);
	}

	private boolean holdsQuickest()
	{
		Clue kept = remembered.get().kept;
		return config.threeStepSwaps() && (kept != null && kept.place == Place.HELD || Boolean.TRUE.equals(readQuicker));
	}

	/**
	 * Each phase of a swap is a step of its own, so the guide moves on as the clues move: to the kept clue with the slow
	 * one, through the kept clue's parts, back to the slow one with the pieces, and onto it.
	 */
	private Step swapStep(Clue kept, Clue onto, Clue tracked, Set<Integer> done, Step coreStep)
	{
		boolean allPieces = done.size() == PIECES.size();
		if (tracked == kept)
		{
			return onto == null ? null : allPieces ? at(onto, DROP) : keptParts(kept, done, coreStep);
		}
		if (tracked != null && tracked == onto)
		{
			return allPieces ? step(List.of(tracked.tile != null ? tracked.tile : playerTile()), COMBINE) : at(kept, DROP);
		}
		if (Boolean.FALSE.equals(readQuicker))
		{
			return allPieces ? null : at(kept, DROP);
		}
		if (Boolean.TRUE.equals(readQuicker))
		{
			return allPieces && piecesTile != null ? step(List.of(piecesTile), DROP) : null;
		}
		if (!held && onto != null)
		{
			return allPieces ? at(onto, take(onto)) : at(kept, take(kept));
		}
		return null;
	}

	private static SubStep take(Clue clue)
	{
		return clue.place == Place.BANK ? WITHDRAW : PICK_UP;
	}

	/** Null for a clue held. A clue is dropped beside one on the floor, so each lies apart and is picked up on its own. */
	private Step at(Clue clue, SubStep act)
	{
		WalkData walk = routes.getWalk();
		if (clue.place == Place.FLOOR)
		{
			WorldPoint tile = act == DROP && clue.beside != null ? clue.beside : clue.tile;
			SubStep.SubStepBuilder there = act == PICK_UP ? act.toBuilder().groundItemTile(tile) : act.toBuilder();
			return step(List.of(walk == null ? tile : walkNodeNear(walk.getTable(), tile)), there.waypoint(tile).build());
		}
		return clue.place == Place.BANK && walk != null ? step(walk.getTable().bankTiles(), act) : null;
	}

	/**
	 * The route ends at a walk node, the only tiles it reaches from afar: the one nearest where the clue lies, from which
	 * the act's own tile is walked to. The tile itself when none is near.
	 */
	private static WorldPoint walkNodeNear(WalkTable table, WorldPoint tile)
	{
		for (int ring = 0; ring <= NODE_SEARCH_TILES; ring++)
		{
			for (int dx = -ring; dx <= ring; dx++)
			{
				for (int dy = -ring; dy <= ring; dy++)
				{
					WorldPoint near = tile.dx(dx).dy(dy);
					if (Math.max(Math.abs(dx), Math.abs(dy)) == ring && table.node(near) != WalkTable.NO_NODE)
					{
						return near;
					}
				}
			}
		}
		return tile;
	}

	/** A drop or pick-up's tile is marked; at a bank, any bank's will do, and the pieces combine anywhere. */
	private static Step step(List<WorldPoint> targets, SubStep act)
	{
		boolean marked = targets.size() == 1 && act != COMBINE && act.getWaypoint() == null;
		SubStep there = marked ? act.toBuilder().waypoint(targets.get(0)).build() : act;
		return Step.builder().key(ClueTypes.THREE_STEP + ":" + act.getText()).type(ClueTypes.THREE_STEP).tier(MASTER)
			.text(act.getText()).kind(TargetKind.ANY_OF).targets(targets).data(StepIndex.Entry.ofActs(List.of(there))).build();
	}

	private static SubStep.SubStepBuilder act(String text)
	{
		return SubStep.of(SubStep.Kind.ACT, text);
	}

	/** From the bank if one of the clues is in it, else by the ground item that went; any other master clue is new. */
	private void taken(Clues clues)
	{
		Clue taken = tracked(clues).filter(clue -> bankMemory.isBankOpen() ? clue.place == Place.BANK
			: clue.item != null && clue.item == despawned).findFirst().orElse(null);
		readParts = null;
		if (taken != null)
		{
			taken.place = Place.HELD;
			taken.tile = playerTile();
			taken.item = null;
			remembered.set(clues);
		}
	}

	private void putDown(Clues clues, boolean allPieces)
	{
		Clue down = heldClue(clues);
		if (down == null && readParts != null)
		{
			down = learn(clues, allPieces);
		}
		readParts = null;
		readQuicker = null;
		if (down == null)
		{
			return;
		}
		if (bankMemory.isBankOpen())
		{
			down.place = Place.BANK;
			remembered.set(clues);
			return;
		}
		dropping = down;
		droppedTick = client.getTickCount();
	}

	/**
	 * The read three-step put down without combining: the quickest becomes the kept clue, and the clue kept before is the
	 * one its pieces go onto. A slower one is what the kept clue's pieces go onto.
	 */
	private Clue learn(Clues clues, boolean allPieces)
	{
		Clue learned = new Clue(readParts);
		if (clues.kept == null || Boolean.TRUE.equals(readQuicker))
		{
			clues.onto = allPieces && clues.kept != null ? clues.kept : clues.onto;
			clues.kept = learned;
			return learned;
		}
		if (Boolean.FALSE.equals(readQuicker))
		{
			clues.onto = learned;
			return learned;
		}
		return null;
	}

	/** The pieces went onto the clue held, which moved on to its next step or, finished, became a casket. */
	private void combined(Clues clues)
	{
		Clue held = heldClue(clues);
		readParts = null;
		if (held != null)
		{
			forget(clues, held);
			remembered.set(clues);
		}
	}

	/** A clue that left the inventory without its ground item showing was used up, not dropped. */
	private void confirmDrop(Clues clues)
	{
		if (dropping == null)
		{
			return;
		}
		WorldPoint at = playerTile();
		TileItem item = spawned.keySet().stream().filter(spawn -> at != null && spawned.get(spawn).distanceTo(at) <= 1)
			.findFirst().orElse(null);
		if (item != null)
		{
			dropping.place = Place.FLOOR;
			dropping.tile = spawned.remove(item);
			dropping.beside = besideTile(dropping.tile, clues);
			dropping.ticksLeft = FLOOR_TICKS;
			dropping.item = item;
		}
		else if (client.getTickCount() - droppedTick < DROP_TICKS)
		{
			return;
		}
		else
		{
			forget(clues, dropping);
		}
		dropping = null;
		remembered.set(clues);
	}

	/** A free tile next to a dropped clue, reachable from it, with no other clue on it; the clue's own if there is none. */
	private WorldPoint besideTile(WorldPoint tile, Clues clues)
	{
		WorldArea from = new WorldArea(tile, 1, 1);
		for (int[] step : BESIDE)
		{
			WorldPoint next = tile.dx(step[0]).dy(step[1]);
			if (from.canTravelInDirection(client.getTopLevelWorldView(), step[0], step[1]) && tracked(clues).noneMatch(clue -> next.equals(clue.tile)))
			{
				return next;
			}
		}
		return tile;
	}

	/**
	 * A loaded scene's ground items, matched to the clues remembered on their tiles. Two on one tile pair by age: the clue
	 * with less time left is the item that despawns first.
	 */
	private void attachSpawned(Clues clues)
	{
		List<TileItem> items = spawned.keySet().stream().sorted(comparingInt(TileItem::getDespawnTime)).collect(toList());
		List<Clue> waiting = tracked(clues).filter(clue -> clue.place == Place.FLOOR && clue.item == null)
			.sorted(comparingInt(clue -> clue.ticksLeft)).collect(toList());
		for (Clue clue : waiting)
		{
			items.stream().filter(item -> spawned.get(item).equals(clue.tile)).findFirst().ifPresent(item ->
			{
				clue.item = item;
				items.remove(item);
			});
		}
	}

	/**
	 * A read tells which clue is held: a tracked one by its parts, or another three-step. A clue thought held that reads
	 * as something else was never there: its place is no longer known.
	 */
	private void read(Clues clues, ClueScroll coreClue, Step coreStep)
	{
		if (coreStep == null || !MASTER.equals(coreStep.getTier()))
		{
			return;
		}
		List<String> parts = CoreClueReader.threeStepParts(coreClue);
		if (clues.kept == null && !parts.isEmpty() && config.threeStepSwaps())
		{
			clues.kept = new Clue(parts);
		}
		Clue known = tracked(clues).filter(clue -> clue.parts.equals(parts)).findFirst().orElse(null);
		Clue thought = heldClue(clues);
		List<String> other = known == null && !parts.isEmpty() ? parts : null;
		if (thought == known && Objects.equals(other, readParts))
		{
			return;
		}
		if (thought != null && thought != known)
		{
			thought.place = null;
		}
		if (known != null)
		{
			known.place = Place.HELD;
		}
		readParts = other;
		remembered.set(clues);
	}

	/** Only logged-in ticks count, as for the game's own timer; written to the profile once a minute. */
	private void countDown(Clues clues)
	{
		List<Clue> floor = tracked(clues).filter(clue -> clue.place == Place.FLOOR).collect(toList());
		if (floor.isEmpty())
		{
			return;
		}
		floor.forEach(clue -> clue.ticksLeft--);
		floor.stream().filter(clue -> clue.ticksLeft <= 0).forEach(clue -> forget(clues, clue));
		if (client.getTickCount() % TICKS_PER_MINUTE == 0)
		{
			remembered.set(clues);
		}
		else
		{
			remembered.hold(clues);
		}
	}

	/** The read three-step against the kept clue, scored again for each new read and each change of the setting. */
	private void compare(Clues clues)
	{
		ThreeStepKeep by = config.threeStepKeep();
		if (readParts == null || clues.kept == null || !config.threeStepSwaps())
		{
			comparedParts = null;
			return;
		}
		if (readParts.equals(comparedParts) && by == comparedBy)
		{
			return;
		}
		List<String> parts = readParts;
		readQuicker = null;
		if (scorer.compare(parts, clues.kept.parts, quicker -> readQuicker = parts.equals(readParts) ? quicker : readQuicker))
		{
			comparedParts = parts;
			comparedBy = by;
		}
	}

	private static Clue heldClue(Clues clues)
	{
		return tracked(clues).filter(clue -> clue.place == Place.HELD).findFirst().orElse(null);
	}

	private static Stream<Clue> tracked(Clues clues)
	{
		return Stream.of(clues.kept, clues.onto).filter(Objects::nonNull);
	}

	private static void forget(Clues clues, Clue clue)
	{
		clues.kept = clues.kept == clue ? null : clues.kept;
		clues.onto = clues.onto == clue ? null : clues.onto;
	}

	/** By part index. */
	private Set<Integer> piecesHeld()
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		Set<Integer> held = new HashSet<>();
		for (int part = 0; part < PIECES.size(); part++)
		{
			if (inventory != null && inventory.contains(PIECES.get(part)))
			{
				held.add(part);
			}
		}
		return held;
	}

	/**
	 * The kept clue's parts: core's once the held clue is read again, which names and marks their NPCs, else the step
	 * data's by the parts' texts.
	 */
	private Step keptParts(Clue kept, Set<Integer> done, Step coreStep)
	{
		boolean read = coreStep != null && ClueTypes.THREE_STEP.equals(coreStep.getType());
		return read ? coreStep : resolver.threeStep(kept.parts, done, this::npcName);
	}

	/** The NPC's name as it shows: a multi-NPC (the Warriors' Guild doorman) is named only in its shown form; else null. */
	private String npcName(int npcId)
	{
		NPCComposition npc = client.getNpcDefinition(npcId);
		NPCComposition shown = npc.getConfigs() == null ? npc : npc.transform();
		String name = shown == null ? null : shown.getName();
		return name == null || "null".equals(name) ? null : name;
	}

	private WorldPoint playerTile()
	{
		Player local = client.getLocalPlayer();
		return local == null ? null : local.getWorldLocation();
	}

	enum Place
	{
		HELD,
		BANK,
		FLOOR
	}

	/** Kept in the profile. */
	static final class Clues
	{
		Clue kept;
		/** While a swap is under way: the three-step the pieces go onto. */
		Clue onto;
	}

	static final class Clue
	{
		List<String> parts = List.of();
		/** Null once the plugin lost track of it. */
		Place place;
		/** On the floor, where it lies; held, where it was taken. */
		WorldPoint tile;
		/** On the floor: the free tile next to it, where another clue is dropped. */
		WorldPoint beside;
		/** On the floor: the logged-in ticks until it vanishes. */
		int ticksLeft;
		/** Its ground item while the scene holds it. */
		transient TileItem item;

		Clue()
		{
		}

		Clue(List<String> parts)
		{
			this.parts = List.copyOf(parts);
		}
	}
}
