package com.cluehelper.dev;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(name = "UI Watchdog", description = "Dev only: logs UI-thread freezes with a stack trace")
public class UiWatchdogPlugin extends Plugin
{
	/** Longer than this is a visible freeze. */
	private static final long STALL_MS = 250;

	private volatile boolean watching;

	@Override
	protected void startUp()
	{
		watching = true;
		Thread t = new Thread(this::watch, "ui-watchdog");
		t.setDaemon(true);
		t.start();
	}

	@Override
	protected void shutDown()
	{
		watching = false;
	}

	private void watch()
	{
		while (watching)
		{
			AtomicBoolean done = new AtomicBoolean();
			long sent = System.nanoTime();
			SwingUtilities.invokeLater(() -> done.set(true));
			boolean reported = false;
			while (!done.get() && watching)
			{
				sleep(20);
				if (!reported && (System.nanoTime() - sent) / 1_000_000 > STALL_MS)
				{
					reported = true;
					log.warn("[CH-WATCHDOG] UI thread stalled >{}ms; it is in:{}", STALL_MS, swingStack());
				}
			}
			if (reported)
			{
				log.warn("[CH-WATCHDOG] UI thread stall ended after {} ms", (System.nanoTime() - sent) / 1_000_000);
			}
			sleep(100);
		}
	}

	private static String swingStack()
	{
		StringBuilder trace = new StringBuilder();
		for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet())
		{
			if (e.getKey().getName().startsWith("AWT-EventQueue"))
			{
				for (int i = 0; i < Math.min(30, e.getValue().length); i++)
				{
					trace.append(System.lineSeparator()).append("\t    at ").append(e.getValue()[i]);
				}
			}
		}
		return trace.toString();
	}

	private static void sleep(long ms)
	{
		try
		{
			Thread.sleep(ms);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}
}
