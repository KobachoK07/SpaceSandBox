package spacesb.remake;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class DiscordRPC {
    private static final String CLIENT_ID = "1535259172675780818";
    private static final long START_TIME = System.currentTimeMillis() / 1000L;
    private static final long PID = ProcessHandle.current().pid();
    private static volatile boolean started = false;
    private static RandomAccessFile pipe;
    private static long nonceCounter = 1L;

    public static synchronized void start() {
        if (started) return;
        started = true;

        Thread rpcThread = new Thread(() -> {
            StringBuilder sb = new StringBuilder(512);
            byte[] header = new byte[8];
            ByteBuffer headerBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            byte[] outArray = new byte[2048];
            ByteBuffer outBuf = ByteBuffer.wrap(outArray).order(ByteOrder.LITTLE_ENDIAN);

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    pipe = openDiscordPipe();
                    if (pipe == null) {
                        Thread.sleep(10000L);
                        continue;
                    }

                    sendFrame(0, "{\"v\":1,\"client_id\":\"" + CLIENT_ID + "\"}", outArray, outBuf);
                    readFrame(header, headerBuf);

                    while (!Thread.currentThread().isInterrupted()) {
                        int planetsCount = SpaceEngine.planets.size();
                        int debrisCount = SpaceEngine.fragments.size() + SpaceEngine.particles.size();

                        sb.setLength(0);
                        sb.append("{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":").append(PID)
                                .append(",\"activity\":{\"details\":\"")
                                .append(SpaceEngine.isPaused ? "На паузе" : "В симуляции (" + SpaceUI.formatTimeScale(SpaceEngine.timeScale) + ")")
                                .append("\",\"state\":\"Планет: ").append(planetsCount).append(" | Объектов: ").append(debrisCount)
                                .append("\",\"timestamps\":{\"start\":").append(START_TIME)
                                .append("},\"assets\":{\"large_image\":\"logo\",\"large_text\":\"SpaceSB Remake\"},")
                                .append("\"buttons\":[{\"label\":\"Скачать на Modrinth\",\"url\":\"https://modrinth.com/mod/space-sandbox\"}]}")
                                .append("},\"nonce\":\"").append(nonceCounter++).append("\"}");

                        sendFrame(1, sb.toString(), outArray, outBuf);
                        readFrame(header, headerBuf);
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

    private static void sendFrame(int opcode, String jsonPayload, byte[] outArray, ByteBuffer outBuf) throws Exception {
        byte[] bytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
        outBuf.clear();
        outBuf.putInt(opcode);
        outBuf.putInt(bytes.length);
        outBuf.put(bytes);
        pipe.write(outArray, 0, 8 + bytes.length);
    }

    private static void readFrame(byte[] header, ByteBuffer headerBuf) throws Exception {
        pipe.readFully(header);
        headerBuf.clear();
        headerBuf.getInt();
        int length = headerBuf.getInt();
        if (length > 0 && length < 65536) {
            pipe.skipBytes(length);
        }
    }

    private static void closePipeQuietly() {
        if (pipe != null) {
            try { pipe.close(); } catch (Exception ignored) {}
            pipe = null;
        }
    }
}