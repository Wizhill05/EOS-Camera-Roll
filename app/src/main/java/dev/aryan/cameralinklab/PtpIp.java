package dev.aryan.cameralinklab;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import javax.net.SocketFactory;

/** A bounded diagnostic session; it never changes camera settings or deletes objects. */
public final class PtpIp implements AutoCloseable {
    private static final int MAX_FRAME = 1024 * 1024;
    private volatile Socket command;
    private volatile Socket events;
    private volatile boolean closed;
    private final SocketFactory factory;
    private final Consumer<String> log;
    private Thread eventThread;
    private int transaction;
    private boolean session;

    public PtpIp(SocketFactory factory, Consumer<String> log) {
        this.factory = factory;
        this.log = log;
    }

    public void connect(String host, int port, byte[] guid) throws IOException {
        if (guid.length != 16) throw new IOException("Initiator GUID must contain 16 bytes");
        log.accept("TCP: opening command channel to " + host + ":" + port);
        command = factory.createSocket();
        connectSocket(command, host, port, 90000);
        if (closed) throw new IOException("Cancelled");
        byte[] name = "Aryan Camera Link Lab\0".getBytes(StandardCharsets.UTF_16LE);
        ByteBuffer init = le(16 + name.length + 4);
        init.put(guid).put(name).putInt(0x00010000);
        write(command, 1, init.array());
        log.accept("PAIRING: approve the computer connection on the camera. Waiting up to 90 seconds.");
        Frame ack = read(command);
        if (ack.type == 5) throw new IOException("Camera rejected initialization, reason " + hexInt(ack.body));
        if (ack.type != 2 || ack.body.length < 24) throw new IOException("Invalid InitCommandAck");
        int number = wrap(ack.body).getInt();
        log.accept("PAIRING: command acknowledged, connection " + Integer.toUnsignedString(number));
        command.setSoTimeout(15000);
        events = factory.createSocket();
        connectSocket(events, host, port, 15000);
        write(events, 3, le(4).putInt(number).array());
        Frame eventAck = read(events);
        if (eventAck.type != 4) throw new IOException("Expected InitEventAck, received " + eventAck.type);
        log.accept("EVENT CHANNEL: acknowledged");
        eventThread = new Thread(() -> {
            try {
                while (!closed) {
                    Frame f = read(events);
                    if (f.type == 13) write(events, 14, new byte[0]);
                    else log.accept("EVENT: packet type " + f.type + ", " + f.body.length + " bytes");
                }
            } catch (IOException error) {
                if (!closed) log.accept("EVENT: " + error.getMessage());
            }
        }, "ptpip-events");
        events.setSoTimeout(0);
        eventThread.start();
    }

    public void diagnose() throws IOException {
        Result opened = operation(0x1002, 1);
        if (opened.code != 0x2001) throw new IOException("OpenSession response " + hex(opened.code));
        session = true;
        log.accept("SESSION: OpenSession succeeded");
        Result device = operation(0x1001);
        requireOk("GetDeviceInfo", device);
        log.accept("DEVICE: " + describeDevice(device.data));
        Result storage = operation(0x1004);
        if (storage.code == 0x2001) {
            ByteBuffer b = wrap(storage.data);
            if (b.remaining() < 4) throw new IOException("Truncated storage list");
            long count = Integer.toUnsignedLong(b.getInt());
            if (count > b.remaining() / 4) throw new IOException("Invalid storage count");
            log.accept("STORAGE: camera returned " + count + " storage IDs");
            for (int i = 0; i < count; i++) log.accept("Storage " + hex(b.getInt()));
        } else {
            log.accept("STORAGE: response " + hex(storage.code) + ". This probe does not yet initialize Canon remote mode; this is not proof the card is inaccessible.");
        }
        log.accept("PTP TEST PASSED: pairing, event channel and session work. File transfer is a separate test.");
    }

    private Result operation(int opcode, int... parameters) throws IOException {
        if (closed) throw new IOException("Cancelled");
        int id = opcode == 0x1002 ? 0 : ++transaction;
        ByteBuffer body = le(10 + 4 * parameters.length);
        body.putInt(1).putShort((short) opcode).putInt(id);
        for (int p : parameters) body.putInt(p);
        write(command, 6, body.array());
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        long declared = -1;
        boolean ended = false;
        for (int packets = 0; packets < 4096; packets++) {
            Frame f = read(command);
            ByteBuffer b = wrap(f.body);
            if (f.type == 13) { write(command, 14, new byte[0]); continue; }
            if (f.type == 7) {
                if (b.remaining() < 6) throw new IOException("Truncated operation response");
                int code = Short.toUnsignedInt(b.getShort());
                if (b.getInt() != id) throw new IOException("Mismatched response transaction");
                if (code == 0x2001 && declared >= 0 && (!ended || declared != data.size()))
                    throw new IOException("Incomplete PTP data phase");
                log.accept("Operation " + hex(opcode) + " -> " + hex(code) + ", data " + data.size() + " bytes");
                return new Result(code, data.toByteArray());
            }
            if (f.type == 9) {
                if (b.remaining() != 12 || b.getInt() != id || declared >= 0) throw new IOException("Invalid StartData");
                declared = b.getLong();
                if (declared < 0 || declared > MAX_FRAME) throw new IOException("Diagnostic data exceeds 1 MiB");
            } else if (f.type == 10 || f.type == 12) {
                if (b.remaining() < 4 || b.getInt() != id || declared < 0 || ended) throw new IOException("Invalid data transaction");
                if (data.size() + b.remaining() > declared) throw new IOException("Data exceeds declared length");
                data.write(f.body, 4, f.body.length - 4);
                if (f.type == 12) ended = true;
            } else throw new IOException("Unexpected command packet type " + f.type);
        }
        throw new IOException("Too many PTP packets");
    }

    private static void requireOk(String name, Result result) throws IOException {
        if (result.code != 0x2001) throw new IOException(name + " failed: " + hex(result.code));
    }

    static String describeDevice(byte[] data) throws IOException {
        try {
            ByteBuffer b = wrap(data);
            b.getShort(); b.getInt(); b.getShort(); string(b); b.getShort();
            for (int i = 0; i < 5; i++) {
                long n = Integer.toUnsignedLong(b.getInt());
                if (n > b.remaining() / 2) throw new IOException("Invalid device capability count");
                b.position(b.position() + (int)n * 2);
            }
            return string(b) + " / " + string(b) + " / firmware " + string(b);
        } catch (BufferUnderflowException | IllegalArgumentException e) {
            throw new IOException("Truncated DeviceInfo", e);
        }
    }

    private static String string(ByteBuffer b) {
        int n = Byte.toUnsignedInt(b.get());
        if (n == 0) return "";
        byte[] bytes = new byte[n * 2]; b.get(bytes);
        return new String(bytes, 0, bytes.length - 2, StandardCharsets.UTF_16LE);
    }

    static Frame read(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        byte[] header = new byte[8]; in.readFully(header);
        ByteBuffer b = wrap(header);
        long length = Integer.toUnsignedLong(b.getInt()); int type = b.getInt();
        if (length < 8 || length > MAX_FRAME) throw new IOException("Invalid PTP/IP frame length " + length);
        byte[] body = new byte[(int)length - 8]; in.readFully(body);
        return new Frame(type, body);
    }

    static void write(Socket socket, int type, byte[] body) throws IOException {
        ByteBuffer b = le(body.length + 8).putInt(body.length + 8).putInt(type).put(body);
        OutputStream out = socket.getOutputStream(); out.write(b.array()); out.flush();
    }

    private void connectSocket(Socket socket, String host, int port, int timeout) throws IOException {
        if (closed) { socket.close(); throw new IOException("Cancelled"); }
        socket.connect(new InetSocketAddress(host, port), 8000);
        socket.setSoTimeout(timeout);
    }

    public void finish() {
        if (session && !closed) {
            try { operation(0x1003); log.accept("SESSION: closed"); }
            catch (IOException e) { log.accept("CloseSession: " + e.getMessage()); }
        }
        close();
    }

    @Override public void close() {
        closed = true;
        for (Socket s : new Socket[]{command, events}) if (s != null) try { s.close(); } catch (IOException ignored) {}
    }
    static ByteBuffer le(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }
    static ByteBuffer wrap(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    static String hex(int value) { return String.format(Locale.US, "0x%04X", value); }
    private static String hexInt(byte[] data) { return data.length >= 4 ? hex(wrap(data).getInt()) : "missing"; }
    static final class Frame { final int type; final byte[] body; Frame(int t, byte[] b) { type=t; body=b; } }
    private static final class Result { final int code; final byte[] data; Result(int c, byte[] d) { code=c; data=d; } }
}
