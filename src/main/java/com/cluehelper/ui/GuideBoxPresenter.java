package com.cluehelper.ui;

import com.cluehelper.ClueHelperConfig;
import com.cluehelper.guide.StepGuide;
import com.cluehelper.hotcold.HotColdPlan;
import com.cluehelper.hotcold.HotColdService;
import com.cluehelper.hotcold.HotColdView;
import com.cluehelper.link.ClueLink;
import com.cluehelper.memory.BankMemory;
import com.cluehelper.player.Book;
import com.cluehelper.player.HouseState;
import com.cluehelper.player.PlayerModel;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.poh.PohService;
import com.cluehelper.route.PlannerRunner;
import com.cluehelper.route.RoutePlanner;
import com.cluehelper.step.ClueTypes;
import com.cluehelper.step.HotColdTier;
import com.cluehelper.step.Step;
import com.cluehelper.transport.AvailabilityService;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Client thread only. Reads the guide, the routes and the hot/cold planner into one box and hands it to the overlay. */
@Singleton
public class GuideBoxPresenter
{
	private final ClueLink clueLink;
	private final PlayerModel playerModel;
	private final PlannerRunner planner;
	private final RoutePlanner routes;
	private final StepGuide guide;
	private final HotColdService hotCold;
	private final BankMemory bankMemory;
	private final PohService poh;
	private final AvailabilityService availability;
	private final ClueHelperConfig config;
	private final GuideOverlay overlay;

	@Inject
	GuideBoxPresenter(ClueLink clueLink, PlayerModel playerModel, PlannerRunner planner, RoutePlanner routes, StepGuide guide,
		HotColdService hotCold, BankMemory bankMemory, PohService poh, AvailabilityService availability, ClueHelperConfig config,
		GuideOverlay overlay)
	{
		this.clueLink = clueLink;
		this.playerModel = playerModel;
		this.planner = planner;
		this.routes = routes;
		this.guide = guide;
		this.hotCold = hotCold;
		this.bankMemory = bankMemory;
		this.poh = poh;
		this.availability = availability;
		this.config = config;
		this.overlay = overlay;
	}

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

	public BoxState boxState()
	{
		return BoxState.of(dormantReason() != null, clueLink.isActive(), clueLink.needsRead(), clueLink.hasStep(), planner.isWorking());
	}

	private String dormantReason()
	{
		PlayerSnapshot snapshot = playerModel.getSnapshot();
		return snapshot == null ? null : snapshot.getDormantReason();
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
