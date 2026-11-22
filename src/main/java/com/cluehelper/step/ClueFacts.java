package com.cluehelper.step;

import lombok.Value;

/** A clue as the step data knows it, read from core's public getters by {@link CoreClueReader#facts}. */
@Value
public class ClueFacts
{
	String type;
	/** See {@link StepKey}. */
	String key;
	/** The step's text as the guide shows it. */
	String text;
	/** A hot/cold clue's tier name; null for other types. */
	String tier;
}
