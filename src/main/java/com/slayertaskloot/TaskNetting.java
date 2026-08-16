package com.slayertaskloot;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Value;

/** Nets matching whole-item task drops and supplies for presentation. */
final class TaskNetting
{
	private TaskNetting()
	{
	}

	static Result apply(Map<Integer, Integer> lootQuantities, Map<Integer, Long> lootValues,
		Map<Integer, SupplyEntry> supplies)
	{
		final Map<Integer, Integer> netLoot = new LinkedHashMap<>(lootQuantities);
		final Map<Integer, Long> netLootValues = new LinkedHashMap<>(lootValues);
		final Map<Integer, SupplyEntry> netSupplies = new LinkedHashMap<>();
		supplies.forEach((itemId, supply) -> netSupplies.put(itemId,
			new SupplyEntry(supply.getQuantity(), supply.getValue(), supply.isDoseBased(),
				supply.getComponentHundredths())));

		for (Map.Entry<Integer, SupplyEntry> entry : netSupplies.entrySet())
		{
			final int itemId = entry.getKey();
			final SupplyEntry supply = entry.getValue();
			// A charged item's row counts uses of a weapon you still own, so it must never cancel
			// against a drop of that same weapon.
			if (supply.isDoseBased() || supply.isCharged() || supply.getQuantity() <= 0)
			{
				continue;
			}

			final int lootQuantity = netLoot.getOrDefault(itemId, 0);
			if (lootQuantity <= 0)
			{
				continue;
			}

			final int cancelled = Math.min(lootQuantity, supply.getQuantity());
			final int remainingLoot = lootQuantity - cancelled;
			final int remainingSupply = supply.getQuantity() - cancelled;
			final long remainingLootValue = scaleValue(
				netLootValues.getOrDefault(itemId, 0L), remainingLoot, lootQuantity);
			final long remainingSupplyValue = scaleValue(
				supply.getValue(), remainingSupply, supply.getQuantity());

			if (remainingLoot == 0)
			{
				netLoot.remove(itemId);
				netLootValues.remove(itemId);
			}
			else
			{
				netLoot.put(itemId, remainingLoot);
				netLootValues.put(itemId, remainingLootValue);
			}

			netSupplies.put(itemId,
				new SupplyEntry(remainingSupply, remainingSupplyValue, false));
		}

		netSupplies.entrySet().removeIf(entry ->
			entry.getValue().getQuantity() == 0 && entry.getValue().getValue() == 0);
		return new Result(netLoot, netLootValues, netSupplies);
	}

	private static long scaleValue(long value, int remaining, int original)
	{
		return original <= 0 || remaining <= 0 ? 0 : value * remaining / original;
	}

	@Value
	static class Result
	{
		Map<Integer, Integer> lootQuantities;
		Map<Integer, Long> lootValues;
		Map<Integer, SupplyEntry> supplies;
	}
}
