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
		name = "Highlights and markers",
		description = "What the plugin draws for the current sub-step",
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
		description = "Leave out routes through the Wilderness for steps outside it, unless it's the only way there, and prefer hot/cold feels outside it. Hardcore only applies to Hardcore Ironman and Hardcore Group Ironman accounts",
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
		description = "For a master scroll book teleport into the Wilderness (Revenant caves), take the scroll out at a bank and bank the book first",
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
		keyName = "highlightSpells",
		name = "Highlight spells",
		description = "Highlight the spell the current sub-step casts",
		section = guidanceSection,
		position = 1
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
		position = 2
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
		position = 3
	)
	default boolean pathLine()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showNextStep",
		name = "Show the next step",
		description = "Show the step after the current one in the box",
		section = guidanceSection,
		position = 4
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
