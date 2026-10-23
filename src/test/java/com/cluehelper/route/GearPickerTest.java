package com.cluehelper.route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.List;
import java.util.Set;
import net.runelite.api.EquipmentInventorySlot;
import org.junit.Test;

public class GearPickerTest
{
	private static final int NECK = EquipmentInventorySlot.AMULET.getSlotIdx();
	private static final int HANDS = EquipmentInventorySlot.GLOVES.getSlotIdx();
	private static final int WEAPON = EquipmentInventorySlot.WEAPON.getSlotIdx();
	private static final int SHIELD = EquipmentInventorySlot.SHIELD.getSlotIdx();

	private static final GearPicker.Piece STRENGTH_AMULET = new GearPicker.Piece(1, NECK, false, 10);
	private static final GearPicker.Piece BARROWS_GLOVES = new GearPicker.Piece(2, HANDS, false, 12);
	private static final GearPicker.Piece WHIP = new GearPicker.Piece(3, WEAPON, false, 82);
	private static final GearPicker.Piece DEFENDER = new GearPicker.Piece(4, SHIELD, false, 6);
	private static final GearPicker.Piece GODSWORD = new GearPicker.Piece(5, WEAPON, true, 132);
	private static final List<GearPicker.Piece> PIECES = List.of(STRENGTH_AMULET, BARROWS_GLOVES, WHIP, DEFENDER, GODSWORD);

	@Test
	public void picksTheFewestBankItemsThenTheFewestToPutOn()
	{
		Object[][] cases = {
			// worn, inventory, stored, at least, equip, withdraw
			{Set.of(3), Set.of(2), Set.of(), 90, List.of(2), List.of()},
			{Set.of(3), Set.of(), Set.of(1, 2, 5), 100, List.of(5), List.of(5)},
			{Set.of(3), Set.of(4), Set.of(1, 2), 100, List.of(2, 4), List.of(2)},
			{Set.of(), Set.of(1, 2, 3, 4), Set.of(), 110, List.of(3, 2, 1, 4), List.of()},
		};
		for (Object[] c : cases)
		{
			@SuppressWarnings("unchecked")
			GearPicker.Choice choice = GearPicker.pick(PIECES, (Set<Integer>) c[0], (Set<Integer>) c[1], (Set<Integer>) c[2], (int) c[3])
				.orElseThrow();
			assertEquals("equip, at least " + c[3], c[4], choice.getEquip());
			assertEquals("withdraw, at least " + c[3], c[5], choice.getWithdraw());
		}
	}

	@Test
	public void nothingOwnedAddsUpToItIsEmpty()
	{
		assertFalse(GearPicker.pick(PIECES, Set.of(3), Set.of(1), Set.of(), 200).isPresent());
	}
}
