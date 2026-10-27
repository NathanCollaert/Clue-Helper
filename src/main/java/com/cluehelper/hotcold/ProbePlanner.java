package com.cluehelper.hotcold;

import com.cluehelper.route.Running;
import com.cluehelper.step.HotColdTier;
import java.util.*;
import lombok.*;
import net.runelite.api.coords.WorldPoint;

/**
 * Picks the probe with the most information per tick: the mutual information between the next reading and the spot,
 * over the sampled hypotheses, divided by the probe's cost. Predicts readings with the same rules {@link Hypotheses}
 * filters by, so a probe it values can't leave the grid as it was. Pure.
 */
public final class ProbePlanner
{
	/** A feel doesn't stop movement; the click and the chat line take about a tick. */
	public static final int FEEL_TICKS = 1;
	/**
	 * Ranking-only ticks on every feel. In the simulator 1 to 3 solve masters fastest and with fewer feels than none;
	 * from 4 up the solves get slower.
	 */
	private static final int FEEL_PENALTY_TICKS = 2;
	/** Below this a probe can't tell any two spots apart. */
	private static final double MIN_BITS = 1e-6;
	private static final int COMPARISONS = Comparison.values().length + 1;
	private static final int OUTCOMES = Band.values().length * COMPARISONS;
	private static final double LOG2 = Math.log(2);
	/** With this few spots left the next feel can end the solve, so the trip from the probe to the dig counts too. */
	private static final int ONWARD_SPOTS = 3;

	private ProbePlanner()
	{
	}

	@Value
	@Builder
	public static class Costs
	{
		int wildernessPenaltyTicks;
	}

	@Value
	public static class Branch
	{
		Band band;
		/** Null when the reading would have no comparison. */
		Comparison comparison;
		double probability;
		/** Spot name to its share within this branch, largest first. */
		Map<String, Double> spots;
	}

	@Value
	public static class Choice
	{
		Probe probe;
		double bits;
		int costTicks;
		/** Most likely first. */
		List<Branch> branches;
	}

	/**
	 * @param previous the log's last reading, or null; the next reading is compared against it
	 * @return null when no probe tells the spots apart
	 */
	public static Choice choose(Hypotheses.Samples samples, HotColdTier tier, Reading previous, List<Probe> probes, Costs costs)
	{
		WorldPoint before = previous == null ? null : previous.tiles().get(0);
		double prior = entropy(spotMasses(samples));
		double[] table = new double[OUTCOMES * samples.spotCount()];
		int[] touched = new int[samples.x.length];
		double[] outcomeMass = new double[OUTCOMES];
		int[] digTicks = samples.spotCount() <= ONWARD_SPOTS ? digTicks(samples, probes) : null;
		Probe best = null;
		double bestScore = 0;
		double bestBits = 0;
		int bestCost = 0;
		for (Probe probe : probes)
		{
			double bits = prior - conditionalEntropy(table, touched, outcomeMass, samples, tier, before, probe.getTile());
			if (bits < MIN_BITS)
			{
				continue;
			}
			int cost = cost(probe, costs) + (digTicks == null ? 0 : onward(samples, digTicks, probe.getTile()));
			double score = bits / cost;
			if (best == null || score > bestScore || score == bestScore && cost < bestCost)
			{
				best = probe;
				bestScore = score;
				bestBits = bits;
				bestCost = cost;
			}
		}
		if (best == null)
		{
			return null;
		}
		return new Choice(best, bestBits, bestCost, branches(samples, tier, before, best.getTile()));
	}

	static int cost(Probe probe, Costs costs)
	{
		return probe.getRankTicks() + FEEL_TICKS + FEEL_PENALTY_TICKS + (probe.isWilderness() ? costs.getWildernessPenaltyTicks() : 0);
	}

	/**
	 * Each spot's dig as if reached from here: the cheapest probe near it plus the walk, which teleports make about the
	 * same from anywhere.
	 */
	private static int[] digTicks(Hypotheses.Samples samples, List<Probe> probes)
	{
		int spots = samples.spotCount();
		int[] ticks = new int[spots];
		for (int s = 0; s < spots; s++)
		{
			WorldPoint centre = centre(samples, s);
			int best = Integer.MAX_VALUE;
			for (Probe probe : probes)
			{
				best = Math.min(best, probe.getRankTicks() + walkTicks(probe.getTile(), centre.getX(), centre.getY()));
			}
			ticks[s] = best;
		}
		return ticks;
	}

	/** The expected trip from the probe to the dig: a walk when it's near, else as from here. */
	private static int onward(Hypotheses.Samples samples, int[] digTicks, WorldPoint at)
	{
		double ticks = 0;
		for (int i = 0; i < samples.x.length; i++)
		{
			ticks += samples.weight[i] * Math.min(walkTicks(at, samples.x[i], samples.y[i]), digTicks[samples.spot[i]]);
		}
		return (int) Math.round(ticks);
	}

	private static int walkTicks(WorldPoint from, int x, int y)
	{
		return (Running.halfTicks(Hypotheses.distance(from, x, y)) + 1) / 2;
	}

	private static WorldPoint centre(Hypotheses.Samples samples, int spot)
	{
		double x = 0, y = 0, mass = 0;
		for (int i = 0; i < samples.x.length; i++)
		{
			if (samples.spot[i] == spot)
			{
				x += samples.weight[i] * samples.x[i];
				y += samples.weight[i] * samples.y[i];
				mass += samples.weight[i];
			}
		}
		return new WorldPoint((int) Math.round(x / mass), (int) Math.round(y / mass), 0);
	}

	/** {@code table} must be all zeros, and is again on return; {@code touched} holds at least one slot per sample. */
	private static double conditionalEntropy(double[] table, int[] touched, double[] outcomeMass, Hypotheses.Samples samples,
		HotColdTier tier, WorldPoint before, WorldPoint at)
	{
		int spots = samples.spotCount();
		int n = 0;
		for (int i = 0; i < samples.x.length; i++)
		{
			int cell = outcome(samples.x[i], samples.y[i], tier, before, at) * spots + samples.spot[i];
			if (table[cell] == 0)
			{
				touched[n++] = cell;
			}
			table[cell] += samples.weight[i];
		}
		double spread = 0;
		for (int t = 0; t < n; t++)
		{
			int cell = touched[t];
			double m = table[cell];
			spread += m * Math.log(m);
			outcomeMass[cell / spots] += m;
			table[cell] = 0;
		}
		double sum = -spread;
		for (int o = 0; o < OUTCOMES; o++)
		{
			if (outcomeMass[o] > 0)
			{
				sum += outcomeMass[o] * Math.log(outcomeMass[o]);
				outcomeMass[o] = 0;
			}
		}
		return sum / LOG2;
	}

	static int outcome(int hx, int hy, HotColdTier tier, WorldPoint before, WorldPoint at)
	{
		int distance = Hypotheses.distance(at, hx, hy);
		Band band = Band.of(distance, tier);
		if (before == null || band == Band.SHAKING)
		{
			return band.ordinal() * COMPARISONS;
		}
		return band.ordinal() * COMPARISONS + 1 + Comparison.of(distance, Hypotheses.distance(before, hx, hy)).ordinal();
	}

	private static List<Branch> branches(Hypotheses.Samples samples, HotColdTier tier, WorldPoint before, WorldPoint at)
	{
		int spots = samples.spotCount();
		double[] table = new double[OUTCOMES * spots];
		for (int i = 0; i < samples.x.length; i++)
		{
			table[outcome(samples.x[i], samples.y[i], tier, before, at) * spots + samples.spot[i]] += samples.weight[i];
		}
		List<Branch> branches = new ArrayList<>();
		for (int o = 0; o < OUTCOMES; o++)
		{
			double total = 0;
			for (int s = 0; s < spots; s++)
			{
				total += table[o * spots + s];
			}
			if (total <= 0)
			{
				continue;
			}
			List<Integer> order = new ArrayList<>();
			for (int s = 0; s < spots; s++)
			{
				if (table[o * spots + s] > 0)
				{
					order.add(s);
				}
			}
			int row = o;
			order.sort((a, b) -> Double.compare(table[row * spots + b], table[row * spots + a]));
			Map<String, Double> shares = new LinkedHashMap<>();
			for (int s : order)
			{
				shares.put(samples.names.get(s), table[o * spots + s] / total);
			}
			int c = o % COMPARISONS;
			branches.add(new Branch(Band.values()[o / COMPARISONS], c == 0 ? null : Comparison.values()[c - 1], total, shares));
		}
		branches.sort((a, b) -> Double.compare(b.getProbability(), a.getProbability()));
		return branches;
	}

	private static double[] spotMasses(Hypotheses.Samples samples)
	{
		double[] mass = new double[samples.spotCount()];
		for (int i = 0; i < samples.x.length; i++)
		{
			mass[samples.spot[i]] += samples.weight[i];
		}
		return mass;
	}

	private static double entropy(double[] masses)
	{
		double h = 0;
		for (double m : masses)
		{
			if (m > 0)
			{
				h -= m * Math.log(m);
			}
		}
		return h / LOG2;
	}
}
