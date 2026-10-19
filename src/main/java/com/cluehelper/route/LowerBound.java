package com.cluehelper.route;

import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * A* lower bounds for one leg's targets, in half-ticks: the shortest table walk to a target with every gate
 * open, capped by the cheapest teleport or fairy ring followed by such a walk. It never overestimates and is
 * consistent over every edge kind, so the label search stays exact while popping far fewer labels.
 */
final class LowerBound
{
	static final int UNREACHABLE = Integer.MAX_VALUE / 4;

	private final int[] bound;
	private final int viaTeleport;

	LowerBound(PlanContext ctx, int[] targets)
	{
		int[] walk = walkToTargets(ctx, targets);
		int cheapest = UNREACHABLE;
		for (PlanContext.Options options : ctx.options)
		{
			if (options == null)
			{
				continue;
			}
			for (PlanContext.Teleport tele : options.teleports)
			{
				cheapest = Math.min(cheapest, tele.halfTicks + walk[tele.node]);
			}
			int fairy = options.fairyEntries.values().stream().mapToInt(e -> e.halfTicks).min().orElse(UNREACHABLE);
			for (PlanContext.Teleport landing : options.fairyLandings)
			{
				cheapest = Math.min(cheapest, fairy + walk[landing.node]);
			}
		}
		viaTeleport = cheapest;
		bound = walk;
		for (int node = 0; node < bound.length; node++)
		{
			bound[node] = Math.min(bound[node], viaTeleport);
		}
		bound[ctx.start] = 0;
		bound[ctx.goal] = 0;
	}

	int of(int node)
	{
		return bound[node];
	}

	private static int[] walkToTargets(PlanContext ctx, int[] targets)
	{
		WalkTable table = ctx.table;
		int[] walk = new int[ctx.nodeCount()];
		Arrays.fill(walk, UNREACHABLE);
		PriorityQueue<int[]> queue = new PriorityQueue<>((a, b) -> a[1] - b[1]);
		for (int target : targets)
		{
			walk[target] = 0;
			queue.add(new int[]{target, 0});
		}
		while (!queue.isEmpty())
		{
			int[] head = queue.poll();
			int node = head[0];
			if (head[1] != walk[node] || !ctx.isTableNode(node))
			{
				continue;
			}
			for (int i = table.reverseFrom(node); i < table.reverseEnd(node); i++)
			{
				int entry = table.reverseEntry(i);
				int from = table.entryFrom(entry);
				int cost = head[1] + table.entryFull(entry);
				if (cost < walk[from])
				{
					walk[from] = cost;
					queue.add(new int[]{from, cost});
				}
			}
		}
		return walk;
	}
}
