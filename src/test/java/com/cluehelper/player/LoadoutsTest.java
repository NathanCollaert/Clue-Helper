package com.cluehelper.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/** Every fixture item name must resolve: a name that silently resolved to nothing would drop a teleport from B10's golden routes. */
public class LoadoutsTest
{
	@Test
	public void theSevenFixturesBuildSnapshots()
	{
		for (String name : Loadouts.NAMES)
		{
			PlayerSnapshot s = Loadouts.load(name).getCarried();
			assertEquals(name, Book.STANDARD, s.getSpellbook().getBook());
			assertTrue(name, s.isQuestsRead());
		}
		PlayerSnapshot maxed = Loadouts.load("maxed-main").getCarried();
		assertEquals(28 + 15, maxed.getInventory().size());
		assertTrue(maxed.finished(Quest.LUNAR_DIPLOMACY) && maxed.getSpellbook().isSwapAccess());
		assertEquals(126, maxed.getCombatLevel());
		assertEquals(PlayerSnapshot.UnknownVars.PASS, maxed.getUnknownVars());

		PlayerSnapshot f2p = Loadouts.load("f2p").getCarried();
		assertFalse(f2p.isMembersWorld());
		assertEquals(37, f2p.boosted(Skill.MAGIC));
		assertEquals(17, Loadouts.load("gim").getBanked().size());
		assertTrue(Loadouts.load("fresh").getCarried().getInventory().isEmpty());
		assertEquals(1, (int) Loadouts.load("uim").getCarried().getInventory().get(ItemID.AMULET_OF_GLORY_4));
	}
}
