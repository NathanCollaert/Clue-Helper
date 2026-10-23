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
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;

@Slf4j
final class PohTrace
{
	static final String POH_TAG = "[CH-POH]";
	static final String MENU_TAG = "[CH-MENU]";
	static final File FRAMES = new File("build/poh-menus");
	private static final Set<Integer> TELEPORT_INTERFACES = Set.of(17, 187, 219, 590, InterfaceID.Fairyrings.ROOT_RECT0 >>> 16,
		InterfaceID.FairyringsLog.UNIVERSE >>> 16, InterfaceID.QuetzalMenu.ICONS >>> 16, InterfaceID.QuetzalwhistleMenu.ICONS >>> 16);
	private static final int MOST_FRAMES = 200;
	private static final int NEXUS_GROUP = 17;
	/** Interfaces the client reloads with every new scene: not a teleport's chooser. */
	private static final Set<Integer> RELOADED_ON_LANDING = Set.of(71, 109, 116, 182, 203, 216, 218, 239, 320, 387, 429, 541, 629, 712);

	private final Client client;
	private final StepGuide guide;
	private final MenuLines lines;
	private final Consumer<File> saveFrame;
	private final Runnable unregister;
	private int pendingInterface = -1;
	private static final int TREE_CHILDREN = 80;
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
			log.info("{} t{} target {}", MENU_TAG, client.getTickCount(), now);
		}
		lastTarget = now;
		if (pendingMenu && client.isMenuOpen())
		{
			log.info("{} t{} menu rows outlined {}", MENU_TAG, client.getTickCount(), rects(lines.menuRows(target)));
		}
		pendingMenu = false;
		if (pendingInterface >= 0)
		{
			log.info("{} t{} interface {} lines {}", MENU_TAG, client.getTickCount(), pendingInterface,
				rects(lines.lines(target)));
			if (pendingInterface == InterfaceID.MINIGAMES)
			{
				logMinigameCards();
			}
			if (pendingInterface == InterfaceID.BOOKOFSCROLLS && guide.getHighlightMenuWidget() >= 0)
			{
				logScrollEntry(guide.getHighlightMenuWidget());
			}
			if (pendingInterface == NEXUS_GROUP)
			{
				logLayer("rows", InterfaceID.TelenexusTeleport.ROWS1);
				logLayer("text", InterfaceID.TelenexusTeleport.TEXT1);
			}
			if (pendingInterface == InterfaceID.SAILING_MENU || pendingInterface == InterfaceID.CHARTERING_MENU_SIDE)
			{
				logTree(InterfaceID.SAILING_MENU);
				logTree(InterfaceID.CHARTERING_MENU_SIDE);
			}
			frame("interface-" + pendingInterface);
			pendingInterface = -1;
		}
	}

	/** Runs before the plugin (priority 2) and better-teleport-menu (1), so the arguments are logged as they arrive. */
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

	private void logScrollEntry(int entry)
	{
		List<String> parts = new ArrayList<>();
		for (int id = entry; id <= entry + 3; id++)
		{
			Widget w = client.getWidget(id);
			parts.add((id & 0xFFFF) + (w == null ? " null" : " type " + w.getType() + " item " + w.getItemId() + " sprite " + w.getSpriteId()
				+ " model " + w.getModelId() + " text '" + w.getText() + "' " + w.getBounds()));
		}
		log.info("{} t{} scroll entry {}", MENU_TAG, client.getTickCount(), parts);
	}

	private void logMinigameCards()
	{
		List<String> cards = new ArrayList<>();
		for (int card = InterfaceID.Minigames.MINIGAME_21; card <= InterfaceID.Minigames.MINIGAME_1; card++)
		{
			Widget widget = client.getWidget(card);
			List<String> texts = new ArrayList<>();
			collectTexts(widget, texts);
			cards.add((card & 0xFFFF) + (widget == null ? " null" : widget.isHidden() ? " hidden" : "") + " " + texts);
		}
		log.info("{} t{} minigame cards {}", MENU_TAG, client.getTickCount(), cards);
	}

	private static void collectTexts(Widget widget, List<String> out)
	{
		if (widget == null)
		{
			return;
		}
		if (widget.getText() != null && !widget.getText().isBlank())
		{
			out.add(widget.getText());
		}
		for (Widget[] children : new Widget[][]{widget.getStaticChildren(), widget.getDynamicChildren(), widget.getNestedChildren()})
		{
			for (int i = 0; children != null && i < children.length; i++)
			{
				collectTexts(children[i], out);
			}
		}
	}

	private void logLayer(String name, int component)
	{
		Widget layer = client.getWidget(component);
		Widget[] children = layer == null ? null : layer.getDynamicChildren();
		List<String> lines = new ArrayList<>();
		for (int i = 0; children != null && i < Math.min(4, children.length); i++)
		{
			Widget c = children[i];
			lines.add(i + ": y " + c.getRelativeY() + " h " + c.getHeight() + " text '" + c.getText() + "' colour " + Integer.toHexString(c.getTextColor()));
		}
		log.info("{} t{} nexus {} layer: {} children {}", MENU_TAG, client.getTickCount(), name, children == null ? 0 : children.length, lines);
	}

	/** Every widget of an interface we don't read yet, to learn where its lines and map markers are. */
	private void logTree(int group)
	{
		for (int child = 0; child < TREE_CHILDREN; child++)
		{
			Widget widget = client.getWidget(group, child);
			if (widget != null)
			{
				logWidget(group + ":" + child, widget);
			}
		}
	}

	private void logWidget(String path, Widget widget)
	{
		log.info("{} tree {} type {} hidden {} text '{}' name '{}' actions {} sprite {} item {} bounds {}", MENU_TAG, path,
			widget.getType(), widget.isHidden(), widget.getText(), widget.getName(),
			widget.getActions() == null ? "[]" : java.util.Arrays.toString(widget.getActions()), widget.getSpriteId(),
			widget.getItemId(), widget.getBounds());
		Widget[] dynamic = widget.getDynamicChildren();
		for (int i = 0; dynamic != null && i < Math.min(dynamic.length, TREE_CHILDREN); i++)
		{
			if (dynamic[i] != null)
			{
				logWidget(path + "[" + i + "]", dynamic[i]);
			}
		}
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
