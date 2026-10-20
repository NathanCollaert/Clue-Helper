package com.cluehelper.memory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ObjectComposition;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.plugins.cluescrolls.clues.emote.STASHUnit;
import net.runelite.client.util.Text;

/**
 * What the player's STASH units hold (PLAN §5.2, B13). Built state is each unit's own varbit, read live; filled
 * state has no var, so it is learned from the two Search chat lines and remembered per unit with the date. A
 * unit never seen is unknown, which never counts as filled. Client thread.
 */
@Singleton
public class StashMemory
{
	public enum State
	{
		NOT_BUILT, FILLED, EMPTY, UNKNOWN
	}

	static final String KEY = "stash";
	static final String WITHDRAWN = "You withdraw your items from the STASH unit.";
	static final String DEPOSITED = "You deposit your items into the STASH unit.";
	/** A Search line lands 0–3 ticks after the click, or 8 when the first Search of a session asks for the PIN (B13). */
	static final int SEARCH_LINE_TICKS = 8;
	private static final String SEARCH = "Search";
	private static final int BUILT = 1;
	private static final int NOT_SEEN = -1;
	private static final Set<Integer> UNIT_OBJECTS = Arrays.stream(STASHUnit.values()).map(STASHUnit::getObjectId)
		.collect(Collectors.toUnmodifiableSet());

	private final Client client;
	private final MemorySlot<Units> remembered;
	private final Map<Integer, Integer> builtVarbits = new HashMap<>();
	private int lastSearchObject = NOT_SEEN;
	private int lastSearchTick;

	@Inject
	StashMemory(Client client, MemoryStore memory)
	{
		this.client = client;
		this.remembered = memory.slot(KEY, Units.class, Units::new);
	}

	/** The state of the unit with this object id. */
	public State state(int objectId)
	{
		if (!isBuilt(objectId))
		{
			return State.NOT_BUILT;
		}
		Seen seen = remembered.get().units.get(objectId);
		return seen == null ? State.UNKNOWN : seen.filled ? State.FILLED : State.EMPTY;
	}

	/** When the unit's state was last seen (epoch millis), or 0. */
	public long seenAt(int objectId)
	{
		Seen seen = remembered.get().units.get(objectId);
		return seen == null ? 0 : seen.seenAt;
	}

	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (SEARCH.equals(Text.removeTags(event.getMenuOption())) && UNIT_OBJECTS.contains(event.getId()))
		{
			lastSearchObject = event.getId();
			lastSearchTick = client.getTickCount();
		}
	}

	public void onChatMessage(ChatMessage message)
	{
		if (message.getType() != ChatMessageType.GAMEMESSAGE || lastSearchObject == NOT_SEEN
			|| client.getTickCount() - lastSearchTick > SEARCH_LINE_TICKS)
		{
			return;
		}
		String text = Text.removeTags(message.getMessage());
		if (WITHDRAWN.equals(text) || DEPOSITED.equals(text))
		{
			remember(lastSearchObject, DEPOSITED.equals(text));
			lastSearchObject = NOT_SEEN;
		}
	}

	private void remember(int objectId, boolean filled)
	{
		Units units = new Units();
		units.units.putAll(remembered.get().units);
		Seen seen = new Seen();
		seen.filled = filled;
		seen.seenAt = System.currentTimeMillis();
		units.units.put(objectId, seen);
		remembered.set(units);
	}

	/** The unit object is a multiloc on its own {@code HH_CONSTRUCTED_*} varbit, readable at login (B13). */
	private boolean isBuilt(int objectId)
	{
		int varbit = builtVarbits.computeIfAbsent(objectId, id ->
		{
			ObjectComposition unit = client.getObjectDefinition(id);
			return unit == null ? -1 : unit.getVarbitId();
		});
		return varbit == -1 || client.getVarbitValue(varbit) == BUILT;
	}

	static final class Units
	{
		Map<Integer, Seen> units = new HashMap<>();
	}

	static final class Seen
	{
		boolean filled;
		long seenAt;
	}
}
