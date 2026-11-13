/*
 * Copyright (c) 2026, Alexb
 * All rights reserved.
 * Licensed under BSD 2-Clause. See LICENSE.
 */
package com.rebuildplanner;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Gear Upgrade: Rebuild Planner",
	description = "Pick a megarare and see what to sell from your bank to afford it, while keeping the gear you need to earn the money back",
	tags = {"rebuild", "megarare", "bank", "sell", "twisted bow", "shadow", "scythe", "money"}
)
public class RebuildPlannerPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	@Inject
	private RebuildPlannerConfig config;

	@Inject
	private Holdings holdings;

	@Inject
	private Gson gson;

	@Inject
	private ConfigManager configManager;

	/** The player's own ✕s and amounts; saved so they survive a restart. */
	private Overrides overrides = new Overrides();
	private static final String OVERRIDES_KEY = "overrides";
	private Map<String, List<RebuildData.Component>> components = java.util.Collections.emptyMap();

	private List<RebuildData.Goal> goals;
	private Planner planner;
	private RebuildPanel panel;
	private NavigationButton navButton;

	/** Set when holdings change; the plan is rebuilt once on the next tick. */
	private boolean dirty;
	/** Set when potion storage may have changed; it is re-read on the next tick. */
	private boolean potionsDirty;

	@Override
	protected void startUp()
	{
		goals = RebuildData.loadGoals(gson);
		final Map<String, List<Integer>> variants = RebuildData.loadVariants(gson);
		components = RebuildData.loadComponents(gson);
		planner = new Planner(new ClientItemInfo(), variants);

		overrides = loadOverrides();
		saved = loadSaved();
		activePlan = configManager.getConfiguration(RebuildPlannerConfig.GROUP, ACTIVE_KEY);
		panel = new RebuildPanel(goals, itemManager, new PanelActions());
		final SavedPlan active = find(activePlan);
		if (active == null)
		{
			activePlan = null;
		}
		else
		{
			panel.select(active.getGoal(), active.getMethod());
		}
		pushPlans();

		navButton = NavigationButton.builder()
			.tooltip("Gear Upgrade: Rebuild Planner")
			.icon(icon())
			// The sidebar sorts by priority, then tooltip. Sharing Gear Upgrade's
			// priority and starting with its name keeps this directly beneath it.
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(this::readAll);
		}
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		navButton = null;
		panel = null;
		holdings.clear();
	}

	private static BufferedImage icon()
	{
		return ImageUtil.resizeImage(
			ImageUtil.loadImageResource(RebuildPlannerPlugin.class, "icon.png"), 16, 16);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(this::readAll);
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			holdings.clear();
			dirty = true;
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		final int id = event.getContainerId();
		if (id == InventoryID.BANK || id == InventoryID.INV || id == InventoryID.WORN)
		{
			holdings.refresh(id);
			dirty = true;
			if (id == InventoryID.BANK)
			{
				potionsDirty = true;
			}
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		// Reading potion storage runs client scripts, which cannot be started from
		// inside an event a script fired. Flag it and read on the next tick.
		if (holdings.isBankSeen() && Holdings.isPotionStoreVarp(event.getVarpId()))
		{
			potionsDirty = true;
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (RebuildPlannerConfig.GROUP.equals(event.getGroup()))
		{
			dirty = true;
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (potionsDirty)
		{
			potionsDirty = false;
			try
			{
				holdings.refreshPotionStore();
			}
			catch (RuntimeException | AssertionError e)
			{
				log.debug("Could not read potion storage", e);
			}
			dirty = true;
		}
		if (dirty)
		{
			dirty = false;
			recompute();
		}
	}

	private void readAll()
	{
		holdings.refresh(InventoryID.BANK);
		holdings.refresh(InventoryID.INV);
		holdings.refresh(InventoryID.WORN);
		recompute();
	}

	/** Called from the panel when the goal or method selection changes. */
	private void requestPlan()
	{
		clientThread.invokeLater(this::recompute);
	}

	private void recompute()
	{
		final RebuildPanel target = panel;
		if (target == null || goals == null || goals.isEmpty())
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			SwingUtilities.invokeLater(target::showLoggedOut);
			return;
		}

		final RebuildData.Goal goal = target.selectedGoal();
		if (goal == null)
		{
			return;
		}

		final Map<Integer, Long> owned = holdings.totals();
		final long coins = owned.getOrDefault(Planner.COINS, 0L)
			+ owned.getOrDefault(Planner.PLATINUM_TOKEN, 0L) * 1000L;
		final DpsModel.Levels levels = new DpsModel.Levels(
			client.getRealSkillLevel(Skill.ATTACK), client.getRealSkillLevel(Skill.STRENGTH),
			client.getRealSkillLevel(Skill.RANGED), client.getRealSkillLevel(Skill.MAGIC));
		final Planner.Settings settings = new Planner.Settings(
			config.minStackValue(), config.supplyReserve(), config.maxDpsLoss(), config.maxSwitchLoss(),
			config.minGain());

		final List<Plan> plans = new ArrayList<>();
		for (RebuildData.Method method : goal.getMethods())
		{
			try
			{
				plans.add(planner.plan(goal, method, owned, coins, levels, settings, overrides));
			}
			catch (RuntimeException e)
			{
				log.warn("Could not plan {} via {}", goal.getName(), method.getName(), e);
			}
		}

		final boolean bankSeen = holdings.isBankSeen();
		final boolean changed = !overrides.isEmpty();
		SwingUtilities.invokeLater(() -> target.showPlans(goal, plans, bankSeen, changed));
	}

	// ---- Saved plans ---------------------------------------------------------

	private static final String SAVED_KEY = "savedPlans";
	private static final String ACTIVE_KEY = "activePlan";
	private List<SavedPlan> saved = new ArrayList<>();
	/** Name of the saved plan being worked on, or null for a new one. */
	private String activePlan;

	private List<SavedPlan> loadSaved()
	{
		final String json = configManager.getConfiguration(RebuildPlannerConfig.GROUP, SAVED_KEY);
		final List<SavedPlan> out = new ArrayList<>();
		if (json == null || json.isEmpty())
		{
			return out;
		}
		try
		{
			final SavedPlan[] loaded = gson.fromJson(json, SavedPlan[].class);
			if (loaded != null)
			{
				for (SavedPlan plan : loaded)
				{
					if (plan != null && plan.getName() != null && plan.getGoal() != null)
					{
						plan.setOverrides(filled(plan.getOverrides()));
						out.add(plan);
					}
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("Discarding unreadable saved rebuild plans", e);
		}
		return out;
	}

	private SavedPlan find(String name)
	{
		if (name != null)
		{
			for (SavedPlan plan : saved)
			{
				if (name.equals(plan.getName()))
				{
					return plan;
				}
			}
		}
		return null;
	}

	/** Writes the saved plans and which one is open, and refreshes the panel's list. */
	private void persistSaved()
	{
		if (saved.isEmpty())
		{
			configManager.unsetConfiguration(RebuildPlannerConfig.GROUP, SAVED_KEY);
		}
		else
		{
			configManager.setConfiguration(RebuildPlannerConfig.GROUP, SAVED_KEY, gson.toJson(saved));
		}
		if (activePlan == null)
		{
			configManager.unsetConfiguration(RebuildPlannerConfig.GROUP, ACTIVE_KEY);
		}
		else
		{
			configManager.setConfiguration(RebuildPlannerConfig.GROUP, ACTIVE_KEY, activePlan);
		}
		pushPlans();
	}

	private void pushPlans()
	{
		final RebuildPanel target = panel;
		if (target == null)
		{
			return;
		}
		final List<String> names = new ArrayList<>();
		for (SavedPlan plan : saved)
		{
			names.add(plan.getName());
		}
		target.setSavedPlans(names, activePlan);
	}

	/** Never-null collections, whatever an older save left out. */
	private static Overrides filled(Overrides loaded)
	{
		if (loaded == null)
		{
			return new Overrides();
		}
		if (loaded.getExcluded() == null)
		{
			loaded.setExcluded(new java.util.HashSet<>());
		}
		if (loaded.getSellQuantity() == null)
		{
			loaded.setSellQuantity(new java.util.HashMap<>());
		}
		if (loaded.getForceSell() == null)
		{
			loaded.setForceSell(new java.util.HashSet<>());
		}
		if (loaded.getReplacement() == null)
		{
			loaded.setReplacement(new java.util.HashMap<>());
		}
		return loaded;
	}

	private Overrides copy(Overrides source)
	{
		return filled(gson.fromJson(gson.toJson(source), Overrides.class));
	}

	private Overrides loadOverrides()
	{
		final String json = configManager.getConfiguration(RebuildPlannerConfig.GROUP, OVERRIDES_KEY);
		if (json == null || json.isEmpty())
		{
			return new Overrides();
		}
		try
		{
			return filled(gson.fromJson(json, Overrides.class));
		}
		catch (RuntimeException e)
		{
			log.debug("Discarding unreadable rebuild overrides", e);
			return new Overrides();
		}
	}

	/** Saves and replans. Runs on the Swing thread; planning moves to the client thread. */
	private void overridesChanged()
	{
		if (overrides.isEmpty())
		{
			configManager.unsetConfiguration(RebuildPlannerConfig.GROUP, OVERRIDES_KEY);
		}
		else
		{
			configManager.setConfiguration(RebuildPlannerConfig.GROUP, OVERRIDES_KEY, gson.toJson(overrides));
		}
		requestPlan();
	}

	/** The panel's buttons and fields. */
	private final class PanelActions implements RebuildPanel.Actions
	{
		@Override
		public void changed()
		{
			// A different megarare is a fresh start: choices made for the last one
			// would silently skew this plan. It is no longer the saved plan either.
			if (activePlan != null)
			{
				activePlan = null;
				persistSaved();
			}
			if (!overrides.isEmpty())
			{
				overrides = new Overrides();
				overridesChanged();
				return;
			}
			requestPlan();
		}

		@Override
		public void exclude(int itemId)
		{
			// Crossing out something the player added themselves just takes it back off the list.
			if (!overrides.getForceSell().remove(itemId))
			{
				overrides.getExcluded().add(itemId);
			}
			overrides.getSellQuantity().remove(itemId);
			overridesChanged();
		}

		@Override
		public void restore(int itemId)
		{
			overrides.getExcluded().remove(itemId);
			overridesChanged();
		}

		@Override
		public void setQuantity(int itemId, long quantity)
		{
			overrides.getSellQuantity().put(itemId, quantity);
			overridesChanged();
		}

		@Override
		public void clearQuantity(int itemId)
		{
			overrides.getSellQuantity().remove(itemId);
			overridesChanged();
		}

		@Override
		public void savePlan(String name, String goal, String method)
		{
			final SavedPlan existing = find(name);
			final SavedPlan plan = new SavedPlan(name, goal, method, copy(overrides));
			if (existing == null)
			{
				saved.add(plan);
			}
			else
			{
				saved.set(saved.indexOf(existing), plan);
			}
			activePlan = name;
			persistSaved();
		}

		@Override
		public void loadPlan(String name)
		{
			final SavedPlan plan = find(name);
			final RebuildPanel target = panel;
			if (plan == null || target == null)
			{
				return;
			}
			activePlan = name;
			overrides = copy(plan.getOverrides());
			target.select(plan.getGoal(), plan.getMethod());
			persistSaved();
			overridesChanged();
		}

		@Override
		public void newPlan()
		{
			activePlan = null;
			overrides = new Overrides();
			persistSaved();
			overridesChanged();
		}

		@Override
		public void deletePlan(String name)
		{
			final SavedPlan plan = find(name);
			if (plan == null)
			{
				return;
			}
			saved.remove(plan);
			if (name.equals(activePlan))
			{
				activePlan = null;
			}
			persistSaved();
		}

		@Override
		public void addSale(int itemId)
		{
			overrides.getExcluded().remove(itemId);
			overrides.getForceSell().add(itemId);
			overridesChanged();
		}

		@Override
		public void setReplacement(int soldId, int useId)
		{
			overrides.getReplacement().put(soldId, useId);
			overridesChanged();
		}

		@Override
		public void clearReplacement(int soldId)
		{
			overrides.getReplacement().remove(soldId);
			overridesChanged();
		}

		@Override
		public void resetAll()
		{
			overrides = new Overrides();
			overridesChanged();
		}
	}

	@Provides
	RebuildPlannerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RebuildPlannerConfig.class);
	}

	/** Item facts straight from the client; only ever called on the client thread. */
	private final class ClientItemInfo implements ItemInfo
	{
		@Override
		public String name(int itemId)
		{
			final ItemComposition composition = itemManager.getItemComposition(itemId);
			return composition.getName();
		}

		@Override
		public long price(int itemId)
		{
			return itemId <= 0 ? 0 : itemManager.getItemPrice(itemId);
		}

		@Override
		public boolean tradeable(int itemId)
		{
			return itemManager.getItemComposition(itemId).isGeTradeable();
		}

		@Override
		public long buyPrice(int itemId)
		{
			if (tradeable(itemId))
			{
				return price(itemId);
			}
			// Made, not bought: a Neitiznot faceguard is a helm plus a basilisk jaw.
			final List<RebuildData.Component> parts = components.get(String.valueOf(itemId));
			if (parts == null)
			{
				return 0;
			}
			long total = 0;
			for (RebuildData.Component part : parts)
			{
				final long each = price(part.getId());
				if (each <= 0)
				{
					return 0;
				}
				total += each * Math.max(1, part.getQty());
			}
			return total;
		}

		@Override
		public Stats stats(int itemId)
		{
			final ItemStats stats = itemManager.getItemStats(itemId);
			final ItemEquipmentStats e = stats == null ? null : stats.getEquipment();
			if (e == null)
			{
				return null;
			}
			return new Stats(e.getAstab(), e.getAslash(), e.getAcrush(), e.getAmagic(), e.getArange(),
				e.getStr(), e.getRstr(), e.getMdmg(), e.getAspeed(), e.isTwoHanded());
		}
	}
}
