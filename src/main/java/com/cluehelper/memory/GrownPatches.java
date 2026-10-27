package com.cluehelper.memory;

import com.cluehelper.player.PlayerModel;
import java.util.Set;
import javax.inject.*;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;

/**
 * Client thread. Patches the Time Tracking plugin remembers for this account, as core stores them: "region.varbit" to
 * "value:seconds" in its group. A grown Farming Guild redwood is a place for Sherlock's redwood task.
 */
@Singleton
public class GrownPatches
{
	private static final String FARMING_GUILD_REDWOOD = "Farming Guild redwood";
	private static final String TIME_TRACKING = "timetracking";
	private static final String REDWOOD_KEY = "4922." + VarbitID.FARMING_TRANSMIT_I;
	private static final int HARVESTABLE = 18;
	private static final int CHECK_HEALTH = 37;
	private static final int REGROWN_FIRST = 41;
	private static final int REGROWN_LAST = 55;

	@Inject
	private ConfigManager configManager;
	@Inject
	private PlayerModel player;

	/** Call on start, after a profile change and when Time Tracking writes one of its patches. */
	public void publish()
	{
		player.setGrownPatches(isGrownRedwood(storedValue(REDWOOD_KEY)) ? Set.of(FARMING_GUILD_REDWOOD) : Set.of());
	}

	public boolean isOurs(ConfigChanged event)
	{
		return TIME_TRACKING.equals(event.getGroup()) && REDWOOD_KEY.equals(event.getKey());
	}

	/** Core's redwood patch values: chopping, or grown with its health check due first. */
	private static boolean isGrownRedwood(int value)
	{
		return value == HARVESTABLE || value == CHECK_HEALTH || value >= REGROWN_FIRST && value <= REGROWN_LAST;
	}

	/** -1 when Time Tracking has never seen the patch. */
	private int storedValue(String key)
	{
		String stored = configManager.getRSProfileConfiguration(TIME_TRACKING, key);
		String value = stored == null ? "" : stored.split(":", 2)[0];
		return value.matches("\\d+") ? Integer.parseInt(value) : -1;
	}
}
