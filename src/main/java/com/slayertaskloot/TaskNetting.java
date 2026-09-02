package com.slayertaskloot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.IntFunction;
import lombok.Value;
import net.runelite.client.game.ItemVariationMapping;

/** Nets matching task drops and supplies for presentation. */
final class TaskNetting
{
	private TaskNetting()
	{
	}

	/**
	 * @param itemNames resolves an item id to its name, so a dose family can be recognised across
	 *                  the variants it is stored under. A potion is charged in doses under its
	 *                  4-dose container while the drop that paid for it may be any variant, and
	 *                  nothing but the name connects the two
	 */
	static Result apply(Map<Integer, Integer> lootQuantities, Map<Integer, Long> lootValues,
		Map<Integer, SupplyEntry> supplies, IntFunction<String> itemNames)
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
			if (supply.isCharged() || supply.getQuantity() <= 0)
			{
				continue;
			}

			if (supply.isDoseBased())
			{
				cancelDoses(itemId, supply, netLoot, netLootValues, netSupplies, itemNames);
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

	/**
	 * Cancels doses drunk against dropped containers of the same potion, and re-expresses whatever
	 * is left as the containers the player is actually holding.
	 *
	 * <p>Food nets whole item against whole item; a potion cannot, because the two columns are
	 * counted in different units. Doses are what was used and containers are what dropped, so
	 * without a conversion between them the dose row and the drop row never met — the potion stayed
	 * in Drops at full value <em>and</em> its doses stayed in Supplies, which is what got reported:
	 * net matching working for food and not for potions.
	 *
	 * <p>Both sides are therefore reduced to doses, cancelled there, and the remainder packed back
	 * into containers. A 3-dose drop with two doses drunk leaves a 1-dose, which is what the player
	 * has in the inventory, and the supply row goes entirely. Cancelling only whole containers was
	 * tried first and leaves that case showing a full 3-dose drop against a 2-dose charge: correct
	 * arithmetic, and the same "both rows still there" reading the bug was reported as.
	 *
	 * <p>The cost is that a row can name a variant that never dropped — two 4-dose drops with three
	 * doses drunk read as a 4-dose and a 1-dose. That is a fiction the food case never needs, and it
	 * is accepted because the alternative is a fiction too: a drop that is visibly still full when it
	 * isn't. Profit is identical either way, since netting takes the same value out of both columns.
	 */
	private static void cancelDoses(int supplyId, SupplyEntry supply,
		Map<Integer, Integer> netLoot, Map<Integer, Long> netLootValues,
		Map<Integer, SupplyEntry> netSupplies, IntFunction<String> itemNames)
	{
		final String base = SupplyTracker.doseBaseName(itemNames.apply(supplyId));
		if (base == null)
		{
			return;
		}

		// Pool every variant of the potion that dropped, in doses.
		final List<Integer> dropped = new ArrayList<>();
		long totalDoses = 0;
		long totalValue = 0;
		for (Map.Entry<Integer, Integer> entry : netLoot.entrySet())
		{
			final String name = itemNames.apply(entry.getKey());
			if (!base.equalsIgnoreCase(SupplyTracker.doseBaseName(name)))
			{
				continue;
			}
			dropped.add(entry.getKey());
			totalDoses += (long) entry.getValue() * SupplyTracker.doseCount(name);
			totalValue += netLootValues.getOrDefault(entry.getKey(), 0L);
		}

		final long cancelled = Math.min(totalDoses, supply.getQuantity());
		if (cancelled <= 0)
		{
			return;
		}

		for (Integer lootId : dropped)
		{
			netLoot.remove(lootId);
			netLootValues.remove(lootId);
		}
		repackage(base, dropped, totalDoses - cancelled, totalDoses, totalValue,
			netLoot, netLootValues, itemNames);

		final int remainingSupply = supply.getQuantity() - (int) cancelled;
		netSupplies.put(supplyId, new SupplyEntry(remainingSupply,
			scaleValue(supply.getValue(), remainingSupply, supply.getQuantity()), true));
	}

	/**
	 * Puts {@code doses} back into the largest containers that will hold them, and shares the value
	 * they are worth across the containers in proportion to what each holds.
	 *
	 * <p>Largest first, so five doses read as a 4-dose and a 1-dose rather than five 1-doses.
	 *
	 * <p>The value put back is the remainder's share of what the drops were recorded at, not the
	 * market price of the containers it is expressed in — a 3-dose is not worth exactly three
	 * quarters of a 4-dose on the Grand Exchange. Taking the share keeps netting removing precisely
	 * as much from Drops as it removes from Supplies, so repackaging can never move the profit
	 * figure. Any doses no container can express keep their value on the last row rather than
	 * quietly evaporating.
	 */
	private static void repackage(String base, List<Integer> dropped, long doses, long totalDoses,
		long totalValue, Map<Integer, Integer> netLoot, Map<Integer, Long> netLootValues,
		IntFunction<String> itemNames)
	{
		if (doses <= 0 || totalDoses <= 0)
		{
			return;
		}

		final NavigableMap<Integer, Integer> byDose = doseVariants(base, dropped, itemNames);
		final List<int[]> placements = new ArrayList<>();
		long left = doses;
		for (Integer perContainer : byDose.descendingKeySet())
		{
			if (left < perContainer)
			{
				continue;
			}
			final long count = left / perContainer;
			left -= count * perContainer;
			placements.add(new int[] {byDose.get(perContainer), (int) count, perContainer});
			if (left == 0)
			{
				break;
			}
		}
		if (placements.isEmpty())
		{
			return;
		}

		final long remainingValue = totalValue * doses / totalDoses;
		final long placedDoses = doses - left;
		long assigned = 0;
		for (int i = 0; i < placements.size(); i++)
		{
			final int[] placement = placements.get(i);
			final long held = (long) placement[1] * placement[2];
			// The last row takes whatever rounding left over, so the rows sum to the total exactly.
			final long value = i == placements.size() - 1
				? remainingValue - assigned
				: remainingValue * held / placedDoses;
			assigned += value;
			netLoot.merge(placement[0], placement[1], Integer::sum);
			netLootValues.merge(placement[0], value, Long::sum);
		}
	}

	/**
	 * Every dose variant of one potion, as doses -> item id.
	 *
	 * <p>Taken from RuneLite's variation table, which groups all four containers of a potion under
	 * one base — the only place that relationship is recorded, since a 1-dose that never dropped
	 * cannot be recovered from the drops themselves.
	 *
	 * <p>Two variants can share a dose count: the prayer potion group carries the Last Man Standing
	 * copies alongside the ordinary ones, and both are named "Prayer potion(4)". A variant that
	 * actually dropped always wins, and otherwise the lowest id does — the duplicates were added to
	 * the game long after the originals, so the lower id is the ordinary item. The cost of getting
	 * that wrong is a row showing an unfamiliar icon for the right potion, not a wrong total.
	 */
	private static NavigableMap<Integer, Integer> doseVariants(String base, List<Integer> dropped,
		IntFunction<String> itemNames)
	{
		final NavigableMap<Integer, Integer> byDose = new TreeMap<>();
		for (Integer lootId : dropped)
		{
			for (Integer variantId : ItemVariationMapping.getVariations(
				ItemVariationMapping.map(lootId)))
			{
				final int doses = matchingDoseCount(base, itemNames.apply(variantId));
				if (doses > 0)
				{
					byDose.merge(doses, variantId, Math::min);
				}
			}
		}
		for (Integer lootId : dropped)
		{
			final int doses = matchingDoseCount(base, itemNames.apply(lootId));
			if (doses > 0)
			{
				byDose.put(doses, lootId);
			}
		}
		return byDose;
	}

	/** The dose count of {@code name}, or 0 unless it is a container of the {@code base} family. */
	private static int matchingDoseCount(String base, String name)
	{
		return base.equalsIgnoreCase(SupplyTracker.doseBaseName(name)) ? SupplyTracker.doseCount(name) : 0;
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
