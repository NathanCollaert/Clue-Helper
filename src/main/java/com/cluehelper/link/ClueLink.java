package com.cluehelper.link;

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
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.*;
import net.runelite.client.plugins.cluescrolls.*;
import net.runelite.client.plugins.cluescrolls.clues.*;
import static java.util.stream.Collectors.toUnmodifiableSet;

/**
 * Core is read once per tick, at GameTick priority -1, after core's own handlers have updated it. Its active state
 * comes from {@code isPluginActive} and {@link PluginChanged}, never from {@code isPluginEnabled} or the config key,
 * which both flip before core actually stops or starts.
 */
@Slf4j
@Singleton
public class ClueLink
{
	/**
	 * By the quest that places a clue's NPC, the varbit the game's own clue script reads for which of the step's targets
	 * the NPC stands at, counting from 1 in the targets' order; 0 before the quest places it.
	 */
	private static final Map<String, Integer> PLACE_VARBITS = Map.of("CURSE_OF_THE_EMPTY_LORD", VarbitID.SECRET_GHOST_RANDOMISER);

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
	ThreeStepSwap threeStepSwap;
	final LinkState state = new LinkState();
	final List<StepListener> listeners = new CopyOnWriteArrayList<>();
	final Set<String> loggedFailures = ConcurrentHashMap.newKeySet();

	/** Null if the client has no Clue Scroll plugin. */
	@Getter
	volatile ClueScrollPlugin core;
	/** Null when core has no clue and no three-step swap is under way. */
	@Getter
	volatile Step currentStep;
	/**
	 * Null while core has a clue. Otherwise the step of the easy-to-elite clue that came into the inventory last, which
	 * the planner plans ahead so reading it guides at once; nothing shows it.
	 */
	@Getter
	volatile Step aheadStep;
	/** Client thread. The inventory's easy-to-elite clues by item, in the order they came in. */
	final Map<Integer, ClueScroll> heldClues = new LinkedHashMap<>();
	Set<Integer> inventoryIds = Set.of();

	/** Swing thread. Core's instance exists even while it is off. */
	public void start()
	{
		core = findCore();
		currentStep = null;
		aheadStep = null;
		heldClues.clear();
		inventoryIds = Set.of();
		state.start(isCoreActive());
	}

	public void stop()
	{
		core = null;
		currentStep = null;
		aheadStep = null;
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
	 * Client thread. Core outlines this NPC itself. It marks its clue's NPC only some of the time: not after a map load
	 * until the NPC spawns again, not inside a cave reached from the clue's tile (Fycie in Rantz's cave), nor where it
	 * has the NPC on another floor.
	 */
	public boolean coreMarks(NPC npc)
	{
		ClueScrollPlugin plugin = core;
		List<NPC> marked = plugin == null ? null : plugin.getNpcsToMark();
		return marked != null && marked.contains(npc) && drawsItsNpcsHere(plugin);
	}

	/**
	 * An anagram, cipher or music clue draws its NPCs only while its own tile is in the scene: Prospector Percy, whom core
	 * places on Falador's surface, stands in the Motherlode Mine.
	 */
	private boolean drawsItsNpcsHere(ClueScrollPlugin plugin)
	{
		ClueScroll clue = coreClue();
		if (!(clue instanceof AnagramClue || clue instanceof CipherClue || clue instanceof MusicClue))
		{
			return true;
		}
		WorldPoint tile = ((LocationClueScroll) clue).getLocation(plugin);
		return tile != null && tile.isInScene(client);
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
		Step coreStep = clue == null ? null : stepOf(clue);
		threeStepSwap.onGameTick(clue, coreStep);
		Step step = threeStepSwap.guided(coreStep);
		trackHeldClues();
		ClueScroll held = newestHeldClue();
		aheadStep = active && step == null && held != null ? stepOf(held) : null;
		StepChange change = state.tick(active, step == null ? null : step.getKey(),
			step == null ? null : List.of(step.getTargets(), step.getStages()));
		if (change != StepChange.UNCHANGED)
		{
			currentStep = step;
			listeners.forEach(listener -> listener.onStepChange(change, step));
		}
		return wasActive != state.isActive() || hadStep != state.hasStep();
	}

	/** The clue's step as the player's state leaves it. */
	private Step stepOf(ClueScroll clue)
	{
		return Stages.adjusted(atPlacedNpc(resolve(clue)), this::owns, playerTile());
	}

	/** Notes each clue item that came into the inventory since the last tick, and forgets those that left it. */
	private void trackHeldClues()
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory == null)
		{
			return;
		}
		Set<Integer> ids = Arrays.stream(inventory.getItems()).map(Item::getId).collect(toUnmodifiableSet());
		heldClues.keySet().retainAll(ids);
		for (int id : ids)
		{
			ClueScroll clue = inventoryIds.contains(id) ? null : CoreClueReader.clueOfItem(id);
			if (clue != null)
			{
				heldClues.put(id, clue);
			}
		}
		inventoryIds = ids;
	}

	private ClueScroll newestHeldClue()
	{
		List<ClueScroll> held = List.copyOf(heldClues.values());
		return held.isEmpty() ? null : held.get(held.size() - 1);
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

	/** The step at the one target the game placed its NPC at; at all of them before the game has placed it. */
	private Step atPlacedNpc(Step step)
	{
		StepIndex.LearnPlace learn = step.getData().getLearnPlace();
		if (learn == null)
		{
			return step;
		}
		int place = client.getVarbitValue(PLACE_VARBITS.get(learn.getQuest()));
		List<StepIndex.Target> targets = step.getData().getTargets();
		return place < 1 || place > targets.size() ? step : Stages.atNpc(step, targets.get(place - 1).getNpcs().get(0));
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
