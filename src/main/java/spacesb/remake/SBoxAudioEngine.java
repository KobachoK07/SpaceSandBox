package spacesb.remake;

import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public class SBoxAudioEngine {

    private static volatile boolean running = false;
    private static volatile float volume = 0.20f;
    private static Thread audioThread;

    private static final String[] KNOWN_TRACK_PATHS = {
            "assets/spacesb-remake/sounds/Sound.mp3",
            "assets/spacesb-remake/sounds/sound.mp3",
            "assets/spacesb-remake/sounds/music.mp3",
            "assets/spacesb-remake/sounds/Music.mp3",
            "assets/spacesb-remake/sounds/Sound.ogg",
            "assets/spacesb-remake/sounds/sound.ogg",
            "assets/spacesb-remake/sounds/music.ogg",
            "assets/spacesb-remake/sounds/Sound.wav",
            "assets/spacesb-remake/sounds/sound.wav",
            "assets/spacesb-remake/sounds/music.wav"
    };

    private static final Map<String, File> TRACK_SOURCES = new LinkedHashMap<>();

    public static synchronized void init() {
        if (running) return;
        volume = Math.max(0.0f, Math.min(1.0f, SpaceEngine.musicVolume));
        running = true;

        audioThread = new Thread(SBoxAudioEngine::audioLoop, "SBox-Universal-Audio");
        audioThread.setDaemon(true);
        audioThread.start();
    }

    public static void setMusicVolume(float newVol) {
        volume = Math.max(0.0f, Math.min(1.0f, newVol));
    }

    public static synchronized void shutdown() {
        running = false;
        if (audioThread != null) {
            audioThread.interrupt();
            audioThread = null;
        }
    }

    private static boolean isSupportedAudioExtension(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".wav");
    }

    private static File extractContainerFromUrl(URL url) {
        if (url == null) return null;
        try {
            String raw = URLDecoder.decode(url.toString(), StandardCharsets.UTF_8);
            while (raw.startsWith("union:") || raw.startsWith("jar:") || raw.startsWith("file:")) {
                if (raw.startsWith("union:")) raw = raw.substring(6);
                else if (raw.startsWith("jar:")) raw = raw.substring(4);
                else if (raw.startsWith("file:")) raw = raw.substring(5);
            }
            int bangIdx = raw.indexOf('!');
            if (bangIdx != -1) raw = raw.substring(0, bangIdx);
            raw = raw.replaceAll("#\\d+$", "");

            while (raw.startsWith("//")) raw = raw.substring(1);
            if (raw.length() >= 3 && raw.charAt(0) == '/' && Character.isLetter(raw.charAt(1)) && raw.charAt(2) == ':') {
                raw = raw.substring(1);
            }
            File f = new File(raw);
            if (f.exists()) return f;
        } catch (Exception ignored) {}
        return null;
    }

    private static void scanFileOrDirectory(File container) {
        if (container == null || !container.exists()) return;
        try {
            if (container.isFile() && container.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                try (JarFile jar = new JarFile(container)) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        if (entry.isDirectory()) continue;
                        String name = entry.getName().replace('\\', '/');
                        if (name.startsWith("/")) name = name.substring(1);
                        if (name.startsWith("assets/spacesb-remake/") && isSupportedAudioExtension(name)) {
                            TRACK_SOURCES.putIfAbsent(name, container);
                        }
                    }
                }
            } else if (container.isDirectory()) {
                Path rootPath = container.toPath();
                Path scanRoot = rootPath;
                Path cur = rootPath;
                while (cur != null && cur.getFileName() != null) {
                    if ("assets".equalsIgnoreCase(cur.getFileName().toString())) {
                        if (cur.getParent() != null) scanRoot = cur.getParent();
                        break;
                    }
                    cur = cur.getParent();
                }

                Path assetsTarget = scanRoot.resolve("assets").resolve("spacesb-remake");
                Path walkStart = Files.exists(assetsTarget) ? assetsTarget : scanRoot;
                final Path finalRoot = scanRoot;
                final File finalRootFile = scanRoot.toFile();

                try (Stream<Path> walk = Files.walk(walkStart, 8)) {
                    walk.filter(Files::isRegularFile).forEach(file -> {
                        String rel = finalRoot.relativize(file).toString().replace('\\', '/');
                        if (rel.startsWith("assets/spacesb-remake/") && isSupportedAudioExtension(rel)) {
                            TRACK_SOURCES.putIfAbsent(rel, finalRootFile);
                        }
                    });
                }
            }
        } catch (Exception ignored) {}
    }

    private static void scanProjectParents(File startDir) {
        File cur = startDir;
        for (int depth = 0; depth < 6 && cur != null; depth++) {
            scanFileOrDirectory(new File(cur, "src/main/resources"));
            scanFileOrDirectory(new File(cur, "build/resources/main"));
            scanFileOrDirectory(new File(cur, "resources/main"));
            File modsDir = new File(cur, "mods");
            if (modsDir.exists() && modsDir.isDirectory()) {
                File[] jars = modsDir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
                if (jars != null) {
                    for (File jar : jars) scanFileOrDirectory(jar);
                }
            }
            cur = cur.getParentFile();
        }
    }

    private static List<String> discoverAudioTracks() {
        TRACK_SOURCES.clear();

        for (String candidate : KNOWN_TRACK_PATHS) {
            byte[] data = readTrackBytesDirect(candidate);
            if (data != null && data.length > 64) {
                TRACK_SOURCES.putIfAbsent(candidate, null);
            }
        }

        try {
            URL selfUrl = SBoxAudioEngine.class.getResource("SBoxAudioEngine.class");
            File selfContainer = extractContainerFromUrl(selfUrl);
            if (selfContainer != null) {
                scanFileOrDirectory(selfContainer);
                if (selfContainer.isDirectory()) scanProjectParents(selfContainer);
                else if (selfContainer.getParentFile() != null) scanProjectParents(selfContainer.getParentFile());
            }
        } catch (Exception ignored) {}

        try {
            CodeSource src = SBoxAudioEngine.class.getProtectionDomain().getCodeSource();
            if (src != null && src.getLocation() != null) {
                File csFile = extractContainerFromUrl(src.getLocation());
                if (csFile != null) {
                    scanFileOrDirectory(csFile);
                    if (csFile.isDirectory()) scanProjectParents(csFile);
                }
            }
        } catch (Exception ignored) {}

        try {
            scanProjectParents(Paths.get("").toAbsolutePath().toFile());
        } catch (Exception ignored) {}

        return new ArrayList<>(TRACK_SOURCES.keySet());
    }

    private static byte[] readTrackBytesDirect(String trackPath) {
        String clean = trackPath.startsWith("/") ? trackPath.substring(1) : trackPath;
        byte[] bytes = SpaceUI.loadFileBytes(clean);
        if (bytes != null && bytes.length > 64) return bytes;

        try {
            ClassLoader ctxCl = Thread.currentThread().getContextClassLoader();
            if (ctxCl != null) {
                try (InputStream is = ctxCl.getResourceAsStream(clean)) {
                    if (is != null) return is.readAllBytes();
                }
            }
            try (InputStream is = SBoxAudioEngine.class.getClassLoader().getResourceAsStream(clean)) {
                if (is != null) return is.readAllBytes();
            }
            try (InputStream is = SBoxAudioEngine.class.getResourceAsStream("/" + clean)) {
                if (is != null) return is.readAllBytes();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static byte[] readTrackBytes(String trackPath) {
        byte[] direct = readTrackBytesDirect(trackPath);
        if (direct != null && direct.length > 64) return direct;

        String clean = trackPath.startsWith("/") ? trackPath.substring(1) : trackPath;
        File container = TRACK_SOURCES.get(trackPath);
        if (container != null && container.exists()) {
            try {
                if (container.isFile() && container.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    try (JarFile jar = new JarFile(container)) {
                        JarEntry entry = jar.getJarEntry(clean);
                        if (entry == null) {
                            Enumeration<JarEntry> en = jar.entries();
                            while (en.hasMoreElements()) {
                                JarEntry je = en.nextElement();
                                if (je.getName().equalsIgnoreCase(clean)) {
                                    entry = je;
                                    break;
                                }
                            }
                        }
                        if (entry != null) {
                            try (InputStream is = jar.getInputStream(entry)) {
                                return is.readAllBytes();
                            }
                        }
                    }
                } else if (container.isDirectory()) {
                    Path p = container.toPath().resolve(clean);
                    if (Files.exists(p)) return Files.readAllBytes(p);
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static void audioLoop() {
        List<String> tracks = discoverAudioTracks();
        if (tracks.isEmpty()) {
            System.err.println("[SBox Audio] Музыкальные файлы не найдены в ресурсах!");
            return;
        }
        System.out.println("[SBox Audio] Найдено треков: " + tracks);

        int idx = 0;
        while (running && !Thread.currentThread().isInterrupted()) {
            if (tracks.size() > 1 && idx == 0) {
                Collections.shuffle(tracks);
            }
            String trackPath = tracks.get(idx % tracks.size());
            idx = (idx + 1) % tracks.size();

            byte[] audioBytes = readTrackBytes(trackPath);
            if (audioBytes == null || audioBytes.length < 64) {
                try { Thread.sleep(1000L); } catch (InterruptedException e) { break; }
                continue;
            }

            String lower = trackPath.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".mp3")) {
                playMp3Universal(audioBytes);
            } else if (lower.endsWith(".ogg") || (audioBytes[0] == 'O' && audioBytes[1] == 'g' && audioBytes[2] == 'g' && audioBytes[3] == 'S')) {
                if (!playOggFromMemory(audioBytes)) {
                    playMp3Universal(audioBytes);
                }
            } else {
                if (!playWavFromMemory(audioBytes)) {
                    playMp3Universal(audioBytes);
                }
            }

            try {
                Thread.sleep(400L);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private static int findMp3AudioFrameOffset(byte[] mp3Bytes) {
        if (mp3Bytes.length > 10 && mp3Bytes[0] == 'I' && mp3Bytes[1] == 'D' && mp3Bytes[2] == '3') {
            int id3Size = ((mp3Bytes[6] & 0x7F) << 21)
                    | ((mp3Bytes[7] & 0x7F) << 14)
                    | ((mp3Bytes[8] & 0x7F) << 7)
                    | (mp3Bytes[9] & 0x7F);
            int totalHeader = 10 + id3Size;
            if (totalHeader > 0 && totalHeader < mp3Bytes.length - 64) {
                return totalHeader;
            }
        }
        return 0;
    }

    private static File writeTempMp3File(String fileName, byte[] mp3Bytes, int offset) {
        int len = mp3Bytes.length - offset;
        if (len <= 64) return null;

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String pub = System.getenv("PUBLIC");
            if (pub != null && !pub.isEmpty()) {
                try {
                    File pubFile = new File(pub, fileName);
                    try (FileOutputStream fos = new FileOutputStream(pubFile)) {
                        fos.write(mp3Bytes, offset, len);
                    }
                    if (pubFile.exists() && pubFile.length() > 64) return pubFile;
                } catch (Exception ignored) {}
            }
        }
        try {
            File tmp = new File(System.getProperty("java.io.tmpdir"), fileName);
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(mp3Bytes, offset, len);
            }
            if (tmp.exists() && tmp.length() > 64) return tmp;
        } catch (Exception ignored) {}

        try {
            File localTmp = new File(fileName);
            try (FileOutputStream fos = new FileOutputStream(localTmp)) {
                fos.write(mp3Bytes, offset, len);
            }
            return localTmp;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void playMp3Universal(byte[] mp3Bytes) {
        int strippedOffset = findMp3AudioFrameOffset(mp3Bytes);
        File cleanMp3 = writeTempMp3File("sbox_bgm_clean.mp3", mp3Bytes, strippedOffset);
        File rawMp3 = writeTempMp3File("sbox_bgm_raw.mp3", mp3Bytes, 0);

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            if (playMp3ViaWindowsMci(cleanMp3, rawMp3)) return;
        } else if (os.contains("mac") && rawMp3 != null) {
            if (playMp3ViaMacAfconvert(rawMp3)) return;
        }
        if (rawMp3 != null) {
            playMp3ViaExternalFfmpegOrMpg123(rawMp3);
        }
    }

    private static int sendMciCommandW(long mciAddr, String command, ByteBuffer retBuf, int retChars) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer cmdBuf = stack.UTF16(command);
            long cmdPtr = MemoryUtil.memAddress(cmdBuf);
            long retPtr = (retBuf != null) ? MemoryUtil.memAddress(retBuf) : 0L;
            return JNI.callPPPPI(cmdPtr, retPtr, (long) retChars, 0L, mciAddr);
        }
    }

    private static boolean tryOpenMciFile(long mciAddr, File file) {
        if (file == null || !file.exists()) return false;
        String path = file.getAbsolutePath();
        if (sendMciCommandW(mciAddr, "open \"" + path + "\" type mpegvideo alias sboxbgm", null, 0) == 0) {
            return true;
        }
        return sendMciCommandW(mciAddr, "open \"" + path + "\" alias sboxbgm", null, 0) == 0;
    }

    /**
     * Проигрывает MP3 через системный winmm.dll (MCI) напрямую через JNI.callPPPPI (без рефлексии).
     */
    private static boolean playMp3ViaWindowsMci(File cleanMp3, File rawMp3) {
        SharedLibrary winmm = null;
        ByteBuffer retBuf = null;
        long mciAddr = 0L;

        try {
            try {
                winmm = Library.loadNative(SBoxAudioEngine.class, "org.lwjgl", "winmm");
            } catch (Throwable t) {
                winmm = Library.loadNative(SBoxAudioEngine.class, "org.lwjgl", "winmm.dll");
            }
            mciAddr = winmm.getFunctionAddress("mciSendStringW");
            if (mciAddr == 0L) return false;

            sendMciCommandW(mciAddr, "close sboxbgm", null, 0);

            if (!tryOpenMciFile(mciAddr, cleanMp3) && !tryOpenMciFile(mciAddr, rawMp3)) {
                System.err.println("[SBox Audio] Не удалось открыть MP3 через MCI.");
                return false;
            }

            int initVol = Math.max(0, Math.min(1000, Math.round(volume * 1000.0f)));
            sendMciCommandW(mciAddr, "setaudio sboxbgm volume to " + initVol, null, 0);

            int playRes = sendMciCommandW(mciAddr, "play sboxbgm repeat", null, 0);
            if (playRes != 0) {
                playRes = sendMciCommandW(mciAddr, "play sboxbgm", null, 0);
                if (playRes != 0) return false;
            }

            // Повторно применяем громкость сразу после старта воспроизведения
            sendMciCommandW(mciAddr, "setaudio sboxbgm volume to " + initVol, null, 0);
            int lastVolInt = initVol;

            retBuf = MemoryUtil.memAlloc(256);
            while (running && !Thread.currentThread().isInterrupted()) {
                int targetVol = Math.max(0, Math.min(1000, Math.round(volume * 1000.0f)));
                if (targetVol != lastVolInt) {
                    sendMciCommandW(mciAddr, "setaudio sboxbgm volume to " + targetVol, null, 0);
                    lastVolInt = targetVol;
                }

                MemoryUtil.memSet(MemoryUtil.memAddress(retBuf), 0, 256);
                int stRes = sendMciCommandW(mciAddr, "status sboxbgm mode", retBuf, 120);
                if (stRes == 0) {
                    String mode = MemoryUtil.memUTF16Safe(MemoryUtil.memAddress(retBuf));
                    if (mode != null && mode.trim().toLowerCase(Locale.ROOT).startsWith("stopped")) {
                        sendMciCommandW(mciAddr, "seek sboxbgm to start", null, 0);
                        if (sendMciCommandW(mciAddr, "play sboxbgm repeat", null, 0) != 0) {
                            sendMciCommandW(mciAddr, "play sboxbgm", null, 0);
                        }
                        sendMciCommandW(mciAddr, "setaudio sboxbgm volume to " + lastVolInt, null, 0);
                    }
                }
                Thread.sleep(45L);
            }
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return true;
        } catch (Throwable t) {
            System.err.println("[SBox Audio] Ошибка MCI: " + t.getMessage());
            return false;
        } finally {
            if (mciAddr != 0L) {
                sendMciCommandW(mciAddr, "close sboxbgm", null, 0);
            }
            if (retBuf != null) MemoryUtil.memFree(retBuf);
            if (winmm != null) {
                try { winmm.free(); } catch (Exception ignored) {}
            }
        }
    }

    private static boolean playMp3ViaMacAfconvert(File mp3File) {
        try {
            File outWav = new File(System.getProperty("java.io.tmpdir"), "sbox_bgm_converted.wav");
            Process p = new ProcessBuilder("/usr/bin/afconvert", "-f", "WAVE", "-d", "LEI16@44100",
                    mp3File.getAbsolutePath(), outWav.getAbsolutePath()).start();
            p.waitFor();
            if (outWav.exists() && outWav.length() > 128) {
                byte[] wavBytes = Files.readAllBytes(outWav.toPath());
                return playWavFromMemory(wavBytes);
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean playMp3ViaExternalFfmpegOrMpg123(File mp3File) {
        String[][] commands = {
                {"ffmpeg", "-v", "quiet", "-i", mp3File.getAbsolutePath(), "-f", "s16le", "-ac", "2", "-ar", "44100", "-"},
                {"mpg123", "-q", "-s", "-r", "44100", "--stereo", mp3File.getAbsolutePath()}
        };
        for (String[] cmd : commands) {
            Process proc = null;
            SourceDataLine line = null;
            try {
                proc = new ProcessBuilder(cmd).start();
                AudioFormat format = new AudioFormat(44100.0f, 16, 2, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(format, 16384);
                line.start();

                InputStream in = proc.getInputStream();
                byte[] buf = new byte[8192];
                int read;
                boolean gotData = false;
                while (running && !Thread.currentThread().isInterrupted() && (read = in.read(buf)) != -1) {
                    gotData = true;
                    float curVol = volume;
                    for (int i = 0; i + 1 < read; i += 2) {
                        short sample = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
                        int scaled = Math.round(sample * curVol);
                        if (scaled > 32767) scaled = 32767;
                        else if (scaled < -32768) scaled = -32768;
                        buf[i] = (byte) (scaled & 0xFF);
                        buf[i + 1] = (byte) ((scaled >> 8) & 0xFF);
                    }
                    line.write(buf, 0, read - (read % 2));
                }
                line.drain();
                if (gotData) return true;
            } catch (Throwable ignored) {
            } finally {
                if (line != null) {
                    try { line.stop(); line.close(); } catch (Exception ignored) {}
                }
                if (proc != null) proc.destroyForcibly();
            }
        }
        return false;
    }

    private static boolean playOggFromMemory(byte[] oggBytes) {
        int offset = 0;
        int searchLimit = Math.min(oggBytes.length - 4, 65536);
        for (int i = 0; i < searchLimit; i++) {
            if (oggBytes[i] == 'O' && oggBytes[i + 1] == 'g' && oggBytes[i + 2] == 'g' && oggBytes[i + 3] == 'S') {
                offset = i;
                break;
            }
        }

        int len = oggBytes.length - offset;
        if (len <= 64) return false;

        ByteBuffer vorbisData = MemoryUtil.memAlloc(len);
        vorbisData.put(oggBytes, offset, len).flip();
        long decoder = 0L;
        SourceDataLine line = null;
        ShortBuffer pcmBuf = null;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer errorBuf = stack.mallocInt(1);
            decoder = STBVorbis.stb_vorbis_open_memory(vorbisData, errorBuf, null);
            if (decoder == 0L) return false;

            STBVorbisInfo info = STBVorbisInfo.malloc(stack);
            STBVorbis.stb_vorbis_get_info(decoder, info);
            int channels = info.channels();
            int sampleRate = info.sample_rate();
            if (channels <= 0 || sampleRate <= 0) return false;

            int outChannels = Math.min(2, channels);
            AudioFormat format = new AudioFormat((float) sampleRate, 16, outChannels, true, false);
            DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, format);
            line = (SourceDataLine) AudioSystem.getLine(lineInfo);
            line.open(format, 16384);
            line.start();

            int framesPerChunk = 2048;
            pcmBuf = MemoryUtil.memAllocShort(framesPerChunk * channels);
            byte[] outBytes = new byte[framesPerChunk * outChannels * 2];

            while (running && !Thread.currentThread().isInterrupted()) {
                pcmBuf.clear();
                int samplesPerChannel = STBVorbis.stb_vorbis_get_samples_short_interleaved(decoder, channels, pcmBuf);
                if (samplesPerChannel <= 0) break;

                float curVol = volume;
                int outByteIdx = 0;
                for (int s = 0; s < samplesPerChannel; s++) {
                    int base = s * channels;
                    for (int ch = 0; ch < outChannels; ch++) {
                        int scaled = Math.round(pcmBuf.get(base + ch) * curVol);
                        if (scaled > 32767) scaled = 32767;
                        else if (scaled < -32768) scaled = -32768;
                        outBytes[outByteIdx++] = (byte) (scaled & 0xFF);
                        outBytes[outByteIdx++] = (byte) ((scaled >> 8) & 0xFF);
                    }
                }
                line.write(outBytes, 0, outByteIdx);
            }
            line.drain();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (pcmBuf != null) MemoryUtil.memFree(pcmBuf);
            if (line != null) {
                try { line.stop(); line.close(); } catch (Exception ignored) {}
            }
            if (decoder != 0L) STBVorbis.stb_vorbis_close(decoder);
            MemoryUtil.memFree(vorbisData);
        }
    }

    private static boolean playWavFromMemory(byte[] wavBytes) {
        SourceDataLine line = null;
        try (InputStream rawIn = new ByteArrayInputStream(wavBytes);
             BufferedInputStream bufIn = new BufferedInputStream(rawIn);
             AudioInputStream ais = AudioSystem.getAudioInputStream(bufIn)) {

            AudioFormat baseFormat = ais.getFormat();
            int channels = Math.max(1, Math.min(2, baseFormat.getChannels()));
            float sampleRate = (baseFormat.getSampleRate() > 0) ? baseFormat.getSampleRate() : 44100.0f;

            AudioFormat pcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sampleRate,
                    16,
                    channels,
                    channels * 2,
                    sampleRate,
                    false
            );

            try (AudioInputStream pcmStream = AudioSystem.getAudioInputStream(pcmFormat, ais)) {
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, pcmFormat);
                line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(pcmFormat, 16384);
                line.start();

                byte[] buffer = new byte[8192];
                int bytesRead;
                while (running && !Thread.currentThread().isInterrupted()
                        && (bytesRead = pcmStream.read(buffer, 0, buffer.length)) != -1) {
                    float curVol = volume;
                    for (int i = 0; i + 1 < bytesRead; i += 2) {
                        short sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
                        int scaled = Math.round(sample * curVol);
                        if (scaled > 32767) scaled = 32767;
                        else if (scaled < -32768) scaled = -32768;
                        buffer[i] = (byte) (scaled & 0xFF);
                        buffer[i + 1] = (byte) ((scaled >> 8) & 0xFF);
                    }
                    line.write(buffer, 0, bytesRead);
                }
                line.drain();
                return true;
            }
        } catch (Throwable t) {
            return false;
        } finally {
            if (line != null) {
                try { line.stop(); line.close(); } catch (Exception ignored) {}
            }
        }
    }
}