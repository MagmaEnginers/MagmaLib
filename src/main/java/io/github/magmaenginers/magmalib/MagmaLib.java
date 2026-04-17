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
 * MagmaLib - API moderna para scheduling compatible con Paper y Folia
 * <p>
 * <b>Características principales:</b>
 * <ul>
 *   <li>Task Builder fluent con generics type-safe para composición funcional</li>
 *   <li>API directa de alto rendimiento para hot paths (zero-allocation)</li>
 *   <li>Detección automática de Folia/Paper con caché de reflexión</li>
 *   <li>Manejo seguro de chunks y entidades con opción {@code unsafe} para máximo rendimiento</li>
 *   <li>Composición estilo CompletableFuture: {@code thenApply}, {@code thenAccept}, {@code exceptionally}</li>
 *   <li>Propagación de contexto para debugging: stack trace de creación, plugin, ubicación</li>
 *   <li>Utilidades avanzadas: {@code runTimerUntil}, {@code runWithRetry}, {@code forAllPlayers}</li>
 * </ul>
 *
 * <p><b>Ejemplo de uso con composición type-safe:</b>
 * <pre>{@code
 * MagmaLib.<Integer>task(() -> calculateScore(player))
 *     .at(player.getLocation())
 *     .afterTicks(10)
 *     .thenApply(score -> score > 100 ? "¡Excelente!" : "Sigue intentando")
 *     .thenAccept(message -> player.sendMessage(message))
 *     .exceptionally(e -> {
 *         plugin.getLogger().warning("Error calculando score: " + safeMessage(e));
 *         return "Error";
 *     })
 *     .run();
 * }</pre>
 *
 * @author Piratemajo
 * @version 2.0.1
 * @see <a href="https://github.com/MagmaEnginers/MagmaLib">GitHub Repository</a>
 */
public class MagmaLib {

    private static Plugin plugin;
    private static Boolean isFoliaCache = null;

    /**
     * Inicializa MagmaLib con tu plugin principal.
     * <p>
     * Este método debe invocarse en {@link JavaPlugin#onEnable()} antes de usar
     * cualquier otra funcionalidad de la librería. Detecta automáticamente si el
     * servidor ejecuta Folia mediante reflexión y cachea el resultado.
     *
     * @param plugin la instancia principal del plugin
     * @throws IllegalStateException si plugin es null
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
     * Determina si el servidor actual ejecuta Folia.
     * <p>
     * El resultado se cachea tras la primera llamada para evitar reflexión repetida.
     *
     * @return {@code true} si es Folia, {@code false} si es Paper/Spigot
     * @throws IllegalStateException si no se ha llamado {@link #init(Plugin)}
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
     * Obtiene la instancia del plugin inicializado.
     *
     * @return el {@link Plugin} registrado, o {@code null} si no inicializado
     */
    public static Plugin getPlugin() {
        return plugin;
    }

    // ==================== UTILITIES ====================

    /**
     * Obtiene un mensaje de error null-safe para logging.
     * <p>
     * Si {@code e.getMessage()} es null, retorna el nombre de la excepción.
     *
     * @param e la excepción a procesar
     * @return mensaje descriptivo nunca null
     */
    public static String safeMessage(Throwable e) {
        if (e == null) return "Unknown error";
        String msg = e.getMessage();
        return (msg != null && !msg.isEmpty()) ? msg : e.getClass().getSimpleName();
    }

    // ==================== TASK BUILDER - RUNNABLE (Backward Compatible) ====================

    /**
     * Crea un nuevo {@link TaskBuilder} para tareas sin retorno de valor.
     * <p>
     * Método compatible con versiones anteriores. Para composición type-safe,
     * usa {@link #task(Supplier)}.
     *
     * @param runnable el código a ejecutar
     * @return un nuevo {@link TaskBuilder} para configuración adicional
     */
    public static TaskBuilder task(Runnable runnable) {
        return new TaskBuilder(runnable);
    }

    // ==================== TASK BUILDER - GENERIC (Type-Safe Composition) ====================

    /**
     * Crea un nuevo {@link TaskBuilder} genérico para tareas con retorno de valor.
     * <p>
     * Permite composición funcional tipo {@link CompletableFuture} con métodos:
     * {@link TaskBuilder#thenApply(Function)}, {@link TaskBuilder#thenAccept(Consumer)},
     * y {@link TaskBuilder#exceptionally(Function)}.
     *
     * @param supplier el proveedor del valor a calcular
     * @param <T> el tipo del valor retornado
     * @return un nuevo {@link TaskBuilder<T>} para composición type-safe
     * @see TaskBuilder#thenApply(Function)
     * @see TaskBuilder#thenAccept(Consumer)
     */
    public static <T> TaskBuilder<T> task(Supplier<T> supplier) {
        return new TaskBuilder<>(supplier);
    }

    /**
     * Builder fluent para configurar tareas con opciones avanzadas.
     * <p>
     * Soporta dos modos:
     * <ul>
     *   <li>{@code TaskBuilder} (Runnable): tareas sin retorno, compatible con v1.x</li>
     *   <li>{@code TaskBuilder<T>} (Supplier<T>): tareas con composición type-safe</li>
     * </ul>
     *
     * @param <T> tipo del valor retornado (Void para Runnable)
     */
    public static class TaskBuilder<T> {
        // Configuración compartida
        private Location location;
        private Entity entity;
        private long delay = -1;
        private long period = -1;
        private boolean async = false;
        private boolean cancelIfUnloaded = true;
        private boolean unsafe = false;
        private BooleanSupplier cancelCondition;
        private Consumer<Exception> exceptionHandler;
        private String taskName; // Para debugging/contexto

        // Modo Runnable (backward compatible)
        private final Runnable runnable;
        private Supplier<T> supplier;

        // Composición funcional (solo para Supplier mode)
        private Function<T, ?> thenApply;
        private Consumer<T> thenAccept;
        private Function<Throwable, T> exceptionally;

        // ============ CONSTRUCTORES ============

        /** Constructor para modo Runnable (backward compatible) */
        private TaskBuilder(Runnable runnable) {
            this.runnable = runnable;
            this.supplier = null;
        }

        /** Constructor para modo Supplier (type-safe composition) */
        private TaskBuilder(Supplier<T> supplier) {
            this.supplier = supplier;
            this.runnable = null;
        }

        // ============ CONFIGURACIÓN (común a ambos modos) ============

        /**
         * Asocia la tarea a una ubicación para routing regional en Folia.
         *
         * @param location ubicación para routing
         * @return este builder para encadenamiento
         */
        public TaskBuilder<T> at(Location location) {
            this.location = location;
            return this;
        }

        /**
         * Asocia la tarea a una entidad para scheduling vinculado en Folia.
         *
         * @param entity entidad para vincular
         * @return este builder para encadenamiento
         */
        public TaskBuilder<T> with(Entity entity) {
            this.entity = entity;
            return this;
        }

        /**
         * Establece retraso inicial antes de la primera ejecución.
         *
         * @param delay cantidad de retraso
         * @param unit unidad de tiempo
         * @return este builder para encadenamiento
         */
        public TaskBuilder<T> after(long delay, TimeUnit unit) {
            this.delay = unit.toMillis(delay);
            return this;
        }

        /**
         * Establece retraso inicial en ticks de Minecraft (1 tick = 50ms).
         *
         * @param ticks retraso en ticks
         * @return este builder para encadenamiento
         */
        public TaskBuilder<T> afterTicks(long ticks) {
            this.delay = ticks * 50L;
            return this;
        }

        /**
         * Establece intervalo periódico entre ejecuciones.
         *
         * @param period duración del intervalo
         * @param unit unidad de tiempo
         * @return este builder para encadenamiento
         */
        public TaskBuilder<T> every(long period, TimeUnit unit) {
            this.period = unit.toMillis(period);
            return this;
        }

        /**
         * Establece intervalo periódico en ticks de Minecraft.
         *
         * @param ticks intervalo en ticks
         * @return este builder para encadenamiento
         */
        public TaskBuilder<T> everyTicks(long ticks) {
            this.period = ticks * 50L;
            return this;
        }

        /**
         * Configura la tarea para ejecutarse en hilo asíncrono.
         * <p>
         * ⚠️ Las tareas asíncronas no pueden interactuar directamente con la API de Bukkit.
         *
         * @return este builder para encadenamiento
         */
        public TaskBuilder<T> async() {
            this.async = true;
            return this;
        }

        /**
         * Configura si cancelar automáticamente si el chunk no está cargado.
         *
         * @param cancel {@code true} para cancelar si no cargado (por defecto)
         * @return este builder para encadenamiento
         */
        public TaskBuilder<T> cancelIfUnloaded(boolean cancel) {
            this.cancelIfUnloaded = cancel;
            return this;
        }

        /**
         * ⚠️ <b>MODO ALTO RENDIMIENTO:</b> Desactiva validaciones de seguridad.
         * <p>
         * Úsalo SOLO en hot paths donde garantices manualmente:
         * <ul>
         *   <li>{@code location.getWorld() != null}</li>
         *   <li>Chunk cargado: {@code world.isChunkLoaded(x, z)}</li>
         *   <li>{@code entity.isValid() == true}</li>
         * </ul>
         *
         * @return este builder para encadenamiento
         */
        public TaskBuilder<T> unsafe() {
            this.unsafe = true;
            return this;
        }

        /**
         * Establece condición dinámica para cancelar antes de cada ejecución.
         *
         * @param condition proveedor que retorna {@code true} para cancelar
         * @return este builder para encadenamiento
         */
        public TaskBuilder<T> cancelIf(BooleanSupplier condition) {
            this.cancelCondition = condition;
            return this;
        }

        /**
         * Establece manejador personalizado para excepciones.
         *
         * @param handler consumidor para procesar la excepción
         * @return este builder para encadenamiento
         */
        public TaskBuilder<T> handleException(Consumer<Exception> handler) {
            this.exceptionHandler = handler;
            return this;
        }

        /**
         * Asigna un nombre descriptivo para debugging y métricas.
         *
         * @param name nombre identificativo de la tarea
         * @return este builder para encadenamiento
         */
        public TaskBuilder<T> named(String name) {
            this.taskName = name;
            return this;
        }

        // ============ COMPOSICIÓN FUNCIONAL (solo Supplier mode) ============

        /**
         * Transforma el resultado de la tarea aplicando una función.
         * <p>
         * Equivalente a {@link CompletableFuture#thenApply(Function)}.
         *
         * @param mapper función para transformar el resultado
         * @param <R> tipo del nuevo resultado
         * @return este builder con tipo actualizado para encadenamiento
         * @throws IllegalStateException si se usa con constructor Runnable
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
         * Consume el resultado de la tarea sin retornar valor.
         * <p>
         * Equivalente a {@link CompletableFuture#thenAccept(Consumer)}.
         *
         * @param action consumidor para procesar el resultado
         * @return este builder para encadenamiento
         * @throws IllegalStateException si se usa con constructor Runnable
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
         * Maneja excepciones proporcionando un valor de recuperación.
         * <p>
         * Equivalente a {@link CompletableFuture#exceptionally(Function)}.
         *
         * @param fallback función que retorna valor de recuperación ante error
         * @return este builder para encadenamiento
         * @throws IllegalStateException si se usa con constructor Runnable
         */
        public TaskBuilder<T> exceptionally(Function<Throwable, T> fallback) {
            if (supplier == null) {
                throw new IllegalStateException("exceptionally() requires task(Supplier<T>), not task(Runnable)");
            }
            this.exceptionally = fallback;
            return this;
        }

        // ============ EJECUCIÓN ============

        /**
         * Programa y ejecuta la tarea con la configuración especificada.
         *
         * @return un {@link Task} para controlar la tarea (cancelar, verificar estado)
         */
        public Task run() {
            Runnable executable = createExecutable();
            TaskBuilder<Void> voidBuilder = new TaskBuilder<>(executable);
            voidBuilder.location = this.location;
            voidBuilder.entity = this.entity;
            voidBuilder.delay = this.delay;
            voidBuilder.period = this.period;
            voidBuilder.async = this.async;
            voidBuilder.cancelIfUnloaded = this.cancelIfUnloaded;
            voidBuilder.unsafe = this.unsafe;
            voidBuilder.cancelCondition = this.cancelCondition;
            voidBuilder.exceptionHandler = this.exceptionHandler;
            voidBuilder.taskName = this.taskName;
            return voidBuilder.runInternal();
        }

        /**
         * Crea el Runnable ejecutable con composición y manejo de errores.
         */
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
                    } catch (Exception e) {
                        if (exceptionally != null) {
                            try {
                                exceptionally.apply(e);
                                return;
                            } catch (Exception fallbackError) {
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
                            plugin.getLogger().severe("Error en tarea MagmaLib" + context + ": " + safeMessage(e));
                            e.printStackTrace();
                        }
                    }
                };
            }
            return () -> {};
        }

        /**
         * Ejecuta un Runnable con manejo seguro de excepciones y condiciones.
         */
        private void executeSafely(Runnable action) {
            try {
                if (cancelCondition == null || !cancelCondition.getAsBoolean()) {
                    action.run();
                }
            } catch (Exception e) {
                if (exceptionHandler != null) {
                    exceptionHandler.accept(e);
                } else {
                    String context = taskName != null ? " ['" + taskName + "']" : "";
                    plugin.getLogger().severe("Error en tarea MagmaLib" + context + ": " + safeMessage(e));
                    e.printStackTrace();
                }
            }
        }

        // ============ FOLIA-SAFE TICK CONVERSION ====================

        /**
         * Convierte milisegundos a ticks asegurando compatibilidad con Folia.
         * <p>
         * Para tareas periódicas en Folia, garantiza delay mínimo de 1 tick
         * porque Folia rechaza delay <= 0 en runAtFixedRate.
         *
         * @param ms milisegundos a convertir
         * @param isPeriodic true si es tarea periódica (every/period > 0)
         * @param isDelay true si este valor es para delay (no period)
         * @return ticks seguros para Folia
         */
        private long toFoliaSafeTicks(long ms, boolean isPeriodic, boolean isDelay) {
            if (ms <= 0) {
                // Folia requiere delay >= 1 tick para tareas periódicas
                if (isFolia() && isPeriodic && isDelay) {
                    return 1;
                }
                return 0;
            }
            long ticks = ms / 50L;
            // Asegurar mínimo 1 tick para delays en tareas periódicas de Folia
            if (isFolia() && isPeriodic && isDelay && ticks <= 0) {
                return 1;
            }
            return Math.max(0, ticks);
        }

        // ============ ROUTING INTERNO (lógica original corregida) ============

        private Task runInternal() {
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

        private Task runAsync() {
            Runnable safeRunnable = () -> executeSafely(() -> {});

            if (isFolia()) {
                AsyncScheduler scheduler = Bukkit.getAsyncScheduler();
                if (period > 0) {
                    long safeDelay = toFoliaSafeTicks(delay > 0 ? delay : 0, true, true);
                    long safePeriod = toFoliaSafeTicks(period, true, false);
                    return new FoliaTask(scheduler.runAtFixedRate(plugin, t -> safeRunnable.run(), safeDelay, safePeriod, TimeUnit.MILLISECONDS));
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

        private Task runAtLocation() {
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
                Runnable safeRunnable = () -> executeSafely(() -> {});

                if (period > 0) {
                    // Folia requiere delay >= 1 para runAtFixedRate
                    long safeDelay = delay <= 0 ? 1 : delayTicks;
                    return new FoliaTask(scheduler.runAtFixedRate(plugin, location, t -> safeRunnable.run(), safeDelay, periodTicks));
                } else if (delay > 0) {
                    return new FoliaTask(scheduler.runDelayed(plugin, location, t -> safeRunnable.run(), delayTicks));
                } else {
                    scheduler.run(plugin, location, t -> safeRunnable.run());
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

                long delayTicks = toFoliaSafeTicks(delay > 0 ? delay : 0, period > 0, true);
                long periodTicks = toFoliaSafeTicks(period, period > 0, false);
                Runnable safeRunnable = () -> executeSafely(() -> {});

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
                return runGlobal();
            }
        }

        private Task runGlobal() {
            if (isFolia()) {
                GlobalRegionScheduler scheduler = Bukkit.getGlobalRegionScheduler();

                long delayTicks = toFoliaSafeTicks(delay > 0 ? delay : 0, period > 0, true);
                long periodTicks = toFoliaSafeTicks(period, period > 0, false);
                Runnable safeRunnable = () -> executeSafely(() -> {});

                if (period > 0) {
                    // Folia requiere delay >= 1 para runAtFixedRate
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
                Runnable safeRunnable = () -> executeSafely(() -> {});

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

        /**
         * Genera string de contexto para logging/debugging.
         */
        private String contextLog() {
            StringBuilder sb = new StringBuilder();
            if (taskName != null) sb.append(" task='").append(taskName).append("'");
            if (location != null) sb.append(" location=").append(location);
            if (entity != null) sb.append(" entity=").append(entity.getType());
            return sb.toString();
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

    public static void runDirect(Runnable runnable) {
        if (isFoliaCache) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    public static void runDirectAt(Location location, Runnable runnable) {
        if (isFoliaCache) {
            Bukkit.getRegionScheduler().execute(plugin, location, runnable);
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    public static void runDirectWith(Entity entity, Runnable runnable) {
        if (isFoliaCache && entity != null) {
            entity.getScheduler().execute(plugin, runnable, null, 0L);
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    public static void runDirectLater(Runnable runnable, long delayTicks) {
        if (isFoliaCache) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> runnable.run(), delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
        }
    }

    public static void runDirectTimer(Runnable runnable, long periodTicks) {
        if (isFoliaCache) {
            long safeDelay = isFoliaCache ? 1L : 0L;
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> runnable.run(), safeDelay, periodTicks);
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, runnable, 0L, periodTicks);
        }
    }

    public static Task runTimerUntilFast(Runnable task, long periodTicks, BooleanSupplier stopCondition) {
        Runnable wrapped = () -> {
            if (stopCondition.getAsBoolean()) return;
            task.run();
        };
        if (isFoliaCache) {
            long safeDelay = 1L;
            return new FoliaTask(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> wrapped.run(), safeDelay, periodTicks));
        } else {
            return new PaperTask(Bukkit.getScheduler().runTaskTimer(plugin, wrapped, 0L, periodTicks));
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
                        plugin.getLogger().warning("Error procesando jugador " + player.getName() + ": " + safeMessage(e));
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
                                plugin.getLogger().warning("Error procesando chunk: " + safeMessage(e));
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
                        plugin.getLogger().severe("Task failed after " + maxAttempts + " attempts: " + safeMessage(e));
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