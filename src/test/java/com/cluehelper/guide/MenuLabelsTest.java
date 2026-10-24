package com.cluehelper.guide;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A miss here is a highlight that silently never shows. */
public class MenuLabelsTest
{
	@Test
	public void recordedLinesMatchTheirCatalogLabels()
	{
		assertTrue(MenuLabels.matches("<col=735a28>1</col>: Tree Gnome Village", "Tree Gnome Village"));
		assertTrue(MenuLabels.matches("<col=ccccff>1:</col> Emir's Arena", "Emir's Arena"));
		assertTrue(MenuLabels.matches("<col=ffffff>1</col> :  Barrows", "Barrows"));
		assertTrue(MenuLabels.matches("[L] Marim (Ape Atoll)", "Marim"));
		assertTrue(MenuLabels.matches("Xeric's Lookout", "1. Xeric's Lookout"));
		assertTrue(MenuLabels.matches("Your house (Rimmington)", "Your house"));
		assertTrue(MenuLabels.matches("<col=ff9040>Wilderness</col>", "Wilderness"));
		assertTrue(MenuLabels.matches("Feldip Hills (Carnivorous chinchompas)", "Feldip Hills"));
		assertTrue(MenuLabels.matches("Warrior's Guild", "Warriors' Guild"));
		assertTrue(MenuLabels.matches("7: Karamja: Kaleb Paramaya (retired)", "7. Kaleb Paramaya"));
		assertTrue(MenuLabels.matches("Carnivorous chinchompas", "Feldip Hills"));
		assertTrue(MenuLabels.matches("Black chinchompas", "Black chinchompa"));
		assertTrue(MenuLabels.matches("Monastery Teleport", "Kandarin Monastery"));
		assertTrue(MenuLabels.matches("Farm Teleport", "Ardougne Farm"));
		assertFalse(MenuLabels.matches("Farm Teleport", "Kandarin Monastery"));
		assertFalse(MenuLabels.matches("Xeric's Heart", "Xeric's Honour"));
		assertFalse(MenuLabels.matches("Ardougne", "West Ardougne Teleport"));
		assertTrue(MenuLabels.isLastDestination("Last-destination (BKS)", "BKS"));
		assertFalse(MenuLabels.isLastDestination("Last-destination (CKS)", "BKS"));
		assertTrue(MenuLabels.isRingDestination("<col=40d0ff>Ring-Zanaris</col>", "ZANARIS"));
		assertFalse(MenuLabels.isRingDestination("<col=40d0ff>Ring-configure</col>", "ZANARIS"));
	}
}
