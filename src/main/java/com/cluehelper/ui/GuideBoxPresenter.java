package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.StepGuide;
import com.cluehelper.hotcold.*;
import com.cluehelper.link.ClueLink;
import com.cluehelper.memory.BankMemory;
import com.cluehelper.player.*;
import com.cluehelper.poh.PohService;
import com.cluehelper.route.*;
import com.cluehelper.step.*;
import com.cluehelper.transport.AvailabilityService;
import javax.inject.*;

/** Client thread only. Reads the guide, the routes and the hot/cold planner into one box and hands it to the overlay. */
@Singleton
public class GuideBoxPresenter
{
	@Inject
	private ClueLink clueLink;
	@Inject
	private PlayerModel playerModel;
	@Inject
	private PlannerRunner planner;
	@Inject
	private RoutePlanner routes;
	@Inject
	private StepGuide guide;
	@Inject
	private HotColdService hotCold;
	@Inject
	private BankMemory bankMemory;
	@Inject
	private PohService poh;
	@Inject
	private AvailabilityService availability;
	@Inject
	private ClueHelperConfig config;
	@Inject
	private GuideOverlay overlay;

	public void refresh()
	{
		GuideBox box = GuideBox.of(boxState(), routes.getPlan(), guide.getRoute(), guide.getShown(),
			config.showNextStep() ? guide.getShownNext() : null, stuckBookOnF2p(), prompts());
		GuideBox atStep = guide.isAtStep() ? box.atTheStep() : box;
		GuideBox.GuideBoxBuilder shown = atStep.toBuilder();
		if (atStep.getState().showsRoutes())
		{
			shown.hotCold(hotColdView());
		}
		overlay.show(shown.build());
	}

	private BoxState boxState()
	{
		return BoxState.of(isDormant(), clueLink.isActive(), clueLink.needsRead(), clueLink.hasStep(), planner.isWorking());
	}

	private boolean isDormant()
	{
		PlayerSnapshot snapshot = playerModel.getSnapshot();
		return snapshot != null && snapshot.getDormantReason() != null;
	}

	private HotColdView hotColdView()
	{
		Step step = clueLink.getCurrentStep();
		if (step == null || !ClueTypes.HOT_COLD.equals(step.getType()) || !config.hotColdPlanner())
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
