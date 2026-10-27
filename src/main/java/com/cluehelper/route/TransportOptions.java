package com.cluehelper.route;

import com.cluehelper.player.*;
import com.cluehelper.transport.*;
import java.util.*;

/** The teleports and spellbook changes available on one side of the bank stop, placed by where each is used from. */
final class TransportOptions
{
	/** A row the scroll book rules keep until a bank stop. */
	private static final int AFTER_BANK_ONLY = -1;
	private static final int SWAP_DURATION_TICKS = 4;
	private static final int SWAP_MENU_TICKS = 1;
	/** The house scene loads about a tick after the transport's own landing. */
	private static final int HOUSE_LOAD_HALF_TICKS = 2;

	/** A teleport or a spellbook change as the search takes it: where it ends, what it costs and what it spends. */
	static class Edge
	{
		int node = WalkTable.NO_NODE;
		int halfTicks;
		int[] res;
		int[] amounts;
		int[] withdraw;
		List<ItemUse> withdrawUses;
		RowAvailability.Confidence confidence;
	}

	static final class Teleport extends Edge
	{
		CatalogRow row;
		Book book;
		boolean minigame;
		int maxWilderness;
	}

	static final class Change extends Edge
	{
		SpellbookChange change;
		boolean capeUse;
	}

	final List<Teleport> teleports = new ArrayList<>();
	final Map<Integer, Teleport> fairyEntries = new HashMap<>();
	final List<Teleport> fairyLandings = new ArrayList<>();
	/** Transports that start at a node: house furniture, spirit trees, Renu. */
	final Map<Integer, List<Teleport>> located = new HashMap<>();
	final Map<Integer, List<Change>> changes = new HashMap<>();
	Change cape;
	Change swap;

	private final PlanContext ctx;

	TransportOptions(PlanContext ctx, PlayerSnapshot player, int bankState)
	{
		this.ctx = ctx;
		PlayerSnapshot judgedAt = player.toBuilder().wildernessLevel(0).build();
		addTeleports(player, judgedAt, bankState);
		addChanges(player, judgedAt, bankState);
	}

	/** Every available teleport row, placed by where it is used from; of a home or minigame row's variants, the longest. */
	private void addTeleports(PlayerSnapshot player, PlayerSnapshot judgedAt, int bankState)
	{
		boolean carriesBook = player.carried(BookScrolls.BOOK) > 0;
		Map<String, Teleport> longestVariant = new LinkedHashMap<>();
		for (CatalogRow row : ctx.in.getCatalog().getTeleports())
		{
			if (ctx.in.getRefusedChanges().contains(row.getName()))
			{
				continue;
			}
			RowAvailability judged = ctx.in.getEngine().judge(row, judgedAt, ctx.in.getNowMinutes());
			if (!judged.isAvailable())
			{
				continue;
			}
			int bookStop = scrollBookHalfTicks(row, carriesBook, bankState);
			if (bookStop == AFTER_BANK_ONLY)
			{
				continue;
			}
			Teleport tele = teleport(judged, player, bankState);
			tele.halfTicks += bookStop;
			place(row, tele, player, longestVariant);
		}
		teleports.addAll(longestVariant.values());
		keepMostVersatileWithdraws(teleports);
	}

	/**
	 * The master scroll book's own Wilderness teleport reads a scroll taken out at the bank, and any Wilderness teleport
	 * banks a carried book first: the half-ticks that costs, or {@link #AFTER_BANK_ONLY} before the bank stop.
	 */
	private int scrollBookHalfTicks(CatalogRow row, boolean carriesBook, int bankState)
	{
		boolean bookStaysOut = ctx.in.getSettings().isScrollBookOutOfWilderness();
		boolean takenOut = BookScrolls.isTakenOutAtBank(row, bookStaysOut);
		boolean bankedFirst = !takenOut && bookStaysOut && carriesBook && BookScrolls.intoWilderness(row);
		if ((takenOut || bankedFirst) && bankState != PlanContext.BANKED)
		{
			return AFTER_BANK_ONLY;
		}
		return takenOut ? 2 * BookScrolls.TAKE_OUT_TICKS : bankedFirst ? 2 * BookScrolls.DEPOSIT_TICKS : 0;
	}

	/** A fairy ring entry, a transport used where it stands, a fairy ring landing, or a plain teleport by its variant. */
	private void place(CatalogRow row, Teleport tele, PlayerSnapshot player, Map<String, Teleport> longestVariant)
	{
		if (row.getOrigin() != null && row.getCategory() == Category.FAIRY_RING)
		{
			tele.node = ctx.nodeAt(row.getOrigin());
			tele.halfTicks += houseWalkHalfTicks(row.getRequirements(), player);
			fairyEntries.merge(tele.node, tele, (a, b) -> a.halfTicks <= b.halfTicks ? a : b);
		}
		else if (row.getOrigin() != null)
		{
			int from = ctx.nodeAt(row.getOrigin());
			tele.halfTicks += from == ctx.pohNode ? houseWalkHalfTicks(row.getRequirements(), player) : 0;
			located.computeIfAbsent(from, k -> new ArrayList<>()).add(tele);
		}
		else if (row.getCode() != null)
		{
			fairyLandings.add(tele);
		}
		else
		{
			longestVariant.merge(variantKey(row), tele, (a, b) -> a.halfTicks >= b.halfTicks ? a : b);
		}
	}

	/** Every available spellbook change: the Swap, the first cape, and the altars and Tyss where they stand. */
	private void addChanges(PlayerSnapshot player, PlayerSnapshot judgedAt, int bankState)
	{
		for (SpellbookChange change : ctx.in.getCatalog().getSpellbookChanges())
		{
			if (change.isInHouse() && ctx.pohNode == WalkTable.NO_NODE || ctx.in.getRefusedChanges().contains(change.getName()))
			{
				continue;
			}
			CatalogRow asRow = CatalogRow.builder().name(change.getName()).requirements(change.getRequirements()).build();
			RowAvailability judged = ctx.in.getEngine().judge(asRow, judgedAt, ctx.in.getNowMinutes());
			if (!judged.isAvailable())
			{
				continue;
			}
			Change c = change(change, judged, player, bankState);
			if (change.isTemporary())
			{
				swap = c;
			}
			else if (change.isAnywhere())
			{
				cape = cape == null ? c : cape;
			}
			else
			{
				c.node = change.isInHouse() ? ctx.pohNode : ctx.nodeAt(change.getTile());
				c.halfTicks += houseWalkHalfTicks(change.getRequirements(), player);
				changes.computeIfAbsent(c.node, k -> new ArrayList<>()).add(c);
			}
		}
	}

	/** Prefers e.g. the Max cape over a Construction cape that gives the same teleport. */
	private void keepMostVersatileWithdraws(List<Teleport> all)
	{
		Map<String, Teleport> kept = new HashMap<>();
		for (Teleport tele : all)
		{
			if (tele.withdraw.length > 0)
			{
				kept.merge(sameTeleportKey(tele), tele, (a, b) -> ctx.versatility(b.withdraw) > ctx.versatility(a.withdraw) ? b : a);
			}
		}
		all.removeIf(tele -> tele.withdraw.length > 0 && kept.get(sameTeleportKey(tele)) != tele);
	}

	private String sameTeleportKey(Teleport tele)
	{
		return tele.node + "|" + tele.halfTicks + "|" + tele.book;
	}

	/** Home and minigame rows come once per animation override; with several on, the game picks one, so the longest counts. */
	private String variantKey(CatalogRow row)
	{
		return row.getCategory() + "|" + row.getName() + "|" + row.getDestination() + "|" + row.getRequirements().getBook();
	}

	private Teleport teleport(RowAvailability judged, PlayerSnapshot player, int bankState)
	{
		CatalogRow row = judged.getRow();
		Teleport tele = new Teleport();
		tele.row = row;
		tele.node = row.getDestination() == null ? WalkTable.NO_NODE : ctx.nodeAt(row.getDestination());
		tele.halfTicks = 2 * (row.getDuration() + row.getMenuTicks())
			+ (tele.node != WalkTable.NO_NODE && tele.node == ctx.pohNode ? HOUSE_LOAD_HALF_TICKS : 0);
		tele.book = row.getRequirements().getBook();
		tele.minigame = row.getCategory() == Category.MINIGAME;
		tele.maxWilderness = row.getRequirements().getMaxWildernessLevel();
		spending(tele, row.getSpends(), judged, player, bankState);
		return tele;
	}

	private Change change(SpellbookChange change, RowAvailability judged, PlayerSnapshot player, int bankState)
	{
		Change c = new Change();
		c.change = change;
		c.halfTicks = change.isTemporary() ? 2 * (SWAP_DURATION_TICKS + SWAP_MENU_TICKS) : 2 * (change.getDuration() + change.getMenuTicks());
		c.capeUse = change.getSpends().stream().anyMatch(s -> s.getKind() == Spend.Kind.CAPE_USE);
		spending(c, change.getSpends(), judged, player, bankState);
		return c;
	}

	private void spending(Edge edge, List<Spend> spends, RowAvailability judged, PlayerSnapshot player, int bankState)
	{
		List<ItemUse> witness = judged.getWitness();
		Map<Integer, Integer> units = new LinkedHashMap<>();
		for (Spend spend : spends)
		{
			switch (spend.getKind())
			{
				case RUNES:
				case ITEM:
					for (ItemUse use : witness)
					{
						if (use.getContainer() != Container.WORN)
						{
							int r = ctx.resources.item(use.getItemId(), player, bankState);
							units.merge(r, use.getQuantity(), Integer::sum);
						}
					}
					break;
				case CHARGE:
					for (ItemUse use : witness)
					{
						units.merge(ctx.resources.charge(use.getItemId(), player, bankState), 1, Integer::sum);
					}
					break;
				case COOLDOWN:
					units.merge(ctx.resources.once("cooldown:" + spend.getVar(), bankState), 1, Integer::sum);
					break;
				case DAILY:
					units.merge(ctx.resources.once("daily:" + spend.getVar(), bankState), 1, Integer::sum);
					break;
				case CAPE_USE:
					SpellbookState state = player.getSpellbook();
					units.merge(ctx.resources.capeUses(state == null ? 0 : state.getCapeChangesLeft(), bankState), 1, Integer::sum);
					break;
				default:
					throw new IllegalArgumentException("unhandled spend " + spend.getKind());
			}
		}
		units.forEach(ctx.resources::used);
		edge.res = units.keySet().stream().mapToInt(Integer::intValue).toArray();
		edge.amounts = units.values().stream().mapToInt(Integer::intValue).toArray();
		edge.confidence = judged.getConfidence();
		edge.withdrawUses = withdrawUses(witness);
		edge.withdraw = ids(edge.withdrawUses);
	}

	private static int houseWalkHalfTicks(Requirements requirements, PlayerSnapshot player)
	{
		HouseNeed need = requirements.getHouse();
		if (need == null)
		{
			return 0;
		}
		HouseState house = player.getHouse();
		return 2 * house.walkTicks(need.getKind() == HouseNeed.Kind.NEXUS ? HouseState.NEXUS_OBJECTS : need.getIds());
	}

	/** The stored items a judged row's witness takes out of the bank. */
	static List<ItemUse> withdrawUses(List<ItemUse> witness)
	{
		List<ItemUse> stored = new ArrayList<>();
		for (ItemUse use : witness)
		{
			if (use.getContainer().isStored())
			{
				stored.add(use);
			}
		}
		return List.copyOf(stored);
	}

	/** The uses' item ids, sorted, each once. */
	static int[] ids(List<ItemUse> uses)
	{
		return uses.stream().mapToInt(ItemUse::getItemId).distinct().sorted().toArray();
	}
}
