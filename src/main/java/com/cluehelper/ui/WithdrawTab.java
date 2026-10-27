package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.StepGuide;
import java.util.*;
import javax.inject.*;
import net.runelite.api.ScriptID;
import net.runelite.api.events.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.banktags.*;
import net.runelite.client.plugins.banktags.tabs.*;

/**
 * Opens a Bank Tags tab holding only the items the current sub-step withdraws. A withdrawn item stays in the tab's
 * layout for the rest of the bank visit, where Bank Tags draws it as a placeholder, so the others don't shift. The
 * layout is saved only while the tab is open. The tab opens again only when an item joins it, so a tab the player
 * picks themselves stays open. Client thread.
 */
@Singleton
public class WithdrawTab
{
	private static final String TAG = "clue helper";

	@Inject
	private StepGuide guide;
	@Inject
	private ClueHelperConfig config;
	@Inject
	private BankTagsService bankTags;
	@Inject
	private TagManager tagManager;
	@Inject
	private LayoutManager layoutManager;
	@Inject
	private ClientThread clientThread;

	/** In the order they joined, which is their place in the layout. */
	private List<Integer> shownItems = List.of();
	private boolean bankOpen;

	public void start()
	{
		tagManager.registerTag(TAG, itemId -> shownItems.contains(itemId));
	}

	public void stop()
	{
		clientThread.invoke(() ->
		{
			close();
			tagManager.unregisterTag(TAG);
			bankOpen = false;
		});
	}

	public void onGameTick()
	{
		update();
	}

	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankOpen = true;
			update();
		}
	}

	/** Bank Tags reopens its remembered tab as the bank initialises, over ours, so ours opens again straight after. */
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.BANKMAIN_INIT)
		{
			bankOpen = true;
			forgetItems();
			update();
		}
	}

	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankOpen = false;
			forgetItems();
		}
	}

	private void update()
	{
		if (!bankOpen)
		{
			return;
		}
		Set<Integer> wanted = config.withdrawTab() ? guide.getHighlights().getBankItems() : Set.of();
		if (wanted.isEmpty())
		{
			close();
		}
		else if (!shownItems.containsAll(wanted))
		{
			List<Integer> joined = new ArrayList<>(shownItems);
			wanted.stream().filter(id -> !joined.contains(id)).sorted().forEach(joined::add);
			shownItems = List.copyOf(joined);
			layoutManager.saveLayout(new Layout(TAG, shownItems.stream().mapToInt(Integer::intValue).toArray()));
			bankTags.openBankTag(TAG, 0);
		}
	}

	private void close()
	{
		if (TAG.equals(bankTags.getActiveTag()))
		{
			bankTags.closeBankTag();
		}
		forgetItems();
	}

	private void forgetItems()
	{
		if (!shownItems.isEmpty())
		{
			layoutManager.removeLayout(TAG);
		}
		shownItems = List.of();
	}
}
