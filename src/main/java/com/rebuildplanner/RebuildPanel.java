/*
 * Copyright (c) 2026, Alexb
 * All rights reserved.
 * Licensed under BSD 2-Clause. See LICENSE.
 */
package com.rebuildplanner;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.QuantityFormatter;

/** Side panel: pick a megarare and a way to earn it back, read the plan. */
public class RebuildPanel extends PluginPanel
{
	private static final String AUTO = "Best method for me";
	/** Text width that fits the sidebar without a horizontal scrollbar. */
	private static final int TEXT_WIDTH = 165;

	private static final String GOOD = "#6FCF6F";
	private static final String WARN = "#E6B82E";
	private static final String BAD = "#E06C6C";
	private static final String MUTED = "#9A9A9A";

	private final JComboBox<RebuildData.Goal> goalBox = new JComboBox<>();
	private final JComboBox<String> methodBox = new JComboBox<>();
	private final JLabel body = new JLabel();
	private final Runnable onChange;

	private List<Plan> plans = new ArrayList<>();
	private boolean bankSeen;
	/** True while the method list is being refilled, to ignore its events. */
	private boolean filling;

	public RebuildPanel(List<RebuildData.Goal> goals, Runnable onChange)
	{
		this.onChange = onChange;
		setLayout(new BorderLayout(0, 8));
		setBorder(new EmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JPanel top = new JPanel(new GridLayout(0, 1, 0, 4));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JLabel title = new JLabel("Rebuild Planner");
		title.setFont(FontManager.getRunescapeBoldFont());
		top.add(title);

		top.add(caption("Rebuild to"));
		for (RebuildData.Goal goal : goals)
		{
			goalBox.addItem(goal);
		}
		top.add(goalBox);

		top.add(caption("Earn it back at"));
		top.add(methodBox);
		add(top, BorderLayout.NORTH);

		body.setVerticalAlignment(SwingConstants.TOP);
		body.setFont(FontManager.getRunescapeSmallFont());
		add(body, BorderLayout.CENTER);

		fillMethods();
		goalBox.addActionListener(e ->
		{
			fillMethods();
			onChange.run();
		});
		methodBox.addActionListener(e ->
		{
			if (!filling)
			{
				render();
			}
		});

		showMessage("Log in and open your bank to plan a rebuild.");
	}

	private static JLabel caption(String text)
	{
		final JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return label;
	}

	private void fillMethods()
	{
		filling = true;
		methodBox.removeAllItems();
		methodBox.addItem(AUTO);
		final RebuildData.Goal goal = selectedGoal();
		if (goal != null)
		{
			for (RebuildData.Method method : goal.getMethods())
			{
				methodBox.addItem(method.getName());
			}
		}
		filling = false;
	}

	public RebuildData.Goal selectedGoal()
	{
		return (RebuildData.Goal) goalBox.getSelectedItem();
	}

	public void showMessage(String message)
	{
		body.setText(html("<p>" + esc(message) + "</p>"));
	}

	public void showPlans(RebuildData.Goal goal, List<Plan> plans, boolean bankSeen)
	{
		if (goal != selectedGoal())
		{
			return;
		}
		this.plans = plans;
		this.bankSeen = bankSeen;
		render();
	}

	/** The funded plan that earns the most; failing that, the closest one. */
	private Plan best()
	{
		Plan best = null;
		for (Plan plan : plans)
		{
			if (best == null
				|| (plan.isFunded() && !best.isFunded())
				|| (plan.isFunded() == best.isFunded() && (plan.isFunded()
					? plan.getEstGpPerHour() > best.getEstGpPerHour()
					: plan.getShortfall() < best.getShortfall())))
			{
				best = plan;
			}
		}
		return best;
	}

	private Plan selectedPlan()
	{
		final Object choice = methodBox.getSelectedItem();
		if (choice == null || AUTO.equals(choice))
		{
			return best();
		}
		for (Plan plan : plans)
		{
			if (plan.getMethod().getName().equals(choice))
			{
				return plan;
			}
		}
		return best();
	}

	private static String gp(long amount)
	{
		return QuantityFormatter.quantityToStackSize(amount);
	}

	private static String esc(String text)
	{
		return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;");
	}

	private static String html(String inner)
	{
		return "<html><body style='width:" + TEXT_WIDTH + "px'>" + inner + "</body></html>";
	}

	private static String heading(String text)
	{
		return "<p style='margin-top:8px'><b>" + esc(text) + "</b></p>";
	}

	private static String colour(String hex, String text)
	{
		return "<font color='" + hex + "'>" + text + "</font>";
	}

	private void render()
	{
		final Plan plan = selectedPlan();
		if (plan == null)
		{
			showMessage("No plan available for this item.");
			return;
		}

		final StringBuilder out = new StringBuilder();
		final RebuildData.Method method = plan.getMethod();

		if (!bankSeen)
		{
			out.append("<p>").append(colour(WARN,
				"Open your bank once so it can be counted. Until then this only sees your inventory and worn gear."))
				.append("</p>");
		}
		if (plan.isAlreadyOwned())
		{
			out.append("<p>").append(colour(GOOD, "You already own a " + esc(plan.getGoal().getName())
				+ ". The plan below is what a rebuild would look like.")).append("</p>");
		}

		// ---- Verdict ----
		out.append(heading(plan.getGoal().getName() + " via " + method.getName()));
		out.append("<p>Price: ").append(gp(plan.getGoalPrice())).append("<br>");
		out.append("Your coins: ").append(gp(plan.getCoins())).append("<br>");
		if (plan.getBuyCost() > 0)
		{
			out.append("Gear to buy: ").append(gp(plan.getBuyCost())).append("<br>");
		}
		out.append("To raise: ").append(gp(plan.getNeeded())).append("</p>");

		if (plan.getNeeded() <= 0)
		{
			out.append("<p>").append(colour(GOOD, "You can buy it with your coins. Nothing needs selling."))
				.append("</p>");
		}
		else if (plan.isFunded())
		{
			out.append("<p>").append(colour(GOOD, "Affordable: the sales below raise "
				+ gp(plan.getRaised()) + ".")).append("</p>");
		}
		else
		{
			out.append("<p>").append(colour(BAD, "Short by " + gp(plan.getShortfall())
				+ " even after everything below. Selling more would cost too much damage."))
				.append("</p>");
		}

		// ---- Money back ----
		out.append(heading("Earning it back"));
		out.append("<p>Wiki rate: ").append(gp(method.getGpPerHour())).append("/hr<br>");
		out.append("With your kit: about ").append(gp(plan.getEstGpPerHour())).append("/hr ")
			.append(colour(MUTED, "(" + Math.round(plan.getDpsVersusBest() * 100) + "% of best-in-slot damage)"))
			.append("</p>");
		if (plan.getKitSold() > 0 && plan.getEstGpPerHour() > 0)
		{
			out.append("<p>Rebuying the gear you sold: about ")
				.append(Math.round((double) plan.getKitSold() / plan.getEstGpPerHour()))
				.append(" hours.</p>");
		}
		if (method.getNote() != null)
		{
			out.append("<p>").append(colour(MUTED, esc(method.getNote()))).append("</p>");
		}

		// ---- Sell ----
		long optional = 0;
		final StringBuilder sell = new StringBuilder();
		for (Plan.Sale sale : plan.getSales())
		{
			if (!sale.isNeeded())
			{
				optional += sale.getProceeds();
				continue;
			}
			sell.append(esc(sale.getName()));
			if (sale.getQuantity() > 1 || sale.getKept() > 0)
			{
				sell.append(" x ").append(gp(sale.getQuantity()));
			}
			sell.append(" &ndash; ").append(gp(sale.getProceeds()));
			if (sale.getKept() > 0)
			{
				sell.append(colour(MUTED, " (keep " + gp(sale.getKept()) + ")"));
			}
			sell.append("<br>");
		}
		if (sell.length() > 0)
		{
			out.append(heading("Sell from your bank"));
			out.append("<p>").append(sell).append("</p>");
			out.append("<p>").append(colour(MUTED, "After the 2% Grand Exchange tax.")).append("</p>");
		}
		if (optional > 0)
		{
			out.append("<p>").append(colour(MUTED, "Another " + gp(optional)
				+ " of spare items is not needed and stays in the bank.")).append("</p>");
		}

		// ---- Swaps ----
		if (!plan.getSwaps().isEmpty())
		{
			out.append(heading("Sell and swap down"));
			out.append("<p>");
			for (Plan.Swap swap : plan.getSwaps())
			{
				out.append("Sell ").append(esc(swap.getSell())).append(", use ")
					.append(esc(swap.getUse()))
					.append(swap.isUseOwned() ? "" : colour(MUTED, " (buy)"))
					.append(" &ndash; frees ").append(gp(swap.getFreed()));
				if (swap.getLossPct() >= 0.05)
				{
					out.append(colour(WARN, String.format(" (-%.1f%% damage)", swap.getLossPct())));
				}
				out.append(colour(MUTED, " [" + esc(swap.getRole()) + "]")).append("<br>");
			}
			out.append("</p>");
			if (plan.getDpsLossPct() >= 0.05)
			{
				out.append("<p>Total damage given up: ")
					.append(String.format("%.1f%%", plan.getDpsLossPct())).append("</p>");
			}
		}

		// ---- Buy ----
		if (!plan.getBuys().isEmpty())
		{
			out.append(heading("Buy for the kit"));
			out.append("<p>");
			for (Plan.Buy buy : plan.getBuys())
			{
				out.append(esc(buy.getName())).append(" &ndash; ").append(gp(buy.getCost()))
					.append(colour(MUTED, " [" + esc(buy.getRole()) + "]")).append("<br>");
			}
			out.append("</p>");
		}

		// ---- Keep ----
		final Map<String, StringBuilder> byRole = new LinkedHashMap<>();
		for (Plan.Keep keep : plan.getKeeps())
		{
			byRole.computeIfAbsent(keep.getRole(), k -> new StringBuilder())
				.append(esc(keep.getName())).append("<br>");
		}
		if (!byRole.isEmpty())
		{
			out.append(heading("Keep - do not sell"));
			for (Map.Entry<String, StringBuilder> entry : byRole.entrySet())
			{
				out.append("<p>").append(colour(MUTED, esc(entry.getKey()))).append("<br>")
					.append(entry.getValue()).append("</p>");
			}
		}

		body.setText(html(out.toString()));
	}
}
