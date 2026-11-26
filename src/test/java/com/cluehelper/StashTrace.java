package com.cluehelper;

import com.google.gson.Gson;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.cluescrolls.clues.emote.STASHUnit;
import net.runelite.client.util.Text;

/**
 * Times every Search of a STASH unit to the line saying the items came out or went in, against the window StashMemory
 * waits for that line (0-3 ticks, 8 when the session's first Search asks for the PIN): one {@link Searched} line each
 * in {@link #LOG}, summed up by withdraw or deposit and PIN in {@link #SUMMARY}.
 *
 * <p>Ticks: a click carries the count of the period it happens in, the line the count of the period before its boundary,
 * so click to line = line + 1 - click; standing at the unit is counted as in {@link CrossingTrace}. StashMemory's window
 * is sampled into {@link WaitWindows} as it counts it: line - click.
 */
@Slf4j
final class StashTrace
{
	static final String TAG = "[CH-STASH-TIME]";
	static final File LOG = new File(TimingLog.DIR, "stash.jsonl");
	static final File SUMMARY = new File(TimingLog.DIR, "stash.tsv");
	private static final String SEARCH = "Search";
	private static final String WITHDRAWN = "You withdraw your items from the STASH unit.";
	private static final String DEPOSITED = "You deposit your items into the STASH unit.";
	private static final Set<Integer> UNIT_OBJECTS = Arrays.stream(STASHUnit.values()).map(STASHUnit::getObjectId)
		.collect(Collectors.toSet());
	private static final int GIVE_UP_TICKS = 100;

	private final Client client;
	private final Gson gson;
	private final TimingLog timingLog;
	private final WaitWindows windows;
	private Searched searched;
	private WorldPoint tileAtClick;
	private final Map<WorldPoint, Integer> firstSeen = new HashMap<>();

	StashTrace(Client client, Gson gson, TimingLog timingLog, WaitWindows windows)
	{
		this.client = client;
		this.gson = gson;
		this.timingLog = timingLog;
		this.windows = windows;
	}

	@Subscribe(priority = -3)
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (Text.removeTags(event.getMenuOption()).equals(SEARCH) && UNIT_OBJECTS.contains(event.getId()))
		{
			searched = new Searched();
			searched.unit = event.getId();
			searched.clickTick = client.getTickCount();
			tileAtClick = PlayerTiles.tile(client);
			firstSeen.clear();
		}
	}

	@Subscribe(priority = -3)
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (searched != null && event.getGroupId() == InterfaceID.BANKPIN_KEYPAD)
		{
			searched.pin = true;
		}
	}

	@Subscribe(priority = -3)
	public void onChatMessage(ChatMessage event)
	{
		if (searched == null)
		{
			return;
		}
		String text = Text.removeTags(event.getMessage());
		if (text.equals(WITHDRAWN) || text.equals(DEPOSITED))
		{
			searched.withdrew = text.equals(WITHDRAWN);
			finish();
		}
		else
		{
			searched.otherLines.add(text);
		}
	}

	@Subscribe(priority = -3)
	public void onGameTick(GameTick tick)
	{
		if (searched == null)
		{
			return;
		}
		firstSeen.putIfAbsent(PlayerTiles.tile(client), client.getTickCount());
		if (client.getTickCount() - searched.clickTick > GIVE_UP_TICKS)
		{
			searched = null;
		}
	}

	private void finish()
	{
		int line = client.getTickCount();
		WorldPoint tile = PlayerTiles.tile(client);
		searched.at = PlayerTiles.text(tile);
		searched.lineTick = line;
		searched.clickToLine = line + 1 - searched.clickTick;
		int standing = Objects.equals(tile, tileAtClick) ? searched.clickTick : firstSeen.getOrDefault(tile, line) + 1;
		searched.standToLine = line + 1 - standing;
		windows.sample(WaitWindows.Window.STASH_LINE, line - searched.clickTick, (searched.withdrew ? "withdraw" : "deposit")
			+ (searched.pin ? " with PIN" : ""));
		String json = gson.toJson(searched);
		log.info("{} {}", TAG, json);
		timingLog.append(LOG, json, SUMMARY, lines -> StashTimings.tsv(lines.stream()
			.map(logged -> gson.fromJson(logged, Searched.class))
			.collect(Collectors.toList())));
		searched = null;
	}

	/** One line of the log. */
	static final class Searched
	{
		int unit;
		String at;
		boolean withdrew;
		boolean pin;
		int clickTick;
		int lineTick;
		int clickToLine;
		int standToLine;
		/** Game lines between the click and the STASH's own, such as a refusal. */
		List<String> otherLines = new ArrayList<>();
	}
}
