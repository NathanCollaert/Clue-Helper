package com.cluehelper.memory;

interface ProfileConfig
{
	/** Null until the client has a RuneScape profile, which needs a login. */
	String profileKey();

	String get(String key);

	void set(String key, String value);

	void unset(String key);
}
