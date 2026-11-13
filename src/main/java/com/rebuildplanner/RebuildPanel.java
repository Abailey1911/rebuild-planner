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
import java.util.HashMap;
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
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ButtonGroup;
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

		/** Keep the current megarare, method and changes under a name; saving an existing name updates it. */
		void savePlan(String name, String goal, String method);

		void loadPlan(String name);

		/** Start again with no changes and no saved plan open. */
		void newPlan();

		void deletePlan(String name);

		/** Sell something the plan was leaving alone. */
		void addSale(int itemId);

		/** Use this item in place of a kit piece that is being sold. */
		void setReplacement(int soldId, int useId);

		void clearReplacement(int soldId);

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
	/** The plan as it stood just before the player's last change, to show what moved. */
	private Map<Integer, Long> before;
	private Map<Integer, String> beforeNames;
	/** What the player just did, e.g. "You kept Abyssal dagger (p++)". */
	private String changeReason;
	private int changedItem = -1;
	/** The outcome of the last change, kept on screen until the next one. */
	private List<String[]> changes;
	private boolean scrollToTop;

	/** Plan is what you end up with; Sell is everything you can act on to get there. */
	private static final String[] TABS = {"Plan", "Sell"};
	private static final int SELL_TAB = 1;
	private final JPanel tabRow = new JPanel(new GridLayout(1, 0, 3, 0));
	private final JToggleButton[] tabButtons = new JToggleButton[TABS.length];
	/** One-line verdict shown on every tab, so a change made on Sell shows its effect at once. */
	private final JLabel status = new JLabel();
	private int selectedTab;
	/** Scroll position to restore after the next render, or -1. */
	private int keepScroll = -1;

	/** True while the method list is being refilled, to ignore its events. */
	private boolean filling;

	public RebuildPanel(List<RebuildData.Goal> goals, ItemManager itemManager, Actions actions)
	{
		this.itemManager = itemManager;
		final Actions raw = actions;
		this.actions = new Actions()
		{
			@Override
			public void changed()
			{
				changes = null;
				before = null;
				scrollToTop = true;
				raw.changed();
			}

			@Override
			public void exclude(int itemId)
			{
				remember(itemId, "You kept " + nameOf(itemId));
				raw.exclude(itemId);
			}

			@Override
			public void restore(int itemId)
			{
				remember(itemId, "You let " + nameOf(itemId) + " be sold again");
				raw.restore(itemId);
			}

			@Override
			public void setQuantity(int itemId, long quantity)
			{
				remember(itemId, "You changed " + nameOf(itemId) + " to sell " + gp(quantity));
				raw.setQuantity(itemId, quantity);
			}

			@Override
			public void clearQuantity(int itemId)
			{
				remember(itemId, "You put " + nameOf(itemId) + " back to the suggested amount");
				raw.clearQuantity(itemId);
			}

			@Override
			public void savePlan(String name, String goal, String method)
			{
				raw.savePlan(name, goal, method);
			}

			@Override
			public void loadPlan(String name)
			{
				changes = null;
				before = null;
				scrollToTop = true;
				raw.loadPlan(name);
			}

			@Override
			public void newPlan()
			{
				changes = null;
				before = null;
				scrollToTop = true;
				raw.newPlan();
			}

			@Override
			public void deletePlan(String name)
			{
				raw.deletePlan(name);
			}

			@Override
			public void addSale(int itemId)
			{
				remember(itemId, "You added " + nameOf(itemId) + " to the sell list");
				raw.addSale(itemId);
			}

			@Override
			public void setReplacement(int soldId, int useId)
			{
				remember(soldId, "You chose what replaces " + nameOf(soldId));
				raw.setReplacement(soldId, useId);
			}

			@Override
			public void clearReplacement(int soldId)
			{
				remember(soldId, "You went back to the suggested swap for " + nameOf(soldId));
				raw.clearReplacement(soldId);
			}

			@Override
			public void resetAll()
			{
				remember(-1, "You reset all your changes");
				raw.resetAll();
			}
		};		setLayout(new BorderLayout(0, 8));
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

		// Saved plans: pick one to open it, Save to keep (or update) what is on screen.
		top.add(caption("Plan"));
		planBox.addItem(NEW_PLAN);
		top.add(planBox);
		top.add(Box.createVerticalStrut(3));
		final JPanel planButtons = new JPanel(new GridLayout(1, 0, 3, 0));
		planButtons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (javax.swing.JButton button : new javax.swing.JButton[]{saveButton, newButton, deleteButton})
		{
			button.setFont(FontManager.getRunescapeSmallFont());
			button.setFocusPainted(false);
			button.setMargin(new java.awt.Insets(2, 2, 2, 2));
			planButtons.add(button);
		}
		saveButton.setToolTipText("Save this megarare, method and your changes. Saving an open plan updates it.");
		newButton.setToolTipText("Start a fresh plan with none of your changes");
		deleteButton.setToolTipText("Delete the saved plan that is open");
		top.add(left(planButtons));
		top.add(Box.createVerticalStrut(6));
		planBox.addActionListener(e ->
		{
			if (fillingPlans)
			{
				return;
			}
			final String choice = (String) planBox.getSelectedItem();
			if (choice == null || NEW_PLAN.equals(choice))
			{
				this.actions.newPlan();
			}
			else
			{
				this.actions.loadPlan(choice);
			}
		});
		saveButton.addActionListener(e -> saveClicked());
		newButton.addActionListener(e -> this.actions.newPlan());
		deleteButton.addActionListener(e ->
		{
			final String open = openPlan();
			if (open != null && javax.swing.JOptionPane.showConfirmDialog(this, "Delete the saved plan \"" + open + "\"?",
				"Rebuild Planner", javax.swing.JOptionPane.OK_CANCEL_OPTION) == javax.swing.JOptionPane.OK_OPTION)
			{
				this.actions.deletePlan(open);
			}
		});

		top.add(caption("Rebuild to"));
		for (RebuildData.Goal goal : goals)
		{
			goalBox.addItem(goal);
		}
		top.add(goalBox);
		top.add(Box.createVerticalStrut(4));
		top.add(caption("Earn it back at"));
		top.add(methodBox);
		top.add(Box.createVerticalStrut(6));

		tabRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		final ButtonGroup group = new ButtonGroup();
		for (int i = 0; i < TABS.length; i++)
		{
			final int index = i;
			final JToggleButton tab = new JToggleButton(TABS[i], i == 0);
			tab.setFont(FontManager.getRunescapeSmallFont());
			tab.setFocusPainted(false);
			tab.addActionListener(e ->
			{
				selectedTab = index;
				scrollToTop = true;
				render();
			});
			group.add(tab);
			tabButtons[i] = tab;
			tabRow.add(tab);
		}
		top.add(left(tabRow));
		top.add(Box.createVerticalStrut(4));
		status.setFont(FontManager.getRunescapeSmallFont());
		top.add(left(status));
		add(top, BorderLayout.NORTH);

		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(content, BorderLayout.CENTER);

		fillMethods();
		updateHero();
		goalBox.addActionListener(e ->
		{
			if (loading)
			{
				return;
			}
			fillMethods();
			updateHero();
			this.actions.changed();
		});
		methodBox.addActionListener(e ->
		{
			if (!filling)
			{
				changes = null;
				scrollToTop = true;
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
			// Otherwise the caret drags the scroll pane to whichever text was built last.
			if (getCaret() instanceof javax.swing.text.DefaultCaret)
			{
				((javax.swing.text.DefaultCaret) getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
			}
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

	// ---- Saved plans -------------------------------------------------------

	private static final String NEW_PLAN = "New plan (not saved)";
	private final JComboBox<String> planBox = new JComboBox<>();
	private final javax.swing.JButton saveButton = new javax.swing.JButton("Save");
	private final javax.swing.JButton newButton = new javax.swing.JButton("New");
	private final javax.swing.JButton deleteButton = new javax.swing.JButton("Delete");
	/** True while the plan list is being refilled, to ignore its events. */
	private boolean fillingPlans;
	/** True while a saved plan's megarare is being selected, so it is not taken for a fresh start. */
	private boolean loading;

	/** Name of the saved plan that is open, or null. */
	private String openPlan()
	{
		final String choice = (String) planBox.getSelectedItem();
		return choice == null || NEW_PLAN.equals(choice) ? null : choice;
	}

	/** Refills the plan list. Runs on the Swing thread. */
	public void setSavedPlans(List<String> names, String open)
	{
		fillingPlans = true;
		planBox.removeAllItems();
		planBox.addItem(NEW_PLAN);
		for (String name : names)
		{
			planBox.addItem(name);
		}
		planBox.setSelectedItem(open != null && names.contains(open) ? open : NEW_PLAN);
		fillingPlans = false;
		deleteButton.setEnabled(openPlan() != null);
		saveButton.setText(openPlan() != null ? "Save" : "Save as…");
	}

	/** Shows a saved plan's megarare and method without treating it as the player starting over. */
	public void select(String goalName, String methodName)
	{
		loading = true;
		for (int i = 0; i < goalBox.getItemCount(); i++)
		{
			if (goalBox.getItemAt(i).getName().equals(goalName))
			{
				goalBox.setSelectedIndex(i);
			}
		}
		loading = false;
		fillMethods();
		updateHero();
		filling = true;
		methodBox.setSelectedItem(AUTO);
		for (int i = 0; i < methodBox.getItemCount(); i++)
		{
			if (methodBox.getItemAt(i).equals(methodName))
			{
				methodBox.setSelectedIndex(i);
			}
		}
		filling = false;
	}

	private void saveClicked()
	{
		final RebuildData.Goal goal = selectedGoal();
		if (goal == null)
		{
			return;
		}
		String name = openPlan();
		if (name == null)
		{
			final Object typed = javax.swing.JOptionPane.showInputDialog(this, "Name this plan", "Rebuild Planner",
				javax.swing.JOptionPane.PLAIN_MESSAGE, null, null, goal.getName() + " rebuild");
			name = typed == null ? "" : typed.toString().trim();
			if (name.isEmpty() || NEW_PLAN.equals(name))
			{
				return;
			}
		}
		final Object method = methodBox.getSelectedItem();
		actions.savePlan(name, goal.getName(), method == null || AUTO.equals(method) ? null : method.toString());
		// A moment's confirmation, since saving an open plan changes nothing on screen.
		saveButton.setText("Saved ✓");
		final javax.swing.Timer back = new javax.swing.Timer(1500,
			e -> saveButton.setText(openPlan() != null ? "Save" : "Save as…"));
		back.setRepeats(false);
		back.start();
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
				|| (plan.isWorthIt() && !best.isWorthIt())
				|| (plan.isWorthIt() == best.isWorthIt() && plan.isFunded() && !best.isFunded())
				|| (plan.isWorthIt() == best.isWorthIt() && plan.isFunded() == best.isFunded() && (plan.isFunded()
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
		tabRow.setVisible(false);
		status.setVisible(false);
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
		// Jump to the top the moment the bank is first read, not wherever the
		// instructions happened to leave the scroll position.
		if (bankSeen && !this.bankSeen)
		{
			scrollToTop = true;
		}
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
		final int position = scrollToTop ? 0 : keepScroll;
		if (scrollToTop)
		{
			held = null;
		}
		scrollToTop = false;
		keepScroll = -1;
		if (position >= 0)
		{
			scrollTo(position);
		}
	}

	/**
	 * Puts the scroll bar back where it was. The text re-wraps once it learns its
	 * real width, which changes the page's height a moment after it is built -
	 * setting the position only once would be clamped against the wrong height
	 * and land at the bottom - so it is applied again as the layout settles.
	 */
	private void scrollTo(int position)
	{
		if (scrollTimer != null)
		{
			scrollTimer.stop();
		}
		final int[] runs = {0};
		lastTarget = position;
		final Runnable apply = () ->
		{
			final JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
			if (scroll == null)
			{
				return;
			}
			// Prefer keeping a row the player was looking at exactly where it was:
			// cards appearing or growing above it would otherwise push it away.
			int target = position;
			if (held != null)
			{
				for (Object[] row : held)
				{
					final JComponent now = anchors.get(row[0]);
					if (now != null && now.getParent() != null)
					{
						target = yOf(now, scroll) - (Integer) row[1];
						break;
					}
				}
			}
			lastTarget = Math.max(0, target);
			scroll.getVerticalScrollBar().setValue(lastTarget);
		};
		SwingUtilities.invokeLater(apply);
		scrollTimer = new javax.swing.Timer(40, e ->
		{
			apply.run();
			if (++runs[0] >= 5)
			{
				((javax.swing.Timer) e.getSource()).stop();
			}
		});
		scrollTimer.start();
	}

	private javax.swing.Timer scrollTimer;
	private int lastTarget;
	/** Rows on screen now, by a key that survives a redraw, e.g. "sale:4151". */
	private final Map<String, JComponent> anchors = new java.util.LinkedHashMap<>();
	/** Rows that were in view before the redraw, each with how far down the viewport it sat. */
	private List<Object[]> held;

	private <T extends JComponent> T anchor(String key, T row)
	{
		anchors.put(key, row);
		return row;
	}

	private static int yOf(JComponent row, JScrollPane scroll)
	{
		return SwingUtilities.convertPoint(row.getParent(), row.getLocation(), scroll.getViewport().getView()).y;
	}

	/** Notes which rows are in view, so the redraw can put one of them back in the same place. */
	private void holdRows(int changedItem)
	{
		held = null;
		final JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
		if (scroll == null)
		{
			return;
		}
		final int top = scroll.getVerticalScrollBar().getValue();
		final int bottom = top + scroll.getViewport().getHeight();
		final List<Object[]> rows = new ArrayList<>();
		final List<Object[]> changed = new ArrayList<>();
		for (Map.Entry<String, JComponent> entry : anchors.entrySet())
		{
			final JComponent row = entry.getValue();
			if (row.getParent() == null)
			{
				continue;
			}
			final int y = yOf(row, scroll);
			if (y + row.getHeight() <= top || y >= bottom)
			{
				continue;
			}
			// The row that was changed may move or vanish, so it is the last resort.
			(entry.getKey().endsWith(":" + changedItem) ? changed : rows).add(new Object[]{entry.getKey(), y - top});
		}
		rows.addAll(changed);
		held = rows;
	}

	// ---- What changed ------------------------------------------------------

	private String nameOf(int itemId)
	{
		final Plan plan = selectedPlan();
		if (plan != null)
		{
			for (Plan.Sale sale : plan.getSales())
			{
				if (sale.getItemId() == itemId)
				{
					return sale.getName();
				}
			}
			for (Plan.Swap swap : plan.getSwaps())
			{
				if (swap.getSellId() == itemId)
				{
					return swap.getSell();
				}
			}
			for (Plan.Excluded item : plan.getExcluded())
			{
				if (item.getItemId() == itemId)
				{
					return item.getName();
				}
			}
		}
		return "that item";
	}

	/** Everything the plan currently sells, by item, with names. */
	private static Map<Integer, Long> soldBy(Plan plan, Map<Integer, String> names)
	{
		final Map<Integer, Long> sold = new HashMap<>();
		if (plan == null)
		{
			return sold;
		}
		for (Plan.Sale sale : plan.getSales())
		{
			if (sale.isNeeded())
			{
				sold.merge(sale.getItemId(), sale.getQuantity(), Long::sum);
				names.put(sale.getItemId(), sale.getName());
			}
		}
		for (Plan.Swap swap : plan.getSwaps())
		{
			sold.merge(swap.getSellId(), 1L, Long::sum);
			names.put(swap.getSellId(), swap.getSell());
		}
		return sold;
	}

	private int currentScroll()
	{
		final JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
		return scroll == null ? -1 : scroll.getVerticalScrollBar().getValue();
	}

	private void remember(int itemId, String reason)
	{
		beforeNames = new HashMap<>();
		before = soldBy(selectedPlan(), beforeNames);
		changeReason = reason;
		changedItem = itemId;
		// Stay where the player was: they are working down the list.
		keepScroll = currentScroll();
		holdRows(itemId);
	}

	/** Compares the new plan with the one before the player's change. */
	private void diff(Plan plan)
	{
		if (before == null)
		{
			return;
		}
		final Map<Integer, String> names = new HashMap<>(beforeNames);
		final Map<Integer, Long> after = soldBy(plan, names);
		changes = new ArrayList<>();
		for (Map.Entry<Integer, Long> entry : after.entrySet())
		{
			final long was = before.getOrDefault(entry.getKey(), 0L);
			if (entry.getKey() != changedItem && entry.getValue() > was)
			{
				changes.add(new String[]{"+", String.valueOf(entry.getKey()), names.get(entry.getKey()),
					was == 0 ? "now sold" : "sell " + gp(entry.getValue()) + " instead of " + gp(was)});
			}
		}
		for (Map.Entry<Integer, Long> entry : before.entrySet())
		{
			final long now = after.getOrDefault(entry.getKey(), 0L);
			if (entry.getKey() != changedItem && now < entry.getValue())
			{
				changes.add(new String[]{"-", String.valueOf(entry.getKey()), names.get(entry.getKey()),
					now == 0 ? "no longer needed" : "sell " + gp(now) + " instead of " + gp(entry.getValue())});
			}
		}
		before = null;
	}

	/** Items the player's last change caused the plan to add. */
	private java.util.Set<Integer> recentlyAdded()
	{
		final java.util.Set<Integer> ids = new java.util.HashSet<>();
		if (changes != null)
		{
			for (String[] change : changes)
			{
				if ("+".equals(change[0]))
				{
					ids.add(Integer.parseInt(change[1]));
				}
			}
		}
		return ids;
	}

	private void addChanges(Plan plan)
	{
		if (changes == null)
		{
			return;
		}
		final JPanel card = card("What changed", ColorScheme.BRAND_ORANGE);
		final JPanel header = left(new JPanel(new BorderLayout()));
		header.setBackground(CARD);
		header.add(text(esc(changeReason) + ".", 0, Color.WHITE), BorderLayout.CENTER);
		header.add(button("\u2715", "Hide", MUTED, () ->
		{
			changes = null;
			render();
		}), BorderLayout.EAST);
		card.add(header);
		card.add(Box.createVerticalStrut(4));

		if (changes.isEmpty())
		{
			card.add(text(plan.isFunded()
				? "Nothing else had to change - the items already listed still cover it."
				: "Nothing else can make up the difference.", 0, plan.isFunded() ? MUTED : BAD));
		}
		for (String[] change : changes)
		{
			final boolean added = "+".equals(change[0]);
			card.add(row(Integer.parseInt(change[1]), 1, change[2],
				"<span style='color:" + hex(added ? WARN : GOOD) + "'>" + (added ? "Added: " : "Dropped: ")
					+ esc(change[3]) + "</span>", null));
		}
		addCard(card);
	}

	private void render()
	{
		// Any redraw that is not a deliberate jump to the top stays where the player is.
		if (keepScroll < 0 && !scrollToTop)
		{
			if (scrollTimer != null && scrollTimer.isRunning())
			{
				// A second redraw while the first is still settling: keep aiming for the same place.
				keepScroll = lastTarget;
			}
			else
			{
				keepScroll = currentScroll();
				holdRows(-1);
			}
		}
		anchors.clear();
		content.removeAll();
		tabRow.setVisible(bankSeen);
		status.setVisible(bankSeen);

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

		diff(plan);
		updateStatus(plan);
		if (selectedTab == SELL_TAB)
		{
			// Everything you can act on: what goes, and your own changes.
			addChanges(plan);
			addSwaps(plan);
			addSales(plan);
			addExcluded(plan);
		}
		else
		{
			// The outcome: the verdict, where the gp comes back, and the kit you end up with.
			addSummary(plan);
			addEarnBack(plan);
			addKeeps(plan);
			addBuys(plan);
		}
		refresh();
	}

	private void updateStatus(Plan plan)
	{
		final String text;
		final Color colour;
		if (!plan.isWorthIt())
		{
			text = "Not worth it";
			colour = BAD;
		}
		else if (plan.getNeeded() <= 0 || plan.isFunded())
		{
			text = "Affordable";
			colour = GOOD;
		}
		else
		{
			text = "Short by " + gp(plan.getShortfall());
			colour = BAD;
		}
		// Kept short: a label does not wrap, and a long one would widen the sidebar.
		status.setText(text + "  \u00B7  " + gp(Math.min(plan.getRaised(), plan.getNeeded())) + " / " + gp(plan.getNeeded()));
		status.setToolTipText("Raised by the sales on the Sell tab, out of what is still needed after your coins");
		status.setForeground(colour);
		tabButtons[SELL_TAB].setText(plan.getSwaps().isEmpty() && plan.getSales().isEmpty() ? "Sell"
			: "Sell (" + (plan.getSwaps().size() + (int) plan.getSales().stream().filter(Plan.Sale::isNeeded).count()) + ")");
	}
	// ---- Sections --------------------------------------------------------

	private void addSummary(Plan plan)
	{
		final long target = plan.getGoalPrice() + plan.getBuyCost();
		final long have = Math.min(target, plan.getCoins() + plan.getRaised());
		final Color accent = !plan.isWorthIt() ? BAD : plan.getNeeded() <= 0 || plan.isFunded() ? GOOD : BAD;

		final JPanel card = card(!plan.isWorthIt() ? "Not worth it" : plan.isAlreadyOwned() ? "You already own one" : "The plan", accent);

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
		if (!plan.isWorthIt())
		{
			verdict = String.format("With the gear you would have left, a %s only does %s%.0f%% damage compared with "
				+ "your %s today. Keep your setup and don't rebuild - or keep fewer things back so less of it has to go.",
				plan.getGoal().getName(), plan.getGainPct() >= 0 ? "+" : "", plan.getGainPct(), plan.getTodayWeapon());
		}
		else if (plan.getNeeded() <= 0)
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
		if (plan.isWorthIt() && plan.getTodayWeapon() != null)
		{
			card.add(text(String.format("+%.0f%% damage over your %s.", plan.getGainPct(), plan.getTodayWeapon()),
				INNER - 20, MUTED));
		}
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
		if (needed.isEmpty() && optional == 0 && plan.getExcluded().isEmpty() && plan.getAddable().isEmpty())
		{
			return;
		}

		final JPanel card = card("Sell from your bank", GOOD);
		// Anything else of value the plan was leaving alone, for the player to add.
		if (!plan.getAddable().isEmpty())
		{
			card.add(left(addSaleBox(plan)));
			card.add(Box.createVerticalStrut(6));
		}
		for (Plan.Sale sale : needed)
		{
			final JPanel row = anchor("sale:" + sale.getItemId(), left(new JPanel(new BorderLayout(6, 0))));
			row.setBackground(CARD);
			row.setBorder(new EmptyBorder(3, 0, 3, 0));
			row.add(icon(sale.getItemId(), sale.getQuantity()), BorderLayout.WEST);

			final JPanel middle = column();
			middle.setBackground(CARD);
			final boolean isNew = recentlyAdded().contains(sale.getItemId());
			middle.add(text("<span style='color:white'>" + esc(sale.getName()) + "</span>"
				+ (isNew ? " <span style='color:" + hex(WARN) + "'><b>NEW</b></span>" : ""), 0, MUTED));

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
		// What the player chose to keep stays visible here, crossed out.
		for (Plan.Excluded item : plan.getExcluded())
		{
			final JPanel row = anchor("kept:" + item.getItemId(), left(new JPanel(new BorderLayout(6, 0))));
			row.setBackground(CARD);
			row.setBorder(new EmptyBorder(3, 0, 3, 0));
			row.add(icon(item.getItemId(), 1), BorderLayout.WEST);
			row.add(text("<s>" + esc(item.getName()) + "</s><br>Keeping", 0, MUTED), BorderLayout.CENTER);
			row.add(button("\u21BA", "Let the plan sell this again", MUTED,
				() -> actions.restore(item.getItemId())), BorderLayout.EAST);
			card.add(row);
		}
		card.add(Box.createVerticalStrut(4));
		card.add(text("After the 2% Grand Exchange tax. Click \u2715 to keep an item, or change an amount and "
			+ "press Enter." + (optional > 0 ? " Another " + gp(optional) + " of spare items is not needed." : ""),
			INNER - 20, MUTED));
		addCard(card);
	}

	/** A drop-down of everything the plan is keeping, with pictures, to add to the sell list. */
	private JComboBox<Plan.Choice> addSaleBox(Plan plan)
	{
		final Plan.Choice prompt = new Plan.Choice(-1, -1, "+ Sell something else…", true, 0);
		final JComboBox<Plan.Choice> add = new JComboBox<>();
		add.addItem(prompt);
		for (Plan.Choice choice : plan.getAddable())
		{
			add.addItem(choice);
		}
		final Map<Integer, javax.swing.ImageIcon> icons = new HashMap<>();
		add.setRenderer(new javax.swing.DefaultListCellRenderer()
		{
			@Override
			public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
																   boolean selected, boolean focused)
			{
				super.getListCellRendererComponent(list, value, index, selected, focused);
				final Plan.Choice choice = (Plan.Choice) value;
				setFont(FontManager.getRunescapeSmallFont());
				setBorder(new EmptyBorder(2, 4, 2, 4));
				if (choice == null || choice.getId() < 0)
				{
					setText(prompt.getName());
					setIcon(null);
					setForeground(ColorScheme.BRAND_ORANGE);
					return this;
				}
				setText(choice.getName() + "  ·  " + gp(choice.getPrice()));
				setIcon(icons.computeIfAbsent(choice.getIconId(), id ->
				{
					final java.awt.image.BufferedImage image = itemManager.getImage(id);
					if (image instanceof net.runelite.client.util.AsyncBufferedImage)
					{
						((net.runelite.client.util.AsyncBufferedImage) image).onLoaded(list::repaint);
					}
					return new javax.swing.ImageIcon(image);
				}));
				return this;
			}
		});
		add.setPrototypeDisplayValue(prompt);
		add.setMaximumRowCount(10);
		add.setToolTipText("Add an item the plan is keeping - a kit piece or your current weapon - to the sell list");
		add.addActionListener(e ->
		{
			final Plan.Choice choice = (Plan.Choice) add.getSelectedItem();
			if (choice != null && choice.getId() >= 0)
			{
				actions.addSale(choice.getId());
			}
		});
		return add;
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

			final JPanel row = anchor("swap:" + swap.getSellId(), left(new JPanel(new BorderLayout(6, 0))));
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
			final JLabel useIcon = icon(swap.getUseId(), 1);
			pictures.add(useIcon);
			if (!swap.getChoices().isEmpty())
			{
				final JLabel more = new JLabel(" ▾");
				more.setForeground(ColorScheme.BRAND_ORANGE);
				pictures.add(more);
				for (JComponent c : new JComponent[]{useIcon, more})
				{
					c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
					c.setToolTipText("Click to choose what to use instead of " + swap.getSell());
					c.addMouseListener(new MouseAdapter()
					{
						@Override
						public void mousePressed(MouseEvent e)
						{
							replacementMenu(swap).show(useIcon, 0, useIcon.getHeight());
						}
					});
				}
			}
			top.add(pictures, BorderLayout.WEST);
			top.add(button("\u2715", "Keep " + swap.getSell() + " - the plan will look elsewhere", BAD,
				() -> actions.exclude(swap.getSellId())), BorderLayout.EAST);
			row.add(top, BorderLayout.NORTH);

			row.add(text("<span style='color:white'>Sell " + esc(swap.getSell()) + "</span><br>"
				+ "Use " + esc(swap.getUse()) + (swap.isUseOwned() || swap.getUseId() < 0 ? "" : " (buy it)")
				+ (swap.isCustom() ? " <span style='color:" + hex(WARN) + "'>&middot; your pick</span>" : "") + "<br>"
				+ "<span style='color:" + hex(GOOD) + "'>+" + gp(swap.getFreed()) + "</span>", 0, MUTED),
				BorderLayout.CENTER);
			card.add(row);
		}
		addCard(card);
	}

	/** The list of things a sold piece can be replaced with, best first. */
	private javax.swing.JPopupMenu replacementMenu(Plan.Swap swap)
	{
		final javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
		for (Plan.Choice choice : swap.getChoices())
		{
			final boolean current = choice.getName().equals(swap.getUse());
			final javax.swing.JMenuItem item = new javax.swing.JMenuItem((current ? "✓ " : "") + choice.getName()
				+ "  ·  " + (choice.isOwned() ? "owned" : gp(choice.getPrice())));
			final java.awt.image.BufferedImage image = itemManager.getImage(choice.getIconId());
			item.setIcon(new javax.swing.ImageIcon(image));
			if (image instanceof net.runelite.client.util.AsyncBufferedImage)
			{
				((net.runelite.client.util.AsyncBufferedImage) image).onLoaded(item::repaint);
			}
			item.setFont(FontManager.getRunescapeSmallFont());
			item.setForeground(current ? Color.WHITE : choice.isOwned() ? GOOD : ColorScheme.LIGHT_GRAY_COLOR);
			item.addActionListener(e -> actions.setReplacement(swap.getSellId(), choice.getId()));
			menu.add(item);
		}
		if (swap.isCustom())
		{
			menu.addSeparator();
			final javax.swing.JMenuItem reset = new javax.swing.JMenuItem("↺ Back to the suggested swap");
			reset.setFont(FontManager.getRunescapeSmallFont());
			reset.addActionListener(e -> actions.clearReplacement(swap.getSellId()));
			menu.add(reset);
		}
		return menu;
	}

	/** A way to undo everything at once, shown only after the player has changed something. */
	private void addExcluded(Plan plan)
	{
		if (!hasOverrides)
		{
			return;
		}
		final JPanel card = card("Your changes", ColorScheme.LIGHT_GRAY_COLOR);
		card.add(text("Items you chose to keep are crossed out in the sell list - click \u21BA there to undo one.",
			0, MUTED));
		card.add(Box.createVerticalStrut(4));
		final JLabel reset = left(button("Reset all my changes", "Undo every \u2715 and amount change",
			ColorScheme.BRAND_ORANGE, actions::resetAll));
		reset.setFont(FontManager.getRunescapeSmallFont());
		reset.setHorizontalAlignment(SwingConstants.LEFT);
		reset.setPreferredSize(null);
		card.add(reset);
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
			card.add(row(buy.getItemId(), buy.getQuantity(), buy.getName(),
				(buy.getQuantity() > 1 ? "Buy " + gp(buy.getQuantity()) + " &middot; " : "")
					+ "<span style='color:" + hex(BAD) + "'>-" + gp(buy.getCost()) + "</span> &middot; "
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

		boolean anyToBuy = false;
		final JPanel card = card("Your kit - keep, do not sell", ColorScheme.LIGHT_GRAY_COLOR);
		for (Map.Entry<String, List<Plan.Keep>> entry : byRole.entrySet())
		{
			card.add(caption(entry.getKey()));
			final JPanel grid = left(new JPanel(new GridLayout(0, 5, 2, 2)));
			grid.setBackground(CARD);
			for (Plan.Keep keep : entry.getValue())
			{
				final JLabel picture = icon(keep.getItemId(), keep.getQuantity());
				picture.setOpaque(true);
				picture.setBackground(ColorScheme.DARK_GRAY_COLOR);
				if (keep.isToBuy())
				{
					anyToBuy = true;
					picture.setBorder(BorderFactory.createLineBorder(WARN, 1));
				}
				picture.setToolTipText(keep.isToBuy() ? "Buy: " + keep.getName() + " (replaces a piece you sell)" : keep.getQuantity() > 1
					? gp(keep.getQuantity()) + " x " + keep.getName() + " - one full charge" : keep.getName());
				grid.add(picture);
			}
			card.add(grid);
			card.add(Box.createVerticalStrut(6));
		}
		card.add(text("Hover a picture for its name." + (anyToBuy ? " <span style='color:" + hex(WARN)
			+ "'>Outlined</span> pieces are bought to replace ones you sell." : ""), INNER - 20, MUTED));
		addCard(card);
	}
}
