package com.cluehelper.hotcold;

/** "Than last time": the exact Chebyshev distance against the step's previous reading (B18, 93 of 93). */
public enum Comparison
{
	WARMER("warmer"),
	SAME("same"),
	COLDER("colder");

	private final String text;

	Comparison(String text)
	{
		this.text = text;
	}

	public String getText()
	{
		return text;
	}

	public static Comparison of(int distance, int previousDistance)
	{
		return distance < previousDistance ? WARMER : distance == previousDistance ? SAME : COLDER;
	}
}
