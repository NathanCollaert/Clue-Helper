package com.cluehelper.guide;

import com.cluehelper.memory.BankMemory;
import com.cluehelper.player.*;
import com.cluehelper.route.*;
import com.cluehelper.step.*;
import java.util.*;
import java.util.stream.Collectors;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;
import net.runelite.client.util.Text;

/**
 * A skill challenge's task act: the items it is done with, the making step reached, the spell cast on an item and the
 * interface option it picks. Client thread only.
 */
@Singleton
final class TaskActs
{
	/** A farming patch's varbit: below this it still has weeds, above it a crop (as RuneLite's time tracking reads it). */
	private static final int PATCH_WEEDED = 3;
	private static final String RAKE = "Rake";
	private static final String SPADE = "Spade";

	/** In the order a patch is picked to plant in: the least clearing first. */
	private enum Patch
	{
		EMPTY,
		WEEDS,
		CROP
	}

	@Inject
	private Client client;
	@Inject
	private SceneObjects sceneObjects;
	@Inject
	private PlayerModel player;
	@Inject
	private BankMemory bankMemory;
	@Inject
	private StepNeeds stepNeeds;
	@Inject
	private LeftClicks leftClicks;

	/** The items the task needs that the player carries, outlined (used on each other, not clicked once). */
	Highlights withItems(Highlights shown, SubStep current, Step step)
	{
		if (current == null || !current.isTask() || step == null || step.getRequirements() == null)
		{
			return shown;
		}
		Set<Integer> carried = player.carriedIds();
		if (neededFromCarriedKit(step) != null)
		{
			return shown.toBuilder().items(Set.of(ItemID.HUNTSMANS_KIT)).tab(StepGuide.Tab.INVENTORY).build();
		}
		Integer staff = staffToWield(step);
		if (staff != null)
		{
			Set<Integer> wield = Set.of(staff);
			return shown.toBuilder().items(wield).fillItems(leftClicks.wears(staff) ? wield : Set.of()).tab(StepGuide.Tab.INVENTORY).build();
		}
		StepIndex.Cast cast = step.getData().getCast();
		if (cast != null && cast.getOn().stream().anyMatch(carried::contains))
		{
			return withCast(shown, cast, carried);
		}
		Set<Integer> forPatch = patchItems(step, carried);
		if (forPatch != null)
		{
			return shown.toBuilder().items(forPatch).fillItems(forPatch.stream().filter(leftClicks::wearsOrUses).collect(Collectors.toUnmodifiableSet()))
				.tab(StepGuide.Tab.INVENTORY).build();
		}
		StepIndex.Making making = makingNow(step, carried);
		if (current.getStage().getOption() != null && making == null)
		{
			return shown;
		}
		Set<Integer> items = making != null
			? carried.stream().filter(id -> making.getItems().stream().anyMatch(group -> group.contains(id))).collect(Collectors.toUnmodifiableSet())
			: carried.stream().filter(id -> step.getRequirements().getItems().stream().anyMatch(need -> need.fulfilledBy(id)))
			.collect(Collectors.toUnmodifiableSet());
		return shown.toBuilder().items(items).fillItems(items.stream().filter(leftClicks::wearsOrUses).collect(Collectors.toUnmodifiableSet()))
			.tab(items.isEmpty() ? shown.getTab() : StepGuide.Tab.INVENTORY).build();
	}

	/** The book to switch to first, else the making step reached; null when the task needs neither. */
	String detail(Step step)
	{
		String book = step == null ? null : step.getData().getBook();
		if (book != null && !book.equals(player.currentBook().name()))
		{
			return "Switch to the " + book.toLowerCase(Locale.ROOT) + " spellbook first";
		}
		String inKit = neededFromCarriedKit(step);
		if (inKit != null)
		{
			return "Take the " + inKit + " out of the huntsman's kit";
		}
		Integer staff = staffToWield(step);
		if (staff != null)
		{
			return "Wield the " + client.getItemDefinition(staff).getName() + " first";
		}
		Patch patch = nearestPatch(step);
		if (patch != null)
		{
			return patch == Patch.WEEDS ? "Rake the weeds off the patch first"
				: patch == Patch.CROP ? "Dig the crop out with the spade first" : "Use the seed on the patch";
		}
		StepIndex.Making making = makingNow(step, player.carriedIds());
		return making == null ? null : making.getText();
	}

	/** What the nearest patch takes next, as carried: the rake on weeds, the spade on a crop, else the seed and dibber. */
	private Set<Integer> patchItems(Step step, Set<Integer> carried)
	{
		Patch patch = nearestPatch(step);
		if (patch == null)
		{
			return null;
		}
		if (patch == Patch.CROP)
		{
			return step.getData().getRequires().stream().filter(need -> need.getName().equals(SPADE))
				.flatMap(need -> need.getIds().stream()).filter(carried::contains).collect(Collectors.toUnmodifiableSet());
		}
		boolean rake = patch == Patch.WEEDS;
		return carried.stream().filter(id -> step.getRequirements().getItems().stream()
				.anyMatch(need -> need.fulfilledBy(id) && RAKE.equals(need.getCollectiveName(client)) == rake))
			.collect(Collectors.toUnmodifiableSet());
	}

	/** The state of the patch to plant in; null for other tasks or with none in the scene. */
	private Patch nearestPatch(Step step)
	{
		if (step == null || !step.getData().isPlants())
		{
			return null;
		}
		Set<Integer> ids = step.getData().getPlaces().stream().flatMap(place -> place.getObjects().stream()).collect(Collectors.toSet());
		TileObject patch = patchToUse(step, sceneObjects.find(ids));
		return patch == null ? null : stateOf(patch);
	}

	/**
	 * Of a planting task's patches in sight, the one to plant in: an empty one before a weedy one before one with a crop,
	 * as clearing takes a while, then the nearest. Null for other tasks or none read.
	 */
	TileObject patchToUse(Step step, List<TileObject> patches)
	{
		Player local = client.getLocalPlayer();
		if (step == null || !step.getData().isPlants() || local == null)
		{
			return null;
		}
		WorldPoint at = local.getWorldLocation();
		return patches.stream().filter(patch -> stateOf(patch) != null)
			.min(Comparator.comparing(this::stateOf).thenComparingInt(patch -> patch.getWorldLocation().distanceTo2D(at)))
			.orElse(null);
	}

	/** Read from the patch's varbit; null for an object that has none. */
	private Patch stateOf(TileObject patch)
	{
		int varbit = client.getObjectDefinition(patch.getId()).getVarbitId();
		if (varbit < 0)
		{
			return null;
		}
		int state = client.getVarbitValue(varbit);
		return state < PATCH_WEEDED ? Patch.WEEDS : state == PATCH_WEEDED ? Patch.EMPTY : Patch.CROP;
	}

	/**
	 * At a spirit tree for the planted-tree task: the player's first planted tree, as its line in the tree's list, reached
	 * by Travel or, on the house's spiritual fairy tree, by Tree.
	 */
	String plantedTreeLine(SubStep current, Step step)
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		if (!current.isTask() || step == null || !step.getData().isToPlantedTree() || snapshot == null)
		{
			return null;
		}
		return snapshot.getPlantedTrees().stream().sorted().findFirst().orElse(null);
	}

	/** An interface option the task picks and its confirm button, outlined while the interface is open. */
	List<Widget> choice(SubStep current, Step step)
	{
		Widget product = makeOption(step);
		if (product != null)
		{
			return List.of(product);
		}
		StepIndex.Choose choose = current != null && current.isTask() && step != null ? step.getData().getChoose() : null;
		Widget root = choose == null ? null : client.getWidget(choose.getRoot());
		if (root == null || root.isHidden())
		{
			return List.of();
		}
		Widget option = withText(root, choose.getText());
		List<Widget> widgets = new ArrayList<>();
		if (option != null)
		{
			widgets.add(option.getParent() != null ? option.getParent() : option);
		}
		Widget confirm = client.getWidget(choose.getConfirm());
		if (confirm != null && !confirm.isHidden())
		{
			widgets.add(confirm);
		}
		return List.copyOf(widgets);
	}

	/** The making step's product in the "How many do you wish to make?" box: its option, A to R. */
	private Widget makeOption(Step step)
	{
		Widget box = client.getWidget(InterfaceID.Skillmulti.UNIVERSE);
		StepIndex.Making making = box == null || box.isHidden() || step == null ? null : makingNow(step, player.carriedIds());
		if (making == null || making.getMake() < 0)
		{
			return null;
		}
		for (int id = InterfaceID.Skillmulti.A; id <= InterfaceID.Skillmulti.R; id++)
		{
			Widget option = client.getWidget(id);
			if (option != null && !option.isHidden()
				&& Widgets.descendants(option).stream().anyMatch(shown -> shown.getItemId() == making.getMake()))
			{
				return option;
			}
		}
		return null;
	}

	private static Widget withText(Widget parent, String text)
	{
		return Widgets.descendants(parent).stream()
			.filter(widget -> !widget.isHidden() && widget.getText() != null && Text.removeTags(widget.getText()).equalsIgnoreCase(text))
			.findFirst().orElse(null);
	}

	/** The name of a step item only the carried huntsman's kit holds, or null. */
	private String neededFromCarriedKit(Step step)
	{
		Set<Integer> carried = player.carriedIds();
		BankMemory.Seen kit = carried.contains(ItemID.HUNTSMANS_KIT) ? bankMemory.getKit() : null;
		if (kit == null || step == null)
		{
			return null;
		}
		Item[] held = PlayerSnapshot.oneOfEach(carried);
		for (RequiredItems.Need need : stepNeeds.of(step).getNeeds())
		{
			if (!need.getRequirement().fulfilledBy(held))
			{
				Integer stored = kit.items.keySet().stream().filter(need.getRequirement()::fulfilledBy).findFirst().orElse(null);
				if (stored != null)
				{
					return client.getItemDefinition(stored).getName().toLowerCase(Locale.ROOT);
				}
			}
		}
		return null;
	}

	/** The spell's button until it is picked (the last of its buttons shown), then the items it is cast on. */
	private Highlights withCast(Highlights shown, StepIndex.Cast cast, Set<Integer> carried)
	{
		boolean picked = client.isWidgetSelected();
		Set<Integer> items = picked ? carried.stream().filter(cast.getOn()::contains).collect(Collectors.toUnmodifiableSet()) : Set.of();
		int spellWidget = picked ? -1 : cast.getWidgets().stream().filter(id -> Widgets.shown(client, id)).reduce((first, last) -> last)
			.orElse(cast.getWidgets().isEmpty() ? -1 : cast.getWidgets().get(0));
		return shown.toBuilder().items(items).fillItems(items).spellWidget(spellWidget)
			.tab(picked ? StepGuide.Tab.INVENTORY : StepGuide.Tab.MAGIC).build();
	}

	/** A carried staff the task's runes come from, while no such staff is wielded; null otherwise. */
	private Integer staffToWield(Step step)
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		RuneModel runes = player.getRuneModel();
		if (step == null || step.getRequirements() == null || snapshot == null || runes == null)
		{
			return null;
		}
		Set<Integer> staves = runes.staves();
		List<ItemRequirement> needs = step.getRequirements().getItems();
		boolean wielding = snapshot.getWorn().keySet().stream()
			.anyMatch(id -> staves.contains(id) && needs.stream().anyMatch(need -> need.fulfilledBy(id)));
		return wielding ? null : snapshot.getInventory().keySet().stream()
			.filter(id -> staves.contains(id) && needs.stream().anyMatch(need -> need.fulfilledBy(id))).findFirst().orElse(null);
	}

	/** The furthest making step whose worked-on item is carried; null before any is, or for a task made of nothing. */
	private static StepIndex.Making makingNow(Step step, Set<Integer> carried)
	{
		List<StepIndex.Making> making = step == null ? List.of() : step.getData().getMaking();
		StepIndex.Making now = null;
		for (StepIndex.Making part : making)
		{
			if (!part.getItems().isEmpty() && part.getItems().get(0).stream().anyMatch(carried::contains))
			{
				now = part;
			}
		}
		return now;
	}
}
