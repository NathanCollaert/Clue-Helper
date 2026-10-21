package com.cluehelper;

import com.cluehelper.memory.FairyRingMemory;
import com.cluehelper.memory.SpiritTreeMemory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;

@Slf4j
final class NetworkTrace
{
	static final String TREE_TAG = "[CH-TREE]";
	static final String RING_TAG = "[CH-RING]";
	static final String TIME_TAG = "[CH-TIME]";
	static final String QUETZAL_TAG = "[CH-QUETZAL]";
	private static final int WATCH_TICKS = 30;
	private static final int QUETZAL_MENU = InterfaceID.QuetzalMenu.ICONS >>> 16;
	private static final int WHISTLE_MENU = InterfaceID.QuetzalwhistleMenu.ICONS >>> 16;

	private final Client client;
	private final Consumer<File> saveFrame;
	private final Runnable unregister;
	private int watching;
	private WorldPoint lastTile;
	private int lastAnimation = -2;
	private int pendingQuetzal = -1;

	NetworkTrace(Client client, SpiritTreeMemory trees, FairyRingMemory rings, Consumer<File> saveFrame)
	{
		this.client = client;
		this.saveFrame = saveFrame;
		Runnable untrees = trees.addListener(line -> log.info("{} t{} {}", TREE_TAG, client.getTickCount(), line));
		Runnable unrings = rings.addListener(line -> log.info("{} t{} {}", RING_TAG, client.getTickCount(), line));
		unregister = () ->
		{
			untrees.run();
			unrings.run();
		};
	}

	void stop()
	{
		unregister.run();
	}

	@Subscribe(priority = -3)
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (keypadOpen())
		{
			return;
		}
		log.info("{} t{} click {}|{}|{}|{} at {}", TIME_TAG, client.getTickCount(), event.getMenuOption(), event.getMenuTarget(),
			event.getMenuAction(), event.getId(), text(tile()));
		watching = WATCH_TICKS;
	}

	@Subscribe(priority = -3)
	public void onGameTick(GameTick tick)
	{
		if (pendingQuetzal >= 0)
		{
			dumpQuetzalIcons(pendingQuetzal);
			pendingQuetzal = -1;
		}
		if (watching <= 0 || keypadOpen())
		{
			return;
		}
		watching--;
		WorldPoint tile = tile();
		Player local = client.getLocalPlayer();
		int animation = local == null ? -1 : local.getAnimation();
		if (tile != null && !tile.equals(lastTile) || animation != lastAnimation)
		{
			log.info("{} t{} at {} anim {}", TIME_TAG, client.getTickCount(), text(tile), animation);
		}
		lastTile = tile;
		lastAnimation = animation;
	}

	@Subscribe(priority = -3)
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == QUETZAL_MENU || event.getGroupId() == WHISTLE_MENU)
		{
			pendingQuetzal = event.getGroupId();
		}
	}

	private void dumpQuetzalIcons(int group)
	{
		Widget icons = client.getWidget(group == QUETZAL_MENU ? InterfaceID.QuetzalMenu.ICONS : InterfaceID.QuetzalwhistleMenu.ICONS);
		List<String> children = new ArrayList<>();
		Widget[] dynamic = icons == null ? null : icons.getDynamicChildren();
		for (int i = 0; dynamic != null && i < dynamic.length; i++)
		{
			Widget c = dynamic[i];
			children.add(i + ":" + c.getOriginalX() + "," + c.getOriginalY() + " " + c.getWidth() + "x" + c.getHeight()
				+ (c.isHidden() ? " hidden" : "") + " sprite " + c.getSpriteId() + " model " + c.getModelId() + " name '" + c.getName()
				+ "' ops " + (c.getActions() == null ? "[]" : String.join("/", nonNull(c.getActions()))));
		}
		log.info("{} t{} interface {} icons {} children {}", QUETZAL_TAG, client.getTickCount(), group,
			icons == null ? "none" : icons.getBounds(), children);
		List<String> sites = new ArrayList<>();
		for (int row : client.getDBTableRows(DBTableID.Quetzal.ID))
		{
			sites.add(client.getDBTableField(row, DBTableID.Quetzal.COL_ID, 0)[0] + " " + client.getDBTableField(row, DBTableID.Quetzal.COL_NAME, 0)[0]
				+ " @" + client.getDBTableField(row, DBTableID.Quetzal.COL_IF_X_POS, 0)[0] + "," + client.getDBTableField(row, DBTableID.Quetzal.COL_IF_Y_POS, 0)[0]);
		}
		log.info("{} t{} cache sites {}", QUETZAL_TAG, client.getTickCount(), sites);
		saveFrame.accept(new File(PohTrace.FRAMES, "quetzal-" + group + "-t" + client.getTickCount() + ".png"));
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

	private WorldPoint tile()
	{
		Player local = client.getLocalPlayer();
		return local == null ? null : WorldPoint.fromLocalInstance(client, local.getLocalLocation());
	}

	private boolean keypadOpen()
	{
		return client.getWidget(InterfaceID.BANKPIN_KEYPAD, 0) != null;
	}

	private static String text(WorldPoint tile)
	{
		return tile == null ? "?" : tile.getX() + " " + tile.getY() + " " + tile.getPlane();
	}
}
