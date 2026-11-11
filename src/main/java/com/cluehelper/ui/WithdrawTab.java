package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.StepGuide;
import java.util.*;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.banktags.*;
import net.runelite.client.plugins.banktags.tabs.*;
import static java.util.stream.Collectors.*;

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
	private Client client;
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

	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankOpen = true;
			refresh();
		}
	}

	/** Bank Tags reopens its remembered tab as the bank initialises, over ours, so ours opens again straight after. */
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.BANKMAIN_INIT)
		{
			bankOpen = true;
			forgetItems();
			refresh();
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

	/**
	 * Opens or updates the tab for the items the sub-step withdraws now, while the bank is open. An item the plan no longer
	 * wants leaves the tab unless it was withdrawn: the plan can change once the bank's real contents load.
	 */
	public void refresh()
	{
		if (!bankOpen)
		{
			return;
		}
		Set<Integer> wanted = config.withdrawTab() ? guide.getHighlights().getBankItems() : Set.of();
		if (wanted.isEmpty())
		{
			close();
			return;
		}
		List<Integer> kept = shownItems.stream().filter(id -> wanted.contains(id) || isCarried(id)).collect(toList());
		boolean joins = !kept.containsAll(wanted);
		wanted.stream().filter(id -> !kept.contains(id)).sorted().forEach(kept::add);
		if (kept.equals(shownItems))
		{
			return;
		}
		shownItems = List.copyOf(kept);
		layoutManager.saveLayout(new Layout(TAG, shownItems.stream().mapToInt(Integer::intValue).toArray()));
		if (joins || TAG.equals(bankTags.getActiveTag()))
		{
			bankTags.openBankTag(TAG, 0);
		}
	}

	private boolean isCarried(int itemId)
	{
		return isIn(InventoryID.INV, itemId) || isIn(InventoryID.WORN, itemId);
	}

	private boolean isIn(int containerId, int itemId)
	{
		ItemContainer container = client.getItemContainer(containerId);
		return container != null && container.contains(itemId);
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
