package com.cluehelper;

import com.cluehelper.guide.StepGuide;
import com.cluehelper.guide.SubStep;
import com.cluehelper.memory.BankMemory;
import java.io.File;
import java.util.Objects;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.ScriptID;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.util.Text;

@Slf4j
final class BankTrace
{
	static final String TAG = "[CH-BANK]";
	static final File FRAMES = new File("build/bank");
	/** Long enough to catch the next transport's first click, which comes a few ticks after the bank closes. */
	private static final int AFTER_CLOSE_TICKS = 15;

	private final Client client;
	private final BankMemory bank;
	private final StepGuide guide;
	private final Consumer<File> saveFrame;
	private final BankTagsService bankTags;
	private boolean keypadOpen;
	private boolean bankOpen;
	private String shownTag;
	private int bankOpenTick = -1;
	private int bankClosedTick = -1;
	private int frames;

	BankTrace(Client client, BankMemory bank, StepGuide guide, BankTagsService bankTags, Consumer<File> saveFrame)
	{
		this.bankTags = bankTags;
		this.client = client;
		this.bank = bank;
		this.guide = guide;
		this.saveFrame = saveFrame;
		FRAMES.mkdirs();
	}

	@Subscribe(priority = -3)
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.BANK || event.getContainerId() == InventoryID.INV_GROUP_TEMP)
		{
			BankMemory.Seen seen = event.getContainerId() == InventoryID.BANK ? bank.getBank() : bank.getGroupStorage();
			log(String.format("%s container: %d items remembered, PIN pending %s", event.getContainerId() == InventoryID.BANK ? "bank" : "group storage",
				seen == null ? 0 : seen.items.size(), bank.isPinPending()));
			SubStep now = guide.getCurrent();
			if (now != null && now.getKind() == SubStep.Kind.WITHDRAW)
			{
				log("withdraw sub-step: " + now.getText() + " | outlined " + guide.getHighlights().getBankItems());
				saveFrame.accept(new File(FRAMES, String.format("%03d-t%d.png", ++frames, client.getTickCount())));
			}
		}
	}

	@Subscribe(priority = -3)
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.BANKPIN_KEYPAD)
		{
			keypadOpen = true;
			log("PIN keypad open");
		}
		else if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankOpenTick = client.getTickCount();
			bankOpen = true;
			shownTag = bankTags.getActiveTag();
			log("bank open, tab '" + shownTag + "', withdraw items " + guide.getHighlights().getBankItems());
			saveFrame.accept(new File(FRAMES, String.format("%03d-t%d-open.png", ++frames, client.getTickCount())));
		}
	}

	@Subscribe(priority = -3)
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANKPIN_KEYPAD)
		{
			keypadOpen = false;
			log("PIN keypad closed");
		}
		else if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankClosedTick = client.getTickCount();
			bankOpen = false;
			log("bank closed after " + (bankOpenTick < 0 ? "?" : bankClosedTick - bankOpenTick) + " ticks; saved");
		}
	}

	/** Every client frame, so the first frame after Bank Tags or Clue Helper switches tabs is caught. */
	@Subscribe(priority = -3)
	public void onClientTick(ClientTick tick)
	{
		String active = bankTags.getActiveTag();
		if (bankOpen && !Objects.equals(active, shownTag))
		{
			shownTag = active;
			log("tab now '" + active + "', withdraw items " + guide.getHighlights().getBankItems());
			saveFrame.accept(new File(FRAMES, String.format("%03d-t%d-tab.png", ++frames, client.getTickCount())));
		}
	}

	@Subscribe(priority = 10)
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() == ScriptID.BANKMAIN_INIT)
		{
			log("bank init starts, tab '" + bankTags.getActiveTag() + "'");
		}
	}

	@Subscribe(priority = -3)
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.BANKMAIN_INIT)
		{
			log("bank init done, tab '" + bankTags.getActiveTag() + "', withdraw items " + guide.getHighlights().getBankItems());
		}
	}

	@Subscribe(priority = -3)
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (keypadOpen)
		{
			return;
		}
		String option = Text.removeTags(event.getMenuOption());
		boolean nearBank = bankOpenTick >= 0 && (bankClosedTick < bankOpenTick || client.getTickCount() - bankClosedTick <= AFTER_CLOSE_TICKS);
		boolean watched = option.equals("Bank") || option.equals("Search") || option.equals("Check") || option.startsWith("Use")
			&& Text.removeTags(event.getMenuTarget()).contains("Bank");
		if (nearBank || watched)
		{
			log(String.format("click '%s' on '%s' item %d", option, Text.removeTags(event.getMenuTarget()), event.getItemId()));
		}
	}

	@Subscribe(priority = -3)
	public void onChatMessage(ChatMessage message)
	{
		String text = Text.removeTags(message.getMessage());
		if (message.getType() == ChatMessageType.GAMEMESSAGE && (text.contains("STASH") || text.contains("charge") || text.contains("PIN")))
		{
			log("chat: " + text);
		}
	}

	private void log(String line)
	{
		log.info("{} t{} {}", TAG, client.getTickCount(), line);
	}
}
