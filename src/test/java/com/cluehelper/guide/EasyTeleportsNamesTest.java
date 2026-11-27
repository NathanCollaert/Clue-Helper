package com.cluehelper.guide;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.*;
import org.junit.Test;

/** A miss here is a teleport the player renamed in Easy Teleports that is silently never highlighted. */
public class EasyTeleportsNamesTest
{
	private static final List<EasyTeleportsNames.Rename> RENAMES = List.of(
		new EasyTeleportsNames.Rename("Castle Wars", "CW"),
		new EasyTeleportsNames.Rename("Stronghold Slayer Cave", "Gnome Stronghold Caves"));

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
		return EasyTeleportsNames.readings(shown, RENAMES).stream().anyMatch(reading -> MenuLabels.matches(reading, label));
	}
}
