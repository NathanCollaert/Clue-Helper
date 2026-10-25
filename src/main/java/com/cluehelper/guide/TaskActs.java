package com.cluehelper.guide;

import com.cluehelper.memory.BankMemory;
import com.cluehelper.player.PlayerModel;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.route.RequiredItems;
import com.cluehelper.route.StepNeeds;
import com.cluehelper.step.Step;
import com.cluehelper.step.StepIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/**
 * A skill challenge's task act: the items it is done with, the making step reached, the spell cast on an item and the
 * interface option it picks. Client thread only.
 */
@Singleton
final class TaskActs
{
	private final Client client;
	private final PlayerModel player;
	private final BankMemory bankMemory;
	private final StepNeeds stepNeeds;
	private final LeftClicks leftClicks;

	@Inject
	TaskActs(Client client, PlayerModel player, BankMemory bankMemory, StepNeeds stepNeeds, LeftClicks leftClicks)
	{
		this.client = client;
		this.player = player;
		this.bankMemory = bankMemory;
		this.stepNeeds = stepNeeds;
		this.leftClicks = leftClicks;
	}

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
		StepIndex.Cast cast = step.getData().getCast();
		if (cast != null && cast.getOn().stream().anyMatch(carried::contains))
		{
			return withCast(shown, cast, carried);
		}
		if (current.getStage().getOption() != null && makingNow(step, carried) == null)
		{
			return shown;
		}
		StepIndex.Making making = makingNow(step, carried);
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
		StepIndex.Making making = makingNow(step, player.carriedIds());
		return making == null ? null : making.getText();
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
		if (parent.getText() != null && Text.removeTags(parent.getText()).equalsIgnoreCase(text))
		{
			return parent;
		}
		for (Widget[] children : new Widget[][]{parent.getStaticChildren(), parent.getDynamicChildren(), parent.getNestedChildren()})
		{
			for (Widget child : children == null ? new Widget[0] : children)
			{
				Widget found = child.isHidden() ? null : withText(child, text);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
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
		int spellWidget = picked ? -1 : cast.getWidgets().stream().filter(this::widgetShown).reduce((first, last) -> last)
			.orElse(cast.getWidgets().isEmpty() ? -1 : cast.getWidgets().get(0));
		return shown.toBuilder().items(items).fillItems(items).spellWidget(spellWidget)
			.tab(picked ? StepGuide.Tab.INVENTORY : StepGuide.Tab.MAGIC).build();
	}

	private boolean widgetShown(int widgetId)
	{
		Widget widget = client.getWidget(widgetId);
		return widget != null && !widget.isHidden();
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
