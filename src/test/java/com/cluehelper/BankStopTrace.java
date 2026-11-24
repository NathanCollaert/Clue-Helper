package com.cluehelper;

import com.google.gson.Gson;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Times every bank stop, for the costs the planner gives one (PlanContext's bank stop, per-item withdraw, PIN and
 * deposit-first half-ticks): one {@link Stopped} line each in {@link #LOG}, summed up by item stacks and PIN in
 * {@link #SUMMARY}. A stop runs from the player standing at the bank to the first click that is neither bank work nor
 * wearing an item: the next transport's first click, or a walk.
 *
 * <p>Ticks: as in {@link CrossingTrace}, the player stands at the bank from boundary S + 1, S the GameTick count they
 * were first seen there, or from the bank click's own period when already there; a click in period c is c - start ticks
 * into the stop. The bank and the PIN keypad opening are server effects, seen with the count of the period before.
 */
@Slf4j
final class BankStopTrace
{
	static final String TAG = "[CH-BANK]";
	static final File LOG = new File(TimingLog.DIR, "bank-stops.jsonl");
	static final File SUMMARY = new File(TimingLog.DIR, "bank-stops.tsv");
	private static final String BANK = "Bank";
	private static final String USE = "Use";
	private static final Set<String> WEAR_OPTIONS = Set.of("Wear", "Wield", "Equip", "Remove");
	private static final Set<Integer> BANK_GROUPS = Set.of(InterfaceID.BANKMAIN, InterfaceID.BANKSIDE, InterfaceID.BANKPIN_KEYPAD);
	private static final int GIVE_UP_TICKS = 50;
	private static final int ABANDON_TICKS = 300;

	private enum Phase
	{
		IDLE,
		/** Clicked the bank; walking there or waiting for it to open. */
		GOING,
		/** Open, or worked and closed; waiting for the click that ends the stop. */
		BANKING
	}

	private final Client client;
	private final Gson gson;
	private final TimingLog timingLog;
	private Phase phase = Phase.IDLE;
	private Stopped stopped;
	private WorldPoint tileAtClick;
	private final Map<WorldPoint, Integer> firstSeen = new HashMap<>();
	private Map<Integer, Integer> carriedAtClick = Map.of();

	BankStopTrace(Client client, Gson gson, TimingLog timingLog)
	{
		this.client = client;
		this.gson = gson;
		this.timingLog = timingLog;
	}

	@Subscribe(priority = -3)
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		String option = Text.removeTags(event.getMenuOption());
		if (phase == Phase.BANKING && !isBankWork(event, option))
		{
			finish();
		}
		else if (phase == Phase.BANKING)
		{
			stopped.clicks++;
		}
		else if (isBankClick(event, option, Text.removeTags(event.getMenuTarget())))
		{
			begin(option + " " + Text.removeTags(event.getMenuTarget()));
		}
	}

	@Subscribe(priority = -3)
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (phase == Phase.GOING && event.getGroupId() == InterfaceID.BANKMAIN)
		{
			opened();
		}
		else if (phase != Phase.IDLE && event.getGroupId() == InterfaceID.BANKPIN_KEYPAD)
		{
			stopped.pinOpenTick = client.getTickCount();
		}
	}

	@Subscribe(priority = -3)
	public void onWidgetClosed(WidgetClosed event)
	{
		if (phase != Phase.IDLE && event.getGroupId() == InterfaceID.BANKPIN_KEYPAD && stopped.pinOpenTick != 0)
		{
			stopped.pinTicks = client.getTickCount() - stopped.pinOpenTick;
		}
	}

	@Subscribe(priority = -3)
	public void onGameTick(GameTick tick)
	{
		int now = client.getTickCount();
		if (phase == Phase.GOING)
		{
			firstSeen.putIfAbsent(PlayerTiles.tile(client), now);
			if (now - stopped.clickTick > GIVE_UP_TICKS)
			{
				phase = Phase.IDLE;
			}
		}
		else if (phase == Phase.BANKING && now - stopped.startTick > ABANDON_TICKS)
		{
			phase = Phase.IDLE;
		}
	}

	private static boolean isBankClick(MenuOptionClicked event, String option, String target)
	{
		boolean bankChest = option.equals(USE) && event.getMenuAction() == MenuAction.GAME_OBJECT_FIRST_OPTION && target.contains(BANK);
		return option.equals(BANK) || bankChest;
	}

	private static boolean isBankWork(MenuOptionClicked event, String option)
	{
		return BANK_GROUPS.contains(event.getParam1() >>> 16) || WEAR_OPTIONS.contains(option);
	}

	private void begin(String click)
	{
		stopped = new Stopped();
		stopped.click = click;
		stopped.clickTick = client.getTickCount();
		tileAtClick = PlayerTiles.tile(client);
		firstSeen.clear();
		carriedAtClick = carried();
		phase = Phase.GOING;
	}

	private void opened()
	{
		WorldPoint tile = PlayerTiles.tile(client);
		stopped.openTick = client.getTickCount();
		stopped.startTick = Objects.equals(tile, tileAtClick) ? stopped.clickTick : firstSeen.getOrDefault(tile, stopped.openTick) + 1;
		stopped.standToOpen = stopped.openTick + 1 - stopped.startTick;
		stopped.at = PlayerTiles.text(tile);
		phase = Phase.BANKING;
	}

	private void finish()
	{
		stopped.endTick = client.getTickCount();
		stopped.stopTicks = stopped.endTick - stopped.startTick;
		stopped.itemStacks = changedItems(carriedAtClick, carried());
		String line = gson.toJson(stopped);
		log.info("{} {}", TAG, line);
		timingLog.append(LOG, line, SUMMARY, lines -> BankStopTimings.tsv(lines.stream()
			.map(logged -> gson.fromJson(logged, Stopped.class))
			.collect(Collectors.toList())));
		stopped = null;
		phase = Phase.IDLE;
	}

	/** Quantities by item id, carried and worn apart: worn ids are negated. */
	private Map<Integer, Integer> carried()
	{
		Map<Integer, Integer> quantities = new HashMap<>();
		addItems(quantities, client.getItemContainer(InventoryID.INV), 1);
		addItems(quantities, client.getItemContainer(InventoryID.WORN), -1);
		return quantities;
	}

	private static void addItems(Map<Integer, Integer> quantities, ItemContainer container, int sign)
	{
		if (container == null)
		{
			return;
		}
		for (Item item : container.getItems())
		{
			if (item.getId() > 0)
			{
				quantities.merge(sign * item.getId(), item.getQuantity(), Integer::sum);
			}
		}
	}

	/** Item ids whose carried or worn quantity changed: a stack withdrawn, deposited or put on counts once. */
	private static int changedItems(Map<Integer, Integer> before, Map<Integer, Integer> after)
	{
		Set<Integer> ids = new HashSet<>(before.keySet());
		ids.addAll(after.keySet());
		return (int) ids.stream()
			.filter(id -> !Objects.equals(before.get(id), after.get(id)))
			.map(Math::abs)
			.distinct()
			.count();
	}

	/** One line of the log. */
	static final class Stopped
	{
		/** The bank click, as the menu showed it. */
		String click;
		String at;
		int clickTick;
		/** The boundary from which the player stood at the bank. */
		int startTick;
		int openTick;
		int standToOpen;
		/** 0 when no PIN was asked. */
		int pinOpenTick;
		int pinTicks;
		/** Bank and wear clicks during the stop. */
		int clicks;
		int itemStacks;
		int endTick;
		/** Standing at the bank to the click that ended the stop. */
		int stopTicks;
	}
}
