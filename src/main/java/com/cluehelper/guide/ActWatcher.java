package com.cluehelper.guide;

import com.cluehelper.player.*;
import com.cluehelper.step.*;
import java.util.*;
import javax.inject.*;
import lombok.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/** Client thread only. The events that end an act, each kept with the tick it was last seen. */
@Singleton
public class ActWatcher
{
	static final int NEVER = Integer.MIN_VALUE;
	private static final String WITHDRAW_LINE = "You withdraw your items from the STASH unit.";
	private static final String DEPOSIT_LINE = "You deposit your items into the STASH unit.";
	private static final String URI = "Uri";
	/** Uri and the double agents appear beside the player who called them. */
	private static final int SPAWN_TILES = 5;
	private static final List<String> BOXES = List.of("puzzle box", "light box");
	/** Name starts, since the scroll and the puzzle box carry their tier: "Challenge scroll (hard)". */
	private static final List<String> CHALLENGE_ITEMS = List.of("challenge scroll", BOXES.get(0), BOXES.get(1));
	/** A solved puzzle box holds its tiles in this many slots, in item id order, and leaves the last slot empty. */
	private static final int PUZZLE_TILES = 24;

	@Inject
	Client client;
	@Inject
	PlayerModel player;
	int withdrawTick = NEVER;
	int depositTick = NEVER;
	int uriTick = NEVER;
	int challengeTick = NEVER;
	int enemyTick = NEVER;
	NPC enemy;
	boolean challengeCarried;
	/** The puzzle or light box carried, by name; null for none. */
	@Getter(AccessLevel.PACKAGE)
	String box;
	@Getter(AccessLevel.PACKAGE)
	boolean boxOpen;
	/** The carried box was seen solved while open; a solved box opens solved again. */
	@Getter(AccessLevel.PACKAGE)
	boolean boxSolved;

	public void onChatMessage(ChatMessage message)
	{
		if (message.getType() != ChatMessageType.GAMEMESSAGE && message.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		String text = Text.removeTags(message.getMessage());
		if (WITHDRAW_LINE.equals(text))
		{
			withdrawTick = client.getTickCount();
		}
		else if (DEPOSIT_LINE.equals(text))
		{
			depositTick = client.getTickCount();
		}
	}

	/** Call once per tick with the step being guided. */
	void onGameTick(Step step)
	{
		int tick = client.getTickCount();
		boolean carried = CHALLENGE_ITEMS.stream().anyMatch(player::carriesItemNamed);
		if (carried && !challengeCarried)
		{
			challengeTick = tick;
		}
		challengeCarried = carried;
		box = BOXES.stream().filter(player::carriesItemNamed).findFirst().orElse(null);
		watchBox();
		Player local = client.getLocalPlayer();
		WorldPoint at = local == null ? null : local.getWorldLocation();
		if (at == null || client.getTopLevelWorldView() == null)
		{
			return;
		}
		Set<Integer> enemies = enemyIds(step);
		NPC near = null;
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			boolean beside = npc.getWorldLocation().distanceTo2D(at) <= SPAWN_TILES;
			if (beside && URI.equals(npc.getName()))
			{
				uriTick = tick;
			}
			if (beside && enemies.contains(npc.getId()) && !npc.isDead())
			{
				near = npc;
			}
		}
		enemy = near;
		if (near != null)
		{
			enemyTick = tick;
		}
	}

	private void watchBox()
	{
		Widget puzzle = client.getWidget(InterfaceID.TRAIL_SLIDEPUZZLE, 0);
		Widget lights = client.getWidget(InterfaceID.LightPuzzle.LIGHTS);
		boxOpen = puzzle != null || lights != null;
		boolean solvedNow = puzzle != null ? isPuzzleSolved() : lights != null && areAllLit(lights);
		boxSolved = box != null && (boxSolved || solvedNow);
	}

	private boolean isPuzzleSolved()
	{
		ItemContainer tiles = client.getItemContainer(InventoryID.TRAIL_PUZZLEINV);
		Item[] items = tiles == null ? new Item[0] : tiles.getItems();
		if (items.length < PUZZLE_TILES)
		{
			return false;
		}
		for (int slot = 1; slot < PUZZLE_TILES; slot++)
		{
			if (items[slot].getId() != items[0].getId() + slot)
			{
				return false;
			}
		}
		return items.length == PUZZLE_TILES || items[PUZZLE_TILES].getId() == -1;
	}

	private static boolean areAllLit(Widget lights)
	{
		Widget[] bulbs = lights.getDynamicChildren();
		return Arrays.stream(bulbs).anyMatch(bulb -> bulb.getItemId() == ItemID.TRAIL_LIGHT_LIT)
			&& Arrays.stream(bulbs).noneMatch(bulb -> bulb.getItemId() == ItemID.TRAIL_LIGHT_UNLIT);
	}

	/** Items came out of a STASH unit since {@code since} and none went back in after. */
	boolean holdsStashItems(int since)
	{
		return withdrawTick >= since && withdrawTick > depositTick;
	}

	/** Only the ends this watcher sees as events; the guide checks the others against what the player carries. */
	boolean isDone(SubStep.Done done, int since)
	{
		switch (done)
		{
			case STASH_WITHDRAWN:
				return withdrawTick >= since;
			case STASH_DEPOSITED:
				return depositTick >= since;
			case URI_HERE:
				return uriTick >= since;
			case CHALLENGE_ITEM:
				return challengeTick >= since || challengeCarried;
			case STEP_CHANGES:
			case ITEM_CARRIED:
			case ITEM_GONE:
			case ITEMS_GONE:
			case WORN:
			case SLOTS_EMPTY:
			case ITEM_HELD:
			case TRACK_PLAYING:
				return false;
			default:
				throw new IllegalArgumentException("unhandled act end " + done);
		}
	}

	/** The double agent, wizard or guard attacking now; null when none is near. */
	NPC getEnemy()
	{
		return enemy;
	}

	boolean foughtSince(int tick)
	{
		return enemyTick >= tick && enemy == null;
	}

	private static Set<Integer> enemyIds(Step step)
	{
		return step == null ? Set.of() : step.getData().getEnemies();
	}
}
