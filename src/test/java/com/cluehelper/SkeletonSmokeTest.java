package com.cluehelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import org.junit.Test;

public class SkeletonSmokeTest
{
	@Test
	public void manifestNamesAPluginClassWithADescriptor() throws IOException, ClassNotFoundException
	{
		Properties manifest = new Properties();
		try (FileInputStream in = new FileInputStream("runelite-plugin.properties"))
		{
			manifest.load(in);
		}
		assertEquals("Clue Helper", manifest.getProperty("displayName"));
		assertEquals("standard", manifest.getProperty("build"));

		Class<?> plugin = Class.forName(manifest.getProperty("plugins"));
		assertTrue(Plugin.class.isAssignableFrom(plugin));
		assertNotNull(plugin.getAnnotation(PluginDescriptor.class));
		assertTrue(plugin.getName().startsWith("com.cluehelper."));
	}
}
