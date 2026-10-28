package com.cluehelper.hotcold;

import lombok.*;

/** "Than last time": the exact Chebyshev distance against the step's previous reading (exact in 93 of 93 recorded readings). */
@RequiredArgsConstructor
public enum Comparison
{
	WARMER("warmer"),
	SAME("same"),
	COLDER("colder");

	@Getter
	private final String text;

	public static Comparison of(int distance, int previousDistance)
	{
		return distance < previousDistance ? WARMER : distance == previousDistance ? SAME : COLDER;
	}
}
