package ru.winkiz.lab4;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Independent scripted TCP peers test the client; no external broker needed. */
public final class SelfTest {
    private static int passed;
    private static final Duration SECOND = Duration.ofSeconds(1);
    private SelfTest() { }
    @FunctionalInterface interface Checked { void run() throws Exception; }
    @FunctionalInterface interface Peer { void run(Socket socket) throws Exception; }

    private static final class Fixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 10, java.net.InetAddress.getLoopbackAddress());
        final ExecutorService pool = Executors.newSingleThreadExecutor();
        final Future<?> task;
        volatile Socket accepted;
        Fixture(Peer peer) throws IOException {
            task = pool.submit(() -> {
                try (Socket socket = server.accept()) {
                    accepted = socket; socket.setSoTimeout(3000); peer.run(socket);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        }
        BrokerClient client(BrokerClient.Role role) throws IOException { return new BrokerClient("127.0.0.1", server.getLocalPort(), role, "self-test"); }
        @Override public void close() throws Exception {
            server.close();
            try { task.get(4, TimeUnit.SECONDS); }
            finally {
                if (accepted != null) accepted.close();
                pool.shutdownNow();
                if (!pool.awaitTermination(2, TimeUnit.SECONDS)) throw new AssertionError("Fixture thread leaked");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        test("UTF-8 byte length, framing and fragmented input", () -> {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Protocol.writeMessage(output, Protocol.encode("привет"));
            check(new String(output.toByteArray(), StandardCharsets.UTF_8).equals("message 12\nпривет"), "Byte length");
            byte[] wire = "message 12\nприветmessage 5\na\nb\0c".getBytes(StandardCharsets.UTF_8);
            InputStream fragmented = new ByteArrayInputStream(wire) {
                @Override public synchronized int read(byte[] bytes, int off, int length) { return super.read(bytes, off, Math.min(1, length)); }
            };
            check(Protocol.decode(Protocol.readPayload(fragmented)).equals("привет"), "Cyrillic");
            check(Protocol.decode(Protocol.readPayload(fragmented)).equals("a\nb\0c"), "Newline and NUL payload");
        });
        for (String wire : List.of("msg 1\na", "message -1\n", "message 0\n", "message x\n", "message +1\na",
                "message 2147483648\n", "message 1048577\n", "message 3\nab", "message 1", "message  1\na", "message 1\r\na")) {
            test("Reject malformed/truncated frame " + wire.replace('\n', '|'), () -> expect(IOException.class,
                    () -> Protocol.readPayload(new ByteArrayInputStream(wire.getBytes(StandardCharsets.US_ASCII)))));
        }
        test("Header bound and invalid UTF-8", () -> {
            expect(IOException.class, () -> Protocol.readLine(new ByteArrayInputStream(("x".repeat(1025) + "\n").getBytes(StandardCharsets.US_ASCII))));
            expect(IOException.class, () -> Protocol.decode(new byte[]{(byte) 0xC3, 0x28}));
            expect(EOFException.class, () -> Protocol.readPayload(new ByteArrayInputStream(new byte[0])));
        });
        test("Validation before connection and payload bounds", () -> {
            for (String queue : List.of("", "a b", "test\nx", "русский", "x".repeat(1020)))
                expect(IllegalArgumentException.class, () -> new BrokerClient("127.0.0.1", 1, BrokerClient.Role.SENDER, queue));
            expect(IllegalArgumentException.class, () -> Protocol.encode(""));
            expect(IllegalArgumentException.class, () -> Protocol.encode("a".repeat(1048577)));
            expect(IllegalArgumentException.class, () -> Protocol.encode("\uD800"));
            check(Protocol.encode("a".repeat(1048576)).length == 1048576, "Maximum valid payload");
            expect(IllegalArgumentException.class, () -> new BrokerClient("localhost", 0, BrokerClient.Role.SENDER, "ok"));
        });
        test("Unavailable server returns IOException", () -> {
            int port;
            try (ServerSocket unused = new ServerSocket(0)) { port = unused.getLocalPort(); }
            int finalPort = port;
            expect(IOException.class, () -> new BrokerClient("127.0.0.1", finalPort, BrokerClient.Role.SENDER, "ok"));
        });
        test("Setup, exact bodies, partial timeout and immutable copy", () -> {
            CountDownLatch hold = new CountDownLatch(1);
            try (Fixture fixture = new Fixture(socket -> {
                check(readLine(socket.getInputStream()).equals("receive self-test"), "Setup receive");
                OutputStream out = socket.getOutputStream();
                out.write("message 12\nприветmessage 3\nx\ny".getBytes(StandardCharsets.UTF_8)); out.flush();
                hold.await(2, TimeUnit.SECONDS);
            }); BrokerClient client = fixture.client(BrokerClient.Role.RECEIVER)) {
                long began = System.nanoTime();
                List<String> result = client.receive(3, Duration.ofMillis(100));
                check(result.equals(List.of("привет", "x\ny")), "Partial batch");
                check(System.nanoTime() - began < 900_000_000, "One global timeout");
                expect(UnsupportedOperationException.class, () -> result.add("oops"));
                check(client.receive(0, Duration.ZERO).isEmpty(), "Zero count");
                expect(IllegalArgumentException.class, () -> client.receive(-1, SECOND));
                expect(IllegalArgumentException.class, () -> client.receive(1, Duration.ofSeconds(-1)));
                expect(IllegalStateException.class, () -> client.send("wrong role"));
                hold.countDown();
            } finally { hold.countDown(); }
        });
        for (String wire : List.of("message 5\nab", "message x\n", "message 2\n\u00C0\u00AF", "")) {
            test("Background error wakes receive: " + wire.replace('\n', '|'), () -> {
                try (Fixture fixture = new Fixture(socket -> {
                    readLine(socket.getInputStream()); socket.getOutputStream().write(wire.getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                }); BrokerClient client = fixture.client(BrokerClient.Role.RECEIVER)) {
                    long began = System.nanoTime();
                    expect(IOException.class, () -> client.receive(1, Duration.ofSeconds(5)));
                    check(System.nanoTime() - began < 1_500_000_000L, "Prompt failure propagation");
                }
            });
        }
        test("Half frame is bounded by receive timeout and close", () -> {
            CountDownLatch hold = new CountDownLatch(1);
            try (Fixture fixture = new Fixture(socket -> {
                readLine(socket.getInputStream()); socket.getOutputStream().write("message 999\na".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush(); hold.await(2, TimeUnit.SECONDS);
            }); BrokerClient client = fixture.client(BrokerClient.Role.RECEIVER)) {
                check(client.receive(1, Duration.ofMillis(30)).isEmpty(), "Incomplete payload timeout");
                client.close(); client.close(); hold.countDown();
            } finally { hold.countDown(); }
        });
        test("Concurrent send: 4 threads share one client, no mixed frames", () -> {
            CountDownLatch received = new CountDownLatch(1), release = new CountDownLatch(1);
            try (Fixture fixture = new Fixture(socket -> {
                InputStream in = socket.getInputStream();
                check(readLine(in).equals("send self-test"), "Setup send");
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < 400; i++) {
                    String header = readLine(in); check(header.matches("message [0-9]+"), "Atomic header");
                    int n = Integer.parseInt(header.substring(8)); byte[] bytes = in.readNBytes(n);
                    check(bytes.length == n && seen.add(new String(bytes, StandardCharsets.UTF_8)), "Atomic unique body");
                }
                received.countDown(); release.await(2, TimeUnit.SECONDS);
            }); BrokerClient client = fixture.client(BrokerClient.Role.SENDER)) {
                ExecutorService senders = Executors.newFixedThreadPool(4);
                try {
                    List<Future<?>> futures = new java.util.ArrayList<>();
                    for (int s = 0; s < 4; s++) {
                        int id = s;
                        futures.add(senders.submit(() -> {
                            try { for (int i = 0; i < 100; i++) client.send(id + ":" + i + ":привет\n😀"); }
                            catch (IOException e) { throw new RuntimeException(e); }
                        }));
                    }
                    for (Future<?> f : futures) f.get(3, TimeUnit.SECONDS);
                    check(received.await(2, TimeUnit.SECONDS), "All shared-client sends received");
                    expect(IllegalStateException.class, () -> client.receive(1, SECOND));
                } finally { senders.shutdownNow(); senders.awaitTermination(2, TimeUnit.SECONDS); release.countDown(); }
                client.close(); expect(IOException.class, () -> client.send("closed"));
            } finally { release.countDown(); }
        });
        test("Close wakes all waiting consumers and joins reader", () -> {
            CountDownLatch waiting = new CountDownLatch(2), release = new CountDownLatch(1);
            try (Fixture fixture = new Fixture(socket -> { readLine(socket.getInputStream()); release.await(3, TimeUnit.SECONDS); });
                 BrokerClient client = fixture.client(BrokerClient.Role.RECEIVER)) {
                ExecutorService consumers = Executors.newFixedThreadPool(2);
                try {
                    List<Future<?>> futures = new java.util.ArrayList<>();
                    for (int i = 0; i < 2; i++) futures.add(consumers.submit(() -> {
                        waiting.countDown();
                        try { client.receive(1, Duration.ofSeconds(20)); throw new AssertionError("Closed receive should fail"); }
                        catch (IOException expected) { }
                        catch (InterruptedException e) { throw new RuntimeException(e); }
                    }));
                    check(waiting.await(1, TimeUnit.SECONDS), "Consumers ready");
                    client.close(); client.close();
                    for (Future<?> future : futures) future.get(1, TimeUnit.SECONDS);
                } finally { consumers.shutdownNow(); consumers.awaitTermination(1, TimeUnit.SECONDS); release.countDown(); }
            } finally { release.countDown(); }
            check(Main.countLabThreads() == 0, "No client reader threads remain");
        });
        test("Concurrent receive gives disjoint messages; interrupt is preserved", () -> {
            CountDownLatch release = new CountDownLatch(1);
            try (Fixture fixture = new Fixture(socket -> {
                readLine(socket.getInputStream());
                for (int i = 0; i < 100; i++) {
                    byte[] body = ("id-" + i).getBytes(StandardCharsets.US_ASCII);
                    socket.getOutputStream().write(("message " + body.length + "\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body);
                }
                socket.getOutputStream().flush(); release.await(3, TimeUnit.SECONDS);
            }); BrokerClient client = fixture.client(BrokerClient.Role.RECEIVER)) {
                ExecutorService consumers = Executors.newFixedThreadPool(2);
                try {
                    Future<List<String>> first = consumers.submit(() -> client.receive(50, SECOND));
                    Future<List<String>> second = consumers.submit(() -> client.receive(50, SECOND));
                    Set<String> seen = new HashSet<>(first.get(2, TimeUnit.SECONDS));
                    check(seen.size() == 50, "First consumer unique");
                    for (String item : second.get(2, TimeUnit.SECONDS)) check(seen.add(item), "Disjoint consumers");
                    check(seen.size() == 100, "All messages consumed exactly once");
                    Future<Boolean> interrupted = consumers.submit(() -> {
                        Thread.currentThread().interrupt();
                        try { client.receive(1, SECOND); return false; }
                        catch (InterruptedException expected) { return true; }
                        catch (IOException e) { return false; }
                    });
                    check(interrupted.get(1, TimeUnit.SECONDS), "Interrupted caller exits receive");
                } finally { consumers.shutdownNow(); consumers.awaitTermination(1, TimeUnit.SECONDS); release.countDown(); }
            } finally { release.countDown(); }
        });
        for (int mode = 0; mode < 3; mode++) {
            int fault = mode;
            test("Scenario detects equal-count loss/duplicate, corrupted body, extra frame: " + mode,
                    () -> faultyDelivery(fault));
        }
        test("All 3 scenarios against local TCP broker", () -> {
            try (LocalBroker broker = new LocalBroker("127.0.0.1", 0)) {
                var runner = new ScenarioRunner(new ScenarioRunner.Options("127.0.0.1", broker.port(), Duration.ofSeconds(10), 17, "self"));
                for (int s = 1; s <= 3; s++) {
                    var result = runner.run(new ScenarioRunner.Plan("self-" + s, s, s == 3 ? 2 : 1, s, 1000, 256, true));
                    check(result.passed(), "Scenario " + s + ": " + result.error() + " counts=" + java.util.Arrays.toString(result.counts()) + " lost=" + result.lost());
                }
                check(broker.protocolErrors() == 0, "No broker protocol errors");
            }
            check(Main.countLabThreads() == 0, "No lab threads after broker.close");
        });
        System.out.println("SELFTEST PASS: " + passed + " checks");
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int b; (b = input.read()) != -1;) { if (b == '\n') return line.toString(StandardCharsets.US_ASCII); line.write(b); }
        throw new EOFException("Scripted peer expected complete header");
    }
    private static byte[] rawFrame(InputStream in) throws IOException {
        String header = readLine(in);
        int length = Integer.parseInt(header.substring("message ".length()));
        byte[] body = in.readNBytes(length);
        check(body.length == length, "Scripted broker received complete frame");
        return body;
    }
    private static void rawWrite(OutputStream out, byte[] body) throws IOException {
        out.write(("message " + body.length + "\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body); out.flush();
    }
    private static void faultyDelivery(int mode) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 10, java.net.InetAddress.getLoopbackAddress());
             ExecutorService worker = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(3000);
            Future<?> peer = worker.submit(() -> {
                try (Socket sender = server.accept()) {
                    sender.setSoTimeout(3000);
                    InputStream in = sender.getInputStream(); readLine(in);
                    byte[][] before = new byte[500][];
                    for (int i = 0; i < 500; i++) before[i] = rawFrame(in);
                    try (Socket receiver = server.accept()) {
                        receiver.setSoTimeout(3000); readLine(receiver.getInputStream());
                        for (int i = 0; i < 500; i++) {
                            byte[] body = before[mode == 0 && i == 1 ? 0 : i].clone();
                            if (mode == 1 && i == 0) body[body.length - 1] = '!';
                            rawWrite(receiver.getOutputStream(), body);
                        }
                        for (int i = 0; i < 500; i++) rawWrite(receiver.getOutputStream(), rawFrame(in));
                        if (mode == 2) rawWrite(receiver.getOutputStream(), before[0]);
                        // Hold connection until the runner closes it; EOF must not mask frame validation.
                        receiver.getInputStream().read();
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            var runner = new ScenarioRunner(new ScenarioRunner.Options("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(5), 71, "fault"));
            var result = runner.run(new ScenarioRunner.Plan("fault-" + mode, 1, 1, 1, 1000, 256, true));
            check(!result.passed(), "Fault must not pass");
            if (mode == 0) check(result.received() == 1000 && result.duplicates() == 1 && result.lost() == 1, "Equal counts cannot hide loss/duplication");
            if (mode == 1) check(result.corrupted() == 1 && result.received() == 1000, "Payload validation");
            if (mode == 2) check(result.received() == 1001 && result.duplicates() == 1, "Extra frame after expected total");
            peer.get(2, TimeUnit.SECONDS);
        }
    }
    private static void test(String name, Checked body) throws Exception {
        body.run(); passed++; System.out.println("PASS " + name);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void expect(Class<? extends Throwable> type, Checked body) throws Exception {
        try { body.run(); }
        catch (Throwable e) { if (type.isInstance(e)) return; throw new AssertionError("Expected " + type + ", got " + e, e); }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}
