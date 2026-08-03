package com.slayertaskloot;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(SlayerTaskLootConfig.GROUP)
public interface SlayerTaskLootConfig extends Config
{
	String GROUP = "slayertaskloot";

	// -------------------------------------------------------------------------
	// Display
	// -------------------------------------------------------------------------

	@ConfigSection(
		name = "Display",
		description = "What the side panel shows",
		position = 0
	)
	String displaySection = "display";

	@ConfigItem(
		keyName = "showItemValues",
		name = "Show item values",
		description = "Show the GE value alongside each item's quantity",
		section = displaySection,
		position = 0
	)
	default boolean showItemValues()
	{
		return true;
	}

	@ConfigItem(
		keyName = "historySize",
		name = "Tasks to remember",
		description = "How many completed tasks to keep in the History tab. Set to 0 to keep none.",
		section = displaySection,
		position = 1
	)
	@Range(min = 0, max = 50)
	default int historySize()
	{
		return 10;
	}

	// -------------------------------------------------------------------------
	// Loot
	// -------------------------------------------------------------------------

	@ConfigSection(
		name = "Loot",
		description = "Controls which drops get credited to the task",
		position = 1
	)
	String lootSection = "loot";

	@ConfigItem(
		keyName = "lootCreditWindow",
		name = "Late loot window (seconds)",
		description = "How long after a task kill its drop can still arrive and be credited. "
			+ "Needed for bosses whose loot isn't dropped when they die and has to be collected, "
			+ "such as Araxxor. Late drops are only credited when they come from a monster already "
			+ "confirmed as a target of this task, so widening this doesn't pull in stray kills. "
			+ "Set to 0 to credit only drops that land as the monster dies.",
		section = lootSection,
		position = 0
	)
	@Range(min = 0, max = 600)
	default int lootCreditWindow()
	{
		return 120;
	}

	// -------------------------------------------------------------------------
	// Supplies
	// -------------------------------------------------------------------------

	@ConfigSection(
		name = "Supplies",
		description = "Controls which supply usage gets charged to the task",
		position = 2
	)
	String suppliesSection = "supplies";

	@ConfigItem(
		keyName = "trackSupplies",
		name = "Track supplies and profit",
		description = "Measure what you use up on task and show profit after supplies.<br>"
			+ "When off, no supply measurement is done at all and the panel reports the gross "
			+ "GE value of the task's drops only. The settings below have no effect.",
		section = suppliesSection,
		position = 0
	)
	default boolean trackSupplies()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sessionTimeout",
		name = "End session after (minutes)",
		description = "A session stays open while task kills keep happening. After this long with "
			+ "no credited kill it closes, and supplies stop counting toward the task until the "
			+ "next kill. Keeps unrelated activity off the task's bill.",
		section = suppliesSection,
		position = 1
	)
	@Range(min = 1, max = 60)
	default int sessionTimeout()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "graceWindow",
		name = "Grace window (seconds)",
		description = "Supplies used this long before a session opens still count, so teleports "
			+ "out and boosts drunk before the first kill are included. Set to 0 to count only "
			+ "from the first credited kill onward.",
		section = suppliesSection,
		position = 2
	)
	@Range(min = 0, max = 300)
	default int graceWindow()
	{
		return 30;
	}
}
