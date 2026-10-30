package com.cluehelper.memory;

import com.cluehelper.player.*;
import java.util.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.util.Text;

/**
 * Client thread. Account unlocks the game keeps no var for, learned from play and named as the catalog's
 * {@code UNLOCK_} tokens name them.
 * <ul>
 * <li>Xeric's Honour: the talisman's menu shows its line the same whether it is unlocked or not. It is learned from the
 * chat line when an ancient tablet unlocks it, or from a teleport that lands there: Mount Quidamortem can be walked to,
 * but no other teleport lands on that spot.</li>
 * <li>Dragontooth Island: the ghost captain's fare is 25 ecto-tokens until a one-off 500 unlocks free trips. A landing
 * on the island that cost no tokens shows the trip is free.</li>
 * </ul>
 */
@Singleton
public class UnlockMemory
{
	static final String XERICS_HONOUR_KEY = "xericsHonour";
	static final String DRAGONTOOTH_KEY = "dragontoothFreeTrips";
	private static final String XERICS_HONOUR = "XERICS_HONOUR";
	private static final String DRAGONTOOTH = "DRAGONTOOTH";
	/** The landing of the talisman's and the mounted talisman's Honour teleports in items.tsv and poh.tsv. */
	static final WorldPoint XERICS_HONOUR_LANDING = new WorldPoint(1254, 3560, 0);
	/** The ghost captain's landing on Dragontooth Island in rides.tsv. */
	static final WorldPoint DRAGONTOOTH_LANDING = new WorldPoint(3792, 3560, 0);
	private static final String TABLET_USED = "As the talisman and tablet are held together, a surge of power connects the two.";
	/** The real Honour landing is 3 tiles north of the catalog's (1254 3563); a little more allows for a spread. */
	private static final int LANDING_TILES = 5;

	@Inject
	private Client client;
	@Inject
	private PlayerModel player;
	private MemorySlot<Seen> xericsHonour;
	private MemorySlot<Seen> dragontooth;
	private WorldPoint lastTile;
	private int lastEctoTokens;

	@Inject
	void init(MemoryStore memory)
	{
		xericsHonour = memory.slot(XERICS_HONOUR_KEY, Seen.class, Seen::new);
		dragontooth = memory.slot(DRAGONTOOTH_KEY, Seen.class, Seen::new);
	}

	/** Call on start and after a profile change. */
	public void publish()
	{
		Set<String> unlocks = new HashSet<>();
		if (xericsHonour.get().isSeen())
		{
			unlocks.add(XERICS_HONOUR);
		}
		if (dragontooth.get().isSeen())
		{
			unlocks.add(DRAGONTOOTH);
		}
		player.setUnlocks(unlocks);
	}

	/** Call before the player model's GameTick handler, so its snapshot has fresh unlocks. */
	public void onGameTick()
	{
		Player local = client.getLocalPlayer();
		WorldPoint tile = local == null ? null : WorldPoint.fromLocalInstance(client, local.getLocalLocation());
		int ectoTokens = carriedEctoTokens();
		if (PositionRules.teleported(lastTile, tile))
		{
			if (tile.distanceTo(XERICS_HONOUR_LANDING) <= LANDING_TILES)
			{
				learn(xericsHonour);
			}
			if (tile.distanceTo(DRAGONTOOTH_LANDING) <= LANDING_TILES && ectoTokens >= lastEctoTokens)
			{
				learn(dragontooth);
			}
		}
		lastTile = tile;
		lastEctoTokens = ectoTokens;
	}

	public void onChatMessage(ChatMessage message)
	{
		if (message.getType() == ChatMessageType.GAMEMESSAGE && Text.removeTags(message.getMessage()).startsWith(TABLET_USED))
		{
			learn(xericsHonour);
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

	private int carriedEctoTokens()
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		return inventory == null ? 0 : inventory.count(ItemID.ECTOTOKEN);
	}

	private void learn(MemorySlot<Seen> unlock)
	{
		if (unlock.get().isSeen())
		{
			return;
		}
		Seen seen = new Seen();
		seen.learnedAt = System.currentTimeMillis();
		unlock.set(seen);
		publish();
	}

	static final class Seen
	{
		/** Epoch millis when the unlock was first seen; 0 when never. */
		long learnedAt;

		boolean isSeen()
		{
			return learnedAt > 0;
		}
	}
}
