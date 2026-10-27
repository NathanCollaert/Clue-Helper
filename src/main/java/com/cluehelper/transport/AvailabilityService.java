package com.cluehelper.transport;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.memory.BankMemory;
import com.cluehelper.player.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.inject.*;
import net.runelite.api.Client;

/** Client thread, except the getters. Also judges again when a cooldown clears, since no var changes then. */
@Singleton
public class AvailabilityService
{
	private static final long MILLIS_PER_MINUTE = 60_000L;

	@Inject
	private PlayerModel player;
	@Inject
	private Client client;
	@Inject
	private BankMemory bankMemory;
	@Inject
	private ClueHelperConfig config;
	private final Set<Integer> catalogItems = new HashSet<>();
	private final Map<Integer, String> itemNames = new ConcurrentHashMap<>();
	private final List<Consumer<AvailabilitySet>> listeners = new CopyOnWriteArrayList<>();

	private volatile List<CatalogRow> catalog = List.of();
	private volatile AvailabilitySet carried = AvailabilitySet.EMPTY;
	private volatile AvailabilitySet banked = AvailabilitySet.EMPTY;
	private volatile PlayerSnapshot bankedSnapshot;
	private long judgedBankVersion = -1;
	private volatile boolean stale = true;
	private Runnable unregister;

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
			AvailabilityEngine engine = new AvailabilityEngine(runes, this::itemName);
			carried = engine.carried(catalog, snapshot, now);
			bankedSnapshot = withBank(snapshot);
			banked = bankedSnapshot == null ? AvailabilitySet.EMPTY : engine.carried(catalog, bankedSnapshot, now);
			AvailabilitySet judged = carried;
			listeners.forEach(listener -> listener.accept(judged));
		}
	}

	/** Null when no bank counts. */
	/** The bank is part of planning while the setting is on and the account has one. */
	public boolean bankCounts(PlayerSnapshot snapshot)
	{
		return config.useBank() && snapshot.getAccountType().hasBank();
	}

	private PlayerSnapshot withBank(PlayerSnapshot snapshot)
	{
		BankMemory.Seen bank = bankMemory.getBank();
		if (bank == null || !bankCounts(snapshot))
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
			.bank(player.counted(bankMemory.withKit(bank.items)))
			.groupStorage(group == null ? Map.of() : player.counted(group.items))
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
