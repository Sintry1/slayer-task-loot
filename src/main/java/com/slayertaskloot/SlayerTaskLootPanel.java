package com.slayertaskloot;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.SwingUtil;

class SlayerTaskLootPanel extends PluginPanel
{
	private static final Color PROFIT_GREEN = new Color(0, 200, 83);
	private static final Color LOSS_RED = new Color(220, 70, 70);
	private static final Color SUPPLY_ORANGE = new Color(220, 150, 60);
	private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final ImageIcon START_ICON;
	private static final ImageIcon START_ICON_HOVER;
	private static final ImageIcon PAUSE_ICON;
	private static final ImageIcon PAUSE_ICON_HOVER;
	private static final ImageIcon DELETE_ICON;
	private static final ImageIcon DELETE_ICON_HOVER;
	private static final ImageIcon RESUME_ICON;
	private static final ImageIcon RESUME_ICON_HOVER;
	private static final ImageIcon MERGE_ICON;
	private static final ImageIcon MERGE_ICON_HOVER;

	static
	{
		final BufferedImage start = ImageUtil.loadImageResource(
			SlayerTaskLootPanel.class, "/net/runelite/client/plugins/timetracking/start_icon.png");
		final BufferedImage pause = ImageUtil.loadImageResource(
			SlayerTaskLootPanel.class, "/net/runelite/client/plugins/timetracking/pause_icon.png");
		final BufferedImage delete = ImageUtil.loadImageResource(
			SlayerTaskLootPanel.class, "/net/runelite/client/plugins/timetracking/delete_icon.png");
		final BufferedImage resume = ImageUtil.loadImageResource(
			SlayerTaskLootPanel.class, "/net/runelite/client/plugins/timetracking/loop_icon.png");
		final BufferedImage merge = buildMergeIcon();
		START_ICON = new ImageIcon(start);
		START_ICON_HOVER = new ImageIcon(ImageUtil.luminanceOffset(start, -80));
		PAUSE_ICON = new ImageIcon(pause);
		PAUSE_ICON_HOVER = new ImageIcon(ImageUtil.luminanceOffset(pause, -80));
		DELETE_ICON = new ImageIcon(delete);
		DELETE_ICON_HOVER = new ImageIcon(ImageUtil.luminanceOffset(delete, -80));
		RESUME_ICON = new ImageIcon(resume);
		RESUME_ICON_HOVER = new ImageIcon(ImageUtil.luminanceOffset(resume, -80));
		MERGE_ICON = new ImageIcon(merge);
		MERGE_ICON_HOVER = new ImageIcon(ImageUtil.luminanceOffset(merge, -80));
	}

	private final SlayerTaskLootPlugin plugin;
	private final SlayerTaskLootConfig config;
	private final ItemManager itemManager;

	private final JPanel currentContainer = new JPanel();
	private final JPanel historyContainer = new JPanel();
	private JLabel historyHeaderLabel;
	private TaskView currentTask;
	private List<TaskView> history = Collections.emptyList();
	private boolean suppliesExpanded;
	private boolean dropsExpanded = true;

	SlayerTaskLootPanel(SlayerTaskLootPlugin plugin, SlayerTaskLootConfig config, ItemManager itemManager)
	{
		super(false);
		this.plugin = plugin;
		this.config = config;
		this.itemManager = itemManager;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(buildTitleBar(), BorderLayout.NORTH);

		final JTabbedPane tabs = new JTabbedPane();
		tabs.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tabs.setForeground(Color.WHITE);
		tabs.setFont(FontManager.getRunescapeFont());
		tabs.addTab("Current", buildCurrentTab());
		tabs.addTab("History", buildHistoryTab());
		add(tabs, BorderLayout.CENTER);

		rebuild();
	}

	private JPanel buildTitleBar()
	{
		final JPanel bar = new JPanel();
		bar.setLayout(new BorderLayout());
		bar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bar.setBorder(new EmptyBorder(8, 8, 8, 8));

		final JLabel title = new JLabel("Slayer Task Loot");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		bar.add(title, BorderLayout.WEST);

		return bar;
	}

	private JPanel buildCurrentTab()
	{
		currentContainer.setLayout(new BoxLayout(currentContainer, BoxLayout.Y_AXIS));
		currentContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		currentContainer.setBorder(new EmptyBorder(4, 8, 8, 8));

		final JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.add(currentContainer, BorderLayout.NORTH);

		final JPanel tab = new JPanel(new BorderLayout());
		tab.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tab.add(wrapScrolling(wrapper), BorderLayout.CENTER);

		return tab;
	}

	private JPanel buildHistoryTab()
	{
		historyContainer.setLayout(new BoxLayout(historyContainer, BoxLayout.Y_AXIS));
		historyContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		historyContainer.setBorder(new EmptyBorder(4, 8, 8, 8));

		historyHeaderLabel = new JLabel();
		historyHeaderLabel.setFont(FontManager.getRunescapeSmallFont());
		historyHeaderLabel.setForeground(Color.LIGHT_GRAY);
		historyHeaderLabel.setBorder(new EmptyBorder(6, 8, 2, 8));

		final JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.add(historyContainer, BorderLayout.NORTH);

		final JPanel tab = new JPanel(new BorderLayout());
		tab.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tab.add(historyHeaderLabel, BorderLayout.NORTH);
		tab.add(wrapScrolling(wrapper), BorderLayout.CENTER);

		final JButton clear = new JButton("Clear history");
		clear.setFocusPainted(false);
		clear.addActionListener(e -> plugin.clearHistory());
		tab.add(clear, BorderLayout.SOUTH);

		return tab;
	}

	private static JScrollPane wrapScrolling(JPanel content)
	{
		final JScrollPane scroll = new JScrollPane(content,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		return scroll;
	}

	void display(TaskView current, List<TaskView> past)
	{
		this.currentTask = current;
		this.history = past == null ? Collections.emptyList() : past;
		rebuild();
	}

	private void rebuild()
	{
		rebuildCurrent();
		rebuildHistory();
	}

	private void rebuildCurrent()
	{
		currentContainer.removeAll();

		if (currentTask == null)
		{
			currentContainer.add(placeholder("No slayer task assigned."));
		}
		else
		{
			currentContainer.add(buildTaskBox(currentTask, true));
		}

		currentContainer.revalidate();
		currentContainer.repaint();
		revalidate();
		repaint();
	}

	private int closedSessionCount()
	{
		if (currentTask == null)
		{
			return 0;
		}
		int count = 0;
		for (TaskView.SessionView session : currentTask.getSessionViews())
		{
			if (!session.isOpen())
			{
				count++;
			}
		}
		return count;
	}

	private void mergeSessions()
	{
		final List<TaskView.SessionView> closed = new java.util.ArrayList<>();
		for (TaskView.SessionView session : currentTask.getSessionViews())
		{
			if (!session.isOpen())
			{
				closed.add(session);
			}
		}

		final TaskView.SessionView source = (TaskView.SessionView) JOptionPane.showInputDialog(
			this, "Select the session to combine:", "Merge sessions",
			JOptionPane.PLAIN_MESSAGE, null, closed.toArray(), closed.get(closed.size() - 1));
		if (source == null)
		{
			return;
		}

		closed.remove(source);
		final TaskView.SessionView target = (TaskView.SessionView) JOptionPane.showInputDialog(
			this, "Combine it into:", "Merge sessions",
			JOptionPane.PLAIN_MESSAGE, null, closed.toArray(), closed.get(closed.size() - 1));
		if (target != null)
		{
			plugin.mergeSessions(source.getSessionId(), target.getSessionId());
		}
	}

	private void resumeSession()
	{
		final List<TaskView.SessionView> closed = new java.util.ArrayList<>();
		for (TaskView.SessionView session : currentTask.getSessionViews())
		{
			if (!session.isOpen())
			{
				closed.add(session);
			}
		}
		if (closed.isEmpty())
		{
			return;
		}

		final TaskView.SessionView selected = closed.size() == 1
			? closed.get(0)
			: (TaskView.SessionView) JOptionPane.showInputDialog(
				this, "Select the session to resume:", "Resume session",
				JOptionPane.PLAIN_MESSAGE, null, closed.toArray(), closed.get(closed.size() - 1));
		if (selected != null)
		{
			plugin.resumeSession(selected.getSessionId());
		}
	}

	private void rebuildHistory()
	{
		historyContainer.removeAll();

		if (history.isEmpty())
		{
			historyHeaderLabel.setText("No completed tasks yet.");
			historyContainer.add(placeholder("Finished tasks will appear here."));
		}
		else
		{
			long totalLoot = 0;
			long totalSupplies = 0;
			for (TaskView task : history)
			{
				totalLoot += task.getLootValue();
				totalSupplies += task.getSupplyCost();
			}

			final long total = config.trackSupplies()
				? totalLoot - totalSupplies
				: totalLoot;

			historyHeaderLabel.setText(history.size() + " tasks · " + gp(total) + " total");

			for (TaskView task : history)
			{
				historyContainer.add(buildTaskBox(task, false));
			}
		}

		historyContainer.revalidate();
		historyContainer.repaint();
	}

	private JLabel placeholder(String text)
	{
		final JLabel label = new JLabel(text, SwingConstants.CENTER);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(Color.GRAY);
		label.setBorder(new EmptyBorder(16, 8, 16, 8));
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private JPanel buildTaskBox(TaskView task, boolean isCurrent)
	{
		final JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setBorder(new EmptyBorder(6, 6, 6, 6));
		box.setAlignmentX(Component.LEFT_ALIGNMENT);

		final JPanel title = row(task.getTaskName(), null, Color.WHITE,
			FontManager.getRunescapeBoldFont());
		title.add(isCurrent ? currentActions(task) : historyActions(task), BorderLayout.EAST);
		box.add(title);

		if (task.getTaskLocation() != null)
		{
			box.add(row(task.getTaskLocation(), null, Color.LIGHT_GRAY, FontManager.getRunescapeSmallFont()));
		}

		// Deliberately "tracked" rather than a progress fraction against the assignment size.
		// Enabling the plugin partway through a task starts this at zero while the game's own
		// counter is already down, and implying otherwise would just look broken.
		final String killLine = task.getKills() + (task.getKills() == 1 ? " kill" : " kills")
			+ " tracked · " + formatDuration(task.getOnTaskTicks());
		box.add(row(killLine, null, Color.LIGHT_GRAY, FontManager.getRunescapeSmallFont()));

		// Sessions are shown so the supply attribution can be sanity-checked rather than
		// taken on trust — "3 sessions, 47 min on task" is checkable against reality.
		final String sessionLine = task.getSessions()
			+ (task.getSessions() == 1 ? " session" : " sessions");
		box.add(row(sessionLine, null, Color.GRAY, FontManager.getRunescapeSmallFont()));

		if (!isCurrent && task.getEndedAt() > 0)
		{
			box.add(row(TIMESTAMP_FMT.format(Instant.ofEpochMilli(task.getEndedAt())
				.atZone(ZoneId.systemDefault())), null, Color.GRAY, FontManager.getRunescapeSmallFont()));
		}
		box.add(separator());

		// Each section owns what it totals: the drops header sits directly above the drops it
		// adds up, and supplies below them. Reading a total and then having to look elsewhere
		// for the items behind it was the confusing part of showing all three totals together.
		box.add(collapsibleHeader("Drops", dropsExpanded, gp(task.getLootValue()), PROFIT_GREEN,
			() -> dropsExpanded = !dropsExpanded));

		if (dropsExpanded && !task.getRows().isEmpty())
		{
			box.add(buildItemGrid(task));
		}

		if (config.trackSupplies())
		{
			box.add(separator());
			box.add(collapsibleHeader("Supplies", suppliesExpanded, "-" + gp(task.getSupplyCost()),
				SUPPLY_ORANGE, () -> suppliesExpanded = !suppliesExpanded));

			// Breakdown of the line above. Sums to it exactly, since both come from the
			// same per-family diff.
			if (suppliesExpanded)
			{
				for (TaskView.LootRow supply : task.getSupplyRows())
				{
					box.add(supplyBreakdownRow(supply));
				}
			}

			// Last, under everything it's derived from.
			box.add(separator());
			final long net = task.getLootValue() - task.getSupplyCost();
			box.add(row("Profit", gp(net), net >= 0 ? PROFIT_GREEN : LOSS_RED,
				FontManager.getRunescapeBoldFont()));
		}

		final JPanel spaced = new JPanel(new BorderLayout());
		spaced.setBackground(ColorScheme.DARK_GRAY_COLOR);
		spaced.setBorder(new EmptyBorder(0, 0, 6, 0));
		spaced.setAlignmentX(Component.LEFT_ALIGNMENT);
		spaced.add(box, BorderLayout.CENTER);
		return spaced;
	}

	private JPanel currentActions(TaskView task)
	{
		final JPanel actions = actionToolbar();
		if (task.isSessionOpen())
		{
			final JButton pause = iconButton(PAUSE_ICON, PAUSE_ICON_HOVER, "End current session");
			pause.addActionListener(e -> plugin.endCurrentSession());
			actions.add(pause);
		}
		else if (!task.isCompleted())
		{
			final JButton start = iconButton(START_ICON, START_ICON_HOVER, "Start a new session");
			start.addActionListener(e -> plugin.startNewSession());
			actions.add(start);

			final JButton resume = iconButton(RESUME_ICON, RESUME_ICON_HOVER,
				task.isCanResumeSession()
				? "Resume a closed session and pool new activity into it"
				: "No closed session is available to resume");
			resume.setEnabled(task.isCanResumeSession());
			resume.addActionListener(e -> resumeSession());
			actions.add(resume);

			final JButton merge = iconButton(MERGE_ICON, MERGE_ICON_HOVER,
				closedSessionCount() >= 2
				? "Merge two closed sessions"
				: "At least two closed sessions are required");
			merge.setEnabled(closedSessionCount() >= 2);
			merge.addActionListener(e -> mergeSessions());
			actions.add(merge);
		}

		final JButton reset = iconButton(DELETE_ICON, DELETE_ICON_HOVER, "Reset current task record");
		reset.addActionListener(e ->
		{
			final int answer = JOptionPane.showConfirmDialog(this,
				"Reset the current " + task.getTaskName() + " record?",
				"Reset current task", JOptionPane.YES_NO_OPTION);
			if (answer == JOptionPane.YES_OPTION)
			{
				plugin.resetActiveTask();
			}
		});
		actions.add(reset);
		return actions;
	}

	private JPanel historyActions(TaskView task)
	{
		final JPanel actions = actionToolbar();

		final boolean canResume = currentTask != null && !currentTask.isCompleted()
			&& sameTask(currentTask, task);
		final JButton resume = iconButton(START_ICON, START_ICON_HOVER,
			canResume ? "Pool this record into the current matching task"
				: "Requires an active task with the same monster and location");
		resume.setEnabled(canResume);
		resume.addActionListener(e -> plugin.poolHistoryIntoCurrent(task.getAssignmentId()));
		actions.add(resume);

		final List<TaskView> mergeCandidates = new java.util.ArrayList<>();
		for (TaskView candidate : history)
		{
			if (!candidate.getAssignmentId().equals(task.getAssignmentId())
				&& sameTask(candidate, task))
			{
				mergeCandidates.add(candidate);
			}
		}
		final JButton merge = iconButton(MERGE_ICON, MERGE_ICON_HOVER,
			mergeCandidates.isEmpty()
			? "No other matching history record is available"
			: "Combine all " + mergeCandidates.size() + " other matching "
				+ (mergeCandidates.size() == 1 ? "record" : "records") + " into this one");
		merge.setEnabled(!mergeCandidates.isEmpty());
		merge.addActionListener(e -> mergeHistoryTask(task, mergeCandidates));
		actions.add(merge);

		final JButton delete = iconButton(DELETE_ICON, DELETE_ICON_HOVER,
			"Delete this history record");
		delete.addActionListener(e ->
		{
			final int answer = JOptionPane.showConfirmDialog(this,
				"Delete this " + task.getTaskName() + " record?",
				"Delete task history", JOptionPane.YES_NO_OPTION);
			if (answer == JOptionPane.YES_OPTION)
			{
				plugin.deleteHistoryTask(task.getAssignmentId());
			}
		});
		actions.add(delete);
		return actions;
	}

	private static JPanel actionToolbar()
	{
		final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
		actions.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		return actions;
	}

	private static JButton iconButton(Icon icon, Icon rollover, String tooltip)
	{
		final JButton button = new JButton(icon);
		button.setRolloverIcon(rollover);
		button.setToolTipText(tooltip);
		button.setPreferredSize(new Dimension(18, 16));
		SwingUtil.removeButtonDecorations(button);
		return button;
	}

	private static BufferedImage buildMergeIcon()
	{
		final BufferedImage image = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
		final java.awt.Graphics2D graphics = image.createGraphics();
		graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
			java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setColor(new Color(170, 170, 170));
		graphics.drawLine(2, 4, 11, 4);
		graphics.drawLine(8, 1, 11, 4);
		graphics.drawLine(8, 7, 11, 4);
		graphics.drawLine(11, 10, 2, 10);
		graphics.drawLine(5, 7, 2, 10);
		graphics.drawLine(5, 13, 2, 10);
		graphics.dispose();
		return image;
	}

	/**
	 * Pools every matching record into this one, after confirming how many that is.
	 *
	 * <p>Confirmed rather than done outright because a merge can't be undone from the panel:
	 * the sources are folded in and dropped, and separating them again would mean knowing which
	 * kill and which drop came from where, which nothing records.
	 */
	private void mergeHistoryTask(TaskView target, List<TaskView> candidates)
	{
		final int answer = JOptionPane.showConfirmDialog(this,
			"Combine " + candidates.size() + " other " + target.getTaskName() + " "
				+ (candidates.size() == 1 ? "record" : "records") + " into this one?"
				+ "\nThis cannot be undone.",
			"Merge task history", JOptionPane.YES_NO_OPTION);
		if (answer == JOptionPane.YES_OPTION)
		{
			plugin.mergeAllHistoryTasks(target.getAssignmentId());
		}
	}

	/** Matches on the monster alone — see SlayerTaskLootPlugin.sameTask(), which must agree. */
	private static boolean sameTask(TaskView left, TaskView right)
	{
		return Objects.equals(left.getTaskName(), right.getTaskName());
	}

	/** A section total whose arrow shows and hides the entries making it up. */
	private JPanel collapsibleHeader(String label, boolean expanded, String value, Color valueColor,
		Runnable onToggle)
	{
		final JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);

		final JButton toggle = new JButton((expanded ? "▾ " : "▸ ") + label);
		toggle.setFont(FontManager.getRunescapeSmallFont());
		toggle.setForeground(Color.LIGHT_GRAY);
		toggle.setBorderPainted(false);
		toggle.setContentAreaFilled(false);
		toggle.setFocusPainted(false);
		toggle.setHorizontalAlignment(SwingConstants.LEFT);
		toggle.setBorder(BorderFactory.createEmptyBorder());
		toggle.addActionListener(e ->
		{
			onToggle.run();
			rebuild();
		});
		panel.add(toggle, BorderLayout.WEST);

		final JLabel valueLabel = new JLabel(value);
		valueLabel.setFont(FontManager.getRunescapeSmallFont());
		valueLabel.setForeground(valueColor);
		panel.add(valueLabel, BorderLayout.EAST);
		return panel;
	}

	private JPanel buildItemGrid(TaskView task)
	{
		final JPanel list = new JPanel();
		list.setLayout(new GridLayout(0, 1, 0, 2));
		list.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		list.setAlignmentX(Component.LEFT_ALIGNMENT);

		for (TaskView.LootRow item : task.getRows())
		{
			list.add(buildItemRow(item));
		}

		return list;
	}

	private JPanel buildItemRow(TaskView.LootRow item)
	{
		final JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		final JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(32, 32));
		final AsyncBufferedImage image = itemManager.getImage(
			item.getItemId(), item.getQuantity(), item.getQuantity() > 1);
		image.addTo(icon);
		row.add(icon, BorderLayout.WEST);

		final JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		final JLabel name = new JLabel(item.getName());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(Color.WHITE);
		text.add(name);

		final StringBuilder detail = new StringBuilder();
		if (item.getQuantity() > 1)
		{
			detail.append('x').append(QuantityFormatter.quantityToStackSize(item.getQuantity()));
		}
		if (config.showItemValues())
		{
			if (detail.length() > 0)
			{
				detail.append("  ");
			}
			detail.append(gp(item.getValue()));
		}

		if (detail.length() > 0)
		{
			final JLabel sub = new JLabel(detail.toString());
			sub.setFont(FontManager.getRunescapeSmallFont());
			sub.setForeground(Color.LIGHT_GRAY);
			text.add(sub);
		}

		row.add(text, BorderLayout.CENTER);
		row.setToolTipText(item.getName() + " — " + QuantityFormatter.formatNumber(item.getValue()) + " gp");

		return row;
	}

	/**
	 * One indented line under the Supplies total, e.g. "Prayer potion  x14 doses" against
	 * its cost. Text-only and compact deliberately — with icons this list would dwarf the
	 * loot it's meant to be a footnote to.
	 */
	private static JPanel supplyBreakdownRow(TaskView.LootRow supply)
	{
		final JPanel panel = new JPanel(new BorderLayout(4, 0));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);

		final StringBuilder left = new StringBuilder("   ").append(supply.getName());
		if (supply.getQuantity() > 0)
		{
			left.append("  x").append(QuantityFormatter.quantityToStackSize(supply.getQuantity()));
			if (!supply.getUnit().isEmpty())
			{
				left.append(' ').append(supply.getUnit());
			}
		}

		final JLabel nameLabel = new JLabel(left.toString());
		nameLabel.setFont(FontManager.getRunescapeSmallFont());
		nameLabel.setForeground(Color.GRAY);
		panel.add(nameLabel, BorderLayout.WEST);

		final JLabel valueLabel = new JLabel(gp(supply.getValue()));
		valueLabel.setFont(FontManager.getRunescapeSmallFont());
		valueLabel.setForeground(Color.GRAY);
		panel.add(valueLabel, BorderLayout.EAST);

		// A charged item is one line for the weapon, with what its charges actually cost on hover.
		// Listing every rune and vial as its own supply made a scythe look like spellcasting and
		// buried the potions and food underneath it.
		if (!supply.getBreakdown().isEmpty())
		{
			final StringBuilder tooltip = new StringBuilder("<html>").append(supply.getName());
			for (String component : supply.getBreakdown())
			{
				tooltip.append("<br>&nbsp;&nbsp;").append(component);
			}
			panel.setToolTipText(tooltip.append("</html>").toString());
			nameLabel.setToolTipText(panel.getToolTipText());
		}

		return panel;
	}

	/** A label line, optionally with a right-aligned value. */
	private static JPanel row(String left, String right, Color color, Font font)
	{
		final JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);

		final JLabel leftLabel = new JLabel(left);
		leftLabel.setFont(font);
		leftLabel.setForeground(right == null ? color : Color.LIGHT_GRAY);
		panel.add(leftLabel, BorderLayout.WEST);

		if (right != null)
		{
			final JLabel rightLabel = new JLabel(right);
			rightLabel.setFont(font);
			rightLabel.setForeground(color);
			panel.add(rightLabel, BorderLayout.EAST);
		}

		return panel;
	}

	private static JPanel separator()
	{
		final JPanel line = new JPanel();
		line.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		line.setBorder(new MatteBorder(1, 0, 0, 0, ColorScheme.MEDIUM_GRAY_COLOR));
		line.setPreferredSize(new Dimension(0, 5));
		line.setAlignmentX(Component.LEFT_ALIGNMENT);
		return line;
	}

	private static String gp(long value)
	{
		final String formatted = QuantityFormatter.quantityToStackSize(Math.abs(value));
		return (value < 0 ? "-" : "") + formatted + " gp";
	}

	/** Formats on-task game ticks as h:mm:ss / m:ss. */
	private static String formatDuration(int ticks)
	{
		final long totalSeconds = (long) ticks * 600 / 1000;
		final long hours = totalSeconds / 3600;
		final long minutes = (totalSeconds % 3600) / 60;
		final long seconds = totalSeconds % 60;

		return hours > 0
			? String.format("%d:%02d:%02d", hours, minutes, seconds)
			: String.format("%d:%02d", minutes, seconds);
	}
}
