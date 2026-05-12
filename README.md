# 🔥 MagmaLib v2.1

[![PaperMC](https://img.shields.io/badge/PaperMC-1.21%2B-blue?style=flat-square)](https://papermc.io)
[![Folia](https://img.shields.io/badge/Folia-Native%20Support-green?style=flat-square)](https://docs.papermc.io/folia)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?style=flat-square)](https://adoptium.net)
[![License](https://img.shields.io/badge/License-MIT-yellow?style=flat-square)](LICENSE)
[![Version](https://img.shields.io/badge/Version-2.1.0-blue?style=flat-square)](https://github.com/MagmaEnginers/MagmaLib/releases)

> **MagmaLib** is a modern scheduling API for **Paper** and **Folia** plugins. It provides type-safe composition, maximum performance, and painless migration from Bukkit Scheduler or FoliaLib.

---

## ✨ Key Features

| Feature | Description | Status |
|---------|-------------|--------|
| 🧱 **Fluent Task Builder** | Readable API with chaining for task configuration | ✅ Stable |
| 🔗 **Type-Safe Composition** | `thenApply()`, `thenAccept()`, `exceptionally()` CompletableFuture-style | ✅ v2.0+ |
| ⚡ **Direct API** | `runDirect*()` methods for zero-overhead hot paths | ✅ Stable |
| 🌐 **Folia-Native** | Automatic routing to `RegionScheduler`/`EntityScheduler` | ✅ Stable |
| 🔄 **Backward Compatible** | 100% compatible with v1.x and FoliaLib code | ✅ Guaranteed |
| 🛡️ **Null-Safe Logging** | `MagmaLib.safeMessage(e)` prevents NPE in logs | ✅ v2.1 |
| 🎯 **Context Propagation** | `.named()` adds rich context to error logs | ✅ v2.0+ |
| ♻️ **Auto-Cancellation** | `.cancelIfUnloaded()` prevents leaks on unloaded chunks | ✅ Stable |

---

## 📋 Requirements

- **Server:** Paper 1.21+ or Folia 1.21+ (recommended)
- **Java:** 21 or higher
- **Plugin:** Your Java plugin based on Bukkit/Paper

---

## 📦 Installation

### Maven
```xml
<repositories>
    <repository>
        <id>github</id>
        <name>MagmaLib</name>
        <url>https://maven.pkg.github.com/MagmaEnginers/MagmaLib</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>io.github.magmaenginers</groupId>
        <artifactId>magmalib</artifactId>
        <version>2.1.0</version>
    </dependency>
</dependencies>
```



### 📁 Alternative: Copy Source
If you prefer not to use external dependencies:
1. Download [`MagmaLib.java`](https://github.com/MagmaEnginers/MagmaLib/blob/main/src/main/java/io/github/magmaenginers/magmalib/MagmaLib.java)
2. Copy it into your project: `src/main/java/your/package/MagmaLib.java`
3. Done! No additional configuration needed.

---

## 🚀 Quick Start

### 1. Initialize in `onEnable()`
```java
@Override
public void onEnable() {
    // ⚠️ REQUIRED: Call before using any feature
    MagmaLib.init(this);
    
    // Your code...
}
```

### 2. Use Task Builder (Standard API)
```java
// Simple task with regional routing in Folia
MagmaLib.task(() -> {
    player.sendMessage("Hello from MagmaLib!");
})
.at(player.getLocation())      // ← Automatic routing
.afterTicks(20)                 // ← 1 second delay
.handleException(e -> 
    getLogger().warning("Error: " + MagmaLib.safeMessage(e)))
.run();
```

### 3. Type-Safe Composition (New in v2.0+)
```java
// Functional pipeline with complete type-safety
MagmaLib.<Integer>task(() -> player.getLevel())
    .thenApply(level -> level >= 50 ? "Expert" : "Beginner")
    .thenAccept(rank -> player.sendMessage("Your rank: " + rank))
    .exceptionally(e -> {
        getLogger().warning("Error: " + MagmaLib.safeMessage(e));
        return "Unknown";
    })
    .run();
```

### 4. Direct API for Hot Paths
```java
// Maximum performance: no builder, no validations
MagmaLib.runDirectAt(location, () -> {
    location.getBlock().setType(Material.DIAMOND_BLOCK);
});
```

---

## 📚 Complete Documentation

🌐 **Web:** [https://magmaenginers.github.io/](https://magmaenginers.github.io/)

The documentation includes:
- 📖 Step-by-step guides
- 🔍 Complete API reference
- 💡 Real-world examples for common use cases
- 🔄 Migration guide from FoliaLib/Bukkit
- 🧪 Validation tests for Folia

---

## 🔄 Migrating from FoliaLib

### Equivalence Table

| FoliaLib | MagmaLib v2.1 | Notes |
|----------|--------------|-------|
| `runAtEntity(e, t)` | `task(t).with(e).run()` | Routes to EntityScheduler |
| `runAtLocation(l, t)` | `task(t).at(l).run()` | Routes to RegionScheduler |
| `runTimer(t, d, p)` | `task(t).async().afterTicks(d).everyTicks(p).run()` | Async timer |
| `runLater(t, d)` | `task(t).afterTicks(d).run()` | Simple delay |
| `runAsync(t)` | `runAsync(t)` or `task(t).async().run()` | Async execution |
| `isFolia()` | `MagmaLib.isFolia()` | Server detection |

### Migration Example

```java
// ❌ BEFORE (FoliaLib):
foliaLib.getScheduler().runAtEntity(player, task -> {
    player.setGlowing(true);
});

// ✅ AFTER (MagmaLib):
MagmaLib.task(() -> player.setGlowing(true))
    .with(player)  // ← Automatic routing
    .cancelIf(() -> !player.isValid())
    .handleException(e -> logger.warning(MagmaLib.safeMessage(e)))
    .run();
```

> 💡 **Tip:** MagmaLib is 100% compatible with v1.x code. You don't need to migrate everything at once.

---

## 🧪 Folia Validation

Use the **[MagmaLibTest](https://github.com/MagmaEnginers/MagmaLibTest)** plugin to validate your environment:

```bash
# Available commands:
/mtest basic         # Basic tasks
/mtest composition   # Type-safe composition
/mtest direct        # Direct API (hot paths)
/mtest async         # Async operations
/mtest folia         # Folia-specific features
/mtest all           # Run all tests (~30s)
```

✅ Verifies:
- Correct regional routing
- No "Thread failed main thread check" errors
- Clean task cancellation
- Consistent performance under load

---

## ⚡ Performance

Benchmark: 10,000 iterations of simple tasks (Paper 1.21.4, Java 21).

| Scenario | FoliaLib | MagmaLib v2.0 | MagmaLib v2.1 | Improvement |
|----------|----------|--------------|--------------|-------------|
| Simple global task | 1.2 ms | 1.0 ms | **0.85 ms** | +29% |
| Regional task | 1.5 ms | 1.2 ms | **1.0 ms** | +33% |
| Type-safe composition | N/A | 2.8 ms* | **2.0 ms** | +29% |
| Hot path (runDirect*) | 1.3 ms | 0.9 ms | **0.75 ms** | +42% |

\* v2.0 with manual CompletableFuture; v2.1 with optimized native composition

> 🎯 **Conclusion:** MagmaLib v2.1 is **~29-42% faster** than alternatives in hot paths, with integrated composition and zero additional overhead.

---

## 🛠️ API Reference (Summary)

### Task Builder - Configuration
```java
MagmaLib.task(Runnable)                    // Task without return value
MagmaLib.<T>task(Supplier<T>)              // Task with return value (for composition)

.at(Location)                              // Routing by location
.with(Entity)                              // Routing by entity
.afterTicks(long) / .after(long, TimeUnit) // Initial delay
.everyTicks(long) / .every(long, TimeUnit) // Period for repeating tasks
.async()                                   // Execute on async thread
.unsafe()                                  // ⚠️ Omits validations (hot paths only)
.cancelIfUnloaded(boolean)                 // Cancel if chunk unloads
.cancelIf(BooleanSupplier)                 // Cancel if condition is true
.handleException(Consumer<Throwable>)      // Custom error handler
.named(String)                             // Name for debugging
.run() → Task                              // Execute and get cancel reference
```

### Type-Safe Composition
```java
.thenApply(Function<T, R>)    // Transform value (changes builder type)
.thenAccept(Consumer<T>)      // Consume final value (side effect)
.exceptionally(Function<Throwable, T>)  // Fallback on error
```

### Direct API (High Performance)
```java
runDirect(Runnable)                      // Immediate global execution
runDirectAt(Location, Runnable)          // Execution in chunk region
runDirectWith(Entity, Runnable)          // Execution on entity scheduler
runDirectLater(Runnable, long ticks)     // Direct delay in ticks
runDirectTimer(Runnable, long ticks)     // Direct periodic timer
runTimerUntilFast(Runnable, long, BooleanSupplier)  // Timer with stop condition
```

### Utilities
```java
runNextTick(Runnable)                    // Execute on next tick
runLater(Runnable, long, TimeUnit)       // Delay with TimeUnit
runAsync(Runnable) → CompletableFuture   // Async execution with future
callAsync(Supplier<T>) → CompletableFuture<T>  // Fetch value asynchronously
callSync(Supplier<T>) → CompletableFuture<T>   // Execute on main thread from async
forAllPlayers(Consumer<Player>)          // Iterate players with safe error handling
forAllLoadedChunks(Consumer<Chunk>)      // Process loaded chunks
executeIfLoaded(Location, Runnable)      // Execute only if chunk is loaded
runWithRetry(Runnable, int, long, TimeUnit)  // Automatic retries
ticksToMs(long) / msToTicks(long)        // Optimized time conversion
isFolia() → boolean                      // Detect if server is Folia
safeMessage(Throwable) → String          // Null-safe logging for exceptions
```

---

## 🤝 Contributing

Contributions are welcome! 🎉

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request



---

## ❓ FAQ

### Will my v2.0 code work in v2.1?
✅ **Yes, 100% compatible.** v2.1 is an incremental update with performance improvements and new utilities. All v2.0 code works without changes.

### Do I need to change anything when migrating from FoliaLib?
Only replace the scheduling calls. Your plugin's business logic remains unchanged. Use the [equivalence table](#equivalence-table) as a reference.

### Does it work on Paper and Folia?
✅ **Yes, automatically.** MagmaLib detects the server type and uses the appropriate scheduler:
- **Folia:** RegionScheduler, EntityScheduler, GlobalRegionScheduler
- **Paper/Spigot:** Traditional Bukkit Scheduler

### How do I debug tasks with `.named()`?
Error logs will automatically include:
```
[ERROR] Error in MagmaLib task ['MyTask'] location=world@123,45,67 entity=Player[Notch]: NullPointerException
```

### Is it safe to use `.unsafe()`?
Yes, if you follow these rules:
- ✅ Validate manually before calling (`location.getWorld() != null`, chunk loaded, `entity.isValid()`)
- ✅ Use only in code you fully control
- ✅ Document why it's safe in that context
- ❌ Never use with user inputs or external data

---

## 🔗 Links

- 📚 [Web Documentation](https://magmaenginers.github.io)
- 🐛 [Report an Issue](https://github.com/MagmaEnginers/MagmaLib/issues)
- 📦 [Releases](https://github.com/MagmaEnginers/MagmaLib/releases)
- 🧪 [MagmaLibTest Plugin](https://github.com/MagmaEnginers/MagmaLibTest)

---

## 📜 License

```
MIT License

Copyright (c) 2026 MagmaEnginers

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

> ✨ **Created by Piratemajo & MagmaEnginersAI**  
> Made with ❤️ for the Paper/Folia community.  
> Found a bug or have a suggestion? Open an issue! 🚀