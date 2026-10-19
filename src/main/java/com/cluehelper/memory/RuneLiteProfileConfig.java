package com.cluehelper.memory;

import com.cluehelper.ClueHelperConfig;
import net.runelite.client.config.ConfigManager;

final class RuneLiteProfileConfig implements ProfileConfig
{
	private final ConfigManager configManager;

	RuneLiteProfileConfig(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	@Override
	public String profileKey()
	{
		return configManager.getRSProfileKey();
	}

	@Override
	public String get(String key)
	{
		return configManager.getRSProfileConfiguration(ClueHelperConfig.GROUP, key);
	}

	@Override
	public void set(String key, String value)
	{
		configManager.setRSProfileConfiguration(ClueHelperConfig.GROUP, key, value);
	}

	@Override
	public void unset(String key)
	{
		configManager.unsetRSProfileConfiguration(ClueHelperConfig.GROUP, key);
	}
}
