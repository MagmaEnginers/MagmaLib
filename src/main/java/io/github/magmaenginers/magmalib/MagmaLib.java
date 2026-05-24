package io.github.magmaenginers.magmalib;

import io.papermc.paper.threadedregions.scheduler.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;

/**
 * Modern, type-safe scheduling API providing an abstraction layer for both Paper and Folia.
 * <p>
 * <b>Main Features:</b>
 * <ul>
 * <li>Fluent Task Builder with type-safe generic composition.</li>
 * <li>Zero-allocation direct API designed for hot paths.</li>
 * <li>Automatic Folia/Paper detection backed by a fast reflection cache.</li>
 * <li>Safe chunk and entity handling with an {@code unsafe()} bypass for maximum performance.</li>
 * <li>CompletableFuture-style task chaining: {@code thenApply}, {@code thenAccept}, {@code exceptionally}.</li>
 * <li>Advanced flow control utilities: {@code runTimerUntil}, {@code runWithRetry}, {@code forAllPlayers}.</li>
 * </ul>
 *
 * <p><b>Example of type-safe functional composition:</b></p>
 * <pre>{@code
 * MagmaLib.<Integer>task(() -> calculateScore(player))
 * .at(player.getLocation())
 * .afterTicks(10)
 * .thenApply(score -> score > 100 ? "Excellent!" : "Keep trying!")
 * .thenAccept(message -> player.sendMessage(message))
 * .exceptionally(e -> {
 * plugin.getLogger().warning("Failed to calculate score: " + safeMessage(e));
 * return "Error";
 * })
 * .run();
 * }</pre>
 *
 * @author Piratemajo
 * @version 2.1.1
 * @see <a href="https://github.com/MagmaEnginers/MagmaLib">GitHub Repository</a>
 */
public class MagmaLib {

    private static Plugin plugin;
    private static Boolean isFoliaCache = null;

    /**
     * Initializes MagmaLib with your main plugin instance.
     * <p>
     * This method must be called within your {@link JavaPlugin#onEnable()} before utilizing
     * any other library feature. It detects Folia via reflection and caches the result.
     *
     * @param plugin The main plugin instance.
     * @throws IllegalStateException If the plugin instance is null.
     */
    public static void init(Plugin plugin) {
        if (plugin == null) {
            throw new IllegalStateException("MagmaLib.init() requires a non-null plugin instance");
        }
        MagmaLib.plugin = plugin;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            isFoliaCache = true;
        } catch (ClassNotFoundException e) {
            isFoliaCache = false;
        }
    }

    /**
     * Determines whether the current server software is running on Folia.
     * <p>
     * The result is safely cached after the first check to completely avoid overhead.
     *
     * @return {@code true} if running on Folia, {@code false} if Paper/Spigot.
     * @throws IllegalStateException If {@link #init(Plugin)} hasn't been called yet.
     */
    public static boolean isFolia() {
        if (isFoliaCache == null) {
            if (plugin == null) {
                throw new IllegalStateException("MagmaLib.init() must be called before isFolia()");
            }
            try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                isFoliaCache = true;
            } catch (ClassNotFoundException e) {
                isFoliaCache = false;
            }
        }
        return isFoliaCache;
    }

    /**
     * Gets the currently registered plugin instance.
     *
     * @return The registered {@link Plugin}, or {@code null} if uninitialized.
     */
    public static Plugin getPlugin() {
        return plugin;
    }

    // ==================== UTILITIES ====================

    /**
     * Gets a null-safe error message suitable for logging purposes.
     * <p>
     * If {@code e.getMessage()} is null or empty, it returns the Exception's simple class name.
     *
     * @param e The exception to process.
     * @return A descriptive string that is guaranteed to never be null.
     */
    public static String safeMessage(Throwable e) {
        if (e == null) return "Unknown error";
        String msg = e.getMessage();
        return (msg != null && !msg.isEmpty()) ? msg : e.getClass().getSimpleName();
    }

    // ==================== TASK BUILDER ENTRY POINTS ====================

    /**
     * Creates a new {@link TaskBuilder} for tasks that do not return a value.
     * <p>
     * Backward-compatible endpoint. For type-safe functional chaining, use {@link #task(Supplier)} instead.
     *
     * @param runnable The code block to execute.
     * @return A new {@link TaskBuilder} instance for further configuration.
     */
    public static TaskBuilder<Void> task(Runnable runnable) {
        return new TaskBuilder<>(runnable);
    }

    /**
     * Creates a new generic {@link TaskBuilder} for tasks that return a computed value.
     * <p>
     * This supports functional pipeline styling similar to {@link CompletableFuture} via
     * {@link TaskBuilder#thenApply(Function)}, {@link TaskBuilder#thenAccept(Consumer)},
     * and {@link TaskBuilder#exceptionally(Function)}.
     *
     * @param supplier The value provider/calculator.
     * @param <T> The type of the value returned by this provider.
     * @return A new {@link TaskBuilder} instance supporting type-safe composition.
     */
    public static <T> TaskBuilder<T> task(Supplier<T> supplier) {
        return new TaskBuilder<>(supplier);
    }

    // ==================== TASK BUILDER IMPLEMENTATION ====================

    /**
     * Fluent builder to configure and schedule tasks with multi-platform awareness.
     *
     * @param <T> The return type of the task (Void for Runnable actions).
     */
    public static class TaskBuilder<T> {
        private Location location;
        private Entity entity;
        private long delay = -1;
        private long period = -1;
        private boolean async = false;
        private boolean cancelIfUnloaded = true;
        private boolean unsafe = false;
        private BooleanSupplier cancelCondition;
        private Consumer<Throwable> exceptionHandler;
        private String taskName;

        private final Runnable runnable;
        private final Supplier<T> supplier;

        private Function<T, ?> thenApply;
        private Consumer<T> thenAccept;
        private Function<Throwable, T> exceptionally;

        private TaskBuilder(Runnable runnable) {
            this.runnable = runnable;
            this.supplier = null;
        }

        private TaskBuilder(Supplier<T> supplier) {
            this.supplier = supplier;
            this.runnable = null;
        }

        /**
         * Binds the task to a specific location for regional routing on Folia.
         *
         * @param location The target location.
         * @return This builder instance for chaining.
         */
        public TaskBuilder<T> at(Location location) {
            this.location = location;
            return this;
        }

        /**
         * Binds the task to an entity's lifecycle and regional thread scheduler on Folia.
         *
         * @param entity The target entity.
         * @return This builder instance for chaining.
         */
        public TaskBuilder<T> with(Entity entity) {
            this.entity = entity;
            return this;
        }

        /**
         * Sets an initial delay before the task's first execution.
         *
         * @param delay The duration of the delay.
         * @param unit The time unit of the delay.
         * @return This builder instance for chaining.
         */
        public TaskBuilder<T> after(long delay, TimeUnit unit) {
            this.delay = unit.toMillis(delay);
            return this;
        }

        /**
         * Sets an initial delay in Minecraft Server Ticks (1 tick = 50ms).
         *
         * @param ticks The amount of ticks to wait.
         * @return This builder instance for chaining.
         */
        public TaskBuilder<T> afterTicks(long ticks) {
            this.delay = ticks * 50L;
            return this;
        }

        /**
         * Sets a repeating interval period for the task.
         *
         * @param period The duration of the interval.
         * @param unit The time unit of the interval.
         * @return This builder instance for chaining.
         */
        public TaskBuilder<T> every(long period, TimeUnit unit) {
            this.period = unit.toMillis(period);
            return this;
        }

        /**
         * Sets a repeating interval period in Minecraft Server Ticks.
         *
         * @param ticks The amount of ticks between runs.
         * @return This builder instance for chaining.
         */
        public TaskBuilder<T> everyTicks(long ticks) {
            this.period = ticks * 50L;
            return this;
        }

        /**
         * Flags the task to be run completely asynchronously.
         * <p>
         * ⚠️ Asynchronous tasks cannot interact safely with standard Bukkit API components.
         *
         * @return This builder instance for chaining.
         */
        public TaskBuilder<T> async() {
            this.async = true;
            return this;
        }

        /**
         * Configures whether the task should automatically abort if target chunks are unloaded.
         *
         * @param cancel {@code true} to automatically drop the task if unloaded (default).
         * @return This builder instance for chaining.
         */
        public TaskBuilder<T> cancelIfUnloaded(boolean cancel) {
            this.cancelIfUnloaded = cancel;
            return this;
        }

        /**
         * ⚠️ <b>HIGH PERFORMANCE BYPASS:</b> Completely disables structural safety checks.
         * <p>
         * Use this ONLY in high-frequency hot paths where you manually guarantee:
         * <ul>
         * <li>{@code location.getWorld() != null}</li>
         * <li>The chunk is actively loaded: {@code world.isChunkLoaded(x, z)}</li>
         * <li>The target entity is valid: {@code entity.isValid() == true}</li>
         * </ul>
         *
         * @return This builder instance for chaining.
         */
        public TaskBuilder<T> unsafe() {
            this.unsafe = true;
            return this;
        }

        /**
         * Defines a dynamic condition checked right before every execution to trigger cancellation.
         *
         * @param condition A supplier returning {@code true} to cancel the task permanently.
         * @return This builder instance for chaining.
         */
        public TaskBuilder<T> cancelIf(BooleanSupplier condition) {
            this.cancelCondition = condition;
            return this;
        }

        /**
         * Assigns a custom error handler to trap exceptions thrown during execution.
         *
         * @param handler A consumer acting upon the caught exception.
         * @return This builder instance for chaining.
         */
        public TaskBuilder<T> handleException(Consumer<Throwable> handler) {
            this.exceptionHandler = handler;
            return this;
        }

        /**
         * Assigns a custom identifier tag for debugging and logging profiles.
         *
         * @param name Descriptive name of the task.
         * @return This builder instance for chaining.
         */
        public TaskBuilder<T> named(String name) {
            this.taskName = name;
            return this;
        }

        /**
         * Pipelines the result of the task into a mapping function.
         * <p>
         * Behaves exactly like {@link CompletableFuture#thenApply(Function)}.
         *
         * @param mapper The function transforming the old type into a new one.
         * @param <R> The new transformed data type.
         * @return An updated TaskBuilder mapping the new return type.
         * @throws IllegalStateException If used on a builder initialized with a Runnable.
         */
        @SuppressWarnings("unchecked")
        public <R> TaskBuilder<R> thenApply(Function<T, R> mapper) {
            if (supplier == null) {
                throw new IllegalStateException("thenApply() requires task(Supplier<T>), not task(Runnable)");
            }
            if (this.thenApply == null) {
                this.thenApply = mapper;
            } else {
                Function<T, ?> previous = this.thenApply;
                this.thenApply = (Function<T, Object>) value -> mapper.apply((T) previous.apply(value));
            }
            return (TaskBuilder<R>) this;
        }

        /**
         * Consumes the final result of the pipeline without returning a value.
         * <p>
         * Behaves exactly like {@link CompletableFuture#thenAccept(Consumer)}.
         *
         * @param action Consumer acting upon the computed result.
         * @return This builder instance for chaining.
         * @throws IllegalStateException If used on a builder initialized with a Runnable.
         */
        public TaskBuilder<T> thenAccept(Consumer<T> action) {
            if (supplier == null) {
                throw new IllegalStateException("thenAccept() requires task(Supplier<T>), not task(Runnable)");
            }
            if (this.thenAccept == null) {
                this.thenAccept = action;
            } else {
                Consumer<T> previous = this.thenAccept;
                this.thenAccept = value -> {
                    previous.accept(value);
                    action.accept(value);
                };
            }
            return this;
        }

        /**
         * Traps exceptions within the pipeline and recovers with a fallback value.
         * <p>
         * Behaves exactly like {@link CompletableFuture#exceptionally(Function)}.
         *
         * @param fallback A function returning a safe value upon failure.
         * @return This builder instance for chaining.
         * @throws IllegalStateException If used on a builder initialized with a Runnable.
         */
        public TaskBuilder<T> exceptionally(Function<Throwable, T> fallback) {
            if (supplier == null) {
                throw new IllegalStateException("exceptionally() requires task(Supplier<T>), not task(Runnable)");
            }
            this.exceptionally = fallback;
            return this;
        }

        /**
         * Finalizes configuration and schedules the task context across the target platform.
         *
         * @return A {@link Task} handle allowing manual lifecycle monitoring or cancellation.
         */
        public Task run() {
            Runnable executable = createExecutable();
            if (async) {
                return runAsync(executable);
            } else if (location != null) {
                return runAtLocation(executable);
            } else if (entity != null) {
                return runWithEntity(executable);
            } else {
                return runGlobal(executable);
            }
        }

        @SuppressWarnings("unchecked")
        private Runnable createExecutable() {
            if (runnable != null) {
                return () -> executeSafely(runnable);
            } else if (supplier != null) {
                return () -> {
                    try {
                        if (cancelCondition != null && cancelCondition.getAsBoolean()) {
                            return;
                        }
                        T result = supplier.get();
                        if (thenApply != null) {
                            result = (T) thenApply.apply(result);
                        }
                        if (thenAccept != null) {
                            thenAccept.accept(result);
                        }
                    } catch (Throwable e) {
                        if (exceptionally != null) {
                            try {
                                exceptionally.apply(e);
                                return;
                            } catch (Throwable fallbackError) {
                                if (exceptionHandler != null) {
                                    exceptionHandler.accept(fallbackError);
                                } else {
                                    plugin.getLogger().severe("Fallback handler failed: " + safeMessage(fallbackError));
                                }
                            }
                        }
                        if (exceptionHandler != null) {
                            exceptionHandler.accept(e);
                        } else {
                            String context = taskName != null ? " ['" + taskName + "']" : "";
                            plugin.getLogger().severe("Error in MagmaLib task" + context + ": " + safeMessage(e));
                            e.printStackTrace();
                        }
                    }
                };
            }
            return () -> {};
        }

        private void executeSafely(Runnable action) {
            try {
                if (cancelCondition == null || !cancelCondition.getAsBoolean()) {
                    action.run();
                }
            } catch (Throwable e) {
                if (exceptionHandler != null) {
                    exceptionHandler.accept(e);
                } else {
                    String context = taskName != null ? " ['" + taskName + "']" : "";
                    plugin.getLogger().severe("Error in MagmaLib task" + context + ": " + safeMessage(e));
                    e.printStackTrace();
                }
            }
        }

        private long toFoliaSafeTicks(long ms, boolean isPeriodic, boolean isDelay) {
            if (ms <= 0) {
                if (isFolia() && isPeriodic && isDelay) {
                    return 1;
                }
                return 0;
            }
            long ticks = ms / 50L;
            if (isFolia() && isPeriodic && isDelay && ticks <= 0) {
                return 1;
            }
            return Math.max(0, ticks);
        }

        private Task runAsync(Runnable safeRunnable) {
            if (isFolia()) {
                AsyncScheduler scheduler = Bukkit.getAsyncScheduler();
                if (period > 0) {
                    long safeDelay = toFoliaSafeTicks(delay > 0 ? delay : 0, true, true);
                    long safePeriod = toFoliaSafeTicks(period, true, false);
                    return new FoliaTask(scheduler.runAtFixedRate(plugin, t -> safeRunnable.run(), safeDelay * 50L, safePeriod * 50L, TimeUnit.MILLISECONDS));
                } else if (delay > 0) {
                    return new FoliaTask(scheduler.runDelayed(plugin, t -> safeRunnable.run(), delay, TimeUnit.MILLISECONDS));
                } else {
                    scheduler.runNow(plugin, t -> safeRunnable.run());
                    return new EmptyTask();
                }
            } else {
                BukkitTask task;
                long delayTicks = delay > 0 ? delay / 50L : 0;
                if (period > 0) {
                    long periodTicks = period / 50L;
                    task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, safeRunnable, delayTicks, periodTicks);
                } else if (delay > 0) {
                    task = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, safeRunnable, delayTicks);
                } else {
                    task = Bukkit.getScheduler().runTaskAsynchronously(plugin, safeRunnable);
                }
                return new PaperTask(task);
            }
        }

        private Task runAtLocation(Runnable safeRunnable) {
            if (isFolia()) {
                RegionScheduler scheduler = Bukkit.getRegionScheduler();

                if (!unsafe) {
                    World world = location.getWorld();
                    if (world == null) {
                        plugin.getLogger().warning("Location world is null, task cancelled" + contextLog());
                        return new EmptyTask();
                    }
                    if (cancelIfUnloaded && !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                        return new EmptyTask();
                    }
                }

                long delayTicks = toFoliaSafeTicks(delay > 0 ? delay : 0, period > 0, true);
                long periodTicks = toFoliaSafeTicks(period, period > 0, false);

                if (period > 0) {
                    long safeDelay = delay <= 0 ? 1 : delayTicks;
                    return new FoliaTask(scheduler.runAtFixedRate(plugin, location, t -> safeRunnable.run(), safeDelay, periodTicks));
                } else if (delay > 0) {
                    return new FoliaTask(scheduler.runDelayed(plugin, location, t -> safeRunnable.run(), delayTicks));
                } else {
                    scheduler.run(plugin, location, t -> safeRunnable.run());
                    return new EmptyTask();
                }
            } else {
                return runGlobal(safeRunnable);
            }
        }

        private Task runWithEntity(Runnable safeRunnable) {
            if (isFolia() && entity != null) {
                EntityScheduler scheduler = entity.getScheduler();

                if (!unsafe && cancelIfUnloaded && !entity.isValid()) {
                    return new EmptyTask();
                }

                long delayTicks = toFoliaSafeTicks(delay > 0 ? delay : 0, period > 0, true);
                long periodTicks = toFoliaSafeTicks(period, period > 0, false);

                if (period > 0) {
                    long safeDelay = delay <= 0 ? 1 : delayTicks;
                    return new FoliaTask(scheduler.runAtFixedRate(plugin, t -> safeRunnable.run(), null, safeDelay, periodTicks));
                } else if (delay > 0) {
                    return new FoliaTask(scheduler.runDelayed(plugin, t -> safeRunnable.run(), null, delayTicks));
                } else {
                    scheduler.run(plugin, t -> safeRunnable.run(), null);
                    return new EmptyTask();
                }
            } else {
                return runGlobal(safeRunnable);
            }
        }

        private Task runGlobal(Runnable safeRunnable) {
            if (isFolia()) {
                GlobalRegionScheduler scheduler = Bukkit.getGlobalRegionScheduler();

                long delayTicks = toFoliaSafeTicks(delay > 0 ? delay : 0, period > 0, true);
                long periodTicks = toFoliaSafeTicks(period, period > 0, false);

                if (period > 0) {
                    long safeDelay = delay <= 0 ? 1 : delayTicks;
                    return new FoliaTask(scheduler.runAtFixedRate(plugin, t -> safeRunnable.run(), safeDelay, periodTicks));
                } else if (delay > 0) {
                    return new FoliaTask(scheduler.runDelayed(plugin, t -> safeRunnable.run(), delayTicks));
                } else {
                    scheduler.run(plugin, t -> safeRunnable.run());
                    return new EmptyTask();
                }
            } else {
                BukkitTask task;
                long delayTicks = delay > 0 ? delay / 50L : 0;
                long periodTicks = period > 0 ? period / 50L : 0;

                if (period > 0) {
                    task = Bukkit.getScheduler().runTaskTimer(plugin, safeRunnable, Math.max(0, delayTicks), Math.max(1, periodTicks));
                } else if (delay > 0) {
                    task = Bukkit.getScheduler().runTaskLater(plugin, safeRunnable, delayTicks);
                } else {
                    task = Bukkit.getScheduler().runTask(plugin, safeRunnable);
                }
                return new PaperTask(task);
            }
        }

        private String contextLog() {
            StringBuilder sb = new StringBuilder();
            if (taskName != null) sb.append(" task='").append(taskName).append("'");
            if (location != null) sb.append(" location=").append(location);
            if (entity != null) sb.append(" entity=").append(entity.getType());
            return sb.toString();
        }
    }

    // ==================== TASK INTERFACES ====================

    /**
     * Platform-agnostic task abstraction wrapper interface.
     */
    public interface Task {
        /** Cancels the scheduled task. */
        void cancel();
        /** Checks whether the task has been explicitly cancelled. */
        boolean isCancelled();
        /** Checks whether the task is still actively registered and waiting/running. */
        boolean isRunning();
    }

    private static class EmptyTask implements Task {
        @Override public void cancel() {}
        @Override public boolean isCancelled() { return true; }
        @Override public boolean isRunning() { return false; }
    }

    private static class PaperTask implements Task {
        private final BukkitTask task;
        public PaperTask(BukkitTask task) { this.task = task; }
        @Override public void cancel() { if (task != null) task.cancel(); }
        @Override public boolean isCancelled() { return task != null && task.isCancelled(); }
        @Override public boolean isRunning() { return task != null && !task.isCancelled(); }
    }

    private static class FoliaTask implements Task {
        private final ScheduledTask task;
        public FoliaTask(ScheduledTask task) { this.task = task; }
        @Override public void cancel() { if (task != null) task.cancel(); }
        @Override public boolean isCancelled() { return task != null && task.isCancelled(); }
        @Override public boolean isRunning() { return task != null && !task.isCancelled(); }
    }

    // ==================== DIRECT API (HOT PATHS) ====================

    /**
     * Executes a task on the next main/global region tick immediately with minimum footprint.
     *
     * @param runnable The action to execute.
     */
    public static void runDirect(Runnable runnable) {
        if (isFolia()) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    /**
     * Executes a task immediately inside the thread managing the provided location region.
     *
     * @param location The targeted location routing context.
     * @param runnable The action to execute.
     */
    public static void runDirectAt(Location location, Runnable runnable) {
        if (isFolia()) {
            Bukkit.getRegionScheduler().execute(plugin, location, runnable);
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    /**
     * Executes a task immediately inside the thread region managing the targeted entity lifecycle.
     *
     * @param entity   The target entity context.
     * @param runnable The action to execute.
     */
    public static void runDirectWith(Entity entity, Runnable runnable) {
        if (isFolia() && entity != null) {
            entity.getScheduler().execute(plugin, runnable, null, 0L);
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    /**
     * Schedules a delayed action on the main global ticker line using zero allocations.
     *
     * @param runnable   The action to execute.
     * @param delayTicks The amount of ticks to wait.
     */
    public static void runDirectLater(Runnable runnable, long delayTicks) {
        if (isFolia()) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> runnable.run(), delayTicks * 50L);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
        }
    }

    /**
     * Schedules a fast repeating timer task on the global tier lines with zero allocation structures.
     *
     * @param runnable    The action to repeat.
     * @param periodTicks Repeating rate interval measured in ticks.
     */
    public static void runDirectTimer(Runnable runnable, long periodTicks) {
        if (isFolia()) {
            long safeDelayMs = 50L;
            long periodMs = periodTicks * 50L;
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> runnable.run(), safeDelayMs, periodMs);
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, runnable, 0L, periodTicks);
        }
    }

    /**
     * Runs a streamlined global timer loop that will abort automatically as soon as a stop condition evaluates to true.
     *
     * @param task          The code block to execute repeatedly.
     * @param periodTicks   Repeating rate interval measured in ticks.
     * @param stopCondition Condition scanned every pass; when true, execution loops freeze and stop.
     * @return A {@link Task} controller handle.
     */
    public static Task runTimerUntilFast(Runnable task, long periodTicks, BooleanSupplier stopCondition) {
        Runnable wrapped = () -> {
            if (stopCondition.getAsBoolean()) return;
            task.run();
        };
        if (isFolia()) {
            long safeDelayMs = 50L;
            long periodMs = periodTicks * 50L;
            return new FoliaTask(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> wrapped.run(), safeDelayMs, periodMs));
        } else {
            return new PaperTask(Bukkit.getScheduler().runTaskTimer(plugin, wrapped, 0L, periodTicks));
        }
    }

    // ==================== STANDARD UTILITIES ====================

    /** Runs an action on the very next tick. */
    public static void runNextTick(Runnable runnable) {
        task(runnable).afterTicks(1).run();
    }

    /** Runs an action after a specified amount of time. */
    public static void runLater(Runnable runnable, long delay, TimeUnit unit) {
        task(runnable).after(delay, unit).run();
    }

    /** Runs an action asynchronously and returns a {@link CompletableFuture}. */
    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        task(() -> {
            try {
                runnable.run();
                future.complete(null);
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        }).async().run();
        return future;
    }

    /** Invokes a supplier asynchronously and passes the result into a {@link CompletableFuture}. */
    public static <T> CompletableFuture<T> callAsync(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        task(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        }).async().run();
        return future;
    }

    /** Runs a task synchronously on the global/main execution context thread lines. */
    public static void runSync(Runnable runnable) {
        if (isFolia()) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    /** Runs a task synchronously on the global layer after a set tick delay. */
    public static Task runSyncLater(Runnable runnable, long delayTicks) {
        return task(runnable).afterTicks(delayTicks).run();
    }

    /** Calls a value provider on the main thread loop line, returning a completing promise token. */
    public static <T> CompletableFuture<T> callSync(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        runSync(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Executes an operation on all currently online players safely.
     *
     * @param action The operation to apply to each player.
     */
    public static void forAllPlayers(Consumer<Player> action) {
        runSync(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player != null) {
                    try {
                        action.accept(player);
                    } catch (Throwable e) {
                        plugin.getLogger().warning("Error processing player " + player.getName() + ": " + safeMessage(e));
                    }
                }
            }
        });
    }

    /**
     * Scans through all currently loaded chunks across all worlds asynchronously.
     *
     * @param action The operation to execute on each loaded chunk.
     */
    public static void forAllLoadedChunks(Consumer<Chunk> action) {
        task(() -> {
            for (World world : Bukkit.getWorlds()) {
                if (world != null) {
                    for (Chunk chunk : world.getLoadedChunks()) {
                        if (chunk != null) {
                            try {
                                action.accept(chunk);
                            } catch (Throwable e) {
                                plugin.getLogger().warning("Error processing chunk: " + safeMessage(e));
                            }
                        }
                    }
                }
            }
        }).async().run();
    }

    /**
     * Executes a task at a specific location, only if the chunk at that location is currently loaded.
     *
     * @param location The target location.
     * @param task     The task to run.
     */
    public static void executeIfLoaded(Location location, Runnable task) {
        World world = location.getWorld();
        if (world != null && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            task(task).at(location).run();
        }
    }

    /**
     * Spawns a repeating task that monitors a stop condition, cancelling itself when triggered.
     *
     * @param task          The loop body task.
     * @param periodTicks   Repeating rate interval measured in ticks.
     * @param stopCondition Cancellation check logic.
     * @return A {@link Task} controller handle.
     */
    public static Task runTimerUntil(Runnable task, long periodTicks, BooleanSupplier stopCondition) {
        AtomicReference<Task> taskRef = new AtomicReference<>();
        Task actualTask = task(() -> {
            if (stopCondition.getAsBoolean()) {
                Task t = taskRef.get();
                if (t != null) t.cancel();
            } else {
                task.run();
            }
        }).everyTicks(periodTicks).run();
        taskRef.set(actualTask);
        return actualTask;
    }

    /**
     * Runs a task with automatic retry attempts if it fails/throws an exception.
     *
     * @param task                 The task to try.
     * @param maxAttempts          Maximum number of allowed tries.
     * @param delayBetweenAttempts Time delay between each consecutive retry attempt.
     * @param unit                 The time unit of the delay.
     */
    public static void runWithRetry(Runnable task, int maxAttempts, long delayBetweenAttempts, TimeUnit unit) {
        runLater(new Runnable() {
            int attempts = 0;
            @Override
            public void run() {
                try {
                    task.run();
                } catch (Throwable e) {
                    attempts++;
                    if (attempts < maxAttempts) {
                        runLater(this, delayBetweenAttempts, unit);
                    } else {
                        plugin.getLogger().severe("Task failed after " + maxAttempts + " attempts: " + safeMessage(e));
                    }
                }
            }
        }, 0, TimeUnit.MILLISECONDS);
    }

    /**
     * Directly fetches Folia's RegionScheduler.
     *
     * @return The {@link RegionScheduler}.
     * @throws UnsupportedOperationException If requested while running on non-Folia platforms.
     */
    public static RegionScheduler getRegionScheduler() {
        if (isFolia()) {
            return Bukkit.getRegionScheduler();
        }
        throw new UnsupportedOperationException("RegionScheduler is only available on Folia");
    }

    /**
     * Directly fetches Folia's EntityScheduler for a specific entity.
     *
     * @param entity The target entity.
     * @return The {@link EntityScheduler} instance bound to the target entity.
     * @throws UnsupportedOperationException If requested while running on non-Folia platforms.
     */
    public static EntityScheduler getEntityScheduler(Entity entity) {
        if (isFolia() && entity != null) {
            return entity.getScheduler();
        }
        throw new UnsupportedOperationException("EntityScheduler is only available on Folia");
    }

    /** Utility tool converting a value from Server Ticks to Milliseconds. */
    public static long ticksToMs(long ticks) {
        return ticks * 50L;
    }

    /** Utility tool converting a value from Milliseconds to Server Ticks. */
    public static long msToTicks(long ms) {
        return ms / 50L;
    }
}