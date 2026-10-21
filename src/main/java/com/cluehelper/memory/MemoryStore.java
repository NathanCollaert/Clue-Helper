package com.cluehelper.memory;

import com.google.gson.Gson;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/**
 * Nothing is read or written until the client has a RuneScape profile, which needs a login; before that every slot
 * holds its fresh value in memory only.
 */
@Singleton
public class MemoryStore
{
	private final ProfileConfig profile;
	private final Gson gson;
	private final List<MemorySlot<?>> slots = new CopyOnWriteArrayList<>();

	@Inject
	MemoryStore(ConfigManager configManager, Gson gson)
	{
		this(new RuneLiteProfileConfig(configManager), gson);
	}

	MemoryStore(ProfileConfig profile, Gson gson)
	{
		this.profile = profile;
		this.gson = gson;
	}

	/**
	 * Create each slot once and keep it: the store holds on to every slot it makes.
	 *
	 * @param fresh the value of a profile that has none stored, or whose stored JSON is unreadable
	 */
	public <T> MemorySlot<T> slot(String key, Class<T> type, Supplier<T> fresh)
	{
		MemorySlot<T> slot = new MemorySlot<>(key, type, fresh, profile, gson);
		slots.add(slot);
		return slot;
	}

	public boolean hasProfile()
	{
		return profile.profileKey() != null;
	}

	/** Call on {@code RuneScapeProfileChanged}. */
	public void reload()
	{
		for (MemorySlot<?> slot : slots)
		{
			slot.forget();
		}
	}
}
