package com.cluehelper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * The arrival lock after a landing or a crossing: the ticks before the game takes a click. Shared by the traces, it
 * keeps the last ticks' acting clicks (on the world or an item, not on an interface) and the effects that follow
 * clicks (the player moving or animating, an interface opening, the inventory or worn items changing, a game line).
 * After an arrival, the last acting click at or before the first effect that follows one was taken, and bounds the
 * lock from above. Only walk clicks before it bound the lock from below: they were dropped, where another click with no
 * effect may just have done nothing (a door already open, an NPC busy).
 *
 * <p>Ticks: the arrival is seen on GameTick with the count L of the period before its boundary, and a click in period c
 * is c - (L + 1) ticks into the lock; a click is taken on the boundary after its period, so its effect shows with a
 * count of at least c. A dropped click less than {@link #LATE_MS} before a boundary may just have reached the server a
 * tick later, so it never counts as dropped. Registered on the event bus before the traces (priority -2), so a click
 * that starts the next leg is already here when they see it.
 */
final class LockWatch
{
	static final int UNKNOWN = -1;
	private static final int TICK_MS = 600;
	private static final int LATE_MS = 150;
	/** Longer than any lock the traces watch for. */
	private static final int KEEP_TICKS = 40;
	private static final Set<String> NOT_ACTING = Set.of("Examine", "Cancel");
	private static final String[] WORLD_ACTIONS = {"WALK", "GAME_OBJECT_", "NPC_", "GROUND_ITEM_", "PLAYER_", "WIDGET_TARGET_ON_",
		"ITEM_USE_ON_"};
	private static final Set<ChatMessageType> GAME_LINES = Set.of(ChatMessageType.GAMEMESSAGE, ChatMessageType.SPAM,
		ChatMessageType.MESBOX);

	private final Client client;
	private final Deque<Click> clicks = new ArrayDeque<>();
	private final Deque<Effect> effects = new ArrayDeque<>();
	private long lastTickMs;
	private WorldPoint lastTile;

	LockWatch(Client client)
	{
		this.client = client;
	}

	@Subscribe(priority = -2)
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		String option = Text.removeTags(event.getMenuOption());
		if (!NOT_ACTING.contains(option) && (isWorldAction(event.getMenuAction()) || event.getItemId() > 0) && !PlayerTiles.keypadOpen(client))
		{
			clicks.add(new Click(client.getTickCount(), System.currentTimeMillis() - lastTickMs > TICK_MS - LATE_MS, option));
		}
	}

	@Subscribe(priority = -2)
	public void onGameTick(GameTick tick)
	{
		lastTickMs = System.currentTimeMillis();
		WorldPoint tile = PlayerTiles.tile(client);
		if (tile != null && lastTile != null && !tile.equals(lastTile))
		{
			effect("moved");
		}
		lastTile = tile;
		int oldest = client.getTickCount() - KEEP_TICKS;
		clicks.removeIf(click -> click.tick < oldest);
		effects.removeIf(effect -> effect.tick < oldest);
	}

	@Subscribe(priority = -2)
	public void onAnimationChanged(AnimationChanged event)
	{
		if (event.getActor() == client.getLocalPlayer() && event.getActor().getAnimation() != -1)
		{
			effect("animation");
		}
	}

	@Subscribe(priority = -2)
	public void onWidgetLoaded(WidgetLoaded event)
	{
		effect("interface " + event.getGroupId());
	}

	@Subscribe(priority = -2)
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.INV || event.getContainerId() == InventoryID.WORN)
		{
			effect("items");
		}
	}

	@Subscribe(priority = -2)
	public void onChatMessage(ChatMessage event)
	{
		if (GAME_LINES.contains(event.getType()))
		{
			effect("game line");
		}
	}

	/** A click after the arrival has been followed by an effect, so the lock is as bounded as it will get. */
	boolean isSettled(int arrivalTick)
	{
		return taken(arrivalTick) != null;
	}

	Lock settle(int arrivalTick)
	{
		int lockStart = arrivalTick + 1;
		Taken taken = taken(arrivalTick);
		List<Click> dropped = new ArrayList<>();
		for (Click click : clicks)
		{
			if (click.tick >= lockStart && (taken == null || click.tick < taken.click.tick) && !click.late
				&& click.option.equals(PlayerTiles.WALK))
			{
				dropped.add(click);
			}
		}
		int atLeast = dropped.stream().mapToInt(click -> click.tick - lockStart + 1).max().orElse(0);
		return taken == null
			? new Lock(Math.max(0, atLeast), UNKNOWN, null)
			: new Lock(Math.max(0, atLeast), Math.max(0, taken.click.tick - lockStart), taken.click.option + " -> " + taken.effect.kind);
	}

	/** The first effect after the arrival that follows an acting click, with the last such click before it. */
	private Taken taken(int arrivalTick)
	{
		int lockStart = arrivalTick + 1;
		for (Effect effect : effects)
		{
			Click last = null;
			for (Click click : clicks)
			{
				if (click.tick >= lockStart && click.tick <= effect.tick)
				{
					last = click;
				}
			}
			if (last != null)
			{
				return new Taken(last, effect);
			}
		}
		return null;
	}

	private void effect(String kind)
	{
		effects.add(new Effect(client.getTickCount(), kind));
	}

	private static boolean isWorldAction(MenuAction action)
	{
		String name = action.name();
		for (String prefix : WORLD_ACTIONS)
		{
			if (name.startsWith(prefix))
			{
				return true;
			}
		}
		return false;
	}

	static final class Lock
	{
		static final Lock NONE = new Lock(0, 0, null);

		final int atLeast;
		/** {@link #UNKNOWN} when no click after the arrival was seen taken. */
		final int atMost;
		/** The taken click and the effect that showed it, or null. */
		final String takenBy;

		Lock(int atLeast, int atMost, String takenBy)
		{
			this.atLeast = atLeast;
			this.atMost = atMost;
			this.takenBy = takenBy;
		}
	}

	private static final class Click
	{
		private final int tick;
		/** Less than {@link #LATE_MS} before the period's boundary. */
		private final boolean late;
		private final String option;

		private Click(int tick, boolean late, String option)
		{
			this.tick = tick;
			this.late = late;
			this.option = option;
		}
	}

	private static final class Effect
	{
		private final int tick;
		private final String kind;

		private Effect(int tick, String kind)
		{
			this.tick = tick;
			this.kind = kind;
		}
	}

	private static final class Taken
	{
		private final Click click;
		private final Effect effect;

		private Taken(Click click, Effect effect)
		{
			this.click = click;
			this.effect = effect;
		}
	}
}
