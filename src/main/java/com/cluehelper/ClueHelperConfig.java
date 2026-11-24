package com.cluehelper;

import net.runelite.client.config.*;

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
		name = "Master clues",
		description = "Juggling a quick three-step",
		position = 3
	)
	String masterSection = "master";

	@ConfigItem(
		keyName = "avoidWilderness",
		name = "Avoid Wilderness",
		description = "Leave out Wilderness routes for steps outside it",
		section = routesSection,
		position = 0
	)
	default AvoidWilderness avoidWilderness()
	{
		return AvoidWilderness.NEVER;
	}

	@ConfigItem(
		keyName = "stopWildernessClues",
		name = "Stop Wilderness clues",
		description = "Show a red warning instead of guiding into the Wilderness",
		section = routesSection,
		position = 1
	)
	default boolean stopWildernessClues()
	{
		return false;
	}

	@ConfigItem(
		keyName = "scrollBookOutOfWilderness",
		name = "Bank the scroll book",
		description = "Bank the master scroll book before a teleport into the Wilderness",
		section = routesSection,
		position = 2
	)
	default boolean scrollBookOutOfWilderness()
	{
		return true;
	}

	@ConfigItem(
		keyName = "useBank",
		name = "Use the bank",
		description = "Count banked teleports and items, and GIM group storage",
		section = routesSection,
		position = 3
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
		description = "Draw the path to where to go next",
		section = guidanceSection,
		position = 4
	)
	default boolean pathLine()
	{
		return true;
	}

	@ConfigItem(
		keyName = "guideBox",
		name = "Overlay",
		description = "How the box sits with the Clue Scroll plugin's",
		section = guidanceSection,
		position = 5
	)
	default GuideBoxStyle guideBox()
	{
		return GuideBoxStyle.COMBINED;
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
		keyName = "threeStepSwaps",
		name = "Three-step swaps",
		description = "Keep the quickest three-step and guide combining its pieces onto slower ones",
		section = masterSection,
		position = 0
	)
	default boolean threeStepSwaps()
	{
		return true;
	}

	@ConfigItem(
		keyName = "threeStepKeep",
		name = "Three-step to keep",
		description = "Score three-steps by your teleports now, or as a maxed account would",
		section = masterSection,
		position = 1
	)
	default ThreeStepKeep threeStepKeep()
	{
		return ThreeStepKeep.QUICKEST_NOW;
	}
}
