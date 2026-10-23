package com.cluehelper.route;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** An estimate per transport, not a search: consumption and spellbook changes are left out. */
final class WhyList
{
	private WhyList()
	{
	}

	static List<Plan.Option> of(PlanContext ctx, int[] targets)
	{
		int[] walk = gatedWalkToTargets(ctx, targets);
		PlanContext.Options carried = ctx.options[PlanContext.NOT_BANKED];
		List<Plan.Option> options = new ArrayList<>();
		for (PlanContext.Teleport tele : carried.teleports)
		{
			boolean otherBook = tele.book != null && tele.book != ctx.startBook;
			if (!otherBook && reached(walk, tele.node))
			{
				options.add(new Plan.Option(tele.row.getName(), ticks(tele.halfTicks + walk[tele.node]), false));
			}
		}
		int ring = carried.fairyEntries.values().stream().mapToInt(entry -> entry.halfTicks).min().orElse(LowerBound.UNREACHABLE);
		for (PlanContext.Teleport landing : carried.fairyLandings)
		{
			if (ring != LowerBound.UNREACHABLE && reached(walk, landing.node))
			{
				options.add(new Plan.Option(landing.row.getName(), ticks(Math.max(ring, landing.halfTicks) + walk[landing.node]), true));
			}
		}
		options.sort(Comparator.comparingInt(Plan.Option::getTicks).thenComparing(Plan.Option::getName));
		return List.copyOf(options);
	}

	private static boolean reached(int[] walk, int node)
	{
		return node >= 0 && node < walk.length && walk[node] < LowerBound.UNREACHABLE;
	}

	private static int ticks(int halfTicks)
	{
		return (halfTicks + 1) / 2;
	}

	private static int[] gatedWalkToTargets(PlanContext ctx, int[] targets)
	{
		WalkTable table = ctx.table;
		int[] walk = new int[ctx.nodeCount()];
		Arrays.fill(walk, LowerBound.UNREACHABLE);
		PriorityQueue<int[]> queue = new PriorityQueue<>(Comparator.comparingInt(a -> a[1]));
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
				int tiles = ctx.gates(PlanContext.NOT_BANKED).cost(entry);
				int from = table.entryFrom(entry);
				if (tiles != WalkTable.NO_PATH && head[1] + tiles < walk[from])
				{
					walk[from] = head[1] + tiles;
					queue.add(new int[]{from, walk[from]});
				}
			}
		}
		return walk;
	}
}
