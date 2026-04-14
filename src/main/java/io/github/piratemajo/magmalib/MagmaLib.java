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
 * io.github.piratemajo.magmalib.MagmaLib - API moderna para scheduling compatible con Paper y Folia
 * <p>
 * Características principales:
 * <ul>
 *   <li>Task Builder fluent para configuración intuitiva</li>
 *   <li>Detección automática de Folia/Paper</li>
 *   <li>Manejo seguro de chunks y entidades</li>
 *   <li>Utilidades para operaciones comunes</li>
 * </ul>
 *
 * @author Piratemajo
 * @version 1.1
 */
public class MagmaLib {

    private static Plugin plugin;
    private static Boolean isFoliaCache = null;

    /**
     * Inicializa io.github.piratemajo.magmalib.MagmaLib con tu plugin principal.
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

    /**
     * Crea un nuevo TaskBuilder para configurar una tarea.
     *
     * @param runnable El código a ejecutar
     * @return TaskBuilder para configuración fluent
     */
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
        private BooleanSupplier cancelCondition;
        private Consumer<Exception> exceptionHandler;

        private TaskBuilder(Runnable runnable) {
            this.runnable = runnable;
        }

        /**
         * Ejecuta la tarea en la región del chunk de esta ubicación (Folia)
         * o en el hilo principal (Paper).
         */
        public TaskBuilder at(Location location) {
            this.location = location;
            return this;
        }

        /**
         * Vincula la tarea a una entidad para ejecución en su scheduler (Folia).
         */
        public TaskBuilder with(Entity entity) {
            this.entity = entity;
            return this;
        }

        /**
         * Retrasa la ejecución.
         *
         * @param delay Tiempo de espera
         * @param unit Unidad temporal
         */
        public TaskBuilder after(long delay, TimeUnit unit) {
            this.delay = unit.toMillis(delay);
            return this;
        }

        /**
         * Retraso en ticks de Minecraft (1 tick = 50ms).
         *
         * @param ticks Ticks de espera
         */
        public TaskBuilder afterTicks(long ticks) {
            this.delay = ticks * 50;
            return this;
        }

        /**
         * Configura la tarea como repetitiva.
         *
         * @param period Intervalo entre ejecuciones
         * @param unit Unidad temporal
         */
        public TaskBuilder every(long period, TimeUnit unit) {
            this.period = unit.toMillis(period);
            return this;
        }

        /**
         * Intervalo repetitivo en ticks de Minecraft.
         *
         * @param ticks Ticks entre ejecuciones
         */
        public TaskBuilder everyTicks(long ticks) {
            this.period = ticks * 50;
            return this;
        }

        /**
         * Ejecuta la tarea en hilo asíncrono.
         */
        public TaskBuilder async() {
            this.async = true;
            return this;
        }

        /**
         * Cancela la tarea si el chunk no está cargado (solo para .at()).
         *
         * @param cancel true para cancelar si no está cargado
         */
        public TaskBuilder cancelIfUnloaded(boolean cancel) {
            this.cancelIfUnloaded = cancel;
            return this;
        }

        /**
         * Cancela la tarea si se cumple la condición.
         *
         * @param condition Condición de cancelación
         */
        public TaskBuilder cancelIf(BooleanSupplier condition) {
            this.cancelCondition = condition;
            return this;
        }

        /**
         * Manejador personalizado para excepciones.
         *
         * @param handler Consumer que procesa la excepción
         */
        public TaskBuilder handleException(Consumer<Exception> handler) {
            this.exceptionHandler = handler;
            return this;
        }

        /**
         * Ejecuta la tarea configurada.
         *
         * @return Objeto Task para manejo posterior
         */
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
                    plugin.getLogger().severe("Error en tarea io.github.piratemajo.magmalib.MagmaLib: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        private Task runAsync() {
            if (isFolia()) {
                AsyncScheduler scheduler = Bukkit.getAsyncScheduler();
                if (period > 0) {
                    ScheduledTask task = scheduler.runAtFixedRate(plugin,
                            t -> executeSafely(), delay, period, TimeUnit.MILLISECONDS);
                    return new FoliaTask(task);
                } else if (delay > 0) {
                    ScheduledTask task = scheduler.runDelayed(plugin,
                            t -> executeSafely(), delay, TimeUnit.MILLISECONDS);
                    return new FoliaTask(task);
                } else {
                    scheduler.runNow(plugin, t -> executeSafely());
                    return new EmptyTask();
                }
            } else {
                BukkitTask task;
                long delayTicks = delay / 50;
                if (period > 0) {
                    long periodTicks = period / 50;
                    task = Bukkit.getScheduler().runTaskTimerAsynchronously(
                            plugin, this::executeSafely, delayTicks, periodTicks);
                } else if (delay > 0) {
                    task = Bukkit.getScheduler().runTaskLaterAsynchronously(
                            plugin, this::executeSafely, delayTicks);
                } else {
                    task = Bukkit.getScheduler().runTaskAsynchronously(plugin, this::executeSafely);
                }
                return new PaperTask(task);
            }
        }

        private Task runAtLocation() {
            if (isFolia()) {
                RegionScheduler scheduler = Bukkit.getRegionScheduler();
                World world = location.getWorld();

                if (world == null) {
                    plugin.getLogger().warning("Location world is null, task cancelled");
                    return new EmptyTask();
                }

                if (cancelIfUnloaded && !world.isChunkLoaded(
                        location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                    return new EmptyTask();
                }

                long delayTicks = delay / 50;
                long periodTicks = period / 50;

                if (period > 0) {
                    ScheduledTask task = scheduler.runAtFixedRate(plugin, location,
                            t -> executeSafely(), delayTicks, periodTicks);
                    return new FoliaTask(task);
                } else if (delay > 0) {
                    ScheduledTask task = scheduler.runDelayed(plugin, location,
                            t -> executeSafely(), delayTicks);
                    return new FoliaTask(task);
                } else {
                    scheduler.run(plugin, location, t -> executeSafely());
                    return new EmptyTask();
                }
            } else {
                // En Paper, at() equivale a runGlobal()
                return runGlobal();
            }
        }

        private Task runWithEntity() {
            if (isFolia() && entity != null) {
                EntityScheduler scheduler = entity.getScheduler();

                if (cancelIfUnloaded && !entity.isValid()) {
                    return new EmptyTask();
                }

                long delayTicks = delay / 50;
                long periodTicks = period / 50;

                if (period > 0) {
                    ScheduledTask task = scheduler.runAtFixedRate(plugin,
                            t -> executeSafely(), null, delayTicks, periodTicks);
                    return new FoliaTask(task);
                } else if (delay > 0) {
                    ScheduledTask task = scheduler.runDelayed(plugin,
                            t -> executeSafely(), null, delayTicks);
                    return new FoliaTask(task);
                } else {
                    scheduler.run(plugin, t -> executeSafely(), null);
                    return new EmptyTask();
                }
            } else {
                // En Paper o si entity es null, fallback a global
                return runGlobal();
            }
        }

        private Task runGlobal() {
            if (isFolia()) {
                GlobalRegionScheduler scheduler = Bukkit.getGlobalRegionScheduler();
                long delayTicks = delay / 50;
                long periodTicks = period / 50;

                if (period > 0) {
                    ScheduledTask task = scheduler.runAtFixedRate(plugin,
                            t -> executeSafely(), delayTicks, periodTicks);
                    return new FoliaTask(task);
                } else if (delay > 0) {
                    ScheduledTask task = scheduler.runDelayed(plugin,
                            t -> executeSafely(), delayTicks);
                    return new FoliaTask(task);
                } else {
                    scheduler.run(plugin, t -> executeSafely());
                    return new EmptyTask();
                }
            } else {
                BukkitTask task;
                long delayTicks = delay / 50;
                long periodTicks = period / 50;

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

    // ==================== UTILIDADES ====================

    /**
     * Ejecuta en el próximo tick.
     */
    public static void runNextTick(Runnable runnable) {
        task(runnable).afterTicks(1).run();
    }

    /**
     * Ejecuta con retraso especificado.
     */
    public static void runLater(Runnable runnable, long delay, TimeUnit unit) {
        task(runnable).after(delay, unit).run();
    }

    /**
     * Ejecuta asíncronamente con CompletableFuture.
     */
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

    /**
     * Ejecuta supplier asíncrono con retorno de valor.
     */
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

    /**
     * Ejecuta en el hilo principal (global en Folia).
     */
    public static void runSync(Runnable runnable) {
        if (isFolia()) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    /**
     * Ejecuta en el hilo principal con retraso en ticks.
     */
    public static Task runSyncLater(Runnable runnable, long delayTicks) {
        return task(runnable).afterTicks(delayTicks).run();
    }

    /**
     * Ejecuta supplier en el hilo principal con retorno de valor.
     */
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

    /**
     * Ejecuta acción para cada jugador en línea con manejo de errores.
     */
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

    /**
     * Procesa todos los chunks cargados (operación pesada, ejecuta asíncrono).
     */
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

    /**
     * Ejecuta tarea solo si el chunk está cargado.
     */
    public static void executeIfLoaded(Location location, Runnable task) {
        World world = location.getWorld();
        if (world != null && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            task(task).at(location).run();
        }
    }

    /**
     * Ejecuta tarea periódicamente hasta que se cumpla la condición de parada.
     *
     * @param task Tarea a ejecutar
     * @param periodTicks Intervalo en ticks
     * @param stopCondition Condición para detener
     * @return Task para cancelación manual
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
     * Ejecuta tarea con reintentos usando scheduler (NO threads manuales).
     *
     * @param task Tarea a ejecutar
     * @param maxAttempts Intentos máximos
     * @param delayBetweenAttempts Retraso entre intentos
     * @param unit Unidad temporal
     */
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

    /**
     * @return RegionScheduler en Folia, o lanza excepción en Paper
     */
    public static RegionScheduler getRegionScheduler() {
        if (isFolia()) {
            return Bukkit.getRegionScheduler();
        }
        throw new UnsupportedOperationException("RegionScheduler is only available on Folia");
    }

    /**
     * @return EntityScheduler para la entidad en Folia, o lanza excepción en Paper
     */
    public static EntityScheduler getEntityScheduler(Entity entity) {
        if (isFolia() && entity != null) {
            return entity.getScheduler();
        }
        throw new UnsupportedOperationException("EntityScheduler is only available on Folia");
    }

    /**
     * Convierte ticks a milisegundos.
     */
    public static long ticksToMs(long ticks) {
        return ticks * 50L;
    }

    /**
     * Convierte milisegundos a ticks.
     */
    public static long msToTicks(long ms) {
        return ms / 50L;
    }
}