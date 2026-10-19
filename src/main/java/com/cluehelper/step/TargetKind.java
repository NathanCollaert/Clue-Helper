package com.cluehelper.step;

/** How a step's targets combine (PLAN §5.4). */
public enum TargetKind
{
	/** Any one of the tiles will do. */
	ANY_OF,
	/** An NPC that walks around inside its regions. */
	AREA,
	/** Places visited in turn: three-step parts, a skill challenge and its hand-in. */
	SEQUENCE,
	/** Hot/cold: the spots still possible, narrowed by each probe. */
	PROBES
}
