package com.cluehelper;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;

/** Where the local player stands, as the timing traces read it. */
final class PlayerTiles
{
	static final String WALK = "Walk here";
	/** Further than a tick's run, so only a teleport, a ladder or a ride moves the player this far. */
	private static final int JUMP_TILES = 5;

	private PlayerTiles()
	{
	}

	static WorldPoint tile(Client client)
	{
		Player local = client.getLocalPlayer();
		return local == null ? null : WorldPoint.fromLocalInstance(client, local.getLocalLocation());
	}

	static boolean isJump(WorldPoint from, WorldPoint to)
	{
		return from != null && to != null && (from.getPlane() != to.getPlane() || from.distanceTo2D(to) > JUMP_TILES);
	}

	static boolean keypadOpen(Client client)
	{
		return client.getWidget(InterfaceID.BANKPIN_KEYPAD, 0) != null;
	}

	static String text(WorldPoint tile)
	{
		return tile == null ? null : tile.getX() + " " + tile.getY() + " " + tile.getPlane();
	}
}
