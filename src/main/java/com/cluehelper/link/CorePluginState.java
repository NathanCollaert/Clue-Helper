package com.cluehelper.link;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;

/**
 * Whether RuneLite's Clue Scroll plugin is running. Seeded from {@code isPluginActive} at start
 * (a client started with core off posts no event), then flipped by {@link PluginChanged} and
 * re-checked once per tick. Never read from {@code isPluginEnabled} or the plugin's config key:
 * both flip before core actually stops or starts.
 */
@Singleton
public class CorePluginState
{
	private final PluginManager pluginManager;
	private volatile Plugin core;
	private volatile boolean active;

	@Inject
	CorePluginState(PluginManager pluginManager)
	{
		this.pluginManager = pluginManager;
	}

	public void start()
	{
		core = findCore();
		active = isCoreActive();
	}

	public void stop()
	{
		core = null;
		active = false;
	}

	public boolean isActive()
	{
		return active;
	}

	/** The core plugin instance; it exists even while core is off. Null if the client has none. */
	public Plugin getCore()
	{
		return core;
	}

	/**
	 * Runs on the Swing thread, so it only sets the flag.
	 *
	 * @return whether the active state changed
	 */
	public boolean onPluginChanged(PluginChanged event)
	{
		Plugin c = core;
		return c != null && event.getPlugin() == c && update(event.isLoaded());
	}

	/** @return whether the active state changed */
	public boolean recheck()
	{
		return update(isCoreActive());
	}

	private boolean update(boolean nowActive)
	{
		boolean changed = active != nowActive;
		active = nowActive;
		return changed;
	}

	private Plugin findCore()
	{
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (plugin instanceof ClueScrollPlugin)
			{
				return plugin;
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
