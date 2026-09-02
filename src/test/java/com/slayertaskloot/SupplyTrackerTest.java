package com.slayertaskloot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * Covers the dose-suffix parsing that decides which items get folded into one family.
 *
 * <p>A false positive here would misprice a supply by up to 4x, so the parenthesised names
 * that <em>aren't</em> doses matter as much as the ones that are.
 */
public class SupplyTrackerTest
{
	@Test
	public void prayerRemainsAreNotSupplies()
	{
		assertTrue(SupplyTracker.hasPrayerRemainsAction(
			new String[] {"Scatter", null, null, null, "Drop"}));
		assertTrue(SupplyTracker.hasPrayerRemainsAction(
			new String[] {"Bury", null, null, null, "Drop"}));
		assertFalse(SupplyTracker.hasPrayerRemainsAction(
			new String[] {"Eat", null, null, null, "Drop"}));
		assertFalse(SupplyTracker.hasPrayerRemainsAction(null));
		assertTrue(SupplyTracker.isLootOnlyName("Malicious ashes"));
		assertTrue(SupplyTracker.isLootOnlyName("Dragon bones"));
		assertTrue(SupplyTracker.isLootOnlyName("Ensouled abyssal head"));
		assertFalse(SupplyTracker.isLootOnlyName("Ashes"));
		assertFalse(SupplyTracker.isLootOnlyName("Soda ash"));
		assertFalse(SupplyTracker.isLootOnlyName("Shark"));
		assertFalse(SupplyTracker.isLootOnlyName("Headless arrow"));
		// Untradeable loot that is spent rather than bought. Billed, the removal posts a 0 gp row,
		// netting cancels it against the drop, and both rows are deleted — which is how a polished
		// tarnished spear left no trace of itself or of the rune spear it became.
		assertTrue(SupplyTracker.isLootOnlyName("Tarnished spear"));
		assertTrue(SupplyTracker.isLootOnlyName("Tarnished necklace"));
		assertTrue(SupplyTracker.isLootOnlyName("Venator heart"));
		// What it polishes into is an ordinary item and must still bill if it is ever used.
		assertFalse(SupplyTracker.isLootOnlyName("Rune spear"));
		assertFalse(SupplyTracker.isLootOnlyName("Venator bow"));
	}

	/**
	 * The game is not consistent about the space before the dose, and the price lookup used to
	 * rebuild the name as base + "(4)" — which matched "Prayer potion(4)" and missed
	 * "Serum 207 (4)" and "Overload (4)", dropping those families out of dose netting entirely.
	 */
	@Test
	public void doseBaseNameToleratesTheSpaceBeforeTheDose()
	{
		assertEquals("Prayer potion", SupplyTracker.doseBaseName("Prayer potion(4)"));
		assertEquals("Serum 207", SupplyTracker.doseBaseName("Serum 207 (4)"));
		assertEquals("Overload", SupplyTracker.doseBaseName("Overload (4)"));
		assertEquals("Anti-venom+", SupplyTracker.doseBaseName("Anti-venom+(2)"));
		assertEquals("Antidote++", SupplyTracker.doseBaseName("Antidote++(1)"));
		assertNull(SupplyTracker.doseBaseName("Shark"));
		assertNull(SupplyTracker.doseBaseName("(4)"));
		// Charged jewellery carries a numeric suffix too, so this parses it like any other. The
		// name is not what keeps a ring out of potion normalisation — the Drink action is.
		assertEquals("Ring of dueling", SupplyTracker.doseBaseName("Ring of dueling(8)"));
	}

	@Test
	public void bonesAndAshesAreSeparableFromEnsouledHeads()
	{
		// The bones/ashes toggle moves one of these two groups and not the other: remains can be
		// spent for prayer experience, where an ensouled head is loot however the setting reads.
		assertTrue(SupplyTracker.isPrayerRemainsName("Dragon bones"));
		assertTrue(SupplyTracker.isPrayerRemainsName("Malicious ashes"));
		assertFalse(SupplyTracker.isPrayerRemainsName("Ensouled abyssal head"));

		assertTrue(SupplyTracker.isEnsouledHeadName("Ensouled abyssal head"));
		assertFalse(SupplyTracker.isEnsouledHeadName("Dragon bones"));
		assertFalse(SupplyTracker.isEnsouledHeadName("Shark"));
	}

	@Test
	public void changingDenominationIsNotConsumption()
	{
		// A million coins out, a thousand platinum tokens in. Counted in items that is 999,000
		// gone and the transformation guard can't see it, so the entire balance was billed as
		// supplies — reported after converting a task's alching proceeds at the bank. Counted in
		// gp, which is the only unit the two share, both sides are the same number.
		assertEquals(0L,
			SupplyTracker.familyUnits(ItemID.COINS, -1_000_000, 0, 1)
				+ SupplyTracker.familyUnits(ItemID.PLATINUM, 1000, 0, 1000));

		// And the other way round, which is the same exchange run backwards.
		assertEquals(0L,
			SupplyTracker.familyUnits(ItemID.PLATINUM, -1000, 0, 1000)
				+ SupplyTracker.familyUnits(ItemID.COINS, 1_000_000, 0, 1));
	}

	@Test
	public void coinsGenuinelySpentStillCountAtFaceValue()
	{
		// The fix must not make coins free: only a matched exchange cancels.
		assertEquals(-500L, SupplyTracker.familyUnits(ItemID.COINS, -500, 0, 1));
		assertTrue(SupplyTracker.isCurrency(ItemID.COINS));
		assertTrue(SupplyTracker.isCurrency(ItemID.PLATINUM));
		assertFalse(SupplyTracker.isCurrency(ItemID.SHARK));
	}

	@Test
	public void ordinaryItemsAndDosesKeepTheirOwnUnits()
	{
		assertEquals(-2L, SupplyTracker.familyUnits(ItemID.SHARK, -2, 0, 900));
		// One 4-dose container leaving is four doses, whatever it is priced at.
		assertEquals(-4L, SupplyTracker.familyUnits(ItemID._4DOSEPRAYERRESTORE, -1, 4, 10_000));
	}

	@Test
	public void parsesDoseSuffixes()
	{
		assertEquals(4, SupplyTracker.doseCount("Prayer potion(4)"));
		assertEquals(3, SupplyTracker.doseCount("Prayer potion(3)"));
		assertEquals(1, SupplyTracker.doseCount("Super restore(1)"));
		assertEquals(4, SupplyTracker.doseCount("Saradomin brew(4)"));
		assertEquals(2, SupplyTracker.doseCount("Stamina potion(2)"));
	}

	@Test
	public void ignoresParenthesesThatAreNotDoses()
	{
		// Imbued rings, tablets and clue tiers all end in brackets without being dosed.
		assertEquals(0, SupplyTracker.doseCount("Ring of the gods (i)"));
		assertEquals(0, SupplyTracker.doseCount("Teleport to house (tablet)"));
		assertEquals(0, SupplyTracker.doseCount("Clue scroll (easy)"));
		assertEquals(0, SupplyTracker.doseCount("Prayer cape(t)"));
		assertEquals(0, SupplyTracker.doseCount("Crystal helm (inactive)"));
	}

	@Test
	public void ignoresNamesWithNoSuffix()
	{
		assertEquals(0, SupplyTracker.doseCount("Shark"));
		assertEquals(0, SupplyTracker.doseCount("Vial"));
		assertEquals(0, SupplyTracker.doseCount("Adamant bolts"));
		assertEquals(0, SupplyTracker.doseCount(""));
	}

	@Test
	public void groupsSpentFormsWithTheirChargedItem()
	{
		// Billing the full item price when a charged weapon runs dry would be catastrophic
		// on a Scythe, so these have to land in the same family as their charged form.
		assertEquals("Scythe of vitur", SupplyTracker.stripChargeState("Scythe of vitur (uncharged)"));
		assertEquals("Toxic blowpipe", SupplyTracker.stripChargeState("Toxic blowpipe (empty)"));
		assertEquals("Sanguinesti staff", SupplyTracker.stripChargeState("Sanguinesti staff (uncharged)"));
		assertEquals("Serpentine helm", SupplyTracker.stripChargeState("Serpentine helm (uncharged)"));
		assertEquals("Crystal helm", SupplyTracker.stripChargeState("Crystal helm (inactive)"));
	}

	@Test
	public void groupsDegradationStepsWithTheirIntactItem()
	{
		assertEquals("Dharok's greataxe", SupplyTracker.stripChargeState("Dharok's greataxe 100"));
		assertEquals("Dharok's greataxe", SupplyTracker.stripChargeState("Dharok's greataxe 75"));
		assertEquals("Karil's leathertop", SupplyTracker.stripChargeState("Karil's leathertop 0"));
	}

	@Test
	public void leavesOrdinaryNamesAlone()
	{
		assertEquals("Shark", SupplyTracker.stripChargeState("Shark"));
		assertEquals("Adamant bolts", SupplyTracker.stripChargeState("Adamant bolts"));
		assertEquals("Ring of the gods (i)", SupplyTracker.stripChargeState("Ring of the gods (i)"));
		// A trailing number that isn't a degradation step must not be stripped.
		assertEquals("Team-18 cape", SupplyTracker.stripChargeState("Team-18 cape"));
	}

	@Test
	public void handlesMalformedSuffixes()
	{
		// An empty or unbalanced bracket must not be read as a dose count.
		assertEquals(0, SupplyTracker.doseCount("Weird item()"));
		assertEquals(0, SupplyTracker.doseCount(")"));
		assertEquals(0, SupplyTracker.doseCount("(4)"));
		assertEquals(0, SupplyTracker.doseCount("Mixed(4a)"));
	}

	@Test
	public void explicitDropAbsorbsTheLossItCauses()
	{
		final Map<Integer, SupplyTracker.PendingIgnore> ignores = new HashMap<>();
		ignores.put(100, SupplyTracker.pendingIgnore(5));

		// The drop lands a tick later than the click, so the loss has to be forgiven whenever it
		// turns up — not only in the tick the click happened.
		assertEquals(0, SupplyTracker.applyIgnoredRemoval(ignores, 100, -5));
		assertFalse(ignores.containsKey(100));
	}

	@Test
	public void onlyTheDroppedQuantityIsForgiven()
	{
		final Map<Integer, SupplyTracker.PendingIgnore> ignores = new HashMap<>();
		ignores.put(100, SupplyTracker.pendingIgnore(2));

		// Dropping two and eating three is a cost of one, not a free lunch.
		assertEquals(-1, SupplyTracker.applyIgnoredRemoval(ignores, 100, -3));
		assertFalse(ignores.containsKey(100));

		// A gain is never a drop, and an untracked item is never forgiven.
		assertEquals(4, SupplyTracker.applyIgnoredRemoval(ignores, 100, 4));
		assertEquals(-6, SupplyTracker.applyIgnoredRemoval(ignores, 999, -6));
	}

	@Test
	public void everyFormOfOneItemSharesAFamily()
	{
		// Trident of the seas (full), the partly charged form, and the uncharged form share no
		// stem in their names, so only the variation table can connect them. Recharging or
		// depleting one used to bill the full price of whichever form vanished.
		final String full = SupplyTracker.familyKey(ItemID.TOTS, "Trident of the seas (full)");
		final String charged = SupplyTracker.familyKey(ItemID.TOTS_CHARGED, "Trident of the seas");
		final String uncharged = SupplyTracker.familyKey(ItemID.TOTS_UNCHARGED, "Uncharged trident");

		assertEquals(full, charged);
		assertEquals(charged, uncharged);

		// The swamp variants are their own item, and must not merge into the seas family.
		assertNotEquals(charged,
			SupplyTracker.familyKey(ItemID.TOXIC_TOTS_CHARGED, "Trident of the swamp"));
	}

	@Test
	public void anItemThatRevertsToADifferentItemStaysInOneFamily()
	{
		// A spent abyssal tentacle hands back a kraken tentacle. They are not variations of each
		// other, so without the explicit pairing the tentacle vanishing bills its full price.
		assertEquals(
			SupplyTracker.familyKey(ItemID.ABYSSAL_TENTACLE, "Abyssal tentacle"),
			SupplyTracker.familyKey(ItemID.KRAKEN_TENTACLE, "Kraken tentacle"));

		// The whip is the part actually consumed, and is its own item.
		assertNotEquals(
			SupplyTracker.familyKey(ItemID.ABYSSAL_WHIP, "Abyssal whip"),
			SupplyTracker.familyKey(ItemID.KRAKEN_TENTACLE, "Kraken tentacle"));
	}

	@Test
	public void unrelatedItemsFallBackToTheNameHeuristic()
	{
		// An item outside the variation table keeps the old suffix behaviour.
		assertEquals(
			SupplyTracker.familyKey(-1, "Dharok's greataxe 100"),
			SupplyTracker.familyKey(-2, "Dharok's greataxe 25"));
		assertNotEquals(
			SupplyTracker.familyKey(-1, "Shark"),
			SupplyTracker.familyKey(-2, "Lobster"));
	}
}
