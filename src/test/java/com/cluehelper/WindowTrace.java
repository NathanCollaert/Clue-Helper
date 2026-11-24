package com.cluehelper;

import com.cluehelper.guide.Highlights;
import com.cluehelper.guide.MenuTarget;
import com.cluehelper.guide.StepGuide;
import com.cluehelper.guide.SubStep;
import com.cluehelper.poh.PohService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.TileObject;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * The wait windows no other trace sees, sampled into {@link WaitWindows} as their owners count them: the charge line
 * after a Check, the house's furniture loading, the master clue's ground item after a drop, the bank's contents after a
 * deposit, a ride through the step's way in, and the guide outlining an object or NPC it targets once it appears.
 */
final class WindowTrace
{
	private static final String CHECK = "Check";
	private static final String CHARGE = "charge";
	private static final String DEPOSIT = "Deposit";
	/** Longer than any window: an event later than this belongs to something else. */
	private static final int WATCH_TICKS = 10;
	/** Longer than any ride through a way in. */
	private static final int RIDE_WATCH_TICKS = 60;
	/** The waypoint line's own idle test starts this many ticks after the way-in click. */
	private static final int IDLE_FROM_TICKS = 2;
	/**
	 * Longer than the guide's refresh: a target not outlined by then is sampled as never outlined, which may be one the
	 * guide passes over on purpose (a second NPC of the name, farther away).
	 */
	private static final int OUTLINE_WATCH_TICKS = 15;
	private static final int NEVER = Integer.MIN_VALUE / 2;

	private final Client client;
	private final PohService poh;
	private final StepGuide guide;
	private final WaitWindows windows;
	private int checkClickTick = NEVER;
	private boolean inHouse;
	private int houseEnteredTick = NEVER;
	private int lastHouseSpawnTick;
	private boolean carriesMasterClue;
	private int clueDroppedTick = NEVER;
	private int depositClickTick = NEVER;
	private int depositInventoryTick = NEVER;
	private SubStep entering;
	private int enteringClickTick = NEVER;
	/** When the waypoint line's idle test would have ended the ride, or {@link #NEVER}. */
	private int enteringIdleTick = NEVER;
	/** Targets that appeared, by the tick they did, until the guide outlines them. */
	private final Map<Object, Integer> appeared = new HashMap<>();

	WindowTrace(Client client, PohService poh, StepGuide guide, WaitWindows windows)
	{
		this.client = client;
		this.poh = poh;
		this.guide = guide;
		this.windows = windows;
	}

	@Subscribe(priority = -3)
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		String option = Text.removeTags(event.getMenuOption());
		if (option.equals(CHECK))
		{
			checkClickTick = client.getTickCount();
		}
		else if (option.startsWith(DEPOSIT) && client.getWidget(InterfaceID.BANKMAIN, 0) != null)
		{
			depositClickTick = client.getTickCount();
			depositInventoryTick = NEVER;
		}
		else if (isWayInClicked(event))
		{
			entering = guide.getCurrent();
			enteringClickTick = client.getTickCount();
			enteringIdleTick = NEVER;
		}
	}

	@Subscribe(priority = -3)
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() == ChatMessageType.GAMEMESSAGE && within(checkClickTick)
			&& Text.removeTags(event.getMessage()).toLowerCase().contains(CHARGE))
		{
			windows.sample(WaitWindows.Window.CHECK_LINE, client.getTickCount() - checkClickTick, Text.removeTags(event.getMessage()));
			checkClickTick = NEVER;
		}
	}

	@Subscribe(priority = -3)
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		if (within(houseEnteredTick) && isInHouse())
		{
			lastHouseSpawnTick = client.getTickCount();
		}
		noteIfTargeted(event.getGameObject());
	}

	@Subscribe(priority = -3)
	public void onWallObjectSpawned(WallObjectSpawned event)
	{
		noteIfTargeted(event.getWallObject());
	}

	@Subscribe(priority = -3)
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned event)
	{
		noteIfTargeted(event.getDecorativeObject());
	}

	@Subscribe(priority = -3)
	public void onGroundObjectSpawned(GroundObjectSpawned event)
	{
		noteIfTargeted(event.getGroundObject());
	}

	@Subscribe(priority = -3)
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc();
		Highlights highlights = guide.getHighlights();
		boolean targeted = targetsNpc(highlights.getMenuTarget(), npc) || targetsNpc(highlights.getOptionTarget(), npc)
			|| guide.getHighlightNpcs().stream().anyMatch(shown -> shown.getId() == npc.getId());
		if (targeted)
		{
			appeared.put(npc, client.getTickCount());
		}
	}

	/** Every client tick: the guide outlines on its own game tick handler, which may run before or after this trace's. */
	@Subscribe(priority = -3)
	public void onClientTick(ClientTick tick)
	{
		if (appeared.isEmpty())
		{
			return;
		}
		int now = client.getTickCount();
		appeared.entrySet().removeIf(entry ->
		{
			boolean outlined = guide.getHighlightObjects().contains(entry.getKey()) || guide.getHighlightNpcs().contains(entry.getKey());
			boolean givenUp = now - entry.getValue() > OUTLINE_WATCH_TICKS;
			if (outlined || givenUp)
			{
				windows.sample(WaitWindows.Window.OBJECT_REFRESH, now - entry.getValue(),
					describe(entry.getKey()) + (outlined ? "" : ", never outlined"));
			}
			return outlined || givenUp;
		});
	}

	@Subscribe(priority = -3)
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.INV)
		{
			boolean carries = event.getItemContainer().contains(ItemID.TRAIL_CLUE_MASTER);
			clueDroppedTick = carriesMasterClue && !carries ? client.getTickCount() : clueDroppedTick;
			carriesMasterClue = carries;
			depositInventoryTick = within(depositClickTick) && depositInventoryTick == NEVER ? client.getTickCount() : depositInventoryTick;
		}
		else if (event.getContainerId() == InventoryID.BANK && within(depositClickTick))
		{
			sampleDeposit(event.getItemContainer());
		}
	}

	@Subscribe(priority = -3)
	public void onItemSpawned(ItemSpawned event)
	{
		if (event.getItem().getId() == ItemID.TRAIL_CLUE_MASTER && within(clueDroppedTick))
		{
			windows.sample(WaitWindows.Window.CLUE_DROP, client.getTickCount() - clueDroppedTick, event.getTile().getWorldLocation().toString());
			clueDroppedTick = NEVER;
		}
	}

	@Subscribe(priority = -3)
	public void onGameTick(GameTick tick)
	{
		int now = client.getTickCount();
		boolean nowInHouse = isInHouse();
		if (nowInHouse && !inHouse)
		{
			houseEnteredTick = now;
			lastHouseSpawnTick = now;
		}
		inHouse = nowInHouse;
		if (houseEnteredTick != NEVER && (!nowInHouse || now - houseEnteredTick == WATCH_TICKS))
		{
			String detail = nowInHouse ? PlayerTiles.text(PlayerTiles.tile(client)) : "left the house after " + (now - houseEnteredTick);
			windows.sample(WaitWindows.Window.HOUSE_SCAN, lastHouseSpawnTick - houseEnteredTick, detail);
			houseEnteredTick = NEVER;
		}
		watchRide(now);
	}

	/**
	 * In the house's scene: the plugin's house flag still reads true on the tick of landing outside, when that scene's
	 * objects spawn, but the scene is no longer an instance by then.
	 */
	private boolean isInHouse()
	{
		return poh.isInHouse() && client.getTopLevelWorldView().isInstance();
	}

	/** As the waypoint line tells a way in taken: a click on the ENTER step's object. */
	private boolean isWayInClicked(MenuOptionClicked event)
	{
		SubStep current = guide.getCurrent();
		return current != null && current.getKind() == SubStep.Kind.ENTER
			&& guide.getHighlights().getMenuTarget().getObjectIds().contains(event.getId());
	}

	/**
	 * The ride ends when the guide moves on from the way in. Where the waypoint line's idle test would have ended it
	 * sooner, the sample says so: the line came back mid-ride.
	 */
	private void watchRide(int now)
	{
		if (enteringClickTick == NEVER)
		{
			return;
		}
		Player local = client.getLocalPlayer();
		boolean idle = local != null && local.getAnimation() == -1 && local.getPoseAnimation() == local.getIdlePoseAnimation();
		if (enteringIdleTick == NEVER && idle && now - enteringClickTick >= IDLE_FROM_TICKS)
		{
			enteringIdleTick = now;
		}
		boolean movedOn = !Objects.equals(guide.getCurrent(), entering);
		if (movedOn || now - enteringClickTick > RIDE_WATCH_TICKS)
		{
			boolean lineBackEarly = enteringIdleTick != NEVER && enteringIdleTick < now;
			String detail = (movedOn ? "guide moved on" : "guide never moved on")
				+ (lineBackEarly ? ", line back at idle after " + (enteringIdleTick - enteringClickTick) : "");
			windows.sample(WaitWindows.Window.ENTERING, now - enteringClickTick, detail);
			enteringClickTick = NEVER;
			entering = null;
		}
	}

	private void noteIfTargeted(TileObject object)
	{
		Highlights highlights = guide.getHighlights();
		Set<Integer> ids = new HashSet<>(highlights.getMenuTarget().getObjectIds());
		ids.addAll(highlights.getOptionTarget().getObjectIds());
		guide.getHighlightObjects().forEach(shown -> ids.add(shown.getId()));
		if (ids.contains(object.getId()))
		{
			appeared.put(object, client.getTickCount());
		}
	}

	private static boolean targetsNpc(MenuTarget target, NPC npc)
	{
		return target.getNpcIds().contains(npc.getId()) || npc.getName() != null && target.getNpcNames().contains(npc.getName());
	}

	private static String describe(Object target)
	{
		return target instanceof NPC ? "npc " + ((NPC) target).getId() : "object " + ((TileObject) target).getId();
	}

	private void sampleDeposit(ItemContainer bank)
	{
		int now = client.getTickCount();
		String detail = bank.count() + " bank slots";
		windows.sample(WaitWindows.Window.BANK_DEPOSIT, now - depositClickTick, detail);
		if (depositInventoryTick != NEVER)
		{
			windows.sample(WaitWindows.Window.BANK_CONFIRM, now - depositInventoryTick, detail);
		}
		depositClickTick = NEVER;
	}

	private boolean within(int since)
	{
		return since != NEVER && client.getTickCount() - since <= WATCH_TICKS;
	}
}
