package com.cluehelper.memory;

import java.lang.reflect.Method;
import java.util.*;
import lombok.Value;
import net.runelite.client.plugins.timetracking.farming.CropState;
import net.runelite.client.plugins.timetracking.farming.PatchImplementation;
import net.runelite.client.plugins.timetracking.farming.Produce;

/**
 * The allotment growth table {@code GrownPatches} reads, worked out from core's farming tracker, whose
 * {@code PatchImplementation.ALLOTMENT} reads a patch's varbit but is package-private. {@code DataBake} writes it into
 * {@code tables.tsv}, so a RuneLite update that adds a crop changes the baked table and {@code BakedDataTest} fails
 * until it is baked again.
 */
public final class AllotmentStages
{
	public static final String TABLE = "allotment stages left";
	/** An allotment's varbit is 8 bits wide. */
	private static final int VALUES = 256;

	private AllotmentStages()
	{
	}

	/**
	 * By varbit value while a crop is growing or diseased, the growing stages still to come before it is grown. Weeds
	 * are not a crop: they can be raked off.
	 */
	public static Map<Integer, Integer> fromCore()
	{
		Map<Integer, Integer> stagesLeft = new TreeMap<>();
		for (int value = 0; value < VALUES; value++)
		{
			if (!isGrowing(stage(value)))
			{
				continue;
			}
			int last = value;
			while (last + 1 < VALUES && isNext(stage(last), stage(last + 1)))
			{
				last++;
			}
			stagesLeft.put(value, last - value);
		}
		return stagesLeft;
	}

	/** The table's rows as {@code tables.tsv} holds them: table, varbit value, stages left. */
	public static List<String> rows()
	{
		List<String> rows = new ArrayList<>();
		fromCore().forEach((value, left) -> rows.add(TABLE + "\t" + value + "\t" + left));
		return rows;
	}

	private static boolean isGrowing(Stage stage)
	{
		return stage != null && stage.produce != Produce.WEEDS
			&& (stage.state == CropState.GROWING || stage.state == CropState.DISEASED);
	}

	private static boolean isNext(Stage stage, Stage next)
	{
		return isGrowing(next) && next.produce == stage.produce && next.state == stage.state && next.number == stage.number + 1;
	}

	private static Stage stage(int value)
	{
		try
		{
			Method forValue = PatchImplementation.class.getDeclaredMethod("forVarbitValue", int.class);
			forValue.setAccessible(true);
			Object state = forValue.invoke(PatchImplementation.ALLOTMENT, value);
			return state == null ? null : new Stage((Produce) field(state, "getProduce"), (CropState) field(state, "getCropState"),
				(int) field(state, "getStage"));
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("can't read core's allotment state for varbit value " + value, e);
		}
	}

	private static Object field(Object state, String getter) throws ReflectiveOperationException
	{
		Method method = state.getClass().getDeclaredMethod(getter);
		method.setAccessible(true);
		return method.invoke(state);
	}

	@Value
	private static class Stage
	{
		Produce produce;
		CropState state;
		int number;
	}
}
