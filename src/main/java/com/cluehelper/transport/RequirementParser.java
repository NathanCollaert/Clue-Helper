package com.cluehelper.transport;

import com.cluehelper.player.Book;
import java.util.*;
import java.util.regex.*;
import net.runelite.api.*;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

/** Unlike Shortest Path, nothing that fails to parse is dropped: it becomes an error on the row. */
public final class RequirementParser
{
	public static final String ITEMS = "Items";
	public static final String SKILLS = "Skills";
	public static final String QUESTS = "Quests";
	public static final String VARBITS = "Varbits";
	public static final String VARPS = "VarPlayers";
	public static final String WILDERNESS = "Wilderness level";
	public static final String BOOK = "Book";
	public static final String MEMBERS = "Members";
	public static final String CONSUMABLE = "Consumable";
	public static final String HOUSE = "House";
	private static final String PLANTED = "Planted";

	private static final int MAX_SKILL_LEVEL = 99;
	private static final int MAX_COMBAT_LEVEL = 126;
	private static final String MAX = "Max";
	private static final String UNLOCK_PREFIX = "UNLOCK_";
	private static final Pattern VAR_CLAUSE = Pattern.compile("(\\d+)\\s*([=<>&@])\\s*(-?\\d+)");
	private static final Pattern HOUSE_NEED = Pattern.compile("(object|nexus):(\\d+(?:\\|\\d+)*)");
	private static final Pattern ITEM_BRANCH = Pattern.compile("([A-Z0-9_]+)=(\\d+)");
	private static final Map<String, Integer> RUNE_NAMES = runeNames();
	private static final Map<String, Quest> QUESTS_BY_NAME = questsByName();

	public Requirements parse(Map<String, String> cells)
	{
		Parsed out = new Parsed();
		parseItems(cell(cells, ITEMS), out);
		parseLevels(cell(cells, SKILLS), out);
		parseQuests(cell(cells, QUESTS), out);
		Book varBook = parseVars(cell(cells, VARBITS), VarCheck.Kind.VARBIT, out);
		parseVars(cell(cells, VARPS), VarCheck.Kind.VARP, out);
		parseWilderness(cell(cells, WILDERNESS), out);
		HouseNeed house = parseHouse(cell(cells, HOUSE), out);
		Book columnBook = parseBook(cell(cells, BOOK), out);
		if (varBook != null && columnBook != null && varBook != columnBook)
		{
			out.errors.add("Book " + columnBook + " disagrees with varbit 4070 = " + varBook.ordinal());
		}
		return Requirements.builder()
			.items(List.copyOf(out.items))
			.levels(List.copyOf(out.levels))
			.quests(Set.copyOf(out.quests))
			.vars(List.copyOf(out.vars))
			.maxWildernessLevel(out.maxWildernessLevel)
			.book(columnBook != null ? columnBook : varBook)
			.members(isTrue(cell(cells, MEMBERS)))
			.spendsCharges(isTrue(cell(cells, CONSUMABLE)))
			.house(house)
			.plantedTrees(parsePlanted(cell(cells, PLANTED)))
			.errors(List.copyOf(out.errors))
			.build();
	}

	private static Set<String> parsePlanted(String cell)
	{
		return cell.isEmpty() ? Set.of() : Set.of(cell.trim().split("\\s*;\\s*"));
	}

	private static HouseNeed parseHouse(String cell, Parsed out)
	{
		if (cell.isEmpty())
		{
			return null;
		}
		Matcher m = HOUSE_NEED.matcher(cell.trim());
		if (!m.matches())
		{
			out.errors.add("House: can't read '" + cell + "'");
			return null;
		}
		List<Integer> ids = new ArrayList<>();
		for (String id : m.group(2).split("\\|"))
		{
			ids.add(Integer.parseInt(id));
		}
		HouseNeed.Kind kind = m.group(1).equals("nexus") ? HouseNeed.Kind.NEXUS : HouseNeed.Kind.FURNITURE;
		if (kind == HouseNeed.Kind.NEXUS && ids.size() != 1)
		{
			out.errors.add("House: one nexus destination per row, not '" + cell + "'");
			return null;
		}
		return new HouseNeed(kind, List.copyOf(ids));
	}

	private void parseItems(String cell, Parsed out)
	{
		if (cell.isEmpty())
		{
			return;
		}
		String normalised = cell.replace(" ", "").replace("&&", "&").replace("||", "|").toUpperCase(Locale.ROOT);
		for (String clause : normalised.split("&", -1))
		{
			List<ItemClause.Branch> branches = new ArrayList<>();
			for (String branch : clause.split("\\|", -1))
			{
				ItemClause.Branch parsed = parseBranch(branch, out);
				if (parsed != null)
				{
					branches.add(parsed);
				}
			}
			if (!branches.isEmpty())
			{
				out.items.add(new ItemClause(branches, false, null));
			}
		}
	}

	private ItemClause.Branch parseBranch(String branch, Parsed out)
	{
		Matcher m = ITEM_BRANCH.matcher(branch);
		if (!m.matches())
		{
			out.errors.add("Items: can't read '" + branch + "'");
			return null;
		}
		String name = m.group(1);
		int quantity = Integer.parseInt(m.group(2));
		if (quantity < 1)
		{
			out.errors.add("Items: '" + branch + "' needs a positive quantity");
			return null;
		}
		if (name.startsWith(UNLOCK_PREFIX))
		{
			return new ItemClause.Branch(Collections.emptyList(), quantity, name.substring(UNLOCK_PREFIX.length()));
		}
		List<Integer> ids = itemIds(name);
		if (ids == null || ids.isEmpty())
		{
			out.errors.add("Items: unknown item '" + name + "'");
			return null;
		}
		return new ItemClause.Branch(ids, quantity, null);
	}

	private List<Integer> itemIds(String name)
	{
		if (name.chars().allMatch(Character::isDigit))
		{
			return Collections.singletonList(Integer.parseInt(name));
		}
		Integer rune = RUNE_NAMES.get(name);
		return rune != null ? Collections.singletonList(rune) : null;
	}

	private static void parseLevels(String cell, Parsed out)
	{
		for (String clause : cell.split(";"))
		{
			if (clause.trim().isEmpty())
			{
				continue;
			}
			String[] parts = clause.trim().split(" ", 2);
			LevelNeed need = parts.length == 2 ? levelNeed(parts[0], parts[1].trim()) : null;
			if (need == null)
			{
				out.errors.add("Skills: can't read '" + clause.trim() + "'");
			}
			else
			{
				out.levels.add(need);
			}
		}
	}

	private static LevelNeed levelNeed(String level, String name)
	{
		boolean max = MAX.equals(level);
		if (!max && !level.chars().allMatch(Character::isDigit))
		{
			return null;
		}
		int required = max ? 0 : Integer.parseInt(level);
		for (Skill skill : Skill.values())
		{
			if (skill.getName().equalsIgnoreCase(name))
			{
				return LevelNeed.ofSkill(skill, max ? MAX_SKILL_LEVEL : required);
			}
		}
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.startsWith("total"))
		{
			return LevelNeed.ofAggregate(LevelNeed.Aggregate.TOTAL, max ? MAX_SKILL_LEVEL * Skill.values().length : required);
		}
		if (lower.startsWith("combat"))
		{
			return LevelNeed.ofAggregate(LevelNeed.Aggregate.COMBAT, max ? MAX_COMBAT_LEVEL : required);
		}
		if (lower.startsWith("quest") && !max)
		{
			return LevelNeed.ofAggregate(LevelNeed.Aggregate.QUEST_POINTS, required);
		}
		return null;
	}

	private static void parseQuests(String cell, Parsed out)
	{
		for (String name : cell.split(";"))
		{
			if (name.trim().isEmpty())
			{
				continue;
			}
			Quest quest = QUESTS_BY_NAME.get(name.trim());
			if (quest == null)
			{
				out.errors.add("Quests: unknown quest '" + name.trim() + "'");
			}
			else
			{
				out.quests.add(quest);
			}
		}
	}

	/** @return the book of a {@code 4070=n} clause, which becomes the row's book rather than a check */
	private static Book parseVars(String cell, VarCheck.Kind kind, Parsed out)
	{
		Book book = null;
		for (String clause : cell.split(";"))
		{
			if (clause.trim().isEmpty())
			{
				continue;
			}
			Matcher m = VAR_CLAUSE.matcher(clause.trim());
			if (!m.matches())
			{
				out.errors.add((kind == VarCheck.Kind.VARBIT ? VARBITS : VARPS) + ": can't read '" + clause.trim() + "'");
				continue;
			}
			VarCheck check = new VarCheck(kind, Integer.parseInt(m.group(1)), VarCheck.Op.of(m.group(2).charAt(0)),
				Integer.parseInt(m.group(3)));
			if (isBookClause(check))
			{
				book = Book.of(check.getValue());
			}
			else
			{
				out.vars.add(check);
			}
		}
		return book;
	}

	private static boolean isBookClause(VarCheck check)
	{
		return check.getKind() == VarCheck.Kind.VARBIT && check.getId() == VarbitID.SPELLBOOK
			&& check.getOp() == VarCheck.Op.EQUAL && Book.of(check.getValue()) != null;
	}

	private static void parseWilderness(String cell, Parsed out)
	{
		if (cell.isEmpty())
		{
			return;
		}
		if (cell.matches("-?\\d+"))
		{
			int level = Integer.parseInt(cell);
			out.maxWildernessLevel = level < 0 ? Requirements.NO_WILDERNESS_LIMIT : level;
		}
		else
		{
			out.errors.add("Wilderness level: can't read '" + cell + "'");
		}
	}

	private static Book parseBook(String cell, Parsed out)
	{
		if (cell.isEmpty() || cell.equalsIgnoreCase("any"))
		{
			return null;
		}
		try
		{
			return Book.valueOf(cell.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e)
		{
			out.errors.add("Book: unknown book '" + cell + "'");
			return null;
		}
	}

	private static final class Parsed
	{
		final List<ItemClause> items = new ArrayList<>();
		final List<LevelNeed> levels = new ArrayList<>();
		final Set<Quest> quests = new HashSet<>();
		final List<VarCheck> vars = new ArrayList<>();
		final List<String> errors = new ArrayList<>();
		int maxWildernessLevel = Requirements.NO_WILDERNESS_LIMIT;
	}

	private static boolean isTrue(String cell)
	{
		return cell.equalsIgnoreCase("T") || cell.equalsIgnoreCase("Y") || cell.equalsIgnoreCase("true");
	}

	private static String cell(Map<String, String> cells, String column)
	{
		String value = cells.get(column);
		return value == null ? "" : value.trim();
	}

	private static Map<String, Integer> runeNames()
	{
		Map<String, Integer> runes = new HashMap<>();
		runes.put("AIR_RUNE", ItemID.AIRRUNE);
		runes.put("WATER_RUNE", ItemID.WATERRUNE);
		runes.put("EARTH_RUNE", ItemID.EARTHRUNE);
		runes.put("FIRE_RUNE", ItemID.FIRERUNE);
		runes.put("MIND_RUNE", ItemID.MINDRUNE);
		runes.put("BODY_RUNE", ItemID.BODYRUNE);
		runes.put("COSMIC_RUNE", ItemID.COSMICRUNE);
		runes.put("CHAOS_RUNE", ItemID.CHAOSRUNE);
		runes.put("NATURE_RUNE", ItemID.NATURERUNE);
		runes.put("LAW_RUNE", ItemID.LAWRUNE);
		runes.put("DEATH_RUNE", ItemID.DEATHRUNE);
		runes.put("ASTRAL_RUNE", ItemID.ASTRALRUNE);
		runes.put("BLOOD_RUNE", ItemID.BLOODRUNE);
		runes.put("SOUL_RUNE", ItemID.SOULRUNE);
		runes.put("WRATH_RUNE", ItemID.WRATHRUNE);
		runes.put("SUNFIRE_RUNE", ItemID.SUNFIRERUNE);
		runes.put("AETHER_RUNE", ItemID.AETHERRUNE);
		runes.put("MIST_RUNE", ItemID.MISTRUNE);
		runes.put("DUST_RUNE", ItemID.DUSTRUNE);
		runes.put("MUD_RUNE", ItemID.MUDRUNE);
		runes.put("SMOKE_RUNE", ItemID.SMOKERUNE);
		runes.put("STEAM_RUNE", ItemID.STEAMRUNE);
		runes.put("LAVA_RUNE", ItemID.LAVARUNE);
		return Collections.unmodifiableMap(runes);
	}

	private static Map<String, Quest> questsByName()
	{
		Map<String, Quest> quests = new HashMap<>();
		for (Quest quest : Quest.values())
		{
			quests.put(quest.getName(), quest);
		}
		return Collections.unmodifiableMap(quests);
	}
}
