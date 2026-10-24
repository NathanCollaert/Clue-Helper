package com.cluehelper.memory;

import com.cluehelper.player.AccountType;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

/**
 * The bank, group storage and huntsman's kit as last seen. Client thread, except the getters. Saved to the profile only
 * when the bank closes, the kit is seen outside the bank, and at logout: each save costs about 1 ms on the client thread
 * and a bank visit fires one event per withdraw.
 */
@Slf4j
@Singleton
public class BankMemory
{
	static final String BANK_KEY = "bank";
	static final String GROUP_STORAGE_KEY = "groupStorage";
	static final String PIN_KEY = "bankPin";
	static final String KIT_KEY = "huntsmansKit";
	private static final int NO_PLACEHOLDER = -1;
	private static final int NO_NOTE = -1;
	private static final String DEPOSIT = "Deposit";

	private final Client client;
	private final MemorySlot<Seen> storedBank;
	private final MemorySlot<Seen> storedGroup;
	private final MemorySlot<Pin> pin;
	private final MemorySlot<Seen> storedKit;
	private final Map<Integer, Boolean> placeholders = new HashMap<>();
	private final LateDeposits lateDeposits = new LateDeposits();

	private volatile Seen liveBank;
	private volatile Seen liveGroup;
	private volatile Seen liveKit;
	private boolean unsaved;
	/** The PIN is asked once per login: a bank or PIN screen seen since then means it's done. */
	private volatile boolean pinDoneThisSession;
	private volatile long version;
	/** Tracked from the bank's own load and close events: the bank widget can outlive a closed bank. */
	private volatile boolean bankOpen;

	@Inject
	BankMemory(Client client, MemoryStore memory)
	{
		this.client = client;
		this.storedBank = memory.slot(BANK_KEY, Seen.class, Seen::new);
		this.storedGroup = memory.slot(GROUP_STORAGE_KEY, Seen.class, Seen::new);
		this.pin = memory.slot(PIN_KEY, Pin.class, Pin::new);
		this.storedKit = memory.slot(KIT_KEY, Seen.class, Seen::new);
	}

	/** Null when this profile has never opened it. Any thread. */
	public Seen getBank()
	{
		Seen live = liveBank;
		return live != null ? live : seenOrNull(storedBank.get());
	}

	/** Null when never seen or not a group account. Any thread. */
	public Seen getGroupStorage()
	{
		Seen live = liveGroup;
		return live != null ? live : seenOrNull(storedGroup.get());
	}

	/** The huntsman's kit's traps and tools, as last sent when it was opened; null when never. Any thread. */
	public Seen getKit()
	{
		Seen live = liveKit;
		return live != null ? live : seenOrNull(storedKit.get());
	}

	/**
	 * These items plus the huntsman's kit's contents when the kit is among them: a carried kit's traps count as carried, a
	 * banked kit's as banked.
	 */
	public Map<Integer, Integer> withKit(Map<Integer, Integer> items)
	{
		Seen kit = items.containsKey(ItemID.HUNTSMANS_KIT) ? getKit() : null;
		if (kit == null)
		{
			return items;
		}
		Map<Integer, Integer> all = new HashMap<>(items);
		kit.items.forEach((id, quantity) -> all.merge(id, quantity, Integer::sum));
		return all;
	}

	/** Whether the item is only in the huntsman's kit, not loose in the bank: the kit is withdrawn for it. */
	public boolean onlyInBankedKit(int itemId)
	{
		Seen bank = getBank();
		Seen kit = getKit();
		return bank != null && kit != null && bank.items.containsKey(ItemID.HUNTSMANS_KIT) && !bank.items.containsKey(itemId)
			&& kit.items.containsKey(itemId);
	}

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
		if (event.getContainerId() == InventoryID.INV || event.getContainerId() == InventoryID.WORN)
		{
			lateDeposits.onCarried(carried(), client.getTickCount());
		}
		else if (event.getContainerId() == InventoryID.BANK)
		{
			lateDeposits.onBankSeen(client.getTickCount());
			liveBank = seen(event.getItemContainer());
			pinDoneThisSession = true;
			unsaved = true;
			version++;
		}
		else if (event.getContainerId() == InventoryID.INV_GROUP_TEMP && AccountType.of(client.getVarbitValue(VarbitID.IRONMAN)).isGroup()
			&& client.getVarbitValue(VarbitID.GIM_SHARED_BANK_HASEDITED) != 1)
		{
			liveGroup = seen(event.getItemContainer());
			unsaved = true;
			version++;
		}
		else if (event.getContainerId() == InventoryID.HUNTSMANS_KIT)
		{
			liveKit = seen(event.getItemContainer());
			unsaved = true;
			version++;
			if (!bankOpen)
			{
				save();
			}
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
			lateDeposits.onCarried(carried(), client.getTickCount());
		}
	}

	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		String option = event.getMenuOption();
		if (bankOpen && option != null && option.regionMatches(true, 0, DEPOSIT, 0, DEPOSIT.length()))
		{
			lateDeposits.onDepositClicked(client.getTickCount());
		}
	}

	/** Call before the planner's tick, so the plan never sees an unconfirmed deposit as missing. */
	public void onGameTick()
	{
		Map<Integer, Integer> deposited = lateDeposits.onTick();
		Seen bank = getBank();
		if (deposited.isEmpty() || bank == null)
		{
			return;
		}
		Seen updated = new Seen();
		updated.seenAt = bank.seenAt;
		updated.items = new HashMap<>(bank.items);
		deposited.forEach((id, quantity) -> updated.items.merge(id, quantity, Integer::sum));
		liveBank = updated;
		unsaved = true;
		version++;
		if (!bankOpen)
		{
			save();
		}
		log.debug("deposits without a bank update: {}", deposited);
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
		liveKit = null;
		unsaved = false;
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
		if (liveKit != null)
		{
			storedKit.set(liveKit);
		}
		log.debug("bank memory saved in {} ms", (System.nanoTime() - started) / 1e6);
	}

	/** Placeholders arrive as quantity 1 under a placeholder id, so they are told apart by definition. */
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

	/** Notes count as the items they stand for, as a deposit puts them in the bank. */
	private Map<Integer, Integer> carried()
	{
		Map<Integer, Integer> carried = new HashMap<>();
		for (int containerId : new int[]{InventoryID.INV, InventoryID.WORN})
		{
			ItemContainer container = client.getItemContainer(containerId);
			if (container == null)
			{
				continue;
			}
			for (Item item : container.getItems())
			{
				if (item.getId() > 0 && item.getQuantity() > 0)
				{
					carried.merge(unnoted(item.getId()), item.getQuantity(), Integer::sum);
				}
			}
		}
		return carried;
	}

	private int unnoted(int id)
	{
		ItemComposition item = client.getItemDefinition(id);
		return item.getNote() != NO_NOTE ? item.getLinkedNoteId() : id;
	}

	private boolean isPlaceholder(int id)
	{
		return placeholders.computeIfAbsent(id, i -> client.getItemDefinition(i).getPlaceholderTemplateId() != NO_PLACEHOLDER);
	}

	private static Seen seenOrNull(Seen stored)
	{
		return stored == null || stored.seenAt == 0 ? null : stored;
	}

	public static final class Seen
	{
		/** Epoch millis; 0 when never seen. */
		public long seenAt;
		public Map<Integer, Integer> items = new HashMap<>();
	}

	static final class Pin
	{
		boolean seen;
	}
}
