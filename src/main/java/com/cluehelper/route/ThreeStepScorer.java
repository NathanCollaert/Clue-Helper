package com.cluehelper.route;

import com.cluehelper.*;
import com.cluehelper.step.*;
import com.cluehelper.transport.*;
import java.util.*;
import java.util.function.Consumer;
import javax.inject.*;
import net.runelite.client.callback.ClientThread;

/**
 * Which of two three-steps is quicker to do, by their parts alone: each part planned from a teleport-only start at an
 * open bank, so what the player carries and where they stand don't count. Nothing is kept: every comparison plans both.
 */
@Singleton
public class ThreeStepScorer
{
	/** A part no route reaches counts as this slow, so a clue with one is never the quickest. */
	private static final int NO_ROUTE_TICKS = 100_000;

	@Inject
	RoutePlanner routes;
	@Inject
	StepResolver resolver;
	@Inject
	AvailabilityService availability;
	@Inject
	ClueHelperConfig config;
	PlannerRunner runner;

	@Inject
	void init(BackgroundThread executor, ClientThread clientThread)
	{
		runner = new PlannerRunner(executor, clientThread);
	}

	/**
	 * Client thread. Tells {@code then}, on the client thread, whether {@code parts} are quicker than {@code keptParts},
	 * unless a newer comparison was asked for meanwhile.
	 *
	 * @return false while the planner's inputs are still loading: nothing is compared
	 */
	public boolean compare(List<String> parts, List<String> keptParts, Consumer<Boolean> then)
	{
		List<PlanInput> held = inputs(parts);
		List<PlanInput> kept = inputs(keptParts);
		if (held == null || kept == null)
		{
			return false;
		}
		runner.submit(() -> ticks(held) < ticks(kept), then);
		return true;
	}

	private List<PlanInput> inputs(List<String> parts)
	{
		List<PlanInput> inputs = new ArrayList<>();
		for (String part : parts)
		{
			PlanInput input = routes.inputFor(resolver.threeStep(List.of(part), Set.of(), npc -> ""), RequiredItems.NONE);
			if (input == null)
			{
				return null;
			}
			inputs.add(input.toBuilder()
				.start(PlanStart.teleportOnly(input.getStart().getTile()))
				.atOpenBank(true)
				.engine(config.threeStepKeep() == ThreeStepKeep.QUICKEST_MAXED
					? AvailabilityEngine.meetingAll(input.getEngine().getRunes(), availability::itemName) : input.getEngine())
				.build());
		}
		return inputs;
	}

	private static int ticks(List<PlanInput> parts)
	{
		return parts.stream().map(Planner::plan).mapToInt(plan -> plan.getBest() == null ? NO_ROUTE_TICKS : plan.getBest().getTicks())
			.sum();
	}
}
