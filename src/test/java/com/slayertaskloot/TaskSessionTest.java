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
