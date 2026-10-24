package com.cluehelper.player;

import com.cluehelper.memory.MemorySlot;
import com.cluehelper.memory.MemoryStore;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.util.Text;

/**
 * Client thread, except {@link #charges()}. A teleport doesn't lower the remembered count: no chat line says it was
 * used, so the count is shown with the time it was checked.
 */
@Singleton
public class CheckLines
{
	static final String MEMORY_KEY = "checkLines";
	/** "Unlimited charges" counts as this many: more than any route spends. */
	public static final int UNLIMITED = 10_000;
	/** The line arrives on the click's own tick; the rest is slack. */
	private static final int LINE_TICKS = 2;
	private static final String CHECK = "Check";
	private static final Pattern COUNT = Pattern.compile("\\bhas ([\\d,]+) charges?\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern UNLIMITED_LINE = Pattern.compile("\\bhas unlimited charges\\b", Pattern.CASE_INSENSITIVE);
	private static final int NONE = -1;

	private final Client client;
	private final MemorySlot<Remembered> remembered;
	private int checkedItem = NONE;
	private int checkedTick;

	@Inject
	CheckLines(Client client, MemoryStore memory)
	{
		this.client = client;
		this.remembered = memory.slot(MEMORY_KEY, Remembered.class, Remembered::new);
	}

	/** Keyed by item id. Any thread. */
	public Map<Integer, Checked> charges()
	{
		return remembered.get().items;
	}

	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (CHECK.equals(Text.removeTags(event.getMenuOption())) && event.getItemId() > 0)
		{
			checkedItem = event.getItemId();
			checkedTick = client.getTickCount();
		}
	}

	/** @return whether a charge count was learned */
	public boolean onChatMessage(ChatMessage message)
	{
		if (message.getType() != ChatMessageType.GAMEMESSAGE || checkedItem == NONE || client.getTickCount() - checkedTick > LINE_TICKS)
		{
			return false;
		}
		Integer count = parse(Text.removeTags(message.getMessage()));
		if (count == null)
		{
			return false;
		}
		Remembered updated = new Remembered();
		updated.items.putAll(remembered.get().items);
		Checked checked = new Checked();
		checked.charges = count;
		checked.checkedAt = System.currentTimeMillis();
		updated.items.put(checkedItem, checked);
		remembered.set(updated);
		checkedItem = NONE;
		return true;
	}

	/** Null when the line isn't a Check line. */
	static Integer parse(String line)
	{
		if (UNLIMITED_LINE.matcher(line).find())
		{
			return UNLIMITED;
		}
		Matcher m = COUNT.matcher(line);
		return m.find() ? Integer.parseInt(m.group(1).replace(",", "")) : null;
	}

	public static final class Checked
	{
		public int charges;
		private long checkedAt;
	}

	static final class Remembered
	{
		Map<Integer, Checked> items = new HashMap<>();
	}
}
