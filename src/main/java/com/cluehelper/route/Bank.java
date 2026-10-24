package com.cluehelper.route;

import com.cluehelper.transport.Requirements;
import lombok.Value;

@Value
public class Bank
{
	private static final String KIND_DEPOSIT = "deposit";

	String name;
	String kind;
	Requirements requirements;

	public boolean isDepositOnly()
	{
		return KIND_DEPOSIT.equals(kind);
	}
}
