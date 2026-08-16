package com.slayertaskloot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * Covers component quantities being held in hundredths of an item, so that a use spending a
 * fraction of one is visible in the breakdown at the moment it happens.
 *
 * <p>The bug this exists for: a scythe swing spends a hundredth of a vial of blood, which as a
 * whole-item count rounded to nothing. The vial then only appeared on every hundredth swing, so a
 * forty-swing task listed blood runes and no vial — while the vial's cost was in the row's total
 * the whole time. A supply that is tracked but invisible reads exactly like one that isn't
 * tracked, and was reported as such twice.
 */
public class SupplyComponentTest
{
	@Test
	public void aFractionOfAnItemIsListedRatherThanRoundedAway()
	{
		assertEquals("0.01", SlayerTaskLootPlugin.componentQuantity(1));
		assertEquals("0.4", SlayerTaskLootPlugin.componentQuantity(40));
		assertEquals("2.5", SlayerTaskLootPlugin.componentQuantity(250));
		assertEquals("1.05", SlayerTaskLootPlugin.componentQuantity(105));
	}

	@Test
	public void wholeQuantitiesKeepThePanelsAbbreviatedForm()
	{
		assertEquals("2", SlayerTaskLootPlugin.componentQuantity(200));
		assertEquals("1,200", SlayerTaskLootPlugin.componentQuantity(120_000));
		assertEquals("15K", SlayerTaskLootPlugin.componentQuantity(1_500_000));
	}

	@Test
	public void fractionsAccumulateAcrossUses()
	{
		final SupplyEntry entry = new SupplyEntry(1, 80, false,
			Collections.singletonMap(ItemID.VIAL_BLOOD, 1));
		for (int swing = 2; swing <= 40; swing++)
		{
			entry.add(1, 80, Collections.singletonMap(ItemID.VIAL_BLOOD, 1));
		}

		// Forty swings is four tenths of a vial, and says so, rather than waiting for the
		// hundredth swing to show a whole one.
		assertEquals(40, (int) entry.getComponentHundredths().get(ItemID.VIAL_BLOOD));
		assertEquals("0.4", SlayerTaskLootPlugin.componentQuantity(
			entry.getComponentHundredths().get(ItemID.VIAL_BLOOD)));
		assertTrue(entry.isCharged());
	}

	@Test
	public void recordsWrittenInWholeItemsMigrateOnRead()
	{
		// A stored record predating hundredths holds whole items. Reading it as hundredths
		// unchanged would shrink every historic breakdown a hundredfold.
		final SupplyEntry legacy = legacyEntry(Collections.singletonMap(ItemID.BLOODRUNE, 12));

		assertEquals(1200, (int) legacy.getComponentHundredths().get(ItemID.BLOODRUNE));
		assertEquals("12", SlayerTaskLootPlugin.componentQuantity(
			legacy.getComponentHundredths().get(ItemID.BLOODRUNE)));
		assertTrue(legacy.isCharged());
	}

	@Test
	public void aMigratedRecordKeepsItsComponentsWhenAddedTo()
	{
		final SupplyEntry legacy = legacyEntry(Collections.singletonMap(ItemID.BLOODRUNE, 12));
		legacy.add(1, 80, Collections.singletonMap(ItemID.BLOODRUNE, 200));

		// The pre-existing 12 must survive the switch to the new field rather than restarting.
		assertEquals(1400, (int) legacy.getComponentHundredths().get(ItemID.BLOODRUNE));
	}

	/** A record deserialised from before components were held in hundredths. */
	private static SupplyEntry legacyEntry(Map<Integer, Integer> wholeItems)
	{
		final SupplyEntry entry = new SupplyEntry(1, 80, false);
		try
		{
			final java.lang.reflect.Field field = SupplyEntry.class.getDeclaredField("components");
			field.setAccessible(true);
			field.set(entry, new LinkedHashMap<>(wholeItems));
		}
		catch (ReflectiveOperationException ex)
		{
			throw new AssertionError(ex);
		}
		return entry;
	}
}
