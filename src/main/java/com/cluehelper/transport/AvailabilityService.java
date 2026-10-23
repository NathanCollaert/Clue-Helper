package com.cluehelper.transport;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.memory.BankMemory;
import com.cluehelper.player.Charge;
import com.cluehelper.player.PlayerModel;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.player.RuneModel;
import java.util.HashMap;
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

/** Client thread, except the getters. Also judges again when a cooldown clears, since no var changes then. */
@Singleton
public class AvailabilityService
{
	private static final long MILLIS_PER_MINUTE = 60_000L;

	private static final int ULTIMATE_IRONMAN = 2;

	private final PlayerModel player;
	private final Client client;
	private final BankMemory bankMemory;
	private final ClueHelperConfig config;
	private final Set<Integer> catalogItems = new HashSet<>();
	private final Map<Integer, String> itemNames = new ConcurrentHashMap<>();
	private final List<Consumer<AvailabilitySet>> listeners = new CopyOnWriteArrayList<>();

	private volatile List<CatalogRow> catalog = List.of();
	private volatile AvailabilitySet carried = AvailabilitySet.EMPTY;
	private volatile AvailabilitySet banked = AvailabilitySet.EMPTY;
	private volatile PlayerSnapshot bankedSnapshot;
	private long judgedBankVersion = -1;
	private volatile long lastJudgeNanos;
	private volatile boolean stale = true;
	private Runnable unregister;

	@Inject
	AvailabilityService(PlayerModel player, Client client, BankMemory bankMemory, ClueHelperConfig config)
	{
		this.player = player;
		this.client = client;
		this.bankMemory = bankMemory;
		this.config = config;
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
		banked = AvailabilitySet.EMPTY;
		bankedSnapshot = null;
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

	/** Call after {@link PlayerModel#onGameTick()} on the same tick. */
	public void onGameTick()
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		RuneModel runes = player.getRuneModel();
		if (snapshot == null || runes == null)
		{
			return;
		}
		long now = nowMinutes();
		boolean bankChanged = bankMemory.getVersion() != judgedBankVersion;
		if (stale || bankChanged || now >= carried.getValidUntilMinute() || now >= banked.getValidUntilMinute())
		{
			stale = false;
			judgedBankVersion = bankMemory.getVersion();
			nameCatalogItems();
			long started = System.nanoTime();
			AvailabilityEngine engine = new AvailabilityEngine(runes, this::itemName);
			carried = engine.carried(catalog, snapshot, now);
			bankedSnapshot = withBank(snapshot);
			banked = bankedSnapshot == null ? AvailabilitySet.EMPTY : engine.carried(catalog, bankedSnapshot, now);
			lastJudgeNanos = System.nanoTime() - started;
			AvailabilitySet judged = carried;
			listeners.forEach(listener -> listener.accept(judged));
		}
	}

	/** Null when no bank counts. */
	private PlayerSnapshot withBank(PlayerSnapshot snapshot)
	{
		BankMemory.Seen bank = bankMemory.getBank();
		if (bank == null || !config.useBank() || snapshot.getAccountType() == ULTIMATE_IRONMAN)
		{
			return null;
		}
		BankMemory.Seen group = bankMemory.getGroupStorage();
		Set<Integer> stored = new HashSet<>(bank.items.keySet());
		if (group != null)
		{
			stored.addAll(group.items.keySet());
		}
		stored.retainAll(catalogItems);
		Map<Integer, Charge> charges = new HashMap<>(player.charges(stored));
		charges.putAll(snapshot.getCharges());
		return snapshot.toBuilder()
			.bank(bankMemory.withKit(bank.items))
			.groupStorage(group == null ? Map.of() : group.items)
			.charges(charges)
			.build();
	}

	/** Listeners run on the client thread, each time the catalog is judged again. */
	public Runnable addListener(Consumer<AvailabilitySet> listener)
	{
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	public AvailabilitySet getCarried()
	{
		return carried;
	}

	/** Empty when no bank counts. */
	public AvailabilitySet getBanked()
	{
		return banked;
	}

	/** Null when no bank counts. */
	public PlayerSnapshot getBankedSnapshot()
	{
		return bankedSnapshot;
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
					branch.getIds().forEach(id ->
					{
						catalogItems.add(id);
						itemNames.computeIfAbsent(id, i -> client.getItemDefinition(i).getName());
					});
				}
			}
		}
	}
}
