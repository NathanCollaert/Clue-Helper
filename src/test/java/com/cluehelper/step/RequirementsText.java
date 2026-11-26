package com.cluehelper.step;

/** A step's needs as one line of a golden file or a dump. */
public final class RequirementsText
{
	private RequirementsText()
	{
	}

	public static String describe(StepRequirements needs)
	{
		StringBuilder text = new StringBuilder();
		if (needs.getSpade() != SpadeNeed.NONE)
		{
			text.append("spade");
		}
		if (needs.needsLight())
		{
			text.append(text.length() > 0 ? ", " : "").append("light");
			if (needs.getFirePitVarbitId() != -1)
			{
				text.append(" (fire pit ").append(needs.getFirePitVarbitId()).append(')');
			}
		}
		int items = needs.getItems().size();
		if (items > 0)
		{
			text.append(text.length() > 0 ? ", " : "").append(items).append(" item requirement").append(items == 1 ? "" : "s");
		}
		return text.length() == 0 ? "none" : text.toString();
	}
}
