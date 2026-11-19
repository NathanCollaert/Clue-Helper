package com.cluehelper.memory;

import com.cluehelper.Tables;
import com.cluehelper.player.*;
import com.cluehelper.transport.CatalogRow;
import java.util.*;
import java.util.stream.Collectors;
import javax.inject.*;
import lombok.Value;
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
 * <li>Logs in the balloons' log storage, which pays a flight's log when it holds one: a flight that cost no carried
 * log shows it does, and a refused flight without the log carried shows it is empty again.</li>
 * </ul>
 */
@Singleton
public class UnlockMemory
{
	static final String XERICS_HONOUR_KEY = "xericsHonour";
	static final String DRAGONTOOTH_KEY = "dragontoothFreeTrips";
	static final String STORED_LOGS_KEY = "balloonStoredLogs";
	private static final String XERICS_HONOUR = "XERICS_HONOUR";
	private static final String DRAGONTOOTH = "DRAGONTOOTH";
	/** The landing of the talisman's and the mounted talisman's Honour teleports in items.tsv and poh.tsv. */
	static final WorldPoint XERICS_HONOUR_LANDING = new WorldPoint(1254, 3560, 0);
	/** The ghost captain's landing on Dragontooth Island in rides.tsv. */
	static final WorldPoint DRAGONTOOTH_LANDING = new WorldPoint(3792, 3560, 0);
	private static final String TABLET_USED = "As the talisman and tablet are held together, a surge of power connects the two.";
	/** The real Honour landing (1254 3563) is 3 tiles north of the catalog's; a little more allows for a spread. */
	private static final int LANDING_TILES = 5;
	private static final List<BalloonFare> BALLOON_FARES = Tables.rows("balloon fares").stream()
		.map(row -> new BalloonFare(tile(row.getKey()), row.id(0), row.getCells().get(1)))
		.collect(Collectors.toUnmodifiableList());
	/** A flight starts beside another balloon; a teleport scroll that lands near one (the Lumber Yard's) does not. */
	private static final int BALLOON_TILES = 15;

	@Inject
	private Client client;
	@Inject
	private PlayerModel player;
	private MemorySlot<Seen> xericsHonour;
	private MemorySlot<Seen> dragontooth;
	private MemorySlot<StoredLogs> storedLogs;
	private WorldPoint lastTile;
	private int lastEctoTokens;
	private Map<Integer, Integer> lastLogs = Map.of();

	@Inject
	void init(MemoryStore memory)
	{
		xericsHonour = memory.slot(XERICS_HONOUR_KEY, Seen.class, Seen::new);
		dragontooth = memory.slot(DRAGONTOOTH_KEY, Seen.class, Seen::new);
		storedLogs = memory.slot(STORED_LOGS_KEY, StoredLogs.class, StoredLogs::new);
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
		unlocks.addAll(storedLogs.get().unlocks);
		player.setUnlocks(unlocks);
	}

	/** Call before the player model's GameTick handler, so its snapshot has fresh unlocks. */
	public void onGameTick()
	{
		Player local = client.getLocalPlayer();
		WorldPoint tile = local == null ? null : WorldPoint.fromLocalInstance(client, local.getLocalLocation());
		int ectoTokens = carriedEctoTokens();
		Map<Integer, Integer> logs = carriedLogs();
		if (PositionRules.teleported(lastTile, tile))
		{
			learnStoredLogs(lastTile, tile, logs);
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
		lastLogs = logs;
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

	/** A flight from one balloon to another that left the carried logs as they were was paid from the log storage. */
	private void learnStoredLogs(WorldPoint from, WorldPoint to, Map<Integer, Integer> logs)
	{
		boolean fromBalloon = BALLOON_FARES.stream().anyMatch(fare -> PositionRules.near(from, fare.getLanding(), BALLOON_TILES));
		for (BalloonFare fare : BALLOON_FARES)
		{
			boolean paidFromStorage = logs.getOrDefault(fare.getLog(), 0) >= lastLogs.getOrDefault(fare.getLog(), 0);
			if (fromBalloon && PositionRules.near(to, fare.getLanding(), LANDING_TILES) && paidFromStorage)
			{
				setStored(fare.getUnlock(), true);
			}
		}
	}

	/** Client thread. A balloon that refused to fly while its log wasn't carried: the log storage has run out of it. */
	public void onTeleportRefused(CatalogRow row)
	{
		for (BalloonFare fare : BALLOON_FARES)
		{
			if (fare.getLanding().equals(row.getDestination()) && carriedLogs().getOrDefault(fare.getLog(), 0) == 0)
			{
				setStored(fare.getUnlock(), false);
			}
		}
	}

	private void setStored(String unlock, boolean stored)
	{
		Set<String> unlocks = new HashSet<>(storedLogs.get().unlocks);
		if (stored ? unlocks.add(unlock) : unlocks.remove(unlock))
		{
			StoredLogs next = new StoredLogs();
			next.unlocks = unlocks;
			storedLogs.set(next);
			publish();
		}
	}

	private Map<Integer, Integer> carriedLogs()
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		Map<Integer, Integer> logs = new HashMap<>();
		for (BalloonFare fare : BALLOON_FARES)
		{
			logs.put(fare.getLog(), inventory == null ? 0 : inventory.count(fare.getLog()));
		}
		return logs;
	}

	private static WorldPoint tile(String cell)
	{
		String[] xyz = cell.split(" ");
		return new WorldPoint(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
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

	@Value
	private static class BalloonFare
	{
		WorldPoint landing;
		int log;
		String unlock;
	}

	static final class StoredLogs
	{
		Set<String> unlocks = new HashSet<>();
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
