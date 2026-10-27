package com.cluehelper.route;

import com.cluehelper.player.*;
import com.cluehelper.transport.*;
import java.util.*;
import java.util.stream.IntStream;
import net.runelite.api.gameval.ItemID;

/** An unread var fails its gate, so an unread account walks the always-open way. */
public final class WalkGates
{
	private static final int[] NOTHING = new int[0];
	/** Any tier cuts a web on the first slash; a knife does half the time, other blades at most as often. */
	private static final Set<Integer> WILDERNESS_SWORDS = Set.of(ItemID.WILDERNESS_SWORD_EASY, ItemID.WILDERNESS_SWORD_MEDIUM,
		ItemID.WILDERNESS_SWORD_HARD, ItemID.WILDERNESS_SWORD_ELITE);
	/** Assumed: the knife's 50%, so one slash more on average. */
	private static final int EXPECTED_SLASHES_WITHOUT_SWORD = 2;
	/** Assumed, as Shortest Path's 4-tick web row includes the two steps through. */
	private static final int SLASH_TICKS = 2;
	private static final int RETRY_HALF_TICKS = 2 * SLASH_TICKS * (EXPECTED_SLASHES_WITHOUT_SWORD - 1);

	private final WalkTable table;
	private final boolean[] gatePass;
	private final boolean[] setPass;
	/** Stored items a passing gate takes, such as a banked knife for a web; carried ones are preferred. */
	private final int[][] gateWithdraw;
	private final int[][] setWithdraw;
	private final int[] gateExtra;
	private final int[] setExtra;

	public WalkGates(WalkTable table, PlayerSnapshot player, AvailabilityEngine engine, long nowMinutes)
	{
		this.table = table;
		List<Requirements> gates = table.getGates();
		gatePass = new boolean[gates.size() + 1];
		gateWithdraw = new int[gates.size() + 1][];
		gateExtra = new int[gates.size() + 1];
		gatePass[0] = true;
		gateWithdraw[0] = NOTHING;
		for (int g = 0; g < gates.size(); g++)
		{
			CatalogRow asRow = CatalogRow.builder().name("gate " + (g + 1)).requirements(gates.get(g)).build();
			RowAvailability judged = engine.judge(asRow, player, nowMinutes);
			gatePass[g + 1] = judged.isAvailable();
			gateWithdraw[g + 1] = judged.isAvailable() ? storedIds(judged) : NOTHING;
			if (judged.isAvailable() && isSlash(gates.get(g)))
			{
				slashWith(g + 1, judged, player);
			}
		}
		setPass = new boolean[table.gateSetCount() + 1];
		setWithdraw = new int[table.gateSetCount() + 1][];
		setExtra = new int[table.gateSetCount() + 1];
		setPass[0] = true;
		setWithdraw[0] = NOTHING;
		for (int s = 1; s <= table.gateSetCount(); s++)
		{
			boolean pass = true;
			TreeSet<Integer> withdraw = new TreeSet<>();
			for (int gate : table.gateSet(s))
			{
				pass &= gatePass[gate];
				IntStream.of(gateWithdraw[gate]).forEach(withdraw::add);
				setExtra[s] += gateExtra[gate];
			}
			setPass[s] = pass;
			setWithdraw[s] = pass ? withdraw.stream().mapToInt(Integer::intValue).toArray() : NOTHING;
		}
	}

	/** {@link WalkTable#NO_PATH} when the failed gates block every way. */
	public int cost(int entry)
	{
		int set = walkedGateSet(entry);
		if (set == table.entryGateSet(entry))
		{
			return table.entryFull(entry) + setExtra[set];
		}
		return set == 0 ? table.entryOpen(entry) : table.entryAlternative(entry) + setExtra[set];
	}

	/** Sorted item ids to take from the bank for {@link #cost}'s walk. */
	public int[] withdraw(int entry)
	{
		return setWithdraw[walkedGateSet(entry)];
	}

	/** The gate set of the walk {@link #cost} takes: the full path's, else the alternative's, else 0 for the open one. */
	private int walkedGateSet(int entry)
	{
		int full = table.entryGateSet(entry);
		if (setPass[full])
		{
			return full;
		}
		int alternative = table.entryAlternativeGateSet(entry);
		return alternative != 0 && setPass[alternative] ? alternative : 0;
	}

	/** Each gate's stored items, sorted, once per distinct set. */
	public List<int[]> storedGateTools()
	{
		Map<String, int[]> tools = new LinkedHashMap<>();
		for (int g = 1; g < gateWithdraw.length; g++)
		{
			if (gateWithdraw[g].length > 0)
			{
				tools.putIfAbsent(Arrays.toString(gateWithdraw[g]), gateWithdraw[g]);
			}
		}
		return List.copyOf(tools.values());
	}

	public boolean[] rowOpen()
	{
		boolean[] open = new boolean[table.rowCount()];
		for (int r = 0; r < open.length; r++)
		{
			open[r] = gatePass[table.rowGate(r)];
		}
		return open;
	}

	private static boolean isSlash(Requirements gate)
	{
		return gate.getItems().stream().anyMatch(clause -> clause.getBranches().stream()
			.anyMatch(branch -> branch.getIds().stream().anyMatch(WILDERNESS_SWORDS::contains)));
	}

	/** A carried blade beats fetching the sword, which costs about what the retries do; a stored sword beats any stored blade. */
	private void slashWith(int gate, RowAvailability judged, PlayerSnapshot player)
	{
		boolean bySword = judged.getWitness().stream().anyMatch(use -> WILDERNESS_SWORDS.contains(use.getItemId()));
		if (bySword)
		{
			return;
		}
		int storedSword = gateWithdraw[gate].length == 0 ? -1 : storedSword(player);
		if (storedSword != -1)
		{
			gateWithdraw[gate] = new int[] {storedSword};
			return;
		}
		gateExtra[gate] = RETRY_HALF_TICKS;
	}

	private static int storedSword(PlayerSnapshot player)
	{
		for (int sword : WILDERNESS_SWORDS)
		{
			if (player.stored(sword) > 0)
			{
				return sword;
			}
		}
		return -1;
	}

	private static int[] storedIds(RowAvailability judged)
	{
		return judged.getWitness().stream()
			.filter(use -> use.getContainer().isStored())
			.mapToInt(ItemUse::getItemId).distinct().sorted().toArray();
	}
}
