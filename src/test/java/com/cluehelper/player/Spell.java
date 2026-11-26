package com.cluehelper.player;

import lombok.Value;

@Value
public class Spell
{
	Book book;
	String name;
	/** The cache object that carries the spell's params. */
	int obj;
	/** Its button in the spellbook, from the object's params; -1 when the cache names none. */
	int widget;
}
