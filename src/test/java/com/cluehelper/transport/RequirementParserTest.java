package com.cluehelper.transport;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Test;

public class RequirementParserTest
{
	private static final String[][] CASES = {
		{"Items", "AIR_RUNE=3&&FIRE_RUNE=1&&LAW_RUNE=1", "items 556x3 & 554x1 & 563x1"},
		{"Items", "1706=1||1708=1||1710=1", "items 1706x1|1708x1|1710x1"},
		{"Items", "13393=1&UNLOCK_XERICS_HONOUR=1", "items 13393x1 & unlock XERICS_HONOUR"},
		{"Items", "aether_rune=2 & sunfire_rune=1", "items 30843x2 & 28929x1"},
		{"Items", "SPADE=1", "error Items: unknown item 'SPADE'"},
		{"Items", "AIR_RUNE", "error Items: can't read 'AIR_RUNE'"},
		{"Items", "AIR_RUNE=0", "error Items: 'AIR_RUNE=0' needs a positive quantity"},
		{"Skills", "25 Magic;50 Agility", "levels 25 Magic; 50 Agility"},
		{"Skills", "Max Total", "levels 2376 total level"},
		{"Skills", "Max Construction;100 Combat;32 Quest", "levels 99 Construction; combat level 100; 32 quest points"},
		{"Skills", "70 Agilty", "error Skills: can't read '70 Agilty'"},
		{"Skills", "high Magic", "error Skills: can't read 'high Magic'"},
		{"Quests", "Lunar Diplomacy;Fairytale II - Cure a Queen", "quests Fairytale II - Cure a Queen; Lunar Diplomacy"},
		{"Quests", "Dragon Slayer", "error Quests: unknown quest 'Dragon Slayer'"},
		{"Varbits", "4070=2", "book LUNAR"},
		{"Varbits", "4070=0;4480=1", "book STANDARD; vars varbit 4480 = 1"},
		{"Varbits", "4070>1", "vars varbit 4070 > 1"},
		{"Varbits", "4560&1;3264=11", "vars varbit 4560 & 1; varbit 3264 = 11"},
		{"Varbits", "6104<-1", "vars varbit 6104 < -1"},
		{"Varbits", "1234~5", "error Varbits: can't read '1234~5'"},
		{"VarPlayers", "4560=0;892@30", "vars varp 4560 = 0; varp 892 @ 30"},
		{"Wilderness level", "20", "wild 20"},
		{"Wilderness level", "-1", ""},
		{"Wilderness level", "deep", "error Wilderness level: can't read 'deep'"},
		{"Book", "Arceuus", "book ARCEUUS"},
		{"Members", "T", "members"},
		{"Consumable", "T", "spends charges"},
	};

	private final RequirementParser parser = new RequirementParser();

	@Test
	public void parsesEveryCaseOfTheShortestPathSyntax()
	{
		List<String> failures = new ArrayList<>();
		for (String[] c : CASES)
		{
			String column = c[0];
			String cell = c[1];
			String expected = c[2];
			String actual = describe(parser.parse(Map.of(column, cell)));
			if (!actual.equals(expected))
			{
				failures.add(column + " '" + cell + "': expected '" + expected + "', got '" + actual + "'");
			}
		}
		assertEquals("", String.join("\n", failures));
	}

	@Test
	public void aBookColumnThatDisagreesWithVarbit4070IsAnError()
	{
		Requirements r = parser.parse(Map.of("Book", "Lunar", "Varbits", "4070=0"));
		assertEquals(List.of("Book LUNAR disagrees with varbit 4070 = 0"), r.getErrors());
	}

	@Test
	public void varChecksPassAtTheirBoundaries()
	{
		VarCheck cooldown = new VarCheck(VarCheck.Kind.VARP, 892, VarCheck.Op.COOLDOWN_MINUTES, 30);
		assertEquals(false, cooldown.test(1000, 1030));
		assertEquals(true, cooldown.test(1000, 1031));
		assertEquals(1031, cooldown.passesFrom(1000));
		assertEquals(Long.MAX_VALUE, new VarCheck(VarCheck.Kind.VARBIT, 1, VarCheck.Op.EQUAL, 1).passesFrom(0));

		assertEquals(true, new VarCheck(VarCheck.Kind.VARBIT, 1, VarCheck.Op.GREATER, 39).test(40, 0));
		assertEquals(false, new VarCheck(VarCheck.Kind.VARBIT, 1, VarCheck.Op.GREATER, 39).test(39, 0));
		assertEquals(true, new VarCheck(VarCheck.Kind.VARBIT, 1, VarCheck.Op.SMALLER, 5).test(4, 0));
		assertEquals(false, new VarCheck(VarCheck.Kind.VARBIT, 1, VarCheck.Op.SMALLER, 5).test(5, 0));
		assertEquals(true, new VarCheck(VarCheck.Kind.VARP, 4182, VarCheck.Op.BIT_SET, 4).test(6, 0));
		assertEquals(false, new VarCheck(VarCheck.Kind.VARP, 4182, VarCheck.Op.BIT_SET, 4).test(3, 0));
	}

	private static String describe(Requirements r)
	{
		List<String> parts = new ArrayList<>();
		if (!r.getItems().isEmpty())
		{
			parts.add("items " + r.getItems().stream().map(RequirementParserTest::clause).collect(Collectors.joining(" & ")));
		}
		if (!r.getLevels().isEmpty())
		{
			parts.add("levels " + r.getLevels().stream().map(LevelNeed::describe).collect(Collectors.joining("; ")));
		}
		if (!r.getQuests().isEmpty())
		{
			parts.add("quests " + r.getQuests().stream().map(q -> q.getName()).sorted().collect(Collectors.joining("; ")));
		}
		if (r.getBook() != null)
		{
			parts.add("book " + r.getBook());
		}
		if (!r.getVars().isEmpty())
		{
			parts.add("vars " + r.getVars().stream().map(VarCheck::describe).collect(Collectors.joining("; ")));
		}
		if (r.getMaxWildernessLevel() != Requirements.NO_WILDERNESS_LIMIT)
		{
			parts.add("wild " + r.getMaxWildernessLevel());
		}
		if (r.isMembers())
		{
			parts.add("members");
		}
		if (r.isSpendsCharges())
		{
			parts.add("spends charges");
		}
		r.getErrors().forEach(error -> parts.add("error " + error));
		return String.join("; ", parts);
	}

	private static String clause(ItemClause clause)
	{
		return clause.getBranches().stream()
			.map(b -> b.isUnlock() ? "unlock " + b.getUnlock() : b.getIds().stream().map(String::valueOf)
				.collect(Collectors.joining("/")) + "x" + b.getQuantity())
			.collect(Collectors.joining("|"));
	}
}
