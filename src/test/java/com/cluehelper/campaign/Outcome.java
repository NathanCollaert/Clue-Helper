package com.cluehelper.campaign;

import com.cluehelper.guide.SubStep;
import com.cluehelper.route.Plan;
import com.cluehelper.route.RouteText;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.Value;

/** What a step's first plan showed: the route as golden lines, and the guide's sub-steps. */
@Value
public class Outcome
{
	/** The tick counts in a route line: {@code 20t}, {@code (rank 21)}, {@code walk 14}, {@code walk south 3}. */
	private static final Pattern TICKS = Pattern.compile("\\b\\d+t\\b|(?<=\\(rank )\\d+(?=\\))|(?<=walk )\\d+|(?<=walk south )\\d+");

	List<String> route;
	List<String> guide;

	public enum Change
	{
		NONE,
		/** The same route and guide; only tick counts moved. */
		TICKS,
		ROUTE
	}

	static Outcome of(Plan plan, List<SubStep> guide)
	{
		List<String> route = new ArrayList<>();
		route.add("best " + RouteText.describe(plan.getBest()));
		if (plan.getNote() != null)
		{
			route.add("note " + plan.getNote());
		}
		plan.getRequirementNotes().forEach(note -> route.add("needs " + note));
		return new Outcome(List.copyOf(route), guideLines(guide));
	}

	public static List<String> guideLines(List<SubStep> guide)
	{
		return guide.stream().map(step -> step.getKind() + ": " + step.getText() + (step.getDetail() == null ? "" : " (" + step.getDetail() + ")"))
			.collect(Collectors.toList());
	}

	/** How {@code now} differs from this, the signed-off outcome. */
	Change changeTo(Outcome now)
	{
		if (equals(now))
		{
			return Change.NONE;
		}
		return guide.equals(now.guide) && withoutTicks(route).equals(withoutTicks(now.route)) ? Change.TICKS : Change.ROUTE;
	}

	private static List<String> withoutTicks(List<String> lines)
	{
		return lines.stream().map(line -> TICKS.matcher(line).replaceAll("#")).collect(Collectors.toList());
	}
}
