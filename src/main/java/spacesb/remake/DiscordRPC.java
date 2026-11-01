package spacesb.remake;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class DiscordRPC {
    private static final String CLIENT_ID = "1535259172675780818";
    private static final long START_TIME = System.currentTimeMillis() / 1000L;
    private static volatile boolean started = false;
    private static RandomAccessFile pipe;

    public static synchronized void start() {
        if (started) return;
        started = true;

        Thread rpcThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    pipe = openDiscordPipe();
                    if (pipe == null) {
                        Thread.sleep(10000L);
                        continue;
                    }

                    String handshake = "{\"v\":1,\"client_id\":\"" + CLIENT_ID + "\"}";
                    sendFrame(0, handshake);
                    readFrame();

                    while (!Thread.currentThread().isInterrupted()) {
                        int planetsCount = SpaceEngine.planets != null ? SpaceEngine.planets.size() : 0;
                        int debrisCount = (SpaceEngine.fragments != null ? SpaceEngine.fragments.size() : 0)
                                + (SpaceEngine.particles != null ? SpaceEngine.particles.size() : 0);

                        String details = SpaceEngine.isPaused ? "На паузе" : "В симуляции (" + SpaceUI.formatTimeScale(SpaceEngine.timeScale) + ")";
                        String state = "Планет: " + planetsCount + " | Объектов: " + debrisCount;

                        String activity = "{"
                                + "\"cmd\":\"SET_ACTIVITY\","
                                + "\"args\":{"
                                + "\"pid\":" + ProcessHandle.current().pid() + ","
                                + "\"activity\":{"
                                + "\"details\":\"" + details + "\","
                                + "\"state\":\"" + state + "\","
                                + "\"timestamps\":{\"start\":" + START_TIME + "},"
                                + "\"assets\":{"
                                + "\"large_image\":\"logo\","
                                + "\"large_text\":\"SpaceSB Remake\""
                                + "},"
                                + "\"buttons\":["
                                + "{\"label\":\"Скачать на Modrinth\",\"url\":\"https://modrinth.com/mod/space-sandbox\"}"
                                + "]"
                                + "}"
                                + "},"
                                + "\"nonce\":\"" + UUID.randomUUID() + "\""
                                + "}";

                        sendFrame(1, activity);
                        readFrame();
                        Thread.sleep(10000L);
                    }
                } catch (Exception e) {
                    closePipeQuietly();
                    try {
                        Thread.sleep(10000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "SpaceSB-DiscordRPC");

        rpcThread.setDaemon(true);
        rpcThread.start();
    }

    private static RandomAccessFile openDiscordPipe() {
        for (int i = 0; i < 10; i++) {
            try {
                return new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw");
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static void sendFrame(int opcode, String jsonPayload) throws Exception {
        byte[] bytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(8 + bytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(opcode);
        buffer.putInt(bytes.length);
        buffer.put(bytes);
        pipe.write(buffer.array());
    }

    private static void readFrame() throws Exception {
        byte[] header = new byte[8];
        pipe.readFully(header);
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        buf.getInt(); // opcode
        int length = buf.getInt();
        if (length > 0 && length < 65536) {
            byte[] payload = new byte[length];
            pipe.readFully(payload);
        }
    }

    private static void closePipeQuietly() {
        if (pipe != null) {
            try { pipe.close(); } catch (Exception ignored) {}
            pipe = null;
        }
    }
}