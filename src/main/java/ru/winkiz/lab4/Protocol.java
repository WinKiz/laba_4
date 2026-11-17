package ru.winkiz.lab4;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** One byte-oriented reader for both header and body. No BufferedReader. */
final class Protocol {
    static final int MAX_MESSAGE_BYTES = 1024 * 1024;
    static final int MAX_HEADER_BYTES = 1024;

    private Protocol() { }

    static void validateQueue(String queue) {
        if (queue == null || !queue.matches("[A-Za-z0-9_-]+")
                || queue.length() > MAX_HEADER_BYTES - "receive ".length()) {
            throw new IllegalArgumentException("Invalid queue name (ASCII letters, digits, _ and - only)");
        }
    }

    static byte[] encode(String message) {
        if (message == null) throw new IllegalArgumentException("Message must not be null");
        // REPORT also rejects unpaired UTF-16 surrogates rather than silently replacing them.
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(message));
            if (encoded.remaining() == 0 || encoded.remaining() > MAX_MESSAGE_BYTES)
                throw new IllegalArgumentException("Message size must be 1..1048576 UTF-8 bytes");
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Message contains invalid Unicode", e);
        }
    }

    static void writeMessage(OutputStream out, byte[] payload) throws IOException {
        out.write(("message " + payload.length + "\n").getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (;;) {
            int b = in.read();
            if (b == -1) {
                if (line.size() == 0) return null;
                throw new EOFException("Connection closed inside header");
            }
            if (b == '\n') return line.toString(StandardCharsets.US_ASCII);
            if (b < 32 || b > 126) throw new IOException("Non-ASCII or control byte in header");
            if (line.size() == MAX_HEADER_BYTES) throw new IOException("Header exceeds 1024 bytes");
            line.write(b);
        }
    }

    static byte[] readPayload(InputStream in) throws IOException {
        String header = readLine(in);
        if (header == null) throw new EOFException("Broker closed connection");
        if (!header.matches("message [0-9]+")) throw new IOException("Invalid frame header: " + header);
        int length;
        try { length = Integer.parseInt(header.substring(8)); }
        catch (NumberFormatException e) { throw new IOException("Invalid payload length", e); }
        if (length <= 0 || length > MAX_MESSAGE_BYTES) throw new IOException("Payload length outside 1..1048576");
        byte[] body = in.readNBytes(length);
        if (body.length != length) throw new EOFException("Short payload: " + body.length + " of " + length);
        return body;
    }

    static String decode(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw new IOException("Invalid UTF-8 payload", e); }
    }
}
