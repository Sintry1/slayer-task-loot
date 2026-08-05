package com.slayertaskloot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class TaskNettingTest
{
	@Test
	public void taskFoodUsedNetsOutOfLootFirst()
	{
		final TaskNetting.Result result = TaskNetting.apply(
			Map.of(385, 5), Map.of(385, 500L),
			Map.of(385, new SupplyEntry(2, 200L, false)));

		assertEquals(Integer.valueOf(3), result.getLootQuantities().get(385));
		assertEquals(Long.valueOf(300L), result.getLootValues().get(385));
		assertFalse(result.getSupplies().containsKey(385));
	}

	@Test
	public void excessUsageRemainsAsSupply()
	{
		final TaskNetting.Result result = TaskNetting.apply(
			Map.of(385, 2), Map.of(385, 200L),
			Map.of(385, new SupplyEntry(5, 500L, false)));

		assertFalse(result.getLootQuantities().containsKey(385));
		assertEquals(3, result.getSupplies().get(385).getQuantity());
		assertEquals(300L, result.getSupplies().get(385).getValue());
	}

	@Test
	public void potionDosesAreNotMixedWithWholeDroppedItems()
	{
		final Map<Integer, SupplyEntry> supplies = new LinkedHashMap<>();
		supplies.put(2434, new SupplyEntry(1, 100L, true));
		final TaskNetting.Result result = TaskNetting.apply(
			Map.of(2434, 1), Map.of(2434, 400L), supplies);

		assertEquals(Integer.valueOf(1), result.getLootQuantities().get(2434));
		assertEquals(1, result.getSupplies().get(2434).getQuantity());
	}
}
