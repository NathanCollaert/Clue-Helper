package com.cluehelper.route;

import com.cluehelper.memory.StashMemory;
import com.cluehelper.player.*;
import com.cluehelper.step.*;
import com.cluehelper.transport.AvailabilityService;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;
import javax.inject.*;
import net.runelite.api.Client;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.cluescrolls.clues.item.*;

/** Client thread only: reads varbits. */
@Singleton
public class StepNeeds
{
	/** Copy of core's {@code ClueScrollOverlay.HAS_SPADE} (1.13.2). */
	private static final ItemRequirement SPADE = ItemRequirements.any("Spade", ItemRequirements.item(ItemID.SPADE),
		ItemRequirements.item(ItemID.EASTER23_SPADE));
	/** Core's {@code ClueScrollOverlay.HAS_LIGHT} item ids (1.13.2). */
	private static final int[] LIGHT_SOURCES = {594, 33, 32, 4531, 4534, 4524, 4539, 4550, 4702, 9065, 5013, 9804, 9805, 13137,
		13138, 13139, 13140, 20720, 29777, 13280, 13342, 26824, 26826, 26828, 26830, 26832, 26834, 26836, 26838, 26840, 26842,
		26844, 26846, 26848, 30363};
	private static final ItemRequirement LIGHT = light();
	/** Core's *clue* bank tag holds a plain spade for these types, and every emote and skill-challenge item. */
	private static final Set<String> SPADE_IN_CORE_TAG = Set.of(ClueTypes.COORDINATE, ClueTypes.HOT_COLD, ClueTypes.FAIRY_RING,
		ClueTypes.MAP, ClueTypes.BEGINNER_MAP);
	private static final Set<String> ITEMS_IN_CORE_TAG = Set.of(ClueTypes.EMOTE, ClueTypes.SKILL);
	private static final int LIT = 1;

	private final Client client;
	private final IntFunction<StashMemory.State> stashStates;
	private final BiFunction<GearBonus, Collection<Integer>, Map<Integer, GearPicker.Piece>> gearPieces;
	private final Supplier<Set<Integer>> ownedItemIds;
	/** One requirement per hand-data entry, so a re-plan's needs equal the last ones. */
	private final Map<StepIndex.Requires, ItemRequirement> wayInItems = new HashMap<>();
	private final Map<StepIndex.Alternative, ItemRequirement> widened = new HashMap<>();

	@Inject
	StepNeeds(Client client, StashMemory stash, GearPieces gearPieces, AvailabilityService availability, PlayerModel player)
	{
		this(client, stash::state, gearPieces::of, () -> ownedItemIds(player.getSnapshot(), availability.getBankedSnapshot()));
	}

	/**
	 * @param client names, real levels and varbits
	 * @param ownedItemIds what gear is picked from: carried, and stored while banking counts
	 */
	public StepNeeds(Client client, IntFunction<StashMemory.State> stashStates,
		BiFunction<GearBonus, Collection<Integer>, Map<Integer, GearPicker.Piece>> gearPieces, Supplier<Set<Integer>> ownedItemIds)
	{
		this.client = client;
		this.stashStates = stashStates;
		this.gearPieces = gearPieces;
		this.ownedItemIds = ownedItemIds;
	}

	public RequiredItems of(Step step)
	{
		StepRequirements requirements = step.getRequirements();
		if (requirements == null)
		{
			return RequiredItems.NONE;
		}
		List<RequiredItems.Need> needs = new ArrayList<>();
		if (requirements.getSpade() == SpadeNeed.SPADE)
		{
			needs.add(new RequiredItems.Need("spade", SPADE, false, SPADE_IN_CORE_TAG.contains(step.getType()), false));
		}
		if (requirements.needsLight() && !firePitLit(requirements))
		{
			needs.add(new RequiredItems.Need("light source", LIGHT, false, false, false));
		}
		boolean inCoreTag = ITEMS_IN_CORE_TAG.contains(step.getType());
		for (ItemRequirement item : requirements.getItems())
		{
			boolean optional = isOptional(item.getCollectiveName(client), step.getData());
			if (!EmptySlots.metByEmptySlots(item) && !optional)
			{
				ItemRequirement need = withAlternatives(item, step.getData());
				needs.add(new RequiredItems.Need(item.getCollectiveName(client), need, true, inCoreTag && need == item, true));
			}
		}
		for (ItemRequirement item : requirements.getOwnItems())
		{
			needs.add(new RequiredItems.Need(item.getCollectiveName(client), item, false, false, true));
		}
		boolean gettingLeft = isGettingLeft(step);
		for (StepIndex.Requires way : taskDone(step) ? List.<StepIndex.Requires>of() : step.getData().getRequires())
		{
			// An item the route still goes to get (the battered key from its bookcase) isn't brought along
			if (!(gettingLeft && way.getFrom() != null))
			{
				needs.add(new RequiredItems.Need(way.getName(), wayInItems.computeIfAbsent(way, StepNeeds::anyOf), false, false, false));
			}
		}
		ItemRequirement key = ownedKey(step);
		if (key != null)
		{
			needs.add(new RequiredItems.Need(key.getCollectiveName(client), key, false, false, false));
		}
		Set<Integer> owned = step.getData().getGear().isEmpty() ? Set.of() : ownedItemIds.get();
		for (StepIndex.Gear gear : step.getData().getGear())
		{
			GearRequirement worn = new GearRequirement(gear.getName(), gear.getAtLeast(), gearPieces.apply(gear.getBonus(), owned));
			needs.add(new RequiredItems.Need(gear.getName(), worn, false, false, false));
		}
		return RequiredItems.builder()
			.needs(List.copyOf(needs))
			.stash(stash(requirements))
			.spadeAtLeprechaun(client.getVarbitValue(VarbitID.FARMING_TOOLS_SPADE) > 0)
			.build();
	}

	private boolean isOptional(String name, StepIndex.Entry data)
	{
		List<StepIndex.Level> from = data.getOptionalFrom().get(name);
		return data.getOptional().contains(name)
			|| from != null && from.stream().allMatch(level -> client.getRealSkillLevel(level.getSkill()) >= level.getLevel());
	}

	/** Core's item, or the hand data's other way to the same thing; core's bank tag then lacks the other items. */
	private ItemRequirement withAlternatives(ItemRequirement item, StepIndex.Entry data)
	{
		StepIndex.Alternative other = data == null ? null
			: data.getAlternatives().stream().filter(alternative -> item.fulfilledBy(alternative.getInstead())).findFirst().orElse(null);
		if (other == null)
		{
			return item;
		}
		return widened.computeIfAbsent(other, alternative -> new AlternativeRequirement(item, alternative.getAll().stream()
			.map(this::part).collect(Collectors.toList())));
	}

	/** Any of these ids, named by them: "Grimy cadantine or Cadantine". */
	private AlternativeRequirement.Part part(List<Integer> ids)
	{
		String name = ids.stream().map(id -> client.getItemDefinition(id).getName()).distinct().collect(Collectors.joining(" or "));
		return new AlternativeRequirement.Part(name, ItemRequirements.any(name, ids.stream().map(ItemRequirements::item)
			.toArray(ItemRequirement[]::new)));
	}

	/** A skill challenge whose task core marked done: the way to the task's place (a talisman for its ruins) isn't needed. */
	private static boolean taskDone(Step step)
	{
		return ClueTypes.SKILL.equals(step.getType()) && step.getStages().stream().noneMatch(stage -> stage.getKind() == Stage.Kind.TASK);
	}

	/** Gear is picked from all of these, the bank only while banking counts. */
	public static Set<Integer> ownedItemIds(PlayerSnapshot snapshot, PlayerSnapshot banked)
	{
		Set<Integer> ids = new HashSet<>(snapshot == null ? Set.of() : snapshot.carriedIds());
		if (banked != null)
		{
			ids.addAll(banked.storedIds());
		}
		return ids;
	}

	private boolean firePitLit(StepRequirements requirements)
	{
		return requirements.getFirePitVarbitId() != -1 && client.getVarbitValue(requirements.getFirePitVarbitId()) == LIT;
	}

	private StashMemory.State stash(StepRequirements requirements)
	{
		return requirements.hasStash() ? stashStates.apply(requirements.getStashObjectId()) : StashMemory.State.NONE;
	}

	/** A stage that gets an item (a key, the battered key) is still ahead. */
	private static boolean isGettingLeft(Step step)
	{
		return step.getStages().stream().anyMatch(stage -> stage.getKind() == Stage.Kind.KEY);
	}

	/**
	 * The step's key once its kill stage is dropped, the key or its ring being owned (StageTarget): brought along, out of
	 * the bank when it's there. Null while the key is still to be got, or for a step without one.
	 */
	private static ItemRequirement ownedKey(Step step)
	{
		return isGettingLeft(step) ? null : step.getData().getTargets().stream()
			.filter(target -> target.getKind() == StepIndex.Target.Kind.KEY && target.getKeyTile() != null)
			.findFirst().map(target -> Stages.keyItem(target.getKey())).orElse(null);
	}

	private static ItemRequirement anyOf(StepIndex.Requires way)
	{
		ItemRequirement[] items = way.getIds().stream().map(ItemRequirements::item).toArray(ItemRequirement[]::new);
		return ItemRequirements.any(way.getName(), items);
	}

	private static ItemRequirement light()
	{
		ItemRequirement[] items = new ItemRequirement[LIGHT_SOURCES.length];
		for (int i = 0; i < items.length; i++)
		{
			items[i] = ItemRequirements.item(LIGHT_SOURCES[i]);
		}
		return ItemRequirements.any("Light source", items);
	}
}
