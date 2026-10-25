package com.cluehelper.campaign;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.runelite.api.Client;

/**
 * A client that answers from a tape. Recording, it passes every call on to the real client and keeps the answers a
 * replay can give back: numbers, texts, enums and their arrays, also when they come through a definition (an item's
 * name). Replaying, it gives those answers back and throws on any call that isn't on the tape, so a replay that needs
 * more than was recorded fails instead of guessing.
 */
public final class ClientTape
{
	private static final String NULL = "<null>";

	private final Client real;
	private final Map<String, Object> answers;
	private final Set<String> unreplayable = new TreeSet<>();
	private final Client client;

	private ClientTape(Client real, Map<String, Object> answers)
	{
		this.real = real;
		this.answers = answers;
		this.client = (Client) proxy(Client.class, real, "");
	}

	/** Client thread: the calls reach the real client. */
	public static ClientTape recording(Client real)
	{
		return new ClientTape(real, new TreeMap<>());
	}

	public static ClientTape replaying(Map<String, Object> answers)
	{
		return new ClientTape(null, new TreeMap<>(answers));
	}

	public Client client()
	{
		return client;
	}

	public Map<String, Object> answers()
	{
		return new TreeMap<>(answers);
	}

	/** Calls a replay can't answer: they returned something other than a value or a definition. */
	public Set<String> unreplayable()
	{
		return Set.copyOf(unreplayable);
	}

	private Object proxy(Class<?> type, Object target, String prefix)
	{
		return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
			(proxy, method, args) -> answer(proxy, method, args, target, prefix));
	}

	private Object answer(Object proxy, Method method, Object[] args, Object target, String prefix) throws Throwable
	{
		switch (method.getName())
		{
			case "toString":
				return "ClientTape";
			case "hashCode":
				return System.identityHashCode(proxy);
			case "equals":
				return proxy == args[0];
		}
		String key = prefix + method.getName() + "(" + arguments(args) + ")";
		Class<?> type = method.getReturnType();
		if (isDefinition(type))
		{
			return definition(type, method, args, target, key);
		}
		if (!isValue(type))
		{
			return unanswerable(method, args, target, key);
		}
		if (real == null)
		{
			if (!answers.containsKey(key))
			{
				throw new IllegalStateException("not on the tape: " + key);
			}
			return fromTape(answers.get(key), type);
		}
		Object value = invoke(method, target, args);
		answers.put(key, toTape(value));
		return value;
	}

	private Object definition(Class<?> type, Method method, Object[] args, Object target, String key) throws Throwable
	{
		if (real == null)
		{
			return NULL.equals(answers.get(key)) ? null : proxy(type, null, key + ".");
		}
		Object definition = invoke(method, target, args);
		if (definition == null)
		{
			answers.put(key, NULL);
			return null;
		}
		return proxy(type, definition, key + ".");
	}

	private Object unanswerable(Method method, Object[] args, Object target, String key) throws Throwable
	{
		if (real == null)
		{
			if (method.getReturnType() == void.class)
			{
				return null;
			}
			throw new IllegalStateException("not on the tape: " + key);
		}
		unreplayable.add(key);
		return invoke(method, target, args);
	}

	private static Object invoke(Method method, Object target, Object[] args) throws Throwable
	{
		try
		{
			return method.invoke(target, args);
		}
		catch (InvocationTargetException e)
		{
			throw e.getCause();
		}
	}

	/** ItemComposition, NPCComposition, ObjectComposition: their answers go on the tape under the call that gave them. */
	private static boolean isDefinition(Class<?> type)
	{
		return type.isInterface() && type.getSimpleName().endsWith("Composition");
	}

	private static boolean isValue(Class<?> type)
	{
		return type.isPrimitive() && type != void.class || type == String.class || type.isEnum() || Number.class.isAssignableFrom(type)
			|| type == Boolean.class || type == int[].class || type == String[].class;
	}

	private static String arguments(Object[] args)
	{
		if (args == null)
		{
			return "";
		}
		return Arrays.stream(args).map(arg -> arg instanceof Enum ? ((Enum<?>) arg).name()
			: arg instanceof int[] ? Arrays.toString((int[]) arg) : String.valueOf(arg)).collect(Collectors.joining(","));
	}

	private static Object toTape(Object value)
	{
		if (value == null)
		{
			return NULL;
		}
		if (value instanceof Enum)
		{
			return ((Enum<?>) value).name();
		}
		if (value instanceof int[])
		{
			return Arrays.stream((int[]) value).boxed().collect(Collectors.toList());
		}
		if (value instanceof String[])
		{
			return Arrays.asList((String[]) value);
		}
		return value;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object fromTape(Object value, Class<?> type)
	{
		if (NULL.equals(value) && type != String.class)
		{
			return null;
		}
		if (type == int.class || type == Integer.class)
		{
			return ((Number) value).intValue();
		}
		if (type == long.class || type == Long.class)
		{
			return ((Number) value).longValue();
		}
		if (type == double.class || type == Double.class)
		{
			return ((Number) value).doubleValue();
		}
		if (type == float.class || type == Float.class)
		{
			return ((Number) value).floatValue();
		}
		if (type == short.class || type == Short.class)
		{
			return ((Number) value).shortValue();
		}
		if (type == byte.class || type == Byte.class)
		{
			return ((Number) value).byteValue();
		}
		if (type.isEnum())
		{
			return Enum.valueOf((Class<Enum>) type, (String) value);
		}
		if (type == int[].class)
		{
			return ((List<Number>) value).stream().mapToInt(Number::intValue).toArray();
		}
		if (type == String[].class)
		{
			return ((List<String>) value).toArray(new String[0]);
		}
		return NULL.equals(value) ? null : value;
	}
}
