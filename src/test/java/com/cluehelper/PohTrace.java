package com.cluehelper;

import com.cluehelper.guide.MenuLines;
import com.cluehelper.guide.MenuTarget;
import com.cluehelper.guide.StepGuide;
import com.cluehelper.guide.SubStep;
import com.cluehelper.poh.PohService;
import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.eventbus.Subscribe;

/**
 * B16's live check: {@code [CH-POH]} lines for house entries, ownership, scans and varbits; {@code [CH-MENU]} lines for
 * every right-click menu and teleport interface opened while a sub-step has a menu target, with the entries, the target
 * and the rectangles the overlay outlines, plus a frame of each. Nothing while the bank PIN keypad is open.
 */
@Slf4j
final class PohTrace
{
	static final String POH_TAG = "[CH-POH]";
	static final String MENU_TAG = "[CH-MENU]";
	static final File FRAMES = new File("build/poh-menus");
	private static final Set<Integer> TELEPORT_INTERFACES = Set.of(17, 187, 219, 590, InterfaceID.Fairyrings.ROOT_RECT0 >>> 16,
		InterfaceID.FairyringsLog.UNIVERSE >>> 16);
	private static final int MOST_FRAMES = 200;
	/** Interfaces the client reloads with every new scene (seen live on each landing): not a teleport's chooser. */
	private static final Set<Integer> RELOADED_ON_LANDING = Set.of(71, 109, 116, 182, 203, 216, 218, 239, 320, 387, 429, 541, 629, 712);

	private final Client client;
	private final StepGuide guide;
	private final MenuLines lines;
	private final Consumer<File> saveFrame;
	private final Runnable unregister;
	private int pendingInterface = -1;
	private boolean pendingMenu;
	private int frames;
	private String lastTarget;

	PohTrace(Client client, PohService poh, StepGuide guide, MenuLines lines, Consumer<File> saveFrame)
	{
		this.client = client;
		this.guide = guide;
		this.lines = lines;
		this.saveFrame = saveFrame;
		FRAMES.mkdirs();
		unregister = poh.addListener(line ->
		{
			log.info("{} t{} {}", POH_TAG, client.getTickCount(), line);
			if (line.startsWith("scan") || line.startsWith("entered"))
			{
				frame("house");
			}
		});
	}

	void stop()
	{
		unregister.run();
	}

	@Subscribe(priority = -3)
	public void onGameTick(GameTick tick)
	{
		MenuTarget target = guide.getMenuTarget();
		String now = target == null || target.isEmpty() ? null : describe(target);
		if (now != null && !now.equals(lastTarget))
		{
			log.info("{} t{} target {} (hint {})", MENU_TAG, client.getTickCount(), now, guide.getMenuHint());
		}
		lastTarget = now;
		if (pendingMenu && client.isMenuOpen())
		{
			log.info("{} t{} menu rows outlined {}", MENU_TAG, client.getTickCount(), rects(lines.menuRows(target)));
		}
		pendingMenu = false;
		if (pendingInterface >= 0)
		{
			log.info("{} t{} interface {} lines {} hint {}", MENU_TAG, client.getTickCount(), pendingInterface,
				rects(lines.lines(target)), guide.getMenuHint());
			frame("interface-" + pendingInterface);
			pendingInterface = -1;
		}
	}

	/** The list scripts' arguments as they arrive (B16: the diary cape's list wasn't matched). */
	@Subscribe(priority = 3)
	public void onScriptPreFired(ScriptPreFired event)
	{
		int id = event.getScriptId();
		if (id != 218 && id != 219 && id != 9142 && id != 9143 && id != 217)
		{
			return;
		}
		int ints = client.getIntStackSize();
		List<Integer> tail = new ArrayList<>();
		for (int i = Math.max(0, ints - 8); i < ints; i++)
		{
			tail.add(client.getIntStack()[i]);
		}
		int objects = client.getObjectStackSize();
		List<String> strings = new ArrayList<>();
		for (int i = Math.max(0, objects - 3); i < objects; i++)
		{
			strings.add(String.valueOf(client.getObjectStack()[i]));
		}
		log.info("{} t{} script {} ints {} objects {}", MENU_TAG, client.getTickCount(), id, tail, strings);
	}

	@Subscribe(priority = -3)
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (TELEPORT_INTERFACES.contains(event.getGroupId()) && !guide.getMenuTarget().isEmpty() && !keypadOpen())
		{
			pendingInterface = event.getGroupId();
		}
		else if (guide.getCurrent() != null && guide.getCurrent().isTravel() && !RELOADED_ON_LANDING.contains(event.getGroupId()) && !keypadOpen())
		{
			log.info("{} t{} interface {} loaded during '{}'", MENU_TAG, client.getTickCount(), event.getGroupId(), guide.getCurrent().getText());
			pendingInterface = event.getGroupId();
		}
	}

	@Subscribe(priority = -3)
	public void onMenuOpened(MenuOpened event)
	{
		MenuTarget target = guide.getMenuTarget();
		if (target.isEmpty() || keypadOpen())
		{
			return;
		}
		List<String> entries = new ArrayList<>();
		for (MenuEntry entry : client.getMenu().getMenuEntries())
		{
			Menu sub = entry.getSubMenu();
			StringBuilder text = new StringBuilder(entry.getOption()).append('|').append(entry.getTarget()).append('|')
				.append(entry.getType()).append('|').append(entry.getIdentifier()).append('|').append(entry.getItemId());
			if (sub != null)
			{
				text.append(" sub[");
				for (MenuEntry option : sub.getMenuEntries())
				{
					text.append(option.getOption()).append(';');
				}
				text.append(']');
			}
			entries.add(text.toString());
		}
		Menu menu = client.getMenu();
		log.info("{} t{} menu at {},{} {}x{} entries {}", MENU_TAG, client.getTickCount(), menu.getMenuX(), menu.getMenuY(),
			menu.getMenuWidth(), menu.getMenuHeight(), entries);
		pendingMenu = true;
		frame("menu");
	}

	@Subscribe(priority = -3)
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		SubStep current = guide.getCurrent();
		if (current == null || keypadOpen() || guide.getMenuTarget().isEmpty())
		{
			return;
		}
		int widget = event.getParam1();
		log.info("{} t{} click {}|{}|{}|{} widget {}:{}[{}] during {} '{}'", MENU_TAG, client.getTickCount(), event.getMenuOption(),
			event.getMenuTarget(), event.getMenuAction(), event.getId(), widget >>> 16, widget & 0xFFFF, event.getParam0(),
			current.getKind(), current.getText());
	}

	private String describe(MenuTarget target)
	{
		return target.getLine() + " id=" + target.getLineId() + " key=" + target.getLineKey() + " name=" + target.getLineName()
			+ " path=" + target.getPath() + " items=" + target.getItemIds() + " objects=" + target.getObjectIds()
			+ " found=" + guide.getHighlightObjects().size() + " filled=" + guide.getFillItems() + "/" + guide.getFillObjects().size();
	}

	private boolean keypadOpen()
	{
		return client.getWidget(InterfaceID.BANKPIN_KEYPAD, 0) != null;
	}

	private void frame(String what)
	{
		if (frames < MOST_FRAMES && !keypadOpen())
		{
			saveFrame.accept(new File(FRAMES, String.format("%03d-t%d-%s.png", ++frames, client.getTickCount(), what)));
		}
	}

	private static String rects(List<Rectangle> rects)
	{
		List<String> out = new ArrayList<>();
		rects.forEach(r -> out.add(r.x + "," + r.y + " " + r.width + "x" + r.height));
		return out.toString();
	}
}
