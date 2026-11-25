package com.cluehelper.guide;

import com.cluehelper.memory.*;
import com.cluehelper.player.*;
import com.cluehelper.route.*;
import com.cluehelper.step.*;
import java.util.*;
import java.util.function.Predicate;
import javax.inject.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;
import static java.util.stream.Collectors.*;
import com.cluehelper.guide.StepGuide.Tab;

/**
 * A skill challenge's task act: the items it is done with, the making step reached, the spell cast on an item and the
 * interface option it picks. Client thread only.
 */
@Singleton
final class TaskActs
{
	private static final String RAKE = "Rake";
	private static final String SPADE = "Spade";
	/** The tools a planting task's seed is listed beside. */
	private static final Set<String> PATCH_TOOLS = Set.of(RAKE, SPADE, "Seed dibber");
	/** What a patch to plant in takes before the seed goes in, one line at a time. */
	private static final Map<Allotment, String> CLEARING_LINES = Map.of(Allotment.WEEDS, "Rake the weeds off the patch",
		Allotment.CROP, "Harvest the crop", Allotment.DEAD, "Clear the dead crop");
	private static final int[] PRODUCT_SCREENS = {InterfaceID.CraftingGold.UNIVERSE, InterfaceID.Smithing.UNIVERSE};

	@Inject
	Client client;
	@Inject
	SceneObjects sceneObjects;
	@Inject
	PlayerModel player;
	@Inject
	BankMemory bankMemory;
	@Inject
	StepNeeds stepNeeds;
	@Inject
	LeftClicks leftClicks;

	/** The items the task needs that the player carries, outlined (used on each other, not clicked once). */
	Highlights withItems(Highlights shown, SubStep current, Step step)
	{
		if (current == null || !current.isTask() || step == null)
		{
			return shown;
		}
		Set<Integer> carried = player.carriedIds();
		if (neededFromCarriedKit(step) != null)
		{
			return shown.toBuilder().items(Set.of(ItemID.HUNTSMANS_KIT)).tab(Tab.INVENTORY).build();
		}
		Integer staff = staffToWield(step);
		if (staff != null)
		{
			Set<Integer> wield = Set.of(staff);
			return shown.toBuilder().items(wield).fillItems(leftClicks.wears(staff) ? wield : Set.of()).tab(Tab.INVENTORY).build();
		}
		StepIndex.Cast cast = step.getData().getCast();
		if (cast != null && cast.getOn().stream().anyMatch(carried::contains))
		{
			return withCast(shown, cast, carried);
		}
		Set<Integer> forPatch = patchItems(step, carried);
		if (forPatch != null)
		{
			return outlined(shown, forPatch, Tab.INVENTORY);
		}
		StepIndex.Making making = makingNow(step, carried);
		if (current.getStage().getOption() != null && making == null)
		{
			return shown;
		}
		Set<Integer> items = carried.stream().filter(id -> making != null
				? making.getItems().stream().anyMatch(group -> group.contains(id)) : StepHighlights.isStepItem(step, id))
			.collect(toUnmodifiableSet());
		return outlined(shown, items, items.isEmpty() ? shown.getTab() : Tab.INVENTORY);
	}

	/** The items outlined, filled where their left click puts them on or uses them. */
	private Highlights outlined(Highlights shown, Set<Integer> items, Tab tab)
	{
		return shown.toBuilder().items(items).fillItems(items.stream().filter(leftClicks::wearsOrUses).collect(toUnmodifiableSet()))
			.tab(tab).build();
	}

	/**
	 * What to do first (switch book, take out of the kit, wield the staff, see to the patch), else the making step
	 * reached; null when none applies.
	 */
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
		StepIndex.Making making = makingNow(step, player.carriedIds());
		return making == null ? null : making.getText();
	}

	/** What the nearest patch takes next, as carried: the seed and dibber once clear, none while its option clears it. */
	private Set<Integer> patchItems(Step step, Set<Integer> carried)
	{
		return itemsFor(step, nearestPatch(step), carried);
	}

	private Set<Integer> itemsFor(Step step, Allotment patch, Set<Integer> carried)
	{
		if (patch == null)
		{
			return null;
		}
		if (patch != Allotment.EMPTY)
		{
			return Set.of();
		}
		return carried.stream().filter(id -> step.getRequirements().getItems().stream()
				.anyMatch(need -> need.fulfilledBy(id) && !RAKE.equals(need.getCollectiveName(client))))
			.collect(toUnmodifiableSet());
	}

	/**
	 * The carried item the patch to plant in takes next: the seed (not the dibber beside it) once empty; -1 for none, as
	 * while the patch's own option clears it.
	 */
	int itemForPatch(Step step, TileObject patch)
	{
		if (step == null || !step.getData().isPlants())
		{
			return -1;
		}
		Set<Integer> items = itemsFor(step, stateOf(patch), player.carriedIds());
		return items == null ? -1 : items.stream().min(Comparator.comparing(id -> isPatchTool(step, id))).orElse(-1);
	}

	private boolean isPatchTool(Step step, int itemId)
	{
		return step.getRequirements().getItems().stream()
			.anyMatch(need -> need.fulfilledBy(itemId) && PATCH_TOOLS.contains(need.getCollectiveName(client)));
	}

	/** The state of the patch to plant in; null for other tasks or with none in the scene. */
	private Allotment nearestPatch(Step step)
	{
		if (step == null || !step.getData().isPlants())
		{
			return null;
		}
		Set<Integer> ids = step.getData().getPlaces().stream().flatMap(place -> place.getObjects().stream()).collect(toSet());
		TileObject patch = patchToUse(step, sceneObjects.find(ids));
		return patch == null ? null : stateOf(patch);
	}

	/**
	 * Of a planting task's patches in sight, the one to plant in: an empty one before a weedy one before one with a grown
	 * or dead crop, as clearing takes a while, then the nearest. Null for other tasks or none that can be cleared now.
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

	/** Read from the form the patch shows now; null for a growing crop or an object that is no patch. */
	private Allotment stateOf(TileObject patch)
	{
		ObjectComposition patchObject = client.getObjectDefinition(patch.getId());
		ObjectComposition shown = patchObject.getImpostorIds() == null ? null : patchObject.getImpostor();
		return shown == null || patchObject.getVarbitId() < 0 ? null : Allotment.of(client.getVarbitValue(patchObject.getVarbitId()), shown);
	}

	/** An interface option the task picks and its confirm button, outlined while the interface is open. */
	List<Widget> choice(SubStep current, Step step)
	{
		Widget product = makeOption(products(current, step));
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

	/** The making step's product, else what the act makes: the item it is done once held (a black d'hide body). */
	private Set<Integer> products(SubStep current, Step step)
	{
		StepIndex.Making making = step == null ? null : makingNow(step, player.carriedIds());
		if (making != null && making.getMake() >= 0)
		{
			return Set.of(making.getMake());
		}
		return current != null && current.getDone() == SubStep.Done.ITEM_HELD ? current.getHeldWhenDone() : Set.of();
	}

	/**
	 * The product in the "How many do you wish to make?" box (its option, A to R), or in a screen that shows each product
	 * as its item: the furnace's jewellery, the anvil's smithing.
	 */
	private Widget makeOption(Set<Integer> products)
	{
		if (products.isEmpty())
		{
			return null;
		}
		for (int screen : PRODUCT_SCREENS)
		{
			if (Widgets.shown(client, screen))
			{
				return Widgets.descendants(client.getWidget(screen)).stream()
					.filter(shown -> Widgets.visible(shown) && products.contains(shown.getItemId())).findFirst().orElse(null);
			}
		}
		if (!Widgets.shown(client, InterfaceID.Skillmulti.UNIVERSE))
		{
			return null;
		}
		for (int id = InterfaceID.Skillmulti.A; id <= InterfaceID.Skillmulti.R; id++)
		{
			Widget option = client.getWidget(id);
			if (option != null && !option.isHidden()
				&& Widgets.descendants(option).stream().anyMatch(shown -> products.contains(shown.getItemId())))
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

	/**
	 * A task act, or the walk to it, read as its phase: a planting task's patch cleared first, else the first phase whose
	 * items are carried, else the task's own line.
	 */
	SubStep phased(SubStep act, Step step)
	{
		Stage planting = step == null || !step.getData().isPlants() ? null : act.isTask() ? act.getStage() : taskWalkedTo(act, step);
		if (planting != null)
		{
			Allotment patch = nearestPatch(step);
			boolean clearing = patch != null && CLEARING_LINES.containsKey(patch);
			String text = clearing ? CLEARING_LINES.get(patch) : planting.getAct();
			String option = !act.isTask() ? act.getOption() : clearing ? patch.getOption() : planting.getOption();
			return text.equals(act.getText()) && Objects.equals(option, act.getOption()) ? act : act.toBuilder().text(text).option(option).build();
		}
		Stage task = step == null || step.getData().getPhases().isEmpty() ? null : act.isTask() ? act.getStage() : taskWalkedTo(act, step);
		if (task == null)
		{
			return act;
		}
		Set<Integer> carried = player.carriedIds();
		StepIndex.Phase phase = step.getData().getPhases().stream()
			.filter(candidate -> candidate.getWhileCarried().stream().anyMatch(carried::contains)).findFirst().orElse(null);
		String text = phase == null ? task.getAct() : phase.getText();
		String option = phase == null || phase.getOption() == null ? task.getOption() : phase.getOption();
		return text.equals(act.getText()) && Objects.equals(option, act.getOption()) ? act : act.toBuilder().text(text).option(option).build();
	}

	/** The task a walk reads as (the walk to the pyre), by its line; null for any other sub-step. */
	private static Stage taskWalkedTo(SubStep walk, Step step)
	{
		if (walk.getKind() != SubStep.Kind.WALK)
		{
			return null;
		}
		Stage task = step.getStages().stream().filter(stage -> stage.getKind() == Stage.Kind.TASK).findFirst().orElse(null);
		boolean taskLine = task != null && (walk.getText().equals(task.getAct())
			|| step.getData().getPhases().stream().anyMatch(phase -> phase.getText().equals(walk.getText()))
			|| step.getData().isPlants() && CLEARING_LINES.containsValue(walk.getText()));
		return taskLine ? task : null;
	}

	/** The spell's button until it is picked (the last of its buttons shown), then the items it is cast on. */
	private Highlights withCast(Highlights shown, StepIndex.Cast cast, Set<Integer> carried)
	{
		boolean picked = client.isWidgetSelected();
		Set<Integer> items = picked ? carried.stream().filter(cast.getOn()::contains).collect(toUnmodifiableSet()) : Set.of();
		int spellWidget = picked ? -1 : cast.getWidgets().stream().filter(id -> Widgets.shown(client, id)).reduce((first, last) -> last)
			.orElse(cast.getWidgets().isEmpty() ? -1 : cast.getWidgets().get(0));
		return shown.toBuilder().items(items).fillItems(items).spellWidget(spellWidget)
			.tab(picked ? Tab.INVENTORY : Tab.MAGIC).build();
	}

	/** A carried staff the task's runes come from, while no such staff is wielded; null otherwise. */
	private Integer staffToWield(Step step)
	{
		PlayerSnapshot snapshot = player.getSnapshot();
		RuneModel runes = player.getRuneModel();
		if (step == null || snapshot == null || runes == null)
		{
			return null;
		}
		Set<Integer> staves = runes.staves();
		Predicate<Integer> stepStaff = id -> staves.contains(id) && StepHighlights.isStepItem(step, id);
		boolean wielding = snapshot.getWorn().keySet().stream().anyMatch(stepStaff);
		return wielding ? null : snapshot.getInventory().keySet().stream().filter(stepStaff).findFirst().orElse(null);
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
