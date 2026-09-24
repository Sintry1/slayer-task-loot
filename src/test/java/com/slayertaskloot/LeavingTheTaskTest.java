package com.slayertaskloot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.runelite.api.MenuAction;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

/**
 * What stops a task being billed for everything done after leaving it.
 *
 * <p>Reported from an Araxxor task: the session ran on for the whole idle timeout after the last
 * kill, and the supplies column picked up a Grand Exchange purchase crafted into an amulet of
 * rancour, the noxious point and pommel combined into a halberd (taking the dropped blade out of
 * the drops with them), brimstone keys and a farm run.
 */
public class LeavingTheTaskTest
{
	private static final long MINUTE = 60_000L;

	@Test
	public void onlyAJumpWalkingCannotMakeIsATeleport()
	{
		final WorldPoint start = new WorldPoint(3200, 3200, 0);
		assertFalse(SlayerTaskLootPlugin.isTeleport(start, new WorldPoint(3202, 3201, 0)));
		assertFalse("an agility shortcut",
			SlayerTaskLootPlugin.isTeleport(start, new WorldPoint(3206, 3200, 0)));
		assertFalse("a staircase", SlayerTaskLootPlugin.isTeleport(start, new WorldPoint(3200, 3200, 1)));
		assertTrue(SlayerTaskLootPlugin.isTeleport(start, new WorldPoint(3200, 3200 + 6400, 0)));
		assertTrue(SlayerTaskLootPlugin.isTeleport(start,
			new WorldPoint(3200 + SlayerTaskLootPlugin.TELEPORT_MIN_TILES + 1, 3200, 0)));
		assertFalse("no reading yet", SlayerTaskLootPlugin.isTeleport(null, start));
	}

	@Test
	public void instanceToInstanceIsJudgedByTheTilesTheInstancesWereCopiedFrom()
	{
		// Araxxor's lair and a house can be placed next to each other in instance space.
		final WorldPoint lair = new WorldPoint(7000, 5000, 0);
		final WorldPoint house = new WorldPoint(7004, 5003, 0);
		final WorldPoint lairTemplate = new WorldPoint(3630, 9800, 0);
		final WorldPoint houseTemplate = new WorldPoint(1860, 5700, 0);

		assertTrue("a tablet from the lair to the house",
			SlayerTaskLootPlugin.isTeleport(lair, house, lairTemplate, houseTemplate, true));
		assertFalse("walking between two rooms of the house, which are scattered templates",
			SlayerTaskLootPlugin.isTeleport(house, new WorldPoint(7005, 5003, 0),
				houseTemplate, new WorldPoint(1900, 5760, 0), false));
		assertFalse("crossing a region boundary, which also shows a loading screen",
			SlayerTaskLootPlugin.isTeleport(lairTemplate, new WorldPoint(3631, 9800, 0),
				lairTemplate, new WorldPoint(3631, 9800, 0), true));
	}

	@Test
	public void aKillResumesTheSessionATeleportPausedWithinTheTimeout()
	{
		final long now = 100 * MINUTE;
		assertTrue(SlayerTaskLootPlugin.isResumableTeleportPause("TELEPORT", now - 3 * MINUTE, now, 10));
		assertFalse("past the idle timeout",
			SlayerTaskLootPlugin.isResumableTeleportPause("TELEPORT", now - 11 * MINUTE, now, 10));
		assertFalse("ended rather than paused",
			SlayerTaskLootPlugin.isResumableTeleportPause("IDLE_TIMEOUT", now - MINUTE, now, 10));
		assertFalse(SlayerTaskLootPlugin.isResumableTeleportPause(null, now - MINUTE, now, 10));
	}

	@Test
	public void resumingAPausedSessionAddsASegmentToTheSameSession()
	{
		final TaskLootRecord record = new TaskLootRecord("Araxytes", null, 100, 1L);
		final TaskSession session = record.openNewSession(1_000, false);
		record.closeSession(session.getSessionId(), 2_000, "TELEPORT");
		assertEquals("TELEPORT", session.lastCloseReason());

		record.resumeSession(session.getSessionId(), 3_000, false);

		assertTrue(session.isOpen());
		assertNull(session.lastCloseReason());
		assertEquals(1, record.getTaskSessions().size());
		assertEquals(2, session.getSegments().size());
		assertFalse(session.getSegments().get(1).isOpenedManually());
	}

	@Test
	public void usingAnItemOnSomethingMayBeACraftButCastingASpellIsNot()
	{
		assertTrue(SlayerTaskLootPlugin.isItemCraftAction("Use", MenuAction.WIDGET_TARGET_ON_WIDGET));
		assertTrue(SlayerTaskLootPlugin.isItemCraftAction("Use", MenuAction.WIDGET_TARGET_ON_GAME_OBJECT));
		assertTrue(SlayerTaskLootPlugin.isItemCraftAction("Use", MenuAction.WIDGET_TARGET_ON_NPC));
		assertTrue(SlayerTaskLootPlugin.isItemCraftAction("Combine", MenuAction.CC_OP));
		assertFalse("selecting the item, not using it yet",
			SlayerTaskLootPlugin.isItemCraftAction("Use", MenuAction.WIDGET_TARGET));
		assertFalse("alchemy", SlayerTaskLootPlugin.isItemCraftAction("Cast", MenuAction.WIDGET_TARGET_ON_WIDGET));
		assertFalse(SlayerTaskLootPlugin.isItemCraftAction("Eat", MenuAction.CC_OP));
	}

	@Test
	public void aCraftNeedsSomethingNewToHaveBeenMade()
	{
		assertTrue("a noxious halberd", SupplyTracker.isCraftProduct("Noxious halberd", false, 1));
		assertFalse("alchemy's coins", SupplyTracker.isCraftProduct("#currency", false, 1000));
		assertFalse("potions decanted", SupplyTracker.isCraftProduct("Prayer potion", true, 4));
		assertFalse("a weapon poisoned is the same weapon", SupplyTracker.isCraftProduct("#1215", false, 0));
		assertFalse("something used up", SupplyTracker.isCraftProduct("Noxious point", false, -1));
	}

	@Test
	public void foodAndCoinsStillBillOnACraftingTick()
	{
		assertTrue(SupplyTracker.isCraftInput("Noxious blade", false, -1));
		assertTrue(SupplyTracker.isCraftInput("Amulet of torture", false, -1));
		assertFalse("eaten", SupplyTracker.isCraftInput("wild pie", true, -1));
		assertFalse("spent", SupplyTracker.isCraftInput("#currency", false, -500));
		assertFalse("the product", SupplyTracker.isCraftInput("Noxious halberd", false, 1));
	}
}
