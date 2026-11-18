package ru.winkiz.lab4;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.time.Duration;

/** Auxiliary development broker, NOT the teacher's server or the assessed client. */
public final class LocalBroker implements AutoCloseable {
    private final ServerSocket listener;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Mailbox> queues = new ConcurrentHashMap<>();
    private final ExecutorService workers;
    private final List<Thread> workerThreads = new CopyOnWriteArrayList<>();
    private volatile boolean closed;
    private final AtomicLong protocolErrors = new AtomicLong();

    private static final class Peer {
        final Socket socket;
        final OutputStream out;
        Peer(Socket socket, OutputStream out) { this.socket = socket; this.out = out; }
    }

    private static final class Mailbox {
        final ArrayDeque<byte[]> pending = new ArrayDeque<>();
        final ArrayDeque<Peer> receivers = new ArrayDeque<>();
        long pendingBytes;
    }

    public LocalBroker(String bind, int port) throws IOException {
        listener = new ServerSocket(port, 128, InetAddress.getByName(bind));
        AtomicLong ids = new AtomicLong();
        workers = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "broker-local-" + ids.incrementAndGet());
            workerThreads.add(thread);
            return thread;
        });
        workers.submit(this::acceptLoop);
    }

    public int port() { return listener.getLocalPort(); }
    public long protocolErrors() { return protocolErrors.get(); }

    private void acceptLoop() {
        try {
            while (!closed) {
                Socket socket = listener.accept();
                sockets.add(socket);
                workers.submit(() -> session(socket));
            }
        } catch (IOException e) {
            if (!closed) { protocolErrors.incrementAndGet(); System.err.println("Local broker accept: " + e); }
        }
    }

    private Mailbox newMailbox(String ignored) {
        Mailbox box = new Mailbox();
        workers.submit(() -> dispatch(box));
        return box;
    }

    private void session(Socket socket) {
        Mailbox box = null;
        Peer peer = null;
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(3000);
            var in = new BufferedInputStream(socket.getInputStream());
            var out = new BufferedOutputStream(socket.getOutputStream());
            String setup = Protocol.readLine(in);
            if (setup == null || !setup.matches("(send|receive) [A-Za-z0-9_-]+"))
                throw new IOException("Invalid setup line");
            String[] parts = setup.split(" ");
            Protocol.validateQueue(parts[1]);
            if (queues.size() >= 1000 && !queues.containsKey(parts[1])) throw new IOException("Queue limit reached");
            box = queues.computeIfAbsent(parts[1], this::newMailbox);
            socket.setSoTimeout(0);
            if (parts[0].equals("send")) {
                for (;;) {
                    // EOF at a frame boundary is a normal sender disconnect.
                    in.mark(1);
                    if (in.read() == -1) break;
                    in.reset();
                    byte[] body = Protocol.readPayload(in);
                    synchronized (box) {
                        while (!closed && box.pendingBytes + body.length > 64L * 1024 * 1024) box.wait();
                        if (closed) return;
                        box.pending.addLast(body);
                        box.pendingBytes += body.length;
                        box.notifyAll();
                    }
                }
            } else {
                peer = new Peer(socket, out);
                synchronized (box) { box.receivers.addLast(peer); box.notifyAll(); }
                // Receiver sends nothing after setup. Reading detects its close, not an ACK.
                if (in.read() != -1) throw new IOException("Receiver sent unexpected data");
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (IOException | IllegalArgumentException e) {
            if (!closed && !(e instanceof java.net.SocketException)) {
                protocolErrors.incrementAndGet();
                System.err.println("Local broker session: " + e.getMessage());
            }
        } finally {
            if (box != null && peer != null) synchronized (box) { box.receivers.remove(peer); box.notifyAll(); }
            // Remove the receiver BEFORE closing: peer EOF then confirms deregistration on this broker.
            try { socket.close(); } catch (IOException e) {
                if (!closed) { protocolErrors.incrementAndGet(); System.err.println("Local broker close: " + e); }
            }
            sockets.remove(socket);
        }
    }

    private void dispatch(Mailbox box) {
        try {
            while (!closed) {
                synchronized (box) {
                    while (!closed && (box.pending.isEmpty() || box.receivers.isEmpty())) box.wait();
                    if (closed) return;
                    Peer peer = box.receivers.removeFirst();
                    byte[] body = box.pending.peekFirst();
                    try {
                        Protocol.writeMessage(peer.out, body);
                        box.pending.removeFirst();
                        box.pendingBytes -= body.length;
                        box.receivers.addLast(peer);
                    } catch (IOException e) {
                        // Failed writes retain the frame. Successful TCP writes are NOT delivery ACKs.
                        try { peer.socket.close(); } catch (IOException ignored) { }
                    }
                    box.notifyAll();
                }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        listener.close();
        IOException failure = null;
        for (Socket socket : new HashSet<>(sockets)) {
            try { socket.close(); } catch (IOException e) { failure = e; }
        }
        for (Mailbox box : queues.values()) synchronized (box) { box.notifyAll(); }
        if (!ThreadSupport.shutdown(workers, workerThreads, Duration.ofSeconds(5)))
            failure = new IOException("Local broker threads remain");
        if (failure != null) throw failure;
    }
}
