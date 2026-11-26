package com.cluehelper;

import com.cluehelper.guide.*;
import com.cluehelper.hotcold.HotColdService;
import com.cluehelper.link.*;
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
	private static final Set<String> HOT_COLD_KEYS = Set.of("avoidWilderness", "stopWildernessClues");
	/** The on/off setting the Overlay dropdown replaced. */
	private static final String OLD_SHOW_BOX_KEY = "showBox";
	private static final String GUIDE_BOX_KEY = "guideBox";

	@Inject
	ClueLink clueLink;

	@Inject
	ThreeStepSwap threeStepSwap;

	@Inject
	MemoryStore memory;

	@Inject
	BankMemory bankMemory;

	@Inject
	StashMemory stashMemory;

	@Inject
	CheckLines checkLines;

	@Inject
	GrownPatches grownPatches;

	@Inject
	UnlockMemory unlocks;

	@Inject
	KeyRingMemory keyRing;

	@Inject
	PohService poh;

	@Inject
	PlayerModel playerModel;

	@Inject
	AvailabilityService availability;

	@Inject
	BundledData bundledData;

	@Inject
	PlannerRunner planner;
	@Inject
	BackgroundThread backgroundThread;

	@Inject
	RoutePlanner routes;

	@Inject
	StepGuide guide;

	@Inject
	ActWatcher actWatcher;

	@Inject
	HotColdService hotCold;

	@Inject
	ClientThread clientThread;

	@Inject
	Client client;

	@Inject
	ClueHelperConfig config;

	@Inject
	ConfigManager configManager;

	@Inject
	OverlayManager overlayManager;

	@Inject
	ItemHighlightOverlay itemOverlay;

	@Inject
	WidgetHighlightOverlay widgetOverlay;

	@Inject
	MenuLineOverlay menuLineOverlay;

	@Inject
	FurnitureOverlay furnitureOverlay;

	@Inject
	WanderAreaOverlay wanderAreaOverlay;

	@Inject
	MenuLines menuLines;

	@Inject
	EasyTeleportsNames easyTeleportsNames;

	@Inject
	WaypointOverlay waypointOverlay;

	@Inject
	MinimapPathOverlay minimapPathOverlay;

	@Inject
	GuideOverlay guideOverlay;
	@Inject
	GuideBoxPresenter box;
	@Inject
	CoreBoxMerge coreBoxMerge;

	@Inject
	SceneDoors sceneDoors;

	@Inject
	WithdrawTab withdrawTab;

	@Inject
	FairyLogFilter fairyLogFilter;

	@Inject
	RouteReport routeReport;


	final List<Runnable> unregister = new ArrayList<>();
	boolean shownWorking;
	List<Object> shownSwapLines = List.of();

	@Provides
	ClueHelperConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ClueHelperConfig.class);
	}

	@Override
	protected void startUp()
	{
		backgroundThread.start();
		carryOverHiddenBox();
		guideOverlay.show(GuideBox.EMPTY);
		clueLink.start();
		playerModel.start();
		poh.start();
		availability.start();
		bundledData.getCatalog().thenAccept(availability::setCatalog);
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
		clientThread.invokeLater(this::mergeCoreBox);
	}

	@Override
	protected void shutDown()
	{
		for (Overlay overlay : overlays())
		{
			overlayManager.remove(overlay);
		}
		coreBoxMerge.giveBack(clueLink.isActive());
		unregister.forEach(Runnable::run);
		unregister.clear();
		routeReport.stop();
		withdrawTab.stop();
		fairyLogFilter.stop();
		hotCold.stop();
		guide.stop();
		routes.stop();
		planner.cancelAll();
		backgroundThread.stop();
		availability.stop();
		poh.stop();
		playerModel.stop();
		clueLink.stop();
	}

	@Subscribe
	public void onPluginChanged(PluginChanged event)
	{
		easyTeleportsNames.onPluginChanged();
		if (clueLink.onPluginChanged(event))
		{
			clientThread.invokeLater(box::refresh);
			clientThread.invokeLater(this::mergeCoreBox);
		}
	}

	/** Runs after core's priority-0 handlers, which update its clue. */
	@Subscribe(priority = -1)
	public void onGameTick(GameTick tick)
	{
		if (clueLink.onGameTick())
		{
			box.refresh();
			mergeCoreBox();
		}
		bankMemory.onGameTick();
		poh.onGameTick();
		menuLines.takeFreshList(grownPatches::onListMenu);
		unlocks.onGameTick();
		playerModel.onGameTick();
		availability.onGameTick();
		routes.onGameTick();
		hotCold.onGameTick();
		guide.onGameTick();
		withdrawTab.refresh();
		fairyLogFilter.refresh();
		List<Object> swapLines = Arrays.asList(threeStepSwap.headline(), threeStepSwap.notes());
		if (planner.isWorking() != shownWorking || !swapLines.equals(shownSwapLines))
		{
			shownWorking = planner.isWorking();
			shownSwapLines = swapLines;
			box.refresh();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
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
		menuLines.colourLines(guide.getHighlights().getMenuTarget(), guide.getHighlights().getScroll(), config.highlightMenus());
	}

	/** Runs after Menu Entry Swapper's swaps, so the menu's top entry is the real left click. */
	@Subscribe(priority = -1)
	public void onPostMenuSort(PostMenuSort event)
	{
		guide.onPostMenuSort();
		threeStepSwap.onPostMenuSort();
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		threeStepSwap.onItemSpawned(event);
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		threeStepSwap.onItemDespawned(event);
	}

	/** Runs after other plugins build the menu; only marks our option, never adds, removes or reorders entries. */
	@Subscribe(priority = -1)
	public void onMenuOpened(MenuOpened event)
	{
		menuLines.forgetMenu();
		menuLines.colourMenu(guide.getHighlights().getMenuTarget(), config.highlightMenus());
		menuLines.colourMenu(guide.getHighlights().getOptionTarget(), config.highlightMenus());
		for (MenuTarget pathObject : waypointOverlay.pathObjectTargets())
		{
			menuLines.colourMenu(pathObject, config.highlightMenus());
		}
	}

	/** Reads the menu scripts' arguments before better-teleport-menu (priority 1) rewrites them. */
	@Subscribe(priority = 2)
	public void onScriptPreFired(ScriptPreFired event)
	{
		menuLines.onScriptPreFired(event);
		poh.onScriptPreFired(event);
		fairyLogFilter.onScriptPreFired(event);
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		withdrawTab.onScriptPostFired(event);
		fairyLogFilter.onScriptPostFired(event);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		stashMemory.onMenuOptionClicked(event);
		keyRing.onMenuOptionClicked(event);
		sceneDoors.onMenuOptionClicked(event);
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
		threeStepSwap.onGameStateChanged(event);
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
		if (MusicTracks.tellsOfAnUnlock(event))
		{
			playerModel.onMusicTrackUnlocked();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		easyTeleportsNames.onConfigChanged(event);
		if (grownPatches.isOurs(event))
		{
			playerModel.onGrownPatchChanged();
		}
		if (!ClueHelperConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		clientThread.invokeLater(box::refresh);
		clientThread.invokeLater(this::mergeCoreBox);
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
	}

	/** A player who turned the old "Show the box" off keeps no box. */
	private void carryOverHiddenBox()
	{
		String oldShowBox = configManager.getConfiguration(ClueHelperConfig.GROUP, OLD_SHOW_BOX_KEY);
		if (oldShowBox == null)
		{
			return;
		}
		if (Boolean.FALSE.toString().equals(oldShowBox))
		{
			configManager.setConfiguration(ClueHelperConfig.GROUP, GUIDE_BOX_KEY, GuideBoxStyle.NONE);
		}
		configManager.unsetConfiguration(ClueHelperConfig.GROUP, OLD_SHOW_BOX_KEY);
	}

	/** Reads core's state when it runs, so a call queued before shutDown finds core inactive and changes nothing. */
	private void mergeCoreBox()
	{
		coreBoxMerge.apply(clueLink.isActive() ? clueLink.getCore() : null);
	}

	private Overlay[] overlays()
	{
		return new Overlay[]{itemOverlay, widgetOverlay, waypointOverlay, minimapPathOverlay, wanderAreaOverlay, menuLineOverlay, furnitureOverlay, guideOverlay};
	}
}
