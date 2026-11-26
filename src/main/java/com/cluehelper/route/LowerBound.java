package com.cluehelper.route;

import java.util.*;

/**
 * A* lower bounds in half-ticks. They must never overestimate and must stay consistent over every edge kind, or the
 * label search stops being exact.
 */
final class LowerBound
{
	static final int UNREACHABLE = Integer.MAX_VALUE / 4;

	final int[] bound;

	LowerBound(PlanContext ctx, int[] targets)
	{
		int[] walk = walkToTargets(ctx, targets);
		int cheapest = UNREACHABLE;
		for (TransportOptions options : ctx.options)
		{
			if (options == null)
			{
				continue;
			}
			for (TransportOptions.Teleport tele : options.teleports)
			{
				cheapest = Math.min(cheapest, tele.halfTicks + walk[tele.node]);
			}
			for (List<TransportOptions.Teleport> here : options.located.values())
			{
				for (TransportOptions.Teleport tele : here)
				{
					cheapest = Math.min(cheapest, tele.halfTicks + walk[tele.node]);
				}
			}
			int fairy = options.fairyEntries.values().stream().mapToInt(e -> e.halfTicks).min().orElse(UNREACHABLE);
			for (TransportOptions.Teleport landing : options.fairyLandings)
			{
				cheapest = Math.min(cheapest, fairy + walk[landing.node]);
			}
		}
		bound = walk;
		for (int node = 0; node < bound.length; node++)
		{
			bound[node] = Math.min(bound[node], cheapest);
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
		int[] seed = new int[ctx.nodeCount()];
		Arrays.fill(seed, UNREACHABLE);
		for (int target : targets)
		{
			seed[target] = 0;
		}
		return walkTo(ctx, seed);
	}

	/** From every node, the cheapest walk to a seeded node plus that node's seed; the seed array becomes the result. */
	static int[] walkTo(PlanContext ctx, int[] seed)
	{
		WalkTable table = ctx.table;
		int[] walk = seed;
		PriorityQueue<int[]> queue = new PriorityQueue<>((a, b) -> a[1] - b[1]);
		for (int node = 0; node < walk.length; node++)
		{
			if (walk[node] < UNREACHABLE)
			{
				queue.add(new int[]{node, walk[node]});
			}
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
