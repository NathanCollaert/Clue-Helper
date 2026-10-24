package com.cluehelper.hotcold;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;

/**
 * Every tile within each spot's radius of its centre, kept while it explains the step's readings exactly: the band from
 * the Chebyshev distance, and each comparison against the previous reading of the log. When no tile explains them all,
 * the tiles with the fewest misses are kept instead, so the grid never empties.
 */
public final class Hypotheses
{
	/** 133 of 140 cache centres equal core's, so a spot's prior sits mostly on its centre. */
	static final double CENTRE_SHARE = 0.5;

	private final List<Spot> spots;
	private final int[] x;
	private final int[] y;
	private final int[] spot;
	private final double[] weight;
	private final int misses;

	private Hypotheses(List<Spot> spots, int[] x, int[] y, int[] spot, double[] weight, int misses)
	{
		this.spots = spots;
		this.x = x;
		this.y = y;
		this.spot = spot;
		this.weight = weight;
		this.misses = misses;
	}

	public static Hypotheses filter(List<Spot> spots, boolean beginner, List<Reading> readings)
	{
		List<List<WorldPoint>> tiles = new ArrayList<>();
		for (Reading reading : readings)
		{
			tiles.add(reading.tiles());
		}
		List<int[]> kept = new ArrayList<>();
		int fewest = Integer.MAX_VALUE;
		for (int s = 0; s < spots.size(); s++)
		{
			Spot candidate = spots.get(s);
			int r = candidate.getRadius();
			for (int dx = -r; dx <= r; dx++)
			{
				for (int dy = -r; dy <= r; dy++)
				{
					int hx = candidate.getX() + dx;
					int hy = candidate.getY() + dy;
					int missed = misses(hx, hy, readings, tiles, beginner, fewest);
					if (missed < fewest)
					{
						fewest = missed;
						kept.clear();
					}
					if (missed == fewest)
					{
						kept.add(new int[]{hx, hy, s, dx == 0 && dy == 0 ? 1 : 0});
					}
				}
			}
		}
		int n = kept.size();
		int[] x = new int[n];
		int[] y = new int[n];
		int[] spot = new int[n];
		double[] weight = new double[n];
		for (int i = 0; i < n; i++)
		{
			int[] h = kept.get(i);
			x[i] = h[0];
			y[i] = h[1];
			spot[i] = h[2];
			weight[i] = priorWeight(spots.get(h[2]), h[3] == 1);
		}
		return new Hypotheses(spots, x, y, spot, weight, fewest == Integer.MAX_VALUE ? 0 : fewest);
	}

	/** Counting stops past {@code enough}: such a tile is dropped anyway. */
	private static int misses(int hx, int hy, List<Reading> readings, List<List<WorldPoint>> tiles, boolean beginner, int enough)
	{
		int missed = 0;
		for (int i = 0; i < readings.size() && missed <= enough; i++)
		{
			Reading reading = readings.get(i);
			if (!reading.isAnchor() && !fitsBand(hx, hy, reading.getBand(), tiles.get(i), beginner))
			{
				missed++;
			}
			if (i > 0 && reading.getComparison() != null && !fitsComparison(hx, hy, reading.getComparison(), tiles.get(i), tiles.get(i - 1)))
			{
				missed++;
			}
		}
		return missed;
	}

	private static boolean fitsBand(int hx, int hy, Band band, List<WorldPoint> at, boolean beginner)
	{
		for (WorldPoint tile : at)
		{
			if (Band.of(distance(tile, hx, hy), beginner) == band)
			{
				return true;
			}
		}
		return false;
	}

	private static boolean fitsComparison(int hx, int hy, Comparison comparison, List<WorldPoint> at, List<WorldPoint> before)
	{
		for (WorldPoint tile : at)
		{
			for (WorldPoint previous : before)
			{
				if (Comparison.of(distance(tile, hx, hy), distance(previous, hx, hy)) == comparison)
				{
					return true;
				}
			}
		}
		return false;
	}

	static int distance(WorldPoint tile, int hx, int hy)
	{
		return Math.max(Math.abs(tile.getX() - hx), Math.abs(tile.getY() - hy));
	}

	private static double priorWeight(Spot spot, boolean centre)
	{
		int side = 2 * spot.getRadius() + 1;
		return centre ? CENTRE_SHARE : (1 - CENTRE_SHARE) / (side * side - 1);
	}

	public boolean isExact()
	{
		return misses == 0;
	}

	/** Each kept spot's share of the posterior, largest first. */
	public Map<String, Double> spotShares()
	{
		double[] mass = new double[spots.size()];
		double total = 0;
		for (int i = 0; i < x.length; i++)
		{
			mass[spot[i]] += weight[i];
			total += weight[i];
		}
		List<Integer> order = new ArrayList<>();
		for (int s = 0; s < mass.length; s++)
		{
			if (mass[s] > 0)
			{
				order.add(s);
			}
		}
		order.sort((a, b) -> Double.compare(mass[b], mass[a]));
		Map<String, Double> shares = new LinkedHashMap<>();
		for (int s : order)
		{
			shares.put(spots.get(s).getName(), mass[s] / total);
		}
		return shares;
	}

	public List<Spot> keptSpots()
	{
		List<Spot> kept = new ArrayList<>();
		for (String name : spotShares().keySet())
		{
			kept.add(spot(name));
		}
		return kept;
	}

	public Spot spot(String name)
	{
		for (Spot candidate : spots)
		{
			if (candidate.getName().equals(name))
			{
				return candidate;
			}
		}
		return null;
	}

	/** The tile nearest, by Chebyshev, to the farthest kept tile of the spot: a dig there covers them all if any tile does. */
	public WorldPoint digTile(String spotName)
	{
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
		for (int i = 0; i < x.length; i++)
		{
			if (spots.get(spot[i]).getName().equals(spotName))
			{
				minX = Math.min(minX, x[i]);
				minY = Math.min(minY, y[i]);
				maxX = Math.max(maxX, x[i]);
				maxY = Math.max(maxY, y[i]);
			}
		}
		return minX == Integer.MAX_VALUE ? null : new WorldPoint((minX + maxX) / 2, (minY + maxY) / 2, 0);
	}

	/** Up to {@code perSpot} kept tiles of each spot, the centre first, weighted to keep each spot's share. */
	public Samples sample(int perSpot)
	{
		List<Integer> spotIndex = new ArrayList<>();
		List<String> names = new ArrayList<>();
		List<int[]> picked = new ArrayList<>();
		List<Double> weights = new ArrayList<>();
		double total = 0;
		for (double w : weight)
		{
			total += w;
		}
		for (int s = 0; s < spots.size(); s++)
		{
			List<Integer> mine = new ArrayList<>();
			double mass = 0;
			for (int i = 0; i < x.length; i++)
			{
				if (spot[i] == s)
				{
					mine.add(i);
					mass += weight[i];
				}
			}
			if (mine.isEmpty())
			{
				continue;
			}
			int compact = names.size();
			names.add(spots.get(s).getName());
			spotIndex.add(s);
			List<Integer> chosen = spread(mine, perSpot, spots.get(s));
			double chosenMass = 0;
			for (int i : chosen)
			{
				chosenMass += weight[i];
			}
			for (int i : chosen)
			{
				picked.add(new int[]{x[i], y[i], compact});
				weights.add(weight[i] / chosenMass * mass / total);
			}
		}
		int n = picked.size();
		int[] sx = new int[n];
		int[] sy = new int[n];
		int[] ss = new int[n];
		double[] sw = new double[n];
		for (int i = 0; i < n; i++)
		{
			sx[i] = picked.get(i)[0];
			sy[i] = picked.get(i)[1];
			ss[i] = picked.get(i)[2];
			sw[i] = weights.get(i);
		}
		return new Samples(List.copyOf(names), sx, sy, ss, sw);
	}

	private List<Integer> spread(List<Integer> mine, int perSpot, Spot owner)
	{
		if (mine.size() <= perSpot)
		{
			return mine;
		}
		List<Integer> chosen = new ArrayList<>();
		for (int i : mine)
		{
			if (x[i] == owner.getX() && y[i] == owner.getY())
			{
				chosen.add(i);
			}
		}
		int wanted = perSpot - chosen.size();
		for (int k = 0; k < wanted; k++)
		{
			int i = mine.get((int) ((long) k * mine.size() / wanted));
			if (!chosen.contains(i))
			{
				chosen.add(i);
			}
		}
		return chosen;
	}

	/** Sampled hypotheses with weights summing to 1; spots are indexed into {@link #names}. */
	public static final class Samples
	{
		final List<String> names;
		final int[] x;
		final int[] y;
		final int[] spot;
		final double[] weight;

		Samples(List<String> names, int[] x, int[] y, int[] spot, double[] weight)
		{
			this.names = names;
			this.x = x;
			this.y = y;
			this.spot = spot;
			this.weight = weight;
		}

		public int spotCount()
		{
			return names.size();
		}
	}
}
