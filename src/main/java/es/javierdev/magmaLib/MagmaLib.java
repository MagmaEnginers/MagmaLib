package es.javierdev.magmaLib;

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
import java.util.*;

public class MagmaLib {

    private static Plugin plugin;
    private static boolean isFolia;

    public static void init(Plugin plugin) {
        MagmaLib.plugin = plugin;
        isFolia = Bukkit.getVersion().contains("Folia");
    }

    public static boolean isFolia() {
        return isFolia;
    }

    // Task Builder
    public static TaskBuilder task(Runnable runnable) {
        return new TaskBuilder(runnable);
    }

    public static class TaskBuilder {
        private Runnable runnable;
        private Location location;
        private Entity entity;
        private long delay = -1;
        private long period = -1;
        private TimeUnit timeUnit = TimeUnit.MILLISECONDS;
        private boolean async = false;
        private boolean cancelIfUnloaded = true;
        private BooleanSupplier cancelCondition;
        private Consumer<Exception> exceptionHandler;

        public TaskBuilder(Runnable runnable) {
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
            this.delay = delay;
            this.timeUnit = unit;
            return this;
        }

        public TaskBuilder afterTicks(long ticks) {
            return after(ticks * 50, TimeUnit.MILLISECONDS);
        }

        public TaskBuilder every(long period, TimeUnit unit) {
            this.period = period;
            this.timeUnit = unit;
            return this;
        }

        public TaskBuilder everyTicks(long ticks) {
            return every(ticks * 50, TimeUnit.MILLISECONDS);
        }

        public TaskBuilder async() {
            this.async = true;
            return this;
        }

        public TaskBuilder cancelIfUnloaded(boolean cancel) {
            this.cancelIfUnloaded = cancel;
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
                    plugin.getLogger().severe("Error en tarea: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        private Task runAsync() {
            if (isFolia) {
                AsyncScheduler scheduler = Bukkit.getAsyncScheduler();
                if (period > 0) {
                    ScheduledTask task = scheduler.runAtFixedRate(plugin,
                            t -> executeSafely(), delay, period, timeUnit);
                    return new FoliaTask(task);
                } else if (delay > 0) {
                    ScheduledTask task = scheduler.runDelayed(plugin,
                            t -> executeSafely(), delay, timeUnit);
                    return new FoliaTask(task);
                } else {
                    scheduler.runNow(plugin, t -> executeSafely());
                    return new EmptyTask();
                }
            } else {
                BukkitTask task;
                long delayTicks = timeUnit.toMillis(delay) / 50;
                if (period > 0) {
                    long periodTicks = timeUnit.toMillis(period) / 50;
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
            if (isFolia) {
                RegionScheduler scheduler = Bukkit.getRegionScheduler();

                if (cancelIfUnloaded && !isChunkLoaded(location)) {
                    return new EmptyTask();
                }

                if (period > 0) {
                    long periodTicks = timeUnit.toMillis(period) / 50;
                    ScheduledTask task = scheduler.runAtFixedRate(plugin, location,
                            t -> executeSafely(), delay, periodTicks);
                    return new FoliaTask(task);
                } else if (delay > 0) {
                    long delayTicks = timeUnit.toMillis(delay) / 50;
                    ScheduledTask task = scheduler.runDelayed(plugin, location,
                            t -> executeSafely(), delayTicks);
                    return new FoliaTask(task);
                } else {
                    scheduler.run(plugin, location, t -> executeSafely());
                    return new EmptyTask();
                }
            } else {
                return runGlobal();
            }
        }

        private boolean isChunkLoaded(Location loc) {
            World world = loc.getWorld();
            int chunkX = loc.getBlockX() >> 4;
            int chunkZ = loc.getBlockZ() >> 4;
            return world != null && world.isChunkLoaded(chunkX, chunkZ);
        }

        private Task runWithEntity() {
            if (isFolia) {
                EntityScheduler scheduler = entity.getScheduler();

                if (cancelIfUnloaded && !entity.isValid()) {
                    return new EmptyTask();
                }

                if (period > 0) {
                    long periodTicks = timeUnit.toMillis(period) / 50;
                    ScheduledTask task = scheduler.runAtFixedRate(plugin,
                            t -> executeSafely(), null, delay, periodTicks);
                    return new FoliaTask(task);
                } else if (delay > 0) {
                    long delayTicks = timeUnit.toMillis(delay) / 50;
                    ScheduledTask task = scheduler.runDelayed(plugin,
                            t -> executeSafely(), null, delayTicks);
                    return new FoliaTask(task);
                } else {
                    scheduler.run(plugin, t -> executeSafely(), null);
                    return new EmptyTask();
                }
            } else {
                return runGlobal();
            }
        }

        private Task runGlobal() {
            if (isFolia) {
                GlobalRegionScheduler scheduler = Bukkit.getGlobalRegionScheduler();
                if (period > 0) {
                    long periodTicks = timeUnit.toMillis(period) / 50;
                    ScheduledTask task = scheduler.runAtFixedRate(plugin,
                            t -> executeSafely(), delay, periodTicks);
                    return new FoliaTask(task);
                } else if (delay > 0) {
                    long delayTicks = timeUnit.toMillis(delay) / 50;
                    ScheduledTask task = scheduler.runDelayed(plugin,
                            t -> executeSafely(), delayTicks);
                    return new FoliaTask(task);
                } else {
                    scheduler.run(plugin, t -> executeSafely());
                    return new EmptyTask();
                }
            } else {
                BukkitTask task;
                long delayTicks = timeUnit.toMillis(delay) / 50;
                if (period > 0) {
                    long periodTicks = timeUnit.toMillis(period) / 50;
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

    // Task Handling
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
        @Override public void cancel() { task.cancel(); }
        @Override public boolean isCancelled() { return task.isCancelled(); }
        @Override public boolean isRunning() { return !task.isCancelled(); }
    }

    private static class FoliaTask implements Task {
        private final ScheduledTask task;
        public FoliaTask(ScheduledTask task) { this.task = task; }
        @Override public void cancel() { task.cancel(); }
        @Override public boolean isCancelled() { return task.isCancelled(); }
        @Override public boolean isRunning() { return !task.isCancelled(); }
    }

    // Utilidades
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

    public static void executeIfLoaded(Location location, Runnable task) {
        if (location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            MagmaLib.task(task).at(location).run();
        }
    }


    public static void runSync(Runnable runnable) {
        if (isFolia) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    public static Task runSyncLater(Runnable runnable, long delayTicks) {
        return task(runnable).afterTicks(delayTicks).run();
    }

    // 3. Ejecutar tarea con retorno de valor sincrónico
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
        task(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                try {
                    action.accept(player);
                } catch (Exception e) {
                    plugin.getLogger().warning("Error procesando jugador " + player.getName() + ": " + e.getMessage());
                }
            }
        }).run();
    }

    public static void forAllLoadedChunks(Consumer<Chunk> action) {
        task(() -> {
            for (World world : Bukkit.getWorlds()) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    try {
                        action.accept(chunk);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Error procesando chunk: " + e.getMessage());
                    }
                }
            }
        }).async().run(); // Normalmente es una operación pesada
    }


    public static Task runTimerUntil(Runnable task, long periodTicks, BooleanSupplier stopCondition) {
        AtomicReference<Task> taskRef = new AtomicReference<>();

        Task actualTask = MagmaLib.task(() -> {
            if (stopCondition.getAsBoolean()) {
                taskRef.get().cancel();
            } else {
                task.run();
            }
        }).everyTicks(periodTicks).run();

        taskRef.set(actualTask);
        return actualTask;
    }


    public static void runWithRetry(Runnable task, int maxAttempts, long delayBetweenAttempts, TimeUnit unit) {
        new Thread(() -> {
            int attempts = 0;
            while (attempts < maxAttempts) {
                try {
                    task.run();
                    return;
                } catch (Exception e) {
                    attempts++;
                    try {
                        unit.sleep(delayBetweenAttempts);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }).start();
    }

    public static Object getRegionScheduler(Location location) {
        if (isFolia) {
            return Bukkit.getRegionScheduler();
        }
        return Bukkit.getScheduler();
    }
}