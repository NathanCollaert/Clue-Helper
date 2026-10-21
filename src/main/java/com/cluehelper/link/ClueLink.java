package com.cluehelper.link;

import com.cluehelper.step.Step;
import com.cluehelper.step.StepResolver;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ParamID;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.plugins.cluescrolls.ClueScrollService;
import net.runelite.client.plugins.cluescrolls.clues.ClueScroll;

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

	private final PluginManager pluginManager;
	private final ClueScrollService service;
	private final Client client;
	private final StepResolver resolver;
	private final LinkState state = new LinkState();
	private final List<StepListener> listeners = new CopyOnWriteArrayList<>();
	private final Set<String> loggedFailures = ConcurrentHashMap.newKeySet();

	private volatile ClueScrollPlugin core;
	private volatile Step current;
	private volatile boolean inventoryChanged;
	private boolean inventoryHoldsClue;

	@Inject
	ClueLink(PluginManager pluginManager, ClueScrollService service, Client client, StepResolver resolver)
	{
		this.pluginManager = pluginManager;
		this.service = service;
		this.client = client;
		this.resolver = resolver;
	}

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
	 * @return whether anything the panel shows changed: active, needs-read or has-step
	 */
	public boolean onGameTick()
	{
		boolean wasActive = state.isActive();
		boolean neededRead = state.needsRead();
		boolean hadStep = state.hasStep();

		boolean active = isCoreActive();
		ClueScroll clue = active ? service.getClue() : null;
		Step step = clue == null ? null : resolve(clue);
		StepChange change = state.tick(active, step == null ? null : step.getKey(),
			step == null ? null : List.of(step.getTargets(), step.getStages()), clue == null && holdsClue());
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

	private boolean holdsClue()
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
}
