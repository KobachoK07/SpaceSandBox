package spacesb.remake;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.stb.STBTTAlignedQuad;
import org.lwjgl.stb.STBTTBakedChar;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM;
import static spacesb.remake.SpaceEngine.*;

public class SpaceUI {

    public static final String ICON_PLANETS = "assets/spacesb-remake/textures/gui/planets.png";
    public static final String ICON_MOONS = "assets/spacesb-remake/textures/gui/moons.png";
    public static final String ICON_STARS = "assets/spacesb-remake/textures/gui/stars.png";
    public static final String ICON_HOLES = "assets/spacesb-remake/textures/gui/holes.png";
    public static final String CURSOR_TEX = "assets/spacesb-remake/textures/gui/cursor.png";
    public static final String CURSOR_AIM_TEX = "assets/spacesb-remake/textures/gui/cursor1.png";
    public static final String ICON_FLASHLIGHT = "assets/spacesb-remake/textures/gui/flashlight.png";
    public static final String ICON_RAYS = "assets/spacesb-remake/textures/gui/rays.png";
    public static final String LOGO_TEX = "assets/spacesb-remake/textures/gui/mod_logo.png";
    public static final String MODRINTH_TEX = "assets/spacesb-remake/textures/gui/mod.png";
    public static final String TELEGRAM_TEX = "assets/spacesb-remake/textures/gui/tgg.png";
    public static final String DONATE_TEX = "assets/spacesb-remake/textures/gui/da.png";

    private static double editMenuAnimX = 0;
    private static double lastUiTime = 0;

    private static double animAddY = 0.0;
    private static double animToolsY = 0.0;

    private static boolean initNotif = false;
    private static boolean prevShowGrid = true;
    private static boolean prevShowTrails = true;
    private static boolean prevPhysicsEnabled = true;
    private static boolean prevMoveTool = false;
    private static boolean prevPaused = false;

    public static final int FONT_HEIGHT = 16;
    public static final int SMALL_FONT_HEIGHT = 12;
    public static boolean isFontLoaded = false;
    private static long fontDescriptor = -1;
    private static long smallFontDescriptor = -1;
    private static STBTTBakedChar.Buffer cdata;
    private static STBTTBakedChar.Buffer cdataSmall;
    private static final Map<String, Long> textures = new HashMap<>();

    public static void initGL() { }
    public static void setProjectionMatrix(Matrix4f mat) { }

    public static void drawQuad(float x1, float y1, float x2, float y2, float u1, float v1, float u2, float v2, float r, float g, float b, float a, int mode, long descSet) {
        spacesb.remake.render.SBoxVulkanUI.drawQuad(x1, y1, x2 - x1, y2 - y1, u1, v1, u2, v2, r, g, b, a, mode, descSet);
    }

    public static byte[] loadFileBytes(String path) {
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        try {
            InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(cleanPath);
            if (is != null) { byte[] d = is.readAllBytes(); is.close(); return d; }

            is = SpaceUI.class.getClassLoader().getResourceAsStream(cleanPath);
            if (is != null) { byte[] d = is.readAllBytes(); is.close(); return d; }

            String[] devPaths = { "src/main/resources/", "../src/main/resources/", "../../src/main/resources/" };
            for (String prefix : devPaths) {
                java.nio.file.Path p = Paths.get(prefix + cleanPath);
                if (Files.exists(p)) return Files.readAllBytes(p);
            }
        } catch (Exception e) {
            System.err.println("[SBox UI] Ошибка при чтении: " + cleanPath);
        }
        return null;
    }

    public static void initFont(String internalPath) {
        if (isFontLoaded) return;
        try {
            byte[] bytes = loadFileBytes(internalPath);
            if (bytes == null) { System.out.println("[SBox UI] ФАЙЛ ШРИФТА НЕ НАЙДЕН: " + internalPath); return; }

            ByteBuffer ttfBuffer = BufferUtils.createByteBuffer(bytes.length).put(bytes);
            ttfBuffer.flip();

            cdata = STBTTBakedChar.malloc(96);
            ByteBuffer bitmap = BufferUtils.createByteBuffer(512 * 512);
            int result = STBTruetype.stbtt_BakeFontBitmap(ttfBuffer, 16.0f, bitmap, 512, 512, 32, cdata);

            cdataSmall = STBTTBakedChar.malloc(96);
            ByteBuffer smallBitmap = BufferUtils.createByteBuffer(512 * 512);
            int resultSmall = STBTruetype.stbtt_BakeFontBitmap(ttfBuffer, 12.0f, smallBitmap, 512, 512, 32, cdataSmall);

            if (result > 0 && resultSmall > 0) {
                fontDescriptor = spacesb.remake.render.SBoxVulkanUI.uploadTexture(bitmap, 512, 512, VK_FORMAT_R8_UNORM);
                smallFontDescriptor = spacesb.remake.render.SBoxVulkanUI.uploadTexture(smallBitmap, 512, 512, VK_FORMAT_R8_UNORM);
                isFontLoaded = true;
            } else { System.out.println("[SBox UI] STBTT Ошибка генерации битмапа шрифта."); }
        } catch (Exception e) { System.out.println("[SBox UI] КРИТИЧЕСКИЙ Сбой шрифта: " + e.getMessage()); }
    }

    public static long getTexture(String path) {
        if (textures.containsKey(path)) return textures.get(path);
        try {
            byte[] bytes = loadFileBytes(path);
            if (bytes == null) { System.out.println("[SBox UI] КАРТИНКА НЕ НАЙДЕНА: " + path); return -1; }

            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(bytes));
            if (bi == null) { System.out.println("[SBox UI] IMAGE-IO НЕ СМОГ ДЕКОДИРОВАТЬ PNG: " + path); return -1; }

            int w = bi.getWidth(); int h = bi.getHeight();
            int[] pixels = bi.getRGB(0, 0, w, h, null, 0, w);

            ByteBuffer buffer = BufferUtils.createByteBuffer(w * h * 4);
            for (int i = 0; i < pixels.length; i++) {
                int p = pixels[i];
                buffer.put((byte) ((p >> 16) & 0xFF));
                buffer.put((byte) ((p >> 8) & 0xFF));
                buffer.put((byte) (p & 0xFF));
                buffer.put((byte) ((p >> 24) & 0xFF));
            }
            buffer.flip();

            long id = spacesb.remake.render.SBoxVulkanUI.uploadTexture(buffer, w, h, VK_FORMAT_R8G8B8A8_UNORM);
            textures.put(path, id);
            return id;
        } catch (Exception e) { System.out.println("[SBox UI] Ошибка загрузки текстуры: " + e.getMessage()); return -1; }
    }

    public static void preloadAllResources() {
        initFont("assets/spacesb-remake/fonts/minecraft.ttf");
        getTexture(ICON_PLANETS); getTexture(ICON_MOONS); getTexture(ICON_STARS); getTexture(ICON_HOLES);
        getTexture(CURSOR_TEX); getTexture(CURSOR_AIM_TEX); getTexture(ICON_FLASHLIGHT); getTexture(ICON_RAYS);
        getTexture(LOGO_TEX); getTexture(MODRINTH_TEX); getTexture(TELEGRAM_TEX); getTexture(DONATE_TEX);
        for (MenuItem item : menuItems) {
            if (item.guiTex != null && !item.guiTex.isEmpty()) getTexture(item.guiTex);
        }
        System.out.println("[SBox UI] Все UI текстуры успешно предзагружены в GPU.");
    }

    public static void fill(int x1, int y1, int x2, int y2, int color) {
        float a = (color >> 24 & 255) / 255.0f; float r = (color >> 16 & 255) / 255.0f;
        float g = (color >> 8 & 255) / 255.0f; float b = (color & 255) / 255.0f;
        if (a == 0) a = 1.0f;
        drawQuad(x1, y1, x2, y2, 0, 0, 0, 0, r, g, b, a, 0, -1);
    }

    public static void drawOutline(int x, int y, int width, int height, int color) {
        fill(x, y, x + width, y + 1, color); fill(x, y + height - 1, x + width, y + height, color);
        fill(x, y, x + 1, y + height, color); fill(x + width - 1, y, x + width, y + height, color);
    }

    public static void drawText(String text, float x, float y, int color) {
        float r = (color >> 16 & 255) / 255.0f; float g = (color >> 8 & 255) / 255.0f;
        float b = (color & 255) / 255.0f; float a = (color >> 24 & 255) / 255.0f; if (a == 0) a = 1.0f;
        if (!isFontLoaded) {
            float curX = x;
            for (int i = 0; i < text.length(); i++) { fill((int)curX, (int)y, (int)curX + 6, (int)y + 12, color); curX += 8; }
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer xb = stack.floats((int)x); FloatBuffer yb = stack.floats((int)(y + 14));
            STBTTAlignedQuad q = STBTTAlignedQuad.malloc(stack);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i); if (c < 32 || c >= 128) continue;
                STBTruetype.stbtt_GetBakedQuad(cdata, 512, 512, c - 32, xb, yb, q, true);
                drawQuad(Math.round(q.x0()), Math.round(q.y0()), Math.round(q.x1()), Math.round(q.y1()),
                        q.s0(), q.t0(), q.s1(), q.t1(), r, g, b, a, 2, fontDescriptor);
            }
        }
    }

    public static void drawSmallText(String text, float x, float y, int color) {
        float r = (color >> 16 & 255) / 255.0f; float g = (color >> 8 & 255) / 255.0f;
        float b = (color & 255) / 255.0f; float a = (color >> 24 & 255) / 255.0f; if (a == 0) a = 1.0f;
        if (!isFontLoaded || smallFontDescriptor == -1) {
            drawText(text, x, y, color);
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer xb = stack.floats((int)x); FloatBuffer yb = stack.floats((int)(y + 10));
            STBTTAlignedQuad q = STBTTAlignedQuad.malloc(stack);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i); if (c < 32 || c >= 128) continue;
                STBTruetype.stbtt_GetBakedQuad(cdataSmall, 512, 512, c - 32, xb, yb, q, true);
                drawQuad(Math.round(q.x0()), Math.round(q.y0()), Math.round(q.x1()), Math.round(q.y1()),
                        q.s0(), q.t0(), q.s1(), q.t1(), r, g, b, a, 2, smallFontDescriptor);
            }
        }
    }

    public static void drawRotatedSmallText(String text, float centerX, float centerY, float angleRad, int color) {
        if (!isFontLoaded || smallFontDescriptor == -1) return;
        float r = (color >> 16 & 255) / 255.0f;
        float g = (color >> 8 & 255) / 255.0f;
        float b = (color & 255) / 255.0f;
        float a = (color >> 24 & 255) / 255.0f;
        if (a == 0) a = 1.0f;

        float halfW = getSmallTextWidth(text) * 0.5f;
        float cos = (float) Math.cos(angleRad);
        float sin = (float) Math.sin(angleRad);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer xb = stack.floats(-halfW);
            FloatBuffer yb = stack.floats(4.0f);
            STBTTAlignedQuad q = STBTTAlignedQuad.malloc(stack);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c < 32 || c >= 128) continue;
                STBTruetype.stbtt_GetBakedQuad(cdataSmall, 512, 512, c - 32, xb, yb, q, true);

                float lx0 = q.x0(), ly0 = q.y0();
                float lx1 = q.x1(), ly1 = q.y1();

                float rx0 = centerX + (lx0 * cos - ly0 * sin);
                float ry0 = centerY + (lx0 * sin + ly0 * cos);

                float rx1 = centerX + (lx0 * cos - ly1 * sin);
                float ry1 = centerY + (lx0 * sin + ly1 * cos);

                float rx2 = centerX + (lx1 * cos - ly1 * sin);
                float ry2 = centerY + (lx1 * sin + ly1 * cos);

                float rx3 = centerX + (lx1 * cos - ly0 * sin);
                float ry3 = centerY + (lx1 * sin + ly0 * cos);

                spacesb.remake.render.SBoxVulkanUI.drawQuadVertices(
                        rx0, ry0, q.s0(), q.t0(),
                        rx1, ry1, q.s0(), q.t1(),
                        rx2, ry2, q.s1(), q.t1(),
                        rx3, ry3, q.s1(), q.t0(),
                        r, g, b, a, 2, smallFontDescriptor
                );
            }
        }
    }

    public static int getTextWidth(String text) {
        if (!isFontLoaded) return text.length() * 8;
        float w = 0;
        for (int i = 0; i < text.length(); i++) { char c = text.charAt(i); if (c >= 32 && c < 128) w += cdata.get(c - 32).xadvance(); }
        return (int) w;
    }

    public static int getSmallTextWidth(String text) {
        if (!isFontLoaded || cdataSmall == null) return text.length() * 6;
        float w = 0;
        for (int i = 0; i < text.length(); i++) { char c = text.charAt(i); if (c >= 32 && c < 128) w += cdataSmall.get(c - 32).xadvance(); }
        return (int) w;
    }

    public static void drawIcon(String path, int x, int y, int w, int h, int color) {
        long id = getTexture(path);
        if (id == -1) {
            fill(x, y, x + w/2, y + h/2, 0xFFFF00FF); fill(x + w/2, y, x + w, y + h/2, 0xFF000000);
            fill(x, y + h/2, x + w/2, y + h, 0xFF000000); fill(x + w/2, y + h/2, x + w, y + h, 0xFFFF00FF);
            return;
        }
        float a = (color >> 24 & 255) / 255.0f; float r = (color >> 16 & 255) / 255.0f;
        float g = (color >> 8 & 255) / 255.0f; float b = (color & 255) / 255.0f; if (a == 0) a = 1.0f;
        drawQuad(x, y, x+w, y+h, 0, 0, 1, 1, r, g, b, a, 1, id);
    }
    public static void drawIcon(String path, int x, int y, int w, int h) { drawIcon(path, x, y, w, h, 0xFFFFFFFF); }

    public static int drawButton(String text, int x, int y, int padX, int padY, boolean isHighlight) {
        int btnW = getTextWidth(text) + padX * 2, btnH = FONT_HEIGHT + padY * 2;
        fill(x, y, x + btnW, y + btnH, isHighlight ? 0xFF111111 : 0xFF000000);
        drawOutline(x, y, btnW, btnH, isHighlight ? 0xFFFFFFFF : 0x80FFFFFF);
        drawText(text, x + padX, y + padY, 0xFFFFFFFF);
        return btnW;
    }

    public static void drawMenuToggle(String text, int x, int y, int w, int h, boolean isOn) {
        fill(x, y, x + w, y + h, isOn ? 0xFF153315 : 0xFF111111); drawOutline(x, y, w, h, isOn ? 0xFF55FF55 : 0xFF444444);
        drawText(text, x + 6, y + (h - FONT_HEIGHT) / 2 + 1, 0xFFFFFFFF);
        String state = isOn ? "ON" : "OFF"; int stateW = getTextWidth(state);
        drawText(state, x + w - stateW - 6, y + (h - FONT_HEIGHT) / 2 + 1, isOn ? 0xFF55FF55 : 0xFFAAAAAA);
    }

    public static void drawMenuAction(String text, int x, int y, int w, int h, int textColor) {
        fill(x, y, x + w, y + h, 0xFF220808); drawOutline(x, y, w, h, 0xFFFF5555);
        drawText(text, x + (w - getTextWidth(text)) / 2, y + (h - FONT_HEIGHT) / 2 + 1, textColor);
    }

    public static void drawTinyModeButton(String letter, int x, int y, int size, boolean active, boolean disabled) {
        int bgColor = disabled ? 0xFF111111 : (active ? 0xFF333333 : 0xFF080808);
        int outlineColor = disabled ? 0xFF2A2A2A : (active ? 0xFFFFFFFF : 0xFF555555);
        int textColor = disabled ? 0xFF555555 : (active ? 0xFFFFFFAA : 0xFFAAAAAA);
        fill(x, y, x + size, y + size, bgColor); drawOutline(x, y, size, size, outlineColor);
        int tx = x + (size - getTextWidth(letter)) / 2, ty = y + (size - FONT_HEIGHT) / 2 + 1;
        drawText(letter, tx, ty, textColor);
    }

    public static void addNotification(String text) { SpaceEngine.notifications.add(0, new Notification(text)); }

    public static String formatTimeScale(double scale) {
        if (scale < 60) return String.format(Locale.US, "%.1f sec/sec", scale);
        if (scale < 3600) return String.format(Locale.US, "%.1f min/sec", scale / 60);
        if (scale < 86400) return String.format(Locale.US, "%.1f hr/sec", scale / 3600);
        if (scale < 31536000) return String.format(Locale.US, "%.1f days/sec", scale / 86400);
        return formatCompact(scale / 31536000) + " yrs/sec";
    }

    public static String formatFlightSpeed(double speedKmS) {
        if (speedKmS >= 299792.458) return String.format(Locale.US, "%.2f c", speedKmS / 299792.458);
        else if (speedKmS >= 1.0) return String.format(Locale.US, "%.1f km/s", speedKmS);
        else {
            double speedMs = speedKmS * 1000.0;
            if (speedMs >= 1.0) return String.format(Locale.US, "%.1f m/s", speedMs);
            else return String.format(Locale.US, "%.1f km/h", speedKmS * 3600.0);
        }
    }

    public static String formatCompact(double v) {
        if (v == 0.0) return "0"; double abs = Math.abs(v);
        if (abs < 0.01) return String.format(Locale.US, "%.2e", v);
        if (abs >= 1e12) return String.format(Locale.US, "%.2e", v);
        if (abs >= 1e9) return String.format(Locale.US, "%.2f B", v / 1e9);
        if (abs >= 1e6) return String.format(Locale.US, "%.2f M", v / 1e6);
        if (abs >= 1e3) return String.format(Locale.US, "%.2f k", v / 1e3);
        return String.format(Locale.US, "%.2f", v);
    }

    public static String formatDistanceKm(double distKm) {
        double abs = Math.abs(distKm);
        if (abs >= 9.4607e12) return String.format(Locale.US, "%.2f ly", distKm / 9.4607e12);
        if (abs >= 1e12) return String.format(Locale.US, "%.2f T km", distKm / 1e12);
        if (abs >= 1e9) return String.format(Locale.US, "%.2f B km", distKm / 1e9);
        if (abs >= 1e6) return String.format(Locale.US, "%.2f M km", distKm / 1e6);
        if (abs >= 1e3) return String.format(Locale.US, "%.2f k km", distKm / 1e3);
        return String.format(Locale.US, "%.1f km", distKm);
    }

    private static boolean project3DSegment(
            Vector3d a, Vector3d b,
            Vector3d camPos, Vector3d forward, Vector3d right, Vector3d up,
            int sWidth, int sHeight, float[] outCoords) {
        double ax = a.x - camPos.x, ay = a.y - camPos.y, az = a.z - camPos.z;
        double bx = b.x - camPos.x, by = b.y - camPos.y, bz = b.z - camPos.z;

        double camZa = ax * forward.x + ay * forward.y + az * forward.z;
        double camZb = bx * forward.x + by * forward.y + bz * forward.z;

        final double zNear = 0.05;
        if (camZa <= zNear && camZb <= zNear) return false;

        double camXa = ax * right.x + ay * right.y + az * right.z;
        double camYa = ax * up.x + ay * up.y + az * up.z;

        double camXb = bx * right.x + by * right.y + bz * right.z;
        double camYb = bx * up.x + by * up.y + bz * up.z;

        if (camZa <= zNear) {
            double t = (zNear - camZa) / (camZb - camZa);
            camXa += t * (camXb - camXa);
            camYa += t * (camYb - camYa);
            camZa = zNear;
        } else if (camZb <= zNear) {
            double t = (zNear - camZb) / (camZa - camZb);
            camXb += t * (camXa - camXb);
            camYb += t * (camYa - camYb);
            camZb = zNear;
        }

        outCoords[0] = (float) (0.5 * sWidth + (camXa / camZa) * sHeight);
        outCoords[1] = (float) (0.5 * sHeight - (camYa / camZa) * sHeight);
        outCoords[2] = (float) (0.5 * sWidth + (camXb / camZb) * sHeight);
        outCoords[3] = (float) (0.5 * sHeight - (camYb / camZb) * sHeight);
        return true;
    }

    private static boolean is3DPointOccludedByPlanets(Vector3d pt, Vector3d camPos) {
        Vector3d ray = new Vector3d(pt).sub(camPos);
        double segLen = ray.length();
        if (segLen < 1e-5) return false;
        ray.div(segLen);

        for (Planet p : planets) {
            double r = p.radius;
            // 1. Если сама точка находится внутри куба планеты — скрываем её
            Vector3d relPt = new Vector3d(pt).sub(p.pos);
            if (relPt.lengthSquared() < 3.0 * r * r) {
                double tiltRad = Math.toRadians(p.tilt);
                double rotRad = Math.toRadians(p.currentAngle);
                relPt.rotateZ(-tiltRad).rotateY(-rotRad);
                if (Math.abs(relPt.x) <= r * 1.03 && Math.abs(relPt.y) <= r * 1.03 && Math.abs(relPt.z) <= r * 1.03) {
                    return true;
                }
            }

            // 2. Если луч от камеры до точки трейла пересекает куб планеты ближе, чем сама точка — скрываем её
            double tiltRad = Math.toRadians(p.tilt);
            double rotRad = Math.toRadians(p.currentAngle);
            Vector3d ro = new Vector3d(camPos).sub(p.pos).rotateZ(-tiltRad).rotateY(-rotRad);
            Vector3d rd = new Vector3d(ray).rotateZ(-tiltRad).rotateY(-rotRad);

            double ix = 1.0 / (Math.abs(rd.x) < 1e-7 ? 1e-7 : rd.x);
            double iy = 1.0 / (Math.abs(rd.y) < 1e-7 ? 1e-7 : rd.y);
            double iz = 1.0 / (Math.abs(rd.z) < 1e-7 ? 1e-7 : rd.z);

            double t0x = (-r - ro.x) * ix, t1x = (r - ro.x) * ix;
            double t0y = (-r - ro.y) * iy, t1y = (r - ro.y) * iy;
            double t0z = (-r - ro.z) * iz, t1z = (r - ro.z) * iz;

            double tNear = Math.max(Math.max(Math.min(t0x, t1x), Math.min(t0y, t1y)), Math.min(t0z, t1z));
            double tFar = Math.min(Math.min(Math.max(t0x, t1x), Math.max(t0y, t1y)), Math.max(t0z, t1z));

            if (tNear < tFar && tFar > 0.0) {
                double hitT = (tNear > 0.0) ? tNear : tFar;
                if (hitT < segLen - 0.02) {
                    return true;
                }
            }
        }
        return false;
    }

    // Отрисовка 3D-отрезка с отсечением участков, находящихся внутри или позади планет
    private static void drawOccluded3DSegment(
            Vector3d a, Vector3d b,
            Vector3d camPos, Vector3d forward, Vector3d right, Vector3d up,
            int sWidth, int sHeight, float thickness, float r, float g, float bCol, float alpha, float[] segBuf) {
        boolean occA = is3DPointOccludedByPlanets(a, camPos);
        boolean occB = is3DPointOccludedByPlanets(b, camPos);
        if (occA && occB) return;

        if (!occA && !occB) {
            Vector3d mid = new Vector3d(a).add(b).mul(0.5);
            if (!is3DPointOccludedByPlanets(mid, camPos)) {
                if (project3DSegment(a, b, camPos, forward, right, up, sWidth, sHeight, segBuf)) {
                    spacesb.remake.render.SBoxVulkanUI.drawLine(segBuf[0], segBuf[1], segBuf[2], segBuf[3], thickness, r, g, bCol, alpha);
                }
                return;
            }
        }

        // Если отрезок частично входит в планету (например, последний сегмент трейла у поверхности) — разбиваем его на 6 микро-шагов
        final int SUB = 6;
        Vector3d p0 = new Vector3d();
        Vector3d p1 = new Vector3d();
        for (int k = 0; k < SUB; k++) {
            double t0 = (double) k / SUB;
            double t1 = (double) (k + 1) / SUB;
            p0.set(a).lerp(b, t0);
            p1.set(a).lerp(b, t1);
            if (!is3DPointOccludedByPlanets(p0, camPos) && !is3DPointOccludedByPlanets(p1, camPos)) {
                if (project3DSegment(p0, p1, camPos, forward, right, up, sWidth, sHeight, segBuf)) {
                    spacesb.remake.render.SBoxVulkanUI.drawLine(segBuf[0], segBuf[1], segBuf[2], segBuf[3], thickness, r, g, bCol, alpha);
                }
            }
        }
    }

    private static void render3DOverlays(int sWidth, int sHeight) {
        double pitch = Math.toRadians(CameraManager.rot.x);
        double yaw = Math.toRadians(CameraManager.rot.y);

        Vector3d forward = new Vector3d(
                -Math.sin(yaw) * Math.cos(pitch),
                -Math.sin(pitch),
                -Math.cos(yaw) * Math.cos(pitch)
        ).normalize();
        Vector3d right = new Vector3d(forward).cross(0.0, 1.0, 0.0).normalize();
        Vector3d up = new Vector3d(right).cross(forward).normalize();
        Vector3d camPos = CameraManager.pos;

        float[] seg = new float[4];

        // 1. Рендер орбитальных следов (Show Trails) с 3D-окклюзией планетами
        if (showTrails) {
            for (Planet p : planets) {
                int n = p.trail.size();
                if (n < 2) continue;
                float r = p.rColor / 255.0f;
                float g = p.gColor / 255.0f;
                float b = p.bColor / 255.0f;
                for (int i = 0; i < n - 1; i++) {
                    Vector3d p0 = p.trail.get(i);
                    Vector3d p1 = p.trail.get(i + 1);
                    float alpha = ((float) (i + 1) / n) * 0.65f;
                    drawOccluded3DSegment(p0, p1, camPos, forward, right, up, sWidth, sHeight, 1.0f, r, g, b, alpha, seg);
                }
                Vector3d last = p.trail.get(n - 1);
                drawOccluded3DSegment(last, p.pos, camPos, forward, right, up, sWidth, sHeight, 1.0f, r, g, b, 0.65f, seg);
            }
        }

        // 2. Рендер кольца орбиты, прямой от планеты до планеты и динамического текста расстояния
        if (isPlacingPlanet && currentSpawnMode == SpawnMode.ORBIT && orbitTarget != null && planets.contains(orbitTarget)) {
            MenuItem item = menuItems[selectedPlanetIndex];
            Vector3d holoPos = getHologramPos(item.radius);
            Vector3d orbitUp = new Vector3d(0.0, 1.0, 0.0);

            if (isDraggingOrbit) {
                getInclinedOrbitState(baseOrbitPos, orbitInclination, orbitTarget, holoPos, orbitUp);
            }

            calculateSpawnVelocity(holoPos, new Vector3d(orbitUp));

            Vector3d center = orbitTarget.pos;
            Vector3d radial = new Vector3d(holoPos).sub(center);
            double orbitRadius = radial.length();

            if (orbitRadius > 0.01) {
                Vector3d tangent = new Vector3d(orbitUp).cross(radial);
                if (tangent.lengthSquared() < 1e-6) {
                    tangent.set(1.0, 0.0, 0.0).cross(radial);
                }
                tangent.normalize().mul(orbitRadius);

                int segments = 128;
                Vector3d prevPt = new Vector3d();
                Vector3d currPt = new Vector3d();
                for (int s = 0; s <= segments; s++) {
                    double theta = (s * 2.0 * Math.PI) / segments;
                    double cosT = Math.cos(theta);
                    double sinT = Math.sin(theta);
                    currPt.set(center)
                            .fma(cosT, radial)
                            .fma(sinT, tangent);
                    if (s > 0) {
                        drawOccluded3DSegment(prevPt, currPt, camPos, forward, right, up, sWidth, sHeight, 1.0f, 0.85f, 0.85f, 0.85f, 0.75f, seg);
                    }
                    prevPt.set(currPt);
                }

                // Прямая линия от поверхности центральной планеты до поверхности создаваемой планеты
                Vector3d dirNorm = new Vector3d(radial).div(orbitRadius);
                Vector3d lineStart = new Vector3d(center).fma(orbitTarget.radius * 1.05, dirNorm);
                Vector3d lineEnd = new Vector3d(holoPos).fma(-item.radius * 1.05, dirNorm);

                if (orbitRadius > orbitTarget.radius + item.radius) {
                    drawOccluded3DSegment(lineStart, lineEnd, camPos, forward, right, up, sWidth, sHeight, 1.15f, 0.95f, 0.95f, 0.95f, 0.9f, seg);
                }

                if (project3DSegment(center, holoPos, camPos, forward, right, up, sWidth, sHeight, seg)) {
                    Vector3d mid3D = new Vector3d(center).add(holoPos).mul(0.5);
                    double mx = mid3D.x - camPos.x, my = mid3D.y - camPos.y, mz = mid3D.z - camPos.z;
                    double mCamZ = mx * forward.x + my * forward.y + mz * forward.z;

                    float labelX, labelY;
                    if (mCamZ > 0.1) {
                        double mCamX = mx * right.x + my * right.y + mz * right.z;
                        double mCamY = mx * up.x + my * up.y + mz * up.z;
                        labelX = (float) (0.5 * sWidth + (mCamX / mCamZ) * sHeight);
                        labelY = (float) (0.5 * sHeight - (mCamY / mCamZ) * sHeight);
                    } else {
                        labelX = (seg[0] + seg[2]) * 0.5f;
                        labelY = (seg[1] + seg[3]) * 0.5f;
                    }

                    float dx = seg[2] - seg[0];
                    float dy = seg[3] - seg[1];
                    float angle = (float) Math.atan2(dy, dx);
                    if (angle > Math.PI * 0.5f || angle < -Math.PI * 0.5f) {
                        angle += (float) Math.PI;
                    }

                    float offsetDist = 8.0f;
                    labelX += (float) Math.sin(angle) * offsetDist;
                    labelY -= (float) Math.cos(angle) * offsetDist;

                    double distKm = orbitRadius * BLOCK_TO_KM;
                    String distLabel = formatDistanceKm(distKm);
                    drawRotatedSmallText(distLabel, labelX, labelY, angle, 0xFFFFFFFF);
                }
            }
        }
    }

    public static void onScroll(double yoffset) { }

    public static void renderDirectUI(long window, int sWidth, int sHeight, float uiScale) {
        if (menuItems == null || menuItems.length == 0) return;

        double currentRealTime = System.currentTimeMillis() / 1000.0;
        if (lastUiTime == 0) lastUiTime = currentRealTime;
        double dtUI = currentRealTime - lastUiTime;
        lastUiTime = currentRealTime;

        animAddY += ((isAddMenuOpen ? 1.0 : 0.0) - animAddY) * Math.min(1.0, dtUI * 18.0);
        animToolsY += ((isToolsMenuOpen ? 1.0 : 0.0) - animToolsY) * Math.min(1.0, dtUI * 18.0);

        if (!initNotif) { addNotification("SpaceSB Engine Active"); initNotif = true; }
        if (prevShowGrid != showGrid) { addNotification("Grid: " + (showGrid ? "ON" : "OFF")); prevShowGrid = showGrid; }
        if (prevShowTrails != showTrails) { addNotification("Trails: " + (showTrails ? "ON" : "OFF")); prevShowTrails = showTrails; }
        if (prevPhysicsEnabled != physicsEnabled) { addNotification("Physics: " + (physicsEnabled ? "ON" : "OFF")); prevPhysicsEnabled = physicsEnabled; }
        if (prevMoveTool != isMoveToolActive) { addNotification("Move Tool: " + (isMoveToolActive ? "ON" : "OFF")); prevMoveTool = isMoveToolActive; }
        if (prevPaused != isPaused) { addNotification(isPaused ? "Simulation Paused" : "Simulation Resumed"); prevPaused = isPaused; }

        if (!isWelcomeMenuOpen) {
            render3DOverlays(sWidth, sHeight);
        }

        int barHeight = 26, rectY = sHeight - barHeight;

        if (!CameraManager.isLocked) {
            long elapsedScroll = System.currentTimeMillis() - lastScrollTime;
            if (elapsedScroll < 2000 && !isWelcomeMenuOpen) {
                float alpha = elapsedScroll < 1500 ? 1.0f : 1.0f - (elapsedScroll - 1500) / 500.0f;
                int aInt = (int)(alpha * 255);
                if (aInt > 5) {
                    String speedTxt = "Speed: " + formatFlightSpeed(playerFlightSpeed);
                    int tw = getTextWidth(speedTxt);
                    drawText(speedTxt, sWidth / 2 - tw / 2, 25, (aInt << 24) | 0xFFFFFF);
                }
            }
        }

        if (showFpsOverlay) {
            String fpsText = "SpaceSB Independent Engine";
            String tickText = String.format(Locale.US, "Physics Tick: %.2f ms", lastPhysicsTickMs);
            String objectsText = "Planets: " + planets.size() + " | Particles: " + particles.size();
            fill(5, 5, 250, 65, 0x99000000);
            drawText(fpsText, 10, 10, 0x55FF55);
            drawText(tickText, 10, 30, lastPhysicsTickMs < 5.0 ? 0x55FF55 : 0xFFFF55);
            drawText(objectsText, 10, 50, 0xAAAAAA);
        }

        if (isWelcomeMenuOpen) {
            int panelW = 500, panelH = 345;
            int panelX = (sWidth - panelW) / 2, panelY = (sHeight - panelH) / 2;

            fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF050505);
            drawOutline(panelX, panelY, panelW, panelH, 0xFF333333);

            int logoW = 160, logoH = 58;
            int logoX = panelX + (panelW - logoW) / 2;
            int logoY = panelY + 18;
            drawIcon(LOGO_TEX, logoX, logoY, logoW, logoH);

            int warnY = logoY + logoH + 14;
            String warnTxt = "WARNING!";
            drawText(warnTxt, panelX + (panelW - getTextWidth(warnTxt)) / 2, warnY, 0xFFFF5555);

            int textY = warnY + 24;
            String[] lines = {
                    "This mod features realistic simulations of open space,",
                    "extreme cosmic scales, total darkness, and depictions",
                    "of colossal astronomical objects.",
                    "",
                    "If you suffer from astrophobia, megalophobia,",
                    "thalassophobia, or heightened anxiety, we strongly",
                    "advise against playing this mod.",
                    "",
                    "The gameplay can trigger severe panic attacks and",
                    "intense psychological distress; please do not put",
                    "your mental and physical health at risk."
            };
            for (String line : lines) {
                if (!line.isEmpty()) {
                    drawSmallText(line, panelX + (panelW - getSmallTextWidth(line)) / 2, textY, 0xFFAAAAAA);
                    textY += SMALL_FONT_HEIGHT + 4;
                } else {
                    textY += 8;
                }
            }

            int modBtnS = 24, gap = 14;
            SpaceEngine.modBtnX = panelX + 25;
            SpaceEngine.modBtnY = panelY + panelH - modBtnS - 20;
            SpaceEngine.tgBtnX = SpaceEngine.modBtnX + modBtnS + gap;
            SpaceEngine.tgBtnY = SpaceEngine.modBtnY;
            SpaceEngine.daBtnX = SpaceEngine.tgBtnX + modBtnS + gap;
            SpaceEngine.daBtnY = SpaceEngine.modBtnY;

            drawIcon(MODRINTH_TEX, SpaceEngine.modBtnX, SpaceEngine.modBtnY, modBtnS, modBtnS);
            drawIcon(TELEGRAM_TEX, SpaceEngine.tgBtnX, SpaceEngine.tgBtnY, modBtnS, modBtnS);
            drawIcon(DONATE_TEX, SpaceEngine.daBtnX, SpaceEngine.daBtnY, modBtnS, modBtnS);

            String closeTxt = "I Understand";
            SpaceEngine.closeBtnW = getTextWidth(closeTxt) + 30;
            SpaceEngine.closeBtnH = 30;
            SpaceEngine.closeBtnX = panelX + panelW - SpaceEngine.closeBtnW - 20;
            SpaceEngine.closeBtnY = panelY + panelH - SpaceEngine.closeBtnH - 17;

            fill(SpaceEngine.closeBtnX, SpaceEngine.closeBtnY, SpaceEngine.closeBtnX + SpaceEngine.closeBtnW, SpaceEngine.closeBtnY + SpaceEngine.closeBtnH, 0xFF111111);
            drawOutline(SpaceEngine.closeBtnX, SpaceEngine.closeBtnY, SpaceEngine.closeBtnW, SpaceEngine.closeBtnH, 0xFF555555);
            drawText(closeTxt, SpaceEngine.closeBtnX + 15, SpaceEngine.closeBtnY + 7, 0xFFFFFFFF);
        }

        if (CameraManager.isLocked && CameraManager.lockedTarget != null && !isWelcomeMenuOpen) {
            int panelW = 230;
            currentEditMenuW = panelW;
            double targetX = isEditMenuMinimized ? -panelW : 0;
            editMenuAnimX += (targetX - editMenuAnimX) * Math.min(1.0, dtUI * 15.0);
            int currentX = (int) Math.round(editMenuAnimX);
            currentEditMenuX = currentX;
            int panelH = rectY;

            fill(currentX, 0, currentX + panelW, panelH, 0xFF050505);
            fill(currentX + panelW - 1, 0, currentX + panelW, panelH, 0xFF2A2A2A);

            minBtnW = 16; minBtnH = 28; minBtnX = currentX + panelW; minBtnY = 10;
            fill(minBtnX, minBtnY, minBtnX + minBtnW, minBtnY + minBtnH, 0xFF050505);
            drawOutline(minBtnX, minBtnY, minBtnW, minBtnH, 0xFF2A2A2A);
            fill(minBtnX, minBtnY + 1, minBtnX + 1, minBtnY + minBtnH - 1, 0xFF050505);
            drawText(isEditMenuMinimized ? ">" : "<", minBtnX + 4, minBtnY + 6, 0xFFFFFFFF);

            Planet p = CameraManager.lockedTarget;

            // Кнопка Фонарик (индивидуально убирает тени у планеты в фокусе)
            flashBtnX = minBtnX + minBtnW + 4; flashBtnY = 10;
            drawIcon(ICON_FLASHLIGHT, flashBtnX, flashBtnY, flashBtnS, flashBtnS, p.flashlightOn ? 0xFFFFFFFF : 0xFF555555);

            // Кнопка Лучи (индивидуально включает/выключает лучи у звезды в фокусе)
            raysBtnX = flashBtnX + flashBtnS + 4; raysBtnY = 10;
            drawIcon(ICON_RAYS, raysBtnX, raysBtnY, raysBtnS, raysBtnS, p.raysOn ? 0xFFFFFFFF : 0xFF555555);

            drawText("Edit: " + p.name, currentX + 10, 14, 0xFFFFFFFF);

            if (!isEditMenuMinimized) {
                int headerH = 38;
                String[] labels = {"Mass", "Diameter", "Rotation", "Tilt", "Velocity", "Temp"};
                String[][] units = {{"Earths", "Suns", "kg"}, {"km", "Earths", "Suns"}, {"hours", "days", "years"}, {"deg"}, {"km/s", "m/s", "km/h", "c"}, {"C", "K", "F"}};
                int yOff = headerH + 8 - (int)editMenuScrollY;

                for (int i = 0; i < 6; i++) {
                    int blockH = (i < 3) ? 50 : 28;
                    if (yOff + blockH > headerH && yOff < panelH) {
                        fill(currentX + 6, yOff, currentX + panelW - 6, yOff + blockH, 0xFF111111);
                        drawOutline(currentX + 6, yOff, panelW - 12, blockH, 0xFF2A2A2A);
                        drawSmallText(labels[i] + ":", currentX + 12, yOff + 8, 0xFFAAAAAA);

                        int unitW = 48;
                        int unitX = currentX + panelW - unitW - 10;
                        int boxX = currentX + 74;
                        int boxW = unitX - boxX - 6;
                        int boxH = 16;
                        int boxY = yOff + 6;

                        editBoxHits[i][0] = boxX; editBoxHits[i][1] = boxY; editBoxHits[i][2] = boxW; editBoxHits[i][3] = boxH;
                        unitBoxHits[i][0] = unitX; unitBoxHits[i][1] = boxY; unitBoxHits[i][2] = unitW; unitBoxHits[i][3] = boxH;

                        boolean isActive = (activeEditField == i && isTypingPlanetEdit);
                        fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xFF080808);
                        drawOutline(boxX, boxY, boxW, boxH, isActive ? 0xFF55FF55 : 0xFF555555);

                        String valStr;
                        if (isActive) valStr = planetEditBuffer + "_";
                        else {
                            double v = 0; int u = fieldUnits[i];
                            if (i == 0) { if (u == 0) v = p.mass; else if (u == 1) v = p.mass / 333000.0; else if (u == 2) v = p.mass * 5.9722e24; }
                            else if (i == 1) { if (u == 0) v = p.radius * 2.0 * 1260.0; else if (u == 1) v = p.radius * 2.0 * 1260.0 / 12742.0; else if (u == 2) v = p.radius * 2.0 * 1260.0 / 1392700.0; }
                            else if (i == 2) { if (u == 0) v = p.rotationPeriod; else if (u == 1) v = p.rotationPeriod / 24.0; else if (u == 2) v = p.rotationPeriod / 8760.0; }
                            else if (i == 3) { v = p.tilt; }
                            else if (i == 4) { double len = p.vel.length(); if (u == 0) v = len * 1260.0; else if (u == 1) v = len * 1260000.0; else if (u == 2) v = len * 1260.0 * 3600.0; else if (u == 3) v = len * 1260.0 / 299792.458; }
                            else if (i == 5) { if (u == 0) v = p.temperature; else if (u == 1) v = p.temperature + 273.15; else if (u == 2) v = p.temperature * 1.8 + 32.0; }
                            valStr = formatCompact(v);
                        }

                        if (isActive) { while (getSmallTextWidth(valStr) > boxW - 6 && valStr.length() > 1) valStr = valStr.substring(1); }
                        drawSmallText(valStr, boxX + 4, boxY + 2, 0xFFFFFFFF);

                        fill(unitX, boxY, unitX + unitW, boxY + boxH, 0xFF222222);
                        drawOutline(unitX, boxY, unitW, boxH, 0xFF555555);
                        String uStr = units[i][fieldUnits[i]];
                        drawSmallText(uStr, unitX + (unitW - getSmallTextWidth(uStr)) / 2, boxY + 2, 0xFFAAAAAA);

                        if (i < 3) {
                            int mx = currentX + 12, my = yOff + 28;
                            String[] multLabels = {"x0.1", "x0.5", "x2", "x5", "x10"};
                            for (int j = 0; j < 5; j++) {
                                int mw = getSmallTextWidth(multLabels[j]) + 10;
                                int mh = 16;
                                multBoxHits[i][j][0] = mx; multBoxHits[i][j][1] = my; multBoxHits[i][j][2] = mw; multBoxHits[i][j][3] = mh;
                                fill(mx, my, mx + mw, my + mh, 0xFF1A1A1A);
                                drawOutline(mx, my, mw, mh, 0xFF444444);
                                drawSmallText(multLabels[j], mx + 5, my + 2, 0xFFFFFFFF);
                                mx += mw + 5;
                            }
                        }
                    }
                    yOff += blockH + 6;
                }

                int contentH = 290;
                if (contentH > panelH - headerH) {
                    int scrollTrackX = currentX + panelW - 4, scrollTrackY = headerH, scrollTrackH = panelH - headerH;
                    fill(scrollTrackX, scrollTrackY, scrollTrackX + 3, scrollTrackY + scrollTrackH, 0xFF111111);
                    int thumbH = Math.max(20, (int)((float)(panelH - headerH) / contentH * scrollTrackH));
                    int thumbY = scrollTrackY + (int)((editMenuScrollY / (float)(contentH - (panelH - headerH))) * (scrollTrackH - thumbH));
                    fill(scrollTrackX, thumbY, scrollTrackX + 3, thumbY + thumbH, 0xFF555555);
                }
            }
        }

        if (animToolsY > 0.01 && !isWelcomeMenuOpen) {
            toolMenuW = 190;
            toolMenuH = 145;
            toolMenuX = sWidth - toolMenuW;
            toolMenuY = sHeight - (int)((barHeight + toolMenuH) * animToolsY);

            fill(toolMenuX, toolMenuY, sWidth, toolMenuY + toolMenuH, 0xFF050505);
            fill(toolMenuX, toolMenuY, sWidth, toolMenuY + 1, 0xFF1A1A1A);
            fill(toolMenuX, toolMenuY, toolMenuX + 1, toolMenuY + toolMenuH, 0xFF1A1A1A);

            drawText("Tools", toolMenuX + 10, toolMenuY + 5, 0xFFAAAAAA);
            int btnW = toolMenuW - 16, btnX = toolMenuX + 8, toggleH = 20;

            moveToolY = toolMenuY + 24;
            gridToolY = toolMenuY + 47;
            trailToolY = toolMenuY + 70;
            physicsToolY = toolMenuY + 93;
            clearToolY = toolMenuY + 116;

            drawMenuToggle("Move Planets", btnX, moveToolY, btnW, toggleH, isMoveToolActive);
            drawMenuToggle("Show Grid", btnX, gridToolY, btnW, toggleH, showGrid);
            drawMenuToggle("Show Trails", btnX, trailToolY, btnW, toggleH, showTrails);
            drawMenuToggle("Physics", btnX, physicsToolY, btnW, toggleH, physicsEnabled);
            drawMenuAction("Clear Universe", btnX, clearToolY, btnW, toggleH, 0xFFFFAAAA);
        }

        // МЕНЮ ADD: разделитель сдвинут влево (sWidth - 185), кнопки S / O / L увеличены (26x26)
        if (animAddY > 0.01 && !isWelcomeMenuOpen) {
            int menuHeight = 135;
            int menuY = sHeight - (int)((barHeight + menuHeight) * animAddY);

            fill(0, menuY, sWidth, menuY + menuHeight, 0xFF050505);
            fill(0, menuY, sWidth, menuY + 1, 0xFF1A1A1A);

            int catX = 10, catY = menuY + 6, catSize = 18; catHitY = catY; catHitH = catSize;
            fill(catX, catY + 2, catX + 1, catY + catSize - 2, 0xFF3A3A3A); catX += 3;

            for (int c = 0; c < 5; c++) {
                int w;
                if (c == 0) {
                    String text = "ALL"; w = getTextWidth(text) + 12; catHitX[c] = catX; catHitW[c] = w;
                    drawText(text, catX + 6, catY + (catSize - FONT_HEIGHT) / 2 + 1, currentCategory == c ? 0xFF666666 : 0xFFFFFFFF); catX += w;
                } else {
                    w = catSize + 12; catHitX[c] = catX; catHitW[c] = w;
                    String tex = null;
                    if (c == 1) tex = ICON_PLANETS; if (c == 2) tex = ICON_MOONS; if (c == 3) tex = ICON_STARS; if (c == 4) tex = ICON_HOLES;
                    if (tex != null) drawIcon(tex, catX + (w - catSize)/2, catY, catSize, catSize, currentCategory == c ? 0xFF555555 : 0xFFFFFFFF);
                    catX += w;
                }
                if (c < 4) { catX += 2; fill(catX, catY + 2, catX + 1, catY + catSize - 2, 0xFF3A3A3A); catX += 3; }
            }

            for (int i = 0; i < planetHitboxes.length; i++) planetHitboxes[i][0] = -1;

            java.util.List<Integer> visibleItems = new java.util.ArrayList<>();
            for (int i = 0; i < menuItems.length; i++) {
                boolean match = false;
                if (currentCategory == 0) match = true;
                else if (currentCategory == 1 && menuItems[i].type.equals("Planet")) match = true;
                else if (currentCategory == 2 && menuItems[i].type.equals("Satellite")) match = true;
                else if (currentCategory == 3 && menuItems[i].type.equals("Star")) match = true;
                else if (currentCategory == 4 && menuItems[i].type.equals("Hole")) match = true;
                if (match) visibleItems.add(i);
            }

            int sepX = sWidth - 185;
            int iconSize = 22, startX = 14, startY = menuY + 34, rowHeight = 44;

            int simX = startX;
            int totalRows = 1;
            for (int vi = 0; vi < visibleItems.size(); vi++) {
                int i = visibleItems.get(vi);
                int tw = getSmallTextWidth(menuItems[i].name);
                int itemW = Math.max(iconSize + 18, tw + 14);
                if (simX + itemW > sepX - 10 && simX > startX) {
                    simX = startX;
                    totalRows++;
                }
                simX += itemW + 8;
            }

            int visibleAreaH = menuHeight - 38;
            int maxScroll = Math.max(0, totalRows * rowHeight - visibleAreaH);
            if (menuScrollY > maxScroll) menuScrollY = maxScroll;
            if (menuScrollY < 0) menuScrollY = 0;

            int curX = startX;
            int curRow = 0;
            for (int vi = 0; vi < visibleItems.size(); vi++) {
                int i = visibleItems.get(vi);
                String pName = menuItems[i].name;
                int tw = getSmallTextWidth(pName);
                int itemW = Math.max(iconSize + 18, tw + 14);

                if (curX + itemW > sepX - 10 && curX > startX) {
                    curX = startX;
                    curRow++;
                }

                int eY = startY + curRow * rowHeight - (int) menuScrollY;
                int iconX = curX + (itemW - iconSize) / 2;
                int tX = curX + (itemW - tw) / 2;
                int tY = eY + iconSize + 4;

                if (eY >= menuY + 26 && eY + iconSize + 14 <= menuY + menuHeight) {
                    String guiTexStr = menuItems[i].guiTex;
                    if (guiTexStr == null || guiTexStr.isEmpty()) guiTexStr = ICON_PLANETS;
                    drawIcon(guiTexStr, iconX, eY, iconSize, iconSize);

                    if (i == selectedPlanetIndex) {
                        fill(tX - 3, tY - 2, tX + tw + 3, tY + SMALL_FONT_HEIGHT + 1, 0xAA000000);
                        drawSmallText(pName, tX, tY, 0xFF55FF55);
                    } else {
                        drawSmallText(pName, tX, tY, 0xFFAAAAAA);
                    }
                    planetHitboxes[i][0] = curX;
                    planetHitboxes[i][1] = eY;
                    planetHitboxes[i][2] = itemW;
                    planetHitboxes[i][3] = iconSize + 18;
                }
                curX += itemW + 8;
            }

            if (maxScroll > 0) {
                int trackX = sepX - 5;
                int trackY = menuY + 32;
                int trackH = menuHeight - 40;
                fill(trackX, trackY, trackX + 3, trackY + trackH, 0xFF151515);
                int thumbH = Math.max(16, (int)((float) visibleAreaH / (totalRows * rowHeight) * trackH));
                int thumbY = trackY + (int)((menuScrollY / (float) maxScroll) * (trackH - thumbH));
                fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFF555555);
            }

            fill(sepX, menuY + 10, sepX + 1, menuY + menuHeight - 10, 0xFF2A2A2A);
            int btnS = 26, gap = 10, rightStartX = sepX + 14, btnY = menuY + 14;

            staticBtnX = rightStartX; staticBtnY = btnY; staticBtnW = btnS; staticBtnH = btnS;
            drawTinyModeButton("S", rightStartX, btnY, btnS, currentSpawnMode == SpawnMode.STATIC, false);
            int nextX = rightStartX + btnS + gap; orbitBtnX = nextX; orbitBtnY = btnY; orbitBtnW = btnS; orbitBtnH = btnS;
            drawTinyModeButton("O", nextX, btnY, btnS, currentSpawnMode == SpawnMode.ORBIT, planets.isEmpty());
            nextX += btnS + gap; launchBtnX = nextX; launchBtnY = btnY; launchBtnW = btnS; launchBtnH = btnS;
            drawTinyModeButton("L", nextX, btnY, btnS, currentSpawnMode == SpawnMode.LAUNCH, planets.isEmpty());

            if ((currentSpawnMode == SpawnMode.LAUNCH || currentSpawnMode == SpawnMode.ORBIT) && !planets.isEmpty()) {
                speedBoxHitX = rightStartX; speedBoxHitY = btnY + btnS + 8; speedBoxHitW = 90; speedBoxHitH = 18;
                unitBoxHitX = speedBoxHitX + speedBoxHitW + 4; unitBoxHitY = speedBoxHitY; unitBoxHitW = 48; unitBoxHitH = 18;

                boolean speedBlocked = (currentSpawnMode == SpawnMode.ORBIT && autoOrbitSpeed);
                fill(speedBoxHitX, speedBoxHitY, speedBoxHitX + speedBoxHitW, speedBoxHitY + speedBoxHitH, 0xFF111111);
                drawOutline(speedBoxHitX, speedBoxHitY, speedBoxHitW, speedBoxHitH, speedBlocked ? 0xFF444444 : (isTypingSpeed ? 0xFF55FF55 : 0xFF555555));
                String dispSpeed = isTypingSpeed ? typedSpeed + "_" : String.format(Locale.US, "%.1f", inputSpeed);
                while (getTextWidth(dispSpeed) > speedBoxHitW - 8 && dispSpeed.length() > 1) dispSpeed = dispSpeed.substring(1);
                drawText(dispSpeed, speedBoxHitX + 4, speedBoxHitY + 1, speedBlocked ? 0xFF888888 : 0xFFFFFFFF);

                fill(unitBoxHitX, unitBoxHitY, unitBoxHitX + unitBoxHitW, unitBoxHitY + unitBoxHitH, 0xFF222222);
                drawOutline(unitBoxHitX, unitBoxHitY, unitBoxHitW, unitBoxHitH, speedBlocked ? 0xFF444444 : 0xFF555555);
                String[] unitNames = {"c", "km/s", "km/h", "m/s"};
                drawText(unitNames[speedUnitIndex], unitBoxHitX + 4, unitBoxHitY + 1, speedBlocked ? 0xFF555555 : 0xFFAAAAAA);

                if (isUnitMenuOpen && !speedBlocked) {
                    unitMenuHitW = unitBoxHitW; unitMenuHitH = 16 * 4; unitMenuHitX = unitBoxHitX; unitMenuHitY = unitBoxHitY - unitMenuHitH;
                    fill(unitMenuHitX, unitMenuHitY, unitMenuHitX + unitMenuHitW, unitMenuHitY + unitMenuHitH, 0xFF050505);
                    drawOutline(unitMenuHitX, unitMenuHitY, unitMenuHitW, unitMenuHitH, 0xFF777777);

                    double mX, mY;
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        DoubleBuffer mx = stack.mallocDouble(1); DoubleBuffer my = stack.mallocDouble(1);
                        GLFW.glfwGetCursorPos(window, mx, my); mX = mx.get(0) / uiScale; mY = my.get(0) / uiScale;
                    }
                    for (int i = 0; i < 4; i++) {
                        int itemY = unitMenuHitY + i * 16;
                        if (mX >= unitMenuHitX && mX <= unitMenuHitX + unitMenuHitW && mY >= itemY && mY < itemY + 16) fill(unitMenuHitX, itemY, unitMenuHitX + unitMenuHitW, itemY + 16, 0xFF333333);
                        drawText(unitNames[i], unitMenuHitX + 4, itemY, 0xFFFFFFFF);
                    }
                }

                if (currentSpawnMode == SpawnMode.ORBIT) {
                    autoToggleHitW = 24; autoToggleHitH = 12; autoToggleHitX = speedBoxHitX; autoToggleHitY = speedBoxHitY + speedBoxHitH + 6;
                    fill(autoToggleHitX, autoToggleHitY, autoToggleHitX + autoToggleHitW, autoToggleHitY + autoToggleHitH, autoOrbitSpeed ? 0xFF225522 : 0xFF552222);
                    drawOutline(autoToggleHitX, autoToggleHitY, autoToggleHitW, autoToggleHitH, autoOrbitSpeed ? 0xFF55FF55 : 0xFFFF5555);
                    drawText("Auto", autoToggleHitX + 30, autoToggleHitY - 2, 0xFFAAAAAA);
                }
            }
        }

        fill(0, rectY, sWidth, sHeight, 0xFF050505);
        fill(0, rectY, sWidth, rectY + 1, isPaused ? 0xFFFFFFFF : 0xFF2A2A2A);

        int centerY = rectY + barHeight / 2;
        int playIconPadX = 6, playIconPadY = 2; String playIcon = isPaused ? "||" : ">";

        int playBtnX = 10, btnH = FONT_HEIGHT + playIconPadY * 2, playBtnY = centerY - btnH / 2;
        int playBtnW = drawButton(playIcon, playBtnX, playBtnY, playIconPadX, playIconPadY, isPaused);
        playHitX = playBtnX; playHitY = playBtnY; playHitW = playBtnW; playHitH = btnH;

        int arrowsX = playBtnX + playBtnW + 8;
        int arrowPadX = 2, arrowPadY = 2, arrowBtnH = FONT_HEIGHT + arrowPadY * 2, arrowBtnY = centerY - arrowBtnH / 2;

        int leftArrowW = drawButton("<", arrowsX, arrowBtnY, arrowPadX, arrowPadY, downPressed);
        leftArrowHitX = arrowsX; leftArrowHitY = arrowBtnY; leftArrowHitW = leftArrowW; leftArrowHitH = arrowBtnH;

        int rightArrowX = arrowsX + leftArrowW + 1;
        int rightArrowW = drawButton(">", rightArrowX, arrowBtnY, arrowPadX, arrowPadY, upPressed);
        rightArrowHitX = rightArrowX; rightArrowHitY = arrowBtnY; rightArrowHitW = rightArrowW; rightArrowHitH = arrowBtnH;

        LocalDateTime baseDate = LocalDateTime.of(2026, 1, 1, 12, 0);
        String timeText;
        try {
            LocalDateTime currentDate = baseDate.plusSeconds((long) simTimeSeconds);
            int year = currentDate.getYear();
            if (year > 9999 || year < -9999) { timeText = "Year " + formatCompact(year); } else { DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm"); timeText = currentDate.format(formatter); }
        } catch (Exception e) {
            double yrsPassed = simTimeSeconds / 31536000.0; timeText = "Year " + formatCompact(2026 + yrsPassed);
        }

        String scaleText = formatTimeScale(timeScale);

        int timeX = rightArrowX + rightArrowW + 12;
        int textY = centerY - FONT_HEIGHT / 2;
        drawText(timeText, timeX, textY, 0xFFFFFFFF);

        int sepX = timeX + getTextWidth(timeText) + 8;
        fill(sepX, centerY - 6, sepX + 1, centerY + 6, 0xFF555555);
        drawText(scaleText, sepX + 8, textY, 0xFFAAAAAA);

        String[] rightButtons = {"Add", "Tools"}; int btnPadX = 6, gap = 8, totalRightWidth = 0;
        for (String btn : rightButtons) totalRightWidth += getTextWidth(btn) + btnPadX * 2 + gap;
        int currX = sWidth - totalRightWidth - 10, rightBtnH = FONT_HEIGHT + 4, rightBtnY = centerY - rightBtnH / 2;

        for (String btn : rightButtons) {
            boolean highlight = (btn.equals("Add") && isAddMenuOpen) || (btn.equals("Tools") && isToolsMenuOpen);
            int w = drawButton(btn, currX, rightBtnY, btnPadX, 2, highlight);
            if (btn.equals("Add")) { addBtnX = currX; addBtnY = rightBtnY; addBtnW = w; addBtnH = rightBtnH; }
            if (btn.equals("Tools")) { toolsBtnX = currX; toolsBtnY = rightBtnY; toolsBtnW = w; toolsBtnH = rightBtnH; }
            currX += w + gap;
        }

        for (int i = 0; i < notifications.size(); i++) {
            Notification n = notifications.get(i);
            n.targetY = 12 + i * 26;
            n.currentY += (n.targetY - n.currentY) * Math.min(1.0, dtUI * 15.0);
            n.life -= dtUI;
            if (i >= 3 || n.life <= 0) { n.alpha -= dtUI * 4.0; } else { n.alpha += dtUI * 5.0; }
            if (n.alpha > 1.0) n.alpha = 1.0;
            if (n.alpha <= 0) {
                notifications.remove(i);
                i--;
            } else {
                int tw = getSmallTextWidth(n.text);
                int notifW = tw + 26;
                int notifH = 22;
                int x = sWidth - notifW - 10;
                int y = (int) Math.round(n.currentY);
                int aInt = (int)(n.alpha * 255);
                if (aInt > 5) {
                    fill(x, y, x + notifW, y + notifH, (aInt << 24) | 0x111111);
                    drawOutline(x, y, notifW, notifH, (aInt << 24) | 0x555555);
                    fill(x + notifW - 3, y, x + notifW, y + notifH, (aInt << 24) | 0x55FF55);
                    drawSmallText(n.text, x + 10, y + (notifH - SMALL_FONT_HEIGHT) / 2 + 1, (aInt << 24) | 0xFFFFFF);
                }
            }
        }

        if (!CameraManager.isRotating()) {
            double mX, mY;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                DoubleBuffer mx = stack.mallocDouble(1); DoubleBuffer my = stack.mallocDouble(1);
                GLFW.glfwGetCursorPos(window, mx, my); mX = mx.get(0) / uiScale; mY = my.get(0) / uiScale;
            }
            if (isPlacingPlanet && currentSpawnMode == SpawnMode.LAUNCH) {
                int aimSize = 24; drawIcon(CURSOR_AIM_TEX, (int)mX - aimSize / 2, (int)mY - aimSize / 2, aimSize, aimSize);
            } else {
                int cursorSize = 24; drawIcon(CURSOR_TEX, (int)mX, (int)mY, cursorSize, cursorSize);
            }
        }
    }
}