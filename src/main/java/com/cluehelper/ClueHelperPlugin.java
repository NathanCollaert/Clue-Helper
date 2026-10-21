package com.cluehelper;

import com.cluehelper.dev.DevHarness;
import com.cluehelper.guide.MenuLines;
import com.cluehelper.guide.StepGuide;
import com.cluehelper.hotcold.HotColdPlan;
import com.cluehelper.hotcold.HotColdService;
import com.cluehelper.hotcold.HotColdView;
import com.cluehelper.link.ClueLink;
import com.cluehelper.memory.BankMemory;
import com.cluehelper.memory.FairyRingMemory;
import com.cluehelper.memory.MemoryStore;
import com.cluehelper.memory.SpiritTreeMemory;
import com.cluehelper.memory.StashMemory;
import com.cluehelper.player.Book;
import com.cluehelper.player.CheckLines;
import com.cluehelper.player.HouseState;
import com.cluehelper.player.PlayerModel;
import com.cluehelper.poh.PohService;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.route.PlannerRunner;
import com.cluehelper.route.RoutePlanner;
import com.cluehelper.step.ClueTypes;
import com.cluehelper.step.Step;
import com.cluehelper.transport.AvailabilityService;
import com.cluehelper.transport.CatalogData;
import com.cluehelper.ui.ClueHelperPanel;
import com.cluehelper.ui.Icons;
import com.cluehelper.ui.FurnitureOverlay;
import com.cluehelper.ui.ItemHighlightOverlay;
import com.cluehelper.ui.MenuLineOverlay;
import com.cluehelper.ui.MinimapMarkerOverlay;
import com.cluehelper.ui.PanelModel;
import com.cluehelper.ui.PanelState;
import com.cluehelper.ui.WidgetHighlightOverlay;
import com.cluehelper.ui.WaypointOverlay;
import com.cluehelper.ui.WorldMapMarkers;
import com.google.inject.Provides;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Named;
import javax.swing.SwingUtilities;
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
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
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
	private static final int ULTIMATE_IRONMAN = 2;
	private static final String FORGET_HOUSE_KEY = "pohForget";
	private static final String HOUSE_KEY_PREFIX = "poh";
	private static final Set<String> HOT_COLD_KEYS = Set.of("hotColdPlanner", "hotColdWildernessPenalty");

	@Inject
	private ClientToolbar clientToolbar;

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
	@Getter(AccessLevel.PACKAGE)
	private FairyRingMemory fairyRings;

	@Inject
	@Getter(AccessLevel.PACKAGE)
	private PohService poh;

	@Inject
	private ConfigManager configManager;

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
	@Getter(AccessLevel.PACKAGE)
	private MenuLines menuLines;

	@Inject
	private WaypointOverlay waypointOverlay;

	@Inject
	private MinimapMarkerOverlay minimapOverlay;

	@Inject
	private WorldMapMarkers mapMarkers;

	@Inject
	@Getter(AccessLevel.PACKAGE)
	private DevHarness devHarness;

	@Inject
	@Named("developerMode")
	private boolean developerMode;

	@Getter(AccessLevel.PACKAGE)
	private ClueHelperPanel panel;
	private NavigationButton navigationButton;
	private Runnable unregisterRoutes;
	private Runnable unregisterGuide;
	private Runnable unregisterHotCold;
	private boolean shownWorking;

	@Provides
	ClueHelperConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ClueHelperConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new ClueHelperPanel();
		navigationButton = NavigationButton.builder()
			.tooltip("Clue Helper")
			.icon(Icons.logo())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navigationButton);
		clueLink.start();
		playerModel.start();
		poh.start();
		clientThread.invokeLater(spiritTrees::publish);
		clientThread.invokeLater(fairyRings::publish);
		availability.start();
		catalog.get().thenAccept(loaded -> availability.setCatalog(loaded.getTeleports()));
		routes.start();
		guide.start();
		hotCold.start();
		unregisterRoutes = routes.addListener(plan -> showPanelState());
		unregisterGuide = guide.addListener(this::onGuideChanged);
		unregisterHotCold = hotCold.addListener(this::showPanelState);
		for (Overlay overlay : overlays())
		{
			overlayManager.add(overlay);
		}
		clientThread.invokeLater(this::showPanelState);
		if (developerMode)
		{
			devHarness.start(this::shownPanelState, this::panelState);
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
		clientThread.invokeLater(mapMarkers::clear);
		routes.stop();
		planner.cancelAll();
		availability.stop();
		poh.stop();
		playerModel.stop();
		clueLink.stop();
		clientToolbar.removeNavigation(navigationButton);
		navigationButton = null;
		panel = null;
	}

	@Subscribe
	public void onPluginChanged(PluginChanged event)
	{
		if (clueLink.onPluginChanged(event))
		{
			clientThread.invokeLater(this::showPanelState);
		}
	}

	/** Runs after core's priority-0 handlers, which update its clue. */
	@Subscribe(priority = -1)
	public void onGameTick(GameTick tick)
	{
		if (clueLink.onGameTick())
		{
			showPanelState();
		}
		bankMemory.onGameTick();
		poh.onGameTick();
		menuLines.takeFreshList(spiritTrees::onListMenu);
		spiritTrees.onGameTick();
		fairyRings.onGameTick();
		playerModel.onGameTick();
		availability.onGameTick();
		routes.onGameTick();
		hotCold.onGameTick();
		guide.onGameTick();
		if (planner.isWorking() != shownWorking)
		{
			shownWorking = planner.isWorking();
			showPanelState();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		clueLink.onItemContainerChanged(event);
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
		fairyRings.onMenuOptionClicked(event);
		bankMemory.onMenuOptionClicked(event);
		poh.onMenuOptionClicked(event);
		guide.onMenuOptionClicked(event);
		checkLines.onMenuOptionClicked(event);
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
		guide.onGameStateChanged(event);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		hotCold.onChatMessage(event);
		guide.onChatMessage(event);
		stashMemory.onChatMessage(event);
		if (checkLines.onChatMessage(event))
		{
			playerModel.onChargesLearned();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!ClueHelperConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		clientThread.invokeLater(mapMarkers::refresh);
		if (FORGET_HOUSE_KEY.equals(event.getKey()) && config.pohForget())
		{
			configManager.setConfiguration(ClueHelperConfig.GROUP, FORGET_HOUSE_KEY, false);
			clientThread.invokeLater(poh::forget);
		}
		else if (event.getKey().startsWith(HOUSE_KEY_PREFIX))
		{
			clientThread.invokeLater(poh::onConfigChanged);
		}
		else if (HOT_COLD_KEYS.contains(event.getKey()))
		{
			clientThread.invokeLater(() ->
			{
				hotCold.onConfigChanged();
				showPanelState();
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
		fairyRings.publish();
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		devHarness.onCommandExecuted(event);
	}

	private PanelState shownPanelState()
	{
		ClueHelperPanel shown = panel;
		return shown == null ? null : shown.getShownState();
	}

	PanelState panelState()
	{
		return PanelState.of(dormantReason() != null, clueLink.isActive(), clueLink.needsRead(), clueLink.hasStep(), planner.isWorking());
	}

	private Overlay[] overlays()
	{
		return new Overlay[]{itemOverlay, widgetOverlay, waypointOverlay, minimapOverlay, menuLineOverlay, furnitureOverlay};
	}

	private void onGuideChanged()
	{
		mapMarkers.refresh();
		showPanelState();
	}

	private String dormantReason()
	{
		PlayerSnapshot snapshot = playerModel.getSnapshot();
		return snapshot == null ? null : snapshot.getDormantReason();
	}

	/** Client thread only: reads the guide's state, then hands it to Swing. */
	private void showPanelState()
	{
		ClueHelperPanel target = panel;
		if (target == null)
		{
			return;
		}
		PanelModel model = PanelModel.of(panelState(), dormantReason(), routes.getPlan(), guide.getRoute(), guide.getCurrent(),
			guide.getNext(), guide.isHighlightWorn(), stuckBookOnF2p(), bankInfo(), houseInfo(), guide.getMenuHint(),
			System.currentTimeMillis());
		PanelModel shown = model.getState().showsRoutes() ? model.toBuilder().hotCold(hotColdView()).build() : model;
		SwingUtilities.invokeLater(() -> target.show(shown));
	}

	private HotColdView hotColdView()
	{
		Step step = clueLink.getCurrentStep();
		if (step == null || !ClueTypes.HOT_COLD.equals(step.getType()) || !config.hotColdPlanner())
		{
			return null;
		}
		HotColdPlan plan = hotCold.getPlan();
		if (plan == null || !plan.getStepKey().equals(step.getKey()))
		{
			return null;
		}
		boolean beginner = "beginner".equals(step.getTier());
		return HotColdView.of(new HotColdView.Inputs(plan, hotCold.getCoreSpots(), hotCold.isCoreReset(),
			hotCold.spotsOf(beginner), beginner, hotCold.hitpoints()));
	}

	private PanelModel.BankInfo bankInfo()
	{
		BankMemory.Seen bank = bankMemory.getBank();
		PlayerSnapshot snapshot = playerModel.getSnapshot();
		boolean bankCounts = config.useBank() && snapshot != null && snapshot.getAccountType() != ULTIMATE_IRONMAN;
		boolean promptOpen = bank == null && bankCounts;
		return new PanelModel.BankInfo(bank == null ? 0 : bank.seenAt, promptOpen, id -> client.getItemDefinition(id).getName());
	}

	private PanelModel.HouseInfo houseInfo()
	{
		HouseState house = poh.getHouse();
		boolean known = !house.getNexusKeys().isEmpty() || !house.getFurniture().isEmpty();
		boolean onlyNexusVarbitsKnown = known && house.getSource() == HouseState.Source.VARBITS;
		return new PanelModel.HouseInfo(house.getScannedAt(), onlyNexusVarbitsKnown, house.getSource() == HouseState.Source.MANUAL);
	}

	/** Members keep their book on an F2P world, where no book can be changed and only Standard has teleports. */
	private Book stuckBookOnF2p()
	{
		PlayerSnapshot snapshot = playerModel.getSnapshot();
		if (snapshot == null || snapshot.isMembersWorld() || snapshot.getSpellbook() == null)
		{
			return null;
		}
		Book book = snapshot.getSpellbook().getBook();
		return book == null || book == Book.STANDARD ? null : book;
	}
}
