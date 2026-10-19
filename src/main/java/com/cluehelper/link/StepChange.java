package com.cluehelper.link;

public enum StepChange
{
	/** A different key from the last step, or any step after a read without one. */
	NEW,
	/** Same key, no gap, different targets: three-step progress or hot/cold narrowing. */
	UPDATED,
	/** Core has no clue any more. */
	CLEARED,
	UNCHANGED
}
