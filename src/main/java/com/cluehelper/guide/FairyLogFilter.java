package com.cluehelper.guide;

import com.cluehelper.transport.MenuLine;
import java.util.*;
import java.util.stream.Collectors;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

/**
 * While the sub-step dials a fairy ring code, the ring's travel log is scrolled so the code's row is at the top, once
 * per build of the list: the player can still scroll away. A search the player types shows the game's own results.
 * Client thread.
 */
@Singleton
public class FairyLogFilter
{
	public static final int BUILD_LOG_SCRIPT = 8080;
	private static final int NONE = -1;

	@Inject
	Client client;
	@Inject
	StepGuide guide;
	@Inject
	FairyDials fairyDials;
	@Inject
	ClientThread clientThread;

	int scrolledRow = NONE;

	/**
	 * The game builds the list again whenever it is opened, sorted or searched, at the top. Scrolled once the build is
	 * over: the scrollbar's script can't run inside the build's.
	 */
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == BUILD_LOG_SCRIPT)
		{
			scrolledRow = NONE;
			clientThread.invokeLater(this::refresh);
		}
	}

	/** Scrolls the open list to a new code. */
	public void refresh()
	{
		int wanted = wantedRow();
		Widget contents = client.getWidget(InterfaceID.FairyringsLog.CONTENTS);
		Widget row = wanted == NONE ? null : client.getWidget(wanted);
		if (wanted == scrolledRow || row == null || row.isSelfHidden() || row.getParentId() != contents.getId())
		{
			return;
		}
		int lowest = Math.max(0, contents.getScrollHeight() - contents.getHeight());
		contents.setScrollY(Math.min(row.getOriginalY(), lowest));
		client.runScript(ScriptID.UPDATE_SCROLLBAR, InterfaceID.FairyringsLog.SCROLLBAR, contents.getId(), contents.getScrollY());
		scrolledRow = wanted;
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
		return FairyDials.logRow(target, letters);
	}

	private boolean isSearching()
	{
		String search = client.getVarcStrValue(VarClientID.FAIRYRINGS_SEARCHSTRING);
		return search != null && !search.isBlank();
	}

	/** The game draws a row's code as dynamic children of the list at the row's height. */
	static List<Widget> codeBeside(Widget row)
	{
		Widget[] drawn = row.getParent() == null ? null : row.getParent().getDynamicChildren();
		return drawn == null ? List.of() : Arrays.stream(drawn)
			.filter(child -> child.getOriginalY() == row.getOriginalY())
			.collect(Collectors.toList());
	}
}
