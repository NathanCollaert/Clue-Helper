package com.cluehelper.guide;

import com.cluehelper.step.Step;
import com.cluehelper.step.StepIndex;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
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
	private static final Set<String> CHALLENGE_ITEMS = Set.of("challenge scroll", "puzzle box", "light box");

	private final Client client;
	private int withdrawTick = NEVER;
	private int depositTick = NEVER;
	private int uriTick = NEVER;
	private int challengeTick = NEVER;
	private int enemyTick = NEVER;
	private NPC enemy;
	private boolean inventoryChanged = true;
	private boolean challengeCarried;

	@Inject
	ActWatcher(Client client)
	{
		this.client = client;
	}

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

	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.INV)
		{
			inventoryChanged = true;
		}
	}

	/** Call once per tick with the step being guided. */
	void onGameTick(Step step)
	{
		int tick = client.getTickCount();
		if (inventoryChanged)
		{
			inventoryChanged = false;
			boolean carried = carriesChallengeItem();
			if (carried && !challengeCarried)
			{
				challengeTick = tick;
			}
			challengeCarried = carried;
		}
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
			case EQUIPPED:
			case ITEM_CARRIED:
			case ITEM_GONE:
			case WAY_IN_WORN:
			case GEAR_WORN:
			case SLOTS_EMPTY:
			case ITEM_HELD:
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
		StepIndex.Entry data = step == null ? null : step.getData();
		if (data == null)
		{
			return Set.of();
		}
		Set<Integer> ids = new HashSet<>();
		data.getCombat().forEach(combat -> ids.addAll(combat.getNpcs()));
		return ids;
	}

	private boolean carriesChallengeItem()
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory == null)
		{
			return false;
		}
		for (Item item : inventory.getItems())
		{
			if (item.getId() > 0 && CHALLENGE_ITEMS.contains(client.getItemDefinition(item.getId()).getName().toLowerCase()))
			{
				return true;
			}
		}
		return false;
	}
}
