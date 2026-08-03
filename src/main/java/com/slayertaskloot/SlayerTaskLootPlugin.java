package com.slayertaskloot;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Text;

/**
 * Tracks loot and supply usage for the current slayer assignment, kept separate from the
 * Loot Tracker's lifetime totals so a task can be reviewed without resetting anything.
 *
 * <p>Deliberately standalone: slayer state is read from vanilla varps and loot from
 * {@link ServerNpcLoot}, so there is no {@code @PluginDependency} on the Slayer or Loot
 * Tracker plugins and nothing breaks when either is switched off.
 */
@Slf4j
@PluginDescriptor(
	name = "Slayer Task Loot",
	description = "Tracks loot from the current slayer task separately, with optional net profit after supplies",
	tags = {"slayer", "task", "loot", "drops", "profit", "supplies", "tracker", "gp"}
)
public class SlayerTaskLootPlugin extends Plugin
{
	private static final String KEY_ACTIVE_TASK = "activeTask";
	private static final String KEY_HISTORY = "taskHistory";
	private static final Type HISTORY_TYPE = new TypeToken<List<TaskLootRecord>>(){}.getType();

	/** Interfaces whose item movement can't be consumption, so we re-baseline instead of charging. */
	private static final int[] RESYNC_INTERFACES = {
		InterfaceID.BANKMAIN,
		InterfaceID.BANK_DEPOSITBOX,
		InterfaceID.SHOPMAIN,
		InterfaceID.TRADEMAIN,
		InterfaceID.GE_OFFERS,
		InterfaceID.GE_COLLECT,
	};

	/** Task id meaning "a boss", from [proc,helper_slayer_current_assignment]. */
	private static final int BOSS_TASK_ID = 98;

	/**
	 * Largest counter drop still counted as kills when an assignment ends. Generous enough to
	 * cover a barrage stack finishing the task, small enough that cancelling with a long way
	 * to go isn't mistaken for combat.
	 */
	private static final int MAX_FINISHING_KILLS = 12;

	/**
	 * Slack in ticks when lining a death or a drop up against a slayer counter change. They
	 * arrive in the same tick in an order the client doesn't control, so an exact match would
	 * be a coin flip.
	 */
	private static final int CREDIT_MATCH_TOLERANCE = 2;

	/** Persist at most this often, in game ticks, so a long task isn't a write per kill. */
	private static final int PERSIST_INTERVAL_TICKS = 10;

	/** Refresh the panel at most this often. Supply charges fire far too fast to rebuild on each. */
	private static final int PANEL_REFRESH_INTERVAL_TICKS = 5;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private SlayerTaskLootConfig config;

	@Inject
	private SupplyTracker supplyTracker;

	@Inject
	private Gson gson;

	private SlayerTaskLootPanel panel;
	private NavigationButton navButton;

	private TaskLootRecord activeTask;
	private List<TaskLootRecord> history = new ArrayList<>();

	/** Last observed slayer counter value. -1 until the first read of this login. */
	private int lastSlayerCount = -1;

	/**
	 * NPC names established as targets of the current assignment, learned by seeing them die
	 * on a tick the slayer counter moved. Lets a drop that arrives long after the kill still
	 * be recognised, without a hardcoded task-to-monster table.
	 */
	private final Set<String> confirmedTargets = new HashSet<>();

	/** Recently deceased NPC name -> tick, held briefly so it can be matched against a credit. */
	private final Map<String, Integer> recentDeaths = new HashMap<>();

	/** Loot awaiting attribution, held one tick so the counter change has time to land. */
	private final Deque<PendingLoot> pendingLoot = new ArrayDeque<>();

	/** Supply charges made while no session was open, replayed if one opens within the grace window. */
	private final Deque<BufferedCharge> graceBuffer = new ArrayDeque<>();

	/** Groups currently open that make item movement something other than consumption. */
	private final Set<Integer> openInterfaces = new HashSet<>();

	// Memoised slayer DB resolution, keyed on the varps it derives from. See resolveTaskName().
	private int cachedTaskId = -1;
	private int cachedBossId = -1;
	private String cachedTaskName;
	private int cachedAreaId = Integer.MIN_VALUE;
	private String cachedTaskLocation;

	/** Rendered history, rebuilt only when history changes rather than on every refresh. */
	private List<TaskView> historyViews = Collections.emptyList();
	private boolean historyViewsDirty = true;

	private boolean sessionOpen;
	private int lastCreditTick = -1;

	/** Tick the counter reached zero, or -1. Keeps a finished task open for its late loot. */
	private int taskEndTick = -1;
	private boolean supplyDirty;
	private boolean persistDirty;
	private boolean panelDirty;

	@Provides
	SlayerTaskLootConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SlayerTaskLootConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new SlayerTaskLootPanel(this, config, itemManager);
		navButton = NavigationButton.builder()
			.tooltip("Slayer Task Loot")
			.icon(buildNavIcon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		// Enabling the plugin mid-session means no LOGGED_IN event is coming, so load now.
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				load();
				supplyTracker.resync();
				updateTask();
			}
		});
	}

	@Override
	protected void shutDown()
	{
		// Synchronously, and before anything is cleared. Deferring this to the client thread
		// would run it after the fields below are nulled and persist an empty task, throwing
		// away an assignment in progress every time the plugin is toggled off. Writing RS
		// profile config off the client thread is safe — it uses a cached profile key.
		persist();

		clientToolbar.removeNavigation(navButton);
		panel = null;
		navButton = null;
		activeTask = null;
		history = new ArrayList<>();
		lastSlayerCount = -1;
		lastCreditTick = -1;
		sessionOpen = false;
		supplyDirty = false;
		persistDirty = false;
		panelDirty = false;
		taskEndTick = -1;
		confirmedTargets.clear();
		recentDeaths.clear();
		pendingLoot.clear();
		graceBuffer.clear();
		openInterfaces.clear();
		supplyTracker.clear();
	}

	// ------------------------------------------------------------------
	// Slayer task state
	// ------------------------------------------------------------------

	@SuppressWarnings("unused")
	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		final int varpId = event.getVarpId();
		if (varpId == VarPlayerID.SLAYER_COUNT
			|| varpId == VarPlayerID.SLAYER_TARGET
			|| varpId == VarPlayerID.SLAYER_AREA
			|| varpId == VarPlayerID.SLAYER_COUNT_ORIGINAL
			|| event.getVarbitId() == VarbitID.SLAYER_TARGET_BOSSID)
		{
			updateTask();
		}
	}

	/**
	 * Reconciles our view of the assignment against the game's varps. Mirrors the approach
	 * in the built-in Slayer plugin, which is the only place this resolution is documented.
	 */
	private void updateTask()
	{
		final int amount = client.getVarpValue(VarPlayerID.SLAYER_COUNT);

		if (amount <= 0)
		{
			if (activeTask != null && !activeTask.isCompleted())
			{
				// Credit the kill that finished the assignment, then stop. Archiving waits
				// for the tick loop, because that final drop is still pending attribution.
				creditTaskEnd();
				activeTask.setEndedAt(System.currentTimeMillis());
				taskEndTick = client.getTickCount();
				closeSession();
				markDirty();
			}
			lastSlayerCount = amount;
			return;
		}

		final String taskName = resolveTaskName();
		if (taskName == null)
		{
			// DB rows aren't populated yet. Leave lastSlayerCount alone so no counter change
			// is missed; a later varp change brings us back here.
			return;
		}

		final String taskLocation = resolveTaskLocation();
		final int initialAmount = client.getVarpValue(VarPlayerID.SLAYER_COUNT_ORIGINAL);

		// Identity has to be settled before any kill is credited. A new assignment whose size
		// is smaller than what was left on the previous one — a Turael skip — otherwise looks
		// like the counter dropping by dozens, and would bank that as a burst of kills against
		// the task being replaced.
		final boolean sameAssignment = activeTask != null
			&& !activeTask.isCompleted()
			&& Objects.equals(taskName, activeTask.getTaskName())
			&& Objects.equals(taskLocation, activeTask.getTaskLocation())
			&& initialAmount == activeTask.getInitialAmount()
			// A counter that rises is always a fresh assignment; it never climbs within one.
			// This catches back-to-back identical tasks of identical size, which every other
			// field above would match. Skipped on the first read after login, where there is
			// no baseline and the stored task should simply resume.
			&& !(lastSlayerCount >= 0 && amount > lastSlayerCount);

		if (!sameAssignment)
		{
			startTask(taskName, taskLocation, initialAmount);
		}
		else
		{
			creditIfKill(amount);
		}

		lastSlayerCount = amount;
	}

	/**
	 * Credits kills for a mid-task drop in the slayer counter — the one hard signal this
	 * plugin rests on, since the counter only moves for kills that counted toward the task.
	 *
	 * <p>Deliberately uncapped. Barraging a stack in the Catacombs can take the counter down
	 * by nine in a single change, and rejecting that as implausible would throw away both the
	 * kills and every drop that came with them. A drop landing on a value above zero is always
	 * kills: reassignment is caught by the identity check before this runs, and cancelling
	 * always lands on zero, never on a positive number.
	 */
	private void creditIfKill(int amount)
	{
		if (lastSlayerCount <= 0 || amount >= lastSlayerCount)
		{
			return;
		}

		creditKills(lastSlayerCount - amount);
	}

	/**
	 * Handles the counter reaching zero, which is either the finishing kill or a cancelled
	 * assignment. The counter alone can't tell them apart, so the two consequences are
	 * separated rather than gambling on one answer:
	 *
	 * <ul>
	 *   <li><b>Loot</b> is always credited for the tick. Getting this wrong on a completion
	 *   would lose the finishing drop, and it costs nothing on a cancellation — you're stood
	 *   at a slayer master, where nothing is dropping.</li>
	 *   <li><b>The kill count</b> only moves when the drop is small enough to be kills, so
	 *   cancelling with forty left doesn't book forty kills against an abandoned task.</li>
	 * </ul>
	 */
	private void creditTaskEnd()
	{
		if (lastSlayerCount <= 0)
		{
			return;
		}

		lastCreditTick = client.getTickCount();
		openSession();

		if (lastSlayerCount <= MAX_FINISHING_KILLS)
		{
			activeTask.addKills(lastSlayerCount);
		}
		else
		{
			log.debug("Counter dropped {} straight to zero — cancelled, not counted as kills",
				lastSlayerCount);
		}
	}

	/**
	 * Resolves the assignment name, memoised on the varps it derives from.
	 *
	 * <p>The cache is the point, not an optimisation: this runs from the {@code SLAYER_COUNT}
	 * handler, so without it every single kill would trigger a fresh scan of the slayer task
	 * table. Barraging a stack multiplies that by the number of NPCs dying at once, which is
	 * exactly how a plugin ends up stuttering on multi-kills.
	 */
	@Nullable
	private String resolveTaskName()
	{
		final int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
		final int bossId = client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID);

		if (taskId == cachedTaskId && bossId == cachedBossId && cachedTaskName != null)
		{
			return cachedTaskName;
		}

		final String resolved = lookUpTaskName(taskId, bossId);
		if (resolved != null)
		{
			// Only a hit is cached. A miss means the DB rows aren't populated yet, and caching
			// that would pin the failure in place instead of retrying on the next varp change.
			cachedTaskId = taskId;
			cachedBossId = bossId;
			cachedTaskName = resolved;
		}
		return resolved;
	}

	@Nullable
	private String lookUpTaskName(int taskId, int bossId)
	{
		int taskRow;
		if (taskId == BOSS_TASK_ID)
		{
			final List<Integer> bossRows = client.getDBRowsByValue(
				DBTableID.SlayerTaskSublist.ID,
				DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID,
				0,
				bossId);
			if (bossRows.isEmpty())
			{
				return null;
			}
			taskRow = (Integer) client.getDBTableField(bossRows.get(0), DBTableID.SlayerTaskSublist.COL_TASK, 0)[0];
		}
		else
		{
			final List<Integer> taskRows = client.getDBRowsByValue(
				DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, taskId);
			if (taskRows.isEmpty())
			{
				return null;
			}
			taskRow = taskRows.get(0);
		}

		final String name = (String) client.getDBTableField(taskRow, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)[0];
		return name == null || name.isEmpty()
			? null
			// Only the first character is touched. Lowercasing the rest would mangle names
			// that carry their own capitalisation, e.g. "TzHaar-Ket" or "Kree'arra".
			: name.substring(0, 1).toUpperCase() + name.substring(1);
	}

	/** Memoised for the same reason as {@link #resolveTaskName()} — it runs per kill too. */
	@Nullable
	private String resolveTaskLocation()
	{
		final int areaId = client.getVarpValue(VarPlayerID.SLAYER_AREA);

		if (areaId == cachedAreaId)
		{
			return cachedTaskLocation;
		}

		if (areaId <= 0)
		{
			// A master that assigns no area. Safe to cache: there is no lookup to retry.
			cachedAreaId = areaId;
			cachedTaskLocation = null;
			return null;
		}

		final List<Integer> areaRows = client.getDBRowsByValue(
			DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID, 0, areaId);
		if (areaRows.isEmpty())
		{
			return null;
		}

		final String resolved = (String) client.getDBTableField(
			areaRows.get(0), DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER, 0)[0];

		if (resolved != null)
		{
			cachedAreaId = areaId;
			cachedTaskLocation = resolved;
		}
		return resolved;
	}

	private void startTask(String taskName, @Nullable String taskLocation, int initialAmount)
	{
		archiveActiveTask();

		activeTask = new TaskLootRecord(taskName, taskLocation, initialAmount, System.currentTimeMillis());
		sessionOpen = false;
		lastCreditTick = -1;
		graceBuffer.clear();
		// Anything still awaiting attribution belonged to the assignment just archived, and
		// must not land on this one.
		pendingLoot.clear();
		lastCreditTick = -1;
		taskEndTick = -1;
		// Targets are per assignment: what counted for the last task says nothing about this one.
		confirmedTargets.clear();
		recentDeaths.clear();
		supplyTracker.resync();
		markDirty();

		log.debug("Started tracking task: {}x {} at {}", initialAmount, taskName, taskLocation);
		pushToPanel();
	}

	/** Moves the finished assignment into history, trimming to the configured size. */
	private void archiveActiveTask()
	{
		if (activeTask == null)
		{
			return;
		}

		if (activeTask.getEndedAt() == 0)
		{
			// Abandoned rather than completed — a new task was handed out first.
			activeTask.setEndedAt(System.currentTimeMillis());
		}

		final boolean worthKeeping = !activeTask.getItems().isEmpty()
			|| activeTask.getKills() > 0
			|| activeTask.getSupplyCost() > 0;

		if (worthKeeping)
		{
			history.add(0, activeTask);
			historyViewsDirty = true;
		}

		trimHistory();
		activeTask = null;
		taskEndTick = -1;
		closeSession();
		markDirty();
	}

	private void trimHistory()
	{
		final int max = config.historySize();
		while (history.size() > max)
		{
			history.remove(history.size() - 1);
			historyViewsDirty = true;
		}
	}

	// ------------------------------------------------------------------
	// Kill credit and sessions
	// ------------------------------------------------------------------

	private void creditKills(int count)
	{
		activeTask.addKills(count);
		lastCreditTick = client.getTickCount();
		openSession();
		markDirty();
	}

	/**
	 * Opens an on-task session, replaying any supply spend from the grace window so that
	 * teleports out and pre-fight boosts land on the task rather than being discarded.
	 */
	private void openSession()
	{
		if (sessionOpen)
		{
			return;
		}

		sessionOpen = true;
		activeTask.openedSession();

		final int cutoff = client.getTickCount() - toTicks(config.graceWindow());
		long replayed = 0;
		for (BufferedCharge charge : graceBuffer)
		{
			if (charge.tick >= cutoff)
			{
				activeTask.addSupplyCharge(charge.charge);
				replayed += charge.charge.getTotal();
			}
		}
		graceBuffer.clear();

		log.debug("Session {} open, replayed {} gp of grace-window supplies",
			activeTask.getSessions(), replayed);
	}

	private void closeSession()
	{
		sessionOpen = false;
		graceBuffer.clear();
	}

	// ------------------------------------------------------------------
	// Loot
	// ------------------------------------------------------------------

	@SuppressWarnings("unused")
	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		if (activeTask == null)
		{
			return;
		}

		final NPCComposition composition = event.getComposition();
		final String name = composition.getName() == null
			? "Unknown"
			: Text.removeTags(composition.getName());

		pendingLoot.add(new PendingLoot(client.getTickCount(), name, event.getItems()));
	}

	/**
	 * Attributes buffered loot once its tick has fully elapsed. Waiting a tick means the
	 * order in which the loot and the counter change arrive within a tick doesn't matter.
	 */
	private void resolvePendingLoot()
	{
		final int now = client.getTickCount();

		for (Iterator<PendingLoot> it = pendingLoot.iterator(); it.hasNext(); )
		{
			final PendingLoot loot = it.next();
			if (loot.tick >= now)
			{
				continue;
			}

			if (activeTask != null && isCreditedDrop(loot))
			{
				for (ItemStack item : loot.items)
				{
					activeTask.addItem(itemManager.canonicalize(item.getId()), item.getQuantity());
				}
				markDirty();
				log.debug("Credited {} drop to task", loot.npcName);
			}

			it.remove();
		}
	}

	/**
	 * Decides whether a drop belongs to the task.
	 *
	 * <p>Two cases, because not every monster drops its loot as it dies:
	 *
	 * <ul>
	 *   <li><b>Dropped on death</b> — the drop lands within a tick or two of the counter
	 *   moving. Credited outright; nothing else needs to be known about it.</li>
	 *   <li><b>Collected afterwards</b> — Araxxor and friends leave loot to be picked up, so
	 *   the drop can arrive long after the counter moved. Credited only if it came from a
	 *   monster already confirmed as a target of this assignment.</li>
	 * </ul>
	 *
	 * <p>That name check is what keeps the late window honest. Credit is recorded per tick,
	 * not per NPC — the counter says a task kill happened, never which monster it was — so a
	 * window that trusted timing alone would sweep up any drop that happened to land inside
	 * it, including from something that had nothing to do with the task.
	 */
	private boolean isCreditedDrop(PendingLoot loot)
	{
		if (lastCreditTick < 0)
		{
			return false;
		}

		final int sinceCredit = loot.tick - lastCreditTick;

		// A drop marginally ahead of the counter change is still that kill's drop.
		if (sinceCredit < -CREDIT_MATCH_TOLERANCE)
		{
			return false;
		}

		if (sinceCredit <= CREDIT_MATCH_TOLERANCE)
		{
			return true;
		}

		return sinceCredit <= toTicks(config.lootCreditWindow())
			&& confirmedTargets.contains(loot.npcName);
	}

	/**
	 * Notes an NPC death so it can be matched against a counter change.
	 *
	 * <p>Reconciliation is deferred to the tick loop rather than done here, because the death
	 * and the counter change land in the same tick in an order we don't control.
	 */
	@SuppressWarnings("unused")
	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		if (activeTask == null)
		{
			return;
		}

		final NPC npc = event.getNpc();
		if (npc.isDead())
		{
			noteNpcDeath(npc);
		}
	}

	private void noteNpcDeath(NPC npc)
	{
		if (npc.getName() != null)
		{
			recentDeaths.put(Text.removeTags(npc.getName()), client.getTickCount());
		}
	}

	/** Promotes anything that died alongside a counter change to a confirmed task target. */
	private void reconcileTargets(int now)
	{
		for (Iterator<Map.Entry<String, Integer>> it = recentDeaths.entrySet().iterator(); it.hasNext(); )
		{
			final Map.Entry<String, Integer> death = it.next();
			final int deathTick = death.getValue();

			if (lastCreditTick >= 0 && Math.abs(deathTick - lastCreditTick) <= CREDIT_MATCH_TOLERANCE)
			{
				if (confirmedTargets.add(death.getKey()))
				{
					log.debug("Confirmed {} as a target of this task", death.getKey());
				}
				it.remove();
			}
			else if (deathTick < now - CREDIT_MATCH_TOLERANCE)
			{
				// Died without the counter moving — not a task target, at least not this kill.
				it.remove();
			}
		}
	}

	// ------------------------------------------------------------------
	// Supplies
	// ------------------------------------------------------------------

	@SuppressWarnings("unused")
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		final int id = event.getContainerId();
		if (id == InventoryID.INV || id == InventoryID.WORN)
		{
			// Inventory and worn equipment are diffed together, and both fire their own
			// event, so defer to the end of the tick and evaluate them as one change.
			supplyDirty = true;
		}
	}

	@SuppressWarnings("unused")
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if ("Drop".equals(event.getMenuOption()))
		{
			// Thrown away rather than used up.
			supplyTracker.resync();
			supplyDirty = false;
		}
	}

	@SuppressWarnings("unused")
	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() == client.getLocalPlayer())
		{
			// Losing an inventory is not a supply cost.
			supplyTracker.resync();
			supplyDirty = false;
			return;
		}

		// Second source for target confirmation alongside onNpcDespawned. Bosses with scripted
		// death sequences don't always despawn in a state that reads as dead, and missing the
		// death would mean their collected loot never gets recognised.
		if (activeTask != null && event.getActor() instanceof NPC)
		{
			noteNpcDeath((NPC) event.getActor());
		}
	}

	private void processSupplies()
	{
		if (!supplyDirty)
		{
			return;
		}
		supplyDirty = false;

		if (!config.trackSupplies())
		{
			// Feature off: don't diff anything. The snapshot is deliberately left stale and
			// gets rebaselined when the setting is turned back on, so nothing consumed while
			// off is billed retroactively.
			return;
		}

		if (isResyncInterfaceOpen())
		{
			// Banking, trading or shopping — re-baseline so restocking isn't charged.
			supplyTracker.resync();
			return;
		}

		final SupplyCharge spent = supplyTracker.charge();
		if (spent.isEmpty() || activeTask == null || activeTask.isCompleted())
		{
			return;
		}

		if (sessionOpen)
		{
			activeTask.addSupplyCharge(spent);
			markDirty();
		}
		else if (config.graceWindow() > 0)
		{
			// Might belong to a session that hasn't opened yet — hold it and decide later.
			graceBuffer.add(new BufferedCharge(client.getTickCount(), spent));
		}
	}

	@SuppressWarnings("unused")
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (isResyncGroup(event.getGroupId()))
		{
			openInterfaces.add(event.getGroupId());
		}
	}

	@SuppressWarnings("unused")
	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		openInterfaces.remove(event.getGroupId());
	}

	private static boolean isResyncGroup(int groupId)
	{
		for (int candidate : RESYNC_INTERFACES)
		{
			if (candidate == groupId)
			{
				return true;
			}
		}
		return false;
	}

	private boolean isResyncInterfaceOpen()
	{
		if (!openInterfaces.isEmpty())
		{
			return true;
		}

		// Fallback for the plugin being enabled while one of these is already open, in which
		// case its WidgetLoaded came and went before we were listening.
		for (int groupId : RESYNC_INTERFACES)
		{
			if (client.getWidget(groupId, 0) != null)
			{
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------
	// Tick loop
	// ------------------------------------------------------------------

	@SuppressWarnings("unused")
	@Subscribe
	public void onGameTick(GameTick event)
	{
		final int now = client.getTickCount();

		// Kill credit for this tick is already registered by onVarbitChanged, which fires
		// during packet processing, so the session state below is up to date.
		reconcileTargets(now);
		resolvePendingLoot();
		processSupplies();

		if (sessionOpen && activeTask != null)
		{
			activeTask.tickOnTask();

			if (lastCreditTick >= 0 && now - lastCreditTick > toTicks(config.sessionTimeout() * 60))
			{
				log.debug("Session idle for {} minutes, closing", config.sessionTimeout());
				closeSession();
				markDirty();
			}
		}

		pruneGraceBuffer(now);

		// A finished assignment is held open until its late-loot window has run out, not just
		// until the tick after the last kill. Archiving the moment the counter hits zero would
		// mean a boss whose loot has to be collected — Araxxor, say — loses its final drop,
		// because there'd be no active task left for it to land on by the time it arrives.
		if (activeTask != null && activeTask.isCompleted() && pendingLoot.isEmpty()
			&& taskEndTick >= 0 && now - taskEndTick > toTicks(config.lootCreditWindow()))
		{
			archiveActiveTask();
			pushToPanel();
		}

		// Both are throttled rather than run on every change: firing ammunition charges
		// supplies every few ticks, and neither a config write nor a full panel rebuild is
		// worth doing at that rate.
		if (panelDirty && now % PANEL_REFRESH_INTERVAL_TICKS == 0)
		{
			panelDirty = false;
			pushToPanel();
		}

		if (persistDirty && now % PERSIST_INTERVAL_TICKS == 0)
		{
			persist();
		}
	}

	/** Flags that the stored snapshot and the panel are both behind the live state. */
	private void markDirty()
	{
		persistDirty = true;
		panelDirty = true;
	}

	private void pruneGraceBuffer(int now)
	{
		final int cutoff = now - toTicks(config.graceWindow());
		while (!graceBuffer.isEmpty() && graceBuffer.peekFirst().tick < cutoff)
		{
			graceBuffer.removeFirst();
		}
	}

	private static int toTicks(int seconds)
	{
		// One game tick is 600ms.
		return (seconds * 1000) / 600;
	}

	// ------------------------------------------------------------------
	// Lifecycle and persistence
	// ------------------------------------------------------------------

	@SuppressWarnings("unused")
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		final GameState state = event.getGameState();

		if (state == GameState.LOGGED_IN)
		{
			load();

			// A task that finished before logging out has no loot still to arrive, and its
			// late-loot window can't span a session. Close it rather than leaving a finished
			// assignment presented as the current one.
			if (activeTask != null && activeTask.isCompleted())
			{
				archiveActiveTask();
			}

			// Carried items aren't readable until now, and anything that changed while
			// logged out must not be charged.
			supplyTracker.resync();
			updateTask();
		}
		else if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			persist();
			// Tick counts don't carry across a session, so anchors measured in ticks are
			// meaningless now. A new kill re-establishes them.
			lastCreditTick = -1;
			taskEndTick = -1;
			// The counter is re-read on login; treat the next read as a fresh baseline so
			// logging in never looks like a burst of credited kills.
			lastSlayerCount = -1;
			closeSession();
			supplyTracker.clear();
			pendingLoot.clear();
			recentDeaths.clear();
			openInterfaces.clear();
		}
	}

	@SuppressWarnings("unused")
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!SlayerTaskLootConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		// Config changes arrive from the Swing thread, and rebuilding the panel resolves item
		// names and prices through ItemManager, so hop across before touching game state.
		final boolean trim = "historySize".equals(event.getKey());
		final boolean suppliesToggled = "trackSupplies".equals(event.getKey());
		clientThread.invokeLater(() ->
		{
			if (trim)
			{
				trimHistory();
				persist();
			}
			if (suppliesToggled)
			{
				// Rebaseline on the way back in. Without this, everything used while tracking
				// was off would land on the task the moment it's switched on.
				supplyTracker.resync();
				supplyDirty = false;
			}
			pushToPanel();
		});
	}

	private void persist()
	{
		try
		{
			configManager.setRSProfileConfiguration(SlayerTaskLootConfig.GROUP, KEY_ACTIVE_TASK,
				activeTask == null ? "" : gson.toJson(activeTask));
			configManager.setRSProfileConfiguration(SlayerTaskLootConfig.GROUP, KEY_HISTORY,
				gson.toJson(history, HISTORY_TYPE));
			persistDirty = false;
		}
		catch (Exception ex)
		{
			log.warn("Failed to persist task loot", ex);
		}
	}

	private void load()
	{
		final String activeJson = configManager.getRSProfileConfiguration(
			SlayerTaskLootConfig.GROUP, KEY_ACTIVE_TASK);
		final String historyJson = configManager.getRSProfileConfiguration(
			SlayerTaskLootConfig.GROUP, KEY_HISTORY);

		try
		{
			activeTask = activeJson == null || activeJson.isEmpty()
				? null
				: gson.fromJson(activeJson, TaskLootRecord.class);
		}
		catch (JsonSyntaxException ex)
		{
			log.warn("Discarding malformed active task", ex);
			activeTask = null;
		}

		try
		{
			final List<TaskLootRecord> stored = historyJson == null || historyJson.isEmpty()
				? null
				: gson.fromJson(historyJson, HISTORY_TYPE);
			history = stored == null ? new ArrayList<>() : stored;
		}
		catch (JsonSyntaxException ex)
		{
			log.warn("Discarding malformed task history", ex);
			history = new ArrayList<>();
		}

		historyViewsDirty = true;
		trimHistory();
		pushToPanel();
	}

	// ------------------------------------------------------------------
	// Panel actions
	// ------------------------------------------------------------------

	void resetActiveTask()
	{
		clientThread.invokeLater(() ->
		{
			if (activeTask == null)
			{
				return;
			}
			activeTask = new TaskLootRecord(
				activeTask.getTaskName(),
				activeTask.getTaskLocation(),
				activeTask.getInitialAmount(),
				System.currentTimeMillis());
			closeSession();
			lastCreditTick = -1;
			supplyTracker.resync();
			persist();
			pushToPanel();
		});
	}

	void clearHistory()
	{
		clientThread.invokeLater(() ->
		{
			history = new ArrayList<>();
			historyViewsDirty = true;
			persist();
			pushToPanel();
		});
	}

	// ------------------------------------------------------------------
	// Panel plumbing
	// ------------------------------------------------------------------

	/**
	 * Resolves names and prices here on the client thread, then hands the panel immutable
	 * views so it never reads game state off the Swing thread.
	 */
	private void pushToPanel()
	{
		if (panel == null)
		{
			return;
		}

		if (client.getGameState() != GameState.LOGGED_IN && activeTask == null && history.isEmpty())
		{
			return;
		}

		final TaskView current = activeTask == null ? null : toView(activeTask);

		// Only the active task changes between refreshes. Re-resolving every stored task's
		// item names and prices on each pass would mean thousands of lookups a few times a
		// second once the history is full, for output that is byte-for-byte identical.
		if (historyViewsDirty)
		{
			final List<TaskView> rebuilt = new ArrayList<>(history.size());
			for (TaskLootRecord record : history)
			{
				rebuilt.add(toView(record));
			}
			historyViews = Collections.unmodifiableList(rebuilt);
			historyViewsDirty = false;
		}

		final List<TaskView> past = historyViews;

		SwingUtilities.invokeLater(() ->
		{
			if (panel != null)
			{
				panel.display(current, past);
			}
		});
	}

	private TaskView toView(TaskLootRecord record)
	{
		final List<TaskView.LootRow> rows = new ArrayList<>();
		long lootValue = 0;

		for (Map.Entry<Integer, Integer> entry : record.getItems().entrySet())
		{
			final int itemId = entry.getKey();
			final int quantity = entry.getValue();
			final long value = (long) itemManager.getItemPrice(itemId) * quantity;
			lootValue += value;

			rows.add(new TaskView.LootRow(itemId, itemName(itemId), quantity, value, ""));
		}

		rows.sort(Comparator.comparingLong(TaskView.LootRow::getValue).reversed());

		final List<TaskView.LootRow> supplyRows = new ArrayList<>();
		for (Map.Entry<Integer, SupplyEntry> entry : record.getSupplies().entrySet())
		{
			final int itemId = entry.getKey();
			final SupplyEntry supply = entry.getValue();

			// Dose families are stored under their largest variant, so the name still carries
			// the "(4)" suffix. Drop it — the row already says the count is in doses.
			String name = itemName(itemId);
			if (supply.isDoseBased())
			{
				final int open = name.lastIndexOf('(');
				if (open > 0)
				{
					name = name.substring(0, open).trim();
				}
			}

			supplyRows.add(new TaskView.LootRow(
				itemId, name, supply.getQuantity(), supply.getValue(),
				supply.isDoseBased() ? "doses" : ""));
		}

		supplyRows.sort(Comparator.comparingLong(TaskView.LootRow::getValue).reversed());

		return new TaskView(
			record.getTaskName(),
			record.getTaskLocation(),
			record.getKills(),
			record.getInitialAmount(),
			record.getStartedAt(),
			record.getEndedAt(),
			record.isCompleted(),
			lootValue,
			record.getSupplyCost(),
			record.getSessions(),
			record.getOnTaskTicks(),
			Collections.unmodifiableList(rows),
			Collections.unmodifiableList(supplyRows));
	}

	private String itemName(int itemId)
	{
		try
		{
			final ItemComposition comp = itemManager.getItemComposition(itemId);
			if (comp != null && comp.getName() != null && !comp.getName().isEmpty())
			{
				return comp.getName();
			}
		}
		catch (Exception ex)
		{
			// Fall back to the id; a missing composition shouldn't blank the panel.
		}
		return "Item #" + itemId;
	}

	private static BufferedImage buildNavIcon()
	{
		final int size = 17;
		final BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		g.setColor(new Color(120, 30, 30));
		g.fillOval(1, 1, size - 2, size - 2);

		g.setColor(new Color(70, 15, 15));
		g.drawOval(1, 1, size - 2, size - 2);

		g.setColor(Color.WHITE);
		g.setFont(new Font("SansSerif", Font.BOLD, 10));
		g.drawString("S", 6, 12);

		g.dispose();
		return img;
	}

	// ------------------------------------------------------------------
	// Buffered state
	// ------------------------------------------------------------------

	/** Loot held for one tick while we wait to see whether the kill was credited. */
	private static final class PendingLoot
	{
		private final int tick;
		private final String npcName;
		private final Collection<ItemStack> items;

		private PendingLoot(int tick, String npcName, Collection<ItemStack> items)
		{
			this.tick = tick;
			this.npcName = npcName;
			this.items = items;
		}
	}

	/** Supply spend from before a session opened, replayable within the grace window. */
	private static final class BufferedCharge
	{
		private final int tick;
		private final SupplyCharge charge;

		private BufferedCharge(int tick, SupplyCharge charge)
		{
			this.tick = tick;
			this.charge = charge;
		}
	}
}
