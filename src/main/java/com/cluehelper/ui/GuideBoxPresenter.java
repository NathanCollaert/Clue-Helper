package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.*;
import com.cluehelper.hotcold.*;
import com.cluehelper.link.*;
import com.cluehelper.memory.BankMemory;
import com.cluehelper.player.*;
import com.cluehelper.poh.PohService;
import com.cluehelper.route.*;
import com.cluehelper.step.*;
import com.cluehelper.transport.AvailabilityService;
import java.util.*;
import javax.inject.*;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;

/** Client thread only. Reads the guide, the routes and the hot/cold planner into one box and hands it to the overlay. */
@Singleton
public class GuideBoxPresenter
{
	@Inject
	ClueLink clueLink;
	@Inject
	ThreeStepSwap threeStepSwap;
	@Inject
	PlayerModel playerModel;
	@Inject
	PlannerRunner planner;
	@Inject
	RoutePlanner routes;
	@Inject
	StepGuide guide;
	@Inject
	HotColdService hotCold;
	@Inject
	BankMemory bankMemory;
	@Inject
	PohService poh;
	@Inject
	AvailabilityService availability;
	@Inject
	ClueHelperConfig config;
	@Inject
	GuideOverlay overlay;

	public void refresh()
	{
		GuideBox box = GuideBox.of(boxState(), routes.getPlan(), guide.getRoute(), guide.getCurrent(),
			config.showNextStep() ? guide.getNext() : null, stuckBookOnF2p(), prompts());
		GuideBox atStep = guide.isAtStep() ? box.atTheStep() : box;
		List<String> notes = new ArrayList<>(atStep.getNotes());
		notes.addAll(threeStepSwap.notes());
		String prayerWarning = guide.getPrayerWarning();
		if (prayerWarning != null)
		{
			notes.add(prayerWarning);
		}
		GuideBox.GuideBoxBuilder shown = atStep.toBuilder().headline(threeStepSwap.headline()).notes(List.copyOf(notes));
		if (repeatsCoreSolution(atStep.getNowDetail()))
		{
			shown.nowDetail(null);
		}
		if (atStep.getState().showsRoutes())
		{
			shown.hotCold(hotColdView());
		}
		overlay.show(shown.build());
	}

	/** The step's detail can be core's solution word for word, which the Clue Helper box leaves to core. */
	private boolean repeatsCoreSolution(String detail)
	{
		ClueScrollPlugin core = clueLink.getCore();
		return detail != null && core != null && detail.equals(CoreClueReader.solution(clueLink.coreClue(), core));
	}

	private BoxState boxState()
	{
		return BoxState.of(isDormant(), clueLink.isActive(), clueLink.hasStep(), guide.owesStashPutBack(),
			planner.isWorking());
	}

	private boolean isDormant()
	{
		PlayerSnapshot snapshot = playerModel.getSnapshot();
		return snapshot != null && snapshot.getDormantReason() != null;
	}

	private HotColdView hotColdView()
	{
		Step step = clueLink.getCurrentStep();
		if (step == null || !ClueTypes.HOT_COLD.equals(step.getType()))
		{
			return null;
		}
		HotColdPlan plan = hotCold.getPlan();
		if (plan == null || !plan.getStepKey().equals(step.getKey()))
		{
			return null;
		}
		HotColdTier tier = HotColdTier.of(step.getTier());
		return HotColdView.of(new HotColdView.Inputs(plan, hotCold.spotsOf(tier), tier, hotCold.hitpoints()));
	}

	private GuideBox.Prompts prompts()
	{
		PlayerSnapshot snapshot = playerModel.getSnapshot();
		boolean bankCounts = snapshot != null && availability.bankCounts(snapshot);
		HouseState house = poh.getHouse();
		boolean houseKnown = !house.getNexusKeys().isEmpty() || !house.getFurniture().isEmpty();
		boolean onlyNexusVarbitsKnown = houseKnown && house.getSource() == HouseState.Source.VARBITS;
		return new GuideBox.Prompts(bankMemory.getBank() == null && bankCounts, onlyNexusVarbitsKnown);
	}

	/** Members keep their book on an F2P world, where no book can be changed and only Standard has teleports. */
	private Book stuckBookOnF2p()
	{
		PlayerSnapshot snapshot = playerModel.getSnapshot();
		if (snapshot == null || snapshot.isMembersWorld() || snapshot.getSpellbook() == null)
		{
			return null;
		}
		Book book = snapshot.getSpellbook().getBook();
		return book == null || book == Book.STANDARD ? null : book;
	}
}
