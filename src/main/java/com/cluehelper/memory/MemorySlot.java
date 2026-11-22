package com.cluehelper.memory;

import com.google.gson.*;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

/** Safe to use from any thread. */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class MemorySlot<T>
{
	final String key;
	final Class<T> type;
	final Supplier<T> fresh;
	final ProfileConfig profile;
	final Gson gson;
	final Runnable onSet;

	boolean loaded;
	String loadedProfile;
	T value;
	boolean held;

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
		onSet.run();
		if (currentProfile == null)
		{
			log.debug("{} kept in memory only: no RuneScape profile yet", key);
			return;
		}
		profile.set(key, gson.toJson(newValue));
	}

	/** Kept in memory until {@link #saveHeld()}: for a value that changes too often to write every time. */
	public synchronized void hold(T newValue)
	{
		value = newValue;
		loadedProfile = profile.profileKey();
		loaded = true;
		held = true;
	}

	public synchronized void saveHeld()
	{
		if (held)
		{
			held = false;
			set(value);
		}
	}

	synchronized void forget()
	{
		held = false;
		loaded = false;
		loadedProfile = null;
		value = null;
	}

	private T read(String currentProfile)
	{
		String json = currentProfile == null ? null : profile.get(key);
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
