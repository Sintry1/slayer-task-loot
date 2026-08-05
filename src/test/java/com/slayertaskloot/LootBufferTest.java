package com.slayertaskloot;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
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
}
