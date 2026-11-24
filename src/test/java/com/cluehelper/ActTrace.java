package com.cluehelper;

import com.cluehelper.guide.StepGuide;
import com.cluehelper.guide.SubStep;
import com.cluehelper.link.ClueLink;
import com.google.gson.Gson;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Times each act the guide points at (a dig, search, talk, emote, equip, drop, STASH withdrawal, hand-in): one
 * {@link Acted} line each in {@link #LOG}, summed up per act in {@link #SUMMARY}. An act runs from the player standing
 * where they do it to its first animation, dialogue, game message and change to the inventory or worn items (the
 * game's share), to the guide moving on, and to the clue changing. The player's share is their reaction: the guide
 * showing the act to the player's first click on it.
 *
 * <p>Ticks: the player last stepped on GameTick S, so stands from boundary S + 1, or from the click's own period when
 * they never stepped after it. The start is fixed at the act's first sign (an effect, the guide or the clue moving on),
 * so walking off afterwards doesn't move it. An effect, the guide or the clue seen with count E answers boundary E + 1,
 * so stand to effect = E + 1 - start.
 */
@Slf4j
final class ActTrace
{
	static final String TAG = "[CH-ACT]";
	static final File LOG = new File(TimingLog.DIR, "acts.jsonl");
	static final File SUMMARY = new File(TimingLog.DIR, "acts.tsv");
	private static final Set<String> NOT_A_START = Set.of(PlayerTiles.WALK, "Cancel", "Examine");
	private static final Set<Integer> DIALOGUES = Set.of(InterfaceID.CHAT_LEFT, InterfaceID.CHAT_RIGHT, InterfaceID.CHATMENU,
		InterfaceID.MESSAGEBOX, InterfaceID.OBJECTBOX);
	private static final Set<ChatMessageType> GAME_LINES = Set.of(ChatMessageType.GAMEMESSAGE, ChatMessageType.SPAM,
		ChatMessageType.MESBOX);
	private static final int NOT_SEEN = 0;
	private static final int GIVE_UP_TICKS = 100;

	private final Client client;
	private final StepGuide guide;
	private final Gson gson;
	private final TimingLog timingLog;
	private final BooleanSupplier mockedClue;
	private final List<Runnable> unregister = new ArrayList<>();
	private SubStep acting;
	private Acted acted;
	private WorldPoint lastTile;
	private int lastStepTick = NOT_SEEN;
	/** The sub-step the guide showed last, and since when. */
	private SubStep shown;
	private int shownTick = NOT_SEEN;

	ActTrace(Client client, StepGuide guide, ClueLink clueLink, Gson gson, TimingLog timingLog, BooleanSupplier mockedClue)
	{
		this.mockedClue = mockedClue;
		this.client = client;
		this.guide = guide;
		this.gson = gson;
		this.timingLog = timingLog;
		unregister.add(guide.addListener(this::onGuideChanged));
		unregister.add(clueLink.addListener((change, step) -> onClueChanged()));
	}

	void stop()
	{
		unregister.forEach(Runnable::run);
		unregister.clear();
	}

	@Subscribe(priority = -3)
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		String option = Text.removeTags(event.getMenuOption());
		SubStep current = guide.getCurrent();
		if (acted == null && current != null && current.getKind() == SubStep.Kind.ACT && !NOT_A_START.contains(option))
		{
			begin(current, option + " " + Text.removeTags(event.getMenuTarget()));
		}
		else if (acted != null)
		{
			acted.clicks++;
		}
	}

	@Subscribe(priority = -3)
	public void onAnimationChanged(AnimationChanged event)
	{
		if (acted != null && acted.animationTick == NOT_SEEN && event.getActor() == client.getLocalPlayer()
			&& event.getActor().getAnimation() != -1)
		{
			fixStart();
			acted.animationTick = client.getTickCount();
			acted.animation = event.getActor().getAnimation();
		}
	}

	@Subscribe(priority = -3)
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (acted != null && acted.dialogueTick == NOT_SEEN && DIALOGUES.contains(event.getGroupId()))
		{
			fixStart();
			acted.dialogueTick = client.getTickCount();
		}
	}

	@Subscribe(priority = -3)
	public void onChatMessage(ChatMessage event)
	{
		if (acted != null && acted.messageTick == NOT_SEEN && GAME_LINES.contains(event.getType()))
		{
			fixStart();
			acted.messageTick = client.getTickCount();
			acted.message = Text.removeTags(event.getMessage());
		}
	}

	@Subscribe(priority = -3)
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		boolean carried = event.getContainerId() == InventoryID.INV || event.getContainerId() == InventoryID.WORN;
		if (acted != null && acted.itemsChangedTick == NOT_SEEN && carried)
		{
			fixStart();
			acted.itemsChangedTick = client.getTickCount();
		}
	}

	@Subscribe(priority = -3)
	public void onGameTick(GameTick tick)
	{
		WorldPoint tile = PlayerTiles.tile(client);
		if (acted != null && tile != null && lastTile != null && !tile.equals(lastTile))
		{
			lastStepTick = client.getTickCount();
		}
		lastTile = tile;
		if (acted != null && client.getTickCount() - acted.clickTick > GIVE_UP_TICKS)
		{
			finish();
		}
	}

	private void onGuideChanged()
	{
		SubStep current = guide.getCurrent();
		if (!Objects.equals(current, shown))
		{
			shown = current;
			shownTick = client.getTickCount();
		}
		if (acted != null && acted.guideMovedOnTick == NOT_SEEN && !Objects.equals(guide.getCurrent(), acting))
		{
			fixStart();
			acted.guideMovedOnTick = client.getTickCount();
			finishOnceBothMoved();
		}
	}

	private void onClueChanged()
	{
		if (acted != null && acted.clueChangedTick == NOT_SEEN)
		{
			fixStart();
			acted.clueChangedTick = client.getTickCount();
			finishOnceBothMoved();
		}
	}

	/** An act that doesn't end its step is done once the guide moves on; the step's last act once the clue changes too. */
	private void finishOnceBothMoved()
	{
		boolean endsStep = acting.getDone() == SubStep.Done.STEP_CHANGES;
		if (acted.guideMovedOnTick != NOT_SEEN && (!endsStep || acted.clueChangedTick != NOT_SEEN))
		{
			finish();
		}
	}

	private void begin(SubStep current, String click)
	{
		acting = current;
		acted = new Acted();
		acted.act = current.getText();
		acted.done = current.getDone();
		acted.click = click;
		acted.clickTick = client.getTickCount();
		acted.shownTick = current.equals(shown) ? shownTick : NOT_SEEN;
		acted.mocked = mockedClue.getAsBoolean();
		lastStepTick = NOT_SEEN;
	}

	/** Where and from when the player did the act: where they stood at its first sign. */
	private void fixStart()
	{
		if (acted.startTick != NOT_SEEN)
		{
			return;
		}
		acted.at = PlayerTiles.text(PlayerTiles.tile(client));
		acted.startTick = lastStepTick == NOT_SEEN ? acted.clickTick : lastStepTick + 1;
	}

	private void finish()
	{
		fixStart();
		String line = gson.toJson(acted);
		log.info("{} {}", TAG, line);
		timingLog.append(LOG, line, SUMMARY, lines -> ActTimings.tsv(lines.stream()
			.map(logged -> gson.fromJson(logged, Acted.class))
			.collect(Collectors.toList())));
		acted = null;
		acting = null;
	}

	/** One line of the log. A tick stays 0 when its sign never came. */
	static final class Acted
	{
		/** The guide's line. */
		String act;
		SubStep.Done done;
		/** The first click, as the menu showed it. */
		String click;
		int clicks;
		String at;
		int clickTick;
		/** The boundary from which the player stood where they did it. */
		int startTick;
		int animationTick;
		int animation;
		int dialogueTick;
		int messageTick;
		/** The inventory or worn items first changed: an item put on, used, handed in or taken out. */
		int itemsChangedTick;
		/** The guide first showed this act; 0 when it showed before this trace saw it. */
		int shownTick;
		String message;
		int guideMovedOnTick;
		int clueChangedTick;
		/**
		 * The clue was the dev panel's mock: the game never answers its acts (no challenge scroll, no reward), so the
		 * guide and the clue move on only when the next one is mocked.
		 */
		boolean mocked;
	}
}
