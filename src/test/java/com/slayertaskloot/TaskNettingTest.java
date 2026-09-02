package com.slayertaskloot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntFunction;
import org.junit.Test;

public class TaskNettingTest
{
	private static final int SHARK = 385;
	private static final int PRAYER_POTION_4 = 2434;
	private static final int PRAYER_POTION_3 = 139;
	private static final int PRAYER_POTION_2 = 141;
	private static final int PRAYER_POTION_1 = 143;
	private static final int SUPER_RESTORE_4 = 3024;

	private static final IntFunction<String> NAMES;

	static
	{
		final Map<Integer, String> names = new HashMap<>();
		names.put(SHARK, "Shark");
		names.put(PRAYER_POTION_4, "Prayer potion(4)");
		names.put(PRAYER_POTION_3, "Prayer potion(3)");
		names.put(PRAYER_POTION_2, "Prayer potion(2)");
		names.put(PRAYER_POTION_1, "Prayer potion(1)");
		names.put(SUPER_RESTORE_4, "Super restore(4)");
		NAMES = id -> names.getOrDefault(id, "Item #" + id);
	}

	@Test
	public void taskFoodUsedNetsOutOfLootFirst()
	{
		final TaskNetting.Result result = TaskNetting.apply(
			Map.of(SHARK, 5), Map.of(SHARK, 500L),
			Map.of(SHARK, new SupplyEntry(2, 200L, false)), NAMES);

		assertEquals(Integer.valueOf(3), result.getLootQuantities().get(SHARK));
		assertEquals(Long.valueOf(300L), result.getLootValues().get(SHARK));
		assertFalse(result.getSupplies().containsKey(SHARK));
	}

	@Test
	public void excessUsageRemainsAsSupply()
	{
		final TaskNetting.Result result = TaskNetting.apply(
			Map.of(SHARK, 2), Map.of(SHARK, 200L),
			Map.of(SHARK, new SupplyEntry(5, 500L, false)), NAMES);

		assertFalse(result.getLootQuantities().containsKey(SHARK));
		assertEquals(3, result.getSupplies().get(SHARK).getQuantity());
		assertEquals(300L, result.getSupplies().get(SHARK).getValue());
	}

	@Test
	public void aFullyDrunkDroppedPotionCancelsBothWays()
	{
		// The reported bug: food netted and potions didn't, so a dropped potion drunk dry stayed
		// in Drops at full value while its doses stayed in Supplies.
		final Map<Integer, SupplyEntry> supplies = new LinkedHashMap<>();
		supplies.put(PRAYER_POTION_4, new SupplyEntry(4, 400L, true));
		final TaskNetting.Result result = TaskNetting.apply(
			Map.of(PRAYER_POTION_4, 1), Map.of(PRAYER_POTION_4, 400L), supplies, NAMES);

		assertFalse(result.getLootQuantities().containsKey(PRAYER_POTION_4));
		assertFalse(result.getSupplies().containsKey(PRAYER_POTION_4));
	}

	@Test
	public void dosesCancelAcrossTheVariantThatDropped()
	{
		// Charged under the 4-dose container, dropped as a 3-dose. Only the name connects them.
		final Map<Integer, SupplyEntry> supplies = new LinkedHashMap<>();
		supplies.put(PRAYER_POTION_4, new SupplyEntry(3, 300L, true));
		final TaskNetting.Result result = TaskNetting.apply(
			Map.of(PRAYER_POTION_3, 1), Map.of(PRAYER_POTION_3, 300L), supplies, NAMES);

		assertFalse(result.getLootQuantities().containsKey(PRAYER_POTION_3));
		assertFalse(result.getSupplies().containsKey(PRAYER_POTION_4));
	}

	@Test
	public void aPartlyDrunkDropBecomesTheContainerStillHeld()
	{
		// A 3-dose drops and two doses are drunk. What the player is holding is a 1-dose, and that
		// is what Drops now says. Cancelling only whole containers left this showing a full 3-dose
		// against a 2-dose charge — right arithmetic, and the same "both rows still there" reading
		// the original bug was reported as.
		final Map<Integer, SupplyEntry> supplies = new LinkedHashMap<>();
		supplies.put(PRAYER_POTION_4, new SupplyEntry(2, 200L, true));
		final TaskNetting.Result result = TaskNetting.apply(
			Map.of(PRAYER_POTION_3, 1), Map.of(PRAYER_POTION_3, 300L), supplies, NAMES);

		assertFalse(result.getLootQuantities().containsKey(PRAYER_POTION_3));
		assertEquals(Integer.valueOf(1), result.getLootQuantities().get(PRAYER_POTION_1));
		assertEquals(Long.valueOf(100L), result.getLootValues().get(PRAYER_POTION_1));
		assertFalse(result.getSupplies().containsKey(PRAYER_POTION_4));
	}

	@Test
	public void aRemainderIsPackedIntoTheLargestContainersThatHoldIt()
	{
		// Eight doses dropped, three drunk. Five left reads as a 4-dose and a 1-dose rather than
		// five 1-doses — and neither denomination is one that dropped, which is the accepted cost.
		final Map<Integer, SupplyEntry> supplies = new LinkedHashMap<>();
		supplies.put(PRAYER_POTION_4, new SupplyEntry(3, 300L, true));
		final TaskNetting.Result result = TaskNetting.apply(
			Map.of(PRAYER_POTION_4, 2), Map.of(PRAYER_POTION_4, 800L), supplies, NAMES);

		assertEquals(Integer.valueOf(1), result.getLootQuantities().get(PRAYER_POTION_4));
		assertEquals(Integer.valueOf(1), result.getLootQuantities().get(PRAYER_POTION_1));
		assertFalse(result.getSupplies().containsKey(PRAYER_POTION_4));

		// Netting must take exactly as much out of Drops as it takes out of Supplies, so the
		// repackaging can never move the profit figure: 800 - 300 both before and after.
		assertEquals(500L, result.getLootValues().values().stream().mapToLong(Long::longValue).sum());
	}

	@Test
	public void dosesPoolAcrossEveryContainerThatDropped()
	{
		// Seven doses against a (4) and a (2): six cancel and the seventh stays charged, because
		// the drops only ever held six.
		final Map<Integer, Integer> loot = new LinkedHashMap<>();
		loot.put(PRAYER_POTION_4, 1);
		loot.put(PRAYER_POTION_2, 1);
		final Map<Integer, Long> values = new LinkedHashMap<>();
		values.put(PRAYER_POTION_4, 400L);
		values.put(PRAYER_POTION_2, 200L);
		final Map<Integer, SupplyEntry> supplies = new LinkedHashMap<>();
		supplies.put(PRAYER_POTION_4, new SupplyEntry(7, 700L, true));

		final TaskNetting.Result result = TaskNetting.apply(loot, values, supplies, NAMES);

		assertTrue(result.getLootQuantities().isEmpty());
		assertEquals(1, result.getSupplies().get(PRAYER_POTION_4).getQuantity());
		assertEquals(100L, result.getSupplies().get(PRAYER_POTION_4).getValue());
	}

	@Test
	public void dosesNeverCancelADifferentPotion()
	{
		final Map<Integer, SupplyEntry> supplies = new LinkedHashMap<>();
		supplies.put(SUPER_RESTORE_4, new SupplyEntry(4, 400L, true));
		final TaskNetting.Result result = TaskNetting.apply(
			Map.of(PRAYER_POTION_4, 1), Map.of(PRAYER_POTION_4, 400L), supplies, NAMES);

		assertEquals(Integer.valueOf(1), result.getLootQuantities().get(PRAYER_POTION_4));
		assertEquals(4, result.getSupplies().get(SUPER_RESTORE_4).getQuantity());
	}

	@Test
	public void aChargedItemNeverCancelsAgainstADropOfItself()
	{
		final Map<Integer, SupplyEntry> supplies = new LinkedHashMap<>();
		supplies.put(SHARK, new SupplyEntry(3, 300L, false, Map.of(SHARK, 100)));
		final TaskNetting.Result result = TaskNetting.apply(
			Map.of(SHARK, 5), Map.of(SHARK, 500L), supplies, NAMES);

		assertEquals(Integer.valueOf(5), result.getLootQuantities().get(SHARK));
		assertEquals(3, result.getSupplies().get(SHARK).getQuantity());
	}
}
