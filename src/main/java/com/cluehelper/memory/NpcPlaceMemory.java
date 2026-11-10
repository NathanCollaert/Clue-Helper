package com.cluehelper.memory;

import com.cluehelper.guide.Widgets;
import com.cluehelper.player.PlayerModel;
import com.cluehelper.step.*;
import java.util.*;
import java.util.stream.Collectors;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/**
 * Client thread. Where the game put a clue's NPC for this player (Viggora: one of three places, named in the Curse of the
 * Empty Lord journal), learned from that journal while it is open or from a Talk-to on the NPC. Until then the step
 * reads the journal first when its quest is done, else it goes to any of the places.
 */
@Singleton
public class NpcPlaceMemory
{
	static final String KEY = "npcPlaces";
	private static final String TALK = "Talk-to";
	/** A spot's NPC stands this near its target tile. */
	private static final int SPOT_TILES = 20;

	@Inject
	private Client client;
	@Inject
	private StepData data;
	@Inject
	private PlayerModel player;
	private MemorySlot<Places> remembered;
	private List<StepIndex.Entry> learnable;

	@Inject
	void init(MemoryStore memory)
	{
		remembered = memory.slot(KEY, Places.class, Places::new);
	}

	/** The step at its NPC's learned place; before that, with the journal to read first once its quest is done. */
	public Step adjusted(Step step)
	{
		StepIndex.LearnPlace learn = step.getData().getLearnPlace();
		if (learn == null)
		{
			return step;
		}
		Integer npc = remembered.get().npcs.get(learn.getQuest());
		if (npc != null)
		{
			return Stages.atNpc(step, npc);
		}
		if (!player.finished(Quest.valueOf(learn.getQuest())))
		{
			return step;
		}
		String who = step.getNpcs().isEmpty() ? "the NPC" : step.getNpcs().get(0);
		return Stages.learnFirst(step, "Read the " + learn.getJournal() + " journal", "It says where " + who + " is");
	}

	public void onGameTick()
	{
		Widget title = client.getWidget(InterfaceID.Questjournal.TITLE);
		if (title == null || title.isHidden() || title.getText() == null)
		{
			return;
		}
		String shownTitle = plain(title.getText());
		String text = Widgets.descendants(client.getWidget(InterfaceID.Questjournal.UNIVERSE)).stream()
			.map(Widget::getText).filter(Objects::nonNull).map(NpcPlaceMemory::plain).collect(Collectors.joining(" "));
		for (StepIndex.Entry entry : learnable())
		{
			StepIndex.LearnPlace learn = entry.getLearnPlace();
			if (shownTitle.contains(plain(learn.getJournal())))
			{
				learn.getSpots().stream().filter(spot -> spot.getWords().stream().map(NpcPlaceMemory::plain).anyMatch(text::contains))
					.findFirst().ifPresent(spot -> remember(learn, spot.getNpc()));
			}
		}
	}

	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		NPC npc = event.getMenuEntry().getNpc();
		if (npc == null || !TALK.equals(event.getMenuOption()))
		{
			return;
		}
		WorldPoint at = npc.getWorldLocation();
		for (StepIndex.Entry entry : learnable())
		{
			StepIndex.LearnPlace learn = entry.getLearnPlace();
			learn.getSpots().stream().filter(spot -> isShownAs(spot.getNpc(), npc.getId()))
				.filter(spot -> isNear(spotTile(entry, spot), at))
				.min(Comparator.comparingInt(spot -> spotTile(entry, spot).distanceTo2D(at)))
				.ifPresent(spot -> remember(learn, spot.getNpc()));
		}
	}

	/** The spot's NPC, or the NPC it spawns as: each of Viggora's three spots shows the same Mysterious ghost. */
	private boolean isShownAs(int spotNpc, int npcId)
	{
		if (spotNpc == npcId)
		{
			return true;
		}
		NPCComposition definition = client.getNpcDefinition(spotNpc);
		int[] forms = definition == null ? null : definition.getConfigs();
		return forms != null && Arrays.stream(forms).anyMatch(form -> form == npcId);
	}

	private static boolean isNear(WorldPoint spot, WorldPoint at)
	{
		return spot != null && spot.getPlane() == at.getPlane() && spot.distanceTo2D(at) <= SPOT_TILES;
	}

	private static WorldPoint spotTile(StepIndex.Entry entry, StepIndex.LearnSpot spot)
	{
		return entry.getTargets().stream().filter(target -> target.getNpcs().contains(spot.getNpc())).map(StepIndex.Target::getTile)
			.filter(Objects::nonNull).findFirst().orElse(null);
	}

	private List<StepIndex.Entry> learnable()
	{
		if (learnable == null)
		{
			learnable = data.index().getSteps().values().stream().filter(entry -> entry.getLearnPlace() != null)
				.collect(Collectors.toUnmodifiableList());
		}
		return learnable;
	}

	private void remember(StepIndex.LearnPlace learn, int npc)
	{
		Places known = remembered.get();
		if (Objects.equals(known.npcs.get(learn.getQuest()), npc))
		{
			return;
		}
		Places places = new Places();
		places.npcs = new HashMap<>(known.npcs);
		places.npcs.put(learn.getQuest(), npc);
		remembered.set(places);
	}

	/** The journal writes place names with tags and its own breaks: compared lower case, tags and apostrophes off. */
	private static String plain(String text)
	{
		return Text.removeTags(text.replace("<br>", " ")).replace("'", "").toLowerCase(Locale.ROOT);
	}

	static final class Places
	{
		/** By quest name, the id of the target NPC the player's journal or talk placed. */
		Map<String, Integer> npcs = new HashMap<>();
	}
}
