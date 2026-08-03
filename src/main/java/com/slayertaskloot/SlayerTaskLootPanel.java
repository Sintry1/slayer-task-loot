package com.slayertaskloot;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
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
import net.runelite.client.util.QuantityFormatter;

class SlayerTaskLootPanel extends PluginPanel
{
	private static final Color PROFIT_GREEN = new Color(0, 200, 83);
	private static final Color LOSS_RED = new Color(220, 70, 70);
	private static final Color SUPPLY_ORANGE = new Color(220, 150, 60);
	private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private final SlayerTaskLootPlugin plugin;
	private final SlayerTaskLootConfig config;
	private final ItemManager itemManager;

	private final JPanel currentContainer = new JPanel();
	private final JPanel historyContainer = new JPanel();
	private JLabel historyHeaderLabel;

	private TaskView currentTask;
	private List<TaskView> history = Collections.emptyList();

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

		final JButton reset = new JButton("Reset current task");
		reset.setFocusPainted(false);
		reset.addActionListener(e -> plugin.resetActiveTask());
		tab.add(reset, BorderLayout.SOUTH);

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

		box.add(row(task.getTaskName(), null, Color.WHITE, FontManager.getRunescapeBoldFont()));

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

		// --- Value summary ---
		box.add(row("Drops", gp(task.getLootValue()), PROFIT_GREEN, FontManager.getRunescapeSmallFont()));

		if (config.trackSupplies())
		{
			box.add(row("Supplies", "-" + gp(task.getSupplyCost()), SUPPLY_ORANGE,
				FontManager.getRunescapeSmallFont()));

			// Breakdown of the line above. Sums to it exactly, since both come from the
			// same per-family diff.
			for (TaskView.LootRow supply : task.getSupplyRows())
			{
				box.add(supplyBreakdownRow(supply));
			}

			final long net = task.getLootValue() - task.getSupplyCost();
			box.add(row("Profit", gp(net), net >= 0 ? PROFIT_GREEN : LOSS_RED,
				FontManager.getRunescapeBoldFont()));
		}

		// --- Item grid ---
		if (!task.getRows().isEmpty())
		{
			box.add(separator());
			box.add(buildItemGrid(task));
		}

		final JPanel spaced = new JPanel(new BorderLayout());
		spaced.setBackground(ColorScheme.DARK_GRAY_COLOR);
		spaced.setBorder(new EmptyBorder(0, 0, 6, 0));
		spaced.setAlignmentX(Component.LEFT_ALIGNMENT);
		spaced.add(box, BorderLayout.CENTER);
		return spaced;
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
