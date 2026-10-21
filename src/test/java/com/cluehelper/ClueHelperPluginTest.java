package com.cluehelper;

import com.cluehelper.dev.UiWatchdogPlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Dev launcher, started by `gradlew dev`. */
public class ClueHelperPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ClueHelperPlugin.class, ClueHelperDevPlugin.class, UiWatchdogPlugin.class);
		RuneLite.main(args);
	}
}
