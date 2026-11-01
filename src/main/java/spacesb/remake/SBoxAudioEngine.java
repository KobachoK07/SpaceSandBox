package spacesb.remake;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.*;

public class SBoxAudioEngine {

    private static long alcDevice = 0L;
    private static long alcContext = 0L;
    private static boolean ownContext = false;
    private static volatile boolean initialized = false;

    private static int musicBufferId = 0;
    private static int musicSourceId = 0;
    private static volatile float currentVolume = 0.20f;

    private interface MsAcm32 extends Library {
        MsAcm32 INSTANCE = Native.load("msacm32", MsAcm32.class);
        int acmStreamOpen(PointerByReference pahs, Pointer had, Pointer pwfxSrc, Pointer pwfxDst, Pointer pwfltr, Pointer dwCallback, Pointer dwInstance, int fdwOpen);
        int acmStreamPrepareHeader(Pointer has, Pointer pash, int fdwPrepare);
        int acmStreamConvert(Pointer has, Pointer pash, int fdwConvert);
        int acmStreamUnprepareHeader(Pointer has, Pointer pash, int fdwUnprepare);
        int acmStreamClose(Pointer has, int fdwClose);
    }

    private static final int[] BITRATES_V1_L3 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0};
    private static final int[] BITRATES_V2_L3 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0};
    private static final int[] SAMPLERATES_V1 = {44100, 48000, 32000, 0};
    private static final int[] SAMPLERATES_V2 = {22050, 24000, 16000, 0};
    private static final int[] SAMPLERATES_V25 = {11025, 12000, 8000, 0};

    public static void init() {
        if (initialized) return;
        initialized = true;
        currentVolume = SpaceEngine.musicVolume;

        Thread audioThread = new Thread(() -> {
            try {
                initOpenALBackend();
                byte[] mp3Bytes = loadMp3Resource();
                if (mp3Bytes == null || mp3Bytes.length < 128) {
                    System.err.println("[SBox Audio] MP3 файл не найден в assets/spacesb-remake/sounds/space_music.mp3");
                    return;
                }

                DecodedPcm pcm = decodeMp3ToPcm(mp3Bytes);
                if (pcm == null || pcm.pcmData == null || pcm.pcmData.remaining() == 0) {
                    System.err.println("[SBox Audio] Не удалось декодировать MP3 поток.");
                    return;
                }

                musicBufferId = alGenBuffers();
                int format = (pcm.channels == 1) ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;
                alBufferData(musicBufferId, format, pcm.pcmData, pcm.sampleRate);
                MemoryUtil.memFree(pcm.pcmData);

                musicSourceId = alGenSources();
                alSourcei(musicSourceId, AL_BUFFER, musicBufferId);
                alSourcei(musicSourceId, AL_LOOPING, AL_TRUE);
                alSourcei(musicSourceId, AL_SOURCE_RELATIVE, AL_TRUE);
                alSource3f(musicSourceId, AL_POSITION, 0.0f, 0.0f, 0.0f);
                alSourcef(musicSourceId, AL_PITCH, 1.0f);
                alSourcef(musicSourceId, AL_GAIN, currentVolume);

                if (currentVolume > 0.001f) {
                    alSourcePlay(musicSourceId);
                }
                System.out.println("[SBox Audio] OpenAL MP3 движок запущен (" + pcm.sampleRate + " Hz, " + pcm.channels + " ch).");
            } catch (Throwable t) {
                System.err.println("[SBox Audio] Ошибка запуска OpenAL: " + t.getMessage());
            }
        }, "SBox-OpenAL-Audio");
        audioThread.setDaemon(true);
        audioThread.start();
    }

    private static void initOpenALBackend() {
        long existingCtx = alcGetCurrentContext();
        if (existingCtx != 0L) {
            long dev = alcGetContextsDevice(existingCtx);
            ALCCapabilities alcCaps = ALC.createCapabilities(dev);
            AL.createCapabilities(alcCaps);
            return;
        }
        alcDevice = alcOpenDevice((ByteBuffer) null);
        if (alcDevice == 0L) throw new RuntimeException("Не удалось открыть аудиоустройство OpenAL");
        alcContext = alcCreateContext(alcDevice, (IntBuffer) null);
        if (alcContext == 0L) throw new RuntimeException("Не удалось создать контекст OpenAL");
        alcMakeContextCurrent(alcContext);
        ALCCapabilities alcCaps = ALC.createCapabilities(alcDevice);
        AL.createCapabilities(alcCaps);
        ownContext = true;
    }

    public static void setMusicVolume(float volume) {
        currentVolume = Math.max(0.0f, Math.min(1.0f, volume));
        SpaceEngine.musicVolume = currentVolume;
        if (musicSourceId != 0) {
            try {
                if (ownContext && alcContext != 0L && alcGetCurrentContext() == 0L) {
                    alcMakeContextCurrent(alcContext);
                }
                alSourcef(musicSourceId, AL_GAIN, currentVolume);
                int state = alGetSourcei(musicSourceId, AL_SOURCE_STATE);
                if (currentVolume > 0.001f && state != AL_PLAYING) {
                    alSourcePlay(musicSourceId);
                } else if (currentVolume <= 0.001f && state == AL_PLAYING) {
                    alSourcePause(musicSourceId);
                }
            } catch (Throwable ignored) {}
        }
    }

    public static void shutdown() {
        try {
            if (musicSourceId != 0) {
                alSourceStop(musicSourceId);
                alDeleteSources(musicSourceId);
                musicSourceId = 0;
            }
            if (musicBufferId != 0) {
                alDeleteBuffers(musicBufferId);
                musicBufferId = 0;
            }
            if (ownContext) {
                if (alcContext != 0L) alcDestroyContext(alcContext);
                if (alcDevice != 0L) alcCloseDevice(alcDevice);
            }
        } catch (Throwable ignored) {}
    }

    private static byte[] loadMp3Resource() {
        String[] candidates = {
                "assets/spacesb-remake/sounds/space_music.mp3",
                "assets/spacesb-remake/sounds/music.mp3",
                "assets/spacesb-remake/space_music.mp3"
        };
        for (String path : candidates) {
            byte[] data = SpaceUI.loadFileBytes(path);
            if (data != null && data.length > 128) return data;
        }
        String[] devDirs = {
                "src/main/resources/assets/spacesb-remake/sounds",
                "../src/main/resources/assets/spacesb-remake/sounds"
        };
        for (String dir : devDirs) {
            File folder = new File(dir);
            if (folder.exists() && folder.isDirectory()) {
                try (Stream<Path> paths = Files.walk(Paths.get(dir), 1)) {
                    Path mp3 = paths.filter(p -> p.toString().toLowerCase().endsWith(".mp3")).findFirst().orElse(null);
                    if (mp3 != null) return Files.readAllBytes(mp3);
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static class DecodedPcm {
        ByteBuffer pcmData;
        int sampleRate;
        int channels;
    }

    private static DecodedPcm decodeMp3ToPcm(byte[] mp3) {
        int offset = 0;
        if (mp3.length > 10 && mp3[0] == 'I' && mp3[1] == 'D' && mp3[2] == '3') {
            int id3Size = ((mp3[6] & 0x7F) << 21) | ((mp3[7] & 0x7F) << 14) | ((mp3[8] & 0x7F) << 7) | (mp3[9] & 0x7F);
            offset = Math.min(mp3.length - 4, id3Size + 10);
        }

        int sampleRate = 44100, channels = 2, bitrate = 128, frameSize = 417;
        for (int i = offset; i < mp3.length - 4; i++) {
            if ((mp3[i] & 0xFF) == 0xFF && (mp3[i + 1] & 0xE0) == 0xE0) {
                int b1 = mp3[i + 1] & 0xFF, b2 = mp3[i + 2] & 0xFF, b3 = mp3[i + 3] & 0xFF;
                int verBits = (b1 >> 3) & 3, layerBits = (b1 >> 1) & 3;
                int brIdx = (b2 >> 4) & 0x0F, srIdx = (b2 >> 2) & 3, pad = (b2 >> 1) & 1;
                if (verBits == 1 || layerBits != 1 || brIdx == 0 || brIdx == 15 || srIdx == 3) continue;

                int sr = (verBits == 3) ? SAMPLERATES_V1[srIdx] : (verBits == 2 ? SAMPLERATES_V2[srIdx] : SAMPLERATES_V25[srIdx]);
                int br = (verBits == 3) ? BITRATES_V1_L3[brIdx] : BITRATES_V2_L3[brIdx];
                if (sr <= 0 || br <= 0) continue;

                int fLen = ((verBits == 3 ? 144000 : 72000) * br) / sr + pad;
                if (i + 36 < mp3.length) {
                    boolean isXing = false;
                    for (int k = i + 4; k < Math.min(mp3.length - 4, i + fLen); k++) {
                        if ((mp3[k] == 'X' && mp3[k + 1] == 'i' && mp3[k + 2] == 'n' && mp3[k + 3] == 'g')
                                || (mp3[k] == 'I' && mp3[k + 1] == 'n' && mp3[k + 2] == 'f' && mp3[k + 3] == 'o')) {
                            isXing = true;
                            break;
                        }
                    }
                    if (isXing) {
                        i += Math.max(1, fLen - 1);
                        continue;
                    }
                }
                offset = i;
                sampleRate = sr;
                bitrate = br;
                channels = (((b3 >> 6) & 3) == 3) ? 1 : 2;
                frameSize = Math.max(24, fLen);
                break;
            }
        }

        int mp3PayloadLen = mp3.length - offset;
        if (mp3PayloadLen <= 0) return null;

        Memory srcFmt = new Memory(30);
        srcFmt.clear();
        srcFmt.setShort(0, (short) 0x0055);
        srcFmt.setShort(2, (short) channels);
        srcFmt.setInt(4, sampleRate);
        srcFmt.setInt(8, (bitrate * 1000) / 8);
        srcFmt.setShort(12, (short) 1);
        srcFmt.setShort(14, (short) 0);
        srcFmt.setShort(16, (short) 12);
        srcFmt.setShort(18, (short) 1);
        srcFmt.setInt(20, 2);
        srcFmt.setShort(24, (short) frameSize);
        srcFmt.setShort(26, (short) 1);
        srcFmt.setShort(28, (short) 0);

        Memory dstFmt = new Memory(18);
        dstFmt.clear();
        dstFmt.setShort(0, (short) 1);
        dstFmt.setShort(2, (short) channels);
        dstFmt.setInt(4, sampleRate);
        dstFmt.setInt(8, sampleRate * channels * 2);
        dstFmt.setShort(12, (short) (channels * 2));
        dstFmt.setShort(14, (short) 16);
        dstFmt.setShort(16, (short) 0);

        PointerByReference phStream = new PointerByReference();
        int res = MsAcm32.INSTANCE.acmStreamOpen(phStream, Pointer.NULL, srcFmt, dstFmt, Pointer.NULL, Pointer.NULL, Pointer.NULL, 0);
        if (res != 0) return null;

        Pointer hStream = phStream.getValue();
        int chunkInSize = 65536;
        int chunkOutSize = chunkInSize * 12;
        Memory inBuf = new Memory(chunkInSize);
        Memory outBuf = new Memory(chunkOutSize);

        int hdrSize = Native.POINTER_SIZE == 8 ? 124 : 84;
        Memory streamHdr = new Memory(hdrSize);
        streamHdr.clear();
        streamHdr.setInt(0, hdrSize);
        if (Native.POINTER_SIZE == 8) {
            streamHdr.setPointer(16, inBuf);
            streamHdr.setInt(24, chunkInSize);
            streamHdr.setPointer(40, outBuf);
            streamHdr.setInt(48, chunkOutSize);
        } else {
            streamHdr.setPointer(12, inBuf);
            streamHdr.setInt(16, chunkInSize);
            streamHdr.setPointer(28, outBuf);
            streamHdr.setInt(32, chunkOutSize);
        }

        if (MsAcm32.INSTANCE.acmStreamPrepareHeader(hStream, streamHdr, 0) != 0) {
            MsAcm32.INSTANCE.acmStreamClose(hStream, 0);
            return null;
        }

        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream(mp3PayloadLen * 6);
        int pos = offset;
        int leftover = 0;

        try {
            while (pos < mp3.length) {
                int toCopy = Math.min(chunkInSize - leftover, mp3.length - pos);
                inBuf.write(leftover, mp3, pos, toCopy);
                int validIn = leftover + toCopy;
                pos += toCopy;

                if (Native.POINTER_SIZE == 8) {
                    streamHdr.setInt(24, validIn);
                    streamHdr.setInt(28, 0);
                    streamHdr.setInt(52, 0);
                } else {
                    streamHdr.setInt(16, validIn);
                    streamHdr.setInt(20, 0);
                    streamHdr.setInt(36, 0);
                }

                int flags = (pos < mp3.length) ? 0x00000010 : 0x00000020;
                int conv = MsAcm32.INSTANCE.acmStreamConvert(hStream, streamHdr, flags);
                if (conv != 0) break;

                int usedIn = Native.POINTER_SIZE == 8 ? streamHdr.getInt(28) : streamHdr.getInt(20);
                int usedOut = Native.POINTER_SIZE == 8 ? streamHdr.getInt(52) : streamHdr.getInt(36);

                if (usedOut > 0) {
                    byte[] pcmChunk = outBuf.getByteArray(0, usedOut);
                    pcmOut.write(pcmChunk, 0, pcmChunk.length);
                }

                if (usedIn <= 0 && usedOut <= 0) break;
                leftover = Math.max(0, validIn - usedIn);
                if (leftover > 0 && usedIn > 0) {
                    byte[] rem = inBuf.getByteArray(usedIn, leftover);
                    inBuf.write(0, rem, 0, leftover);
                }
            }
        } finally {
            if (Native.POINTER_SIZE == 8) streamHdr.setInt(24, chunkInSize);
            else streamHdr.setInt(16, chunkInSize);
            MsAcm32.INSTANCE.acmStreamUnprepareHeader(hStream, streamHdr, 0);
            MsAcm32.INSTANCE.acmStreamClose(hStream, 0);
        }

        byte[] rawPcm = pcmOut.toByteArray();
        if (rawPcm.length == 0) return null;

        ByteBuffer directPcm = MemoryUtil.memAlloc(rawPcm.length);
        directPcm.put(rawPcm).flip();

        DecodedPcm result = new DecodedPcm();
        result.pcmData = directPcm;
        result.sampleRate = sampleRate;
        result.channels = channels;
        return result;
    }
}