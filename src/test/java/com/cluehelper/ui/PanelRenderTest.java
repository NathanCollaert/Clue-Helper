package com.cluehelper.ui;

import static org.junit.Assert.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.laf.RuneLiteLAF;
import org.junit.Test;

/**
 * Lays the panel out offscreen at the sidebar's width in every state, checks nothing runs past
 * the edge, and writes build/panel/&lt;state&gt;.png (2x) to look at.
 */
public class PanelRenderTest
{
	private static final int WIDTH = PluginPanel.PANEL_WIDTH;
	private static final int HEIGHT = 300;
	private static final File OUT = new File("build/panel");

	@Test
	public void everyStateFitsTheSidebar() throws Exception
	{
		OUT.mkdirs();
		SwingUtilities.invokeAndWait(() ->
		{
			RuneLiteLAF.setup();
			for (PanelState state : PanelState.values())
			{
				ClueHelperPanel panel = new ClueHelperPanel();
				panel.display(state);
				panel.setSize(WIDTH, HEIGHT);
				for (int pass = 0; pass < 3; pass++)
				{
					layout(panel);
				}
				for (JComponent text : texts(panel))
				{
					Rectangle r = SwingUtilities.convertRectangle(text.getParent(), text.getBounds(), panel);
					assertTrue(state + ": text inside the content column: " + describe(text) + " " + r,
						r.x >= Theme.SIDE && r.x + r.width <= WIDTH - Theme.SIDE);
					assertTrue(state + ": text has a height: " + describe(text), r.height > 0);
					if (text instanceof TextBlock)
					{
						TextBlock block = (TextBlock) text;
						for (String line : block.getLines())
						{
							assertTrue(state + ": line fits its block: '" + line + "'",
								block.getFontMetrics(block.getFont()).stringWidth(line) <= r.width);
						}
						assertTrue(state + ": text block shows its full height", r.height >= block.getPreferredSize().height);
					}
				}
				render(panel, state.name().toLowerCase(Locale.ROOT));
			}
		});
		writeLogo();
	}

	private static void render(Component panel, String name)
	{
		BufferedImage image = new BufferedImage(panel.getWidth() * 2, panel.getHeight() * 2, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.scale(2, 2);
		RepaintManager rm = RepaintManager.currentManager(panel);
		boolean buffered = rm.isDoubleBufferingEnabled();
		rm.setDoubleBufferingEnabled(false);
		try
		{
			panel.paint(g);
			ImageIO.write(image, "png", new File(OUT, name + ".png"));
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
		finally
		{
			rm.setDoubleBufferingEnabled(buffered);
			g.dispose();
		}
	}

	private static void writeLogo() throws IOException
	{
		BufferedImage logo = Icons.logo();
		BufferedImage big = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = big.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(logo, 0, 0, 64, 64, null);
		g.dispose();
		ImageIO.write(big, "png", new File(OUT, "logo-4x.png"));
	}

	private static void layout(Component c)
	{
		if (c instanceof Container)
		{
			((Container) c).doLayout();
			for (Component child : ((Container) c).getComponents())
			{
				layout(child);
			}
		}
	}

	private static List<JComponent> texts(Container root)
	{
		List<JComponent> out = new ArrayList<>();
		for (Component c : root.getComponents())
		{
			if (!c.isVisible())
			{
				continue;
			}
			if (c instanceof JLabel || c instanceof TextBlock)
			{
				out.add((JComponent) c);
			}
			if (c instanceof Container)
			{
				out.addAll(texts((Container) c));
			}
		}
		return out;
	}

	private static String describe(JComponent text)
	{
		return "'" + (text instanceof JLabel ? ((JLabel) text).getText() : ((TextBlock) text).getText()) + "'";
	}
}
