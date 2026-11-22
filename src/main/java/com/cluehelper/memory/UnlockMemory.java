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
import net.runelite.client.util.Text;

/**
 * Client thread. Account unlocks the game keeps no var for, learned from play and named as the catalog's
 * {@code UNLOCK_} tokens name them. Each is shown by a trip that lands by its landing without dropping its fare item
 * ({@code landing unlocks} in tables.tsv):
 * <ul>
 * <li>Xeric's Honour: the talisman's menu shows its line the same whether it is unlocked or not, but no other teleport
 * lands on that spot. The chat line when an ancient tablet unlocks it shows it too.</li>
 * <li>Dragontooth Island: the ghost captain's fare is 25 ecto-tokens until a one-off 500 unlocks free trips.</li>
 * <li>Logs in the balloons' log storage, which pays a flight's log when it holds one; a refused flight without the log
 * carried shows it is empty again.</li>
 * </ul>
 */
@Singleton
public class UnlockMemory
{
	/** Named when it held only the balloons' stored logs. */
	static final String UNLOCKS_KEY = "balloonStoredLogs";
	static final String XERICS_HONOUR_KEY = "xericsHonour";
	static final String DRAGONTOOTH_KEY = "dragontoothFreeTrips";
	private static final String XERICS_HONOUR = "XERICS_HONOUR";
	private static final String DRAGONTOOTH = "DRAGONTOOTH";
	private static final String FROM_BALLOON = "balloon";
	private static final String TABLET_USED = "As the talisman and tablet are held together, a surge of power connects the two.";
	/** The real Honour landing (1254 3563) is 3 tiles north of the catalog's; a little more allows for a spread. */
	private static final int LANDING_TILES = 5;
	private static final List<LandingUnlock> LANDING_UNLOCKS = Tables.rows("landing unlocks").stream()
		.map(row -> new LandingUnlock(tile(row.getKey()), row.id(0), row.getCells().get(1), row.getCells().get(2).equals(FROM_BALLOON)))
		.collect(Collectors.toUnmodifiableList());
	/** A flight starts beside another balloon; a teleport scroll that lands near one (the Lumber Yard's) does not. */
	private static final int BALLOON_TILES = 15;

	@Inject
	Client client;
	MemorySlot<Learned> learned;
	MemorySlot<Seen> legacyXericsHonour;
	MemorySlot<Seen> legacyDragontooth;
	WorldPoint lastTile;
	Map<Integer, Integer> lastFares = Map.of();

	@Inject
	void init(MemoryStore memory)
	{
		learned = memory.slot(UNLOCKS_KEY, Learned.class, Learned::new);
		legacyXericsHonour = memory.slot(XERICS_HONOUR_KEY, Seen.class, Seen::new);
		legacyDragontooth = memory.slot(DRAGONTOOTH_KEY, Seen.class, Seen::new);
	}

	public Set<String> unlocks()
	{
		Set<String> unlocks = new HashSet<>(learned.get().unlocks);
		if (legacyXericsHonour.get().learnedAt > 0)
		{
			unlocks.add(XERICS_HONOUR);
		}
		if (legacyDragontooth.get().learnedAt > 0)
		{
			unlocks.add(DRAGONTOOTH);
		}
		return unlocks;
	}

	/** Call before the player model's GameTick handler, so its snapshot has fresh unlocks. */
	public void onGameTick()
	{
		Player local = client.getLocalPlayer();
		WorldPoint tile = local == null ? null : WorldPoint.fromLocalInstance(client, local.getLocalLocation());
		Map<Integer, Integer> fares = carriedFares();
		if (PositionRules.teleported(lastTile, tile))
		{
			learnFromLanding(lastTile, tile, fares);
		}
		lastTile = tile;
		lastFares = fares;
	}

	public void onChatMessage(ChatMessage message)
	{
		if (message.getType() == ChatMessageType.GAMEMESSAGE && Text.removeTags(message.getMessage()).startsWith(TABLET_USED))
		{
			setLearned(XERICS_HONOUR, true);
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

	private void learnFromLanding(WorldPoint from, WorldPoint to, Map<Integer, Integer> fares)
	{
		boolean fromBalloon = LANDING_UNLOCKS.stream().anyMatch(unlock -> unlock.fromBalloon && PositionRules.near(from, unlock.landing, BALLOON_TILES));
		for (LandingUnlock unlock : LANDING_UNLOCKS)
		{
			boolean farePaidElsewhere = fares.getOrDefault(unlock.fare, 0) >= lastFares.getOrDefault(unlock.fare, 0);
			if ((fromBalloon || !unlock.fromBalloon) && PositionRules.near(to, unlock.landing, LANDING_TILES) && farePaidElsewhere)
			{
				setLearned(unlock.name, true);
			}
		}
	}

	/** Client thread. A balloon that refused to fly while its log wasn't carried: the log storage has run out of it. */
	public void onTeleportRefused(CatalogRow row)
	{
		for (LandingUnlock unlock : LANDING_UNLOCKS)
		{
			if (unlock.fromBalloon && unlock.landing.equals(row.getDestination()) && carriedFares().getOrDefault(unlock.fare, 0) == 0)
			{
				setLearned(unlock.name, false);
			}
		}
	}

	private void setLearned(String unlock, boolean known)
	{
		Set<String> unlocks = new HashSet<>(learned.get().unlocks);
		if (known ? unlocks.add(unlock) : unlocks.remove(unlock))
		{
			Learned next = new Learned();
			next.unlocks = unlocks;
			learned.set(next);
		}
	}

	private Map<Integer, Integer> carriedFares()
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		Map<Integer, Integer> fares = new HashMap<>();
		for (LandingUnlock unlock : LANDING_UNLOCKS)
		{
			fares.put(unlock.fare, inventory == null ? 0 : inventory.count(unlock.fare));
		}
		return fares;
	}

	private static WorldPoint tile(String cell)
	{
		String[] xyz = cell.split(" ");
		return new WorldPoint(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
	}

	@Value
	private static class LandingUnlock
	{
		WorldPoint landing;
		/** 0 for a trip with no fare. */
		int fare;
		String name;
		boolean fromBalloon;
	}

	static final class Learned
	{
		Set<String> unlocks = new HashSet<>();
	}

	/** Xeric's Honour's and Dragontooth's own keys before {@link #UNLOCKS_KEY} held every unlock. */
	static final class Seen
	{
		long learnedAt;
	}
}
