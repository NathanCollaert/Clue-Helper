package com.cluehelper.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Verbatim Check lines: each item words its count differently, and only these teach a charge count. */
public class CheckLinesTest
{
	@Test
	public void readsEveryRecordedCheckLine()
	{
		assertEquals(686, (int) CheckLines.parse("The pendant has 686 charges."));
		assertEquals(891, (int) CheckLines.parse("Your ring of shadows has 891 charges remaining."));
		assertEquals(867, (int) CheckLines.parse("Your Giantsoul amulet has 867 charges left powering it."));
		assertEquals(9926, (int) CheckLines.parse("The amulet has 9,926 charges."));
		assertEquals(1, (int) CheckLines.parse("The pendant has 1 charge."));
		assertEquals(CheckLines.UNLIMITED, (int) CheckLines.parse("The Camulet has unlimited charges."));
		assertNull(CheckLines.parse("You have correctly entered your PIN."));
		assertNull(CheckLines.parse("You withdraw your items from the STASH unit."));
	}
}
