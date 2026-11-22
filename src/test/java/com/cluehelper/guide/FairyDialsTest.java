package com.cluehelper.guide;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FairyDialsTest
{
	/** Pairs from the fairy ring's cache definition: "Last-destination (XYZ)" shown at FAIRYRING_LASTLOC_OPVIS 64 + value. */
	@Test
	public void namesTheCodeTheRingsLastDestinationOptionShows()
	{
		assertEquals("AIP", FairyDials.code(0));
		assertEquals("AKS", FairyDials.code(9));
		assertEquals("DIQ", FairyDials.code(19));
		assertEquals("CLR", FairyDials.code(38));
		assertEquals("BKS", FairyDials.code(57));
		assertEquals("BJQ", FairyDials.code(63));
	}
}
