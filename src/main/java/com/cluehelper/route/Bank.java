package com.cluehelper.route;

import com.cluehelper.transport.Requirements;
import lombok.Value;

/** One bank of {@code walk/banks.tsv}, at a walk-table bank node (B15). */
@Value
public class Bank
{
	public static final String KIND_DEPOSIT = "deposit";

	String name;
	/** {@code bank}, or {@link #KIND_DEPOSIT} for a deposit box, which never gives the banked state. */
	String kind;
	/** Members and gates (quest, skill, var) as catalog requirements. */
	Requirements requirements;
	boolean groupStorage;

	public boolean isDepositOnly()
	{
		return KIND_DEPOSIT.equals(kind);
	}
}
