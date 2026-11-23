package com.cluehelper.guide;

import com.cluehelper.memory.*;
import com.cluehelper.transport.Category;
import java.awt.event.KeyEvent;
import java.util.*;
import javax.inject.*;
import lombok.Getter;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.*;

/**
 * Client thread, but for the key listener. An obelisk takes Set Destination and its list line, then Teleport to
 * Destination, then a wait on the platform. The game keeps one destination for every obelisk but shows it nowhere, so
 * the last one picked is remembered, as its line in the list: every obelisk lists the same destinations in the same
 * order. Only a pick sets it: the list also closes on Escape or a walk away.
 */
@Singleton
final class ObeliskDestination implements KeyListener
{
	private static final String SET = "Set Destination";
	private static final String TELEPORT = "Teleport to Destination";
	private static final String KEY = "obeliskDestinationLine";
	private static final String LIST_TITLE = "Select Obelisk destination";
	/** The jump follows the portal animation within a tick or two. */
	private static final int LANDING_TICKS = 4;
	/** An obelisk row's origin is its platform's south-west tile; the pillars stand a tile out from the platform's corners. */
	private static final int PILLAR_TILES = 4;

	@Inject
	Client client;
	@Inject
	ClientThread clientThread;
	@Inject
	KeyManager keyManager;
	@Inject
	MenuLines menuLines;
	@Inject
	SceneObjects sceneObjects;
	MemorySlot<Integer> destination;
	boolean changed;
	/** The step's obelisk is activated: Teleport to Destination clicked from afar only walks there, so its pillars tell. */
	@Getter
	boolean lit;
	int litTick = ActWatcher.NEVER;
	int portalTick = ActWatcher.NEVER;
	SubStep portalStep;

	@Inject
	void init(MemoryStore memory)
	{
		destination = memory.slot(KEY, Integer.class, () -> null);
	}

	void start()
	{
		keyManager.registerKeyListener(this);
	}

	void stop()
	{
		keyManager.unregisterKeyListener(this);
	}

	/** The list line a Set Destination target picks; null for any other target. */
	static String listLine(MenuTarget target)
	{
		List<String> path = target.getPath();
		return path.size() == 2 && SET.equals(path.get(0)) ? path.get(1) : null;
	}

	/**
	 * Teleport to Destination once the obelisks already lead where the target's list line would set them, and nothing
	 * once the obelisk is lit: only the platform is left.
	 */
	MenuTarget nextClick(MenuTarget target)
	{
		if (lit)
		{
			return MenuTarget.NONE;
		}
		String line = listLine(target);
		boolean set = line != null && Integer.valueOf(MenuLabels.hotkeyIndex(line)).equals(destination.get());
		return set ? target.toBuilder().path(List.of(TELEPORT)).build() : target;
	}

	/** @return whether a destination was picked, the obelisk lit up or went out, or a teleport proved it wrong */
	boolean takeChanged()
	{
		boolean was = changed;
		changed = false;
		return was;
	}

	/** Call every tick, before anything can end the tick early: the portal animation lasts only a tick or two. */
	void onGameTick(SubStep current)
	{
		boolean obeliskStep = current != null && current.getRow() != null && current.getRow().getCategory() == Category.WILDERNESS_OBELISK;
		watchPortal(obeliskStep ? current : null);
		if (obeliskStep && isLitAround(current.getWaypoint()))
		{
			litTick = client.getTickCount();
		}
		// The pillars go out a tick before the portal opens, and stay out until the landing
		boolean litLately = litTick != ActWatcher.NEVER && client.getTickCount() - litTick <= LANDING_TICKS;
		boolean nowLit = obeliskStep && (portalTick != ActWatcher.NEVER || litLately);
		changed |= nowLit != lit;
		lit = nowLit;
	}

	/** The portal opening on the obelisk step, then, once the landing is due, whether it went where the step goes. */
	private void watchPortal(SubStep obeliskStep)
	{
		Player local = client.getLocalPlayer();
		if (obeliskStep != null && portalTick == ActWatcher.NEVER && local != null && local.getAnimation() == AnimationID.HUMAN_WILDERNESS_PORTAL)
		{
			portalTick = client.getTickCount();
			portalStep = obeliskStep;
		}
		if (portalTick != ActWatcher.NEVER && client.getTickCount() - portalTick > LANDING_TICKS)
		{
			forgetUnlessLanded(local == null ? null : local.getWorldLocation());
		}
	}

	private boolean isLitAround(WorldPoint platform)
	{
		return platform != null && sceneObjects.find(Set.of(ObjectID.WILDERNESS_PORTAL_STONE_GLOWING)).stream()
			.anyMatch(pillar -> pillar.getWorldLocation().distanceTo2D(platform) <= PILLAR_TILES);
	}

	/**
	 * A teleport that lands away from the step's destination, or leaves the player where they stood (the obelisk was its
	 * own destination), proves the remembered one wrong (set elsewhere, or by another player): it is set again.
	 */
	private void forgetUnlessLanded(WorldPoint at)
	{
		if (at == null || at.distanceTo2D(portalStep.getGoal()) > portalStep.getRadius())
		{
			destination.set(null);
			changed = true;
		}
		portalTick = ActWatcher.NEVER;
	}

	/**
	 * Call on every menu click, clue or not: a line picked in the open list, directly or by its button on a map another
	 * plugin draws over the list (better-teleport-menu).
	 */
	void onMenuOptionClicked(MenuOptionClicked event)
	{
		int mapButton = menuLines.listIndex(event.getMenuTarget());
		if (event.getParam1() == InterfaceID.Menu.LJ_LAYER1)
		{
			pick(event.getParam0());
		}
		else if (mapButton >= 0)
		{
			pick(mapButton);
		}
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
		int line = MenuLines.HOTKEYS.indexOf(Character.toUpperCase(e.getKeyChar()));
		if (line >= 0)
		{
			clientThread.invoke(() -> pick(line));
		}
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
	}

	/** The open list's line at {@code index} was chosen, the step's destination or not: the obelisks now lead there. */
	private void pick(int index)
	{
		if (menuLines.isListOpen(LIST_TITLE))
		{
			destination.set(index);
			changed = true;
		}
	}

	/** The remembered destination stays: the game keeps it. */
	void clear()
	{
		changed = false;
		lit = false;
		litTick = ActWatcher.NEVER;
		portalTick = ActWatcher.NEVER;
	}
}
