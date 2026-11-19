package com.cluehelper.guide;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.*;
import org.junit.Test;

/** A miss here is a teleport the player renamed in Easy Teleports that is silently never highlighted. */
public class EasyTeleportsNamesTest
{
	private static final Map<String, String> SETTINGS = Map.of(
		"enableRingOfDueling", "true",
		"replacementCastleWars", "<col=00ff00>CW</col>",
		"enableSlayerRing", "true",
		"enablePharaohSceptre", "false",
		"replacementJalsavrah", "Plunder");

	private final Map<String, List<String>> gameNames = EasyTeleportsNames.gameNames(SETTINGS::get);

	@Test
	public void renamedLinesNameTheirTeleport()
	{
		assertTrue(shows("<col=00ff00>CW</col>", "Castle Wars"));
		assertTrue(shows("<col=ccccff>2:</col> CW", "Castle Wars"));
		assertTrue(shows("Gnome Stronghold Caves", "Stronghold Slayer Cave"));
		assertTrue(shows("Ferox Enclave", "Ferox Enclave"));
		assertFalse(shows("Plunder", "Jalsavrah"));
	}

	private boolean shows(String shown, String label)
	{
		return EasyTeleportsNames.readings(shown, gameNames).stream().anyMatch(reading -> MenuLabels.matches(reading, label));
	}
}
