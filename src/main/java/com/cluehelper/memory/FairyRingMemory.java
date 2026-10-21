package com.cluehelper.memory;

import com.cluehelper.player.PlayerModel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.util.Text;

/**
 * The fairy ring code the player last travelled to: what a ring's "Last-destination" goes to (B17). The dials alone
 * aren't it (Nathan: turning them without travelling doesn't change the last destination), so it is learned on travel:
 * the dials as they stood when the teleport was confirmed, kept once the player lands. A "Last-destination (XYZ)" seen in
 * a menu corrects it. Remembered per profile. Client thread.
 */
@Singleton
public class FairyRingMemory
{
	static final String KEY = "fairyRing";
	/** Each dial's letters by its varbit value, as the dials turn. */
	private static final String[] LEFT_DIAL = {"A", "D", "C", "B"};
	private static final String[] MIDDLE_DIAL = {"I", "L", "K", "J"};
	private static final String[] RIGHT_DIAL = {"P", "S", "R", "Q"};
	private static final Pattern LAST_DESTINATION = Pattern.compile("last-destination \\(([A-Z]{3})\\)", Pattern.CASE_INSENSITIVE);
	/** A ring teleport lands within a few ticks of the confirm (B14: 7 with the arrival lock). */
	private static final int LANDING_WITHIN_TICKS = 12;
	private static final int JUMP_TILES = 4;

	private final Client client;
	private final PlayerModel player;
	private final MemorySlot<Last> remembered;
	private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
	private String pending;
	private int pendingTick;
	private WorldPoint pendingFrom;
	private String published;

	@Inject
	FairyRingMemory(Client client, MemoryStore memory, PlayerModel player)
	{
		this.client = client;
		this.player = player;
		this.remembered = memory.slot(KEY, Last.class, Last::new);
	}

	/** Log lines for the dev trace. */
	public Runnable addListener(Consumer<String> listener)
	{
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	/** On start and after a profile change: what this profile remembers goes to the snapshot. */
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

	/** The confirm on the dials (also after picking a travel-log line): the dials now are where the ring sends the player. */
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

	/** A hovered or opened menu naming "Last-destination (XYZ)": the game's own word for it. */
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
		boolean jumped = at != null && pendingFrom != null
			&& (at.getPlane() != pendingFrom.getPlane() || at.distanceTo2D(pendingFrom) > JUMP_TILES);
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
