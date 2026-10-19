package com.cluehelper.transport;

import com.cluehelper.player.PlayerModel;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.player.RuneModel;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;

/**
 * Keeps {@code A_carried} current for the loaded catalog: judged again on every new snapshot, and when
 * a cooldown clears (no var changes then). {@code A_banked} stays empty until B15. Client thread,
 * except the getters.
 */
@Singleton
public class AvailabilityService
{
	private static final long MILLIS_PER_MINUTE = 60_000L;

	private final PlayerModel player;
	private final Client client;
	private final Map<Integer, String> itemNames = new ConcurrentHashMap<>();
	private final List<Consumer<AvailabilitySet>> listeners = new CopyOnWriteArrayList<>();

	private volatile List<CatalogRow> catalog = List.of();
	private volatile AvailabilitySet carried = AvailabilitySet.EMPTY;
	private volatile long lastJudgeNanos;
	private volatile boolean stale = true;
	private Runnable unregister;

	@Inject
	AvailabilityService(PlayerModel player, Client client)
	{
		this.player = player;
		this.client = client;
	}

	public void start()
	{
		unregister = player.addListener(snapshot -> stale = true);
	}

	public void stop()
	{
		if (unregister != null)
		{
			unregister.run();
			unregister = null;
		}
		carried = AvailabilitySet.EMPTY;
	}

	/** Any thread. The rows' vars are watched from the next tick on. */
	public void setCatalog(List<CatalogRow> rows)
	{
		Set<Integer> varbits = new HashSet<>();
		Set<Integer> varps = new HashSet<>();
		for (CatalogRow row : rows)
		{
			Requirements req = row.getRequirements();
			req.getVars().forEach(check -> (check.getKind() == VarCheck.Kind.VARBIT ? varbits : varps).add(check.getId()));
			req.getItems().stream().filter(clause -> clause.getWaivedBy() != null)
				.forEach(clause -> varbits.add(clause.getWaivedBy().getId()));
		}
		player.watch(varbits, varps);
		catalog = List.copyOf(rows);
		stale = true;
	}

	public List<CatalogRow> getCatalog()
	{
		return catalog;
	}

	/** From the plugin's GameTick handler, after the player model's. */
	public void onGameTick()
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		RuneModel runes = player.getRuneModel();
		if (snapshot == null || runes == null)
		{
			return;
		}
		long now = nowMinutes();
		if (stale || now >= carried.getValidUntilMinute())
		{
			stale = false;
			nameCatalogItems();
			long started = System.nanoTime();
			carried = new AvailabilityEngine(runes, this::itemName).carried(catalog, snapshot, now);
			lastJudgeNanos = System.nanoTime() - started;
			AvailabilitySet judged = carried;
			listeners.forEach(listener -> listener.accept(judged));
		}
	}

	/** Listeners run on the client thread, each time {@code A_carried} is judged again. */
	public Runnable addListener(Consumer<AvailabilitySet> listener)
	{
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	public AvailabilitySet getCarried()
	{
		return carried;
	}

	/** Empty until B15 remembers the bank. */
	public AvailabilitySet getBanked()
	{
		return AvailabilitySet.EMPTY;
	}

	public long getLastJudgeNanos()
	{
		return lastJudgeNanos;
	}

	public static long nowMinutes()
	{
		return System.currentTimeMillis() / MILLIS_PER_MINUTE;
	}

	private String itemName(int id)
	{
		return itemNames.getOrDefault(id, "item " + id);
	}

	private void nameCatalogItems()
	{
		for (CatalogRow row : catalog)
		{
			for (ItemClause clause : row.getRequirements().getItems())
			{
				for (ItemClause.Branch branch : clause.getBranches())
				{
					branch.getIds().forEach(id -> itemNames.computeIfAbsent(id, i -> client.getItemDefinition(i).getName()));
				}
			}
		}
	}
}
