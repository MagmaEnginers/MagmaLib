package io.github.piratemajo.magmalib;

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
 * MagmaLib - API moderna para scheduling compatible con Paper y Folia
 * <p>
 * Características principales:
 * <ul>
 *   <li>Task Builder fluent para configuración intuitiva</li>
 *   <li>API directa de alto rendimiento para hot paths</li>
 *   <li>Detección automática de Folia/Paper con caché</li>
 *   <li>Manejo seguro de chunks y entidades con opción unsafe</li>
 *   <li>Utilidades avanzadas: runTimerUntil, runWithRetry, forAllPlayers</li>
 * </ul>
 *
 * @author Piratemajo
 * @version 1.2
 */
public class MagmaLib {

    private static Plugin plugin;
    private static Boolean isFoliaCache = null;

    /**
     * Inicializa MagmaLib con tu plugin principal.
     * Llamar en {@link JavaPlugin#onEnable()}.
     *
     * @param plugin El plugin principal
     */
    public static void init(Plugin plugin) {
        MagmaLib.plugin = plugin;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            isFoliaCache = true;
        } catch (ClassNotFoundException e) {
            isFoliaCache = false;
        }
    }

    /**
     * @return true si el servidor está ejecutando Folia
     */
    public static boolean isFolia() {
        if (isFoliaCache == null) {
            init(plugin != null ? plugin : Bukkit.getPluginManager().getPlugins()[0]);
        }
        return isFoliaCache;
    }

    /**
     * @return El plugin inicializado
     */
    public static Plugin getPlugin() {
        return plugin;
    }

    // ==================== TASK BUILDER ====================

    public static TaskBuilder task(Runnable runnable) {
        return new TaskBuilder(runnable);
    }

    public static class TaskBuilder {
        private final Runnable runnable;
        private Location location;
        private Entity entity;
        private long delay = -1;
        private long period = -1;
        private boolean async = false;
        private boolean cancelIfUnloaded = true;
        private boolean unsafe = false;
        private BooleanSupplier cancelCondition;
        private Consumer<Exception> exceptionHandler;

        private TaskBuilder(Runnable runnable) {
            this.runnable = runnable;
        }

        public TaskBuilder at(Location location) {
            this.location = location;
            return this;
        }

        public TaskBuilder with(Entity entity) {
            this.entity = entity;
            return this;
        }

        public TaskBuilder after(long delay, TimeUnit unit) {
            this.delay = unit.toMillis(delay);
            return this;
        }

        public TaskBuilder afterTicks(long ticks) {
            this.delay = ticks * 50L;
            return this;
        }

        public TaskBuilder every(long period, TimeUnit unit) {
            this.period = unit.toMillis(period);
            return this;
        }

        public TaskBuilder everyTicks(long ticks) {
            this.period = ticks * 50L;
            return this;
        }

        public TaskBuilder async() {
            this.async = true;
            return this;
        }

        public TaskBuilder cancelIfUnloaded(boolean cancel) {
            this.cancelIfUnloaded = cancel;
            return this;
        }

        /**
         * ⚠️ MODO ALTO RENDIMIENTO: Desactiva validaciones de seguridad.
         * <p>
         * Úsalo SOLO en hot paths donde garantizas:
         * <ul>
         *   <li>Location.world != null</li>
         *   <li>Chunk está cargado (para .at())</li>
         *   <li>Entity.isValid() == true (para .with())</li>
         * </ul>
         * Si no se cumplen, pueden ocurrir NullPointerException o IllegalStateException.
         *
         * @return this para chaining
         */
        public TaskBuilder unsafe() {
            this.unsafe = true;
            return this;
        }

        public TaskBuilder cancelIf(BooleanSupplier condition) {
            this.cancelCondition = condition;
            return this;
        }

        public TaskBuilder handleException(Consumer<Exception> handler) {
            this.exceptionHandler = handler;
            return this;
        }

        public Task run() {
            if (async) {
                return runAsync();
            } else if (location != null) {
                return runAtLocation();
            } else if (entity != null) {
                return runWithEntity();
            } else {
                return runGlobal();
            }
        }

        private void executeSafely() {
            try {
                if (cancelCondition == null || !cancelCondition.getAsBoolean()) {
                    runnable.run();
                }
            } catch (Exception e) {
                if (exceptionHandler != null) {
                    exceptionHandler.accept(e);
                } else {
                    plugin.getLogger().severe("Error en tarea MagmaLib: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        private Task runAsync() {
            if (isFolia()) {
                AsyncScheduler scheduler = Bukkit.getAsyncScheduler();
                if (period > 0) {
                    return new FoliaTask(scheduler.runAtFixedRate(plugin, t -> executeSafely(), delay, period, TimeUnit.MILLISECONDS));
                } else if (delay > 0) {
                    return new FoliaTask(scheduler.runDelayed(plugin, t -> executeSafely(), delay, TimeUnit.MILLISECONDS));
                } else {
                    scheduler.runNow(plugin, t -> executeSafely());
                    return new EmptyTask();
                }
            } else {
                BukkitTask task;
                long delayTicks = delay / 50L;
                if (period > 0) {
                    long periodTicks = period / 50L;
                    task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::executeSafely, delayTicks, periodTicks);
                } else if (delay > 0) {
                    task = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, this::executeSafely, delayTicks);
                } else {
                    task = Bukkit.getScheduler().runTaskAsynchronously(plugin, this::executeSafely);
                }
                return new PaperTask(task);
            }
        }

        private Task runAtLocation() {
            if (isFolia()) {
                RegionScheduler scheduler = Bukkit.getRegionScheduler();

                if (!unsafe) {
                    World world = location.getWorld();
                    if (world == null) {
                        plugin.getLogger().warning("Location world is null, task cancelled");
                        return new EmptyTask();
                    }
                    if (cancelIfUnloaded && !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                        return new EmptyTask();
                    }
                }

                long delayTicks = delay / 50L;
                long periodTicks = period / 50L;

                if (period > 0) {
                    return new FoliaTask(scheduler.runAtFixedRate(plugin, location, t -> executeSafely(), delayTicks, periodTicks));
                } else if (delay > 0) {
                    return new FoliaTask(scheduler.runDelayed(plugin, location, t -> executeSafely(), delayTicks));
                } else {
                    scheduler.run(plugin, location, t -> executeSafely());
                    return new EmptyTask();
                }
            } else {
                return runGlobal();
            }
        }

        private Task runWithEntity() {
            if (isFolia() && entity != null) {
                EntityScheduler scheduler = entity.getScheduler();

                if (!unsafe && cancelIfUnloaded && !entity.isValid()) {
                    return new EmptyTask();
                }

                long delayTicks = delay / 50L;
                long periodTicks = period / 50L;

                if (period > 0) {
                    return new FoliaTask(scheduler.runAtFixedRate(plugin, t -> executeSafely(), null, delayTicks, periodTicks));
                } else if (delay > 0) {
                    return new FoliaTask(scheduler.runDelayed(plugin, t -> executeSafely(), null, delayTicks));
                } else {
                    scheduler.run(plugin, t -> executeSafely(), null);
                    return new EmptyTask();
                }
            } else {
                return runGlobal();
            }
        }

        private Task runGlobal() {
            if (isFolia()) {
                GlobalRegionScheduler scheduler = Bukkit.getGlobalRegionScheduler();
                long delayTicks = delay / 50L;
                long periodTicks = period / 50L;

                if (period > 0) {
                    return new FoliaTask(scheduler.runAtFixedRate(plugin, t -> executeSafely(), delayTicks, periodTicks));
                } else if (delay > 0) {
                    return new FoliaTask(scheduler.runDelayed(plugin, t -> executeSafely(), delayTicks));
                } else {
                    scheduler.run(plugin, t -> executeSafely());
                    return new EmptyTask();
                }
            } else {
                BukkitTask task;
                long delayTicks = delay / 50L;
                long periodTicks = period / 50L;

                if (period > 0) {
                    task = Bukkit.getScheduler().runTaskTimer(plugin, this::executeSafely, delayTicks, periodTicks);
                } else if (delay > 0) {
                    task = Bukkit.getScheduler().runTaskLater(plugin, this::executeSafely, delayTicks);
                } else {
                    task = Bukkit.getScheduler().runTask(plugin, this::executeSafely);
                }
                return new PaperTask(task);
            }
        }
    }

    // ==================== TASK INTERFACE ====================

    public interface Task {
        void cancel();
        boolean isCancelled();
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

    // ==================== API DIRECTA (HOT PATH - MÁXIMO RENDIMIENTO) ====================

    /**
     * ⚡ Ejecución inmediata en hilo global. SIN validaciones. MÁXIMA VELOCIDAD.
     * <p>
     * ⚠️ Uso exclusivo en rutas críticas donde garantizas parámetros válidos.
     * No verifica nulls, chunks cargados, o estado de entidades.
     *
     * @param runnable Código a ejecutar
     */
    public static void runDirect(Runnable runnable) {
        if (isFoliaCache) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    /**
     * ⚡ Ejecución en región de chunk. Omite checks de chunk/world.
     * <p>
     * ⚠️ Garantiza que location.getWorld() != null y el chunk está cargado.
     *
     * @param location Ubicación para routing en Folia
     * @param runnable Código a ejecutar
     */
    public static void runDirectAt(Location location, Runnable runnable) {
        if (isFoliaCache) {
            Bukkit.getRegionScheduler().execute(plugin, location, runnable);
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    /**
     * ⚡ Ejecución en scheduler de entidad. Sin validación de isValid().
     * <p>
     * ⚠️ Garantiza que entity != null y entity.isValid() == true.
     *
     * @param entity Entidad para routing en Folia
     * @param runnable Código a ejecutar
     */
    public static void runDirectWith(Entity entity, Runnable runnable) {
        if (isFoliaCache && entity != null) {
            entity.getScheduler().execute(plugin, runnable, null, 1L);
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    /**
     * ⚡ Tarea retrasada directa (en ticks). Sin conversión de TimeUnit.
     * <p>
     * ⚠️ Usa ticks directamente para evitar overhead de conversión.
     *
     * @param runnable Código a ejecutar
     * @param delayTicks Retraso en ticks de Minecraft
     */
    public static void runDirectLater(Runnable runnable, long delayTicks) {
        if (isFoliaCache) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> runnable.run(), delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
        }
    }

    /**
     * ⚡ Tarea periódica directa (en ticks). Sin builder ni allocations extra.
     * <p>
     * ⚠️ Ideal para bucles de tick, procesamiento masivo de bloques/entidades.
     *
     * @param runnable Código a ejecutar periódicamente
     * @param periodTicks Intervalo entre ejecuciones en ticks
     */
    public static void runDirectTimer(Runnable runnable, long periodTicks) {
        if (isFoliaCache) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> runnable.run(), 1L, periodTicks);
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, runnable, 1L, periodTicks);
        }
    }

    /**
     * ⚡ Versión de alto rendimiento de runTimerUntil: sin AtomicReference.
     * <p>
     * ⚠️ La tarea debe cancelar manualmente o usar el Task devuelto.
     *
     * @param task Tarea a ejecutar
     * @param periodTicks Intervalo en ticks
     * @param stopCondition Condición para detener
     * @return Task para cancelación manual
     */
    public static Task runTimerUntilFast(Runnable task, long periodTicks, BooleanSupplier stopCondition) {
        Runnable wrapped = () -> {
            if (stopCondition.getAsBoolean()) return;
            task.run();
        };

        if (isFoliaCache) {
            return new FoliaTask(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> wrapped.run(), 1L, periodTicks));
        } else {
            return new PaperTask(Bukkit.getScheduler().runTaskTimer(plugin, wrapped, 1L, periodTicks));
        }
    }

    // ==================== UTILIDADES ESTÁNDAR ====================

    public static void runNextTick(Runnable runnable) {
        task(runnable).afterTicks(1).run();
    }

    public static void runLater(Runnable runnable, long delay, TimeUnit unit) {
        task(runnable).after(delay, unit).run();
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        task(() -> {
            try {
                runnable.run();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }).async().run();
        return future;
    }

    public static <T> CompletableFuture<T> callAsync(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        task(() -> {
            try {
                future.complete(supplier.get());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }).async().run();
        return future;
    }

    public static void runSync(Runnable runnable) {
        if (isFoliaCache) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    public static Task runSyncLater(Runnable runnable, long delayTicks) {
        return task(runnable).afterTicks(delayTicks).run();
    }

    public static <T> CompletableFuture<T> callSync(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        runSync(() -> {
            try {
                future.complete(supplier.get());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public static void forAllPlayers(Consumer<Player> action) {
        runSync(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player != null) {
                    try {
                        action.accept(player);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Error procesando jugador " + player.getName() + ": " + e.getMessage());
                    }
                }
            }
        });
    }

    public static void forAllLoadedChunks(Consumer<Chunk> action) {
        task(() -> {
            for (World world : Bukkit.getWorlds()) {
                if (world != null) {
                    for (Chunk chunk : world.getLoadedChunks()) {
                        if (chunk != null) {
                            try {
                                action.accept(chunk);
                            } catch (Exception e) {
                                plugin.getLogger().warning("Error procesando chunk: " + e.getMessage());
                            }
                        }
                    }
                }
            }
        }).async().run();
    }

    public static void executeIfLoaded(Location location, Runnable task) {
        World world = location.getWorld();
        if (world != null && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            task(task).at(location).run();
        }
    }

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

    public static void runWithRetry(Runnable task, int maxAttempts, long delayBetweenAttempts, TimeUnit unit) {
        AtomicReference<Runnable> retryRef = new AtomicReference<>();

        Runnable attempt = new Runnable() {
            int attempts = 0;
            @Override
            public void run() {
                try {
                    task.run();
                } catch (Exception e) {
                    attempts++;
                    if (attempts < maxAttempts) {
                        runLater(this, delayBetweenAttempts, unit);
                    } else {
                        plugin.getLogger().severe("Task failed after " + maxAttempts + " attempts: " + e.getMessage());
                    }
                }
            }
        };

        retryRef.set(attempt);
        attempt.run();
    }

    public static RegionScheduler getRegionScheduler() {
        if (isFoliaCache) {
            return Bukkit.getRegionScheduler();
        }
        throw new UnsupportedOperationException("RegionScheduler is only available on Folia");
    }

    public static EntityScheduler getEntityScheduler(Entity entity) {
        if (isFoliaCache && entity != null) {
            return entity.getScheduler();
        }
        throw new UnsupportedOperationException("EntityScheduler is only available on Folia");
    }

    public static long ticksToMs(long ticks) {
        return ticks * 50L;
    }

    public static long msToTicks(long ms) {
        return ms / 50L;
    }
}