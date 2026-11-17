package ru.winkiz.lab4;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Automatic scenarios. Uses real TCP clients and identity/payload validation. */
public final class ScenarioRunner {
    public record Options(String host, int port, Duration timeout, long seed, String queuePrefix) { }
    public record Plan(String name, int scenario, int senders, int receivers,
                       int perSender, int payloadBytes, boolean tcpNoDelay) {
        public int total() { return senders * perSender; }
    }
    public record Result(String name, int scenario, int senders, int receivers, int payloadBytes,
                         boolean tcpNoDelay, int sent, int received, int unique, int lost,
                         int duplicates, int unexpected, int corrupted, double seconds,
                         double throughput, double bytesPerSecond, int[] counts, double deviation,
                         double medianLatencyMs, double p95LatencyMs, boolean passed, String error) { }

    private final Options options;
    public ScenarioRunner(Options options) { this.options = options; }

    private final class RunState {
        final Plan plan;
        final Map<String, String> expected = new HashMap<>();
        final Set<String> sentIds = ConcurrentHashMap.newKeySet();
        final Set<String> seen = ConcurrentHashMap.newKeySet();
        final ConcurrentHashMap<String, Long> sendTimes = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Long> arrivals = new ConcurrentHashMap<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final List<BrokerClient> clients = new CopyOnWriteArrayList<>();
        final AtomicInteger totalReceived = new AtomicInteger();
        final AtomicInteger duplicates = new AtomicInteger(), unexpected = new AtomicInteger(), corrupted = new AtomicInteger();
        final int[] counts;
        final Object progress = new Object();
        final CountDownLatch start = new CountDownLatch(1), firstHalf = new CountDownLatch(1);
        final CountDownLatch receiverConnected = new CountDownLatch(1), firstClosed = new CountDownLatch(1);
        final String queue;
        volatile long began, lastReceived;
        volatile boolean stopped;

        RunState(Plan plan) {
            this.plan = plan; counts = new int[plan.receivers()];
            queue = options.queuePrefix() + "-" + plan.scenario() + "-" + UUID.randomUUID().toString().replace("-", "");
        }

        long remaining() { return began == 0 ? options.timeout().toNanos() : options.timeout().toNanos() - (System.nanoTime() - began); }

        BrokerClient connect(BrokerClient.Role role) throws IOException {
            long nanos = began == 0 ? options.timeout().toNanos() : remaining();
            if (nanos <= 0) throw new IOException("Scenario deadline exceeded while connecting");
            BrokerClient client = new BrokerClient(options.host(), options.port(), role, queue,
                    plan.tcpNoDelay(), Duration.ofMillis(Math.max(1, Math.min(3000, nanos / 1_000_000))));
            clients.add(client);
            return client;
        }

        void failure(Throwable e) {
            errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
            stopped = true;
            synchronized (progress) { progress.notifyAll(); }
        }

        void await(CountDownLatch latch) throws InterruptedException, TimeoutException {
            if (!latch.await(Math.max(0, remaining()), TimeUnit.NANOSECONDS)) throw new TimeoutException("Scenario latch timeout");
            if (stopped) throw new TimeoutException("Scenario stopped");
        }

        void record(int receiver, String message) {
            long now = System.nanoTime();
            int colon = message.indexOf(':');
            String id = colon < 0 ? message : message.substring(0, colon);
            if (!expected.containsKey(id)) unexpected.incrementAndGet();
            else if (!expected.get(id).equals(message)) corrupted.incrementAndGet();
            if (!seen.add(id)) duplicates.incrementAndGet();
            arrivals.putIfAbsent(id, now);
            synchronized (progress) {
                counts[receiver]++;
                totalReceived.incrementAndGet();
                lastReceived = Math.max(lastReceived, now);
                progress.notifyAll();
            }
        }

        void receiveLoop(int index, BrokerClient client) {
            try {
                start.await();
                while (!stopped && remaining() > 0) {
                    if (totalReceived.get() >= plan.total()) break;
                    List<String> batch = client.receive(1, Duration.ofNanos(Math.max(0, Math.min(remaining(), 20_000_000))));
                    for (String message : batch) record(index, message);
                    if (plan.scenario() == 2 && index == 0 && counts[0] == plan.total() / 4) {
                        client.close();
                        firstClosed.countDown();
                        return;
                    }
                }
                // Drain already buffered extras; all senders are joined before the final cross-check.
                for (String message : client.receive(plan.total(), Duration.ZERO)) record(index, message);
            } catch (Exception e) { if (!stopped) failure(e); }
        }

        void sendLoop(int sender, BrokerClient client, List<String> messages) {
            try {
                start.await();
                for (int i = 0; i < messages.size() && !stopped; i++) {
                    if (remaining() <= 0) throw new TimeoutException("Sending exceeded scenario deadline");
                    String message = messages.get(i), id = message.substring(0, message.indexOf(':'));
                    synchronized (progress) { if (began == 0) began = System.nanoTime(); }
                    sendTimes.put(id, System.nanoTime());
                    client.send(message);
                    sentIds.add(id);
                    if (plan.scenario() == 1 && i + 1 == messages.size() / 2) {
                        firstHalf.countDown(); await(receiverConnected);
                    }
                    if (plan.scenario() == 2 && firstClosed.getCount() != 0) {
                        // Until R1 closes, only one unconsumed frame is in flight.
                        // This is test coordination, not a protocol-level delivery acknowledgement.
                        synchronized (progress) {
                            while (!arrivals.containsKey(id) && !stopped && remaining() > 0)
                                TimeUnit.NANOSECONDS.timedWait(progress, remaining());
                        }
                        if (!arrivals.containsKey(id)) throw new TimeoutException("Scenario 2 delivery barrier timeout");
                        if (counts[0] >= plan.total() / 4) await(firstClosed);
                    }
                }
            } catch (Exception e) { failure(e); }
        }
    }

    public Result run(Plan plan) throws InterruptedException {
        RunState state = new RunState(plan);
        List<List<String>> generated = new ArrayList<>();
        Random random = new Random(options.seed() ^ plan.name().hashCode());
        for (int sender = 0; sender < plan.senders(); sender++) {
            List<String> messages = new ArrayList<>();
            for (int i = 0; i < plan.perSender(); i++) {
                String id = String.format(java.util.Locale.ROOT, "%02d-%06d", sender, i);
                String message = payload(id, plan.payloadBytes(), random);
                state.expected.put(id, message); messages.add(message);
            }
            generated.add(messages);
        }
        ExecutorService pool = Executors.newFixedThreadPool(plan.senders() + plan.receivers(), r -> new Thread(r, "lab4-scenario-worker"));
        List<Future<?>> futures = new ArrayList<>();
        List<BrokerClient> receivers = new ArrayList<>();
        long finished = 0;
        try {
            if (plan.scenario() != 1) for (int i = 0; i < plan.receivers(); i++) receivers.add(state.connect(BrokerClient.Role.RECEIVER));
            List<BrokerClient> senders = new ArrayList<>();
            for (int i = 0; i < plan.senders(); i++) senders.add(state.connect(BrokerClient.Role.SENDER));
            for (int i = 0; i < receivers.size(); i++) {
                int index = i; futures.add(pool.submit(() -> state.receiveLoop(index, receivers.get(index))));
            }
            for (int i = 0; i < senders.size(); i++) {
                int index = i; futures.add(pool.submit(() -> state.sendLoop(index, senders.get(index), generated.get(index))));
            }
            // The timestamp is established by the first sending worker immediately before send().
            state.start.countDown();
            if (plan.scenario() == 1) {
                state.await(state.firstHalf);
                BrokerClient receiver = state.connect(BrokerClient.Role.RECEIVER);
                receivers.add(receiver);
                futures.add(pool.submit(() -> state.receiveLoop(0, receiver)));
                state.receiverConnected.countDown();
            }
            for (Future<?> future : futures) future.get(Math.max(1, state.remaining()), TimeUnit.NANOSECONDS);
            // Short post-delivery observation detects extra frames, outside measured delivery time.
            for (int i = 0; i < receivers.size(); i++) {
                if (plan.scenario() == 2 && i == 0) continue; // R1 deliberately closed.
                for (String message : receivers.get(i).receive(plan.total(), Duration.ofMillis(50))) state.record(i, message);
            }
        } catch (InterruptedException e) { state.stopped = true; throw e; }
        catch (Exception e) { state.failure(e); }
        finally {
            finished = System.nanoTime();
            state.stopped = true;
            state.start.countDown(); state.firstHalf.countDown(); state.receiverConnected.countDown(); state.firstClosed.countDown();
            for (BrokerClient client : state.clients) try { client.close(); } catch (IOException e) { state.errors.add(e.toString()); }
            pool.shutdownNow();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) state.errors.add("Scenario worker threads remain");
        }
        Set<String> missing = new HashSet<>(state.expected.keySet()); missing.removeAll(state.seen);
        int total = state.totalReceived.get();
        long ended = total >= plan.total() ? state.lastReceived : finished;
        double seconds = state.began == 0 ? 0 : Math.max(0, ended - state.began) / 1e9;
        boolean passed = state.errors.isEmpty() && state.sentIds.equals(state.expected.keySet())
                && state.seen.equals(state.expected.keySet()) && total == plan.total()
                && missing.isEmpty() && state.duplicates.get() == 0 && state.corrupted.get() == 0;
        if (plan.scenario() == 2) passed &= state.counts[0] == plan.total() / 4 && state.counts[1] == plan.total() * 3 / 4;
        double deviation = 0;
        for (int count : state.counts) deviation = Math.max(deviation, Math.abs((total == 0 ? 0 : 100.0 * count / total) - 100.0 / plan.receivers()));
        double[] latencies = state.arrivals.entrySet().stream().filter(e -> state.sendTimes.containsKey(e.getKey()))
                .mapToDouble(e -> (e.getValue() - state.sendTimes.get(e.getKey())) / 1e6).sorted().toArray();
        double median = latencies.length == 0 ? 0 : latencies.length % 2 == 0
                ? (latencies[latencies.length / 2 - 1] + latencies[latencies.length / 2]) / 2 : latencies[latencies.length / 2];
        double p95 = latencies.length == 0 ? 0 : latencies[(int) Math.ceil(latencies.length * .95) - 1];
        return new Result(plan.name(), plan.scenario(), plan.senders(), plan.receivers(), plan.payloadBytes(), plan.tcpNoDelay(),
                state.sentIds.size(), total, state.seen.size(), missing.size(), state.duplicates.get(), state.unexpected.get(),
                state.corrupted.get(), seconds, seconds > 0 ? total / seconds : 0,
                seconds > 0 ? (double) total * plan.payloadBytes() / seconds : 0,
                state.counts.clone(), deviation, median, p95, passed, String.join(" | ", state.errors));
    }

    private static String payload(String id, int bytes, Random random) {
        String prefix = id + ":";
        if (bytes < prefix.length()) throw new IllegalArgumentException("Payload too small for identity");
        StringBuilder text = new StringBuilder(prefix);
        // Include Cyrillic, embedded newline, emoji, and NUL whenever the size allows it.
        String special = "Привет\n😀\0";
        int remaining = bytes - prefix.length();
        if (remaining >= special.getBytes(java.nio.charset.StandardCharsets.UTF_8).length) {
            text.append(special); remaining -= special.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        while (remaining-- > 0) text.append((char) ('a' + random.nextInt(26)));
        return text.toString();
    }
}
