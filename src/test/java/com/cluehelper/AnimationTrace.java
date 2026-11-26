package com.cluehelper;

import com.google.gson.Gson;
import java.io.File;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Every animation the local player plays, one {@link Played} line each in {@link #LOG}, summed up per animation and
 * click in {@link #SUMMARY}. Nothing clicked while the bank PIN keypad is open is kept.
 *
 * <p>Ticks: a click carries the tick count of the period it happens in, an animation the server sets the count of the
 * period before its boundary, so click to start = start tick + 1 - click tick. Milliseconds are wall clock.
 */
@Slf4j
final class AnimationTrace
{
	static final String TAG = "[CH-ANIM]";
	static final File LOG = new File(TimingLog.DIR, "animations.jsonl");
	static final File SUMMARY = new File(TimingLog.DIR, "animations.tsv");
	private static final int NO_ANIMATION = -1;
	/** Far enough to walk across a scene to the object the animation is played on. */
	private static final int CLICK_REACH_TICKS = 50;
	private static final int CLIENT_CYCLE_MS = 20;

	private final Client client;
	private final Gson gson;
	private final TimingLog timingLog;
	private Click lastClick;
	private Played playing;

	AnimationTrace(Client client, Gson gson, TimingLog timingLog)
	{
		this.client = client;
		this.gson = gson;
		this.timingLog = timingLog;
	}

	void stop()
	{
		if (playing != null)
		{
			end(Played.End.LOGGED_OFF);
		}
	}

	@Subscribe(priority = -3)
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (PlayerTiles.keypadOpen(client))
		{
			lastClick = null;
			return;
		}
		lastClick = new Click(Text.removeTags(event.getMenuOption()) + " " + Text.removeTags(event.getMenuTarget()),
			client.getTickCount(), System.currentTimeMillis());
		if (playing != null)
		{
			playing.clickedDuring = true;
		}
	}

	@Subscribe(priority = -3)
	public void onAnimationChanged(AnimationChanged event)
	{
		if (!isLocalPlayer(event.getActor()))
		{
			return;
		}
		int animation = event.getActor().getAnimation();
		if (playing != null && playing.animation == animation)
		{
			playing.restarts++;
			return;
		}
		if (playing != null)
		{
			end(animation == NO_ANIMATION ? Played.End.STOPPED : Played.End.REPLACED);
		}
		if (animation != NO_ANIMATION)
		{
			playing = start(animation);
		}
	}

	@Subscribe(priority = -3)
	public void onGraphicChanged(GraphicChanged event)
	{
		if (playing == null || !isLocalPlayer(event.getActor()))
		{
			return;
		}
		for (ActorSpotAnim spotAnim : event.getActor().getSpotAnims())
		{
			playing.graphics.add(spotAnim.getId());
		}
	}

	private Played start(int animation)
	{
		Played played = new Played();
		played.animation = animation;
		played.startTick = client.getTickCount();
		played.startMs = System.currentTimeMillis();
		played.at = PlayerTiles.text(PlayerTiles.tile(client));
		fillCacheLength(played, client.loadAnimation(animation));
		Click click = lastClick;
		if (click != null && played.startTick - click.tick <= CLICK_REACH_TICKS)
		{
			played.click = click.text;
			played.clickToStartTicks = played.startTick + 1 - click.tick;
			played.clickToStartMs = played.startMs - click.ms;
		}
		return played;
	}

	private void end(Played.End how)
	{
		playing.end = how;
		playing.endTick = client.getTickCount();
		playing.playedMs = System.currentTimeMillis() - playing.startMs;
		String line = gson.toJson(playing);
		playing = null;
		log.info("{} {}", TAG, line);
		timingLog.append(LOG, line, SUMMARY, lines -> AnimationTimings.tsv(lines.stream()
			.map(logged -> gson.fromJson(logged, Played.class))
			.collect(Collectors.toList())));
	}

	private static void fillCacheLength(Played played, Animation cached)
	{
		if (cached == null)
		{
			return;
		}
		int[] frameLengths = cached.getFrameLengths();
		int cycles = cached.isMayaAnim() ? cached.getNumFrames() : frameLengths == null ? 0 : IntStream.of(frameLengths).sum();
		played.cacheMs = cycles * CLIENT_CYCLE_MS;
		played.loops = cached.getFrameStep() != NO_ANIMATION;
	}

	private boolean isLocalPlayer(Actor actor)
	{
		return actor != null && actor == client.getLocalPlayer();
	}

	private static final class Click
	{
		private final String text;
		private final int tick;
		private final long ms;

		private Click(String text, int tick, long ms)
		{
			this.text = text;
			this.tick = tick;
			this.ms = ms;
		}
	}

	/** One line of the log. {@link #click} is null, and its ticks and milliseconds 0, when no click led to it. */
	static final class Played
	{
		enum End
		{
			/** Ran out or was cancelled: the player stands idle again. */
			STOPPED,
			/** Another animation took over. */
			REPLACED,
			LOGGED_OFF
		}

		int animation;
		/** Option and target, as the menu showed them. */
		String click;
		int clickToStartTicks;
		long clickToStartMs;
		int startTick;
		long startMs;
		int endTick;
		long playedMs;
		End end;
		/** The player clicked again while it played, so it may have been cut short. */
		boolean clickedDuring;
		/** The same animation was set again while it played, as skilling and combat do. */
		int restarts;
		/** One run through the cache's frames; a looping animation repeats it. */
		int cacheMs;
		boolean loops;
		String at;
		TreeSet<Integer> graphics = new TreeSet<>();
	}
}
