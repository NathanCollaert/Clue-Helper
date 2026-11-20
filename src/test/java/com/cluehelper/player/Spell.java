package com.cluehelper.player;

import lombok.Value;

@Value
public class Spell
{
	Book book;
	String name;
	/** The cache object that carries the spell's params. */
	int obj;
}
