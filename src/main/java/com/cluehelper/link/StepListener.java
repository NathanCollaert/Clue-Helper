package com.cluehelper.link;

import com.cluehelper.step.Step;

/** Hears every step change, on the client thread. */
public interface StepListener
{
	/** @param step the current step, or null after {@link StepChange#CLEARED} */
	void onStepChange(StepChange change, Step step);
}
