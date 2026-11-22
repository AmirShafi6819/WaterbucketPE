/*
 * Copyright 2026 WaterbucketTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterbucket.waterbucketpe.plugin.python;

import dev.waterbucket.waterbucketpe.ProxyServer;
import dev.waterbucket.waterbucketpe.event.AsyncEvent;
import dev.waterbucket.waterbucketpe.event.CancellableEvent;
import dev.waterbucket.waterbucketpe.event.CompletableEvent;
import dev.waterbucket.waterbucketpe.event.Event;
import dev.waterbucket.waterbucketpe.event.EventManager;
import dev.waterbucket.waterbucketpe.event.EventPriority;
import dev.waterbucket.waterbucketpe.plugin.PluginManager;
import dev.waterbucket.waterbucketpe.plugin.PluginYAML;
import dev.waterbucket.waterbucketpe.plugin.python.PythonPlugin;
import dev.waterbucket.waterbucketpe.utils.config.proxy.ProxyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 3 Stage C/G measurement harness. Runs only with {@code PHASE3_BENCH=true}; all
 * scenarios go through the real production dispatch path (EventManager.callEvent, the
 * PythonPlugin bridge, the real PluginManager loading flow) - nothing bypasses hooks.
 * <p>
 * Results are written to {@code benchmarks/results-plugin-phase3-<label>.json} where the
 * label comes from {@code PHASE3_BENCH_LABEL}, so before/after runs use identical workloads
 * with distinct output files.
 */
@EnabledIfEnvironmentVariable(named = "PHASE3_BENCH", matches = "true")
public class Phase3PluginEngineBenchmark {

    public static class SyncProbeEvent extends Event {
    }

    public static final class CancelSyncProbeEvent extends Event implements CancellableEvent {
    }

    @CompletableEvent
    public static class CompletableSyncProbeEvent extends Event {
    }

    @AsyncEvent
    public static class AsyncProbeEvent extends Event {
    }

    /** Dedicated event for the python-bridge scenario: only the python handler subscribes. */
    public static class PythonProbeEvent extends Event {
    }

    public static class MixedEventA extends Event {
    }

    public static class MixedEventB extends Event {
    }

    public static class MixedEventC extends Event {
    }

    /** Counts python handler executions so the harness can prove the handler really ran. */
    public static final java.util.concurrent.atomic.AtomicLong PYTHON_SINK =
            new java.util.concurrent.atomic.AtomicLong();

    /** Static method invoked from the python handler; static-method interop is reliable. */
    public static void notePython() {
        PYTHON_SINK.incrementAndGet();
    }

    private static volatile long SINK;

    @TempDir
    Path tempDir;

    private ProxyServer proxy;
    private EventManager eventManager;

    @BeforeEach
    void setUp() throws Exception {
        this.proxy = mock(ProxyServer.class);
        ProxyConfig config = mock(ProxyConfig.class);
        when(this.proxy.getConfiguration()).thenReturn(config);
        when(config.getIdleThreads()).thenReturn(4);
        when(this.proxy.getPluginPath()).thenReturn(this.tempDir.resolve("plugins"));
        Files.createDirectories(this.tempDir.resolve("plugins"));
        when(this.proxy.getLogger()).thenReturn(mock(dev.waterbucket.waterbucketpe.logger.MainLogger.class));
        this.eventManager = new EventManager(this.proxy);
        when(this.proxy.getEventManager()).thenReturn(this.eventManager);
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static double percentile(List<Double> values, double p) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    /** warmup + measured rounds; per-op nanoseconds on the calling thread. */
    private Map<String, Object> measure(String name, int warmupRounds, int rounds, int ops, Runnable body) {
        for (int i = 0; i < warmupRounds; i++) {
            for (int op = 0; op < ops; op++) {
                body.run();
            }
        }
        List<Double> perOp = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            long t0 = System.nanoTime();
            for (int op = 0; op < ops; op++) {
                body.run();
            }
            perOp.add((System.nanoTime() - t0) / (double) ops);
        }
        return Map.of("name", name, "unit", "ns/op", "ops", ops,
                "rounds", perOp, "median", median(perOp), "p95", percentile(perOp, 0.95));
    }

    @SuppressWarnings("unchecked")
    private <T extends Event> void subscribeN(Class<T> event, int perPriority) {
        for (int i = 0; i < perPriority; i++) {
            for (EventPriority priority : EventPriority.VALUES) {
                // An anonymous instance per subscription: lambdas from one call site are
                // cached instances and would be deduplicated by the pipeline (contains).
                this.eventManager.subscribe(event, (Consumer<T>) new Consumer<Event>() {
                    @Override
                    public void accept(Event e) {
                        SINK++;
                    }
                }, priority);
            }
        }
    }

    private static Map<String, byte[]> compile(Map<String, String> sources, Path workDir) throws IOException {
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        Path srcDir = workDir.resolve("src");
        Path outDir = workDir.resolve("out");
        Files.createDirectories(srcDir);
        Files.createDirectories(outDir);
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = srcDir.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
        }
        List<String> args = new ArrayList<>(List.of("-d", outDir.toString()));
        try (var walk = Files.walk(srcDir)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
        }
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        if (compiler.run(null, null, err, args.toArray(new String[0])) != 0) {
            throw new IllegalStateException("benchmark sources failed to compile: " + err);
        }
        Map<String, byte[]> classes = new java.util.LinkedHashMap<>();
        try (var walk = Files.walk(outDir)) {
            walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                try {
                    classes.put(outDir.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return classes;
    }

    private record Step(String name, Map<String, Object> data) {
    }

    private final List<Step> results = new ArrayList<>();

    private PythonPlugin newPythonPlugin(Path pluginDir, String mainScript) throws IOException {
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("waterbucket.yml"),
                "name: BenchPy\nversion: 1.0.0\nauthor: bench\nmain: main.py\n");
        Files.writeString(pluginDir.resolve("main.py"), mainScript);
        PluginYAML descriptor = new PluginYAML();
        descriptor.name = "BenchPy";
        descriptor.version = "1.0.0";
        descriptor.author = "bench";
        descriptor.main = "main.py";
        PythonPlugin plugin = new PythonPlugin(this.proxy);
        plugin.initPython(descriptor, this.proxy, pluginDir.toFile());
        return plugin;
    }

    @Test
    void runAllScenarios() throws Exception {
        // ---------- sync dispatch scaling ----------
        this.results.add(new Step("sync_dispatch_0_handlers",
                this.measure("sync_dispatch_0_handlers", 5, 15, 200_000,
                        () -> SINK += this.eventManager.callEvent(new SyncProbeEvent()) == null ? 1 : 0)));

        this.subscribeN(SyncProbeEvent.class, 1);
        this.results.add(new Step("sync_dispatch_1_handler",
                this.measure("sync_dispatch_1_handler", 5, 15, 200_000,
                        () -> this.eventManager.callEvent(new SyncProbeEvent()))));

        // additional handlers: 1 existing + 4 new = 5 total (one per priority)
        for (EventPriority priority : EventPriority.VALUES) {
            this.eventManager.subscribe(SyncProbeEvent.class, new Consumer<SyncProbeEvent>() {
                @Override
                public void accept(SyncProbeEvent event) {
                    SINK++;
                }
            }, priority);
        }
        this.results.add(new Step("sync_dispatch_5_handlers_5_priorities",
                this.measure("sync_dispatch_5_handlers_5_priorities", 5, 15, 200_000,
                        () -> this.eventManager.callEvent(new SyncProbeEvent()))));

        this.subscribeN(SyncProbeEvent.class, 7); // +35 = 40 total
        this.results.add(new Step("sync_dispatch_40_handlers_5_priorities",
                this.measure("sync_dispatch_40_handlers_5_priorities", 5, 15, 100_000,
                        () -> this.eventManager.callEvent(new SyncProbeEvent()))));

        this.subscribeN(SyncProbeEvent.class, 56); // +280 = 320 total
        this.results.add(new Step("sync_dispatch_320_handlers_5_priorities",
                this.measure("sync_dispatch_320_handlers_5_priorities", 5, 15, 50_000,
                        () -> this.eventManager.callEvent(new SyncProbeEvent()))));

        // ---------- cancellation ----------
        this.eventManager.subscribe(CancelSyncProbeEvent.class, Event::setCancelled);
        this.results.add(new Step("cancel_sync_1_handler",
                this.measure("cancel_sync_1_handler", 5, 15, 200_000, () -> {
                    CancelSyncProbeEvent event = new CancelSyncProbeEvent();
                    this.eventManager.callEvent(event);
                    SINK += event.isCancelled() ? 1 : 0;
                })));

        // ---------- completable sync dispatch ----------
        this.eventManager.subscribe(CompletableSyncProbeEvent.class, event -> SINK++);
        this.results.add(new Step("completable_sync_1_handler",
                this.measure("completable_sync_1_handler", 5, 15, 50_000, () -> {
                    CompletableFuture<?> future = this.eventManager.callEvent(new CompletableSyncProbeEvent());
                    SINK += future.isDone() ? 1 : 0;
                })));

        // ---------- allocation rate of the sync dispatch path ----------
        com.sun.management.ThreadMXBean threadMxBean = (com.sun.management.ThreadMXBean)
                ManagementFactory.getThreadMXBean();
        long allocatedBefore = threadMxBean.getThreadAllocatedBytes(Thread.currentThread().getId());
        for (int i = 0; i < 100_000; i++) {
            this.eventManager.callEvent(new SyncProbeEvent());
        }
        long allocatedAfter = threadMxBean.getThreadAllocatedBytes(Thread.currentThread().getId());
        double bytesPerOp = (allocatedAfter - allocatedBefore) / 100_000.0;
        this.results.add(new Step("sync_dispatch_allocations", Map.of(
                "name", "sync_dispatch_allocations", "unit", "bytes/op", "median", bytesPerOp)));

        // ---------- async event round trip ----------
        this.eventManager.subscribe(AsyncProbeEvent.class, event -> SINK++);
        List<Double> asyncPerOp = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            for (int op = 0; op < 2_000; op++) {
                this.eventManager.callEvent(new AsyncProbeEvent()).get(5, TimeUnit.SECONDS);
            }
        }
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            for (int op = 0; op < 2_000; op++) {
                this.eventManager.callEvent(new AsyncProbeEvent()).get(5, TimeUnit.SECONDS);
            }
            asyncPerOp.add((System.nanoTime() - t0) / 2_000.0);
        }
        this.results.add(new Step("async_event_1_handler_roundtrip", Map.of(
                "name", "async_event_1_handler_roundtrip", "unit", "ns/op", "ops", 2000,
                "rounds", asyncPerOp, "median", median(asyncPerOp), "p95", percentile(asyncPerOp, 0.95))));
        long threadsAfterAsync = Thread.activeCount();

        // ---------- subscribe/unsubscribe round trip ----------
        this.results.add(new Step("subscribe_unsubscribe_roundtrip",
                this.measure("subscribe_unsubscribe_roundtrip", 2, 7, 10_000, () -> {
                    Consumer<SyncProbeEvent> handler = event -> SINK++;
                    this.eventManager.subscribe(SyncProbeEvent.class, handler);
                    this.eventManager.unsubscribe(SyncProbeEvent.class, handler);
                })));

        // ---------- Python bridge: load + dispatch overhead ----------
        long t0 = System.nanoTime();
        PythonPlugin python = this.newPythonPlugin(this.tempDir.resolve("plugins").resolve("BenchPy"), """
                import java
                SyncEvent = java.type(
                    "dev.waterbucket.waterbucketpe.plugin.python.Phase3PluginEngineBenchmark$SyncProbeEvent")
                PythonEvent = java.type(
                    "dev.waterbucket.waterbucketpe.plugin.python.Phase3PluginEngineBenchmark$PythonProbeEvent")
                Phase3Sink = java.type(
                    "dev.waterbucket.waterbucketpe.plugin.python.Phase3PluginEngineBenchmark")
                def on_startup(plugin):
                    pass
                def on_enable(plugin):
                    plugin.subscribe(SyncEvent, bench_handler)
                    plugin.subscribe(PythonEvent, python_only_handler)
                def bench_handler(event):
                    pass
                def python_only_handler(event):
                    Phase3Sink.notePython()
                def on_disable(plugin):
                    pass
                """);
        python.onStartup();
        double pythonLoadMs = (System.nanoTime() - t0) / 1_000_000.0;
        python.setEnabled(true);
        this.results.add(new Step("python_plugin_script_load", Map.of(
                "name", "python_plugin_script_load", "unit", "ms", "median", pythonLoadMs)));

        Map<String, Object> pythonBridge = new HashMap<>(
                this.measure("python_bridge_sync_1_handler", 2, 7, 2_000,
                        () -> this.eventManager.callEvent(new SyncProbeEvent())));
        // Proof the python handler really executed during the measurement
        pythonBridge.put("pythonHandlerInvocationsDuringWarmupAndMeasure", PYTHON_SINK.get());
        this.results.add(new Step("python_bridge_sync_1_handler", pythonBridge));

        this.results.add(new Step("python_only_handler_dispatch",
                this.measure("python_only_handler_dispatch", 2, 7, 2_000,
                        () -> this.eventManager.callEvent(new PythonProbeEvent()))));
        double pythonSinkAfterExclusive = PYTHON_SINK.get();

        // ---------- memory profiling: retained after python disable (class histogram) ----------
        String histogramBefore = this.classHistogram();
        python.setEnabled(false);
        System.gc();
        String histogramAfter = this.classHistogram();
        long retainedPolyglot = this.diffHistogramClasses(histogramBefore, histogramAfter);
        this.results.add(new Step("python_disable_retained_polyglot_instances", Map.of(
                "name", "python_disable_retained_polyglot_instances", "unit", "instances",
                "median", retainedPolyglot,
                "histogramCaptured", !histogramAfter.isBlank(),
                "pythonHandlerInvocationsTotal", Math.max(PYTHON_SINK.get(), pythonSinkAfterExclusive))));

        // ---------- plugin loading: 32 generated jars ----------
        Map<String, byte[]> pluginClasses = this.compile(Map.of(
                "benchload/BenchPlugin.java", """
                        package benchload;
                        public class BenchPlugin extends dev.waterbucket.waterbucketpe.plugin.Plugin {
                            @Override public void onEnable() { }
                        }
                        """), this.tempDir.resolve("work-load"));
        byte[] mainClass = pluginClasses.get("benchload/BenchPlugin.class");
        for (int i = 0; i < 32; i++) {
            Path jar = this.tempDir.resolve("plugins").resolve("bench-" + i + ".jar");
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
                out.putNextEntry(new JarEntry("waterbucket.yml"));
                out.write(("name: BenchLoad" + i + "\nversion: 1.0.0\nauthor: bench\n"
                        + "main: benchload.BenchPlugin\n").getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
                out.putNextEntry(new JarEntry("benchload/BenchPlugin.class"));
                out.write(mainClass);
                out.closeEntry();
            }
        }

        t0 = System.nanoTime();
        PluginManager loadManager = new PluginManager(this.proxy);
        double scanMs = (System.nanoTime() - t0) / 1_000_000.0;

        t0 = System.nanoTime();
        loadManager.loadAllPlugins();
        double loadMs = (System.nanoTime() - t0) / 1_000_000.0;

        t0 = System.nanoTime();
        loadManager.enableAllPlugins();
        double enableMs = (System.nanoTime() - t0) / 1_000_000.0;
        this.results.add(new Step("plugin_scan_32_jars", Map.of(
                "name", "plugin_scan_32_jars", "unit", "ms", "median", scanMs)));
        this.results.add(new Step("plugin_load_32_plugins", Map.of(
                "name", "plugin_load_32_plugins", "unit", "ms", "median", loadMs)));
        this.results.add(new Step("plugin_enable_32_plugins", Map.of(
                "name", "plugin_enable_32_plugins", "unit", "ms", "median", enableMs)));

        t0 = System.nanoTime();
        loadManager.disableAllPlugins();
        double disableMs = (System.nanoTime() - t0) / 1_000_000.0;
        this.results.add(new Step("plugin_disable_32_plugins", Map.of(
                "name", "plugin_disable_32_plugins", "unit", "ms", "median", disableMs)));

        // ---------- mixed event types under load (multiple event types, many plugins loaded) ----------
        // Three distinct event classes with 40 handlers each, dispatched in alternation while
        // the 32 loaded plugins remain registered: the multi-plugin, multi-event-type workload
        // from the measurement requirements.
        dev.waterbucket.waterbucketpe.event.Event[] mixedEvents = {
                new MixedEventA(), new MixedEventB(), new MixedEventC()
        };
        this.eventManager.subscribe(MixedEventA.class, event -> SINK++);
        this.eventManager.subscribe(MixedEventB.class, event -> SINK++);
        this.eventManager.subscribe(MixedEventC.class, event -> SINK++);
        this.subscribeN(MixedEventA.class, 7);
        this.subscribeN(MixedEventB.class, 7);
        this.subscribeN(MixedEventC.class, 7);
        java.util.concurrent.atomic.AtomicInteger eventCursor = new java.util.concurrent.atomic.AtomicInteger();
        this.results.add(new Step("mixed_event_types_3x40_handlers",
                this.measure("mixed_event_types_3x40_handlers", 5, 15, 90_000, () -> {
                    int cursor = eventCursor.getAndIncrement();
                    dev.waterbucket.waterbucketpe.event.Event event = mixedEvents[cursor % mixedEvents.length];
                    if (event instanceof MixedEventA) {
                        this.eventManager.callEvent(new MixedEventA());
                    } else if (event instanceof MixedEventB) {
                        this.eventManager.callEvent(new MixedEventB());
                    } else {
                        this.eventManager.callEvent(new MixedEventC());
                    }
                })));

        // ---------- missing-class lookup across all loaded loaders (failure path cost) ----------
        // getClassFromCache scans every plugin loader per miss; with 32 plugins loaded this
        // measures one full miss (32 jar probes + ClassNotFoundException construction).
        dev.waterbucket.waterbucketpe.plugin.PluginClassLoader benchLoader =
                loadManager.getPluginClassLoaders().iterator().next();
        this.results.add(new Step("missing_class_lookup_32_loaders",
                this.measure("missing_class_lookup_32_loaders", 2, 7, 500, () -> {
                    try {
                        benchLoader.loadClass("benchload.Does.Not.Exist");
                    } catch (ClassNotFoundException expected) {
                        SINK++;
                    }
                })));

        this.writeResults(threadsAfterAsync);
    }

    /** Runs jcmd GC.class_histogram on this JVM; falls back to an empty string on failure. */
    private String classHistogram() {
        try {
            String pid = String.valueOf(ProcessHandle.current().pid());
            String jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd").toString();
            Process process = new ProcessBuilder(jcmd, pid, "GC.class_histogram")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(60, TimeUnit.SECONDS);
            return output;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Sum of live-instance deltas across polyglot/truffle classes in the second histogram,
     * i.e. what the disabled plugin left behind; negative when instances were collected.
     */
    private long diffHistogramClasses(String before, String after) {
        Map<String, Long> beforeCounts = this.histogramCounts(before);
        Map<String, Long> afterCounts = this.histogramCounts(after);
        long retained = 0;
        for (Map.Entry<String, Long> entry : afterCounts.entrySet()) {
            if (entry.getKey().startsWith("org.graalvm") || entry.getKey().contains("truffle")
                    || entry.getKey().startsWith("com.oracle.truffle")) {
                retained += entry.getValue() - beforeCounts.getOrDefault(entry.getKey(), 0L);
            }
        }
        return retained;
    }

    private Map<String, Long> histogramCounts(String histogram) {
        Map<String, Long> counts = new HashMap<>();
        if (histogram.isBlank()) {
            return counts;
        }
        for (String line : histogram.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 3 && parts[0].matches("\\d+") && parts[1].contains(":")) {
                try {
                    counts.put(parts[2], Long.parseLong(parts[1]));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return counts;
    }

    private void writeResults(long threadsAfterAsync) throws IOException {
        String label = System.getenv().getOrDefault("PHASE3_BENCH_LABEL", "run");
        Path out = Path.of("benchmarks", "results-plugin-phase3-" + label + ".json");
        Files.createDirectories(out.getParent());
        StringBuilder json = new StringBuilder("{\n");
        json.append(String.format(Locale.ROOT, "  \"label\": \"%s\",\n", label));
        json.append(String.format(Locale.ROOT, "  \"date\": \"%s\",\n", java.time.Instant.now()));
        json.append(String.format(Locale.ROOT, "  \"javaVersion\": \"%s\",\n",
                System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")"));
        json.append(String.format(Locale.ROOT, "  \"threadsAfterAsyncScenarios\": %d,\n", threadsAfterAsync));
        json.append("  \"scenarios\": [");
        for (int i = 0; i < this.results.size(); i++) {
            Step step = this.results.get(i);
            json.append(i == 0 ? "\n" : ",\n");
            json.append("    {\"name\": \"").append(step.name()).append("\", \"data\": ")
                    .append(jsonValue(step.data())).append("}");
        }
        json.append("\n  ]\n}\n");
        Files.writeString(out, json.toString(), StandardCharsets.UTF_8);
        System.out.println("WROTE " + out.toAbsolutePath());
    }

    private String jsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append("\"").append(entry.getKey()).append("\": ").append(jsonValue(entry.getValue()));
            }
            return sb.append("}").toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(jsonValue(item));
            }
            return sb.append("]").toString();
        }
        if (value instanceof Double d) {
            return String.format(Locale.ROOT, "%.2f", d);
        }
        if (value instanceof String s) {
            return "\"" + s + "\"";
        }
        return String.valueOf(value);
    }
}
