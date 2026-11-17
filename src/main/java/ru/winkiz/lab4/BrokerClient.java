package ru.winkiz.lab4;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Thread-safe client for the lab's original, acknowledgement-free protocol. */
public final class BrokerClient implements AutoCloseable {
    public enum Role { SENDER, RECEIVER }
    private static final AtomicLong IDS = new AtomicLong();
    private static final int MAX_PENDING_MESSAGES = 100_000;
    private static final long MAX_PENDING_BYTES = 64L * 1024 * 1024;
    private final Role role;
    private final Socket socket = new Socket();
    private final OutputStream out;
    private final InputStream in;
    private final Object writeLock = new Object();
    private final ReentrantLock stateLock = new ReentrantLock();
    private final Condition changed = stateLock.newCondition();
    // Access to queue, pendingBytes, failure and closed is always under stateLock.
    private final ArrayDeque<Received> received = new ArrayDeque<>();
    private long pendingBytes;
    private IOException failure;
    private boolean closed;
    private final Thread reader;
    private final ScheduledThreadPoolExecutor writeWatchdog;
    private record Received(String text, int bytes) { }

    public BrokerClient(String host, int port, Role role, String queue) throws IOException {
        this(host, port, role, queue, true, Duration.ofSeconds(3));
    }

    public BrokerClient(String host, int port, Role role, String queue,
                        boolean tcpNoDelay, Duration connectTimeout) throws IOException {
        this.role = Objects.requireNonNull(role, "role");
        Protocol.validateQueue(queue);
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Host is required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be 1..65535");
        long millis = connectTimeout.toMillis();
        if (millis < 1 || millis > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid connect timeout");
        try {
            socket.connect(new InetSocketAddress(host, port), (int) millis);
            socket.setTcpNoDelay(tcpNoDelay);
            in = new BufferedInputStream(socket.getInputStream());
            out = new BufferedOutputStream(socket.getOutputStream());
            out.write(((role == Role.SENDER ? "send " : "receive ") + queue + "\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            reader = role == Role.RECEIVER
                    ? new Thread(this::readLoop, "broker-client-reader-" + IDS.incrementAndGet()) : null;
            writeWatchdog = role == Role.SENDER ? new ScheduledThreadPoolExecutor(1,
                    task -> new Thread(task, "broker-client-write-watchdog-" + IDS.incrementAndGet())) : null;
            if (writeWatchdog != null) writeWatchdog.setRemoveOnCancelPolicy(true);
            if (reader != null) reader.start();
        } catch (IOException | RuntimeException e) {
            try { socket.close(); } catch (IOException closing) { e.addSuppressed(closing); }
            if (e instanceof IOException) throw new IOException("Cannot connect/configure broker " + host + ":" + port, e);
            throw e;
        }
    }

    public void send(String message) throws IOException {
        if (role != Role.SENDER) throw new IllegalStateException("Only SENDER can send");
        byte[] bytes = Protocol.encode(message);
        synchronized (writeLock) {
            stateLock.lock();
            try { checkState(); } finally { stateLock.unlock(); }
            // Socket SO_TIMEOUT applies to reads only. A deadline closes a blocked write as well.
            java.util.concurrent.ScheduledFuture<?> deadline;
            try { deadline = writeWatchdog.schedule(() -> fail(new java.net.SocketTimeoutException("Send exceeded 10 seconds")), 10, TimeUnit.SECONDS); }
            catch (java.util.concurrent.RejectedExecutionException e) { throw new IOException("Client is closing", e); }
            try { Protocol.writeMessage(out, bytes); }
            catch (IOException e) { fail(e); throw e; }
            finally { deadline.cancel(false); }
        }
    }

    public List<String> receive(int count, Duration timeout) throws InterruptedException, IOException {
        if (role != Role.RECEIVER) throw new IllegalStateException("Only RECEIVER can receive");
        if (count < 0 || timeout == null || timeout.isNegative()) throw new IllegalArgumentException("Invalid count/timeout");
        long budget;
        try { budget = timeout.toNanos(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Timeout too large", e); }
        long start = System.nanoTime();
        List<String> result = new ArrayList<>();
        stateLock.lockInterruptibly();
        try {
            checkState();
            while (result.size() < count) {
                checkState();
                if (!received.isEmpty()) {
                    Received item = received.removeFirst();
                    pendingBytes -= item.bytes();
                    result.add(item.text());
                    changed.signalAll();
                    continue;
                }
                long remaining = budget - (System.nanoTime() - start);
                if (remaining <= 0) break;
                changed.awaitNanos(remaining);
            }
            return List.copyOf(result);
        } finally { stateLock.unlock(); }
    }

    private void readLoop() {
        try {
            for (;;) {
                byte[] bytes = Protocol.readPayload(in);
                String text = Protocol.decode(bytes);
                stateLock.lockInterruptibly();
                try {
                    // Bounded buffer: a slow consumer applies TCP backpressure, not unlimited allocation.
                    while (!closed && failure == null && (received.size() >= MAX_PENDING_MESSAGES
                            || pendingBytes + bytes.length > MAX_PENDING_BYTES)) changed.await();
                    if (closed || failure != null) return;
                    received.addLast(new Received(text, bytes.length));
                    pendingBytes += bytes.length;
                    changed.signalAll();
                } finally { stateLock.unlock(); }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(new IOException("Receiver interrupted", e));
        } catch (IOException | RuntimeException e) {
            fail(e instanceof IOException io ? io : new IOException("Background receive failed", e));
        }
    }

    private void checkState() throws IOException {
        if (failure != null) throw new IOException("Broker connection failed", failure);
        if (closed) throw new IOException("Client is closed");
    }

    private void fail(IOException error) {
        stateLock.lock();
        try {
            if (!closed && failure == null) failure = error;
            changed.signalAll();
        } finally { stateLock.unlock(); }
        try { socket.close(); } catch (IOException closing) { error.addSuppressed(closing); }
    }

    @Override public void close() throws IOException {
        stateLock.lock();
        try { closed = true; changed.signalAll(); }
        finally { stateLock.unlock(); }
        IOException error = null;
        boolean interrupted = false;
        if (reader != null && reader != Thread.currentThread() && reader.isAlive() && !socket.isClosed()) {
            // A receiver has no outgoing frames. FIN asks the server to deregister it;
            // a cooperating server then closes its side. This is TCP shutdown, not a delivery ACK.
            try { socket.shutdownOutput(); reader.join(500); }
            catch (InterruptedException e) { interrupted = true; }
            catch (IOException ignored) { /* Always follow with the unconditional socket.close below. */ }
        }
        try { socket.close(); } catch (IOException e) { error = e; }
        if (reader != null && reader != Thread.currentThread()) {
            reader.interrupt();
            long begin = System.nanoTime();
            while (reader.isAlive() && System.nanoTime() - begin < 3_000_000_000L) {
                try { reader.join(100); } catch (InterruptedException e) { interrupted = true; }
            }
            if (reader.isAlive()) error = new IOException("Receiver thread did not terminate");
        }
        if (writeWatchdog != null) {
            writeWatchdog.shutdownNow();
            long begin = System.nanoTime();
            while (!writeWatchdog.isTerminated() && System.nanoTime() - begin < 3_000_000_000L) {
                try { writeWatchdog.awaitTermination(100, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
            if (!writeWatchdog.isTerminated()) error = new IOException("Send watchdog did not terminate");
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (error != null) throw error;
    }
}
