package com.cluehelper.guide;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

/** Client thread only. Weapons and armour as the Entrana monks see them: any attack, defence or strength bonus. */
@Singleton
public class CombatGear
{
	private final ItemManager itemManager;

	@Inject
	CombatGear(ItemManager itemManager)
	{
		this.itemManager = itemManager;
	}

	public boolean isWeaponOrArmour(int itemId)
	{
		ItemStats stats = itemManager.getItemStats(itemId);
		ItemEquipmentStats bonuses = stats == null || !stats.isEquipable() ? null : stats.getEquipment();
		return bonuses != null && (bonuses.getAstab() != 0 || bonuses.getAslash() != 0 || bonuses.getAcrush() != 0
			|| bonuses.getAmagic() != 0 || bonuses.getArange() != 0 || bonuses.getDstab() != 0 || bonuses.getDslash() != 0
			|| bonuses.getDcrush() != 0 || bonuses.getDmagic() != 0 || bonuses.getDrange() != 0 || bonuses.getStr() != 0
			|| bonuses.getRstr() != 0 || bonuses.getMdmg() != 0);
	}
}
