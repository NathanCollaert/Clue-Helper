package com.cluehelper.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

public class DevCommands
{
	static final String PREFIX = "ch-";

	private final Map<String, Command> commands = new TreeMap<>();
	private final Consumer<String> reply;

	public DevCommands(Consumer<String> reply)
	{
		this.reply = reply;
		register("help", "list these commands", args -> help());
	}

	public synchronized void register(String nameWithoutPrefix, String usage, Consumer<String[]> action)
	{
		commands.put(nameWithoutPrefix.toLowerCase(Locale.ROOT), new Command(usage, action));
	}

	/** @param command as typed after "::" */
	public void dispatch(String command, String[] args)
	{
		String typed = command.toLowerCase(Locale.ROOT);
		if (!typed.startsWith(PREFIX))
		{
			return;
		}
		Command found;
		synchronized (this)
		{
			found = commands.get(typed.substring(PREFIX.length()));
		}
		if (found == null)
		{
			reply.accept("Unknown command ::" + typed + ", try ::ch-help");
		}
		else
		{
			found.action.accept(args);
		}
	}

	synchronized List<String> lines()
	{
		List<String> lines = new ArrayList<>();
		commands.forEach((name, c) -> lines.add("::" + PREFIX + name + " " + c.usage));
		return lines;
	}

	private void help()
	{
		for (String line : lines())
		{
			reply.accept(line);
		}
	}

	private static final class Command
	{
		final String usage;
		final Consumer<String[]> action;

		Command(String usage, Consumer<String[]> action)
		{
			this.usage = usage;
			this.action = action;
		}
	}
}
