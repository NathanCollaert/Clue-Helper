package com.cluehelper.hotcold;

/** "Than last time": the exact Chebyshev distance against the step's previous reading (exact in 93 of 93 recorded readings). */
public enum Comparison
{
	WARMER,
	SAME,
	COLDER;

	public static Comparison of(int distance, int previousDistance)
	{
		return distance < previousDistance ? WARMER : distance == previousDistance ? SAME : COLDER;
	}
}
