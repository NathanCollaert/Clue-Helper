package com.cluehelper.memory;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;

/** A profile config in memory, for tests of anything that keeps state in a {@link MemoryStore}. */
public final class FakeProfileConfig implements ProfileConfig
{
	private final Map<String, Map<String, String>> profiles = new HashMap<>();
	private String profileKey;

	public MemoryStore newStore()
	{
		return new MemoryStore(this, new Gson());
	}

	public void logIn(String profile)
	{
		profileKey = profile;
	}

	public void logOut()
	{
		profileKey = null;
	}

	public Map<String, String> values(String profile)
	{
		return profiles.computeIfAbsent(profile, p -> new HashMap<>());
	}

	@Override
	public String profileKey()
	{
		return profileKey;
	}

	@Override
	public String get(String key)
	{
		return profileKey == null ? null : values(profileKey).get(key);
	}

	@Override
	public void set(String key, String value)
	{
		if (profileKey == null)
		{
			throw new IllegalStateException("write without a profile: " + key);
		}
		values(profileKey).put(key, value);
	}

	@Override
	public void unset(String key)
	{
		if (profileKey != null)
		{
			values(profileKey).remove(key);
		}
	}
}
