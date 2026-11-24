package com.cluehelper;

import com.cluehelper.route.BookScrolls;
import com.google.gson.Gson;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Times the master scroll book at a bank, for the two costs BookScrolls gives it: a take-out (close the bank, open the
 * book, take a scroll out, open the bank again, deposit the book) and a plain deposit. One {@link Handled} line each in
 * {@link #LOG}, summed up per kind in {@link #SUMMARY}. A take-out runs from the bank closing (or the first book click,
 * when the bank was not open just before) to the book leaving the inventory; a deposit from its click.
 *
 * <p>Ticks: the bank closes in the period of the click that closes it, and a click carries that period's count; the
 * book leaving is a server effect seen with the count of the period before its boundary, so start to gone = gone + 1 -
 * start.
 */
@Slf4j
final class BookScrollTrace
{
	static final String TAG = "[CH-SCROLLS]";
	static final File LOG = new File(TimingLog.DIR, "scroll-book.jsonl");
	static final File SUMMARY = new File(TimingLog.DIR, "scroll-book.tsv");
	private static final String DEPOSIT = "Deposit";
	/** A bank closed this recently was closed to take a scroll out. */
	private static final int CLOSE_REACH_TICKS = 5;
	private static final int GIVE_UP_TICKS = 100;
	private static final int NEVER = Integer.MIN_VALUE / 2;

	private final Client client;
	private final Gson gson;
	private final TimingLog timingLog;
	private Handled handled;
	private Set<Integer> carriedAtStart = Set.of();
	private int bankClosedTick = NEVER;

	BookScrollTrace(Client client, Gson gson, TimingLog timingLog)
	{
		this.client = client;
		this.gson = gson;
		this.timingLog = timingLog;
	}

	@Subscribe(priority = -3)
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (event.getItemId() != BookScrolls.BOOK)
		{
			if (handled != null)
			{
				handled.clicks.add("t" + client.getTickCount() + " " + Text.removeTags(event.getMenuOption()));
			}
			return;
		}
		String option = Text.removeTags(event.getMenuOption());
		boolean bankOpen = client.getWidget(InterfaceID.BANKMAIN, 0) != null;
		if (handled == null)
		{
			begin(bankOpen && option.startsWith(DEPOSIT));
		}
		handled.clicks.add("t" + client.getTickCount() + " " + option + " book");
	}

	@Subscribe(priority = -3)
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankClosedTick = client.getTickCount();
		}
	}

	@Subscribe(priority = -3)
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (handled != null && event.getGroupId() == InterfaceID.BANKMAIN && handled.bankReopenedTick == 0)
		{
			handled.bankReopenedTick = client.getTickCount();
		}
	}

	@Subscribe(priority = -3)
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (handled == null || event.getContainerId() != InventoryID.INV)
		{
			return;
		}
		Set<Integer> carried = carriedIds();
		if (handled.scrollTick == 0 && !carriedAtStart.containsAll(carried))
		{
			handled.scrollTick = client.getTickCount();
		}
		if (!carried.contains(BookScrolls.BOOK))
		{
			finish();
		}
	}

	@Subscribe(priority = -3)
	public void onGameTick(GameTick tick)
	{
		if (handled != null && client.getTickCount() - handled.startTick > GIVE_UP_TICKS)
		{
			handled = null;
		}
	}

	private void begin(boolean depositOnly)
	{
		int now = client.getTickCount();
		handled = new Handled();
		handled.depositOnly = depositOnly;
		handled.startTick = !depositOnly && now - bankClosedTick <= CLOSE_REACH_TICKS ? bankClosedTick : now;
		handled.bankWasClosed = handled.startTick != now;
		carriedAtStart = carriedIds();
	}

	private void finish()
	{
		handled.goneTick = client.getTickCount();
		handled.ticks = handled.goneTick + 1 - handled.startTick;
		String line = gson.toJson(handled);
		log.info("{} {}", TAG, line);
		timingLog.append(LOG, line, SUMMARY, lines -> tsv(lines.stream()
			.map(logged -> gson.fromJson(logged, Handled.class))
			.collect(Collectors.toList())));
		handled = null;
	}

	private Set<Integer> carriedIds()
	{
		Set<Integer> ids = new HashSet<>();
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory != null)
		{
			for (Item item : inventory.getItems())
			{
				ids.add(item.getId());
			}
		}
		return ids;
	}

	/** One row per kind: a take-out that started at the bank closing, one that didn't, and a plain deposit. */
	private static String tsv(List<Handled> all)
	{
		StringBuilder out = new StringBuilder("kind\tbank_was_closed\ttimes\tticks\tto_scroll\tto_bank_reopened\n");
		all.stream()
			.collect(Collectors.groupingBy(handled -> (handled.depositOnly ? "deposit" : "take-out") + "\t" + handled.bankWasClosed))
			.forEach((key, times) -> out.append(String.join("\t", key, String.valueOf(times.size()),
				TimingLog.range(times.stream().mapToLong(handled -> handled.ticks)),
				TimingLog.range(times.stream().filter(handled -> handled.scrollTick != 0).mapToLong(handled -> handled.scrollTick + 1 - handled.startTick)),
				TimingLog.range(times.stream().filter(handled -> handled.bankReopenedTick != 0)
					.mapToLong(handled -> handled.bankReopenedTick + 1 - handled.startTick)))).append('\n'));
		return out.toString();
	}

	/** One line of the log. A tick stays 0 when it never came. */
	static final class Handled
	{
		boolean depositOnly;
		/** The take-out started at the bank closing, not at the first book click. */
		boolean bankWasClosed;
		int startTick;
		/** A new item, the scroll, came into the inventory. */
		int scrollTick;
		int bankReopenedTick;
		int goneTick;
		/** Start to the book leaving the inventory. */
		int ticks;
		List<String> clicks = new ArrayList<>();
	}
}
