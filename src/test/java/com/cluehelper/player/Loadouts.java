package com.cluehelper.player;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Value;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

/**
 * B02's seven fixture loadouts ({@code src/test/resources/loadouts/}, copied from
 * {@code tools/geometry/fixtures/}) as player snapshots, for B10's golden routes. A fixture names its
 * items by family; each family maps to its fully charged id.
 */
public final class Loadouts
{
	public static final List<String> NAMES = List.of("maxed-main", "mid-ironman", "hcim", "gim", "uim", "fresh", "f2p");

	private static final Map<String, Integer> ITEMS = Map.ofEntries(
		Map.entry("Amulet of glory", ItemID.AMULET_OF_GLORY_4),
		Map.entry("Ardougne cloak", ItemID.ARDY_CAPE_ELITE),
		Map.entry("Ardougne tablet", ItemID.POH_TABLET_ARDOUGNETELEPORT),
		Map.entry("Book of the dead", ItemID.BOOK_OF_THE_DEAD),
		Map.entry("Burning amulet", ItemID.BURNING_AMULET_5),
		Map.entry("Camelot tablet", ItemID.POH_TABLET_CAMELOTTELEPORT),
		Map.entry("Combat bracelet", ItemID.JEWL_BRACELET_OF_COMBAT_6),
		Map.entry("Construction cape", ItemID.SKILLCAPE_CONSTRUCTION),
		Map.entry("Crafting cape", ItemID.SKILLCAPE_CRAFTING),
		Map.entry("Desert amulet 4", ItemID.DESERT_AMULET_ELITE),
		Map.entry("Digsite pendant", ItemID.NECKLACE_OF_DIGSITE_5),
		Map.entry("Drakan's medallion", ItemID.DRAKANS_MEDALLION),
		Map.entry("Ectophial", ItemID.ECTOPHIAL),
		Map.entry("Eternal teleport crystal", ItemID.PRIF_TELEPORT_CRYSTAL),
		Map.entry("Falador tablet", ItemID.POH_TABLET_FALADORTELEPORT),
		Map.entry("Fishing cape", ItemID.SKILLCAPE_FISHING),
		Map.entry("Fremennik sea boots", ItemID.FREMENNIK_BOOTS_ELITE),
		Map.entry("Games necklace", ItemID.NECKLACE_OF_MINIGAMES_8),
		Map.entry("Kandarin headgear 4", ItemID.SEERS_HEADBAND_ELITE),
		Map.entry("Karamja gloves 4", ItemID.ATJUN_GLOVES_ELITE),
		Map.entry("Lumbridge tablet", ItemID.POH_TABLET_LUMBRIDGETELEPORT),
		Map.entry("Master scroll book", ItemID.BOOKOFSCROLLS_CHARGED),
		Map.entry("Max cape", ItemID.SKILLCAPE_MAX),
		Map.entry("Morytania legs", ItemID.MORYTANIA_LEGS_ELITE),
		Map.entry("Necklace of passage", ItemID.NECKLACE_OF_PASSAGE_5),
		Map.entry("Pendant of ates", ItemID.PENDANT_OF_ATES),
		Map.entry("Pharaoh's sceptre", ItemID.PHARAOHS_SCEPTRE),
		Map.entry("Ring of dueling", ItemID.RING_OF_DUELING_8),
		Map.entry("Ring of wealth", ItemID.RING_OF_WEALTH_5),
		Map.entry("Royal seed pod", ItemID.MM2_ROYAL_SEED_POD),
		Map.entry("Skills necklace", ItemID.JEWL_NECKLACE_OF_SKILLS_6),
		Map.entry("Slayer ring", ItemID.SLAYER_RING_8),
		Map.entry("Teleport to House tablet", ItemID.POH_TABLET_TELEPORTTOHOUSE),
		Map.entry("Varrock tablet", ItemID.POH_TABLET_VARROCKTELEPORT),
		Map.entry("Watchtower tablet", ItemID.POH_TABLET_WATCHTOWERTELEPORT),
		Map.entry("Xeric's talisman", ItemID.XERIC_TALISMAN));
	/** "runes": true carries this many of every standard-book rune. */
	private static final int RUNE_STACK = 1000;
	private static final List<Integer> RUNES = List.of(ItemID.AIRRUNE, ItemID.WATERRUNE, ItemID.EARTHRUNE, ItemID.FIRERUNE,
		ItemID.MINDRUNE, ItemID.BODYRUNE, ItemID.COSMICRUNE, ItemID.CHAOSRUNE, ItemID.NATURERUNE, ItemID.LAWRUNE,
		ItemID.DEATHRUNE, ItemID.ASTRALRUNE, ItemID.BLOODRUNE, ItemID.SOULRUNE, ItemID.WRATHRUNE);
	private static final Map<String, Integer> ACCOUNT_TYPES = Map.of("mid-ironman", 1, "uim", 2, "hcim", 3, "gim", 4);
	private static final int MAX_QUEST_POINTS = 300;

	private Loadouts()
	{
	}

	@Value
	public static class Loadout
	{
		String name;
		PlayerSnapshot carried;
		/** Item ids the fixture banks ("*" in the fixture means everything: empty here, B15 decides). */
		List<Integer> banked;
		/** The fixture as written, for the planner settings it carries (types, tiers, avoidWilderness, poh). */
		JsonObject fixture;
	}

	public static Loadout load(String name)
	{
		JsonObject f = read(name);
		Map<Integer, Integer> inventory = new HashMap<>();
		for (JsonElement item : array(f, "carried"))
		{
			inventory.merge(itemId(item.getAsString()), 1, Integer::sum);
		}
		if (f.get("runes").getAsBoolean())
		{
			RUNES.forEach(rune -> inventory.put(rune, RUNE_STACK));
		}
		List<Integer> banked = new ArrayList<>();
		array(f, "banked").forEach(item -> banked.add(itemId(item.getAsString())));

		Map<Skill, Integer> levels = levels(f.getAsJsonObject("skills"));
		Map<Quest, QuestState> quests = quests(f.get("quests"));
		JsonObject vars = f.getAsJsonObject("vars");
		Map<Integer, Integer> values = new HashMap<>();
		vars.getAsJsonObject("values").entrySet().forEach(e -> values.put(Integer.parseInt(e.getKey()), e.getValue().getAsInt()));
		boolean varsPass = vars.get("default").getAsString().equals("pass");
		boolean allQuests = f.get("quests").isJsonPrimitive();
		PlayerSnapshot snapshot = PlayerSnapshot.builder()
			.version(1)
			.membersWorld(f.get("members").getAsBoolean())
			.accountType(ACCOUNT_TYPES.getOrDefault(name, 0))
			.inventory(inventory)
			.weapon(-1)
			.boostedLevels(levels)
			.realLevels(levels)
			.totalLevel(f.get("maxTotal").getAsBoolean() ? 99 * Skill.values().length
				: levels.values().stream().mapToInt(Integer::intValue).sum())
			.combatLevel(combatLevel(levels))
			.questPoints(allQuests ? MAX_QUEST_POINTS : 0)
			.questsRead(true)
			.quests(quests)
			.varbits(values)
			.varps(values)
			.unknownVars(varsPass ? PlayerSnapshot.UnknownVars.PASS : PlayerSnapshot.UnknownVars.FAIL)
			.spellbook(new SpellbookState(Book.valueOf(f.get("book").getAsString().toUpperCase()), false,
				SpellbookState.CAPE_CHANGES_PER_DAY, varsPass || values.getOrDefault(VarbitID.ARCEUUS_SPELLBOOK_UNLOCKED, 0) == 1,
				quests.get(Quest.DESERT_TREASURE_I) == QuestState.FINISHED, quests.get(Quest.LUNAR_DIPLOMACY) == QuestState.FINISHED,
				quests.get(Quest.DREAM_MENTOR) == QuestState.FINISHED))
			.build();
		return new Loadout(name, snapshot, List.copyOf(banked), f);
	}

	private static int itemId(String family)
	{
		Integer id = ITEMS.get(family);
		if (id == null)
		{
			throw new IllegalArgumentException("no item id for fixture item '" + family + "'");
		}
		return id;
	}

	private static Map<Skill, Integer> levels(JsonObject skills)
	{
		Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
		for (Skill skill : Skill.values())
		{
			JsonElement own = skills.get(skill.getName());
			levels.put(skill, (own != null ? own : skills.get("default")).getAsInt());
		}
		return levels;
	}

	private static Map<Quest, QuestState> quests(JsonElement spec)
	{
		Map<Quest, QuestState> quests = new EnumMap<>(Quest.class);
		for (Quest quest : Quest.values())
		{
			quests.put(quest, spec.isJsonPrimitive() ? QuestState.FINISHED : QuestState.NOT_STARTED);
		}
		if (spec.isJsonArray())
		{
			Map<String, Quest> byName = new HashMap<>();
			for (Quest quest : Quest.values())
			{
				byName.put(quest.getName(), quest);
			}
			for (JsonElement name : spec.getAsJsonArray())
			{
				Quest quest = byName.get(name.getAsString());
				if (quest == null)
				{
					throw new IllegalArgumentException("no quest named '" + name.getAsString() + "'");
				}
				quests.put(quest, QuestState.FINISHED);
			}
		}
		return quests;
	}

	/** The game's formula. */
	private static int combatLevel(Map<Skill, Integer> l)
	{
		double base = 0.25 * (l.get(Skill.DEFENCE) + l.get(Skill.HITPOINTS) + Math.floor(l.get(Skill.PRAYER) / 2.0));
		double melee = 0.325 * (l.get(Skill.ATTACK) + l.get(Skill.STRENGTH));
		double range = 0.325 * Math.floor(3 * l.get(Skill.RANGED) / 2.0);
		double mage = 0.325 * Math.floor(3 * l.get(Skill.MAGIC) / 2.0);
		return (int) Math.floor(base + Math.max(melee, Math.max(range, mage)));
	}

	private static Iterable<JsonElement> array(JsonObject f, String key)
	{
		JsonElement value = f.get(key);
		return value != null && value.isJsonArray() ? value.getAsJsonArray() : List.of();
	}

	private static JsonObject read(String name)
	{
		try (Reader in = new InputStreamReader(Loadouts.class.getResourceAsStream("/loadouts/" + name + ".json"), StandardCharsets.UTF_8))
		{
			return new JsonParser().parse(in).getAsJsonObject();
		}
		catch (Exception e)
		{
			throw new IllegalStateException("fixture " + name, e);
		}
	}
}
