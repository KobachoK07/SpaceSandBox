package spacesb.remake;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class DiscordRPC {
    private static RandomAccessFile pipe;
    private static final long START_TIME = System.currentTimeMillis();

    public static void start() {
        new Thread(() -> {
            try {
                pipe = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-0", "rw");

                // ВАЖНО: Не забудь Client ID!
                String clientId = "1535259172675780818";
                String handshake = "{\"v\": 1, \"client_id\": \"" + clientId + "\"}";
                send(0, handshake);

                Thread.sleep(500);

                String activity = "{" +
                        "\"cmd\": \"SET_ACTIVITY\"," +
                        "\"args\": {" +
                        "\"pid\": " + ProcessHandle.current().pid() + "," +
                        "\"activity\": {" +
                        "\"details\": \"В симуляции\"," +
                        "\"timestamps\": {" +
                        "\"start\": " + START_TIME + // Таймер
                        "}," +
                        "\"assets\": {" +
                        "\"large_image\": \"logo\"," +
                        "\"large_text\": \"SpaceSB Remake\"" +
                        "}," +
                        "\"buttons\": [" +
                        "{\"label\": \"Скачать на Modrinth\", \"url\": \"https://modrinth.com/mod/space-sandbox\"}" +
                        "]" +
                        "}" +
                        "}," +
                        "\"nonce\": \"" + UUID.randomUUID().toString() + "\"" +
                        "}";

                send(1, activity);
                System.out.println("[SpaceSB] Discord RPC запущен. Кнопка выдана (проверь с другого аккаунта)!");

            } catch (Exception e) {
                System.out.println("[SpaceSB] Ошибка Discord RPC: " + e.getMessage());
            }
        }).start();
    }

    private static void send(int opcode, String payload) throws Exception {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(8 + bytes.length);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(opcode);
        buffer.putInt(bytes.length);
        buffer.put(bytes);
        pipe.write(buffer.array());
    }
}