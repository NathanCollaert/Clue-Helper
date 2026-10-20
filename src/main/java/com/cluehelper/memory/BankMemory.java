package com.cluehelper.memory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;

/**
 * The last-seen bank and GIM group storage of this profile, with the time each was seen (PLAN §5.2, B13). Every
 * bank event replaces the copy in memory; it is written to the profile only when the bank closes and at logout,
 * since each save costs about 1 ms on the client thread and a visit fires one event per withdraw. Also knows
 * whether the bank PIN is still to come this session (B14: 4 ticks on the first open). Client thread, except the
 * getters.
 */
@Slf4j
@Singleton
public class BankMemory
{
	static final String BANK_KEY = "bank";
	static final String GROUP_STORAGE_KEY = "groupStorage";
	static final String PIN_KEY = "bankPin";
	private static final Set<Integer> GROUP_IRONMAN = Set.of(4, 5, 6);
	private static final int NO_PLACEHOLDER = -1;

	private final Client client;
	private final MemorySlot<Seen> storedBank;
	private final MemorySlot<Seen> storedGroup;
	private final MemorySlot<Pin> pin;
	private final Map<Integer, Boolean> placeholders = new HashMap<>();

	private volatile Seen liveBank;
	private volatile Seen liveGroup;
	private boolean unsaved;
	/** The PIN is asked once per login: a bank or PIN screen seen since then means it's done. */
	private volatile boolean pinDoneThisSession;
	private volatile long version;
	/** From the bank's own load and close events: the bank widget can outlive a closed bank. */
	private volatile boolean bankOpen;

	@Inject
	BankMemory(Client client, MemoryStore memory)
	{
		this.client = client;
		this.storedBank = memory.slot(BANK_KEY, Seen.class, Seen::new);
		this.storedGroup = memory.slot(GROUP_STORAGE_KEY, Seen.class, Seen::new);
		this.pin = memory.slot(PIN_KEY, Pin.class, Pin::new);
	}

	/** The bank as last seen; null when this profile has never opened it. Any thread. */
	public Seen getBank()
	{
		Seen live = liveBank;
		return live != null ? live : seenOrNull(storedBank.get());
	}

	/** GIM group storage as last seen; null when never seen or not a group account. Any thread. */
	public Seen getGroupStorage()
	{
		Seen live = liveGroup;
		return live != null ? live : seenOrNull(storedGroup.get());
	}

	/** Whether the next bank open asks for the PIN: the keypad has been seen on this profile and not yet this session. */
	public boolean isPinPending()
	{
		return pin.get().seen && !pinDoneThisSession;
	}

	/** Changes whenever the remembered contents or the PIN state change, so a plan can tell it is stale. */
	public long getVersion()
	{
		return version;
	}

	public boolean isBankOpen()
	{
		return bankOpen;
	}

	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.BANK)
		{
			liveBank = seen(event.getItemContainer());
			pinDoneThisSession = true;
			unsaved = true;
			version++;
		}
		else if (event.getContainerId() == InventoryID.INV_GROUP_TEMP && GROUP_IRONMAN.contains(client.getVarbitValue(VarbitID.IRONMAN))
			&& client.getVarbitValue(VarbitID.GIM_SHARED_BANK_HASEDITED) != 1)
		{
			liveGroup = seen(event.getItemContainer());
			unsaved = true;
			version++;
		}
	}

	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.BANKPIN_KEYPAD && !pin.get().seen)
		{
			Pin seen = new Pin();
			seen.seen = true;
			pin.set(seen);
			version++;
		}
		else if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankOpen = true;
		}
	}

	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankOpen = false;
			save();
		}
		else if (event.getGroupId() == InterfaceID.BANKPIN_KEYPAD)
		{
			pinDoneThisSession = true;
			version++;
		}
	}

	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			save();
			bankOpen = false;
			pinDoneThisSession = false;
			version++;
		}
	}

	/** On {@code RuneScapeProfileChanged}, after the store reloads: the live copies belonged to the old profile. */
	public void onProfileChanged()
	{
		liveBank = null;
		liveGroup = null;
		unsaved = false;
		version++;
	}

	/** Forgets this profile's bank and group storage ({@code ::ch-bank clear}). */
	public void clear()
	{
		liveBank = null;
		liveGroup = null;
		unsaved = false;
		storedBank.clear();
		storedGroup.clear();
		version++;
	}

	private void save()
	{
		if (!unsaved)
		{
			return;
		}
		unsaved = false;
		long started = System.nanoTime();
		if (liveBank != null)
		{
			storedBank.set(liveBank);
		}
		if (liveGroup != null)
		{
			storedGroup.set(liveGroup);
		}
		log.debug("bank memory saved in {} ms", (System.nanoTime() - started) / 1e6);
	}

	/** Placeholders arrive as quantity 1 under a placeholder id, so they are told apart by definition (B13). */
	private Seen seen(ItemContainer container)
	{
		Seen seen = new Seen();
		seen.seenAt = System.currentTimeMillis();
		if (container != null)
		{
			for (Item item : container.getItems())
			{
				if (item.getId() > 0 && item.getQuantity() > 0 && !isPlaceholder(item.getId()))
				{
					seen.items.merge(item.getId(), item.getQuantity(), Integer::sum);
				}
			}
		}
		return seen;
	}

	private boolean isPlaceholder(int id)
	{
		return placeholders.computeIfAbsent(id, i -> client.getItemDefinition(i).getPlaceholderTemplateId() != NO_PLACEHOLDER);
	}

	private static Seen seenOrNull(Seen stored)
	{
		return stored == null || stored.seenAt == 0 ? null : stored;
	}

	/** A remembered container: item id → quantity, and when it was seen (epoch millis). */
	public static final class Seen
	{
		public long seenAt;
		public Map<Integer, Integer> items = new HashMap<>();
	}

	static final class Pin
	{
		boolean seen;
	}
}
