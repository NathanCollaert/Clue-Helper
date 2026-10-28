package com.cluehelper.memory;

import com.cluehelper.player.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.util.Text;

/**
 * Client thread. Turning the dials without travelling doesn't change a ring's Last-destination, so the code is the
 * dials as they stood at the confirm, kept once the player lands. A "Last-destination (XYZ)" menu entry corrects it.
 */
@Singleton
public class FairyRingMemory
{
	static final String KEY = "fairyRing";
	/** Each dial's letters by its varbit value, which follows the turning order. */
	private static final String[] LEFT_DIAL = {"A", "D", "C", "B"};
	private static final String[] MIDDLE_DIAL = {"I", "L", "K", "J"};
	private static final String[] RIGHT_DIAL = {"P", "S", "R", "Q"};
	private static final Pattern LAST_DESTINATION = Pattern.compile("last-destination \\(([A-Z]{3})\\)", Pattern.CASE_INSENSITIVE);
	/** A ring teleport lands about 7 ticks after the confirm, arrival lock included. */
	private static final int LANDING_WITHIN_TICKS = 12;

	@Inject
	private Client client;
	@Inject
	private PlayerModel player;
	private MemorySlot<Last> remembered;
	private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
	private String pending;
	private int pendingTick;
	private WorldPoint pendingFrom;
	private String published;

	@Inject
	void init(MemoryStore memory)
	{
		remembered = memory.slot(KEY, Last.class, Last::new);
	}

	public Runnable addListener(Consumer<String> devTraceLines)
	{
		listeners.add(devTraceLines);
		return () -> listeners.remove(devTraceLines);
	}

	/** Call on start and after a profile change. */
	public void publish()
	{
		String code = remembered.get().code;
		if (code != null && !code.equals(published))
		{
			published = code;
			player.setLastFairyRing(code);
			tell("last destination " + code);
		}
	}

	/** At the confirm the dials show where the ring sends the player, also after picking a travel-log line. */
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		Matcher last = LAST_DESTINATION.matcher(Text.removeTags(event.getMenuOption()));
		if (last.find())
		{
			arm(last.group(1).toUpperCase());
		}
		else if (event.getParam1() == InterfaceID.Fairyrings.CONFIRM)
		{
			arm(dials());
		}
	}

	/** A "Last-destination (XYZ)" entry is the game's own word, so it wins over what travel taught. */
	public void onMenuEntries(MenuEntry[] entries)
	{
		for (MenuEntry entry : entries)
		{
			Matcher last = LAST_DESTINATION.matcher(Text.removeTags(entry.getOption() == null ? "" : entry.getOption()));
			if (last.find())
			{
				remember(last.group(1).toUpperCase(), "menu");
				return;
			}
		}
	}

	/** A confirmed teleport counts once the player has jumped; one the game refused never does. */
	public void onGameTick()
	{
		if (pending == null)
		{
			return;
		}
		WorldPoint at = position();
		boolean jumped = PositionRules.teleported(pendingFrom, at);
		if (jumped)
		{
			remember(pending, "travel");
		}
		if (jumped || client.getTickCount() - pendingTick > LANDING_WITHIN_TICKS)
		{
			pending = null;
		}
	}

	String dials()
	{
		return LEFT_DIAL[client.getVarbitValue(VarbitID.FAIRYRING_1) & 3] + MIDDLE_DIAL[client.getVarbitValue(VarbitID.FAIRYRING_2) & 3]
			+ RIGHT_DIAL[client.getVarbitValue(VarbitID.FAIRYRING_3) & 3];
	}

	private void arm(String code)
	{
		pending = code;
		pendingTick = client.getTickCount();
		pendingFrom = position();
		tell("travelling to " + code + " (dials " + dials() + ")");
	}

	private void remember(String code, String source)
	{
		if (!code.equals(remembered.get().code))
		{
			Last next = new Last();
			next.code = code;
			next.seenAt = System.currentTimeMillis();
			remembered.set(next);
			tell(code + " (" + source + ")");
		}
		publish();
	}

	private WorldPoint position()
	{
		Player local = client.getLocalPlayer();
		return local == null ? null : WorldPoint.fromLocalInstance(client, local.getLocalLocation());
	}

	private void tell(String line)
	{
		listeners.forEach(listener -> listener.accept(line));
	}

	static final class Last
	{
		String code;
		long seenAt;
	}
}
