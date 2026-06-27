package io.github.magmaenginers.magmalib;

import io.papermc.paper.threadedregions.scheduler.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.*;
import java.lang.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.logging.Level;

/**
 * MagmaLib — Modern, type-safe scheduling API for Paper 1.20+ and Folia.
 *
 * <ul>
 *   <li>Type-safe functional composition: {@code thenApply}, {@code thenAccept}, {@code exceptionally}</li>
 *   <li>Global task tracking + {@link #cancelAllTasks()} for clean {@code onDisable()}</li>
 *   <li>Named {@link TaskGroup}s for grouped cancellation (e.g. per-player, per-region)</li>
 *   <li>Three-platform detection: {@link #isFolia()}, {@link #isPaper()}, {@link #isSpigot()}</li>
 *   <li>Async teleport with platform-aware fallbacks: {@link #teleportAsync(Entity, Location)}</li>
 *   <li>Self-cancelling timers that actually cancel: {@link #runTimerUntil}, {@link #runTimerWhile}</li>
 *   <li>Zero-allocation Direct API for hot paths</li>
 *   <li>Zero external dependencies — single file, copy and go</li>
 *   <li>{@code @MainThread} / {@code @AnyThread} annotations for IDE thread-safety hints</li>
 * </ul>
 *
 * <h2>Quick example</h2>
 * <pre>{@code
 * // onEnable
 * MagmaLib.init(this);
 *
 * // Type-safe pipeline
 * MagmaLib.<Integer>task(() -> computeScore(player))
 *     .with(player)
 *     .afterTicks(5)
 *     .thenApply(score -> score > 100 ? "S rank!" : "Try harder")
 *     .thenAccept(player::sendMessage)
 *     .exceptionally(e -> { getLogger().warning(e.getMessage()); return "Error"; })
 *     .run();
 *
 * // onDisable
 * MagmaLib.cancelAllTasks();
 * }</pre>
 *
 * @author Piratemajo
 * @version 3.0.0
 * @see <a href="https://github.com/MagmaEnginers/MagmaLib">GitHub Repository</a>
 */
@SuppressWarnings("unused")
public final class MagmaLib {

    // ==================== THREAD-SAFETY ANNOTATIONS ====================

    /**
     * Marks a method that MUST be called from the main/region thread.
     * Calling from an async thread will trigger AsyncCatcher errors.
     */
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.METHOD)
    public @interface MainThread {}

    /**
     * Marks a method that is safe to call from any thread.
     */
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.METHOD)
    public @interface AnyThread {}

    // ==================== STATE ====================

    private static volatile Plugin plugin;
    private static volatile Boolean isFoliaCache  = null;
    private static volatile Boolean isPaperCache  = null;
    private static volatile Boolean isSpigotCache = null;

    /** Global registry of all live tasks for {@link #cancelAllTasks()}. */
    private static final Set<Task> globalTaskRegistry =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private MagmaLib() {}

    // ==================== INITIALIZATION ====================

    /**
     * Initialises MagmaLib. Call once in {@link JavaPlugin#onEnable()}.
     *
     * @param plugin Non-null plugin instance.
     * @throws IllegalArgumentException if {@code plugin} is null.
     */
    @AnyThread
    public static void init(Plugin plugin) {
        if (plugin == null) throw new IllegalArgumentException(
                "MagmaLib.init() requires a non-null plugin instance");
        MagmaLib.plugin = plugin;
        detectPlatform();
    }

    private static void detectPlatform() {
        // Detect Folia
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            isFoliaCache  = true;
            isPaperCache  = false;
            isSpigotCache = false;
            return;
        } catch (ClassNotFoundException ignored) {}

        isFoliaCache = false;

        // Detect Paper (has io.papermc.paper namespace beyond Folia)
        try {
            Class.forName("io.papermc.paper.configuration.GlobalConfiguration");
            isPaperCache  = true;
            isSpigotCache = false;
            return;
        } catch (ClassNotFoundException ignored) {}

        isPaperCache  = false;
        isSpigotCache = true;
    }

    // ==================== PLATFORM DETECTION ====================

    /**
     * Returns {@code true} when running on Folia.
     * @throws IllegalStateException if {@link #init(Plugin)} has not been called.
     */
    @AnyThread
    public static boolean isFolia() {
        Boolean c = isFoliaCache;
        if (c == null) {
            requireInit();
            detectPlatform();
            c = isFoliaCache;
        }
        return c;
    }

    /**
     * Returns {@code true} when running on Paper (non-Folia).
     */
    @AnyThread
    public static boolean isPaper() {
        Boolean c = isPaperCache;
        if (c == null) { requireInit(); detectPlatform(); c = isPaperCache; }
        return c;
    }

    /**
     * Returns {@code true} when running on Spigot (no Paper extensions detected).
     */
    @AnyThread
    public static boolean isSpigot() {
        Boolean c = isSpigotCache;
        if (c == null) { requireInit(); detectPlatform(); c = isSpigotCache; }
        return c;
    }

    /** @return The registered plugin, or {@code null} if uninitialised. */
    @AnyThread
    public static Plugin getPlugin() { return plugin; }

    private static void requireInit() {
        if (plugin == null) throw new IllegalStateException(
                "MagmaLib.init(plugin) must be called in onEnable() before using MagmaLib");
    }

    // ==================== GLOBAL TASK TRACKING ====================

    /**
     * Cancels every task ever scheduled through MagmaLib and clears the registry.
     * <p>Call this in {@link JavaPlugin#onDisable()} to ensure clean shutdown.</p>
     *
     * <pre>{@code
     * @Override
     * public void onDisable() {
     *     MagmaLib.cancelAllTasks();
     * }
     * }</pre>
     */
    @AnyThread
    public static void cancelAllTasks() {
        int count = 0;
        for (Task t : globalTaskRegistry) {
            if (t.isRunning()) { t.cancel(); count++; }
        }
        globalTaskRegistry.clear();
        if (plugin != null) {
            plugin.getLogger().info("[MagmaLib] Cancelled " + count + " task(s) on shutdown.");
        }
    }

    /**
     * Returns an unmodifiable snapshot of all currently tracked tasks.
     * Useful for diagnostics or custom shutdown logic.
     */
    @AnyThread
    public static Set<Task> getTrackedTasks() {
        return Collections.unmodifiableSet(new HashSet<>(globalTaskRegistry));
    }

    /** Removes completed/cancelled tasks from the global registry to prevent memory leaks. */
    @AnyThread
    public static void pruneRegistry() {
        globalTaskRegistry.removeIf(t -> !t.isRunning());
    }

    private static Task track(Task task) {
        globalTaskRegistry.add(task);
        return task;
    }

    // ==================== TASK GROUP ====================

    /**
     * A named group of tasks that can be cancelled together.
     * <p>Useful for per-player, per-region, or per-feature task management.</p>
     *
     * <pre>{@code
     * TaskGroup group = MagmaLib.newTaskGroup("player-" + player.getUniqueId());
     *
     * group.add(MagmaLib.task(() -> updateAura(player))
     *     .with(player).everyTicks(5).run());
     *
     * group.add(MagmaLib.task(() -> checkCooldown(player))
     *     .everyTicks(20).run());
     *
     * // On player quit:
     * group.cancelAll();
     * }</pre>
     */
    public static final class TaskGroup {

        private final String name;
        private final Set<Task> tasks = Collections.newSetFromMap(new ConcurrentHashMap<>());

        private TaskGroup(String name) { this.name = name; }

        /** Adds a task to this group and returns it for chaining. */
        public Task add(Task task) {
            tasks.add(task);
            return task;
        }

        /** Cancels all tasks in this group. */
        public void cancelAll() {
            int count = 0;
            for (Task t : tasks) {
                if (t.isRunning()) { t.cancel(); count++; }
            }
            tasks.clear();
            if (plugin != null) {
                plugin.getLogger().fine("[MagmaLib] TaskGroup '" + name + "' cancelled " + count + " task(s).");
            }
        }

        /** Removes finished tasks from this group to free memory. */
        public void prune() {
            tasks.removeIf(t -> !t.isRunning());
        }

        /** Returns the number of currently active tasks in this group. */
        public int activeCount() {
            return (int) tasks.stream().filter(Task::isRunning).count();
        }

        /** Returns this group's name. */
        public String getName() { return name; }
    }

    /**
     * Creates a new named {@link TaskGroup}.
     *
     * @param name Descriptive name (e.g. {@code "player-<uuid>"} or {@code "region-mining"}).
     */
    @AnyThread
    public static TaskGroup newTaskGroup(String name) {
        return new TaskGroup(name);
    }

    // ==================== UTILITIES ====================

    /**
     * Returns a guaranteed non-null, non-empty message from a {@link Throwable}.
     * Falls back to the exception's simple class name.
     */
    @AnyThread
    public static String safeMessage(Throwable e) {
        if (e == null) return "Unknown error";
        String msg = e.getMessage();
        return (msg != null && !msg.isEmpty()) ? msg : e.getClass().getSimpleName();
    }

    // ==================== TASK BUILDER ENTRY POINTS ====================

    /**
     * Creates a {@link TaskBuilder} for fire-and-forget tasks.
     *
     * @param runnable The action to execute.
     */
    @AnyThread
    public static TaskBuilder<Void> task(Runnable runnable) {
        return new TaskBuilder<>(runnable);
    }

    /**
     * Creates a type-safe {@link TaskBuilder} for tasks that produce a value.
     * Enables {@code thenApply} / {@code thenAccept} / {@code exceptionally} pipelines.
     *
     * @param supplier The value supplier.
     * @param <T>      The produced type.
     */
    @AnyThread
    public static <T> TaskBuilder<T> task(Supplier<T> supplier) {
        return new TaskBuilder<>(supplier);
    }

    // ==================== TASK BUILDER ====================

    /**
     * Fluent builder for cross-platform task scheduling.
     * <p>Automatically selects the correct Folia scheduler based on context.</p>
     *
     * @param <T> Return type ({@link Void} for Runnable-based builders).
     */
    public static final class TaskBuilder<T> {

        private Location          location;
        private Entity            entity;
        private long              delay  = -1; // milliseconds
        private long              period = -1; // milliseconds
        private boolean           async  = false;
        private boolean           cancelIfUnloaded = true;
        private boolean           unsafe = false;
        private BooleanSupplier   cancelCondition;
        private Consumer<Throwable> exceptionHandler;
        private String            taskName;
        private TaskGroup         group;

        private final Runnable         runnable;
        private final Supplier<T>      supplier;
        private final List<Function<Object, Object>> applyChain = new ArrayList<>();
        private Consumer<T>            thenAccept;
        private Function<Throwable, T> exceptionally;

        private TaskBuilder(Runnable runnable) {
            this.runnable = runnable;
            this.supplier = null;
        }

        private TaskBuilder(Supplier<T> supplier) {
            this.supplier = supplier;
            this.runnable = null;
        }

        // ---- ROUTING ----

        /** Routes to the chunk region of {@code location} (Folia) or main thread (Paper). */
        public TaskBuilder<T> at(Location location) {
            this.location = location; return this;
        }

        /** Routes to the entity's scheduler (Folia) or main thread (Paper). */
        public TaskBuilder<T> with(Entity entity) {
            this.entity = entity; return this;
        }

        // ---- TIMING ----

        /** Initial delay in real time. */
        public TaskBuilder<T> after(long delay, TimeUnit unit) {
            this.delay = unit.toMillis(delay); return this;
        }

        /** Initial delay in server ticks (1 tick = 50 ms). */
        public TaskBuilder<T> afterTicks(long ticks) {
            this.delay = ticks * 50L; return this;
        }

        /** Repeating interval in real time. */
        public TaskBuilder<T> every(long period, TimeUnit unit) {
            this.period = unit.toMillis(period); return this;
        }

        /** Repeating interval in server ticks. Values below 1 are clamped at dispatch time. */
        public TaskBuilder<T> everyTicks(long ticks) {
            this.period = ticks * 50L; return this;
        }

        // ---- FLAGS ----

        /**
         * Runs on the async scheduler.
         * <p>⚠️ Incompatible with {@link #with(Entity)} and {@link #at(Location)}.
         * Any Bukkit API call inside will throw.</p>
         */
        public TaskBuilder<T> async() {
            this.async = true; return this;
        }

        /** Controls auto-cancellation when the target chunk unloads (default: {@code true}). */
        public TaskBuilder<T> cancelIfUnloaded(boolean cancel) {
            this.cancelIfUnloaded = cancel; return this;
        }

        /**
         * Disables chunk-loaded and entity-valid safety checks.
         * <p>⚠️ Only use in hot paths where you manually verify all preconditions.</p>
         */
        public TaskBuilder<T> unsafe() {
            this.unsafe = true; return this;
        }

        // ---- FLOW CONTROL ----

        /**
         * Condition evaluated before each execution. When {@code true}, the run is skipped.
         * <p>For self-cancelling timers prefer {@link MagmaLib#runTimerUntil}.</p>
         */
        public TaskBuilder<T> cancelIf(BooleanSupplier condition) {
            this.cancelCondition = condition; return this;
        }

        /** Custom exception handler. If absent, errors are logged via the plugin logger. */
        public TaskBuilder<T> handleException(Consumer<Throwable> handler) {
            this.exceptionHandler = handler; return this;
        }

        /** Tags this task. The name appears in error logs for easier debugging. */
        public TaskBuilder<T> named(String name) {
            this.taskName = name; return this;
        }

        /**
         * Registers this task with a {@link TaskGroup} on {@link #run()}.
         * The task is automatically added to the group and cancelled when the group is cancelled.
         */
        public TaskBuilder<T> inGroup(TaskGroup group) {
            this.group = group; return this;
        }

        // ---- COMPOSITION (requires Supplier mode) ----

        /**
         * Transforms the pipeline value.
         * <p>Requires {@code task(Supplier<T>)}.</p>
         */
        @SuppressWarnings("unchecked")
        public <R> TaskBuilder<R> thenApply(Function<T, R> mapper) {
            requireSupplierMode("thenApply");
            applyChain.add(v -> mapper.apply((T) v));
            return (TaskBuilder<R>) this;
        }

        /**
         * Consumes the final pipeline value for a side effect.
         * Multiple calls chain sequentially.
         * <p>Requires {@code task(Supplier<T>)}.</p>
         */
        public TaskBuilder<T> thenAccept(Consumer<T> action) {
            requireSupplierMode("thenAccept");
            if (this.thenAccept == null) {
                this.thenAccept = action;
            } else {
                Consumer<T> prev = this.thenAccept;
                this.thenAccept = v -> { prev.accept(v); action.accept(v); };
            }
            return this;
        }

        /**
         * Catches any exception in the pipeline and returns a fallback value.
         * <p>Requires {@code task(Supplier<T>)}.</p>
         */
        public TaskBuilder<T> exceptionally(Function<Throwable, T> fallback) {
            requireSupplierMode("exceptionally");
            this.exceptionally = fallback; return this;
        }

        private void requireSupplierMode(String method) {
            if (supplier == null) throw new IllegalStateException(
                    method + "() requires task(Supplier<T>), not task(Runnable)");
        }

        // ---- DISPATCH ----

        /**
         * Finalises configuration and dispatches the task.
         *
         * @return A {@link Task} handle for cancellation and status queries.
         */
        public Task run() {
            requireInit();
            Runnable exec = createExecutable();
            Task task;
            if      (async)          task = runAsyncInternal(exec);
            else if (location != null) task = runAtLocation(exec);
            else if (entity != null)   task = runWithEntity(exec);
            else                       task = runGlobal(exec);

            track(task);
            if (group != null) group.add(task);
            return task;
        }

        @SuppressWarnings("unchecked")
        private Runnable createExecutable() {
            if (runnable != null) return () -> executeSafely(runnable);
            if (supplier != null) return () -> {
                try {
                    if (cancelCondition != null && cancelCondition.getAsBoolean()) return;
                    Object result = supplier.get();
                    for (Function<Object, Object> m : applyChain) result = m.apply(result);
                    if (thenAccept != null) thenAccept.accept((T) result);
                } catch (Throwable e) {
                    handlePipelineError(e);
                }
            };
            return () -> {};
        }

        private void handlePipelineError(Throwable original) {
            if (exceptionally != null) {
                try {
                    exceptionally.apply(original);
                    return;
                } catch (Throwable fallbackError) {
                    logError("Original error before fallback failed", original);
                    logError("Fallback handler also failed", fallbackError);
                    return;
                }
            }
            if (exceptionHandler != null) exceptionHandler.accept(original);
            else logError("Error in MagmaLib task" + contextLog(), original);
        }

        private void executeSafely(Runnable action) {
            try {
                if (cancelCondition == null || !cancelCondition.getAsBoolean()) action.run();
            } catch (Throwable e) {
                if (exceptionHandler != null) exceptionHandler.accept(e);
                else logError("Error in MagmaLib task" + contextLog(), e);
            }
        }

        private void logError(String msg, Throwable e) {
            plugin.getLogger().log(Level.SEVERE, msg + ": " + safeMessage(e), e);
        }

        /**
         * Converts stored ms to Folia-safe ticks.
         * Folia rejects 0-tick initial delays for fixed-rate tasks — minimum is 1.
         */
        private long toFoliaSafeTicks(long ms, boolean isPeriodic, boolean isDelay) {
            if (ms <= 0) return (isFolia() && isPeriodic && isDelay) ? 1L : 0L;
            long ticks = ms / 50L;
            if (isFolia() && isPeriodic && isDelay && ticks <= 0) return 1L;
            return Math.max(0L, ticks);
        }

        private Task runAsyncInternal(Runnable r) {
            if (isFolia()) {
                AsyncScheduler s = Bukkit.getAsyncScheduler();
                if (period > 0) {
                    long safeDelay = delay > 0 ? delay : 50L;
                    return new FoliaTask(s.runAtFixedRate(plugin, t -> r.run(), safeDelay, period, TimeUnit.MILLISECONDS));
                } else if (delay > 0) {
                    return new FoliaTask(s.runDelayed(plugin, t -> r.run(), delay, TimeUnit.MILLISECONDS));
                } else {
                    s.runNow(plugin, t -> r.run());
                    return EmptyTask.INSTANCE;
                }
            } else {
                long dt = delay > 0 ? delay / 50L : 0;
                if (period > 0) {
                    long pt = period / 50L;
                    return new PaperTask(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, r, dt, pt));
                } else if (delay > 0) {
                    return new PaperTask(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, r, dt));
                } else {
                    return new PaperTask(Bukkit.getScheduler().runTaskAsynchronously(plugin, r));
                }
            }
        }

        private Task runAtLocation(Runnable r) {
            if (isFolia()) {
                RegionScheduler s = Bukkit.getRegionScheduler();
                if (!unsafe) {
                    World w = location.getWorld();
                    if (w == null) {
                        plugin.getLogger().warning("Location world is null, task cancelled" + contextLog());
                        return EmptyTask.INSTANCE;
                    }
                    if (cancelIfUnloaded && !w.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4))
                        return EmptyTask.INSTANCE;
                }
                long dt = toFoliaSafeTicks(delay > 0 ? delay : 0, period > 0, true);
                long pt = toFoliaSafeTicks(period, period > 0, false);
                if (period > 0) {
                    long sd = delay <= 0 ? 1 : dt;
                    return new FoliaTask(s.runAtFixedRate(plugin, location, t -> r.run(), sd, pt));
                } else if (delay > 0) {
                    return new FoliaTask(s.runDelayed(plugin, location, t -> r.run(), dt));
                } else {
                    s.run(plugin, location, t -> r.run());
                    return EmptyTask.INSTANCE;
                }
            }
            return runGlobal(r);
        }

        private Task runWithEntity(Runnable r) {
            if (isFolia() && entity != null) {
                EntityScheduler s = entity.getScheduler();
                if (!unsafe && cancelIfUnloaded && !entity.isValid()) return EmptyTask.INSTANCE;
                long dt = toFoliaSafeTicks(delay > 0 ? delay : 0, period > 0, true);
                long pt = toFoliaSafeTicks(period, period > 0, false);
                if (period > 0) {
                    long sd = delay <= 0 ? 1 : dt;
                    return new FoliaTask(s.runAtFixedRate(plugin, t -> r.run(), null, sd, pt));
                } else if (delay > 0) {
                    return new FoliaTask(s.runDelayed(plugin, t -> r.run(), null, dt));
                } else {
                    s.run(plugin, t -> r.run(), null);
                    return EmptyTask.INSTANCE;
                }
            }
            return runGlobal(r);
        }

        private Task runGlobal(Runnable r) {
            if (isFolia()) {
                GlobalRegionScheduler s = Bukkit.getGlobalRegionScheduler();
                long dt = toFoliaSafeTicks(delay > 0 ? delay : 0, period > 0, true);
                long pt = toFoliaSafeTicks(period, period > 0, false);
                if (period > 0) {
                    long sd = delay <= 0 ? 1 : dt;
                    return new FoliaTask(s.runAtFixedRate(plugin, t -> r.run(), sd, pt));
                } else if (delay > 0) {
                    return new FoliaTask(s.runDelayed(plugin, t -> r.run(), dt));
                } else {
                    s.run(plugin, t -> r.run());
                    return EmptyTask.INSTANCE;
                }
            } else {
                long dt = delay > 0 ? delay / 50L : 0;
                long pt = period > 0 ? period / 50L : 0;
                if (period > 0)
                    return new PaperTask(Bukkit.getScheduler()
                            .runTaskTimer(plugin, r, Math.max(0, dt), Math.max(1, pt)));
                else if (delay > 0)
                    return new PaperTask(Bukkit.getScheduler().runTaskLater(plugin, r, dt));
                else
                    return new PaperTask(Bukkit.getScheduler().runTask(plugin, r));
            }
        }

        private String contextLog() {
            StringBuilder sb = new StringBuilder();
            if (taskName  != null) sb.append(" task='").append(taskName).append("'");
            if (location  != null) sb.append(" location=").append(location);
            if (entity    != null) sb.append(" entity=").append(entity.getType());
            return sb.toString();
        }
    }

    // ==================== TASK HANDLE ====================

    /** Platform-agnostic handle returned by every scheduling call. */
    public interface Task {
        /** Cancels this task. No-op if already cancelled or completed. */
        void cancel();
        /** Returns {@code true} if this task has been cancelled. */
        boolean isCancelled();
        /** Returns {@code true} if this task is still scheduled or running. */
        boolean isRunning();
    }

    // Stateless singleton — no allocation on chunk-not-loaded fast-path.
    private static final class EmptyTask implements Task {
        static final EmptyTask INSTANCE = new EmptyTask();
        private EmptyTask() {}
        @Override public void cancel()            {}
        @Override public boolean isCancelled()    { return true; }
        @Override public boolean isRunning()      { return false; }
    }

    private static final class PaperTask implements Task {
        private final BukkitTask t;
        PaperTask(BukkitTask t)                  { this.t = t; }
        @Override public void cancel()            { if (t != null) t.cancel(); }
        @Override public boolean isCancelled()    { return t == null || t.isCancelled(); }
        @Override public boolean isRunning()      { return t != null && !t.isCancelled(); }
    }

    private static final class FoliaTask implements Task {
        private final ScheduledTask t;
        FoliaTask(ScheduledTask t)                { this.t = t; }
        @Override public void cancel()            { if (t != null) t.cancel(); }
        @Override public boolean isCancelled()    { return t == null || t.isCancelled(); }
        @Override public boolean isRunning()      { return t != null && !t.isCancelled(); }
    }

    // ==================== TELEPORT ====================

    /**
     * Teleports an entity asynchronously with platform-aware fallbacks.
     *
     * <ul>
     *   <li><b>Folia</b>: uses the entity's async teleport via EntityScheduler.</li>
     *   <li><b>Paper 1.20+</b>: uses {@code Entity.teleportAsync()} (native async support).</li>
     *   <li><b>Spigot</b>: schedules a synchronous teleport on the next tick as fallback.</li>
     * </ul>
     *
     * @param entity   Entity or player to teleport.
     * @param location Destination location.
     * @return {@link CompletableFuture}{@code <Boolean>} — {@code true} on success.
     */
    @AnyThread
    public static CompletableFuture<Boolean> teleportAsync(Entity entity, Location location) {
        return teleportAsync(entity, location, TeleportCause.PLUGIN);
    }

    /**
     * Teleports an entity asynchronously with a custom {@link TeleportCause}.
     *
     * @param entity   Entity to teleport.
     * @param location Destination.
     * @param cause    Reason for the teleport.
     * @return {@link CompletableFuture}{@code <Boolean>} — {@code true} on success.
     */
    @AnyThread
    public static CompletableFuture<Boolean> teleportAsync(Entity entity, Location location, TeleportCause cause) {
        requireInit();
        if (entity == null || location == null) {
            CompletableFuture<Boolean> f = new CompletableFuture<>();
            f.complete(false);
            return f;
        }

        if (isFolia()) {
            // Folia: teleport must happen on the entity's region thread.
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            entity.getScheduler().execute(plugin, () -> {
                try {
                    entity.teleport(location, cause);
                    future.complete(true);
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                }
            }, () -> future.complete(false), 1L);
            return future;
        }

        if (isPaper()) {
            // Paper: native async teleport available since 1.13.
            return entity.teleportAsync(location, cause);
        }

        // Spigot fallback: schedule on next tick.
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                boolean result = entity.teleport(location, cause);
                future.complete(result);
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    // ==================== DIRECT API (HOT PATHS) ====================

    /**
     * Executes {@code runnable} on the next global/main-thread tick. No builder, no tracking.
     *
     * @param runnable The action to execute.
     */
    @AnyThread
    public static void runDirect(Runnable runnable) {
        if (isFolia()) Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
        else           Bukkit.getScheduler().runTask(plugin, runnable);
    }

    /**
     * Executes {@code runnable} on the region thread owning {@code location}.
     *
     * @param location Routing context.
     * @param runnable The action.
     */
    @AnyThread
    public static void runDirectAt(Location location, Runnable runnable) {
        if (isFolia()) Bukkit.getRegionScheduler().execute(plugin, location, runnable);
        else           Bukkit.getScheduler().runTask(plugin, runnable);
    }

    /**
     * Executes {@code runnable} on the entity's scheduler (Folia) or main thread (Paper/Spigot).
     *
     * @param entity   Routing context.
     * @param runnable The action.
     */
    @AnyThread
    public static void runDirectWith(Entity entity, Runnable runnable) {
        if (isFolia() && entity != null) entity.getScheduler().execute(plugin, runnable, null, 0L);
        else                             Bukkit.getScheduler().runTask(plugin, runnable);
    }

    /**
     * One-shot delayed execution on the global scheduler. No builder, no tracking.
     *
     * @param runnable   The action.
     * @param delayTicks Delay in server ticks (≥ 1).
     */
    @AnyThread
    public static void runDirectLater(Runnable runnable, long delayTicks) {
        if (isFolia()) Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> runnable.run(), delayTicks);
        else           Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
    }

    /**
     * Repeating task on the global scheduler. No builder, no tracking.
     *
     * @param runnable    The action.
     * @param periodTicks Interval in server ticks (≥ 1).
     */
    @AnyThread
    public static void runDirectTimer(Runnable runnable, long periodTicks) {
        if (isFolia()) Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin, t -> runnable.run(), 1L, Math.max(1L, periodTicks));
        else           Bukkit.getScheduler().runTaskTimer(plugin, runnable, 0L, periodTicks);
    }

    // ==================== STANDARD UTILITIES ====================

    /** Runs {@code runnable} on the very next tick. */
    @AnyThread
    public static void runNextTick(Runnable runnable) {
        task(runnable).afterTicks(1).run();
    }

    /** Runs {@code runnable} after the specified real-time delay. */
    @AnyThread
    public static void runLater(Runnable runnable, long delay, TimeUnit unit) {
        task(runnable).after(delay, unit).run();
    }

    /**
     * Runs {@code runnable} asynchronously and returns a {@link CompletableFuture<Void>}.
     * Exceptions propagate via {@code completeExceptionally}.
     */
    @AnyThread
    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        task(() -> { try { runnable.run(); f.complete(null); }
        catch (Throwable e) { f.completeExceptionally(e); } })
                .async().run();
        return f;
    }

    /** Calls {@code supplier} asynchronously and returns the result via {@link CompletableFuture}. */
    @AnyThread
    public static <T> CompletableFuture<T> callAsync(Supplier<T> supplier) {
        CompletableFuture<T> f = new CompletableFuture<>();
        task(() -> { try { f.complete(supplier.get()); }
        catch (Throwable e) { f.completeExceptionally(e); } })
                .async().run();
        return f;
    }

    /** Executes {@code runnable} on the global/main thread. */
    @AnyThread
    public static void runSync(Runnable runnable) {
        if (isFolia()) Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
        else           Bukkit.getScheduler().runTask(plugin, runnable);
    }

    /** Schedules {@code runnable} on the global/main thread after {@code delayTicks} ticks. */
    @AnyThread
    public static Task runSyncLater(Runnable runnable, long delayTicks) {
        return task(runnable).afterTicks(delayTicks).run();
    }

    /**
     * Runs {@code supplier} on the global/main thread and returns the result via {@link CompletableFuture}.
     * Useful for reading Bukkit API state from an async context.
     */
    @AnyThread
    public static <T> CompletableFuture<T> callSync(Supplier<T> supplier) {
        CompletableFuture<T> f = new CompletableFuture<>();
        runSync(() -> { try { f.complete(supplier.get()); }
        catch (Throwable e) { f.completeExceptionally(e); } });
        return f;
    }

    /**
     * Iterates all online players on the main thread with per-player exception isolation.
     *
     * @param action Operation to apply per player.
     */
    @AnyThread
    public static void forAllPlayers(Consumer<Player> action) {
        runSync(() -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p == null) continue;
                try { action.accept(p); }
                catch (Throwable e) {
                    plugin.getLogger().log(Level.WARNING,
                            "Error processing player " + p.getName() + ": " + safeMessage(e), e);
                }
            }
        });
    }

    /**
     * Iterates all loaded chunks on the main thread with per-chunk exception isolation.
     * <p>{@code getLoadedChunks()} is not thread-safe — must run on main thread.</p>
     *
     * @param action Operation to apply per chunk.
     */
    @AnyThread
    public static void forAllLoadedChunks(Consumer<Chunk> action) {
        runSync(() -> {
            for (World w : Bukkit.getWorlds()) {
                if (w == null) continue;
                for (Chunk c : w.getLoadedChunks()) {
                    if (c == null) continue;
                    try { action.accept(c); }
                    catch (Throwable e) {
                        plugin.getLogger().log(Level.WARNING,
                                "Error processing chunk: " + safeMessage(e), e);
                    }
                }
            }
        });
    }

    /**
     * Executes {@code task} at {@code location} only if its chunk is loaded.
     * Silent no-op otherwise.
     */
    @AnyThread
    public static void executeIfLoaded(Location location, Runnable task) {
        World w = location.getWorld();
        if (w != null && w.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4))
            MagmaLib.task(task).at(location).run();
    }

    // ==================== SELF-CANCELLING TIMERS ====================

    /**
     * Repeating task that cancels itself when {@code stopCondition} returns {@code true}.
     * <p>The task body runs ONLY while the condition is {@code false}.</p>
     *
     * @param task          Loop body.
     * @param periodTicks   Interval in ticks.
     * @param stopCondition When {@code true}, timer self-cancels.
     * @return {@link Task} handle for external cancellation.
     */
    @AnyThread
    public static Task runTimerUntil(Runnable task, long periodTicks, BooleanSupplier stopCondition) {
        AtomicReference<Task> ref = new AtomicReference<>();
        Task handle = MagmaLib.task(() -> {
            if (stopCondition.getAsBoolean()) {
                Task t = ref.get();
                if (t != null) t.cancel();
            } else {
                task.run();
            }
        }).everyTicks(periodTicks).run();
        ref.set(handle);
        return handle;
    }

    /**
     * Repeating task that runs ONLY while {@code continueCondition} is {@code true},
     * and self-cancels the moment it becomes {@code false}.
     * <p>This is the inverse of {@link #runTimerUntil} — often more readable.</p>
     *
     * <pre>{@code
     * // Runs every tick while the player is online and not dead
     * MagmaLib.runTimerWhile(
     *     () -> updateHUD(player),
     *     1,
     *     () -> player.isOnline() && !player.isDead()
     * );
     * }</pre>
     *
     * @param task              Loop body.
     * @param periodTicks       Interval in ticks.
     * @param continueCondition While {@code true}, the task runs. When {@code false}, timer cancels.
     * @return {@link Task} handle for external cancellation.
     */
    @AnyThread
    public static Task runTimerWhile(Runnable task, long periodTicks, BooleanSupplier continueCondition) {
        return runTimerUntil(task, periodTicks, () -> !continueCondition.getAsBoolean());
    }

    /**
     * Fast self-cancelling global timer. No AtomicReference allocation on the
     * happy path — the reference is resolved once at startup.
     *
     * @param task          Loop body.
     * @param periodTicks   Interval in ticks.
     * @param stopCondition When {@code true}, timer self-cancels.
     * @return {@link Task} handle.
     */
    @AnyThread
    public static Task runTimerUntilFast(Runnable task, long periodTicks, BooleanSupplier stopCondition) {
        AtomicReference<Task> ref = new AtomicReference<>();
        Runnable wrapped = () -> {
            if (stopCondition.getAsBoolean()) {
                Task t = ref.get();
                if (t != null) t.cancel();
                return;
            }
            task.run();
        };
        Task scheduled = isFolia()
                ? new FoliaTask(Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin, t -> wrapped.run(), 1L, Math.max(1L, periodTicks)))
                : new PaperTask(Bukkit.getScheduler()
                .runTaskTimer(plugin, wrapped, 0L, periodTicks));
        ref.set(scheduled);
        track(scheduled);
        return scheduled;
    }

    // ==================== RETRY ====================

    /**
     * Executes {@code task} and retries up to {@code maxAttempts} times on failure.
     * First attempt is immediate; retries use {@code delayBetweenAttempts}.
     *
     * @param task                 Operation to attempt.
     * @param maxAttempts          Max tries (≥ 1).
     * @param delayBetweenAttempts Delay between retries.
     * @param unit                 Time unit of the delay.
     * @throws IllegalArgumentException if {@code maxAttempts < 1}.
     */
    @AnyThread
    public static void runWithRetry(Runnable task, int maxAttempts, long delayBetweenAttempts, TimeUnit unit) {
        if (maxAttempts < 1) throw new IllegalArgumentException(
                "maxAttempts must be >= 1, got: " + maxAttempts);
        new Runnable() {
            int attempts = 0;
            @Override public void run() {
                try { task.run(); }
                catch (Throwable e) {
                    attempts++;
                    if (attempts < maxAttempts) runLater(this, delayBetweenAttempts, unit);
                    else plugin.getLogger().log(Level.SEVERE,
                            "Task failed after " + maxAttempts + " attempt(s): " + safeMessage(e), e);
                }
            }
        }.run();
    }

    // ==================== FOLIA SCHEDULER ACCESS ====================

    /**
     * Returns Folia's {@link RegionScheduler}.
     * @throws UnsupportedOperationException on Paper/Spigot.
     */
    public static RegionScheduler getRegionScheduler() {
        if (isFolia()) return Bukkit.getRegionScheduler();
        throw new UnsupportedOperationException("RegionScheduler is only available on Folia");
    }

    /**
     * Returns Folia's {@link EntityScheduler} for the given entity.
     * @throws UnsupportedOperationException on Paper/Spigot or if entity is null.
     */
    public static EntityScheduler getEntityScheduler(Entity entity) {
        if (isFolia() && entity != null) return entity.getScheduler();
        throw new UnsupportedOperationException("EntityScheduler is only available on Folia");
    }

    // ==================== TIME CONVERSION ====================

    /** Converts server ticks to milliseconds (× 50). */
    public static long ticksToMs(long ticks)  { return ticks * 50L; }

    /** Converts milliseconds to server ticks (÷ 50). */
    public static long msToTicks(long ms)     { return ms / 50L; }
}