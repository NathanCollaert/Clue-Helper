package com.cluehelper.route;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Value;
import net.runelite.api.EquipmentInventorySlot;

/**
 * Worn gear adding up to a bonus total, from what the player wears, carries and has stored: as few items out of the
 * bank as can do it, then as few to put on. One item per slot; a two-handed weapon leaves the shield slot empty.
 */
public final class GearPicker
{
	private static final int WEAPON = EquipmentInventorySlot.WEAPON.getSlotIdx();
	private static final int SHIELD = EquipmentInventorySlot.SHIELD.getSlotIdx();

	@Value
	public static class Piece
	{
		int itemId;
		int slot;
		boolean twoHanded;
		int value;
	}

	@Value
	public static class Choice
	{
		/** Not yet worn, the withdrawn ones included. */
		List<Integer> equip;
		List<Integer> withdraw;
	}

	@Value
	private static class Upgrade
	{
		Piece piece;
		int gain;
	}

	private GearPicker()
	{
	}

	/** Empty when nothing owned adds up to {@code atLeast}. */
	public static Optional<Choice> pick(Collection<Piece> pieces, Set<Integer> worn, Set<Integer> inventory, Set<Integer> stored,
		int atLeast)
	{
		Choice best = null;
		for (boolean twoHanded : new boolean[]{false, true})
		{
			Choice choice = pick(pieces, twoHanded, worn, inventory, stored, atLeast);
			if (choice != null && (best == null || cheaper(choice, best)))
			{
				best = choice;
			}
		}
		return Optional.ofNullable(best);
	}

	private static Choice pick(Collection<Piece> pieces, boolean twoHanded, Set<Integer> worn, Set<Integer> inventory,
		Set<Integer> stored, int atLeast)
	{
		Map<Integer, Piece> current = bestPerSlot(pieces, twoHanded, worn);
		Map<Integer, Piece> carried = bestPerSlot(pieces, twoHanded, union(worn, inventory));
		Map<Integer, Piece> storedBest = bestPerSlot(pieces, twoHanded, stored);
		int need = atLeast - current.values().stream().mapToInt(Piece::getValue).sum();
		List<Upgrade> fromCarried = upgrades(carried, current, Map.of());
		List<Upgrade> fromStorage = upgrades(storedBest, current, carried);
		for (int withdrawn = 0; withdrawn <= fromStorage.size(); withdrawn++)
		{
			List<Upgrade> taken = fromStorage.subList(0, withdrawn);
			Set<Integer> takenSlots = new HashSet<>();
			int left = need;
			for (Upgrade upgrade : taken)
			{
				takenSlots.add(upgrade.piece.slot);
				left -= upgrade.piece.value - value(current.get(upgrade.piece.slot));
			}
			List<Integer> equip = new ArrayList<>();
			taken.forEach(upgrade -> equip.add(upgrade.piece.itemId));
			for (Upgrade upgrade : fromCarried)
			{
				if (left <= 0)
				{
					break;
				}
				if (!takenSlots.contains(upgrade.piece.slot))
				{
					equip.add(upgrade.piece.itemId);
					left -= upgrade.gain;
				}
			}
			if (left <= 0)
			{
				List<Integer> withdraw = taken.stream().map(upgrade -> upgrade.piece.itemId).collect(Collectors.toUnmodifiableList());
				return new Choice(List.copyOf(equip), withdraw);
			}
		}
		return null;
	}

	/** Per slot, how much {@code better} adds over the larger of what is worn and {@code beaten}; best first. */
	private static List<Upgrade> upgrades(Map<Integer, Piece> better, Map<Integer, Piece> current, Map<Integer, Piece> beaten)
	{
		List<Upgrade> upgrades = new ArrayList<>();
		better.forEach((slot, piece) ->
		{
			int gain = piece.value - Math.max(value(current.get(slot)), value(beaten.get(slot)));
			if (gain > 0)
			{
				upgrades.add(new Upgrade(piece, gain));
			}
		});
		upgrades.sort(Comparator.comparingInt(Upgrade::getGain).reversed());
		return upgrades;
	}

	private static Map<Integer, Piece> bestPerSlot(Collection<Piece> pieces, boolean twoHanded, Set<Integer> owned)
	{
		Map<Integer, Piece> best = new HashMap<>();
		for (Piece piece : pieces)
		{
			if (owned.contains(piece.itemId) && fits(piece, twoHanded))
			{
				best.merge(piece.slot, piece, (a, b) -> b.value > a.value ? b : a);
			}
		}
		return best;
	}

	private static boolean fits(Piece piece, boolean twoHanded)
	{
		if (piece.slot == SHIELD)
		{
			return !twoHanded;
		}
		return piece.slot != WEAPON || piece.twoHanded == twoHanded;
	}

	private static boolean cheaper(Choice a, Choice b)
	{
		if (a.withdraw.size() != b.withdraw.size())
		{
			return a.withdraw.size() < b.withdraw.size();
		}
		return a.equip.size() < b.equip.size();
	}

	private static int value(Piece piece)
	{
		return piece == null ? 0 : piece.value;
	}

	private static Set<Integer> union(Set<Integer> a, Set<Integer> b)
	{
		Set<Integer> all = new HashSet<>(a);
		all.addAll(b);
		return all;
	}
}
