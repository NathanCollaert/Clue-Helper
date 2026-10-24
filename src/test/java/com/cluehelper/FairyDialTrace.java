package com.cluehelper;

import com.cluehelper.guide.FairyDials;
import com.cluehelper.guide.MenuLines;
import com.cluehelper.guide.MenuTarget;
import com.cluehelper.guide.ScrollBook;
import java.awt.Color;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

@Slf4j
final class FairyDialTrace extends Overlay
{
	static final String TAG = "[CH-DIAL]";
	private static final int GROUP = InterfaceID.Fairyrings.CONFIRM >>> 16;
	private static final int[] DIAL_VARBITS = {VarbitID.FAIRYRING_1, VarbitID.FAIRYRING_2, VarbitID.FAIRYRING_3};
	/** Copies of MenuLineOverlay's colours: ui.Markers is package-private. */
	private static final Color COLOUR = new Color(64, 208, 255);
	private static final Color FILL = new Color(64, 208, 255, 40);

	private final Client client;
	private final MenuLines lines;
	private final FairyDials dials;
	private final Consumer<File> saveFrame;
	private String code;
	private boolean dumpPending;
	private boolean framePending;
	private int frameCount;

	FairyDialTrace(Client client, MenuLines lines, FairyDials dials, Consumer<File> saveFrame)
	{
		this.client = client;
		this.lines = lines;
		this.dials = dials;
		this.saveFrame = saveFrame;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (!event.getCommand().equalsIgnoreCase("ch-ring"))
		{
			return;
		}
		code = event.getArguments().length == 0 ? null : event.getArguments()[0].toUpperCase();
		log.info("{} t{} forced code {}", TAG, client.getTickCount(), code);
	}

	/** Runs after the plugin's own pass, which resets the colour of lines its (empty) target doesn't want. */
	@Subscribe(priority = -3)
	public void onClientTick(ClientTick tick)
	{
		if (code != null)
		{
			lines.colourLines(target(), ScrollBook.NO_WIDGET, true);
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == GROUP)
		{
			dumpPending = true;
		}
	}

	@Subscribe(priority = -3)
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (event.getParam1() >>> 16 == GROUP)
		{
			log.info("{} t{} click {} on {} dials {}", TAG, client.getTickCount(), event.getMenuOption(), name(event.getParam1()), dials());
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		for (int dial = 0; dial < DIAL_VARBITS.length; dial++)
		{
			if (event.getVarbitId() == DIAL_VARBITS[dial])
			{
				log.info("{} t{} dial {} -> {} dials {}", TAG, client.getTickCount(), dial + 1, event.getValue(), dials());
				framePending = true;
			}
		}
	}

	@Subscribe(priority = -3)
	public void onGameTick(GameTick tick)
	{
		if (dumpPending)
		{
			dumpPending = false;
			dumpWidgets();
			framePending = true;
		}
		if (framePending && client.getWidget(InterfaceID.Fairyrings.CONFIRM) != null)
		{
			framePending = false;
			saveFrame.accept(new File(PohTrace.FRAMES, String.format("dial-%03d-t%d-%s.png", ++frameCount, client.getTickCount(), dials())));
		}
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (code == null)
		{
			return null;
		}
		graphics.setStroke(new BasicStroke(2f));
		for (Rectangle line : lines.lines(target()))
		{
			draw(graphics, line);
		}
		dials.shapes(code).forEach(shape -> draw(graphics, shape));
		return null;
	}

	private static void draw(Graphics2D graphics, Shape shape)
	{
		graphics.setColor(FILL);
		graphics.fill(shape);
		graphics.setColor(COLOUR);
		graphics.draw(shape);
	}

	private MenuTarget target()
	{
		return MenuTarget.builder().line(MenuTarget.Line.FAIRY_CODE).lineKey(code).build();
	}

	private void dumpWidgets()
	{
		List<String> widgets = new ArrayList<>();
		for (int id = InterfaceID.Fairyrings.A; id <= InterfaceID.Fairyrings.ROOT_GRAPHIC27; id++)
		{
			Widget w = client.getWidget(id);
			if (w != null)
			{
				widgets.add(name(id) + " " + w.getBounds() + (w.isHidden() ? " hidden" : "") + " type " + w.getType() + " model " + w.getModelId()
					+ " sprite " + w.getSpriteId() + " text '" + w.getText() + "' ops " + (w.getActions() == null ? "[]" : String.join("/", nonNull(w.getActions()))));
			}
		}
		log.info("{} t{} opened, dials {}: {}", TAG, client.getTickCount(), dials(), widgets);
	}

	private String dials()
	{
		return client.getVarbitValue(DIAL_VARBITS[0]) + "" + client.getVarbitValue(DIAL_VARBITS[1]) + client.getVarbitValue(DIAL_VARBITS[2]);
	}

	private static String name(int id)
	{
		return (id >>> 16) + ":" + (id & 0xFFFF);
	}

	private static List<String> nonNull(String[] actions)
	{
		List<String> out = new ArrayList<>();
		for (String a : actions)
		{
			if (a != null && !a.isEmpty())
			{
				out.add(a);
			}
		}
		return out;
	}
}
