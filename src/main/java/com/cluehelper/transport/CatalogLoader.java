package com.cluehelper.transport;

import com.cluehelper.player.Book;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.coords.WorldPoint;

/**
 * A requirement that doesn't parse stays on its row as an error; a malformed tile or number fails the whole load,
 * which the schema test catches before release.
 */
public final class CatalogLoader
{
	static final List<String> TELEPORT_FILES = List.of("spells.tsv", "home_teleports.tsv", "minigames.tsv", "items.tsv",
		"fairy_rings.tsv", "poh.tsv", "spirit_trees.tsv", "quetzals.tsv");
	static final String SPELLBOOK_FILE = "spellbooks.tsv";

	static final String CATEGORY = "Category";
	static final String NAME = "Display info";
	static final String ORIGIN = "Origin";
	static final String DESTINATION = "Destination";
	static final String CODE = "Code";
	static final String DURATION = "Duration";
	static final String MENU_TICKS = "Menu ticks";
	static final String SPENDS = "Spends";
	static final String KIND = "Kind";
	static final String WHERE = "Where";
	static final String FROM = "From";
	static final String TO = "To";
	static final String MENU = "Menu";
	static final String OBJECT = "menuOption menuTarget objectID";

	private static final String ANY = "any";
	private static final String ANYWHERE = "anywhere";
	private static final String IN_HOUSE = "poh";
	private static final String TEMPORARY = "temporary";
	private static final String TOGGLE = "toggle ";
	private static final Pattern WHERE_REF = Pattern.compile("(object|npc):(\\d+)");

	private CatalogLoader()
	{
	}

	public static Catalog load()
	{
		RequirementParser parser = new RequirementParser();
		List<CatalogRow> teleports = new ArrayList<>();
		for (String file : TELEPORT_FILES)
		{
			for (TsvTable.Line line : read(file))
			{
				teleports.add(at(file, line, () -> teleport(line, parser)));
			}
		}
		List<SpellbookChange> changes = new ArrayList<>();
		for (TsvTable.Line line : read(SPELLBOOK_FILE))
		{
			changes.add(at(SPELLBOOK_FILE, line, () -> spellbookChange(line, parser)));
		}
		return new Catalog(List.copyOf(teleports), List.copyOf(changes));
	}

	static List<TsvTable.Line> read(String file)
	{
		try (InputStream in = CatalogLoader.class.getResourceAsStream(file))
		{
			if (in == null)
			{
				throw new IllegalStateException(file + " is missing");
			}
			return TsvTable.read(in);
		}
		catch (IOException e)
		{
			throw new IllegalStateException("can't read " + file, e);
		}
	}

	private static CatalogRow teleport(TsvTable.Line line, RequirementParser parser)
	{
		String code = line.get(CODE);
		String menu = line.get(MENU);
		return CatalogRow.builder()
			.name(line.get(NAME))
			.category(Category.of(line.get(CATEGORY)))
			.origin(tile(line.get(ORIGIN)))
			.destination(tile(line.get(DESTINATION)))
			.code(code.isEmpty() ? null : code)
			.duration(Integer.parseInt(line.get(DURATION)))
			.menuTicks(Integer.parseInt(line.get(MENU_TICKS)))
			.spends(Spend.parse(line.get(SPENDS)))
			.requirements(CatalogRules.fairyRingStaff(parser.parse(line.getCells())))
			.menu(menu.isEmpty() ? null : menu)
			.objectId(objectId(line.get(OBJECT)))
			.build();
	}

	private static SpellbookChange spellbookChange(TsvTable.Line line, RequirementParser parser)
	{
		String where = line.get(WHERE);
		SpellbookChange.SpellbookChangeBuilder change = SpellbookChange.builder()
			.name(line.get(NAME))
			.temporary(TEMPORARY.equals(line.get(KIND)))
			.inHouse(where.startsWith(IN_HOUSE))
			.from(book(line.get(FROM)))
			.toggles(line.get(TO).startsWith(TOGGLE) ? book(line.get(TO).substring(TOGGLE.length())) : null)
			.duration(Integer.parseInt(line.get(DURATION)))
			.menuTicks(Integer.parseInt(line.get(MENU_TICKS)))
			.spends(Spend.parse(line.get(SPENDS)))
			.requirements(parser.parse(line.getCells()));
		if (!where.equals(ANYWHERE) && !where.startsWith(IN_HOUSE))
		{
			change.tile(tile(where.replaceAll("\\s*[a-z]+:\\d+", "")));
		}
		Matcher ref = WHERE_REF.matcher(where);
		while (ref.find())
		{
			int id = Integer.parseInt(ref.group(2));
			if (ref.group(1).equals("object"))
			{
				change.objectId(id);
			}
			else
			{
				change.npcId(id);
			}
		}
		SpellbookChange built = change.build();
		if (built.isInHouse() && built.getObjectId() != SpellbookChange.NO_ID)
		{
			HouseNeed piece = new HouseNeed(HouseNeed.Kind.FURNITURE, List.of(built.getObjectId()));
			return built.toBuilder().requirements(built.getRequirements().toBuilder().house(piece).build()).build();
		}
		return built;
	}

	/** The last word of "Travel Spirit tree 26261"; -1 for an empty cell. */
	private static int objectId(String cell)
	{
		return cell.isEmpty() ? -1 : Integer.parseInt(cell.substring(cell.lastIndexOf(' ') + 1));
	}

	private static Book book(String cell)
	{
		return cell.isEmpty() || cell.equals(ANY) ? null : Book.valueOf(cell.trim().toUpperCase(Locale.ROOT));
	}

	static WorldPoint tile(String cell)
	{
		if (cell.isEmpty())
		{
			return null;
		}
		String[] xyz = cell.trim().split("\\s+");
		if (xyz.length != 3)
		{
			throw new IllegalArgumentException("not a tile: '" + cell + "'");
		}
		return new WorldPoint(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
	}

	private static <T> T at(String file, TsvTable.Line line, Supplier<T> parse)
	{
		try
		{
			return parse.get();
		}
		catch (RuntimeException e)
		{
			throw new IllegalStateException(file + ":" + line.getNumber() + ": " + e.getMessage(), e);
		}
	}
}
