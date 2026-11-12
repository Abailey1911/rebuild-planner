/*
 * Copyright (c) 2026, Alexb
 * All rights reserved.
 * Licensed under BSD 2-Clause. See LICENSE.
 */
package com.rebuildplanner;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.text.View;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.QuantityFormatter;

/** Side panel: pick a megarare and a way to earn it back, then read the plan. */
public class RebuildPanel extends PluginPanel
{
	/** What the panel asks the plugin to do. */
	public interface Actions
	{
		/** The goal choice changed; plan again. */
		void changed();

		void exclude(int itemId);

		void restore(int itemId);

		void setQuantity(int itemId, long quantity);

		void clearQuantity(int itemId);

		void resetAll();
	}

	private static final String AUTO = "Best method for me";

	/** Usable width inside the sidebar's padding. */
	private static final int INNER = PluginPanel.PANEL_WIDTH - 16;
	/** Width of a row's text, beside a 32px icon. */
	private static final int ROW_TEXT = INNER - 32 - 8 - 16;

	private static final Color CARD = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color GOOD = new Color(0x6F, 0xCF, 0x6F);
	private static final Color WARN = new Color(0xE6, 0xB8, 0x2E);
	private static final Color BAD = new Color(0xE0, 0x6C, 0x6C);
	private static final Color MUTED = new Color(0x9A, 0x9A, 0x9A);

	private final ItemManager itemManager;
	private final Actions actions;
	/** True when the player has excluded items or changed amounts. */
	private boolean hasOverrides;
	private final JComboBox<RebuildData.Goal> goalBox = new JComboBox<>();
	private final JComboBox<String> methodBox = new JComboBox<>();
	private final JLabel goalIcon = new JLabel();
	private final JLabel goalName = new JLabel();
	private final JPanel content = new JPanel();

	private List<Plan> plans = new ArrayList<>();
	private boolean bankSeen;
	/** True while the method list is being refilled, to ignore its events. */
	private boolean filling;

	public RebuildPanel(List<RebuildData.Goal> goals, ItemManager itemManager, Actions actions)
	{
		this.itemManager = itemManager;
		this.actions = actions;
		setLayout(new BorderLayout(0, 8));
		setBorder(new EmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JPanel top = column();

		// Big picture of the goal, with its name beside it.
		final JPanel hero = new JPanel(new BorderLayout(8, 0));
		hero.setBackground(CARD);
		hero.setBorder(new EmptyBorder(6, 6, 6, 6));
		goalIcon.setPreferredSize(new Dimension(36, 32));
		hero.add(goalIcon, BorderLayout.WEST);
		final JPanel heroText = column();
		heroText.setBackground(CARD);
		final JLabel title = new JLabel("Rebuild Planner");
		title.setFont(FontManager.getRunescapeSmallFont());
		title.setForeground(MUTED);
		goalName.setFont(FontManager.getRunescapeBoldFont());
		goalName.setForeground(Color.WHITE);
		heroText.add(title);
		heroText.add(goalName);
		hero.add(heroText, BorderLayout.CENTER);
		top.add(hero);
		top.add(Box.createVerticalStrut(6));

		top.add(caption("Rebuild to"));
		for (RebuildData.Goal goal : goals)
		{
			goalBox.addItem(goal);
		}
		top.add(goalBox);
		top.add(Box.createVerticalStrut(4));
		top.add(caption("Earn it back at"));
		top.add(methodBox);
		add(top, BorderLayout.NORTH);

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(content, BorderLayout.CENTER);

		fillMethods();
		updateHero();
		goalBox.addActionListener(e ->
		{
			fillMethods();
			updateHero();
			actions.changed();
		});
		methodBox.addActionListener(e ->
		{
			if (!filling)
			{
				render();
			}
		});

		showLoggedOut();
	}

	// ---- Building blocks -------------------------------------------------

	private static JPanel column()
	{
		final JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		return panel;
	}

	private static <T extends JComponent> T left(T component)
	{
		component.setAlignmentX(Component.LEFT_ALIGNMENT);
		return component;
	}

	private static JLabel caption(String text)
	{
		final JLabel label = left(new JLabel(text));
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return label;
	}

	/**
	 * HTML text that wraps to whatever width the layout hands it.
	 *
	 * <p>A fixed pixel width in the HTML clips, because the space left inside
	 * the sidebar after its scrollbar and padding is not worth guessing at -
	 * Gear Upgrade learned this twice. This reports a preferred width of 1, so
	 * it can never widen the panel, and a height measured at its real width.
	 */
	private static final class WrappingHtml extends JEditorPane
	{
		WrappingHtml(String html, Color colour)
		{
			super("text/html", "<html><body>" + html + "</body></html>");
			putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
			setFont(FontManager.getRunescapeSmallFont());
			setForeground(colour);
			setEditable(false);
			setFocusable(false);
			setOpaque(false);
			setBorder(null);
			setHighlighter(null);
			// Height depends on width, so re-measure once the real width is known.
			addComponentListener(new ComponentAdapter()
			{
				private int lastWidth = -1;

				@Override
				public void componentResized(ComponentEvent e)
				{
					if (getWidth() != lastWidth)
					{
						lastWidth = getWidth();
						revalidate();
					}
				}
			});
		}

		@Override
		public Dimension getPreferredSize()
		{
			final int width = getWidth() > 0 ? getWidth() : PluginPanel.PANEL_WIDTH - 60;
			final View root = getUI().getRootView(this);
			root.setSize(width, Integer.MAX_VALUE);
			return new Dimension(1, (int) Math.ceil(root.getPreferredSpan(View.Y_AXIS)));
		}

		@Override
		public Dimension getMinimumSize()
		{
			return getPreferredSize();
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}

	/** Wrapped text; the width argument is ignored, kept so call sites read the same. */
	private static JComponent text(String html, int width, Color colour)
	{
		return left(new WrappingHtml(html, colour));
	}

	private static String esc(String s)
	{
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;");
	}

	private static String hex(Color c)
	{
		return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}

	private static String gp(long amount)
	{
		return QuantityFormatter.quantityToStackSize(amount);
	}

	/** A rounded-looking card with a coloured heading. */
	private JPanel card(String heading, Color accent)
	{
		final JPanel card = left(column());
		card.setBackground(CARD);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, accent),
			new EmptyBorder(6, 6, 6, 6)));
		if (heading != null)
		{
			final JLabel title = left(new JLabel(heading));
			title.setFont(FontManager.getRunescapeBoldFont());
			title.setForeground(accent);
			card.add(title);
			card.add(Box.createVerticalStrut(4));
		}
		return card;
	}

	private void addCard(JPanel card)
	{
		content.add(card);
		content.add(Box.createVerticalStrut(8));
	}

	private JLabel icon(int itemId, long quantity)
	{
		final JLabel label = new JLabel();
		label.setPreferredSize(new Dimension(36, 32));
		label.setHorizontalAlignment(SwingConstants.CENTER);
		if (itemId > 0)
		{
			itemManager.getImage(itemId, (int) Math.min(Integer.MAX_VALUE, Math.max(1, quantity)),
				quantity > 1).addTo(label);
		}
		return label;
	}

	/** Picture on the left, name and detail lines on the right. */
	private JPanel row(int itemId, long quantity, String name, String detail, String tooltip)
	{
		final JPanel row = left(new JPanel(new BorderLayout(6, 0)));
		row.setBackground(CARD);
		row.setBorder(new EmptyBorder(2, 0, 2, 0));
		row.add(icon(itemId, quantity), BorderLayout.WEST);
		row.add(text("<span style='color:white'>" + esc(name) + "</span>"
			+ (detail == null ? "" : "<br>" + detail), ROW_TEXT, MUTED), BorderLayout.CENTER);
		if (tooltip != null)
		{
			row.setToolTipText(tooltip);
		}
		return row;
	}

	// ---- Selection -------------------------------------------------------

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

	private void updateHero()
	{
		final RebuildData.Goal goal = selectedGoal();
		goalIcon.setIcon(null);
		if (goal != null)
		{
			itemManager.getImage(goal.getBuyId()).addTo(goalIcon);
			goalName.setText(goal.getName());
		}
	}

	public RebuildData.Goal selectedGoal()
	{
		return (RebuildData.Goal) goalBox.getSelectedItem();
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
		if (choice != null && !AUTO.equals(choice))
		{
			for (Plan plan : plans)
			{
				if (plan.getMethod().getName().equals(choice))
				{
					return plan;
				}
			}
		}
		return best();
	}

	// ---- States ----------------------------------------------------------

	public void showLoggedOut()
	{
		content.removeAll();
		final JPanel card = card("Not logged in", MUTED);
		card.add(text("Log in to your account and the planner will read your bank, inventory and worn gear.",
			INNER - 20, Color.WHITE));
		addCard(card);
		refresh();
	}

	/** Step-by-step help shown until the bank has been opened this session. */
	private void addBankHelp()
	{
		final JPanel card = card("Open your bank", WARN);
		card.add(text("The bank can only be read while it is open, so the plan below only counts "
			+ "your inventory and worn gear for now.", INNER - 20, Color.WHITE));
		card.add(Box.createVerticalStrut(6));
		card.add(text("<b>1.</b> Go to any bank booth, chest or banker.<br>"
			+ "<b>2.</b> Open your bank. You do not need to change anything.<br>"
			+ "<b>3.</b> The plan updates straight away. You can close the bank again.",
			INNER - 20, ColorScheme.LIGHT_GRAY_COLOR));
		card.add(Box.createVerticalStrut(4));
		card.add(text("Your bank is remembered until you log out.", INNER - 20, MUTED));
		addCard(card);
	}

	public void showPlans(RebuildData.Goal goal, List<Plan> plans, boolean bankSeen, boolean hasOverrides)
	{
		this.hasOverrides = hasOverrides;
		if (goal != selectedGoal())
		{
			return;
		}
		this.plans = plans;
		this.bankSeen = bankSeen;
		render();
	}

	private void refresh()
	{
		content.revalidate();
		content.repaint();
	}

	private void render()
	{
		content.removeAll();

		// Until the bank has been read the plan would be built on the inventory
		// alone and mislead, so show nothing but how to fix that.
		if (!bankSeen)
		{
			addBankHelp();
			refresh();
			return;
		}

		final Plan plan = selectedPlan();
		if (plan == null)
		{
			final JPanel card = card(null, MUTED);
			card.add(text("No plan available for this item.", INNER - 20, Color.WHITE));
			addCard(card);
			refresh();
			return;
		}

		addSummary(plan);
		addEarnBack(plan);
		addSales(plan);
		addSwaps(plan);
		addBuys(plan);
		addExcluded(plan);
		addKeeps(plan);
		refresh();
	}
	// ---- Sections --------------------------------------------------------

	private void addSummary(Plan plan)
	{
		final long target = plan.getGoalPrice() + plan.getBuyCost();
		final long have = Math.min(target, plan.getCoins() + plan.getRaised());
		final Color accent = plan.getNeeded() <= 0 || plan.isFunded() ? GOOD : BAD;

		final JPanel card = card(plan.isAlreadyOwned() ? "You already own one" : "The plan", accent);

		final JPanel figures = left(new JPanel(new GridLayout(0, 2, 4, 2)));
		figures.setBackground(CARD);
		figure(figures, "Price", gp(plan.getGoalPrice()), Color.WHITE);
		figure(figures, "Your coins", gp(plan.getCoins()), Color.WHITE);
		if (plan.getBuyCost() > 0)
		{
			figure(figures, "Kit to buy", gp(plan.getBuyCost()), Color.WHITE);
		}
		figure(figures, "From sales", gp(plan.getRaised()), GOOD);
		card.add(figures);
		card.add(Box.createVerticalStrut(6));

		final JProgressBar bar = left(new JProgressBar(0, 1000));
		bar.setValue(target <= 0 ? 1000 : (int) (have * 1000 / target));
		bar.setForeground(accent);
		bar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bar.setBorderPainted(false);
		bar.setStringPainted(true);
		bar.setString(gp(have) + " / " + gp(target));
		bar.setPreferredSize(new Dimension(1, 16));
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
		card.add(bar);
		card.add(Box.createVerticalStrut(6));

		final String verdict;
		if (plan.getNeeded() <= 0)
		{
			verdict = "Your coins already cover it. Nothing needs selling.";
		}
		else if (plan.isFunded())
		{
			verdict = "Affordable. Sell the items below to raise " + gp(plan.getNeeded()) + ".";
		}
		else
		{
			verdict = hasOverrides
				? "Can't be done with what you chose to keep: short by " + gp(plan.getShortfall())
					+ ". Allow some items to be sold again, or sell more of something."
				: "Short by " + gp(plan.getShortfall()) + " after everything below. Selling more "
					+ "would cost too much damage.";
		}
		card.add(text("<b>" + esc(verdict) + "</b>", INNER - 20, accent));
		addCard(card);
	}

	private static void figure(JPanel grid, String name, String value, Color colour)
	{
		final JLabel label = new JLabel(name);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(MUTED);
		final JLabel amount = new JLabel(value, SwingConstants.RIGHT);
		amount.setFont(FontManager.getRunescapeSmallFont());
		amount.setForeground(colour);
		grid.add(label);
		grid.add(amount);
	}

	private void addEarnBack(Plan plan)
	{
		final RebuildData.Method method = plan.getMethod();
		final JPanel card = card("Earn it back", ColorScheme.BRAND_ORANGE);
		card.add(row(plan.getGoal().getId(), 1, method.getName(),
			"Wiki: <span style='color:white'>" + gp(method.getGpPerHour()) + "/hr</span><br>"
				+ "Your kit: <span style='color:" + hex(GOOD) + "'>~" + gp(plan.getEstGpPerHour())
				+ "/hr</span> (" + Math.round(plan.getDpsVersusBest() * 100) + "% damage)",
			null));
		if (plan.getKitSold() > 0 && plan.getEstGpPerHour() > 0)
		{
			card.add(text("About " + Math.round((double) plan.getKitSold() / plan.getEstGpPerHour())
				+ " hours to rebuy the gear you sell.", INNER - 20, ColorScheme.LIGHT_GRAY_COLOR));
		}
		if (method.getNote() != null)
		{
			card.add(Box.createVerticalStrut(4));
			card.add(text(esc(method.getNote()), INNER - 20, MUTED));
		}
		addCard(card);
	}

	/** A small clickable glyph, styled to sit at the end of a row. */
	private static JLabel button(String glyph, String tooltip, Color colour, Runnable action)
	{
		final JLabel label = new JLabel(glyph, SwingConstants.CENTER);
		label.setFont(FontManager.getRunescapeBoldFont());
		label.setForeground(colour);
		label.setToolTipText(tooltip);
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.setPreferredSize(new Dimension(18, 18));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				action.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				label.setForeground(Color.WHITE);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				label.setForeground(colour);
			}
		});
		return label;
	}

	/** An editable amount; commits on Enter or when focus leaves. */
	private JTextField quantityField(Plan.Sale sale)
	{
		final JTextField field = new JTextField(gp(sale.getQuantity()), 5);
		field.setFont(FontManager.getRunescapeSmallFont());
		field.setToolTipText("How many to sell, e.g. 5000, 5k or 1.5m. Press Enter to update the plan.");
		field.setMaximumSize(new Dimension(70, 20));
		final Runnable commit = () ->
		{
			final String text = field.getText().trim();
			if (text.isEmpty() || text.equals(gp(sale.getQuantity())))
			{
				return;
			}
			try
			{
				final long qty = Math.max(0, Math.min(sale.getHeld(), QuantityFormatter.parseQuantity(text)));
				actions.setQuantity(sale.getItemId(), qty);
			}
			catch (ParseException e)
			{
				field.setText(gp(sale.getQuantity()));
			}
		};
		field.addActionListener(e -> commit.run());
		field.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent e)
			{
				commit.run();
			}
		});
		return field;
	}

	private void addSales(Plan plan)
	{
		final List<Plan.Sale> needed = new ArrayList<>();
		long optional = 0;
		for (Plan.Sale sale : plan.getSales())
		{
			if (sale.isNeeded())
			{
				needed.add(sale);
			}
			else
			{
				optional += sale.getProceeds();
			}
		}
		if (needed.isEmpty() && optional == 0)
		{
			return;
		}

		final JPanel card = card("Sell from your bank", GOOD);
		for (Plan.Sale sale : needed)
		{
			final JPanel row = left(new JPanel(new BorderLayout(6, 0)));
			row.setBackground(CARD);
			row.setBorder(new EmptyBorder(3, 0, 3, 0));
			row.add(icon(sale.getItemId(), sale.getQuantity()), BorderLayout.WEST);

			final JPanel middle = column();
			middle.setBackground(CARD);
			middle.add(text("<span style='color:white'>" + esc(sale.getName()) + "</span>", 0, MUTED));

			if (sale.getHeld() > 1)
			{
				final JPanel amount = left(new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1)));
				amount.setBackground(CARD);
				amount.add(caption("Sell"));
				amount.add(quantityField(sale));
				amount.add(caption("of " + gp(sale.getHeld())));
				if (sale.isCustom())
				{
					amount.add(button("\u21BA", "Go back to the suggested amount", MUTED,
						() -> actions.clearQuantity(sale.getItemId())));
				}
				middle.add(amount);
			}
			middle.add(text("<span style='color:" + hex(GOOD) + "'>+" + gp(sale.getProceeds()) + "</span>"
				+ (sale.getKept() > 0 && sale.getHeld() > 1 ? " &middot; keeps " + gp(sale.getKept()) : ""),
				0, MUTED));
			row.add(middle, BorderLayout.CENTER);
			row.add(button("\u2715", "Don't sell this - the plan will try to make it up elsewhere", BAD,
				() -> actions.exclude(sale.getItemId())), BorderLayout.EAST);
			card.add(row);
		}
		card.add(Box.createVerticalStrut(4));
		card.add(text("After the 2% Grand Exchange tax. Click \u2715 to keep an item, or change an amount and "
			+ "press Enter." + (optional > 0 ? " Another " + gp(optional) + " of spare items is not needed." : ""),
			INNER - 20, MUTED));
		addCard(card);
	}

	private void addSwaps(Plan plan)
	{
		if (plan.getSwaps().isEmpty())
		{
			return;
		}
		final JPanel card = card("Sell and swap down", WARN);
		String lastRole = null;
		for (Plan.Swap swap : plan.getSwaps())
		{
			if (!swap.getRole().equals(lastRole))
			{
				lastRole = swap.getRole();
				final Double loss = plan.getRoleLoss().get(lastRole);
				card.add(Box.createVerticalStrut(2));
				card.add(text("<b>" + esc(lastRole) + "</b>" + (loss != null && loss >= 0.05
					? " &middot; <span style='color:" + hex(WARN) + "'>" + String.format("-%.1f%% damage", loss)
						+ "</span>" : " &middot; no damage lost"), 0, ColorScheme.LIGHT_GRAY_COLOR));
			}

			final JPanel row = left(new JPanel(new BorderLayout(6, 0)));
			row.setBackground(CARD);
			row.setBorder(new EmptyBorder(2, 0, 4, 0));

			final JPanel top = new JPanel(new BorderLayout());
			top.setBackground(CARD);
			final JPanel pictures = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
			pictures.setBackground(CARD);
			pictures.add(icon(swap.getSellId(), 1));
			final JLabel arrow = new JLabel("\u2192");
			arrow.setForeground(MUTED);
			pictures.add(arrow);
			pictures.add(icon(swap.getUseId(), 1));
			top.add(pictures, BorderLayout.WEST);
			top.add(button("\u2715", "Keep " + swap.getSell() + " - the plan will look elsewhere", BAD,
				() -> actions.exclude(swap.getSellId())), BorderLayout.EAST);
			row.add(top, BorderLayout.NORTH);

			row.add(text("<span style='color:white'>Sell " + esc(swap.getSell()) + "</span><br>"
				+ "Use " + esc(swap.getUse()) + (swap.isUseOwned() || swap.getUseId() < 0 ? "" : " (buy it)") + "<br>"
				+ "<span style='color:" + hex(GOOD) + "'>+" + gp(swap.getFreed()) + "</span>", 0, MUTED),
				BorderLayout.CENTER);
			card.add(row);
		}
		addCard(card);
	}

	/** What the player has told the plan to keep, with a way back. */
	private void addExcluded(Plan plan)
	{
		if (plan.getExcluded().isEmpty() && !hasOverrides)
		{
			return;
		}
		final JPanel card = card("You chose to keep", ColorScheme.LIGHT_GRAY_COLOR);
		for (Plan.Excluded item : plan.getExcluded())
		{
			final JPanel row = left(new JPanel(new BorderLayout(6, 0)));
			row.setBackground(CARD);
			row.add(icon(item.getItemId(), 1), BorderLayout.WEST);
			row.add(text(esc(item.getName()), 0, Color.WHITE), BorderLayout.CENTER);
			row.add(button("\u21BA", "Allow the plan to sell this again", MUTED,
				() -> actions.restore(item.getItemId())), BorderLayout.EAST);
			card.add(row);
		}
		if (hasOverrides)
		{
			card.add(Box.createVerticalStrut(4));
			final JLabel reset = left(button("Reset all my changes", "Undo every \u2715 and amount change",
				ColorScheme.BRAND_ORANGE, actions::resetAll));
			reset.setFont(FontManager.getRunescapeSmallFont());
			reset.setHorizontalAlignment(SwingConstants.LEFT);
			reset.setPreferredSize(null);
			card.add(reset);
		}
		addCard(card);
	}
	private void addBuys(Plan plan)
	{
		if (plan.getBuys().isEmpty())
		{
			return;
		}
		final JPanel card = card("Buy for the kit", BAD);
		for (Plan.Buy buy : plan.getBuys())
		{
			card.add(row(buy.getItemId(), 1, buy.getName(),
				"<span style='color:" + hex(BAD) + "'>-" + gp(buy.getCost()) + "</span> &middot; "
					+ esc(buy.getRole()), null));
		}
		addCard(card);
	}

	/** Every piece to hold on to, as a grid of pictures per setup. */
	private void addKeeps(Plan plan)
	{
		final Map<String, List<Plan.Keep>> byRole = new LinkedHashMap<>();
		for (Plan.Keep keep : plan.getKeeps())
		{
			byRole.computeIfAbsent(keep.getRole(), k -> new ArrayList<>()).add(keep);
		}
		if (byRole.isEmpty())
		{
			return;
		}

		final JPanel card = card("Keep - do not sell", ColorScheme.LIGHT_GRAY_COLOR);
		for (Map.Entry<String, List<Plan.Keep>> entry : byRole.entrySet())
		{
			card.add(caption(entry.getKey()));
			final JPanel grid = left(new JPanel(new GridLayout(0, 5, 2, 2)));
			grid.setBackground(CARD);
			for (Plan.Keep keep : entry.getValue())
			{
				final JLabel picture = icon(keep.getItemId(), 1);
				picture.setOpaque(true);
				picture.setBackground(ColorScheme.DARK_GRAY_COLOR);
				picture.setToolTipText(keep.getName());
				grid.add(picture);
			}
			card.add(grid);
			card.add(Box.createVerticalStrut(6));
		}
		card.add(text("Hover a picture for its name.", INNER - 20, MUTED));
		addCard(card);
	}
}
