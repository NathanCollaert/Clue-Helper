package com.cluehelper.memory;

import com.cluehelper.player.PlayerModel;
import java.util.*;
import javax.inject.*;
import net.runelite.api.Client;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.util.Text;

/**
 * Client thread. Which keys hang on the steel key ring is in no var the client gets, so the Metal key's place is learned
 * from the player's clicks: Remove on it in the ring's Check-keys window takes it off, using it on the ring puts it back.
 * Until either is seen the key counts as on the ring. A ring without it doesn't open the Desert Mining Camp's gate.
 */
@Singleton
public class KeyRingMemory
{
	static final String KEY = "keyRing";
	private static final String REMOVE = "Remove";
	private static final String USE = "Use";
	private static final String METAL_KEY = "metal key";
	private static final String ON_THE_RING = METAL_KEY + " -> steel key ring";

	@Inject
	private Client client;
	@Inject
	private PlayerModel player;
	private MemorySlot<Ring> remembered;

	@Inject
	void init(MemoryStore memory)
	{
		remembered = memory.slot(KEY, Ring.class, Ring::new);
	}

	/** Call on start and after a profile change. */
	public void publish()
	{
		player.setUncounted(remembered.get().metalKeyOff ? Set.of(ItemID.FAVOUR_KEY_RING) : Set.of());
	}

	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		String target = event.getMenuTarget() == null ? "" : Text.removeTags(event.getMenuTarget()).toLowerCase(Locale.ROOT);
		boolean takenOff = REMOVE.equals(event.getMenuOption()) && METAL_KEY.equals(target) && isRingOpen();
		boolean putBack = USE.equals(event.getMenuOption()) && ON_THE_RING.equals(target);
		if (takenOff || putBack)
		{
			remember(takenOff);
		}
	}

	private boolean isRingOpen()
	{
		return client.getWidget(InterfaceID.FAVOUR_KEYRING, 0) != null;
	}

	private void remember(boolean metalKeyOff)
	{
		if (remembered.get().metalKeyOff == metalKeyOff)
		{
			return;
		}
		Ring ring = new Ring();
		ring.metalKeyOff = metalKeyOff;
		remembered.set(ring);
		publish();
	}

	static final class Ring
	{
		boolean metalKeyOff;
	}
}
