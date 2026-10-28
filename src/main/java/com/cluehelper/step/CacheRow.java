package com.cluehelper.step;

import lombok.Getter;

@Getter
public final class CacheRow
{
	private int id;
	private String name;
	/** Null when the step has one row for every branch. */
	private Branch when;

	@Override
	public String toString()
	{
		return id + " " + name + (when == null ? "" : " when " + when);
	}

	public static final class Branch
	{
		private int varbit;
		private Integer equals;
		private Integer notEquals;

		public boolean holdsFor(int value)
		{
			return equals != null ? value == equals : notEquals == null || value != notEquals;
		}

		@Override
		public String toString()
		{
			return "varbit " + varbit + (equals != null ? " = " + equals : " != " + notEquals);
		}
	}
}
