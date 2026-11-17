package ru.winkiz.lab4;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public final class Main {
    private Main() { }

    public static void main(String[] args) {
        int status;
        try { status = execute(args); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); System.err.println("Interrupted"); status = 1; }
        catch (Exception e) { System.err.println("FAIL: " + e.getMessage()); status = 1; }
        if (status != 0) System.exit(status);
    }

    static int execute(String[] args) throws Exception {
        Map<String, String> flags = parse(args);
        if (flags.containsKey("help")) { help(); return 0; }
        if (flags.containsKey("server")) {
            String bind = flags.getOrDefault("bind", "127.0.0.1");
            LocalBroker server = new LocalBroker(bind, integer(flags, "port", 1111, 1, 65535));
            Thread hook = new Thread(() -> { try { server.close(); } catch (Exception e) { System.err.println(e); } }, "lab4-server-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            System.out.println("Auxiliary broker on " + bind + ":" + server.port() + ". Ctrl+C to stop.");
            try (server) { new CountDownLatch(1).await(); }
            return 0;
        }
        boolean local = !flags.containsKey("host");
        if (flags.containsKey("local") && !local) throw new IllegalArgumentException("Use either --local or --host");
        Path output = Path.of(flags.getOrDefault("out", "results/run-" + System.currentTimeMillis()));
        if (Files.exists(output)) try (var entries = Files.list(output)) {
            if (entries.findAny().isPresent()) throw new IllegalArgumentException("Output directory must be new or empty: " + output);
        }
        int repeats = integer(flags, "repeats", 5, 1, 100), warmups = integer(flags, "warmups", 1, 0, 20);
        int perSender = integer(flags, "messages", 1000, 4, 1_000_000);
        if (perSender % 4 != 0) throw new IllegalArgumentException("--messages must be divisible by 4 for scenario 2");
        int bytes = integer(flags, "bytes", 256, 16, Protocol.MAX_MESSAGE_BYTES);
        boolean nodelay = !flags.getOrDefault("nodelay", "true").equals("false");
        double timeout = Double.parseDouble(flags.getOrDefault("timeout", "10"));
        if (!Double.isFinite(timeout) || timeout < .01 || timeout > 3600) throw new IllegalArgumentException("Invalid timeout");
        long seed = Long.parseLong(flags.getOrDefault("seed", "20261117"));
        String prefix = flags.getOrDefault("queue", "lab4"); Protocol.validateQueue(prefix);
        if (prefix.length() > 100) throw new IllegalArgumentException("Queue prefix exceeds 100 characters");
        LocalBroker broker = local ? new LocalBroker("127.0.0.1", 0) : null;
        int port = local ? broker.port() : integer(flags, "port", 1111, 1, 65535);
        String host = flags.getOrDefault("host", "127.0.0.1");
        List<ReportWriter.Observation> observations = new ArrayList<>();
        boolean passed = true;
        long threadsBefore = countLabThreads();
        try {
            var runner = new ScenarioRunner(new ScenarioRunner.Options(host, port, Duration.ofNanos((long) (timeout * 1e9)), seed, prefix));
            List<ScenarioRunner.Plan> plans = new ArrayList<>();
            String selected = flags.getOrDefault("scenario", "all");
            if (!List.of("all", "1", "2", "3").contains(selected)) throw new IllegalArgumentException("--scenario must be all, 1, 2 or 3");
            if (selected.equals("all") || selected.equals("1")) plans.add(new ScenarioRunner.Plan("scenario-1", 1, 1, 1, perSender, bytes, nodelay));
            if (selected.equals("all") || selected.equals("2")) plans.add(new ScenarioRunner.Plan("scenario-2", 2, 1, 2, perSender, bytes, nodelay));
            if (selected.equals("all") || selected.equals("3")) plans.add(new ScenarioRunner.Plan("scenario-3", 3, 2, 3, perSender, bytes, nodelay));
            if (flags.containsKey("full")) {
                for (int receivers : new int[]{1, 2, 3, 4, 6}) plans.add(new ScenarioRunner.Plan("receivers-" + receivers, 3, 2, receivers, perSender, bytes, nodelay));
                for (int size : new int[]{16, 256, 4096, 65536}) plans.add(new ScenarioRunner.Plan("bytes-" + size, 3, 2, 3, perSender, size, nodelay));
                for (boolean enabled : new boolean[]{true, false}) plans.add(new ScenarioRunner.Plan("nodelay-" + enabled, 3, 2, 3, perSender, 16, enabled));
                plans.add(new ScenarioRunner.Plan("load-4x4", 3, 4, 4, 2500, bytes, nodelay));
            }
            for (var plan : plans) {
                for (int run = 0; run < warmups + repeats; run++) {
                    String phase = run < warmups ? "warmup" : "measurement";
                    int index = run < warmups ? run + 1 : run - warmups + 1;
                    var result = runner.run(plan);
                    observations.add(new ReportWriter.Observation(phase, index, result));
                    passed &= result.passed();
                    System.out.printf(Locale.ROOT, "%s %s #%d: sent=%d received=%d lost=%d duplicates=%d time=%.6f s X=%.1f msg/s %s%n",
                            plan.name(), phase, index, result.sent(), result.received(), result.lost(), result.duplicates(), result.seconds(), result.throughput(), result.passed() ? "PASS" : "FAIL");
                    if (!result.error().isEmpty()) System.out.println("  " + result.error());
                    for (int i = 0; i < result.counts().length; i++) System.out.printf(Locale.ROOT, "  R%d: %d (%.2f%%)%n", i + 1, result.counts()[i], result.received() == 0 ? 0 : 100.0 * result.counts()[i] / result.received());
                    if (plan.scenario() == 3 && result.received() > 0)
                        for (int count : result.counts()) if (100.0 * count / result.received() < 20 && plan.receivers() == 3)
                            System.out.println("  Distribution warning: a receiver has less than 20%; inspect server scheduling.");
                    // A failed experiment remains in CSV and causes exit code 1. Never replace it by an expected value.
                }
            }
        } finally { if (broker != null) broker.close(); }
        long remaining = countLabThreads();
        passed &= remaining == 0;
        Files.createDirectories(output);
        Files.writeString(output.resolve("environment.txt"), "server=" + (local ? "auxiliary LocalBroker (loopback); not teacher server" : host + ":" + port)
                + "\njava=" + System.getProperty("java.version") + "\nos=" + System.getProperty("os.name")
                + "\nprocessors=" + Runtime.getRuntime().availableProcessors() + "\nseed=" + seed + "\ntimeout_seconds=" + timeout
                + "\nwarmups=" + warmups + "\nrepeats=" + repeats + "\nthreads_before_clients=" + threadsBefore
                + "\nlab_threads_after_close=" + remaining + "\nprotocol_errors=" + (broker == null ? "unknown" : broker.protocolErrors()) + "\n", StandardCharsets.UTF_8);
        ReportWriter.write(output, observations, local);
        System.out.println("\nAll scenarios: " + (passed ? "PASS" : "FAIL") + ". Reports: " + output.toAbsolutePath());
        return passed ? 0 : 1;
    }

    static long countLabThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive)
                .filter(t -> t.getName().startsWith("broker-client-") || t.getName().startsWith("lab4-scenario-") || t.getName().startsWith("broker-local-")).count();
    }

    private static int integer(Map<String, String> flags, String name, int fallback, int min, int max) {
        int value = Integer.parseInt(flags.getOrDefault(name, Integer.toString(fallback)));
        if (value < min || value > max) throw new IllegalArgumentException("--" + name + " must be " + min + ".." + max);
        return value;
    }

    private static Map<String, String> parse(String[] args) {
        SetNames allowed = new SetNames();
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("Expected --option: " + args[i]);
            String key = args[i].substring(2);
            if (!allowed.all.contains(key) || result.containsKey(key)) throw new IllegalArgumentException("Unknown or repeated option --" + key);
            if (allowed.booleans.contains(key)) result.put(key, "true");
            else {
                if (++i == args.length) throw new IllegalArgumentException("Missing value for --" + key);
                result.put(key, args[i]);
            }
        }
        if (result.containsKey("nodelay") && !List.of("true", "false").contains(result.get("nodelay"))) throw new IllegalArgumentException("--nodelay must be true or false");
        return result;
    }

    private static final class SetNames {
        final java.util.Set<String> booleans = java.util.Set.of("help", "server", "local", "full");
        final java.util.Set<String> all = java.util.Set.of("help", "server", "local", "full", "host", "port", "bind", "out", "scenario", "messages", "bytes", "timeout", "seed", "queue", "nodelay", "warmups", "repeats");
    }

    private static void help() {
        System.out.println("Java 21 TCP broker lab. No arguments: local broker and all 3 required scenarios (1 warmup + 5 runs).\n"
                + "--local --full --out results/local : required + receivers/sizes/Nagle/load experiments\n"
                + "--host HOST --port 1111 : use an already running external broker\n"
                + "--server --bind 127.0.0.1 --port 1111 : auxiliary standalone broker\n"
                + "--scenario all|1|2|3 --messages 1000 --bytes 256 --timeout 10 --seed 20261117\n"
                + "--warmups 1 --repeats 5 --queue lab4 --nodelay true|false --out NEW_DIRECTORY");
    }
}
