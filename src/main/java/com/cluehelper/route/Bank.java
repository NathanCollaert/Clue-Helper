package com.cluehelper.route;

import com.cluehelper.transport.Requirements;
import lombok.Value;

@Value
public class Bank
{
	public static final String KIND_DEPOSIT = "deposit";

	String name;
	String kind;
	Requirements requirements;
	boolean groupStorage;

	public boolean isDepositOnly()
	{
		return KIND_DEPOSIT.equals(kind);
	}
}
