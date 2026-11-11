package com.cluehelper;

import com.cluehelper.guide.*;
import com.cluehelper.hotcold.HotColdService;
import com.cluehelper.link.ClueLink;
import com.cluehelper.memory.*;
import com.cluehelper.player.*;
import com.cluehelper.poh.PohService;
import com.cluehelper.route.*;
import com.cluehelper.transport.*;
import com.cluehelper.ui.*;
import com.google.inject.Provides;
import java.util.*;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.*;
import net.runelite.client.plugins.*;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.ui.overlay.*;

@Slf4j
@PluginDescriptor(
	name = "Clue Helper",
	description = "A click-by-click guide to every clue step, routed from the teleports you own",
	tags = {"clue", "clues", "scroll", "treasure", "trails", "guide", "helper", "route", "teleport", "fastest", "step", "navigation", "highlight", "hot", "cold", "stash"}
)
@PluginDependency(ClueScrollPlugin.class)
@PluginDependency(BankTagsPlugin.class)
public class ClueHelperPlugin extends Plugin
{
	private static final Set<String> HOT_COLD_KEYS = Set.of("hotColdPlanner", "avoidWilderness");

	@Inject
	private ClueLink clueLink;

	@Inject
	private MemoryStore memory;

	@Inject
	private BankMemory bankMemory;

	@Inject
	private StashMemory stashMemory;

	@Inject
	private CheckLines checkLines;

	@Inject
	private SpiritTreeMemory spiritTrees;

	@Inject
	private GrownPatches grownPatches;

	@Inject
	private UnlockMemory unlocks;

	@Inject
	private KeyRingMemory keyRing;

	@Inject
	private NpcPlaceMemory npcPlaces;

	@Inject
	private FairyRingMemory fairyRings;

	@Inject
	private PohService poh;

	@Inject
	private PlayerModel playerModel;

	@Inject
	private AvailabilityService availability;

	@Inject
	private CatalogData catalog;

	@Inject
	private PlannerRunner planner;

	@Inject
	private RoutePlanner routes;

	@Inject
	private StepGuide guide;

	@Inject
	private ActWatcher actWatcher;

	@Inject
	private HotColdService hotCold;

	@Inject
	private ClientThread clientThread;

	@Inject
	private Client client;

	@Inject
	private ClueHelperConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ItemHighlightOverlay itemOverlay;

	@Inject
	private WidgetHighlightOverlay widgetOverlay;

	@Inject
	private MenuLineOverlay menuLineOverlay;

	@Inject
	private FurnitureOverlay furnitureOverlay;

	@Inject
	private WanderAreaOverlay wanderAreaOverlay;

	@Inject
	private MenuLines menuLines;

	@Inject
	private WaypointOverlay waypointOverlay;

	@Inject
	private MinimapPathOverlay minimapPathOverlay;

	@Inject
	private GuideOverlay guideOverlay;
	@Inject
	private GuideBoxPresenter box;

	@Inject
	private SceneDoors sceneDoors;

	@Inject
	private WithdrawTab withdrawTab;

	@Inject
	private RouteReport routeReport;

	@Inject
	private JournalScroll journalScroll;


	private final List<Runnable> unregister = new ArrayList<>();
	private boolean shownWorking;

	@Provides
	ClueHelperConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ClueHelperConfig.class);
	}

	@Override
	protected void startUp()
	{
		guideOverlay.show(GuideBox.EMPTY);
		clueLink.start();
		playerModel.start();
		poh.start();
		clientThread.invokeLater(this::publishMemories);
		availability.start();
		catalog.get().thenAccept(loaded -> availability.setCatalog(loaded.getTeleports()));
		routes.start();
		guide.start();
		hotCold.start();
		withdrawTab.start();
		routeReport.start();
		unregister.add(routes.addListener(plan -> box.refresh()));
		unregister.add(guide.addListener(box::refresh));
		unregister.add(hotCold.addListener(box::refresh));
		for (Overlay overlay : overlays())
		{
			overlayManager.add(overlay);
		}
		clientThread.invokeLater(box::refresh);
	}

	@Override
	protected void shutDown()
	{
		for (Overlay overlay : overlays())
		{
			overlayManager.remove(overlay);
		}
		unregister.forEach(Runnable::run);
		unregister.clear();
		routeReport.stop();
		withdrawTab.stop();
		hotCold.stop();
		guide.stop();
		routes.stop();
		planner.cancelAll();
		availability.stop();
		poh.stop();
		playerModel.stop();
		clueLink.stop();
	}

	@Subscribe
	public void onPluginChanged(PluginChanged event)
	{
		if (clueLink.onPluginChanged(event))
		{
			clientThread.invokeLater(box::refresh);
		}
	}

	/** Runs after core's priority-0 handlers, which update its clue. */
	@Subscribe(priority = -1)
	public void onGameTick(GameTick tick)
	{
		npcPlaces.onGameTick();
		journalScroll.onGameTick();
		if (clueLink.onGameTick())
		{
			box.refresh();
		}
		bankMemory.onGameTick();
		poh.onGameTick();
		menuLines.takeFreshList(spiritTrees::onListMenu);
		spiritTrees.onGameTick();
		fairyRings.onGameTick();
		unlocks.onGameTick();
		playerModel.onGameTick();
		availability.onGameTick();
		routes.onGameTick();
		hotCold.onGameTick();
		guide.onGameTick();
		withdrawTab.refresh();
		if (planner.isWorking() != shownWorking)
		{
			shownWorking = planner.isWorking();
			box.refresh();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		clueLink.onItemContainerChanged(event);
		actWatcher.onItemContainerChanged(event);
		playerModel.onItemContainerChanged(event);
		bankMemory.onItemContainerChanged(event);
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		bankMemory.onWidgetLoaded(event);
		guide.onWidgetLoaded(event);
		withdrawTab.onWidgetLoaded(event);
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		bankMemory.onWidgetClosed(event);
		menuLines.onWidgetClosed(event);
		withdrawTab.onWidgetClosed(event);
	}

	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		menuLines.colourLines(guide.getHighlights().getMenuTarget(), guide.getHighlights().getMenuWidget(), config.highlightMenus());
	}

	/** Runs after Menu Entry Swapper's swaps, so the menu's top entry is the real left click. */
	@Subscribe(priority = -1)
	public void onPostMenuSort(PostMenuSort event)
	{
		guide.onPostMenuSort();
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		if (!client.isMenuOpen() && entries.length > 0)
		{
			fairyRings.onMenuEntries(new MenuEntry[]{entries[entries.length - 1]});
		}
	}

	/** Runs after other plugins build the menu; only colours our option, never adds, removes or reorders entries. */
	@Subscribe(priority = -1)
	public void onMenuOpened(MenuOpened event)
	{
		menuLines.colourMenu(guide.getHighlights().getMenuTarget(), config.highlightMenus());
		menuLines.colourMenu(guide.getHighlights().getOptionTarget(), config.highlightMenus());
		for (MenuTarget pathObject : waypointOverlay.pathObjectTargets())
		{
			menuLines.colourMenu(pathObject, config.highlightMenus());
		}
		fairyRings.onMenuEntries(event.getMenuEntries());
	}

	/** Reads the menu scripts' arguments before better-teleport-menu (priority 1) rewrites them. */
	@Subscribe(priority = 2)
	public void onScriptPreFired(ScriptPreFired event)
	{
		menuLines.onScriptPreFired(event);
		poh.onScriptPreFired(event);
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		withdrawTab.onScriptPostFired(event);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		stashMemory.onMenuOptionClicked(event);
		keyRing.onMenuOptionClicked(event);
		npcPlaces.onMenuOptionClicked(event);
		sceneDoors.onMenuOptionClicked(event);
		fairyRings.onMenuOptionClicked(event);
		bankMemory.onMenuOptionClicked(event);
		poh.onMenuOptionClicked(event);
		guide.onMenuOptionClicked(event);
		checkLines.onMenuOptionClicked(event);
		waypointOverlay.onMenuOptionClicked(event);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		playerModel.onVarbitChanged(event);
		poh.onVarbitChanged(event);
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		playerModel.onStatChanged();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		playerModel.onGameStateChanged(event);
		routes.onGameStateChanged(event);
		bankMemory.onGameStateChanged(event);
		poh.onGameStateChanged(event);
		unlocks.onGameStateChanged(event);
		guide.onGameStateChanged(event);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		hotCold.onChatMessage(event);
		actWatcher.onChatMessage(event);
		guide.onChatMessage(event);
		stashMemory.onChatMessage(event);
		unlocks.onChatMessage(event);
		sceneDoors.onChatMessage(event);
		if (checkLines.onChatMessage(event))
		{
			playerModel.onChargesLearned();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (grownPatches.isOurs(event))
		{
			clientThread.invokeLater(grownPatches::publish);
		}
		if (!ClueHelperConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		clientThread.invokeLater(box::refresh);
		if (HOT_COLD_KEYS.contains(event.getKey()))
		{
			clientThread.invokeLater(() ->
			{
				hotCold.onConfigChanged();
				box.refresh();
			});
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		memory.reload();
		bankMemory.onProfileChanged();
		poh.onProfileChanged();
		hotCold.onProfileChanged();
		publishMemories();
	}

	private void publishMemories()
	{
		spiritTrees.publish();
		grownPatches.publish();
		fairyRings.publish();
		unlocks.publish();
		keyRing.publish();
	}

	private Overlay[] overlays()
	{
		return new Overlay[]{itemOverlay, widgetOverlay, waypointOverlay, minimapPathOverlay, wanderAreaOverlay, menuLineOverlay, furnitureOverlay, guideOverlay};
	}
}
