package com.cluehelper.link;

import com.cluehelper.memory.NpcPlaceMemory;
import com.cluehelper.player.*;
import com.cluehelper.step.*;
import com.cluehelper.transport.AvailabilityService;
import java.util.*;
import java.util.concurrent.*;
import javax.inject.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
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

	@Inject
	PluginManager pluginManager;
	@Inject
	ClueScrollService service;
	@Inject
	Client client;
	@Inject
	StepResolver resolver;
	@Inject
	AvailabilityService availability;
	@Inject
	PlayerModel player;
	@Inject
	NpcPlaceMemory npcPlaces;
	final LinkState state = new LinkState();
	final List<StepListener> listeners = new CopyOnWriteArrayList<>();
	final Set<String> loggedFailures = ConcurrentHashMap.newKeySet();

	/** Null if the client has no Clue Scroll plugin. */
	@Getter
	volatile ClueScrollPlugin core;
	/** Null when core has no clue. */
	@Getter
	volatile Step currentStep;

	/** Swing thread. Core's instance exists even while it is off. */
	public void start()
	{
		core = findCore();
		currentStep = null;
		state.start(isCoreActive());
	}

	public void stop()
	{
		core = null;
		currentStep = null;
		state.start(false);
	}

	public boolean isActive()
	{
		return state.isActive();
	}

	public boolean hasStep()
	{
		return state.hasStep();
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

	/**
	 * Call from a GameTick handler at priority -1.
	 *
	 * @return whether anything the box shows changed: active or has-step
	 */
	public boolean onGameTick()
	{
		boolean wasActive = state.isActive();
		boolean hadStep = state.hasStep();

		boolean active = isCoreActive();
		ClueScroll clue = active ? service.getClue() : null;
		Step step = clue == null ? null : Stages.adjusted(npcPlaces.adjusted(resolve(clue)), this::owns, playerTile());
		StepChange change = state.tick(active, step == null ? null : step.getKey(),
			step == null ? null : List.of(step.getTargets(), step.getStages()));
		if (change != StepChange.UNCHANGED)
		{
			currentStep = step;
			listeners.forEach(listener -> listener.onStepChange(change, step));
		}
		return wasActive != state.isActive() || hadStep != state.hasStep();
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

	private ClueScrollPlugin findCore()
	{
		return pluginManager.getPlugins().stream().filter(ClueScrollPlugin.class::isInstance).map(ClueScrollPlugin.class::cast)
			.findFirst().orElse(null);
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
		@Getter
		volatile boolean active;
		volatile boolean hasStep;
		String lastKey;
		Object lastTargets;

		void start(boolean coreActive)
		{
			hasStep = false;
			lastKey = null;
			lastTargets = null;
			setActive(coreActive);
		}

		/** Swing thread. @return whether the active flag changed */
		boolean onPluginChanged(boolean loaded)
		{
			return setActive(loaded);
		}

		StepChange tick(boolean coreActive, String key, Object targets)
		{
			setActive(coreActive);
			String seen = coreActive ? key : null;
			StepChange change = observe(seen, targets);
			lastKey = seen;
			lastTargets = seen == null ? null : targets;
			hasStep = seen != null;
			return change;
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
