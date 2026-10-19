package com.cluehelper.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * The rune model read from B01's cache dump (cut by {@code tools/spellbook/s2_test_slice.py}): it must
 * agree with B04's spell table, and light exactly the Lunar spells the spellbook widget lit in B04's
 * open-tab frames (run 2, t532 and t564).
 */
public class RuneModelTest
{
	private final RuneModel model = RuneModel.load(new SliceCacheView("/b08/cache-runes.json"));

	@Test
	public void everySpellAgreesWithTheCacheSpellTable() throws Exception
	{
		List<String> expected = new ArrayList<>();
		try (BufferedReader in = reader("/b08/spells.tsv"))
		{
			in.readLine();
			String line;
			while ((line = in.readLine()) != null)
			{
				String[] c = line.split("\t", -1);
				expected.add(String.join(" | ", c[0].toUpperCase(), c[1], c[2], c[3], c[4], c[6]));
			}
		}
		List<String> ours = model.getSpells().stream().map(s -> String.join(" | ", s.getBook().name(), s.getName(),
			String.valueOf(s.getLevel()), s.isMembers() ? "1" : "0", s.getQuest() == null ? "" : String.valueOf(s.getQuest().getId()),
			s.getRunes().stream().map(r -> r.getRune() + "x" + r.getQuantity()).collect(Collectors.joining(";"))))
			.collect(Collectors.toList());
		assertEquals(String.join("\n", expected), String.join("\n", ours));
	}

	@Test
	public void lightsTheLunarSpellsTheWidgetLitWithTheTabOpen()
	{
		PlayerSnapshot t532 = nathanOnLunar(Map.of(ItemID.DEATHRUNE, 9944, ItemID.AETHERRUNE, 9814, ItemID.BLOODRUNE, 9875,
			ItemID.FIRERUNE, 8700));
		Set<String> noRunes = lunarCastable(t532);
		assertEquals(Set.of("Lunar Home Teleport", "Minigame Teleport"), noRunes);

		PlayerSnapshot t564 = nathanOnLunar(Map.of(ItemID.ASTRALRUNE, 4679, ItemID.COSMICRUNE, 16000, ItemID.LAWRUNE, 15884));
		Set<String> expected = new TreeSet<>(noRunes);
		expected.addAll(List.of("Cure Me", "Cure Group", "Spellbook Swap"));
		assertEquals(expected, lunarCastable(t564));
	}

	private Set<String> lunarCastable(PlayerSnapshot player)
	{
		return model.getSpells().stream()
			.filter(spell -> spell.getBook() == Book.LUNAR && model.whyNotCastable(spell, player).isEmpty())
			.map(Spell::getName).collect(Collectors.toCollection(TreeSet::new));
	}

	/** Run 2 after "Deposit inventory" and withdrawing the divine pouch: no staff, 99 Magic, every book unlocked. */
	private static PlayerSnapshot nathanOnLunar(Map<Integer, Integer> pouch)
	{
		Map<Quest, QuestState> quests = new EnumMap<>(Quest.class);
		for (Quest quest : Quest.values())
		{
			quests.put(quest, QuestState.FINISHED);
		}
		Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
		for (Skill skill : Skill.values())
		{
			levels.put(skill, 99);
		}
		return PlayerSnapshot.builder()
			.membersWorld(true)
			.inventory(Map.of(ItemID.DIVINE_RUNE_POUCH, 1))
			.pouchHeld(true)
			.pouch(pouch)
			.boostedLevels(levels)
			.realLevels(levels)
			.questsRead(true)
			.quests(quests)
			.build();
	}

	private static BufferedReader reader(String resource)
	{
		return new BufferedReader(new InputStreamReader(RuneModelTest.class.getResourceAsStream(resource), StandardCharsets.UTF_8));
	}

	/** The cut of the cache dump, in the shape the live client answers. */
	static final class SliceCacheView implements CacheView
	{
		private final JsonObject slice;

		SliceCacheView(String resource)
		{
			try (BufferedReader in = reader(resource))
			{
				slice = new JsonParser().parse(in).getAsJsonObject();
			}
			catch (Exception e)
			{
				throw new IllegalStateException(e);
			}
		}

		@Override
		public int[] enumKeys(int enumId)
		{
			return ints(enumId, "keys");
		}

		@Override
		public int[] enumIntValues(int enumId)
		{
			return ints(enumId, "intVals");
		}

		@Override
		public int itemIntParam(int itemId, int param, int absent)
		{
			JsonElement value = params(itemId).get(String.valueOf(param));
			return value == null ? absent : value.getAsInt();
		}

		@Override
		public String itemStringParam(int itemId, int param)
		{
			JsonElement value = params(itemId).get(String.valueOf(param));
			return value == null ? null : value.getAsString();
		}

		@Override
		public String itemName(int itemId)
		{
			return slice.getAsJsonObject("items").getAsJsonObject(String.valueOf(itemId)).get("name").getAsString();
		}

		@Override
		public List<int[]> multirunes()
		{
			List<int[]> rows = new ArrayList<>();
			for (JsonElement row : slice.getAsJsonArray("multirunes"))
			{
				rows.add(new int[]{row.getAsJsonArray().get(0).getAsInt(), row.getAsJsonArray().get(1).getAsInt()});
			}
			return rows;
		}

		private JsonObject params(int itemId)
		{
			JsonObject item = slice.getAsJsonObject("items").getAsJsonObject(String.valueOf(itemId));
			return item == null ? new JsonObject() : item.getAsJsonObject("params");
		}

		private int[] ints(int enumId, String field)
		{
			JsonObject e = slice.getAsJsonObject("enums").getAsJsonObject(String.valueOf(enumId));
			if (e == null)
			{
				return new int[0];
			}
			JsonArray values = e.getAsJsonArray(field);
			int[] out = new int[values.size()];
			for (int i = 0; i < out.length; i++)
			{
				out[i] = values.get(i).getAsInt();
			}
			return out;
		}
	}
}
