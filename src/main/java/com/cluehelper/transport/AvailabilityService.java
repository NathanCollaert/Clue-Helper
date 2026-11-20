package com.cluehelper.transport;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.memory.BankMemory;
import com.cluehelper.player.*;
import java.util.*;
import javax.inject.*;
import lombok.Getter;

/** Client thread, except the getters. Also judges again when a cooldown clears, since no var changes then. */
@Singleton
public class AvailabilityService
{
	private static final long MILLIS_PER_MINUTE = 60_000L;

	@Inject
	private PlayerModel player;
	@Inject
	private BankMemory bankMemory;
	@Inject
	private ClueHelperConfig config;
	private volatile Set<Integer> catalogItems = Set.of();
	/** The catalog's items and the walk gates' (a long rope), for no-route notes. */
	private volatile Map<Integer, String> itemNames = Map.of();

	@Getter
	private volatile List<CatalogRow> catalog = List.of();
	@Getter
	private volatile AvailabilitySet carried = AvailabilitySet.EMPTY;
	/** Empty when no bank counts. */
	@Getter
	private volatile AvailabilitySet banked = AvailabilitySet.EMPTY;
	/** Null when no bank counts. */
	@Getter
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
	public void setCatalog(Catalog loaded)
	{
		player.watch(loaded.getWatchedVarbits(), loaded.getWatchedVarps());
		catalogItems = loaded.getItems();
		itemNames = loaded.getItemNames();
		catalog = loaded.getTeleports();
		stale = true;
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
			AvailabilityEngine engine = new AvailabilityEngine(runes, this::itemName);
			carried = engine.carried(catalog, snapshot, now);
			bankedSnapshot = withBank(snapshot);
			banked = bankedSnapshot == null ? AvailabilitySet.EMPTY : engine.carried(catalog, bankedSnapshot, now);
		}
	}

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

	public static long nowMinutes()
	{
		return System.currentTimeMillis() / MILLIS_PER_MINUTE;
	}

	/** Any thread. */
	public String itemName(int id)
	{
		return itemNames.getOrDefault(id, "item " + id);
	}
}
