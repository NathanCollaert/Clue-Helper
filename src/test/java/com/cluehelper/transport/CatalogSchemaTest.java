package com.cluehelper.transport;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class CatalogSchemaTest
{
	private static final Map<String, Class<?>> GAMEVAL = Map.of("item", ItemID.class, "object", ObjectID.class,
		"varbit", VarbitID.class, "varp", VarPlayerID.class, "npc", NpcID.class);
	private static final Pattern NAMED_ID = Pattern.compile("(\\w+):(\\d+)=(\\w+)");
	private static final Pattern ITEM_ID = Pattern.compile("(?<![A-Z_])(\\d+)=\\d+");
	private static final Pattern VAR_ID = Pattern.compile("(\\d+)\\s*[=<>&@]");
	private static final Pattern SPELLBOOK_CLAUSE = Pattern.compile("(^|;)\\s*4070\\s*=");
	private static final Pattern WHERE_ID = Pattern.compile("(object|npc):(\\d+)");
	private static final Set<String> BOOKS = Set.of("Standard", "Ancient", "Lunar", "Arceuus");
	private static final int MAX_COORD = (1 << 14) - 1;
	private static final int MIN_TELEPORT_ROWS = 700;

	@Test
	public void everyRowPassesTheSchema()
	{
		List<String> failures = new ArrayList<>();
		for (String file : CatalogLoader.TELEPORT_FILES)
		{
			for (TsvTable.Line line : CatalogLoader.read(file))
			{
				teleportRules(line, failures::add, file + ":" + line.getNumber() + " " + line.get(CatalogLoader.NAME) + ": ");
			}
		}
		for (TsvTable.Line line : CatalogLoader.read(CatalogLoader.SPELLBOOK_FILE))
		{
			sharedRules(line, failures::add, CatalogLoader.SPELLBOOK_FILE + ":" + line.getNumber() + " " + line.get(CatalogLoader.NAME) + ": ");
		}

		Catalog catalog = CatalogLoader.load();
		catalog.getTeleports().forEach(row -> row.getRequirements().getErrors().forEach(e -> failures.add(row.getName() + ": " + e)));
		catalog.getSpellbookChanges().forEach(row -> row.getRequirements().getErrors().forEach(e -> failures.add(row.getName() + ": " + e)));

		assertEquals("", String.join("\n", failures));
		assertTrue(catalog.getTeleports().size() > MIN_TELEPORT_ROWS);
	}

	private static void teleportRules(TsvTable.Line line, Failures fail, String at)
	{
		sharedRules(line, fail, at);
		Category category = Category.of(line.get(CatalogLoader.CATEGORY));
		if (SPELLBOOK_CLAUSE.matcher(line.get(RequirementParser.VARBITS)).find())
		{
			fail.add(at + "4070 left in Varbits");
		}
		if (line.get(RequirementParser.CONSUMABLE).equals("T") && line.get(CatalogLoader.SPENDS).equals("none"))
		{
			fail.add(at + "consumable without Spends");
		}
		if (category != Category.FAIRY_RING && line.get(RequirementParser.WILDERNESS).isEmpty())
		{
			fail.add(at + "teleport without a Wilderness level");
		}
		boolean bookRow = category == Category.SPELL || category == Category.HOME;
		String book = line.get(RequirementParser.BOOK);
		if (bookRow ? !BOOKS.contains(book) : !book.equals("any"))
		{
			fail.add(at + "Book '" + book + "' for a " + category + " row");
		}
		validTile(line.get(CatalogLoader.ORIGIN), fail, at);
		validTile(line.get(CatalogLoader.DESTINATION), fail, at);
		if (line.get(CatalogLoader.ORIGIN).isEmpty() && line.get(CatalogLoader.DESTINATION).isEmpty())
		{
			fail.add(at + "neither origin nor destination");
		}
	}

	private static void sharedRules(TsvTable.Line line, Failures fail, String at)
	{
		Map<String, Integer> named = new HashMap<>();
		Matcher m = NAMED_ID.matcher(line.get("Gameval"));
		while (m.find())
		{
			int id = Integer.parseInt(m.group(2));
			named.put(m.group(1) + ":" + id, id);
			Integer actual = gameval(m.group(1), m.group(3));
			if (actual == null || actual != id)
			{
				fail.add(at + m.group(0) + " but the gameval constant is " + actual);
			}
		}
		for (String ref : referencedIds(line))
		{
			if (!named.containsKey(ref))
			{
				fail.add(at + ref + " has no gameval name beside it");
			}
			if (ref.startsWith("item:") && CatalogRules.UNCHARGED.contains(Integer.parseInt(ref.substring(5))))
			{
				fail.add(at + "uncharged " + ref);
			}
		}
		String members = line.get(RequirementParser.MEMBERS);
		if (!members.equals("Y") && !members.equals("N"))
		{
			fail.add(at + "Members '" + members + "'");
		}
		if (line.get(CatalogLoader.SPENDS).isEmpty() || line.get(CatalogLoader.DURATION).isEmpty()
			|| line.get(CatalogLoader.MENU_TICKS).isEmpty())
		{
			fail.add(at + "Spends, Duration or Menu ticks empty");
		}
	}

	private static List<String> referencedIds(TsvTable.Line line)
	{
		List<String> refs = new ArrayList<>();
		collect(ITEM_ID, line.get(RequirementParser.ITEMS), "item:", refs);
		collect(VAR_ID, line.get(RequirementParser.VARBITS), "varbit:", refs);
		collect(VAR_ID, line.get(RequirementParser.VARPS), "varp:", refs);
		String menu = line.get("menuOption menuTarget objectID");
		if (!menu.isEmpty())
		{
			String kind = line.get(CatalogLoader.MENU).startsWith("npc ") ? "npc:" : "object:";
			refs.add(kind + menu.substring(menu.lastIndexOf(' ') + 1));
		}
		String house = line.get(RequirementParser.HOUSE);
		if (house.startsWith("object:"))
		{
			for (String id : house.substring("object:".length()).split("\\|"))
			{
				refs.add("object:" + id);
			}
		}
		Matcher where = WHERE_ID.matcher(line.get(CatalogLoader.WHERE));
		while (where.find())
		{
			refs.add(where.group(1) + ":" + where.group(2));
		}
		return refs;
	}

	private static void collect(Pattern pattern, String cell, String prefix, List<String> refs)
	{
		Matcher m = pattern.matcher(cell);
		while (m.find())
		{
			refs.add(prefix + m.group(1));
		}
	}

	private static Integer gameval(String kind, String name)
	{
		Class<?> constants = GAMEVAL.get(kind);
		try
		{
			Field field = constants.getField(name);
			field.setAccessible(true);
			return field.getInt(null);
		}
		catch (ReflectiveOperationException | NullPointerException e)
		{
			return null;
		}
	}

	private static void validTile(String cell, Failures fail, String at)
	{
		if (cell.isEmpty())
		{
			return;
		}
		try
		{
			WorldPoint tile = CatalogLoader.tile(cell);
			boolean valid = tile.getX() > 0 && tile.getX() <= MAX_COORD && tile.getY() > 0 && tile.getY() <= MAX_COORD
				&& tile.getPlane() >= 0 && tile.getPlane() <= 3;
			if (!valid)
			{
				fail.add(at + "tile out of range: " + cell);
			}
		}
		catch (RuntimeException e)
		{
			fail.add(at + e.getMessage());
		}
	}

	private interface Failures
	{
		void add(String failure);
	}
}
