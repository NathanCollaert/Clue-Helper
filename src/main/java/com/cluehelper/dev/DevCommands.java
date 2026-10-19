package com.cluehelper.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/** The {@code ::ch-*} chat commands of developer mode. */
public class DevCommands
{
	static final String PREFIX = "ch-";

	private final Map<String, Command> commands = new TreeMap<>();
	private final Consumer<String> reply;

	/** @param reply shows a line to the developer */
	public DevCommands(Consumer<String> reply)
	{
		this.reply = reply;
		register("help", "list these commands", args -> help());
	}

	/** @param name the command without its "ch-" prefix */
	public synchronized void register(String name, String usage, Consumer<String[]> action)
	{
		commands.put(name.toLowerCase(Locale.ROOT), new Command(usage, action));
	}

	/**
	 * @param command as typed after "::"
	 * @return whether the command was one of ours, known or not
	 */
	public boolean dispatch(String command, String[] args)
	{
		String typed = command.toLowerCase(Locale.ROOT);
		if (!typed.startsWith(PREFIX))
		{
			return false;
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
		return true;
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
