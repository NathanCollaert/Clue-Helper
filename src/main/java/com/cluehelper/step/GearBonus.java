package com.cluehelper.step;

/** What a talk's worn gear adds up: an equipment bonus, or a count of items aligned to a god. */
public enum GearBonus
{
	MELEE_STRENGTH,
	MAGIC_ATTACK,
	RANGED_ATTACK,
	GUTHIX_ITEMS,
	ZAMORAK_ITEMS,
	/** Any armour or weapon, one piece each. */
	ANY_EQUIPMENT,
	/** Minus the prayer bonus: a total above zero is a negative prayer bonus (Abbot Langley's anger). */
	NEGATIVE_PRAYER
}
