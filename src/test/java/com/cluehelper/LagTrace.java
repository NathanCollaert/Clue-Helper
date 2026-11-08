package com.cluehelper;

import com.cluehelper.guide.StepGuide;
import com.cluehelper.link.*;
import com.cluehelper.player.PositionRules;
import com.cluehelper.route.*;
import com.cluehelper.step.Step;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.eventbus.Subscribe;

/**
 * {@code [CH-LAG]} lines: from a teleport's first frame at the new tile, or a new step, how long until the scene loaded,
 * the game tick, the route plan, the guide and the start walk from the player's tile, which the path line needs.
 */
@Slf4j
final class LagTrace
{
	private static final String TAG = "[CH-LAG]";
	private static final long GIVE_UP_NANOS = 10_000_000_000L;
	private static final long NANOS_PER_MS = 1_000_000L;

	private final Client client;
	private final RoutePlanner routes;
	private final StepGuide guide;
	private final List<Runnable> unregister = new ArrayList<>();
	private WorldPoint lastFrameTile;
	private Episode episode;

	private static final class Episode
	{
		final String what;
		final long started = System.nanoTime();
		final Map<String, Long> marks = new LinkedHashMap<>();

		Episode(String what)
		{
			this.what = what;
		}

		void mark(String name)
		{
			marks.putIfAbsent(name, System.nanoTime() - started);
		}
	}

	LagTrace(Client client, ClueLink clueLink, RoutePlanner routes, StepGuide guide)
	{
		this.client = client;
		this.routes = routes;
		this.guide = guide;
		unregister.add(clueLink.addListener(this::onStepChange));
		unregister.add(routes.addListener(plan -> mark("plan")));
		unregister.add(guide.addListener(() -> mark("guide")));
	}

	void stop()
	{
		unregister.forEach(Runnable::run);
	}

	@Subscribe(priority = -3)
	public void onClientTick(ClientTick tick)
	{
		Player local = client.getLocalPlayer();
		WorldPoint tile = local == null ? null : local.getWorldLocation();
		if (PositionRules.teleported(lastFrameTile, tile))
		{
			finish("superseded");
			episode = new Episode("teleport to " + tile.getX() + "," + tile.getY() + "," + tile.getPlane());
		}
		lastFrameTile = tile;
		if (episode == null)
		{
			return;
		}
		PlanStart start = routes.getStart();
		if (start != null && start.getTile() != null && start.getTile().equals(tile) && routes.getPlan().getBest() != null)
		{
			mark("start walk here");
		}
		boolean ready = episode.marks.containsKey("start walk here") && guide.getCurrent() != null
			&& (episode.marks.containsKey("plan") || !episode.what.startsWith("step"));
		if (ready || System.nanoTime() - episode.started > GIVE_UP_NANOS)
		{
			finish(ready ? "ready" : "gave up");
		}
	}

	@Subscribe(priority = -3)
	public void onGameTick(GameTick tick)
	{
		mark("game tick");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		mark(event.getGameState().name().toLowerCase(Locale.ROOT));
	}

	private void onStepChange(StepChange change, Step step)
	{
		if (change != StepChange.NEW)
		{
			return;
		}
		finish("superseded");
		episode = new Episode("step " + step.getKey());
	}

	private void mark(String name)
	{
		if (episode != null)
		{
			episode.mark(name);
		}
	}

	private void finish(String outcome)
	{
		if (episode == null)
		{
			return;
		}
		StringBuilder line = new StringBuilder(TAG).append(' ').append(episode.what).append(" (").append(outcome).append("):");
		episode.marks.forEach((name, nanos) -> line.append(' ').append(name).append(" +").append(nanos / NANOS_PER_MS).append(" ms,"));
		log.info(line.substring(0, line.length() - 1));
		episode = null;
	}
}
