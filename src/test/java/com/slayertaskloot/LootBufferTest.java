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
