package com.cluehelper.link;

import com.cluehelper.memory.NpcPlaceMemory;
import com.cluehelper.player.*;
import com.cluehelper.step.*;
import com.cluehelper.transport.AvailabilityService;
import java.util.*;
import java.util.concurrent.*;
import javax.inject.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.*;
import net.runelite.client.plugins.cluescrolls.*;
import net.runelite.client.plugins.cluescrolls.clues.*;

/**
 * Core is read once per tick, at GameTick priority -1, after core's own handlers have updated it. Its active state
 * comes from {@code isPluginActive} and {@link PluginChanged}, never from {@code isPluginEnabled} or the config key,
 * which both flip before core actually stops or starts.
 */
@Slf4j
@Singleton
public class ClueLink
{
	private static final int NO_CLUE_PARAM = -1;

	@Inject
	private PluginManager pluginManager;
	@Inject
	private ClueScrollService service;
	@Inject
	private Client client;
	@Inject
	private StepResolver resolver;
	@Inject
	private AvailabilityService availability;
	@Inject
	private PlayerModel player;
	@Inject
	private NpcPlaceMemory npcPlaces;
	private final LinkState state = new LinkState();
	private final List<StepListener> listeners = new CopyOnWriteArrayList<>();
	private final Set<String> loggedFailures = ConcurrentHashMap.newKeySet();

	private volatile ClueScrollPlugin core;
	private volatile Step current;
	private volatile boolean inventoryChanged;
	private boolean inventoryHoldsClue;

	/** Swing thread. Core's instance exists even while it is off. */
	public void start()
	{
		core = findCore();
		current = null;
		inventoryChanged = true;
		state.start(isCoreActive());
	}

	public void stop()
	{
		core = null;
		current = null;
		state.start(false);
	}

	public boolean isActive()
	{
		return state.isActive();
	}

	public boolean needsRead()
	{
		return state.needsRead();
	}

	public boolean hasStep()
	{
		return state.hasStep();
	}

	/** Null when core has no clue. */
	public Step getCurrentStep()
	{
		return current;
	}

	/** Null if the client has no Clue Scroll plugin. */
	public ClueScrollPlugin getCore()
	{
		return core;
	}

	public ClueScroll coreClue()
	{
		return service.getClue();
	}

	/**
	 * Client thread. Core marks its clue's NPC only while one of the clue's own tiles is in the loaded scene on the
	 * player's floor: not inside a cave reached from that tile (Fycie in Rantz's cave), nor up the ladder from it
	 * (Bolkoy on his platform).
	 */
	public boolean coreMarksNpcs()
	{
		WorldPoint[] locations = coreLocations();
		return locations == null || Arrays.stream(locations).anyMatch(location -> location != null && location.isInScene(client));
	}

	/** The tiles core marks its clue's NPC from, or null where core doesn't tie the marking to a tile. */
	private WorldPoint[] coreLocations()
	{
		ClueScroll clue = coreClue();
		ClueScrollPlugin plugin = core;
		if (plugin == null)
		{
			return null;
		}
		if (clue instanceof LocationClueScroll)
		{
			return ((LocationClueScroll) clue).getLocations(plugin);
		}
		return clue instanceof AnagramClue ? new WorldPoint[]{((AnagramClue) clue).getLocation(plugin)} : null;
	}

	public Runnable addListener(StepListener listener)
	{
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	/**
	 * Swing thread, so it only sets the flag.
	 *
	 * @return whether the active flag changed
	 */
	public boolean onPluginChanged(PluginChanged event)
	{
		Plugin c = core;
		return c != null && event.getPlugin() == c && state.onPluginChanged(event.isLoaded());
	}

	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.INV)
		{
			inventoryChanged = true;
		}
	}

	/**
	 * Call from a GameTick handler at priority -1.
	 *
	 * @return whether anything the box shows changed: active, needs-read or has-step
	 */
	public boolean onGameTick()
	{
		boolean wasActive = state.isActive();
		boolean neededRead = state.needsRead();
		boolean hadStep = state.hasStep();

		boolean active = isCoreActive();
		ClueScroll clue = active ? service.getClue() : null;
		Step step = clue == null ? null : Stages.adjusted(npcPlaces.adjusted(resolve(clue)), this::owns, playerTile());
		StepChange change = state.tick(active, step == null ? null : step.getKey(),
			step == null ? null : List.of(step.getTargets(), step.getStages()), clue == null && carriesClue());
		if (change != StepChange.UNCHANGED)
		{
			current = step;
			for (StepListener listener : listeners)
			{
				listener.onStepChange(change, step);
			}
		}
		return wasActive != state.isActive() || neededRead != state.needsRead() || hadStep != state.hasStep();
	}

	private Step resolve(ClueScroll clue)
	{
		try
		{
			return resolver.resolve(clue, core);
		}
		catch (RuntimeException e)
		{
			if (loggedFailures.add(clue.getClass().getName()))
			{
				log.warn("Could not read the Clue Scroll plugin's {}", clue.getClass().getSimpleName(), e);
			}
			return StepResolver.unreadable(clue);
		}
	}

	private WorldPoint playerTile()
	{
		Player local = client.getLocalPlayer();
		return local == null ? null : local.getWorldLocation();
	}

	private boolean owns(int itemId)
	{
		PlayerSnapshot banked = availability.getBankedSnapshot();
		return player.counts(itemId) && (holds(InventoryID.INV, itemId) || holds(InventoryID.WORN, itemId)
			|| banked != null && (banked.getBank().containsKey(itemId) || banked.getGroupStorage().containsKey(itemId)));
	}

	private boolean holds(int containerId, int itemId)
	{
		ItemContainer items = client.getItemContainer(containerId);
		return items != null && items.contains(itemId);
	}

	/** Client thread only. Whether the inventory holds a clue item, read or not. */
	public boolean carriesClue()
	{
		if (inventoryChanged)
		{
			inventoryChanged = false;
			inventoryHoldsClue = inventoryHoldsClue();
		}
		return inventoryHoldsClue;
	}

	/** A clue item has item param 623, except the shared beginner and master clues, which have none. */
	private boolean inventoryHoldsClue()
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory == null)
		{
			return false;
		}
		for (Item item : inventory.getItems())
		{
			int id = item.getId();
			if (id == ItemID.TRAIL_CLUE_BEGINNER || id == ItemID.TRAIL_CLUE_MASTER
				|| id > 0 && client.getItemDefinition(id).getIntValue(ParamID.CLUE_SCROLL) != NO_CLUE_PARAM)
			{
				return true;
			}
		}
		return false;
	}

	private ClueScrollPlugin findCore()
	{
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (plugin instanceof ClueScrollPlugin)
			{
				return (ClueScrollPlugin) plugin;
			}
		}
		return null;
	}

	private boolean isCoreActive()
	{
		Plugin c = core;
		return c != null && pluginManager.isPluginActive(c);
	}

	/** Called on the client thread. */
	public interface StepListener
	{
		/** @param step the current step, or null after {@link StepChange#CLEARED} */
		void onStepChange(StepChange change, Step step);
	}

	/**
	 * ClueLink's rules without the client, so recorded logs replay through them. A new step is told by its key, never by
	 * object identity: core hands out the same static entry again for re-reads and later trails.
	 */
	static final class LinkState
	{
		private volatile boolean active;
		private volatile boolean needsRead;
		private volatile boolean hasStep;
		private String lastKey;
		private Object lastTargets;

		void start(boolean coreActive)
		{
			hasStep = false;
			needsRead = false;
			lastKey = null;
			lastTargets = null;
			setActive(coreActive);
		}

		/** Swing thread. @return whether the active flag changed */
		boolean onPluginChanged(boolean loaded)
		{
			return setActive(loaded);
		}

		StepChange tick(boolean coreActive, String key, Object targets, boolean inventoryHoldsClue)
		{
			setActive(coreActive);
			String seen = coreActive ? key : null;
			needsRead = coreActive && seen == null && inventoryHoldsClue;
			StepChange change = observe(seen, targets);
			lastKey = seen;
			lastTargets = seen == null ? null : targets;
			hasStep = seen != null;
			return change;
		}

		boolean isActive()
		{
			return active;
		}

		boolean needsRead()
		{
			return needsRead;
		}

		boolean hasStep()
		{
			return hasStep;
		}

		private StepChange observe(String key, Object targets)
		{
			if (key == null)
			{
				return hasStep ? StepChange.CLEARED : StepChange.UNCHANGED;
			}
			if (!hasStep || !key.equals(lastKey))
			{
				return StepChange.NEW;
			}
			return Objects.equals(targets, lastTargets) ? StepChange.UNCHANGED : StepChange.UPDATED;
		}

		private boolean setActive(boolean nowActive)
		{
			boolean changed = active != nowActive;
			active = nowActive;
			return changed;
		}
	}
}
