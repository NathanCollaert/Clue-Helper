package com.cluehelper.ui;

import com.cluehelper.step.CoreClues;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Collectors;
import net.runelite.api.*;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.cluescrolls.*;
import net.runelite.client.plugins.cluescrolls.clues.*;
import net.runelite.client.ui.overlay.components.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pins where core puts the lines RedundantCoreLines finds by place, because a line hides its text. */
public class RedundantCoreLinesTest
{
	private static final List<String> REDUNDANT = List.of(
		"Click the clue scroll on your world map to see dig location.",
		"Click the clue scroll along the edge of your world map to see your destination.",
		"Travel to the destination and dig on the marked tile.",
		"Travel to the fairy ring to see where to dig.");

	@Test
	public void removesOnlyTheRedundantLinesFromEveryCoreMapCoordinateAndFairyRingClue() throws ReflectiveOperationException
	{
		ClueScrollPlugin plugin = pluginCarrying(new Item[]{new Item(ItemID.SPADE, 1), new Item(ItemID.TORCH_LIT, 1)});
		List<String> wrong = new ArrayList<>();
		int checked = 0;
		for (Map.Entry<String, ClueScroll> step : CoreClues.steps().entrySet())
		{
			ClueScroll clue = step.getValue();
			if (!(clue instanceof MapClue) && !(clue instanceof CoordinateClue) && !(clue instanceof FairyRingClue))
			{
				continue;
			}
			PanelComponent panel = new PanelComponent();
			makeOverlayHint(clue, panel, plugin);
			List<String> before = texts(panel.getChildren());
			RedundantCoreLines.remove(panel.getChildren(), clue, plugin);
			List<String> after = texts(panel.getChildren());

			List<String> expected = withoutBlanks(before);
			expected.removeAll(REDUNDANT);
			int removedBlanks = before.size() - after.size() - (withoutBlanks(before).size() - expected.size());
			if (!withoutBlanks(after).equals(expected) || removedBlanks > 1)
			{
				wrong.add(step.getKey() + ": " + before + " -> " + after);
			}
			checked++;
		}
		assertTrue("too few clues checked: " + checked, checked > 100);
		assertEquals(String.join("\n", wrong), 0, wrong.size());
	}

	@Test
	public void removesCoreBoxItemWarningsAndKeepsTheEnemy() throws Exception
	{
		ClueScrollPlugin plugin = pluginCarrying(new Item[0]);
		ClueScroll clue = new DarkDigWithEnemy();
		setField(plugin, "clue", clue);
		Constructor<ClueScrollOverlay> create = ClueScrollOverlay.class.getDeclaredConstructor(ClueScrollPlugin.class, Client.class);
		create.setAccessible(true);
		ClueScrollOverlay overlay = create.newInstance(plugin, plugin.getClient());
		overlay.setClearChildren(false);
		overlay.render(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics());

		List<LayoutableRenderableEntity> lines = new ArrayList<>(overlay.getPanelComponent().getChildren());
		assertEquals(List.of(DarkDigWithEnemy.TITLE, "", "Requires Spade!", "", "Requires Light Source!", "", DarkDigWithEnemy.ENEMY.getText()),
			texts(lines));
		RedundantCoreLines.remove(lines, clue, plugin);
		assertEquals(List.of(DarkDigWithEnemy.TITLE, "", DarkDigWithEnemy.ENEMY.getText()), texts(lines));
	}

	/** Needs both a spade and a light, and has no note, which the offline plugin could not read. */
	private static final class DarkDigWithEnemy extends ClueScroll
	{
		static final String TITLE = "Dark dig";
		static final Enemy ENEMY = Enemy.DOUBLE_AGENT_65;

		DarkDigWithEnemy()
		{
			setRequiresSpade(true);
			setRequiresLight(true);
			setFirePitVarbitId(-1);
			setEnemy(ENEMY);
		}

		@Override
		public void makeOverlayHint(PanelComponent panelComponent, ClueScrollPlugin plugin)
		{
			panelComponent.getChildren().add(TitleComponent.builder().text(TITLE).build());
		}

		@Override
		public void makeWorldOverlayHint(Graphics2D graphics, ClueScrollPlugin plugin)
		{
		}
	}

	/** A plugin of its own, so the shared one keeps its empty hands. */
	private static ClueScrollPlugin pluginCarrying(Item[] carried) throws ReflectiveOperationException
	{
		ClueScrollPlugin plugin = CoreClues.plugin(CoreClues.plugin().getClient());
		setField(plugin, "inventoryItems", carried);
		setField(plugin, "equippedItems", new Item[0]);
		return plugin;
	}

	private static void setField(Object owner, String name, Object value) throws ReflectiveOperationException
	{
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(owner, value);
	}

	/** The offline plugin has no config manager, so the note core draws last fails; every line above it is drawn. */
	private static void makeOverlayHint(ClueScroll clue, PanelComponent panel, ClueScrollPlugin plugin)
	{
		try
		{
			clue.makeOverlayHint(panel, plugin);
		}
		catch (NullPointerException noConfigManager)
		{
			boolean inNote = Arrays.stream(noConfigManager.getStackTrace())
				.anyMatch(frame -> frame.getMethodName().equals("renderOverlayNote"));
			if (!inNote)
			{
				throw noConfigManager;
			}
		}
	}

	private static List<String> withoutBlanks(List<String> texts)
	{
		return texts.stream().filter(text -> !text.isEmpty()).collect(Collectors.toList());
	}

	private static List<String> texts(List<LayoutableRenderableEntity> lines) throws ReflectiveOperationException
	{
		List<String> texts = new ArrayList<>();
		for (LayoutableRenderableEntity line : lines)
		{
			String field = line instanceof TitleComponent ? "text" : "left";
			Field text = line.getClass().getDeclaredField(field);
			text.setAccessible(true);
			Object value = text.get(line);
			texts.add(value == null ? "" : value.toString());
		}
		return texts;
	}
}
