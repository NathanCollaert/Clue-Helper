package com.cluehelper;

import com.cluehelper.dev.DevHarness;
import com.cluehelper.guide.ActWatcher;
import com.cluehelper.guide.MenuLines;
import com.cluehelper.guide.MenuTarget;
import com.cluehelper.guide.StepGuide;
import com.cluehelper.hotcold.HotColdService;
import com.cluehelper.link.ClueLink;
import com.cluehelper.memory.BankMemory;
import com.cluehelper.memory.FairyRingMemory;
import com.cluehelper.memory.GrownPatches;
import com.cluehelper.memory.MemoryStore;
import com.cluehelper.memory.SpiritTreeMemory;
import com.cluehelper.memory.XericsHonourMemory;
import com.cluehelper.memory.StashMemory;
import com.cluehelper.player.CheckLines;
import com.cluehelper.player.PlayerModel;
import com.cluehelper.poh.PohService;
import com.cluehelper.route.PlannerRunner;
import com.cluehelper.route.RoutePlanner;
import com.cluehelper.route.SceneDoors;
import com.cluehelper.transport.AvailabilityService;
import com.cluehelper.transport.CatalogData;
import com.cluehelper.ui.FurnitureOverlay;
import com.cluehelper.ui.GuideBoxPresenter;
import com.cluehelper.ui.WanderAreaOverlay;
import com.cluehelper.ui.GuideBox;
import com.cluehelper.ui.GuideOverlay;
import com.cluehelper.ui.ItemHighlightOverlay;
import com.cluehelper.ui.MenuLineOverlay;
import com.cluehelper.ui.BoxState;
import com.cluehelper.ui.WidgetHighlightOverlay;
import com.cluehelper.ui.WaypointOverlay;
import com.google.inject.Provides;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Named;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "Clue Helper",
	description = "Fastest clue routes from what you own, alongside the Clue Scroll plugin",
	tags = {"clue", "clues", "treasure", "trails", "teleport", "route"}
)
@PluginDependency(ClueScrollPlugin.class)
public class ClueHelperPlugin extends Plugin
{
	private static final Set<String> HOT_COLD_KEYS = Set.of("hotColdPlanner", "avoidWilderness");

	@Inject
	@Getter(AccessLevel.PACKAGE)
	private ClueLink clueLink;

	@Inject
	private MemoryStore memory;

	@Inject
	@Getter(AccessLevel.PACKAGE)
	private BankMemory bankMemory;

	@Inject
	private StashMemory stashMemory;

	@Inject
	private CheckLines checkLines;

	@Inject
	@Getter(AccessLevel.PACKAGE)
	private SpiritTreeMemory spiritTrees;

	@Inject
	private GrownPatches grownPatches;

	@Inject
	private XericsHonourMemory xericsHonour;

	@Inject
	@Getter(AccessLevel.PACKAGE)
	private FairyRingMemory fairyRings;

	@Inject
	@Getter(AccessLevel.PACKAGE)
	private PohService poh;

	@Inject
	@Getter(AccessLevel.PACKAGE)
	private PlayerModel playerModel;

	@Inject
	@Getter(AccessLevel.PACKAGE)
	private AvailabilityService availability;

	@Inject
	private CatalogData catalog;

	@Inject
	private PlannerRunner planner;

	@Inject
	@Getter(AccessLevel.PACKAGE)
	private RoutePlanner routes;

	@Inject
	@Getter(AccessLevel.PACKAGE)
	private StepGuide guide;

	@Inject
	private ActWatcher actWatcher;

	@Inject
	@Getter(AccessLevel.PACKAGE)
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
	@Getter(AccessLevel.PACKAGE)
	private MenuLines menuLines;

	@Inject
	private WaypointOverlay waypointOverlay;

	@Inject
	@Getter(AccessLevel.PACKAGE)
	private GuideOverlay guideOverlay;
	@Inject
	private GuideBoxPresenter box;

	@Inject
	private SceneDoors sceneDoors;


	@Inject
	@Getter(AccessLevel.PACKAGE)
	private DevHarness devHarness;

	@Inject
	@Named("developerMode")
	private boolean developerMode;

	private Runnable unregisterRoutes;
	private Runnable unregisterGuide;
	private Runnable unregisterHotCold;
	private boolean shownWorking;
	private boolean shownCarryingClue;

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
		clientThread.invokeLater(spiritTrees::publish);
		clientThread.invokeLater(grownPatches::publish);
		clientThread.invokeLater(fairyRings::publish);
		clientThread.invokeLater(xericsHonour::publish);
		availability.start();
		catalog.get().thenAccept(loaded -> availability.setCatalog(loaded.getTeleports()));
		routes.start();
		guide.start();
		hotCold.start();
		unregisterRoutes = routes.addListener(plan -> showBox());
		unregisterGuide = guide.addListener(this::showBox);
		unregisterHotCold = hotCold.addListener(this::showBox);
		for (Overlay overlay : overlays())
		{
			overlayManager.add(overlay);
		}
		clientThread.invokeLater(this::showBox);
		if (developerMode)
		{
			devHarness.start(() -> guideOverlay.getShown().getState(), this::boxState);
		}
	}

	@Override
	protected void shutDown()
	{
		devHarness.stop();
		for (Overlay overlay : overlays())
		{
			overlayManager.remove(overlay);
		}
		unregisterHotCold.run();
		unregisterGuide.run();
		unregisterRoutes.run();
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
			clientThread.invokeLater(this::showBox);
		}
	}

	/** Runs after core's priority-0 handlers, which update its clue. */
	@Subscribe(priority = -1)
	public void onGameTick(GameTick tick)
	{
		if (clueLink.onGameTick())
		{
			showBox();
		}
		bankMemory.onGameTick();
		poh.onGameTick();
		menuLines.takeFreshList(spiritTrees::onListMenu);
		spiritTrees.onGameTick();
		fairyRings.onGameTick();
		xericsHonour.onGameTick();
		playerModel.onGameTick();
		availability.onGameTick();
		routes.onGameTick();
		hotCold.onGameTick();
		guide.onGameTick();
		boolean carryingClue = clueLink.carriesClue();
		if (planner.isWorking() != shownWorking || carryingClue != shownCarryingClue)
		{
			shownWorking = planner.isWorking();
			shownCarryingClue = carryingClue;
			showBox();
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
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		bankMemory.onWidgetClosed(event);
		menuLines.onWidgetClosed(event);
	}

	@Subscribe
	public void onClientTick(ClientTick tick)
	{
		menuLines.colourLines(guide.getMenuTarget(), guide.getHighlightMenuWidget(), config.highlightMenus());
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
		menuLines.colourMenu(guide.getMenuTarget(), config.highlightMenus());
		menuLines.colourMenu(guide.getOptionTarget(), config.highlightMenus());
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
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		stashMemory.onMenuOptionClicked(event);
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
		bankMemory.onGameStateChanged(event);
		poh.onGameStateChanged(event);
		xericsHonour.onGameStateChanged(event);
		guide.onGameStateChanged(event);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		hotCold.onChatMessage(event);
		actWatcher.onChatMessage(event);
		guide.onChatMessage(event);
		stashMemory.onChatMessage(event);
		xericsHonour.onChatMessage(event);
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
		clientThread.invokeLater(this::showBox);
		if (HOT_COLD_KEYS.contains(event.getKey()))
		{
			clientThread.invokeLater(() ->
			{
				hotCold.onConfigChanged();
				showBox();
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
		spiritTrees.publish();
		grownPatches.publish();
		fairyRings.publish();
		xericsHonour.publish();
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		devHarness.onCommandExecuted(event);
	}

	BoxState boxState()
	{
		return box.boxState();
	}

	private Overlay[] overlays()
	{
		return new Overlay[]{itemOverlay, widgetOverlay, waypointOverlay, wanderAreaOverlay, menuLineOverlay, furnitureOverlay, guideOverlay};
	}


	/** Client thread only: reads the guide's state into one value for the overlay. */
	private void showBox()
	{
		box.refresh();
	}
}
