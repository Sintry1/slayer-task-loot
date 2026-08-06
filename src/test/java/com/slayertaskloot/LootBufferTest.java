package com.slayertaskloot;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.game.ItemStack;
import org.junit.Test;

public class LootBufferTest
{
	@Test
	public void bufferedLootDoesNotShareRuneliteBackingList()
	{
		final List<ItemStack> eventItems = new ArrayList<>();
		eventItems.add(new ItemStack(995, 100));

		final List<ItemStack> buffered = SlayerTaskLootPlugin.copyLoot(eventItems);
		eventItems.clear();

		assertEquals(1, buffered.size());
		assertEquals(995, buffered.get(0).getId());
		assertEquals(100, buffered.get(0).getQuantity());
	}

	/**
	 * A fight longer than the configured window still counts as one fight, so an Araxxor kill
	 * doesn't lose the supplies that paid for it while a regular araxyte keeps them.
	 */
	@Test
	public void graceWindowStretchesToCoverTheCurrentFight()
	{
		// 50-tick window, fight started 120 ticks ago: the fight wins.
		assertEquals(80, SlayerTaskLootPlugin.graceCutoff(200, 50, 80, 500));

		// Short fight inside the window: the window wins, never shortened by combat.
		assertEquals(150, SlayerTaskLootPlugin.graceCutoff(200, 50, 190, 500));

		// Not in combat at all.
		assertEquals(150, SlayerTaskLootPlugin.graceCutoff(200, 50, -1, 500));

		// An unbroken streak can't reach back past the ceiling.
		assertEquals(500, SlayerTaskLootPlugin.graceCutoff(1000, 50, 20, 500));

		// A window of zero means what it says.
		assertEquals(200, SlayerTaskLootPlugin.graceCutoff(200, 0, 20, 500));

		// The anchor is back-dated to before the attack that produced the first hitsplat, so a
		// charge on the throwing tick still falls inside it rather than one tick outside.
		assertEquals(78, SlayerTaskLootPlugin.graceCutoff(200, 50, 78, 500));
	}

	@Test
	public void resolvesLoadedBlowpipeDartNames()
	{
		assertEquals(806, SlayerTaskLootPlugin.dartId("Bronze dart"));
		assertEquals(25849, SlayerTaskLootPlugin.dartId("Amethyst dart"));
		assertEquals(11230, SlayerTaskLootPlugin.dartId("Dragon dart"));
		assertEquals(-1, SlayerTaskLootPlugin.dartId("Unknown dart"));
	}

	@Test
	public void groundPickupAndInventoryGainAreOnlyCountedOnce()
	{
		final Map<Integer, Integer> ignores = new HashMap<>();
		ignores.put(200, 3);

		assertEquals(0, SlayerTaskLootPlugin.applyPickupInventoryIgnore(ignores, 200, 3));
		assertEquals(0, ignores.size());
	}

	@Test
	public void directToContainerPickupKeepsItsDespawnSignal()
	{
		final Map<Integer, Integer> ignores = new HashMap<>();
		ignores.put(200, 3);

		// No INV gain is presented, so the separately queued despawn quantity remains intact.
		assertEquals(0, SlayerTaskLootPlugin.applyPickupInventoryIgnore(ignores, 200, 0));
		assertEquals(Integer.valueOf(3), ignores.get(200));
	}

	@Test
	public void pickupSuppressionOnlyAbsorbsItsOwnQuantity()
	{
		final Map<Integer, Integer> ignores = new HashMap<>();
		ignores.put(200, 2);

		assertEquals(3, SlayerTaskLootPlugin.applyPickupInventoryIgnore(ignores, 200, 5));
		assertEquals(0, ignores.size());
	}

	@Test
	public void recognisesPortableStorageActions()
	{
		assertEquals(true, SlayerTaskLootPlugin.isRemoteStorageAction("Fill"));
		assertEquals(true, SlayerTaskLootPlugin.isRemoteStorageAction("Bank-All"));
		assertEquals(true, SlayerTaskLootPlugin.isRemoteStorageAction("Deposit"));
		assertEquals(false, SlayerTaskLootPlugin.isRemoteStorageAction("Drink"));
	}

	@Test
	public void groundStackReductionConfirmsAutomaticContainerPickup()
	{
		assertEquals(1, SlayerTaskLootPlugin.removedGroundQuantity(1, 0));
		assertEquals(25, SlayerTaskLootPlugin.removedGroundQuantity(100, 75));
		assertEquals(0, SlayerTaskLootPlugin.removedGroundQuantity(100, 100));
		assertEquals(0, SlayerTaskLootPlugin.removedGroundQuantity(100, -1));
	}

	@Test
	public void recognisesAlchemySpellTargets()
	{
		assertEquals(true, SlayerTaskLootPlugin.isAlchemyCast(
			"Cast", "High Level Alchemy -> Rune platebody"));
		assertEquals(true, SlayerTaskLootPlugin.isAlchemyCast(
			"cast", "Low Level Alchemy -> Yew longbow"));
		assertEquals(false, SlayerTaskLootPlugin.isAlchemyCast("Use", "High Level Alchemy"));
		assertEquals(false, SlayerTaskLootPlugin.isAlchemyCast("Cast", "Fire Blast"));
	}
}
