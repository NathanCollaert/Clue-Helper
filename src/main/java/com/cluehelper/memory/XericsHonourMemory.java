package com.cluehelper.memory;

import com.cluehelper.player.PlayerModel;
import com.cluehelper.player.PositionRules;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.util.Text;

/**
 * Client thread. The game has no var for the Xeric's Honour unlock, and the talisman's menu shows the Honour line the
 * same whether it is unlocked or not. So it is learned from the chat line when an ancient tablet unlocks it, or, for
 * an account that unlocked it before, from a teleport that lands there: Mount Quidamortem can be walked to, but no
 * other teleport lands on that spot.
 */
@Singleton
public class XericsHonourMemory
{
	static final String KEY = "xericsHonour";
	/** The catalog's {@code UNLOCK_XERICS_HONOUR} token. */
	private static final String UNLOCK = "XERICS_HONOUR";
	/** The landing of the talisman's and the mounted talisman's Honour teleports in items.tsv and poh.tsv. */
	static final WorldPoint LANDING = new WorldPoint(1254, 3560, 0);
	private static final String TABLET_USED = "As the talisman and tablet are held together, a surge of power connects the two.";
	/** The real landing is 3 tiles north of the catalog's (1254 3563); a little more allows for a spread. */
	private static final int LANDING_TILES = 5;

	private final Client client;
	private final PlayerModel player;
	private final MemorySlot<Seen> remembered;
	private WorldPoint lastTile;

	@Inject
	XericsHonourMemory(Client client, MemoryStore memory, PlayerModel player)
	{
		this.client = client;
		this.player = player;
		this.remembered = memory.slot(KEY, Seen.class, Seen::new);
	}

	/** Call on start and after a profile change. */
	public void publish()
	{
		player.setUnlocks(isUnlocked() ? Set.of(UNLOCK) : Set.of());
	}

	/** Call before the player model's GameTick handler, so its snapshot has a fresh unlock. */
	public void onGameTick()
	{
		Player local = client.getLocalPlayer();
		WorldPoint tile = local == null ? null : WorldPoint.fromLocalInstance(client, local.getLocalLocation());
		if (PositionRules.teleported(lastTile, tile) && tile.distanceTo(LANDING) <= LANDING_TILES)
		{
			learn();
		}
		lastTile = tile;
	}

	public void onChatMessage(ChatMessage message)
	{
		if (message.getType() == ChatMessageType.GAMEMESSAGE && Text.removeTags(message.getMessage()).startsWith(TABLET_USED))
		{
			learn();
		}
	}

	/** Logging in, possibly on another account, is no teleport. */
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			lastTile = null;
		}
	}

	private boolean isUnlocked()
	{
		return remembered.get().learnedAt > 0;
	}

	private void learn()
	{
		if (isUnlocked())
		{
			return;
		}
		Seen seen = new Seen();
		seen.learnedAt = System.currentTimeMillis();
		remembered.set(seen);
		publish();
	}

	static final class Seen
	{
		/** Epoch millis when the unlock was first seen; 0 when never. */
		long learnedAt;
	}
}
