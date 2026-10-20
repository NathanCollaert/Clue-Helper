package com.cluehelper;

import com.cluehelper.guide.StepGuide;
import com.cluehelper.guide.SubStep;
import com.cluehelper.memory.BankMemory;
import java.io.File;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * B15's live check: {@code [CH-BANK]} lines with ticks for every bank, PIN, STASH and Check event, and every click
 * from a bank opening until a few ticks after it closes, so a bank stop can be timed from the log (B14's arrival to
 * next transport). Nothing is logged while the PIN keypad is open. A frame is saved whenever the bank shows a withdraw
 * sub-step, to check the highlights.
 */
@Slf4j
final class BankTrace
{
	static final String TAG = "[CH-BANK]";
	static final File FRAMES = new File("build/bank");
	/** Clicks are logged until the next transport's first click, which comes a few ticks after the bank closes (B14). */
	private static final int AFTER_CLOSE_TICKS = 15;

	private final Client client;
	private final BankMemory bank;
	private final StepGuide guide;
	private final Consumer<File> saveFrame;
	private boolean keypadOpen;
	private int bankOpenTick = -1;
	private int bankClosedTick = -1;
	private int frames;

	BankTrace(Client client, BankMemory bank, StepGuide guide, Consumer<File> saveFrame)
	{
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
				log("withdraw sub-step: " + now.getText() + " | outlined " + guide.getBankHighlightItems());
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
			log("bank open");
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
			log("bank closed after " + (bankOpenTick < 0 ? "?" : bankClosedTick - bankOpenTick) + " ticks; saved");
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
