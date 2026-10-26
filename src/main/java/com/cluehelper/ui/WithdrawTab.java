package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.StepGuide;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.plugins.banktags.tabs.Layout;
import net.runelite.client.plugins.banktags.tabs.LayoutManager;

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

	private final StepGuide guide;
	private final ClueHelperConfig config;
	private final BankTagsService bankTags;
	private final TagManager tagManager;
	private final LayoutManager layoutManager;
	private final ClientThread clientThread;

	/** In the order they joined, which is their place in the layout. */
	private List<Integer> shownItems = List.of();
	private boolean bankOpen;

	@Inject
	WithdrawTab(StepGuide guide, ClueHelperConfig config, BankTagsService bankTags, TagManager tagManager,
		LayoutManager layoutManager, ClientThread clientThread)
	{
		this.guide = guide;
		this.config = config;
		this.bankTags = bankTags;
		this.tagManager = tagManager;
		this.layoutManager = layoutManager;
		this.clientThread = clientThread;
	}

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
		Set<Integer> wanted = config.withdrawTab() ? guide.getBankHighlightItems() : Set.of();
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
