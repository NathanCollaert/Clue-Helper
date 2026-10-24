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
 * Client thread. Filled state has no var: the Clue Scroll plugin's tick (script 1479) is built state too and stays 1
 * for an emptied unit. So a built unit counts as filled, as that tick suggests, until one of the two Search chat lines
 * says otherwise.
 */
@Singleton
public class StashMemory
{
	public enum State
	{
		NOT_BUILT, FILLED, EMPTY
	}

	static final String KEY = "stash";
	static final String WITHDRAWN = "You withdraw your items from the STASH unit.";
	static final String DEPOSITED = "You deposit your items into the STASH unit.";
	/** A Search line lands 0–3 ticks after the click, or 8 when the first Search of a session asks for the PIN. */
	static final int SEARCH_LINE_TICKS = 8;
	private static final String SEARCH = "Search";
	private static final int CORE_STASH_SCRIPT = 1479;
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

	public State state(int objectId)
	{
		if (!isBuilt(objectId))
		{
			return State.NOT_BUILT;
		}
		Seen seen = remembered.get().units.get(objectId);
		return seen == null || seen.filled ? State.FILLED : State.EMPTY;
	}

	/**
	 * Client thread, outside any script: scripts don't nest, and a call from a chat command's handler trips the
	 * client's assertion.
	 */
	public int coreCheck(int objectId)
	{
		client.runScript(CORE_STASH_SCRIPT, objectId, 0, 0, 0);
		return client.getIntStack()[0];
	}

	public static Set<Integer> unitObjects()
	{
		return UNIT_OBJECTS;
	}

	public boolean built(int objectId)
	{
		return isBuilt(objectId);
	}

	/** Epoch millis, or 0 when never seen. */
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

	/** The unit object is a multiloc on its own {@code HH_CONSTRUCTED_*} varbit, readable at login. */
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
