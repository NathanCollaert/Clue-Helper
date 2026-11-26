package com.cluehelper.player;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Dev client only: items counted as carried on top of the real ones. The plugin has no seam for it, so this swaps the
 * model's snapshot for one with the items added, as a rebuild would, and hands it to the model's listeners. Call it
 * after the plugin's tick handler; a rebuild drops the items until the next call.
 */
public final class FakeCarried
{
	private FakeCarried()
	{
	}

	/** Client thread. */
	@SuppressWarnings("unchecked")
	public static void onto(PlayerModel model, Map<Integer, Integer> items)
	{
		PlayerSnapshot now = model.getSnapshot();
		if (now == null || items.isEmpty() || items.keySet().stream().allMatch(now.getInventory()::containsKey))
		{
			return;
		}
		Map<Integer, Integer> inventory = new HashMap<>(now.getInventory());
		items.forEach((id, quantity) -> inventory.merge(id, quantity, Integer::sum));
		long version = (long) field(model, "version") + 1;
		PlayerSnapshot faked = now.toBuilder().inventory(inventory).version(version).build();
		setField(model, "version", version);
		setField(model, "snapshot", faked);
		((List<Consumer<PlayerSnapshot>>) field(model, "listeners")).forEach(listener -> listener.accept(faked));
	}

	/** Client thread. The next tick builds the snapshot again from the real inventory. */
	public static void forget(PlayerModel model)
	{
		setField(model, "dirty", true);
	}

	private static Object field(PlayerModel model, String name)
	{
		try
		{
			return accessible(name).get(model);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("can't read PlayerModel." + name, e);
		}
	}

	private static void setField(PlayerModel model, String name, Object value)
	{
		try
		{
			accessible(name).set(model, value);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("can't set PlayerModel." + name, e);
		}
	}

	private static Field accessible(String name) throws NoSuchFieldException
	{
		Field field = PlayerModel.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}
}
