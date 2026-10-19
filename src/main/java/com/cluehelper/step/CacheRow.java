package com.cluehelper.step;

/** A Cluehelper* cache row a step maps to (B01). Never the step's identity. */
public final class CacheRow
{
	private int id;
	private String name;
	private Branch when;

	public int getId()
	{
		return id;
	}

	public String getName()
	{
		return name;
	}

	/** The varbit branch this row belongs to, or null when the step has one row for every branch. */
	public Branch getWhen()
	{
		return when;
	}

	@Override
	public String toString()
	{
		return id + " " + name + (when == null ? "" : " when " + when);
	}

	/** Holds when the varbit equals {@code equals}, or differs from {@code notEquals}. */
	public static final class Branch
	{
		private int varbit;
		private Integer equals;
		private Integer notEquals;

		public int getVarbit()
		{
			return varbit;
		}

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
