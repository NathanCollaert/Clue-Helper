package com.cluehelper.route;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.DiggingDog;
import com.cluehelper.memory.StashMemory;
import com.cluehelper.step.ClueTypes;
import com.cluehelper.step.SpadeNeed;
import com.cluehelper.step.Step;
import com.cluehelper.step.StepIndex;
import com.cluehelper.step.StepRequirements;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirements;
import net.runelite.client.plugins.cluescrolls.clues.item.SlotLimitationRequirement;

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
	private final StashMemory stash;
	private final ClueHelperConfig config;
	/** One requirement per hand-data entry, so a re-plan's needs equal the last ones. */
	private final Map<StepIndex.Requires, ItemRequirement> wayInItems = new HashMap<>();

	@Inject
	StepNeeds(Client client, StashMemory stash, ClueHelperConfig config)
	{
		this.client = client;
		this.stash = stash;
		this.config = config;
	}

	public RequiredItems of(Step step)
	{
		StepRequirements requirements = step.getRequirements();
		if (requirements == null)
		{
			return RequiredItems.NONE;
		}
		List<RequiredItems.Need> needs = new ArrayList<>();
		boolean dog = config.diggingDog() == DiggingDog.ALWAYS;
		if (requirements.getSpade() == SpadeNeed.SPADE || requirements.getSpade() == SpadeNeed.SPADE_OR_DIGGING_DOG && !dog)
		{
			needs.add(new RequiredItems.Need("spade", SPADE, false, SPADE_IN_CORE_TAG.contains(step.getType())));
		}
		if (requirements.needsLight() && !firePitLit(requirements))
		{
			needs.add(new RequiredItems.Need("light source", LIGHT, false, false));
		}
		boolean inCoreTag = ITEMS_IN_CORE_TAG.contains(step.getType());
		for (ItemRequirement item : requirements.getItems())
		{
			if (!(item instanceof SlotLimitationRequirement))
			{
				needs.add(new RequiredItems.Need(item.getCollectiveName(client), item, true, inCoreTag));
			}
		}
		for (ItemRequirement item : requirements.getOwnItems())
		{
			needs.add(new RequiredItems.Need(item.getCollectiveName(client), item, false, false));
		}
		if (step.getData() != null)
		{
			for (StepIndex.Requires way : step.getData().getRequires())
			{
				needs.add(new RequiredItems.Need(way.getName(), wayInItems.computeIfAbsent(way, StepNeeds::anyOf), false, false));
			}
		}
		return RequiredItems.builder()
			.needs(List.copyOf(needs))
			.stash(stash(requirements))
			.spadeAtLeprechaun(client.getVarbitValue(VarbitID.FARMING_TOOLS_SPADE) > 0)
			.build();
	}

	private boolean firePitLit(StepRequirements requirements)
	{
		return requirements.getFirePitVarbitId() != -1 && client.getVarbitValue(requirements.getFirePitVarbitId()) == LIT;
	}

	private RequiredItems.Stash stash(StepRequirements requirements)
	{
		if (requirements.getStashObjectId() == -1)
		{
			return RequiredItems.Stash.NONE;
		}
		return RequiredItems.Stash.valueOf(stash.state(requirements.getStashObjectId()).name());
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
