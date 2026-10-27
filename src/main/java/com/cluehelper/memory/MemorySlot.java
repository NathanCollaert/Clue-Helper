package com.cluehelper.memory;

import com.google.gson.*;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/** Safe to use from any thread. */
@Slf4j
public final class MemorySlot<T>
{
	private final String key;
	private final Class<T> type;
	private final Supplier<T> fresh;
	private final ProfileConfig profile;
	private final Gson gson;

	private boolean loaded;
	private String loadedProfile;
	private T value;

	MemorySlot(String key, Class<T> type, Supplier<T> fresh, ProfileConfig profile, Gson gson)
	{
		this.key = key;
		this.type = type;
		this.fresh = fresh;
		this.profile = profile;
		this.gson = gson;
	}

	public synchronized T get()
	{
		String currentProfile = profile.profileKey();
		if (!loaded || !Objects.equals(currentProfile, loadedProfile))
		{
			value = read(currentProfile);
			loadedProfile = currentProfile;
			loaded = true;
		}
		return value;
	}

	/** Without a profile the value is kept in memory only, and dropped when a profile loads. */
	public synchronized void set(T newValue)
	{
		String currentProfile = profile.profileKey();
		value = newValue;
		loadedProfile = currentProfile;
		loaded = true;
		if (currentProfile == null)
		{
			log.debug("{} kept in memory only: no RuneScape profile yet", key);
			return;
		}
		profile.set(key, gson.toJson(newValue));
	}

	public synchronized void clear()
	{
		forget();
		if (profile.profileKey() != null)
		{
			profile.unset(key);
		}
	}

	synchronized void forget()
	{
		loaded = false;
		loadedProfile = null;
		value = null;
	}

	private T read(String currentProfile)
	{
		if (currentProfile == null)
		{
			return fresh.get();
		}
		String json = profile.get(key);
		if (json == null)
		{
			return fresh.get();
		}
		try
		{
			T parsed = gson.fromJson(json, type);
			return parsed == null ? fresh.get() : parsed;
		}
		catch (JsonParseException e)
		{
			log.warn("unreadable {} in this profile, starting fresh", key, e);
			return fresh.get();
		}
	}
}
