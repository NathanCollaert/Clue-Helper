package com.cluehelper.ui;

import static org.junit.Assert.assertTrue;

import com.cluehelper.route.Plan;
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

public class PanelRenderTest
{
	private static final int WIDTH = PluginPanel.PANEL_WIDTH;
	private static final int HEIGHT = 300;
	private static final int ROUTES_HEIGHT = 560;
	private static final int WHY_HEIGHT = 1000;
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
				check(panel, state.name().toLowerCase(Locale.ROOT), HEIGHT);
			}
			ClueHelperPanel routes = new ClueHelperPanel();
			routes.show(threeRoutes());
			check(routes, "routes-3", ROUTES_HEIGHT);
			routes.toggleWhy();
			check(routes, "routes-why", WHY_HEIGHT);
		});
		writeLogo();
	}

	/** Long names everywhere, and more transports than "Show why" lists. */
	private static PanelModel threeRoutes()
	{
		List<Plan.Option> why = new ArrayList<>();
		for (int i = 0; i < ClueHelperPanel.WHY_ROWS + 5; i++)
		{
			why.add(new Plan.Option(i % 3 == 0 ? "Fairy ring DIP: Other Realms: Abyssal Area" : "Achievement diary cape: 6. Pirate Jackie the Fruit",
				40 + 7 * i, i % 3 == 0));
		}
		return PanelModel.builder()
			.state(PanelState.ROUTES)
			.now("Use Max cape: Fishing Teleports: Otto's Grotto")
			.nowDetail("Right-click the cape: Spellbook")
			.next("Walk to the clue")
			.notes(List.of("Wilderness target", "You're on Arceuus; F2P worlds can't change book"))
			.routes(List.of(
				new PanelModel.RouteCard("Best", 133, "Max cape → Ancient → Spellbook Swap → Annakarl Teleport → Fairy ring DIP: Other Realms: Abyssal Area → walk 41",
					"leaves you on Ancient · charges unknown · Wilderness", true),
				new PanelModel.RouteCard("Under the margin · -3", 130, "Clan Wars Minigame Teleport → walk south 21 → walk 162", "", false),
				new PanelModel.RouteCard("Also · +29", 162, "Last Man Standing Minigame Teleport → walk 162", "not everything read yet", false)))
			.why(why)
			.build();
	}

	private static void check(ClueHelperPanel panel, String name, int height)
	{
		panel.setSize(WIDTH, height);
		for (int pass = 0; pass < 3; pass++)
		{
			layout(panel);
		}
		for (JComponent text : texts(panel))
		{
			Rectangle r = SwingUtilities.convertRectangle(text.getParent(), text.getBounds(), panel);
			assertTrue(name + ": text inside the content column: " + describe(text) + " " + r,
				r.x >= Theme.SIDE && r.x + r.width <= WIDTH - Theme.SIDE);
			assertTrue(name + ": text has a height: " + describe(text), r.height > 0);
			if (text instanceof TextBlock)
			{
				TextBlock block = (TextBlock) text;
				for (String line : block.getLines())
				{
					assertTrue(name + ": line fits its block: '" + line + "'",
						block.getFontMetrics(block.getFont()).stringWidth(line) <= r.width);
				}
				assertTrue(name + ": text block shows its full height", r.height >= block.getPreferredSize().height);
			}
		}
		render(panel, name);
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

	/** Offscreen containers have no peer, so revalidate never clears BoxLayout's cached sizes: invalidate by hand. */
	private static void layout(Component c)
	{
		c.invalidate();
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
