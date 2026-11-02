package com.cluehelper.guide;

import com.cluehelper.link.ClueLink;
import com.cluehelper.step.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/**
 * Client thread. A step that reads a quest's journal (Viggora's Curse of the Empty Lord) scrolls the quest list to that
 * quest once each time the list opens, as core does for its emote and music clues; the player scrolls freely after.
 */
@Singleton
public class JournalScroll
{
	@Inject
	private Client client;
	@Inject
	private ClueLink clueLink;
	private boolean scrolled;

	public void onGameTick()
	{
		Step step = clueLink.getCurrentStep();
		StepIndex.LearnPlace learn = step == null ? null : step.getData().getLearnPlace();
		if (learn == null || !Widgets.shown(client, InterfaceID.Questlist.LIST))
		{
			scrolled = false;
			return;
		}
		Widget quest = scrolled ? null : Widgets.descendants(client.getWidget(InterfaceID.Questlist.LIST)).stream()
			.filter(line -> Widgets.visible(line) && line.getText() != null && Text.removeTags(line.getText()).equalsIgnoreCase(learn.getJournal()))
			.findFirst().orElse(null);
		Widget list = quest == null ? null : scrollingAncestor(quest);
		if (list == null)
		{
			return;
		}
		int middle = quest.getBounds().y - list.getBounds().y + list.getScrollY() + quest.getHeight() / 2;
		int scroll = Math.max(0, Math.min(list.getScrollHeight(), middle - list.getHeight() / 2));
		client.runScript(ScriptID.UPDATE_SCROLLBAR, InterfaceID.Questlist.SCROLLBAR, list.getId(), scroll);
		scrolled = true;
	}

	/** The layer that scrolls the line: the first one up that holds more than it shows. */
	private static Widget scrollingAncestor(Widget line)
	{
		for (Widget up = line.getParent(); up != null; up = up.getParent())
		{
			if (up.getScrollHeight() > up.getHeight())
			{
				return up;
			}
		}
		return null;
	}
}
