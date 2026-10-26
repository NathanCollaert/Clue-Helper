package com.cluehelper;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(ClueHelperConfig.GROUP)
public interface ClueHelperConfig extends Config
{
	String GROUP = "cluehelper";

	@ConfigSection(
		name = "Routes",
		description = "How routes are ranked",
		position = 0
	)
	String routesSection = "routes";

	@ConfigSection(
		name = "Spellbook changes",
		description = "Routes that change your spellbook on the way",
		position = 1
	)
	String spellbookSection = "spellbook";

	@ConfigSection(
		name = "Guidance",
		description = "What the plugin shows and draws for the current sub-step",
		position = 2
	)
	String guidanceSection = "guidance";

	@ConfigSection(
		name = "Hot/cold",
		description = "Where to feel the strange device next",
		position = 3
	)
	String hotColdSection = "hotCold";

	@ConfigItem(
		keyName = "avoidWilderness",
		name = "Avoid Wilderness",
		description = "Leave out Wilderness routes for steps outside it. Hardcore only: just on Hardcore Ironman and Hardcore Group Ironman accounts",
		section = routesSection,
		position = 0
	)
	default AvoidWilderness avoidWilderness()
	{
		return AvoidWilderness.ALWAYS;
	}

	@ConfigItem(
		keyName = "scrollBookOutOfWilderness",
		name = "Scroll book stays out of Wilderness",
		description = "Bank the master scroll book before a teleport into the Wilderness",
		section = routesSection,
		position = 1
	)
	default boolean scrollBookOutOfWilderness()
	{
		return true;
	}

	@ConfigItem(
		keyName = "useBank",
		name = "Use the bank",
		description = "Count banked teleports and items (and GIM group storage). A bank stop is shown as Best only when it beats going direct by 5 ticks or 10%",
		section = routesSection,
		position = 2
	)
	default boolean useBank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "spellbookChanges",
		name = "Spellbook changes",
		description = "Spellbook Swap only never leaves you on another book",
		section = spellbookSection,
		position = 0
	)
	default SpellbookChanges spellbookChanges()
	{
		return SpellbookChanges.ALL;
	}

	@ConfigItem(
		keyName = "highlightItems",
		name = "Highlight items",
		description = "Highlight the item the current sub-step uses",
		section = guidanceSection,
		position = 0
	)
	default boolean highlightItems()
	{
		return true;
	}

	@ConfigItem(
		keyName = "withdrawTab",
		name = "Withdraw tab",
		description = "Open a bank tab with only the items to withdraw (needs the Bank Tags plugin)",
		section = guidanceSection,
		position = 1
	)
	default boolean withdrawTab()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightSpells",
		name = "Highlight spells",
		description = "Highlight the spell the current sub-step casts",
		section = guidanceSection,
		position = 2
	)
	default boolean highlightSpells()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightMenus",
		name = "Highlight menu lines",
		description = "Highlight the destination in teleport menus such as the portal nexus and jewellery box",
		section = guidanceSection,
		position = 3
	)
	default boolean highlightMenus()
	{
		return true;
	}

	@ConfigItem(
		keyName = "pathLine",
		name = "Path",
		description = "Draw the path to where to go next. Never drawn onto the clue's own target",
		section = guidanceSection,
		position = 4
	)
	default boolean pathLine()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showBox",
		name = "Show the box",
		description = "Show the Clue Helper box under the Clue Scroll plugin's box. Highlights and the path work without it",
		section = guidanceSection,
		position = 5
	)
	default boolean showBox()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showNextStep",
		name = "Show the next step",
		description = "Show the step after the current one in the box",
		section = guidanceSection,
		position = 6
	)
	default boolean showNextStep()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hotColdPlanner",
		name = "Probe planner",
		description = "Suggest where to feel next, from your feels so far, and route there. Off: the Clue Scroll plugin's list only",
		section = hotColdSection,
		position = 0
	)
	default boolean hotColdPlanner()
	{
		return true;
	}
}
