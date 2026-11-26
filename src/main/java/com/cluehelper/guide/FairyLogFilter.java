package com.cluehelper.guide;

import com.cluehelper.transport.MenuLine;
import java.util.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

/**
 * While the sub-step dials a fairy ring code, the ring's travel log lists only that code, at the top. The game builds
 * the list again whenever it is opened, sorted or searched, so it is narrowed after every build. A search the player
 * types shows the game's own results, and the full list returns once the dials show the code. Client thread.
 */
@Singleton
public class FairyLogFilter
{
	private static final int BUILD_LOG_SCRIPT = 8080;
	private static final int NONE = -1;

	@Inject
	Client client;
	@Inject
	StepGuide guide;
	@Inject
	FairyDials fairyDials;
	@Inject
	ClientThread clientThread;

	/** Puts back what narrowing hid and moved; empty while the list is as the game built it. */
	List<Runnable> undo = new ArrayList<>();
	int narrowedRow = NONE;

	public void stop()
	{
		clientThread.invoke(this::restore);
	}

	/** Some widgets the game never shows again by itself, so the list is whole before the game builds it. */
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() == BUILD_LOG_SCRIPT)
		{
			undo.forEach(Runnable::run);
			undo.clear();
			narrowedRow = NONE;
		}
	}

	/** Narrowing runs the scrollbar script, which can't run inside another script. */
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == BUILD_LOG_SCRIPT)
		{
			clientThread.invokeLater(() -> narrow(wantedRow()));
		}
	}

	/** Narrows the open list to a new code, or gives the full list back once the code is dialled. */
	public void refresh()
	{
		int wanted = wantedRow();
		if (wanted != narrowedRow)
		{
			restore();
			narrow(wanted);
		}
	}

	private int wantedRow()
	{
		Widget contents = client.getWidget(InterfaceID.FairyringsLog.CONTENTS);
		MenuTarget target = guide.getHighlights().getMenuTarget();
		String letters = target.getLine() == MenuLine.FAIRY_CODE ? FairyDials.letters(target.getLineKey()) : null;
		if (contents == null || contents.isHidden() || letters == null || fairyDials.isDialled(letters) || isSearching())
		{
			return NONE;
		}
		return FairyDials.logRow(letters);
	}

	private boolean isSearching()
	{
		String search = client.getVarcStrValue(VarClientID.FAIRYRINGS_SEARCHSTRING);
		return search != null && !search.isBlank();
	}

	/**
	 * Keeps the code's row, its favourite star and the code drawn beside it, and hides everything else in the list. A
	 * row the game hid has no place name, so the list stays whole.
	 */
	private void narrow(int rowId)
	{
		if (rowId == NONE || rowId == narrowedRow)
		{
			return;
		}
		Widget contents = client.getWidget(InterfaceID.FairyringsLog.CONTENTS);
		Widget row = client.getWidget(rowId);
		if (contents == null || row == null || row.isSelfHidden() || row.getParentId() != contents.getId())
		{
			return;
		}
		int rowY = row.getOriginalY();
		for (Widget child : children(contents))
		{
			boolean keep = child == row || child.getId() == rowId + 1 || child.getIndex() >= 0 && child.getOriginalY() == rowY;
			if (keep)
			{
				moveUp(child, rowY);
			}
			else if (!child.isSelfHidden())
			{
				hide(child);
			}
		}
		scrollToTop(contents);
		narrowedRow = rowId;
	}

	private static List<Widget> children(Widget parent)
	{
		List<Widget> out = new ArrayList<>();
		for (Widget[] children : new Widget[][]{parent.getStaticChildren(), parent.getDynamicChildren()})
		{
			out.addAll(children == null ? List.of() : Arrays.asList(children));
		}
		return out;
	}

	private void moveUp(Widget widget, int by)
	{
		int y = widget.getOriginalY();
		widget.setOriginalY(y - by).revalidate();
		undo.add(() -> widget.setOriginalY(y).revalidate());
	}

	private void hide(Widget widget)
	{
		widget.setHidden(true);
		undo.add(() -> widget.setHidden(false));
	}

	/** A list no taller than its box has no scroll height, as the game leaves it. */
	private void scrollToTop(Widget contents)
	{
		int oldHeight = contents.getScrollHeight();
		int oldY = contents.getScrollY();
		contents.setScrollHeight(0);
		contents.setScrollY(0);
		client.runScript(ScriptID.UPDATE_SCROLLBAR, InterfaceID.FairyringsLog.SCROLLBAR, contents.getId(), 0);
		undo.add(() ->
		{
			contents.setScrollHeight(oldHeight);
			contents.setScrollY(oldY);
		});
	}

	private void restore()
	{
		boolean narrowed = !undo.isEmpty();
		undo.forEach(Runnable::run);
		undo.clear();
		narrowedRow = NONE;
		Widget contents = client.getWidget(InterfaceID.FairyringsLog.CONTENTS);
		if (narrowed && contents != null && !contents.isHidden())
		{
			client.runScript(ScriptID.UPDATE_SCROLLBAR, InterfaceID.FairyringsLog.SCROLLBAR, contents.getId(), contents.getScrollY());
		}
	}
}
