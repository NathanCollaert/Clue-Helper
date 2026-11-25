package com.cluehelper;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum SaveConsumables
{
	OFF("Off"),
	FLAT_FEE("Flat fee"),
	SCARCITY("Scarcity");

	final String label;

	@Override
	public String toString()
	{
		return label;
	}
}
