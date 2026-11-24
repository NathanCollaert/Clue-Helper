package com.cluehelper;

import com.cluehelper.guide.StepGuide;
import com.cluehelper.guide.SubStep;
import com.cluehelper.link.ClueLink;
import com.google.gson.Gson;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Times each act the guide points at (a dig, search, talk, emote, equip, STASH withdrawal, hand-in): one {@link Acted}
 * line each in {@link #LOG}, summed up per act in {@link #SUMMARY}. An act runs from the player standing where they do
 * it to its first animation, dialogue and game message, to the guide moving on, and to the clue changing.
 *
 * <p>Ticks: as in {@link CrossingTrace}, the player stands there from boundary S + 1, S the GameTick count they were
 * first seen on the tile, or from the click's own period when already there; a server effect seen with count E
 * happened at boundary E + 1, so stand to effect = E + 1 - start. The guide and the clue move on in the client, on the
 * count of the tick they do it in.
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
	private final List<Runnable> unregister = new ArrayList<>();
	private SubStep acting;
	private Acted acted;
	private WorldPoint tileAtClick;
	private final Map<WorldPoint, Integer> firstSeen = new HashMap<>();

	ActTrace(Client client, StepGuide guide, ClueLink clueLink, Gson gson, TimingLog timingLog)
	{
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
			acted.animationTick = client.getTickCount();
			acted.animation = event.getActor().getAnimation();
			markStanding();
		}
	}

	@Subscribe(priority = -3)
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (acted != null && acted.dialogueTick == NOT_SEEN && DIALOGUES.contains(event.getGroupId()))
		{
			acted.dialogueTick = client.getTickCount();
			markStanding();
		}
	}

	@Subscribe(priority = -3)
	public void onChatMessage(ChatMessage event)
	{
		if (acted != null && acted.messageTick == NOT_SEEN && GAME_LINES.contains(event.getType()))
		{
			acted.messageTick = client.getTickCount();
			acted.message = Text.removeTags(event.getMessage());
			markStanding();
		}
	}

	@Subscribe(priority = -3)
	public void onGameTick(GameTick tick)
	{
		if (acted == null)
		{
			return;
		}
		firstSeen.putIfAbsent(PlayerTiles.tile(client), client.getTickCount());
		if (client.getTickCount() - acted.clickTick > GIVE_UP_TICKS)
		{
			finish();
		}
	}

	private void onGuideChanged()
	{
		if (acted != null && acted.guideMovedOnTick == NOT_SEEN && !Objects.equals(guide.getCurrent(), acting))
		{
			acted.guideMovedOnTick = client.getTickCount();
			finishOnceBothMoved();
		}
	}

	private void onClueChanged()
	{
		if (acted != null && acted.clueChangedTick == NOT_SEEN)
		{
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
		tileAtClick = PlayerTiles.tile(client);
		firstSeen.clear();
	}

	/** Where the player did the act is where they stood at its first effect. */
	private void markStanding()
	{
		if (acted.startTick != NOT_SEEN)
		{
			return;
		}
		WorldPoint tile = PlayerTiles.tile(client);
		acted.at = PlayerTiles.text(tile);
		acted.startTick = Objects.equals(tile, tileAtClick) ? acted.clickTick : firstSeen.getOrDefault(tile, client.getTickCount()) + 1;
	}

	private void finish()
	{
		String line = gson.toJson(acted);
		log.info("{} {}", TAG, line);
		timingLog.append(LOG, line, SUMMARY, lines -> ActTimings.tsv(lines.stream()
			.map(logged -> gson.fromJson(logged, Acted.class))
			.collect(Collectors.toList())));
		acted = null;
		acting = null;
	}

	/** One line of the log. A tick stays 0 when its effect never came. */
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
		String message;
		int guideMovedOnTick;
		int clueChangedTick;
	}
}
