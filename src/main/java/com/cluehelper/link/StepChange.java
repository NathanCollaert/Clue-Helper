package com.cluehelper.link;

public enum StepChange
{
	NEW,
	/** Same key, no gap, different targets: three-step progress or hot/cold narrowing. */
	UPDATED,
	CLEARED,
	UNCHANGED
}
