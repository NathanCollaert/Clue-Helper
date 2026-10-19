package com.cluehelper;

import com.cluehelper.dev.DevHarness;
import com.cluehelper.link.ClueLink;
import com.cluehelper.memory.MemoryStore;
import com.cluehelper.player.PlayerModel;
import com.cluehelper.route.PlannerRunner;
import com.cluehelper.transport.AvailabilityService;
import com.cluehelper.transport.CatalogData;
import com.cluehelper.ui.ClueHelperPanel;
import com.cluehelper.ui.Icons;
import com.cluehelper.ui.PanelState;
import com.google.inject.Provides;
import javax.inject.Inject;
import javax.inject.Named;
import javax.swing.SwingUtilities;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@Slf4j
@PluginDescriptor(
	name = "Clue Helper",
	description = "Fastest clue routes from what you own, alongside the Clue Scroll plugin",
	tags = {"clue", "clues", "treasure", "trails", "teleport", "route"}
)
@PluginDependency(ClueScrollPlugin.class)
public class ClueHelperPlugin extends Plugin
{
	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	@Getter(AccessLevel.PACKAGE)
	private ClueLink clueLink;

	@Inject
	private MemoryStore memory;

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
	private DevHarness devHarness;

	@Inject
	@Named("developerMode")
	private boolean developerMode;

	@Getter(AccessLevel.PACKAGE)
	private ClueHelperPanel panel;
	private NavigationButton navigationButton;

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
		availability.start();
		catalog.get().thenAccept(loaded -> availability.setCatalog(loaded.getTeleports()));
		showPanelState();
		if (developerMode)
		{
			devHarness.start(this::shownPanelState, this::panelState);
		}
	}

	@Override
	protected void shutDown()
	{
		devHarness.stop();
		planner.cancelAll();
		availability.stop();
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
			showPanelState();
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
		playerModel.onGameTick();
		availability.onGameTick();
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		clueLink.onItemContainerChanged(event);
		playerModel.onItemContainerChanged(event);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		playerModel.onVarbitChanged(event);
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
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		memory.reload();
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
		return PanelState.of(clueLink.isActive(), clueLink.needsRead(), clueLink.hasStep(), planner.isWorking());
	}

	private void showPanelState()
	{
		PanelState state = panelState();
		ClueHelperPanel target = panel;
		if (target != null)
		{
			SwingUtilities.invokeLater(() -> target.display(state));
		}
	}
}
