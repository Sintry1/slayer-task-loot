package com.slayertaskloot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import org.junit.Test;

public class TaskSessionTest
{
	@Test
	public void manualResumeAddsSegmentToSameSession()
	{
		final TaskLootRecord task = new TaskLootRecord("Smoke devils", null, 150, 1L);
		final TaskSession session = task.openNewSession(10L, false);
		task.tickSession(session.getSessionId());
		task.closeSession(session.getSessionId(), 20L, "IDLE_TIMEOUT");

		task.resumeSession(session.getSessionId(), 30L);
		task.tickSession(session.getSessionId());
		task.closeSession(session.getSessionId(), 40L, "MANUAL");

		assertEquals(1, task.getSessions());
		assertEquals(2, session.getSegments().size());
		assertEquals(2, task.getOnTaskTicks());
		assertFalse(session.isOpen());
	}

	/**
	 * The tick loop repairs this rather than absorbing it. A reload closing the record's
	 * sessions while the plugin still believed one was open used to stop the panel clock for
	 * good, since opening is guarded on that same belief.
	 */
	@Test
	public void tickingReportsWhenTheSessionIsNotActuallyOpen()
	{
		final TaskLootRecord task = new TaskLootRecord("Smoke devils", null, 150, 1L);
		final TaskSession session = task.openNewSession(10L, false);

		assertTrue(task.tickSession(session.getSessionId()));
		assertFalse(task.tickSession("a session this task has never held"));

		task.closeOpenSessions(20L, "CLIENT_RESTART");
		assertFalse(task.tickSession(session.getSessionId()));
		assertEquals(1, task.getOnTaskTicks());

		task.resumeSession(session.getSessionId(), 30L);
		assertTrue(task.tickSession(session.getSessionId()));
		assertEquals(2, task.getOnTaskTicks());
	}

	@Test
	public void automaticOpenCreatesDistinctSession()
	{
		final TaskLootRecord task = new TaskLootRecord("Smoke devils", null, 150, 1L);
		final TaskSession first = task.openNewSession(10L, false);
		task.closeSession(first.getSessionId(), 20L, "IDLE_TIMEOUT");
		final TaskSession second = task.openNewSession(30L, false);

		assertEquals(2, task.getSessions());
		assertNotEquals(first.getSessionId(), second.getSessionId());
		assertTrue(second.isOpen());
	}

	@Test
	public void closedSessionsCanBeMergedWithoutLosingSegments()
	{
		final TaskLootRecord task = new TaskLootRecord("Smoke devils", null, 150, 1L);
		final TaskSession first = task.openNewSession(10L, false);
		task.closeSession(first.getSessionId(), 20L, "MANUAL");
		final TaskSession second = task.openNewSession(30L, false);
		task.closeSession(second.getSessionId(), 40L, "MANUAL");

		assertTrue(task.mergeSessions(first.getSessionId(), second.getSessionId()));
		assertEquals(1, task.getSessions());
		assertEquals(2, first.getSegments().size());
	}

	@Test
	public void legacySessionTotalsRemainAsMigrationBaseline()
	{
		final TaskLootRecord task = new Gson().fromJson(
			"{\"taskName\":\"Smoke devils\",\"sessions\":2,\"onTaskTicks\":50}",
			TaskLootRecord.class);
		final TaskSession current = task.openNewSession(10L, true);
		task.tickSession(current.getSessionId());

		assertEquals(3, task.getSessions());
		assertEquals(51, task.getOnTaskTicks());
	}

	@Test
	public void mergingSessionsPoolsKillsLootAndSupplies()
	{
		final TaskLootRecord task = new TaskLootRecord("Smoke devils", null, 150, 1L);
		final TaskSession first = task.openNewSession(10L, false);
		task.addKills(first.getSessionId(), 2);
		task.addItem(first.getSessionId(), 995, 100, 100);
		task.addSupplyCharge(first.getSessionId(), new SupplyCharge(30,
			java.util.List.of(new SupplyCharge.Row(565, 3, 30, false))));
		task.closeSession(first.getSessionId(), 20L, "MANUAL");

		final TaskSession second = task.openNewSession(30L, false);
		task.addKills(second.getSessionId(), 3);
		task.addItem(second.getSessionId(), 995, 200, 200);
		task.addSupplyCharge(second.getSessionId(), new SupplyCharge(20,
			java.util.List.of(new SupplyCharge.Row(565, 2, 20, false))));
		task.closeSession(second.getSessionId(), 40L, "MANUAL");

		assertTrue(task.mergeSessions(first.getSessionId(), second.getSessionId()));
		assertEquals(5, task.getKills());
		assertEquals(Integer.valueOf(300), task.getItems().get(995));
		assertEquals(Long.valueOf(300), task.getItemValues().get(995));
		assertEquals(50, task.getSupplyCost());
		assertEquals(5, task.getSupplies().get(565).getQuantity());
	}

	/**
	 * The counter is the only exact kill signal, so a kill it banked has to survive the death
	 * match failing. This is the 311-vs-253 case: every kill moved the counter, but a despawn
	 * seen outside the two-tick window matched nothing and used to be discarded outright.
	 */
	@Test
	public void countedKillsSurviveTheDeathMatchFailing()
	{
		final TaskLootRecord task = new TaskLootRecord("Gargoyles", null, 311, 1L);
		final TaskSession session = task.openNewSession(10L, false);

		for (int i = 0; i < 311; i++)
		{
			task.addCounterKills(1);
		}
		// Only some of those could be tied to a death the client saw in time.
		task.addKills(session.getSessionId(), 253);

		assertEquals(311, task.getKills());
		assertEquals(253, task.getAttributedKills());
	}

	/** They count the same kills by different means, so the total is the larger, never the sum. */
	@Test
	public void countedAndAttributedKillsAreNotAddedTogether()
	{
		final TaskLootRecord task = new TaskLootRecord("Gargoyles", null, 100, 1L);
		final TaskSession session = task.openNewSession(10L, false);
		task.addCounterKills(40);
		task.addKills(session.getSessionId(), 40);

		assertEquals(40, task.getKills());
	}

	/**
	 * A death can be credited with no counter movement to pair with — loot arriving for a kill
	 * the counter banked earlier does exactly that — so the attributed side has to be able to lead.
	 */
	@Test
	public void attributedKillsLeadWhenTheyExceedTheCounter()
	{
		final TaskLootRecord task = new TaskLootRecord("Gargoyles", null, 100, 1L);
		final TaskSession session = task.openNewSession(10L, false);
		task.addCounterKills(2);
		task.addKills(session.getSessionId(), 5);

		assertEquals(5, task.getKills());
	}

	/** Records stored before the counter total existed carry only the attributed side. */
	@Test
	public void storedRecordWithoutACounterTotalKeepsItsKills()
	{
		final TaskLootRecord task = new Gson().fromJson(
			"{\"taskName\":\"Gargoyles\",\"kills\":47}", TaskLootRecord.class);

		assertEquals(47, task.getKills());
		assertEquals(47, task.getAttributedKills());
	}

	@Test
	public void mergingRecordsPoolsCountedKills()
	{
		final TaskLootRecord active = new TaskLootRecord("Gargoyles", null, 100, 20L);
		active.addCounterKills(30);

		final TaskLootRecord historical = new TaskLootRecord("Gargoyles", null, 90, 1L);
		historical.addCounterKills(12);
		historical.setEndedAt(10L);

		active.mergeRecord(historical);

		assertEquals(42, active.getKills());
	}

	@Test
	public void historicalRecordCanBePooledIntoActiveMatchingTask()
	{
		final TaskLootRecord active = new TaskLootRecord("Custodian stalkers", null, 104, 20L);
		final TaskSession activeSession = active.openNewSession(20L, false);
		active.addKills(activeSession.getSessionId(), 2);

		final TaskLootRecord historical = new TaskLootRecord("Custodian stalkers", null, 90, 1L);
		final TaskSession oldSession = historical.openNewSession(1L, false);
		historical.addKills(oldSession.getSessionId(), 5);
		historical.addItem(oldSession.getSessionId(), 995, 100, 100);
		historical.closeSession(oldSession.getSessionId(), 10L, "COMPLETED");
		historical.setEndedAt(10L);

		active.mergeRecord(historical);

		assertEquals(7, active.getKills());
		assertEquals(2, active.getSessions());
		assertEquals(194, active.getInitialAmount());
		assertEquals(Integer.valueOf(100), active.getItems().get(995));
		assertFalse(active.isCompleted());
	}
}
