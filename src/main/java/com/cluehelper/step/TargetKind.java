package com.cluehelper.step;

public enum TargetKind
{
	ANY_OF,
	/** An NPC that walks around inside its regions. */
	AREA,
	/** Places visited in turn: three-step parts, a skill challenge and its hand-in. */
	SEQUENCE,
	/** Hot/cold: the spots still possible, narrowed by each probe. */
	PROBES
}
