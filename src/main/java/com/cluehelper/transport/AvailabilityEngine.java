package com.cluehelper.transport;

import com.cluehelper.player.*;
import java.util.*;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import net.runelite.api.Quest;

/**
 * Pure, so it runs on any thread. Spell rows are never filtered by their book: the planner decides which book a
 * route casts from.
 */
public final class AvailabilityEngine
{
	/** Carried first, so a banked snapshot withdraws only what the player lacks (its bank maps are empty otherwise). */
	private static final List<Container> CARRIED = List.of(Container.INVENTORY, Container.WORN, Container.BANK,
		Container.GROUP_STORAGE);
	/** At a bank a withdrawn item can be worn before the teleport. */
	private static final List<Container> WORN_ONLY = List.of(Container.WORN, Container.BANK, Container.GROUP_STORAGE);
	/** Shortest Path lists every charge of an item as its own alternative: glory(1) to glory(t6) is 12. */
	private static final int MAX_ALTERNATIVES_NAMED = 2;

	private final RuneModel runes;
	private final IntFunction<String> itemNames;

	/** @param itemNames called from any thread */
	public AvailabilityEngine(RuneModel runes, IntFunction<String> itemNames)
	{
		this.runes = runes;
		this.itemNames = itemNames;
	}

	public RuneModel getRunes()
	{
		return runes;
	}

	/** Also judges a banked snapshot ({@link PlayerSnapshot#getBank()} filled), whose witnesses name what to withdraw. */
	public AvailabilitySet carried(List<CatalogRow> rows, PlayerSnapshot player, long nowMinutes)
	{
		List<RowAvailability> judged = rows.stream().map(row -> judge(row, player, nowMinutes)).collect(Collectors.toList());
		long validUntil = judged.stream().mapToLong(RowAvailability::getPassesFrom).min().orElse(Long.MAX_VALUE);
		return new AvailabilitySet(player.getVersion(), List.copyOf(judged), validUntil);
	}

	public RowAvailability judge(CatalogRow row, PlayerSnapshot player, long nowMinutes)
	{
		Verdict v = new Verdict();
		Requirements req = row.getRequirements();
		req.getErrors().forEach(error -> v.no("catalog row unreadable: " + error));
		if (req.isMembers() && !player.isMembersWorld())
		{
			v.no("members world only");
		}
		if (player.getWildernessLevel() > req.getMaxWildernessLevel())
		{
			v.no("above Wilderness level " + req.getMaxWildernessLevel());
		}
		req.getLevels().forEach(need -> checkLevel(need, player, v));
		req.getQuests().forEach(quest -> checkQuest(quest, player, v));
		req.getVars().forEach(check -> checkVar(check, player, nowMinutes, v));
		checkHouse(req.getHouse(), player, v);
		req.getPlantedTrees().forEach(patch -> checkPlanted(patch, player, v));
		req.getItems().forEach(clause -> checkClause(clause, req.isSpendsCharges(), player, nowMinutes, v));
		return v.result(row);
	}

	private static void checkLevel(LevelNeed need, PlayerSnapshot player, Verdict v)
	{
		int have = need.have(player);
		if (have < need.getLevel())
		{
			v.no("needs " + need.describe() + " (has " + have + ")");
		}
	}

	private static void checkQuest(Quest quest, PlayerSnapshot player, Verdict v)
	{
		if (!player.isQuestsRead())
		{
			v.unread("quests not read yet");
		}
		else if (!player.finished(quest))
		{
			v.no(quest.getName() + " not finished");
		}
	}

	private static void checkHouse(HouseNeed need, PlayerSnapshot player, Verdict v)
	{
		if (need == null)
		{
			return;
		}
		HouseState house = player.getHouse();
		boolean built = need.getKind() == HouseNeed.Kind.NEXUS ? house.getNexusKeys().contains(need.getIds().get(0))
			: house.hasAny(need.getIds());
		if (!built)
		{
			v.no(need.describe() + " not known in your house");
		}
	}

	/** Unknown counts as unavailable: a tree never seen grown may be dead or not planted. */
	private static void checkPlanted(String patch, PlayerSnapshot player, Verdict v)
	{
		if (!player.getPlantedTrees().contains(patch))
		{
			v.no("spirit tree at " + patch + " not seen grown");
		}
	}

	private static void checkVar(VarCheck check, PlayerSnapshot player, long nowMinutes, Verdict v)
	{
		Integer value = check.getKind() == VarCheck.Kind.VARBIT ? player.varbit(check.getId()) : player.varp(check.getId());
		if (value == null)
		{
			if (player.getUnknownVars() == PlayerSnapshot.UnknownVars.FAIL)
			{
				v.no(check.describe());
			}
			else if (player.getUnknownVars() == PlayerSnapshot.UnknownVars.UNREAD)
			{
				v.unread(check.describe() + ": not read");
			}
			return;
		}
		if (!check.test(value, nowMinutes))
		{
			v.no(check.whyNot(value, nowMinutes));
			v.passesFrom = Math.min(v.passesFrom, check.passesFrom(value));
		}
	}

	private void checkClause(ItemClause clause, boolean spendsCharges, PlayerSnapshot player, long nowMinutes, Verdict v)
	{
		if (clause.getWaivedBy() != null && passes(clause.getWaivedBy(), player, nowMinutes))
		{
			return;
		}
		List<String> shortfalls = new ArrayList<>();
		String unread = null;
		Paid best = null;
		for (ItemClause.Branch branch : clause.getBranches())
		{
			Paid paid = pay(branch, clause.isMustBeWorn(), spendsCharges, player);
			if (paid.witness != null)
			{
				best = best == null || preferred(paid, best, spendsCharges, player) ? paid : best;
				continue;
			}
			if (paid.unread != null)
			{
				unread = paid.unread;
			}
			else
			{
				shortfalls.add(paid.shortfall);
			}
		}
		if (best != null)
		{
			v.witness.addAll(best.witness);
			v.chargesUnknown |= best.chargesUnknown;
		}
		else if (unread != null)
		{
			v.unread(unread);
		}
		else
		{
			v.no(shortfalls.size() <= MAX_ALTERNATIVES_NAMED ? String.join(" or ", shortfalls)
				: shortfalls.get(0) + " or one of " + (shortfalls.size() - 1) + " other variants");
		}
	}

	/** Fewest known charges wins, so partly charged items get used up first; ties keep the catalog's order. */
	private static boolean preferred(Paid a, Paid b, boolean spendsCharges, PlayerSnapshot player)
	{
		int bankA = fromStorage(a.witness);
		int bankB = fromStorage(b.witness);
		if (bankA != bankB)
		{
			return bankA < bankB;
		}
		return spendsCharges && knownCharges(a.witness, player) < knownCharges(b.witness, player);
	}

	private static int fromStorage(List<ItemUse> witness)
	{
		return (int) witness.stream().filter(use -> use.getContainer().isStored()).count();
	}

	/** Unknown counts as more than any known total. */
	private static int knownCharges(List<ItemUse> witness, PlayerSnapshot player)
	{
		int total = 0;
		for (ItemUse use : witness)
		{
			Charge charge = player.getCharges().get(use.getItemId());
			if (charge == null || charge.getCharges() < 0)
			{
				return Integer.MAX_VALUE;
			}
			total += charge.getCharges();
		}
		return total;
	}

	private Paid pay(ItemClause.Branch branch, boolean mustBeWorn, boolean spendsCharges, PlayerSnapshot player)
	{
		if (branch.isUnlock())
		{
			return player.getUnlocks().contains(branch.getUnlock()) ? Paid.with(List.of(), false)
				: Paid.unread("unlock " + branch.getUnlock() + " not seen yet");
		}
		List<Integer> ids = branch.getIds();
		if (ids.size() == 1 && runes.isRune(ids.get(0)) && !mustBeWorn)
		{
			RuneModel.Supply supply = runes.supply(ids.get(0), branch.getQuantity(), player);
			return supply.isEnough() ? Paid.with(supply.getWitness(), false)
				: Paid.missing(runes.shortfall(ids.get(0), branch.getQuantity(), supply.getHave()));
		}
		List<ItemUse> used = new ArrayList<>();
		boolean chargesUnknown = false;
		boolean emptied = false;
		int have = 0;
		for (Container container : mustBeWorn ? WORN_ONLY : CARRIED)
		{
			for (int id : ids)
			{
				int count = CatalogRules.UNCHARGED.contains(id) ? 0 : player.count(container, id);
				if (count == 0 || have >= branch.getQuantity())
				{
					continue;
				}
				Charge charge = player.getCharges().get(id);
				if (spendsCharges && charge != null && charge.getCharges() <= 0)
				{
					emptied = true;
					continue;
				}
				chargesUnknown |= spendsCharges && charge == null;
				int take = Math.min(count, branch.getQuantity() - have);
				used.add(new ItemUse(container, id, take));
				have += take;
			}
		}
		if (have >= branch.getQuantity())
		{
			return Paid.with(used, chargesUnknown);
		}
		String what = itemNames.apply(ids.get(0)) + (ids.size() > 1 ? " (any of " + ids.size() + ")" : "");
		return Paid.missing((mustBeWorn ? "needs to wield " : "needs ")
			+ (branch.getQuantity() > 1 ? branch.getQuantity() + " x " : "") + what + (emptied ? ", no charges left" : ""));
	}

	private static boolean passes(VarCheck check, PlayerSnapshot player, long nowMinutes)
	{
		Integer value = check.getKind() == VarCheck.Kind.VARBIT ? player.varbit(check.getId()) : player.varp(check.getId());
		if (value == null)
		{
			return player.getUnknownVars() == PlayerSnapshot.UnknownVars.PASS;
		}
		return check.test(value, nowMinutes);
	}

	private static final class Paid
	{
		List<ItemUse> witness;
		boolean chargesUnknown;
		String shortfall;
		String unread;

		static Paid with(List<ItemUse> witness, boolean chargesUnknown)
		{
			Paid p = new Paid();
			p.witness = witness;
			p.chargesUnknown = chargesUnknown;
			return p;
		}

		static Paid missing(String shortfall)
		{
			Paid p = new Paid();
			p.shortfall = shortfall;
			return p;
		}

		static Paid unread(String what)
		{
			Paid p = new Paid();
			p.unread = what;
			return p;
		}
	}

	private static final class Verdict
	{
		final List<String> whyNot = new ArrayList<>();
		final List<ItemUse> witness = new ArrayList<>();
		boolean unread;
		boolean chargesUnknown;
		long passesFrom = Long.MAX_VALUE;

		void no(String reason)
		{
			whyNot.add(reason);
		}

		void unread(String reason)
		{
			unread = true;
			whyNot.add(reason);
		}

		RowAvailability result(CatalogRow row)
		{
			boolean available = whyNot.isEmpty();
			RowAvailability.Confidence confidence = unread ? RowAvailability.Confidence.UNREAD
				: available && chargesUnknown ? RowAvailability.Confidence.CHARGES_UNKNOWN : RowAvailability.Confidence.SURE;
			return new RowAvailability(row, available, confidence, available ? List.copyOf(witness) : List.of(),
				List.copyOf(whyNot), passesFrom);
		}
	}
}
