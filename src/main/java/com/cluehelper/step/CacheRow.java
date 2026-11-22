package com.cluehelper.step;

import lombok.Getter;

@Getter
public final class CacheRow
{
	int id;
	String name;
	/** Null when the step has one row for every branch. */
	Branch when;

	@Override
	public String toString()
	{
		return id + " " + name + (when == null ? "" : " when " + when);
	}

	public static final class Branch
	{
		int varbit;
		Integer equals;
		Integer notEquals;

		@Override
		public String toString()
		{
			return "varbit " + varbit + (equals != null ? " = " + equals : " != " + notEquals);
		}
	}
}
