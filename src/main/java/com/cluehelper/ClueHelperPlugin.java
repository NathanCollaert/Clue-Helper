package com.cluehelper;

import com.cluehelper.dev.DevHarness;
import com.cluehelper.guide.MenuLines;
import com.cluehelper.guide.StepGuide;
import com.cluehelper.link.ClueLink;
import com.cluehelper.memory.BankMemory;
import com.cluehelper.memory.MemoryStore;
import com.cluehelper.memory.StashMemory;
import com.cluehelper.player.Book;
import com.cluehelper.player.CheckLines;
import com.cluehelper.player.HouseState;
import com.cluehelper.player.PlayerModel;
import com.cluehelper.poh.PohService;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.route.PlannerRunner;
import com.cluehelper.route.RoutePlanner;
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
import javax.inject.Inject;
import javax.inject.Named;
import javax.swing.SwingUtilities;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
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
		availability.start();
		catalog.get().thenAccept(loaded -> availability.setCatalog(loaded.getTeleports()));
		routes.start();
		guide.start();
		unregisterRoutes = routes.addListener(plan -> showPanelState());
		unregisterGuide = guide.addListener(this::onGuideChanged);
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
		unregisterGuide.run();
		unregisterRoutes.run();
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

	/** Priority -1: core updates its clue in its own priority-0 handlers, which must run first. */
	@Subscribe(priority = -1)
	public void onGameTick(GameTick tick)
	{
		if (clueLink.onGameTick())
		{
			showPanelState();
		}
		bankMemory.onGameTick();
		poh.onGameTick();
		playerModel.onGameTick();
		availability.onGameTick();
		routes.onGameTick();
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
		menuLines.colourLines(guide.getMenuTarget(), config.highlightMenus());
	}

	/** After Menu Entry Swapper's swaps: the hover menu's top entry is the left click. */
	@Subscribe(priority = -1)
	public void onPostMenuSort(PostMenuSort event)
	{
		guide.onPostMenuSort();
	}

	/** After other plugins have built the menu: our option is coloured, nothing is added, removed or reordered. */
	@Subscribe(priority = -1)
	public void onMenuOpened(MenuOpened event)
	{
		menuLines.colourMenu(guide.getMenuTarget(), config.highlightMenus());
	}

	/** Priority 2: the menu scripts' arguments are read before better-teleport-menu (priority 1) rewrites them (B12). */
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
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		memory.reload();
		bankMemory.onProfileChanged();
		poh.onProfileChanged();
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

	/** Client thread: the guide's state is read here, then handed to Swing. */
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
		SwingUtilities.invokeLater(() -> target.show(model));
	}

	/** "Open your bank once" while this profile has no bank and one would count: not UIM, useBank on (B13). */
	private PanelModel.BankInfo bankInfo()
	{
		BankMemory.Seen bank = bankMemory.getBank();
		PlayerSnapshot snapshot = playerModel.getSnapshot();
		boolean bankCounts = config.useBank() && snapshot != null && snapshot.getAccountType() != ULTIMATE_IRONMAN;
		return new PanelModel.BankInfo(bank == null ? 0 : bank.seenAt, bank == null && bankCounts,
			id -> client.getItemDefinition(id).getName());
	}

	/** "Visit your house once" while only the nexus varbits are known (B16). */
	private PanelModel.HouseInfo houseInfo()
	{
		HouseState house = poh.getHouse();
		boolean known = !house.getNexusKeys().isEmpty() || !house.getFurniture().isEmpty();
		return new PanelModel.HouseInfo(house.getScannedAt(), known && house.getSource() == HouseState.Source.VARBITS,
			house.getSource() == HouseState.Source.MANUAL);
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
