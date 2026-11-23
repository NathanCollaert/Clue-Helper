package com.cluehelper.guide;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AbyssObstaclesTest
{
	@Test
	public void readsTheToolPastAVariantSuffix()
	{
		assertEquals("pickaxe", AbyssObstacles.toolWord("Rune pickaxe"));
		assertEquals("pickaxe", AbyssObstacles.toolWord("Dragon pickaxe (or)"));
		assertEquals("axe", AbyssObstacles.toolWord("Crystal axe (inactive)"));
		assertEquals("hatchet", AbyssObstacles.toolWord("Imcando hatchet"));
		assertEquals("tinderbox", AbyssObstacles.toolWord("Tinderbox"));
	}
}
