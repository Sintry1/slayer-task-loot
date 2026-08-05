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
	String TOOLTIP_OPEN = "<table width='260'><tr><td>";
	String TOOLTIP_CLOSE = "</td></tr></table>";

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
		description = TOOLTIP_OPEN + "Show each item's value next to its quantity."
			+ TOOLTIP_CLOSE,
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
		description = TOOLTIP_OPEN
			+ "How many finished tasks to keep in the History tab. 0 keeps none."
			+ TOOLTIP_CLOSE,
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
		description = "Which drops count toward the task",
		position = 1
	)
	String lootSection = "loot";

	@ConfigItem(
		keyName = "lootCreditWindow",
		name = "Late loot window (seconds)",
		description = TOOLTIP_OPEN
			+ "How long after a kill its drop can still arrive and count, for bosses whose loot "
			+ "has to be collected. 0 counts only drops that land as the monster dies."
			+ TOOLTIP_CLOSE,
		section = lootSection,
		position = 0
	)
	@Range(min = 0, max = 600)
	default int lootCreditWindow()
	{
		return 120;
	}

	@ConfigItem(
		keyName = "lootMode",
		name = "Count loot",
		description = TOOLTIP_OPEN
			+ "Dropped counts every drop. Collected counts only what you pick up, including "
			+ "items sent directly to a storage container."
			+ TOOLTIP_CLOSE,
		section = lootSection,
		position = 1
	)
	default LootMode lootMode()
	{
		return LootMode.DROPPED;
	}

	// -------------------------------------------------------------------------
	// Supplies
	// -------------------------------------------------------------------------

	@ConfigSection(
		name = "Supplies",
		description = "What supply usage counts toward the task",
		position = 2
	)
	String suppliesSection = "supplies";

	@ConfigItem(
		keyName = "trackSupplies",
		name = "Track supplies and profit",
		description = TOOLTIP_OPEN
			+ "Track what you use up and show profit after supplies. When off, the panel shows "
			+ "drop value only and the settings below do nothing."
			+ TOOLTIP_CLOSE,
		section = suppliesSection,
		position = 0
	)
	default boolean trackSupplies()
	{
		return true;
	}

	@ConfigItem(
		keyName = "netMatchingDrops",
		name = "Net matching drops",
		description = TOOLTIP_OPEN
			+ "Cancel drops against supplies of the same item. Five sharks dropped and two eaten "
			+ "show as three sharks and no shark cost."
			+ TOOLTIP_CLOSE,
		section = suppliesSection,
		position = 1
	)
	default boolean netMatchingDrops()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sessionTimeout",
		name = "End session after (minutes)",
		description = TOOLTIP_OPEN
			+ "How long without a task kill before supplies stop counting toward the task."
			+ TOOLTIP_CLOSE,
		section = suppliesSection,
		position = 2
	)
	@Range(min = 1, max = 60)
	default int sessionTimeout()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "graceWindow",
		name = "Grace window (seconds)",
		description = TOOLTIP_OPEN
			+ "How long before your first kill supplies still count, so teleports and boosts are "
			+ "included. 0 counts from the first kill onward."
			+ TOOLTIP_CLOSE,
		section = suppliesSection,
		position = 3
	)
	@Range(min = 0, max = 300)
	default int graceWindow()
	{
		return 30;
	}

	// -------------------------------------------------------------------------
	// Charged items
	// -------------------------------------------------------------------------

	@ConfigSection(
		name = "Charged items",
		description = "What your charged items are filled with",
		position = 3
	)
	String chargedItemsSection = "chargedItems";

	@ConfigItem(
		keyName = "eyeOfAyakCharge",
		name = "Eye of ayak",
		description = TOOLTIP_OPEN
			+ "What your eye of ayak is charged with. Automatic reads it from the message shown "
			+ "when you charge it."
			+ TOOLTIP_CLOSE,
		section = chargedItemsSection,
		position = 0
	)
	default EyeOfAyakCharge eyeOfAyakCharge()
	{
		return EyeOfAyakCharge.AUTOMATIC;
	}

	@ConfigItem(
		keyName = "blowpipeDart",
		name = "Blowpipe dart",
		description = TOOLTIP_OPEN
			+ "Which dart is in your blowpipe. Automatic reads it when you check the blowpipe, "
			+ "so darts aren't counted until you do."
			+ TOOLTIP_CLOSE,
		section = chargedItemsSection,
		position = 1
	)
	default BlowpipeDart blowpipeDart()
	{
		return BlowpipeDart.AUTOMATIC;
	}

	@ConfigItem(
		keyName = "cannonballType",
		name = "Cannonball",
		description = TOOLTIP_OPEN
			+ "Which ammunition your cannon is loaded with. Automatic reads it when you load or "
			+ "unload the cannon."
			+ TOOLTIP_CLOSE,
		section = chargedItemsSection,
		position = 2
	)
	default CannonballType cannonballType()
	{
		return CannonballType.AUTOMATIC;
	}

	@ConfigItem(
		keyName = "tomePage",
		name = "Tome of fire page",
		description = TOOLTIP_OPEN
			+ "Which page your tome of fire is filled with. The game gives no way to tell, so "
			+ "casts are priced at whichever is set here."
			+ TOOLTIP_CLOSE,
		section = chargedItemsSection,
		position = 3
	)
	default TomePage tomePage()
	{
		return TomePage.BURNT_PAGE;
	}
}
