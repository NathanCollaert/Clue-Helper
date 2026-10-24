package com.cluehelper.transport;

import lombok.Value;

@Value
public class VarCheck
{
	public enum Kind
	{
		VARBIT, VARP
	}

	public enum Op
	{
		EQUAL("="), GREATER(">"), SMALLER("<"), BIT_SET("&"),
		/** The var holds the epoch minute a cooldown started; it passes once more than {@code value} minutes have gone by. */
		COOLDOWN_MINUTES("@");

		private final String code;

		Op(String code)
		{
			this.code = code;
		}

		static Op of(char code)
		{
			for (Op op : values())
			{
				if (op.code.charAt(0) == code)
				{
					return op;
				}
			}
			throw new IllegalArgumentException("no var operator " + code);
		}
	}

	Kind kind;
	int id;
	Op op;
	int value;

	public boolean test(int current, long nowMinutes)
	{
		switch (op)
		{
			case EQUAL:
				return current == value;
			case GREATER:
				return current > value;
			case SMALLER:
				return current < value;
			case BIT_SET:
				return (current & value) != 0;
			case COOLDOWN_MINUTES:
				return nowMinutes - current > value;
			default:
				throw new IllegalArgumentException("unhandled op " + op);
		}
	}

	/** The first epoch minute a failing cooldown passes; {@link Long#MAX_VALUE} for every other clause. */
	public long passesFrom(int current)
	{
		return op == Op.COOLDOWN_MINUTES ? (long) current + value + 1 : Long.MAX_VALUE;
	}

	String whyNot(int current, long nowMinutes)
	{
		if (op == Op.COOLDOWN_MINUTES)
		{
			return "on cooldown, " + (passesFrom(current) - nowMinutes) + " min left";
		}
		return describe() + " (is " + current + ")";
	}

	public String describe()
	{
		return (kind == Kind.VARBIT ? "varbit " : "varp ") + id + " " + op.code + " " + value;
	}
}
