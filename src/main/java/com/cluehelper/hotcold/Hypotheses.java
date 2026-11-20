package com.cluehelper.hotcold;

import com.cluehelper.step.HotColdTier;
import java.util.*;
import java.util.function.IntFunction;
import lombok.*;
import net.runelite.api.coords.WorldPoint;
import static java.util.stream.Collectors.*;

/**
 * Every tile within each spot's radius of its centre, kept while it explains the step's readings exactly: the band from
 * the Chebyshev distance, and each comparison against the previous reading of the log. When no tile explains them all,
 * the tiles with the fewest misses are kept instead, so the grid never empties. Only spots with a kept tile are held,
 * and the tiles' weights sum to 1.
 */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class Hypotheses
{
	/** 133 of 140 cache centres equal core's, so a spot's prior sits mostly on its centre. */
	private static final double CENTRE_SHARE = 0.5;

	private final List<Spot> spots;
	final int[] x;
	final int[] y;
	/** Indexes into {@link #spots}. */
	final int[] spot;
	final double[] weight;
	private final int misses;

	/**
	 * The grid of the newest readings that a spot fits exactly and that keep no spot core has dropped. An older reading
	 * that contradicts them is taken as left over from another clue or missed in between: core starts afresh on each
	 * clue and keeps the true spot, while an outdated log can rule it out. Failing that, the newest readings that fit
	 * exactly, then the whole log's closest fits. The anchor a solved step left rules nothing out: the game's "last time"
	 * can be a feel the log never saw, and one more feel costs less than a dig in the wrong place.
	 *
	 * @param coreSpots core's remaining spots; empty when it has none to go by
	 */
	public static Hypotheses fitting(List<Spot> spots, HotColdTier tier, List<Reading> logged, Set<String> coreSpots)
	{
		List<Reading> readings = withoutCarriedAnchor(logged);
		Hypotheses exact = null;
		for (int start = 0; start < readings.size(); start++)
		{
			Hypotheses grid = filter(spots, tier, from(readings, start));
			if (grid.isExact() && (coreSpots.isEmpty() || coreSpots.containsAll(grid.spotShares().keySet())))
			{
				return grid;
			}
			if (grid.isExact() && exact == null)
			{
				exact = grid;
			}
		}
		return exact != null ? exact : filter(spots, tier, readings);
	}

	private static List<Reading> withoutCarriedAnchor(List<Reading> logged)
	{
		return !logged.isEmpty() && logged.get(0).isAnchor() ? logged.subList(1, logged.size()) : logged;
	}

	/** The readings from {@code start} on, the one before kept as the anchor of their first comparison. */
	private static List<Reading> from(List<Reading> readings, int start)
	{
		if (start == 0)
		{
			return readings;
		}
		List<Reading> newest = new ArrayList<>();
		newest.add(readings.get(start - 1).asAnchor());
		newest.addAll(readings.subList(start, readings.size()));
		return newest;
	}

	public static Hypotheses filter(List<Spot> spots, HotColdTier tier, List<Reading> readings)
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
					int missed = misses(hx, hy, readings, tiles, tier, fewest);
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
		List<Spot> keptSpots = new ArrayList<>();
		double total = 0;
		for (int i = 0; i < n; i++)
		{
			int[] h = kept.get(i);
			Spot owner = spots.get(h[2]);
			if (!keptSpots.contains(owner))
			{
				keptSpots.add(owner);
			}
			x[i] = h[0];
			y[i] = h[1];
			spot[i] = keptSpots.size() - 1;
			weight[i] = priorWeight(owner, h[3] == 1);
			total += weight[i];
		}
		for (int i = 0; i < n; i++)
		{
			weight[i] /= total;
		}
		return new Hypotheses(List.copyOf(keptSpots), x, y, spot, weight, fewest == Integer.MAX_VALUE ? 0 : fewest);
	}

	/** Counting stops past {@code enough}: such a tile is dropped anyway. */
	private static int misses(int hx, int hy, List<Reading> readings, List<List<WorldPoint>> tiles, HotColdTier tier, int enough)
	{
		int missed = 0;
		for (int i = 0; i < readings.size() && missed <= enough; i++)
		{
			Reading reading = readings.get(i);
			if (!reading.isAnchor() && !fitsBand(hx, hy, reading.getBand(), tiles.get(i), tier))
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

	private static boolean fitsBand(int hx, int hy, Band band, List<WorldPoint> at, HotColdTier tier)
	{
		for (WorldPoint tile : at)
		{
			if (Band.of(distance(tile, hx, hy), tier) == band)
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
		return sharesLargestFirst(spotMasses(), 1, s -> spots.get(s).getName());
	}

	/** Each index with mass, by its name, to its share of {@code total}; largest first, ties in index order. */
	static Map<String, Double> sharesLargestFirst(double[] mass, double total, IntFunction<String> name)
	{
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
			shares.put(name.apply(s), mass[s] / total);
		}
		return shares;
	}

	public List<Spot> keptSpots()
	{
		return spotShares().keySet().stream().map(this::spot).collect(toList());
	}

	public Spot spot(String name)
	{
		return spots.stream().filter(candidate -> candidate.getName().equals(name)).findFirst().orElse(null);
	}

	/**
	 * The spot's centre when a dig there covers every kept tile: the walk table has a node on it, so the route reaches
	 * it from anywhere, while any other tile is reached only from within the loaded scene. Otherwise the tile nearest,
	 * by Chebyshev, to the farthest kept tile: a dig there covers them all if any tile does.
	 */
	public WorldPoint digTile(String spotName)
	{
		Spot target = spot(spotName);
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
		for (int i = 0; i < x.length; i++)
		{
			if (spots.get(spot[i]) == target)
			{
				minX = Math.min(minX, x[i]);
				minY = Math.min(minY, y[i]);
				maxX = Math.max(maxX, x[i]);
				maxY = Math.max(maxY, y[i]);
			}
		}
		if (minX == Integer.MAX_VALUE)
		{
			return null;
		}
		int fromCentre = Math.max(Math.max(target.getX() - minX, maxX - target.getX()), Math.max(target.getY() - minY, maxY - target.getY()));
		return fromCentre <= Band.digRadius(target.getTier()) ? new WorldPoint(target.getX(), target.getY(), 0)
			: new WorldPoint((minX + maxX) / 2, (minY + maxY) / 2, 0);
	}

	int spotCount()
	{
		return spots.size();
	}

	/** Each spot's share of the weight, by its index. */
	double[] spotMasses()
	{
		double[] mass = new double[spots.size()];
		for (int i = 0; i < x.length; i++)
		{
			mass[spot[i]] += weight[i];
		}
		return mass;
	}
}
