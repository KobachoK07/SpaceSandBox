package spacesb.remake;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import spacesb.remake.render.SBoxCompute;
import spacesb.remake.render.SBoxVulkanCanvas;
import spacesb.remake.render.SBoxVulkanUI;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    public static final String CURSOR_TRASH_TEX = "procedural/trash_cursor";

    public static boolean showHabitableZone = false;
    public static boolean showGravityGrid = false;
    public static int hzToolY = 0, gravGridToolY = 0;

    public static int currentLanguage = 1;
    public static boolean isLanguageMenuOpen = false;
    public static final int[] escLangBtnHit = new int[4];
    public static final int[] escLangDropdownHit = new int[4];
    private static boolean prevLeftMouseDown = false;
    private static boolean defaultsApplied = false;
    private static boolean windowFullscreenApplied = false;

    public static final String[] LANGUAGE_NAMES = {
            "Русский", "English", "Українська", "Беларуская", "Қазақша", "中文", "Français", "Deutsch"
    };

    private static final String[][] I18N = {
            {"Язык", "Language", "Мова", "Мова", "Тіл", "语言", "Langue", "Sprache"},
            {"Новая симуляция", "New Sim", "Нова симуляція", "Новая сімуляцыя", "Жаңа симуляция", "新模拟", "Nouv. Sim", "Neue Sim"},
            {"Графика и экран", "Graphics & Display", "Графіка та екран", "Графіка і экран", "Графика және экран", "图形与显示", "Graphismes & Écran", "Grafik & Anzeige"},
            {"Режим FSR:", "FSR Mode:", "Режим FSR:", "Рэжым FSR:", "FSR режимі:", "FSR 模式:", "Mode FSR:", "FSR-Modus:"},
            {"Верт. синхр.", "V-Sync", "Верт. синхр.", "Верт. сінхр.", "Верт. синхр.", "垂直同步", "Sync. Vert.", "V-Sync"},
            {"Разрешение", "Resolution", "Роздільність", "Разрозненне", "Ажыратымдылық", "分辨率", "Résolution", "Auflösung"},
            {"Режим экрана", "Display Mode", "Режим екрана", "Рэжым экрана", "Экран режимі", "显示模式", "Mode d'écran", "Anzeigemodus"},
            {"Полный экран", "Fullscreen", "Повний екран", "Поўны экран", "Толық экран", "全屏", "Plein écran", "Vollbild"},
            {"В окне", "Windowed", "У вікні", "У акне", "Терезеде", "窗口", "Fenêtré", "Fenster"},
            {"Показать FPS", "Show FPS", "Показати FPS", "Паказаць FPS", "FPS көрсету", "显示帧率", "Afficher FPS", "FPS anzeigen"},
            {"Фоторежим (C)", "Photo Mode (C)", "Фоторежим (C)", "Фотарэжым (C)", "Фоторежим (C)", "拍照模式 (C)", "Mode Photo (C)", "Fotomodus (C)"},
            {"Шейдеры:", "Shaders:", "Шейдери:", "Шэйдары:", "Шейдерлер:", "着色器:", "Shaders:", "Shader:"},
            {"Частицы:", "Particles:", "Частинки:", "Часціцы:", "Бөлшектер:", "粒子:", "Particules:", "Partikel:"},
            {"Выйти из игры", "Exit Game", "Вийти з гри", "Выйсці з гульні", "Ойыннан шығу", "退出游戏", "Quitter le jeu", "Spiel beenden"},
            {"Добавить", "Add", "Додати", "Дадаць", "Қосу", "添加", "Ajouter", "Hinzuf."},
            {"Инструменты", "Tools", "Інструменти", "Інструменты", "Құралдар", "工具", "Outils", "Werkzeuge"},
            {"Двигать планеты", "Move Planets", "Рухати планети", "Рухаць планеты", "Планета жылжыту", "移动行星", "Déplacer", "Bewegen"},
            {"Показать сетку", "Show Grid", "Показати сітку", "Паказаць сетку", "Торды көрсету", "显示网格", "Grille", "Gitter"},
            {"Орбиты / Следы", "Show Trails", "Орбіти / Сліди", "Арбіты / Сляды", "Іздерді көрсету", "显示轨迹", "Trajectoires", "Bahnen"},
            {"Обитаемая зона", "Habitable Zone", "Життєва зона", "Жылая зона", "Тіршілік аймағы", "宜居带", "Zone habitable", "Habitable Zone"},
            {"Физика", "Physics", "Фізика", "Фізіка", "Физика", "物理模拟", "Physique", "Physik"},
            {"Удалить объекты", "Delete Objects", "Видалити об'єкти", "Выдаліць аб'екты", "Объектіні жою", "删除物体", "Supprimer", "Löschen"},
            {"ВКЛ", "ON", "УВІМК", "УКЛ", "ҚОСУ", "开", "OUI", "EIN"},
            {"ВЫКЛ", "OFF", "ВИМК", "ВЫКЛ", "ӨШІРУ", "关", "NON", "AUS"},
            {"Изм.: ", "Edit: ", "Ред.: ", "Рэд.: ", "Өңдеу: ", "编辑: ", "Éditer: ", "Bearb.: "},
            {"Масса", "Mass", "Маса", "Маса", "Масса", "质量", "Masse", "Masse"},
            {"Диаметр", "Diameter", "Діаметр", "Дыяметр", "Диаметр", "直径", "Diamètre", "Durchm."},
            {"Вращение", "Rotation", "Обертання", "Вярчэнне", "Айналу", "自转", "Rotation", "Rotation"},
            {"Наклон", "Tilt", "Нахил", "Нахіл", "Көлбеу", "倾角", "Inclinaison", "Neigung"},
            {"Скорость", "Velocity", "Швидкість", "Хуткасць", "Жылдамдық", "速度", "Vitesse", "Geschw."},
            {"Темп.", "Temp", "Темп.", "Тэмп.", "Темп.", "温度", "Temp.", "Temp."},
            {"Я понимаю", "I Understand", "Я розумію", "Я разумею", "Түсіндім", "我已了解", "Je comprends", "Verstanden"},
            {"Скорость: ", "Speed: ", "Швидкість: ", "Хуткасць: ", "Жылдамдық: ", "速度: ", "Vitesse: ", "Geschw.: "},
            {"Гравит. сетка", "Gravity Grid", "Гравіт. сітка", "Гравіт. сетка", "Гравит. тор", "引力网格", "Grille grav.", "Grav.-Gitter"},
            {"Музыка:", "Music:", "Музика:", "Музыка:", "Музыка:", "音乐:", "Musique:", "Musik:"}
    };

    public static String tr(int key) {
        int lang = Math.max(0, Math.min(7, currentLanguage));
        return (key >= 0 && key < I18N.length) ? I18N[key][lang] : "";
    }

    public static String getParticlePresetLabel(int preset) {
        if (preset <= 0) return "Min";
        if (preset == 1) return "Med";
        if (preset == 2) return "Hard";
        return "Ultra";
    }

    private static void applyInitialDefaultSettings(long window) {
        if (!defaultsApplied) {
            defaultsApplied = true;
            fsrEnabled = false; fsrQualityIndex = 0; fsrSliderPos = 0.125f;
            hdrEnabled = false; vsyncEnabled = true;
            for (int i = 0; i < RESOLUTIONS.length; i++) {
                if (RESOLUTIONS[i][0] == 1920 && RESOLUTIONS[i][1] == 1080) { resolutionIndex = i; break; }
            }
            isFullscreen = true; showFpsOverlay = false; photoModeFeatureEnabled = true;
            shaderMode = 0; shaderSliderPos = 0.833f;
            particlePreset = 3; particleSliderPos = 0.875f;
            musicVolume = 0.20f;
            currentLanguage = 1;
            isMoveToolActive = false; showGrid = true; showGravityGrid = false;
            showTrails = true; showHabitableZone = false; physicsEnabled = true; isDeleteToolActive = false;
            DiscordRPC.start();
        }
        if (window != 0L && !windowFullscreenApplied) {
            windowFullscreenApplied = true;
            try { applyDisplaySettings(window); } catch (Exception ignored) {}
        }
    }

    private static double editMenuAnimX = 0, escMenuAnimX = -320, lastUiTime = 0, animAddY = 0.0, animToolsY = 0.0;
    private static boolean initNotif = false, prevShowGrid = true, prevShowGravityGrid = false, prevShowTrails = true;
    private static boolean prevShowHabitableZone = false, prevPhysicsEnabled = true, prevMoveTool = false, prevDeleteTool = false, prevPaused = false;

    public static final int FONT_HEIGHT = 16, SMALL_FONT_HEIGHT = 12;
    public static boolean isFontLoaded = false;
    private static long fontDescriptor = -1, smallFontDescriptor = -1;

    private static final int ATLAS_SIZE = 1024, MAX_GLYPHS = 2048;
    private static final short[] glyphMapLarge = new short[65536], glyphMapSmall = new short[65536];
    private static final float[] largeU0 = new float[MAX_GLYPHS], largeV0 = new float[MAX_GLYPHS], largeU1 = new float[MAX_GLYPHS], largeV1 = new float[MAX_GLYPHS];
    private static final int[] largeW = new int[MAX_GLYPHS], largeH = new int[MAX_GLYPHS], largeAdv = new int[MAX_GLYPHS];
    private static final float[] smallU0 = new float[MAX_GLYPHS], smallV0 = new float[MAX_GLYPHS], smallU1 = new float[MAX_GLYPHS], smallV1 = new float[MAX_GLYPHS];
    private static final int[] smallW = new int[MAX_GLYPHS], smallH = new int[MAX_GLYPHS], smallAdv = new int[MAX_GLYPHS];

    private static final Map<String, Long> textures = new HashMap<>();

    private static final Vector3d SCRATCH_RAY = new Vector3d(), SCRATCH_REL_PT = new Vector3d(), SCRATCH_RO = new Vector3d(), SCRATCH_RD = new Vector3d();
    private static final Vector3d SCRATCH_MID = new Vector3d(), SCRATCH_PROBE = new Vector3d(), SCRATCH_CLIP = new Vector3d();
    private static final Vector3d SCRATCH_SUB0 = new Vector3d(), SCRATCH_SUB1 = new Vector3d();
    private static final Vector3d SCRATCH_FORWARD = new Vector3d(), SCRATCH_RIGHT = new Vector3d(), SCRATCH_UP = new Vector3d();
    private static final Vector3d SCRATCH_ORBIT_UP = new Vector3d(), SCRATCH_RADIAL = new Vector3d(), SCRATCH_TANGENT = new Vector3d();
    private static final Vector3d SCRATCH_PREV_PT = new Vector3d(), SCRATCH_CURR_PT = new Vector3d(), SCRATCH_MID_3D = new Vector3d();
    private static final float[] SCRATCH_SEG = new float[4], SCRATCH_UVA = new float[2], SCRATCH_UVB = new float[2];
    private static final int[] VISIBLE_ITEMS_BUF = new int[64];

    public static void initGL() {}
    public static void setProjectionMatrix(Matrix4f mat) {}
    public static void onScroll(double yoffset) {}

    public static void drawQuad(float x1, float y1, float x2, float y2, float u1, float v1, float u2, float v2, float r, float g, float b, float a, int mode, long descSet) {
        SBoxVulkanUI.drawQuad(x1, y1, x2 - x1, y2 - y1, u1, v1, u2, v2, r, g, b, a, mode, descSet);
    }

    public static byte[] loadFileBytes(String path) {
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        try {
            InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(cleanPath);
            if (is != null) { byte[] d = is.readAllBytes(); is.close(); return d; }
            is = SpaceUI.class.getClassLoader().getResourceAsStream(cleanPath);
            if (is != null) { byte[] d = is.readAllBytes(); is.close(); return d; }
            String[] devPaths = {"src/main/resources/", "../src/main/resources/", "../../src/main/resources/"};
            for (String prefix : devPaths) {
                java.nio.file.Path p = Paths.get(prefix + cleanPath);
                if (Files.exists(p)) return Files.readAllBytes(p);
            }
        } catch (Exception e) {
            System.err.println("[SBox UI] Ошибка при чтении: " + cleanPath);
        }
        return null;
    }

    private static long buildUnicodeFontAtlas(
            Font primaryFont, Font fallbackFont, Set<Character> chars,
            short[] lookup, float[] u0Arr, float[] v0Arr, float[] u1Arr, float[] v1Arr,
            int[] wArr, int[] hArr, int[] advArr, int targetCellH) {

        BufferedImage img = new BufferedImage(ATLAS_SIZE, ATLAS_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();
        g2d.setColor(new Color(0, 0, 0, 0));
        g2d.fillRect(0, 0, ATLAS_SIZE, ATLAS_SIZE);
        g2d.setColor(Color.WHITE);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int curX = 2, curY = 2, rowMaxH = targetCellH + 6, idx = 1;
        for (char c : chars) {
            if (idx >= MAX_GLYPHS) break;
            Font useFont = (primaryFont != null && primaryFont.canDisplay(c)) ? primaryFont : fallbackFont;
            g2d.setFont(useFont);
            FontMetrics fm = g2d.getFontMetrics();
            int charAdv = Math.max(1, fm.charWidth(c));
            int drawW = Math.max(charAdv + 2, 4), drawH = targetCellH + 4;

            if (curX + drawW + 2 >= ATLAS_SIZE) { curX = 2; curY += rowMaxH + 2; }
            if (curY + drawH + 2 >= ATLAS_SIZE) break;

            g2d.drawString(String.valueOf(c), curX, curY + fm.getAscent());
            lookup[c] = (short) idx;
            u0Arr[idx] = (float) curX / ATLAS_SIZE;
            v0Arr[idx] = (float) curY / ATLAS_SIZE;
            u1Arr[idx] = (float) (curX + drawW) / ATLAS_SIZE;
            v1Arr[idx] = (float) (curY + drawH) / ATLAS_SIZE;
            wArr[idx] = drawW; hArr[idx] = drawH; advArr[idx] = charAdv;
            curX += drawW + 3; idx++;
        }
        g2d.dispose();

        int[] argb = img.getRGB(0, 0, ATLAS_SIZE, ATLAS_SIZE, null, 0, ATLAS_SIZE);
        ByteBuffer bitmap = BufferUtils.createByteBuffer(ATLAS_SIZE * ATLAS_SIZE);
        for (int p : argb) bitmap.put((byte) ((p >>> 24) & 0xFF));
        bitmap.flip();
        return SBoxVulkanUI.uploadTexture(bitmap, ATLAS_SIZE, ATLAS_SIZE, VK_FORMAT_R8_UNORM);
    }

    public static void initFont(String internalPath) {
        if (isFontLoaded) return;
        try {
            byte[] bytes = loadFileBytes(internalPath);
            Font baseMcFont = null;
            if (bytes != null) {
                try { baseMcFont = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(bytes)); }
                catch (Exception ignored) {}
            }
            Font primaryLarge = (baseMcFont != null) ? baseMcFont.deriveFont(Font.PLAIN, 15.0f) : new Font(Font.SANS_SERIF, Font.BOLD, 14);
            Font primarySmall = (baseMcFont != null) ? baseMcFont.deriveFont(Font.PLAIN, 11.5f) : new Font(Font.SANS_SERIF, Font.PLAIN, 11);
            Font fallbackLarge = new Font(Font.SANS_SERIF, Font.BOLD, 14);
            Font fallbackSmall = new Font(Font.SANS_SERIF, Font.PLAIN, 11);

            Set<Character> charset = new LinkedHashSet<>();
            for (int c = 32; c <= 126; c++) charset.add((char) c);
            for (int c = 0x00A0; c <= 0x017F; c++) charset.add((char) c);
            for (int c = 0x0400; c <= 0x04FF; c++) charset.add((char) c);
            charset.add('∞');
            for (String langName : LANGUAGE_NAMES) {
                for (int k = 0; k < langName.length(); k++) charset.add(langName.charAt(k));
            }
            for (String[] row : I18N) {
                for (String s : row) {
                    for (int k = 0; k < s.length(); k++) charset.add(s.charAt(k));
                }
            }

            fontDescriptor = buildUnicodeFontAtlas(primaryLarge, fallbackLarge, charset, glyphMapLarge, largeU0, largeV0, largeU1, largeV1, largeW, largeH, largeAdv, FONT_HEIGHT);
            smallFontDescriptor = buildUnicodeFontAtlas(primarySmall, fallbackSmall, charset, glyphMapSmall, smallU0, smallV0, smallU1, smallV1, smallW, smallH, smallAdv, SMALL_FONT_HEIGHT);
            isFontLoaded = (fontDescriptor != -1 && smallFontDescriptor != -1);
        } catch (Exception e) {
            System.out.println("[SBox UI] Ошибка инициализации Unicode-шрифта: " + e.getMessage());
        }
    }

    public static void drawCountryFlag(int langIdx, int x, int y, int w, int h) {
        fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF3A3A3A);
        int h3 = Math.max(1, h / 3), w3 = Math.max(1, w / 3);
        switch (langIdx) {
            case 0:
                fill(x, y, x + w, y + h3, 0xFFFFFFFF);
                fill(x, y + h3, x + w, y + h3 * 2, 0xFF0039A6);
                fill(x, y + h3 * 2, x + w, y + h, 0xFFD52B1E);
                break;
            case 1:
                fill(x, y, x + w, y + h, 0xFF012169);
                fill(x, y + h / 2 - 2, x + w, y + h / 2 + 2, 0xFFFFFFFF);
                fill(x + w / 2 - 2, y, x + w / 2 + 2, y + h, 0xFFFFFFFF);
                fill(x, y + h / 2 - 1, x + w, y + h / 2 + 1, 0xFFC8102E);
                fill(x + w / 2 - 1, y, x + w / 2 + 1, y + h, 0xFFC8102E);
                break;
            case 2:
                fill(x, y, x + w, y + h / 2, 0xFF0057B7);
                fill(x, y + h / 2, x + w, y + h, 0xFFFFDD00);
                break;
            case 3:
                int bySplit = y + (h * 2) / 3;
                fill(x, y, x + w, bySplit, 0xFFD22730);
                fill(x, bySplit, x + w, y + h, 0xFF009739);
                fill(x, y, x + 3, y + h, 0xFFFFFFFF);
                fill(x + 1, y + 1, x + 2, y + h - 1, 0xFFD22730);
                break;
            case 4:
                fill(x, y, x + w, y + h, 0xFF00AFCA);
                fill(x + 1, y + 1, x + 2, y + h - 1, 0xFFFEC50C);
                fill(x + w / 2 - 2, y + h / 2 - 2, x + w / 2 + 3, y + h / 2 + 3, 0xFFFEC50C);
                break;
            case 5:
                fill(x, y, x + w, y + h, 0xFFDE2910);
                fill(x + 2, y + 2, x + 5, y + 5, 0xFFFFDE00);
                fill(x + 6, y + 1, x + 7, y + 2, 0xFFFFDE00);
                fill(x + 7, y + 3, x + 8, y + 4, 0xFFFFDE00);
                fill(x + 6, y + 5, x + 7, y + 6, 0xFFFFDE00);
                break;
            case 6:
                fill(x, y, x + w3, y + h, 0xFF002395);
                fill(x + w3, y, x + w3 * 2, y + h, 0xFFFFFFFF);
                fill(x + w3 * 2, y, x + w, y + h, 0xFFED2939);
                break;
            default:
                fill(x, y, x + w, y + h3, 0xFF111111);
                fill(x, y + h3, x + w, y + h3 * 2, 0xFFDD0000);
                fill(x, y + h3 * 2, x + w, y + h, 0xFFFFCE00);
                break;
        }
    }

    private static void createProceduralTrashCursor() {
        if (textures.containsKey(CURSOR_TRASH_TEX)) return;
        String[] art = {
                ".....XXXXXX.....", "...XXXXXXXXXX...", "..XXXXXXXXXXXX..", "................",
                "..XXXXXXXXXXXX..", "..XX.XX..XX.XX..", "..XX.XX..XX.XX..", "..XX.XX..XX.XX..",
                "..XX.XX..XX.XX..", "..XX.XX..XX.XX..", "..XX.XX..XX.XX..", "..XX.XX..XX.XX..",
                "..XX.XX..XX.XX..", "..XXXXXXXXXXXX..", "...XXXXXXXXXX...", "................"
        };
        ByteBuffer buf = BufferUtils.createByteBuffer(16 * 16 * 4);
        for (int y = 0; y < 16; y++) {
            String row = art[y];
            for (int x = 0; x < 16; x++) {
                if (row.charAt(x) == 'X') buf.put((byte) 255).put((byte) 85).put((byte) 85).put((byte) 255);
                else buf.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
            }
        }
        buf.flip();
        textures.put(CURSOR_TRASH_TEX, SBoxVulkanUI.uploadTexture(buf, 16, 16, VK_FORMAT_R8G8B8A8_UNORM));
    }

    public static long getTexture(String path) {
        if (textures.containsKey(path)) return textures.get(path);
        if (CURSOR_TRASH_TEX.equals(path)) {
            createProceduralTrashCursor();
            return textures.getOrDefault(path, -1L);
        }
        try {
            byte[] bytes = loadFileBytes(path);
            if (bytes == null) return -1;
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(bytes));
            if (bi == null) return -1;
            int w = bi.getWidth(), h = bi.getHeight();
            int[] pixels = bi.getRGB(0, 0, w, h, null, 0, w);
            ByteBuffer buffer = BufferUtils.createByteBuffer(w * h * 4);
            for (int p : pixels) {
                buffer.put((byte) ((p >> 16) & 0xFF)).put((byte) ((p >> 8) & 0xFF)).put((byte) (p & 0xFF)).put((byte) ((p >> 24) & 0xFF));
            }
            buffer.flip();
            long id = SBoxVulkanUI.uploadTexture(buffer, w, h, VK_FORMAT_R8G8B8A8_UNORM);
            textures.put(path, id);
            return id;
        } catch (Exception e) { return -1; }
    }

    public static void preloadAllResources() {
        applyInitialDefaultSettings(0L);
        initFont("assets/spacesb-remake/fonts/minecraft.ttf");
        getTexture(ICON_PLANETS); getTexture(ICON_MOONS); getTexture(ICON_STARS); getTexture(ICON_HOLES);
        getTexture(CURSOR_TEX); getTexture(CURSOR_AIM_TEX); getTexture(ICON_FLASHLIGHT); getTexture(ICON_RAYS);
        getTexture(LOGO_TEX); getTexture(MODRINTH_TEX); getTexture(TELEGRAM_TEX); getTexture(DONATE_TEX);
        createProceduralTrashCursor();
        for (MenuItem item : menuItems) {
            if (item.guiTex != null && !item.guiTex.isEmpty()) getTexture(item.guiTex);
        }
    }

    public static void fill(int x1, int y1, int x2, int y2, int color) {
        float a = (color >> 24 & 255) / 255.0f, r = (color >> 16 & 255) / 255.0f;
        float g = (color >> 8 & 255) / 255.0f, b = (color & 255) / 255.0f;
        if (a == 0) a = 1.0f;
        drawQuad(x1, y1, x2, y2, 0, 0, 0, 0, r, g, b, a, 0, -1);
    }

    public static void drawOutline(int x, int y, int width, int height, int color) {
        fill(x, y, x + width, y + 1, color); fill(x, y + height - 1, x + width, y + height, color);
        fill(x, y, x + 1, y + height, color); fill(x + width - 1, y, x + width, y + height, color);
    }

    public static void drawText(String text, float x, float y, int color) {
        if (text == null || text.isEmpty()) return;
        float r = (color >> 16 & 255) / 255.0f, g = (color >> 8 & 255) / 255.0f;
        float b = (color & 255) / 255.0f, a = (color >> 24 & 255) / 255.0f; if (a == 0) a = 1.0f;
        if (!isFontLoaded) {
            float curX = x;
            for (int i = 0; i < text.length(); i++) { fill((int) curX, (int) y, (int) curX + 6, (int) y + 12, color); curX += 8; }
            return;
        }
        float curX = Math.round(x), baseY = Math.round(y);
        for (int i = 0; i < text.length(); i++) {
            int gid = glyphMapLarge[text.charAt(i)] & 0xFFFF;
            if (gid == 0) { curX += 6; continue; }
            drawQuad(curX, baseY, curX + largeW[gid], baseY + largeH[gid], largeU0[gid], largeV0[gid], largeU1[gid], largeV1[gid], r, g, b, a, 2, fontDescriptor);
            curX += largeAdv[gid];
        }
    }

    public static void drawSmallText(String text, float x, float y, int color) {
        if (text == null || text.isEmpty()) return;
        float r = (color >> 16 & 255) / 255.0f, g = (color >> 8 & 255) / 255.0f;
        float b = (color & 255) / 255.0f, a = (color >> 24 & 255) / 255.0f; if (a == 0) a = 1.0f;
        if (!isFontLoaded || smallFontDescriptor == -1) { drawText(text, x, y, color); return; }
        float curX = Math.round(x), baseY = Math.round(y);
        for (int i = 0; i < text.length(); i++) {
            int gid = glyphMapSmall[text.charAt(i)] & 0xFFFF;
            if (gid == 0) { curX += 5; continue; }
            drawQuad(curX, baseY, curX + smallW[gid], baseY + smallH[gid], smallU0[gid], smallV0[gid], smallU1[gid], smallV1[gid], r, g, b, a, 2, smallFontDescriptor);
            curX += smallAdv[gid];
        }
    }

    public static void drawRotatedSmallText(String text, float centerX, float centerY, float angleRad, int color) {
        if (!isFontLoaded || smallFontDescriptor == -1 || text == null || text.isEmpty()) return;
        float r = (color >> 16 & 255) / 255.0f, g = (color >> 8 & 255) / 255.0f;
        float b = (color & 255) / 255.0f, a = (color >> 24 & 255) / 255.0f; if (a == 0) a = 1.0f;
        float curX = -getSmallTextWidth(text) * 0.5f, ly0 = -6.0f;
        float cos = (float) Math.cos(angleRad), sin = (float) Math.sin(angleRad);

        for (int i = 0; i < text.length(); i++) {
            int gid = glyphMapSmall[text.charAt(i)] & 0xFFFF;
            if (gid == 0) { curX += 5; continue; }
            float lx0 = curX, lx1 = curX + smallW[gid], ly1 = ly0 + smallH[gid];
            SBoxVulkanUI.drawQuadVertices(
                    centerX + (lx0 * cos - ly0 * sin), centerY + (lx0 * sin + ly0 * cos), smallU0[gid], smallV0[gid],
                    centerX + (lx0 * cos - ly1 * sin), centerY + (lx0 * sin + ly1 * cos), smallU0[gid], smallV1[gid],
                    centerX + (lx1 * cos - ly1 * sin), centerY + (lx1 * sin + ly1 * cos), smallU1[gid], smallV1[gid],
                    centerX + (lx1 * cos - ly0 * sin), centerY + (lx1 * sin + ly0 * cos), smallU1[gid], smallV0[gid],
                    r, g, b, a, 2, smallFontDescriptor
            );
            curX += smallAdv[gid];
        }
    }

    public static int getTextWidth(String text) {
        if (text == null || text.isEmpty()) return 0;
        if (!isFontLoaded) return text.length() * 8;
        int w = 0;
        for (int i = 0; i < text.length(); i++) {
            int gid = glyphMapLarge[text.charAt(i)] & 0xFFFF;
            w += (gid != 0) ? largeAdv[gid] : 6;
        }
        return w;
    }

    public static int getSmallTextWidth(String text) {
        if (text == null || text.isEmpty()) return 0;
        if (!isFontLoaded) return text.length() * 6;
        int w = 0;
        for (int i = 0; i < text.length(); i++) {
            int gid = glyphMapSmall[text.charAt(i)] & 0xFFFF;
            w += (gid != 0) ? smallAdv[gid] : 5;
        }
        return w;
    }

    public static void drawIcon(String path, int x, int y, int w, int h, int color) {
        long id = getTexture(path);
        if (id == -1) {
            fill(x, y, x + w / 2, y + h / 2, 0xFFFF00FF); fill(x + w / 2, y, x + w, y + h / 2, 0xFF000000);
            fill(x, y + h / 2, x + w / 2, y + h, 0xFF000000); fill(x + w / 2, y + h / 2, x + w, y + h, 0xFFFF00FF);
            return;
        }
        float a = (color >> 24 & 255) / 255.0f, r = (color >> 16 & 255) / 255.0f;
        float g = (color >> 8 & 255) / 255.0f, b = (color & 255) / 255.0f; if (a == 0) a = 1.0f;
        drawQuad(x, y, x + w, y + h, 0, 0, 1, 1, r, g, b, a, 1, id);
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
        fill(x, y, x + w, y + h, isOn ? 0xFF153315 : 0xFF121212);
        drawOutline(x, y, w, h, isOn ? 0xFF55FF55 : 0xFF444444);
        drawSmallText(text, x + 8, y + (h - SMALL_FONT_HEIGHT) / 2, isOn ? 0xFF55FF55 : 0xFF888888);
    }

    public static void drawDeleteToolToggle(String text, int x, int y, int w, int h, boolean isOn) {
        fill(x, y, x + w, y + h, isOn ? 0xFF3A1212 : 0xFF121212);
        drawOutline(x, y, w, h, isOn ? 0xFFFF5555 : 0xFF444444);
        drawSmallText(text, x + 8, y + (h - SMALL_FONT_HEIGHT) / 2, isOn ? 0xFFFF5555 : 0xFF888888);
    }

    public static void drawSettingRow(String label, String value, int x, int y, int w, int h, boolean active) {
        fill(x, y, x + w, y + h, active ? 0xFF142614 : 0xFF101010);
        drawOutline(x, y, w, h, active ? 0xFF44CC44 : 0xFF333333);
        drawSmallText(label, x + 6, y + (h - SMALL_FONT_HEIGHT) / 2, 0xFFDDDDDD);
        drawSmallText(value, x + w - getSmallTextWidth(value) - 6, y + (h - SMALL_FONT_HEIGHT) / 2, active ? 0xFF55FF55 : 0xFFAAAAAA);
    }

    public static void drawTinyModeButton(String letter, int x, int y, int size, boolean active, boolean disabled) {
        fill(x, y, x + size, y + size, disabled ? 0xFF111111 : (active ? 0xFF333333 : 0xFF080808));
        drawOutline(x, y, size, size, disabled ? 0xFF2A2A2A : (active ? 0xFFFFFFFF : 0xFF555555));
        drawText(letter, x + (size - getTextWidth(letter)) / 2, y + (size - FONT_HEIGHT) / 2 + 1, disabled ? 0xFF555555 : (active ? 0xFFFFFFAA : 0xFFAAAAAA));
    }

    private static void drawCameraViewfinder(int sWidth, int sHeight) {
        int col = 0xEEFFFFFF, t = 1;
        int marginX = Math.max(18, sWidth / 22), marginY = Math.max(14, sHeight / 18);
        int outerLenX = Math.max(38, sWidth / 8), outerLenY = Math.max(30, sHeight / 6);
        int ox0 = marginX, oy0 = marginY, ox1 = sWidth - marginX, oy1 = sHeight - marginY;

        fill(ox0, oy0, ox0 + outerLenX, oy0 + t, col); fill(ox0, oy0, ox0 + t, oy0 + outerLenY, col);
        fill(ox1 - outerLenX, oy0, ox1, oy0 + t, col); fill(ox1 - t, oy0, ox1, oy0 + outerLenY, col);
        fill(ox0, oy1 - t, ox0 + outerLenX, oy1, col); fill(ox0, oy1 - outerLenY, ox0 + t, oy1, col);
        fill(ox1 - outerLenX, oy1 - t, ox1, oy1, col); fill(ox1 - t, oy1 - outerLenY, ox1, oy1, col);

        int cx = sWidth / 2, cy = sHeight / 2;
        int innerHalfW = Math.max(26, sHeight / 9), innerHalfH = Math.max(20, sHeight / 11);
        int innerArmX = Math.max(11, innerHalfW / 2), innerArmY = Math.max(11, innerHalfH / 2);
        int ix0 = cx - innerHalfW, iy0 = cy - innerHalfH, ix1 = cx + innerHalfW, iy1 = cy + innerHalfH;

        fill(ix0, iy0, ix0 + innerArmX, iy0 + t, col); fill(ix0, iy0, ix0 + t, iy0 + innerArmY, col);
        fill(ix1 - innerArmX, iy0, ix1, iy0 + t, col); fill(ix1 - t, iy0, ix1, iy0 + innerArmY, col);
        fill(ix0, iy1 - t, ix0 + innerArmX, iy1, col); fill(ix0, iy1 - innerArmY, ix0 + t, iy1, col);
        fill(ix1 - innerArmX, iy1 - t, ix1, iy1, col); fill(ix1 - t, iy1 - innerArmY, ix1, oy1, col);

        int crossR = Math.max(5, sHeight / 52);
        fill(cx - crossR, cy, cx + crossR + 1, cy + t, col);
        fill(cx, cy - crossR, cx + t, cy + crossR + 1, col);
    }

    private static void drawHoverTooltip(String title, String desc, int mx, int my, int sWidth, int sHeight) {
        if (desc == null || desc.isEmpty()) return;
        int w1 = (title != null && !title.isEmpty()) ? getSmallTextWidth(title) : 0;
        int boxW = Math.max(w1, getSmallTextWidth(desc)) + 16;
        int boxH = (title != null && !title.isEmpty()) ? 34 : 20;
        int bx = mx + 14, by = my + 12;
        if (bx + boxW > sWidth - 6) bx = Math.max(6, mx - boxW - 10);
        if (by + boxH > sHeight - 30) by = Math.max(6, my - boxH - 8);

        fill(bx, by, bx + boxW, by + boxH, 0xEE08090D);
        drawOutline(bx, by, boxW, boxH, 0xFF557799);
        fill(bx, by, bx + 2, by + boxH, 0xFF55FF55);

        if (title != null && !title.isEmpty()) {
            drawSmallText(title, bx + 8, by + 3, 0xFFFFFFFF);
            drawSmallText(desc, bx + 8, by + 17, 0xFFAAAAAA);
        } else {
            drawSmallText(desc, bx + 8, by + 3, 0xFFDDDDDD);
        }
    }

    public static void addNotification(String text) { SpaceEngine.notifications.add(0, new SpaceEngine.Notification(text)); }

    public static String formatTimeScale(double scale) {
        if (scale < 60) return String.format(Locale.US, "%.1f sec/sec", scale);
        if (scale < 3600) return String.format(Locale.US, "%.1f min/sec", scale / 60);
        if (scale < 86400) return String.format(Locale.US, "%.1f hr/sec", scale / 3600);
        if (scale < 31536000) return String.format(Locale.US, "%.1f days/sec", scale / 86400);
        return formatCompact(scale / 31536000) + " yrs/sec";
    }

    public static String formatFlightSpeed(double speedKmS) {
        if (speedKmS >= LIGHT_SPEED_KMS) return String.format(Locale.US, "%.2f c", speedKmS / LIGHT_SPEED_KMS);
        if (speedKmS >= 1.0) return String.format(Locale.US, "%.1f km/s", speedKmS);
        double speedMs = speedKmS * 1000.0;
        return (speedMs >= 1.0) ? String.format(Locale.US, "%.1f m/s", speedMs) : String.format(Locale.US, "%.1f km/h", speedKmS * 3600.0);
    }

    public static String formatCompact(double v) {
        if (v == 0.0) return "0";
        double abs = Math.abs(v);
        if (abs < 0.01 || abs >= 1e12) return String.format(Locale.US, "%.2e", v);
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

        SCRATCH_UVA[0] = (float) (camXa / camZa);
        SCRATCH_UVA[1] = (float) (-camYa / camZa);
        SCRATCH_UVB[0] = (float) (camXb / camZb);
        SCRATCH_UVB[1] = (float) (-camYb / camZb);

        CameraManager.invertLensWarpUv(SCRATCH_UVA, camPos, forward, right, up);
        CameraManager.invertLensWarpUv(SCRATCH_UVB, camPos, forward, right, up);

        outCoords[0] = 0.5f * sWidth + SCRATCH_UVA[0] * sHeight;
        outCoords[1] = 0.5f * sHeight + SCRATCH_UVA[1] * sHeight;
        outCoords[2] = 0.5f * sWidth + SCRATCH_UVB[0] * sHeight;
        outCoords[3] = 0.5f * sHeight + SCRATCH_UVB[1] * sHeight;
        return true;
    }

    private static boolean is3DPointOccludedByPlanets(Vector3d pt, Vector3d camPos) {
        SCRATCH_RAY.set(pt).sub(camPos);
        double segLen = SCRATCH_RAY.length();
        if (segLen < 1e-5) return false;
        SCRATCH_RAY.div(segLen);

        for (int i = 0, sz = planets.size(); i < sz; i++) {
            Planet p = planets.get(i);
            if (p == null || p.mass <= 0.0) continue;
            double r = p.radius;
            double dx = pt.x - p.pos.x, dy = pt.y - p.pos.y, dz = pt.z - p.pos.z;
            double tiltRad = Math.toRadians(p.tilt), rotRad = Math.toRadians(p.currentAngle);

            if (dx * dx + dy * dy + dz * dz < 3.0 * r * r) {
                SCRATCH_REL_PT.set(dx, dy, dz).rotateZ(-tiltRad).rotateY(-rotRad);
                if (Math.abs(SCRATCH_REL_PT.x) <= r * 1.005 && Math.abs(SCRATCH_REL_PT.y) <= r * 1.005 && Math.abs(SCRATCH_REL_PT.z) <= r * 1.005) return true;
            }

            double camToPx = p.pos.x - camPos.x, camToPy = p.pos.y - camPos.y, camToPz = p.pos.z - camPos.z;
            double proj = camToPx * SCRATCH_RAY.x + camToPy * SCRATCH_RAY.y + camToPz * SCRATCH_RAY.z;
            double boundR = r * 1.75;
            if (proj + boundR <= 0.0 || proj - boundR >= segLen) continue;
            double ox = camToPx - SCRATCH_RAY.x * proj, oy = camToPy - SCRATCH_RAY.y * proj, oz = camToPz - SCRATCH_RAY.z * proj;
            if (ox * ox + oy * oy + oz * oz > boundR * boundR) continue;

            SCRATCH_RO.set(-camToPx, -camToPy, -camToPz).rotateZ(-tiltRad).rotateY(-rotRad);
            SCRATCH_RD.set(SCRATCH_RAY).rotateZ(-tiltRad).rotateY(-rotRad);

            double ix = 1.0 / (Math.abs(SCRATCH_RD.x) < 1e-7 ? 1e-7 : SCRATCH_RD.x);
            double iy = 1.0 / (Math.abs(SCRATCH_RD.y) < 1e-7 ? 1e-7 : SCRATCH_RD.y);
            double iz = 1.0 / (Math.abs(SCRATCH_RD.z) < 1e-7 ? 1e-7 : SCRATCH_RD.z);

            double t0x = (-r - SCRATCH_RO.x) * ix, t1x = (r - SCRATCH_RO.x) * ix;
            double t0y = (-r - SCRATCH_RO.y) * iy, t1y = (r - SCRATCH_RO.y) * iy;
            double t0z = (-r - SCRATCH_RO.z) * iz, t1z = (r - SCRATCH_RO.z) * iz;

            double tNear = Math.max(Math.max(Math.min(t0x, t1x), Math.min(t0y, t1y)), Math.min(t0z, t1z));
            double tFar = Math.min(Math.min(Math.max(t0x, t1x), Math.max(t0y, t1y)), Math.max(t0z, t1z));

            if (tNear < tFar && tFar > 0.0) {
                double hitT = (tNear > 0.0) ? tNear : tFar;
                if (hitT < segLen - 0.01) return true;
            }
        }
        return false;
    }

    private static void drawOccluded3DSegment(
            Vector3d a, Vector3d b,
            Vector3d camPos, Vector3d forward, Vector3d right, Vector3d up,
            int sWidth, int sHeight, float thickness, float r, float g, float bCol, float alpha, float[] segBuf) {
        boolean occA = is3DPointOccludedByPlanets(a, camPos);
        boolean occB = is3DPointOccludedByPlanets(b, camPos);
        if (occA && occB) return;

        if (!occA && !occB) {
            SCRATCH_MID.set(a).add(b).mul(0.5);
            if (!is3DPointOccludedByPlanets(SCRATCH_MID, camPos)) {
                if (project3DSegment(a, b, camPos, forward, right, up, sWidth, sHeight, segBuf)) {
                    SBoxVulkanUI.drawLine(segBuf[0], segBuf[1], segBuf[2], segBuf[3], thickness, r, g, bCol, alpha);
                }
                return;
            }
        }

        if (occA != occB) {
            double lo = 0.0, hi = 1.0;
            for (int iter = 0; iter < 9; iter++) {
                double mid = 0.5 * (lo + hi);
                SCRATCH_PROBE.set(a).lerp(b, mid);
                if (is3DPointOccludedByPlanets(SCRATCH_PROBE, camPos) == occA) lo = mid;
                else hi = mid;
            }
            SCRATCH_CLIP.set(a).lerp(b, occA ? hi : lo);
            if (project3DSegment(occA ? SCRATCH_CLIP : a, occA ? b : SCRATCH_CLIP, camPos, forward, right, up, sWidth, sHeight, segBuf)) {
                SBoxVulkanUI.drawLine(segBuf[0], segBuf[1], segBuf[2], segBuf[3], thickness, r, g, bCol, alpha);
            }
            return;
        }

        final int SUB = 12;
        for (int k = 0; k < SUB; k++) {
            SCRATCH_SUB0.set(a).lerp(b, (double) k / SUB);
            SCRATCH_SUB1.set(a).lerp(b, (double) (k + 1) / SUB);
            if (!is3DPointOccludedByPlanets(SCRATCH_SUB0, camPos) && !is3DPointOccludedByPlanets(SCRATCH_SUB1, camPos)) {
                if (project3DSegment(SCRATCH_SUB0, SCRATCH_SUB1, camPos, forward, right, up, sWidth, sHeight, segBuf)) {
                    SBoxVulkanUI.drawLine(segBuf[0], segBuf[1], segBuf[2], segBuf[3], thickness, r, g, bCol, alpha);
                }
            }
        }
    }

    private static void render3DOverlays(int sWidth, int sHeight) {
        double pitch = Math.toRadians(CameraManager.rot.x);
        double yaw = Math.toRadians(CameraManager.rot.y);

        SCRATCH_FORWARD.set(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), -Math.cos(yaw) * Math.cos(pitch)).normalize();
        SCRATCH_RIGHT.set(SCRATCH_FORWARD).cross(0.0, 1.0, 0.0).normalize();
        SCRATCH_UP.set(SCRATCH_RIGHT).cross(SCRATCH_FORWARD).normalize();
        Vector3d camPos = CameraManager.pos;

        if (showTrails) {
            for (int pIdx = 0, sz = planets.size(); pIdx < sz; pIdx++) {
                Planet p = planets.get(pIdx);
                int n = p.trailCount;
                if (n < 2) continue;
                float r = p.rColor / 255.0f, g = p.gColor / 255.0f, b = p.bColor / 255.0f;
                for (int i = 0; i < n - 1; i++) {
                    float alpha = ((float) (i + 1) / n) * 0.65f;
                    drawOccluded3DSegment(p.getTrailPoint(i), p.getTrailPoint(i + 1), camPos, SCRATCH_FORWARD, SCRATCH_RIGHT, SCRATCH_UP, sWidth, sHeight, 1.0f, r, g, b, alpha, SCRATCH_SEG);
                }
                drawOccluded3DSegment(p.getTrailPoint(n - 1), p.pos, camPos, SCRATCH_FORWARD, SCRATCH_RIGHT, SCRATCH_UP, sWidth, sHeight, 1.0f, r, g, b, 0.65f, SCRATCH_SEG);
            }
        }

        if (isPlacingPlanet && currentSpawnMode == SpawnMode.ORBIT && orbitTarget != null && planets.contains(orbitTarget)) {
            MenuItem item = menuItems[selectedPlanetIndex];
            Vector3d holoPos = getHologramPos(item.radius);
            SCRATCH_ORBIT_UP.set(0.0, 1.0, 0.0);

            if (isDraggingOrbit) {
                getInclinedOrbitState(baseOrbitPos, orbitInclination, orbitTarget, holoPos, SCRATCH_ORBIT_UP);
            }

            calculateSpawnVelocity(holoPos, SCRATCH_ORBIT_UP);

            Vector3d center = orbitTarget.pos;
            SCRATCH_RADIAL.set(holoPos).sub(center);
            double orbitRadius = SCRATCH_RADIAL.length();

            if (orbitRadius > 0.01) {
                SCRATCH_TANGENT.set(SCRATCH_ORBIT_UP).cross(SCRATCH_RADIAL);
                if (SCRATCH_TANGENT.lengthSquared() < 1e-6) SCRATCH_TANGENT.set(1.0, 0.0, 0.0).cross(SCRATCH_RADIAL);
                SCRATCH_TANGENT.normalize().mul(orbitRadius);

                float lineR = validSpawn ? 0.85f : 1.0f;
                float lineG = validSpawn ? 0.85f : 0.25f;
                float lineB = validSpawn ? 0.85f : 0.25f;

                int segments = 128;
                for (int s = 0; s <= segments; s++) {
                    double theta = (s * 2.0 * Math.PI) / segments;
                    SCRATCH_CURR_PT.set(center).fma(Math.cos(theta), SCRATCH_RADIAL).fma(Math.sin(theta), SCRATCH_TANGENT);
                    if (s > 0) {
                        drawOccluded3DSegment(SCRATCH_PREV_PT, SCRATCH_CURR_PT, camPos, SCRATCH_FORWARD, SCRATCH_RIGHT, SCRATCH_UP, sWidth, sHeight, 1.0f, lineR, lineG, lineB, 0.75f, SCRATCH_SEG);
                    }
                    SCRATCH_PREV_PT.set(SCRATCH_CURR_PT);
                }

                final int RAD_SUB = 24;
                for (int rStep = 0; rStep < RAD_SUB; rStep++) {
                    SCRATCH_PREV_PT.set(center).lerp(holoPos, (double) rStep / RAD_SUB);
                    SCRATCH_CURR_PT.set(center).lerp(holoPos, (double) (rStep + 1) / RAD_SUB);
                    drawOccluded3DSegment(SCRATCH_PREV_PT, SCRATCH_CURR_PT, camPos, SCRATCH_FORWARD, SCRATCH_RIGHT, SCRATCH_UP, sWidth, sHeight, 1.15f, lineR, lineG, lineB, 0.9f, SCRATCH_SEG);
                }

                if (project3DSegment(center, holoPos, camPos, SCRATCH_FORWARD, SCRATCH_RIGHT, SCRATCH_UP, sWidth, sHeight, SCRATCH_SEG)) {
                    SCRATCH_MID_3D.set(center).add(holoPos).mul(0.5);
                    double mx = SCRATCH_MID_3D.x - camPos.x, my = SCRATCH_MID_3D.y - camPos.y, mz = SCRATCH_MID_3D.z - camPos.z;
                    double mCamZ = mx * SCRATCH_FORWARD.x + my * SCRATCH_FORWARD.y + mz * SCRATCH_FORWARD.z;

                    float labelX, labelY;
                    if (mCamZ > 0.1) {
                        double mCamX = mx * SCRATCH_RIGHT.x + my * SCRATCH_RIGHT.y + mz * SCRATCH_RIGHT.z;
                        double mCamY = mx * SCRATCH_UP.x + my * SCRATCH_UP.y + mz * SCRATCH_UP.z;
                        SCRATCH_UVA[0] = (float) (mCamX / mCamZ);
                        SCRATCH_UVA[1] = (float) (-mCamY / mCamZ);
                        CameraManager.invertLensWarpUv(SCRATCH_UVA, camPos, SCRATCH_FORWARD, SCRATCH_RIGHT, SCRATCH_UP);
                        labelX = 0.5f * sWidth + SCRATCH_UVA[0] * sHeight;
                        labelY = 0.5f * sHeight + SCRATCH_UVA[1] * sHeight;
                    } else {
                        labelX = (SCRATCH_SEG[0] + SCRATCH_SEG[2]) * 0.5f;
                        labelY = (SCRATCH_SEG[1] + SCRATCH_SEG[3]) * 0.5f;
                    }

                    float angle = (float) Math.atan2(SCRATCH_SEG[3] - SCRATCH_SEG[1], SCRATCH_SEG[2] - SCRATCH_SEG[0]);
                    if (angle > Math.PI * 0.5f || angle < -Math.PI * 0.5f) angle += (float) Math.PI;

                    labelX += (float) Math.sin(angle) * 8.0f;
                    labelY -= (float) Math.cos(angle) * 8.0f;

                    drawRotatedSmallText(formatDistanceKm(orbitRadius * BLOCK_TO_KM), labelX, labelY, angle, validSpawn ? 0xFFFFFFFF : 0xFFFF5555);
                }
            }
        }
    }

    private static void renderNotificationsAndSpeedometer(int sWidth, double dtUI) {
        if (!CameraManager.isLocked) {
            long elapsedScroll = System.currentTimeMillis() - lastScrollTime;
            if (elapsedScroll < 2000 && !isWelcomeMenuOpen) {
                float alpha = elapsedScroll < 1500 ? 1.0f : 1.0f - (elapsedScroll - 1500) / 500.0f;
                int aInt = (int) (alpha * 255);
                if (aInt > 5) {
                    String speedTxt = tr(32) + formatFlightSpeed(playerFlightSpeed);
                    drawText(speedTxt, sWidth / 2 - getTextWidth(speedTxt) / 2, 25, (aInt << 24) | 0xFFFFFF);
                }
            }
        }

        for (int i = 0; i < notifications.size(); i++) {
            Notification n = notifications.get(i);
            n.targetY = 12 + i * 26;
            n.currentY += (n.targetY - n.currentY) * Math.min(1.0, dtUI * 15.0);
            n.life -= dtUI;
            if (i >= 3 || n.life <= 0) n.alpha -= dtUI * 4.0;
            else n.alpha = Math.min(1.0, n.alpha + dtUI * 5.0);

            if (n.alpha <= 0) {
                notifications.remove(i);
                i--;
            } else {
                int tw = getSmallTextWidth(n.text);
                int notifW = tw + 26, notifH = 22;
                int x = sWidth - notifW - 10, y = (int) Math.round(n.currentY);
                int aInt = (int) (n.alpha * 255);
                if (aInt > 5) {
                    fill(x, y, x + notifW, y + notifH, (aInt << 24) | 0x111111);
                    drawOutline(x, y, notifW, notifH, (aInt << 24) | 0x555555);
                    fill(x + notifW - 3, y, x + notifW, y + notifH, (aInt << 24) | 0x55FF55);
                    drawSmallText(n.text, x + 10, y + (notifH - SMALL_FONT_HEIGHT) / 2, (aInt << 24) | 0xFFFFFF);
                }
            }
        }
    }

    public static void renderDirectUI(long window, int sWidth, int sHeight, float uiScale) {
        if (menuItems == null || menuItems.length == 0) return;
        applyInitialDefaultSettings(window);
        SBoxCompute.applyCustomWindowIcons(window);

        if (SpaceEngine.fsrEnabled) {
            SBoxVulkanUI.drawFsrEasuRcasPass(sWidth, sHeight, activeRenderW, activeRenderH, SBoxVulkanCanvas.canvasWidth, SBoxVulkanCanvas.canvasHeight);
        }

        if (SpaceEngine.shaderMode == 1 && SBoxVulkanUI.canvasBloomDescriptorSet != 0) {
            float uMax = (float) activeRenderW / Math.max(1, SBoxVulkanCanvas.canvasWidth);
            float vMax = (float) activeRenderH / Math.max(1, SBoxVulkanCanvas.canvasHeight);
            SBoxVulkanUI.drawQuad(0, 0, sWidth, sHeight, 0.0f, 0.0f, uMax, vMax, 1.0f, 1.0f, 1.0f, 1.0f, 3, SBoxVulkanUI.canvasBloomDescriptorSet);
        }

        double currentRealTime = System.currentTimeMillis() / 1000.0;
        if (lastUiTime == 0) lastUiTime = currentRealTime;
        double dtUI = currentRealTime - lastUiTime;
        lastUiTime = currentRealTime;

        if (showGrid && !prevShowGrid && showGravityGrid) showGravityGrid = false;
        if (showGravityGrid && !prevShowGravityGrid && showGrid) showGrid = false;

        if (!initNotif) { addNotification("SpaceSB Engine Active"); initNotif = true; }
        if (prevShowGrid != showGrid) { addNotification(tr(17) + ": " + (showGrid ? tr(22) : tr(23))); prevShowGrid = showGrid; }
        if (prevShowGravityGrid != showGravityGrid) { addNotification(tr(33) + ": " + (showGravityGrid ? tr(22) : tr(23))); prevShowGravityGrid = showGravityGrid; }
        if (prevShowTrails != showTrails) { addNotification(tr(18) + ": " + (showTrails ? tr(22) : tr(23))); prevShowTrails = showTrails; }
        if (prevShowHabitableZone != showHabitableZone) { addNotification(tr(19) + ": " + (showHabitableZone ? tr(22) : tr(23))); prevShowHabitableZone = showHabitableZone; }
        if (prevPhysicsEnabled != physicsEnabled) { addNotification(tr(20) + ": " + (physicsEnabled ? tr(22) : tr(23))); prevPhysicsEnabled = physicsEnabled; }
        if (prevMoveTool != isMoveToolActive) { addNotification(tr(16) + ": " + (isMoveToolActive ? tr(22) : tr(23))); prevMoveTool = isMoveToolActive; }
        if (prevDeleteTool != isDeleteToolActive) { addNotification(tr(21) + ": " + (isDeleteToolActive ? tr(22) : tr(23))); prevDeleteTool = isDeleteToolActive; }
        if (prevPaused != isPaused && !SpaceEngine.isPhotoModeActive) {
            addNotification(isPaused ? "Simulation Paused" : "Simulation Resumed");
            prevPaused = isPaused;
        }

        if (SpaceEngine.isPhotoModeActive) {
            drawCameraViewfinder(sWidth, sHeight);
            renderNotificationsAndSpeedometer(sWidth, dtUI);
            return;
        }

        double mX, mY;
        boolean leftDown;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            DoubleBuffer mx = stack.mallocDouble(1), my = stack.mallocDouble(1);
            IntBuffer ww = stack.mallocInt(1), wh = stack.mallocInt(1);
            GLFW.glfwGetCursorPos(window, mx, my);
            GLFW.glfwGetWindowSize(window, ww, wh);
            mX = mx.get(0) * ((double) sWidth / Math.max(1, ww.get(0)));
            mY = my.get(0) * ((double) sHeight / Math.max(1, wh.get(0)));
            leftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        }
        boolean leftClickedNow = leftDown && !prevLeftMouseDown;
        prevLeftMouseDown = leftDown;

        String hoverTitle = null, hoverDesc = null;

        animAddY += ((isAddMenuOpen ? 1.0 : 0.0) - animAddY) * Math.min(1.0, dtUI * 18.0);
        animToolsY += ((isToolsMenuOpen ? 1.0 : 0.0) - animToolsY) * Math.min(1.0, dtUI * 18.0);

        if (!isWelcomeMenuOpen) render3DOverlays(sWidth, sHeight);

        int barHeight = 26, rectY = sHeight - barHeight;
        int escPanelW = Math.max(260, (int) (sWidth * 0.28f));
        if (CameraManager.isLocked || !isEscMenuOpen) isLanguageMenuOpen = false;
        if (CameraManager.isLocked) { isEscMenuOpen = false; isResolutionMenuOpen = false; }

        double targetEscX = (isEscMenuOpen && !CameraManager.isLocked && !isWelcomeMenuOpen) ? 0.0 : -escPanelW;
        escMenuAnimX += (targetEscX - escMenuAnimX) * Math.min(1.0, dtUI * 18.0);
        int escX = (int) Math.round(escMenuAnimX);

        if (escX > -escPanelW + 1 && !CameraManager.isLocked && !isWelcomeMenuOpen) {
            int escH = rectY, pad = 12;
            fill(escX, 0, escX + escPanelW, escH, 0xFF050505);
            fill(escX + escPanelW - 1, 0, escX + escPanelW, escH, 0xFF333333);

            int logoW = Math.min(138, escPanelW - pad * 2), logoH = 44;
            drawIcon(LOGO_TEX, escX + (escPanelW - logoW) / 2, 6, logoW, logoH);

            int topSepY = 55;
            fill(escX + pad, topSepY, escX + escPanelW - pad, topSepY + 1, 0xFF555555);

            int gap = 8, gridBtnW = (escPanelW - pad * 2 - gap) / 2, gridBtnH = 24, gridStartY = topSepY + 6;
            String[] topTitles = {tr(1), ""};

            for (int b = 0; b < 2; b++) {
                int bx = escX + pad + b * (gridBtnW + gap), by = gridStartY;
                escTopBtnHits[b][0] = bx; escTopBtnHits[b][1] = by;
                escTopBtnHits[b][2] = isLanguageMenuOpen ? 0 : gridBtnW; escTopBtnHits[b][3] = gridBtnH;

                boolean hovered = (!isLanguageMenuOpen && mX >= bx && mX <= bx + gridBtnW && mY >= by && mY <= by + gridBtnH);
                fill(bx, by, bx + gridBtnW, by + gridBtnH, b == 0 ? (hovered ? 0xFF1E2634 : 0xFF141820) : 0xFF0D0D0D);
                drawOutline(bx, by, gridBtnW, gridBtnH, b == 0 ? 0xFF6688AA : 0xFF2E2E2E);

                if (!topTitles[b].isEmpty()) {
                    drawSmallText(topTitles[b], bx + (gridBtnW - getSmallTextWidth(topTitles[b])) / 2, by + (gridBtnH - SMALL_FONT_HEIGHT) / 2, 0xFFFFFFFF);
                }
                if (hovered && b == 0) {
                    hoverTitle = tr(1);
                    hoverDesc = "Clears all planets and resets simulation time to 2026";
                }
            }

            int sepY = gridStartY + gridBtnH + 6;
            fill(escX + pad, sepY, escX + escPanelW - pad, sepY + 1, 0xFF333333);
            drawSmallText(tr(2), escX + pad, sepY + 4, 0xFF888888);

            int rowX = escX + pad, rowW = escPanelW - pad * 2, rowH = 17, rowGap = 4, startSetY = sepY + 18;
            String onStr = tr(22), offStr = tr(23);

            escSettingHits[0][0] = rowX; escSettingHits[0][1] = startSetY; escSettingHits[0][2] = isLanguageMenuOpen ? 0 : rowW; escSettingHits[0][3] = rowH;
            drawSettingRow("AMD FSR 1.0", fsrEnabled ? onStr : offStr, rowX, startSetY, rowW, rowH, fsrEnabled);

            int fsrRowY = startSetY + (rowH + rowGap);
            drawSmallText(tr(3), rowX, fsrRowY + 2, fsrEnabled ? 0xFFDDDDDD : 0xFF777777);

            int fTrackX = rowX + 74, fTrackW = rowW - 74, fTrackH = 14, fZoneW = fTrackW / 4;
            escFsrTrackHit[0] = fTrackX; escFsrTrackHit[1] = fsrRowY + 2;
            escFsrTrackHit[2] = isLanguageMenuOpen ? 0 : fTrackW; escFsrTrackHit[3] = fTrackH;

            for (int z = 0; z < 4; z++) {
                int zx0 = fTrackX + z * fZoneW, zx1 = (z == 3) ? (fTrackX + fTrackW) : (zx0 + fZoneW);
                fill(zx0, fsrRowY + 2, zx1, fsrRowY + 2 + fTrackH, (fsrQualityIndex == z) ? (fsrEnabled ? 0xFFDCDCDC : 0xFF666666) : 0xFF181818);
                if (z > 0) fill(zx0, fsrRowY + 3, zx0 + 1, fsrRowY + 1 + fTrackH, 0xFF333333);
            }
            drawOutline(fTrackX, fsrRowY + 2, fTrackW, fTrackH, fsrEnabled ? 0xFFAAAAAA : 0xFF444444);

            if (!isDraggingFsrCube) {
                fsrSliderPos += (((fsrQualityIndex + 0.5f) / 4.0f) - fsrSliderPos) * (float) Math.min(1.0, dtUI * 20.0);
            }
            int fCubeSize = 16;
            int fCubeX = Math.max(fTrackX, Math.min(fTrackX + fTrackW - fCubeSize, fTrackX + Math.round(fsrSliderPos * fTrackW) - fCubeSize / 2));
            fill(fCubeX, fsrRowY + 1, fCubeX + fCubeSize, fsrRowY + 1 + fCubeSize, 0xFFFFFFFF);
            drawOutline(fCubeX, fsrRowY + 1, fCubeSize, fCubeSize, 0xFF000000);
            fill(fCubeX + 3, fsrRowY + 4, fCubeX + fCubeSize - 3, fsrRowY + fCubeSize - 2, fsrEnabled ? 0xFFFFFFFF : 0xFF999999);

            int hdrY = startSetY + 2 * (rowH + rowGap);
            escSettingHits[2][0] = rowX; escSettingHits[2][1] = hdrY; escSettingHits[2][2] = isLanguageMenuOpen ? 0 : rowW; escSettingHits[2][3] = rowH;
            drawSettingRow("HDR", hdrEnabled ? onStr : offStr, rowX, hdrY, rowW, rowH, hdrEnabled);

            String[] sLabels = {tr(4), tr(5), tr(6), tr(9), tr(10)};
            int[] res = RESOLUTIONS[resolutionIndex];
            String[] sValues = {
                    vsyncEnabled ? onStr : offStr,
                    res[0] + "x" + res[1] + (isResolutionMenuOpen ? " ^" : " v"),
                    isFullscreen ? tr(7) : tr(8),
                    showFpsOverlay ? onStr : offStr,
                    photoModeFeatureEnabled ? onStr : offStr
            };
            boolean[] sActive = {vsyncEnabled, true, isFullscreen, showFpsOverlay, photoModeFeatureEnabled};

            for (int idx = 0; idx < 5; idx++) {
                int s = idx + 4, sy = startSetY + (idx + 3) * (rowH + rowGap);
                escSettingHits[s][0] = rowX; escSettingHits[s][1] = sy;
                escSettingHits[s][2] = isLanguageMenuOpen ? 0 : rowW; escSettingHits[s][3] = rowH;
                drawSettingRow(sLabels[idx], sValues[idx], rowX, sy, rowW, rowH, sActive[idx]);
            }

            int shRowY = startSetY + 8 * (rowH + rowGap) + 3;
            drawSmallText(tr(11), rowX, shRowY + 1, 0xFFDDDDDD);

            int sTrackX = rowX + 74, sTrackW = rowW - 74, sTrackH = 14, sZoneW = sTrackW / 3;
            escShaderTrackHit[0] = sTrackX; escShaderTrackHit[1] = shRowY;
            escShaderTrackHit[2] = isLanguageMenuOpen ? 0 : sTrackW; escShaderTrackHit[3] = sTrackH;

            fill(sTrackX, shRowY, sTrackX + sZoneW, shRowY + sTrackH, shaderMode == 2 ? 0xFF28BB38 : 0xFF154E1B);
            fill(sTrackX + sZoneW, shRowY, sTrackX + sZoneW * 2, shRowY + sTrackH, shaderMode == 1 ? 0xFFCCAA20 : 0xFF554410);
            fill(sTrackX + sZoneW * 2, shRowY, sTrackX + sTrackW, shRowY + sTrackH, shaderMode == 0 ? 0xFFCC2828 : 0xFF551515);
            drawOutline(sTrackX, shRowY, sTrackW, sTrackH, 0xFF444444);

            fill(sTrackX + sZoneW, shRowY + 1, sTrackX + sZoneW + 1, shRowY + sTrackH - 1, 0xFF222222);
            fill(sTrackX + sZoneW * 2, shRowY + 1, sTrackX + sZoneW * 2 + 1, shRowY + sTrackH - 1, 0xFF222222);

            if (!isDraggingShaderCube) {
                float targetShPos = (shaderMode == 2) ? 0.166f : (shaderMode == 1 ? 0.5f : 0.833f);
                shaderSliderPos += (targetShPos - shaderSliderPos) * (float) Math.min(1.0, dtUI * 20.0);
            }
            int sCubeSize = 16;
            int sCubeX = Math.max(sTrackX, Math.min(sTrackX + sTrackW - sCubeSize, sTrackX + Math.round(shaderSliderPos * sTrackW) - sCubeSize / 2));
            fill(sCubeX, shRowY - 1, sCubeX + sCubeSize, shRowY - 1 + sCubeSize, 0xFFEEEEEE);
            drawOutline(sCubeX, shRowY - 1, sCubeSize, sCubeSize, 0xFF000000);
            fill(sCubeX + 3, shRowY + 2, sCubeX + sCubeSize - 3, shRowY + sCubeSize - 4, shaderMode == 2 ? 0xFF44FF55 : (shaderMode == 1 ? 0xFFFFDD33 : 0xFFFF4444));

            int pRowY = shRowY + (rowH + rowGap);
            drawSmallText(tr(12), rowX, pRowY + 1, 0xFFDDDDDD);

            int trackX = rowX + 74, trackW = rowW - 74, trackH = 14, zoneW = trackW / 4;
            escParticleTrackHit[0] = trackX; escParticleTrackHit[1] = pRowY;
            escParticleTrackHit[2] = isLanguageMenuOpen ? 0 : trackW; escParticleTrackHit[3] = trackH;

            if (isDraggingParticleCube && !isLanguageMenuOpen) {
                particleSliderPos = Math.max(0.0f, Math.min(1.0f, (float) ((mX - trackX) / Math.max(1, trackW))));
                particlePreset = Math.min(3, Math.max(0, (int) (particleSliderPos * 4.0f)));
            }

            fill(trackX, pRowY, trackX + zoneW, pRowY + trackH, particlePreset == 0 ? 0xFF28BB38 : 0xFF154E1B);
            fill(trackX + zoneW, pRowY, trackX + zoneW * 2, pRowY + trackH, particlePreset == 1 ? 0xFFCCAA20 : 0xFF554410);
            fill(trackX + zoneW * 2, pRowY, trackX + zoneW * 3, pRowY + trackH, particlePreset == 2 ? 0xFFCC2828 : 0xFF551515);
            fill(trackX + zoneW * 3, pRowY, trackX + trackW, pRowY + trackH, particlePreset == 3 ? 0xFF8B0032 : 0xFF3B0519);
            drawOutline(trackX, pRowY, trackW, trackH, 0xFF444444);

            fill(trackX + zoneW, pRowY + 1, trackX + zoneW + 1, pRowY + trackH - 1, 0xFF222222);
            fill(trackX + zoneW * 2, pRowY + 1, trackX + zoneW * 2 + 1, pRowY + trackH - 1, 0xFF222222);
            fill(trackX + zoneW * 3, pRowY + 1, trackX + zoneW * 3 + 1, pRowY + trackH - 1, 0xFF222222);

            if (!isDraggingParticleCube) {
                particleSliderPos += (((particlePreset + 0.5f) / 4.0f) - particleSliderPos) * (float) Math.min(1.0, dtUI * 20.0);
            }

            int cubeSize = 16;
            int cubeX = Math.max(trackX, Math.min(trackX + trackW - cubeSize, trackX + Math.round(particleSliderPos * trackW) - cubeSize / 2));
            int pCubeInnerCol = (particlePreset == 0) ? 0xFF44FF55 : (particlePreset == 1 ? 0xFFFFDD33 : (particlePreset == 2 ? 0xFFFF4444 : 0xFFB80F48));

            fill(cubeX, pRowY - 1, cubeX + cubeSize, pRowY - 1 + cubeSize, 0xFFEEEEEE);
            drawOutline(cubeX, pRowY - 1, cubeSize, cubeSize, 0xFF000000);
            fill(cubeX + 3, pRowY + 2, cubeX + cubeSize - 3, pRowY + cubeSize - 4, pCubeInnerCol);

            if (!isResolutionMenuOpen && !isLanguageMenuOpen && mX >= rowX && mX <= rowX + rowW && mY >= pRowY - 2 && mY <= pRowY + trackH + 2) {
                String limStr = (particlePreset == 3) ? "UNLIMITED (Burgundy Ultra)" : (PhysicsEngine.getEffectiveDebrisLimit() + " Max");
                hoverTitle = tr(12) + " " + getParticlePresetLabel(particlePreset) + " — " + limStr;
                hoverDesc = "Green (3.2k) | Yellow (12.8k) | Red (25.6k) | Burgundy (Ultra Unlimited)";
            }

            int vRowY = pRowY + (rowH + rowGap);
            drawSmallText(tr(34), rowX, vRowY + 1, 0xFFDDDDDD);

            int vTrackX = rowX + 74, vTrackW = rowW - 74, vTrackH = 14;
            escVolumeTrackHit[0] = vTrackX; escVolumeTrackHit[1] = vRowY;
            escVolumeTrackHit[2] = isLanguageMenuOpen ? 0 : vTrackW; escVolumeTrackHit[3] = vTrackH;

            if (isDraggingVolumeCube && !isLanguageMenuOpen) {
                musicVolume = Math.max(0.0f, Math.min(1.0f, (float) ((mX - vTrackX) / Math.max(1, vTrackW))));
                SBoxAudioEngine.setMusicVolume(musicVolume);
            }

            fill(vTrackX, vRowY, vTrackX + vTrackW, vRowY + vTrackH, 0xFF141820);
            int vFillW = Math.round(musicVolume * vTrackW);
            if (vFillW > 0) {
                fill(vTrackX, vRowY, vTrackX + vFillW, vRowY + vTrackH, musicVolume > 0.005f ? 0xFF2266AA : 0xFF282828);
            }
            drawOutline(vTrackX, vRowY, vTrackW, vTrackH, 0xFF445566);

            String volLabel = (musicVolume <= 0.005f) ? tr(23) : (Math.round(musicVolume * 100.0f) + "%");
            drawSmallText(volLabel, vTrackX + (vTrackW - getSmallTextWidth(volLabel)) / 2, vRowY + 1, 0xFFFFFFFF);

            int vCubeSize = 16;
            int vCubeX = Math.max(vTrackX, Math.min(vTrackX + vTrackW - vCubeSize, vTrackX + vFillW - vCubeSize / 2));
            fill(vCubeX, vRowY - 1, vCubeX + vCubeSize, vRowY - 1 + vCubeSize, 0xFFEEEEEE);
            drawOutline(vCubeX, vRowY - 1, vCubeSize, vCubeSize, 0xFF000000);
            fill(vCubeX + 3, vRowY + 2, vCubeX + vCubeSize - 3, vRowY + vCubeSize - 4, musicVolume > 0.005f ? 0xFF44AAFF : 0xFF666666);

            int exitH = 22, exitY = escH - exitH - 8;
            int langBtnH = 20, langBtnY = exitY - langBtnH - 6;
            escLangBtnHit[0] = rowX; escLangBtnHit[1] = langBtnY; escLangBtnHit[2] = rowW; escLangBtnHit[3] = langBtnH;

            boolean langHover = (!isResolutionMenuOpen && mX >= rowX && mX <= rowX + rowW && mY >= langBtnY && mY <= langBtnY + langBtnH);
            fill(rowX, langBtnY, rowX + rowW, langBtnY + langBtnH, (isLanguageMenuOpen || langHover) ? 0xFF1A2836 : 0xFF12161E);
            drawOutline(rowX, langBtnY, rowW, langBtnH, isLanguageMenuOpen ? 0xFF55FF55 : 0xFF557799);
            drawSmallText(tr(0) + ":", rowX + 6, langBtnY + (langBtnH - SMALL_FONT_HEIGHT) / 2, 0xFFDDDDDD);

            String curLangStr = LANGUAGE_NAMES[currentLanguage] + (isLanguageMenuOpen ? " ^" : " v");
            int curLangW = getSmallTextWidth(curLangStr), flagW = 18, flagH = 12;
            int textRightX = rowX + rowW - curLangW - 6;
            drawCountryFlag(currentLanguage, textRightX - flagW - 6, langBtnY + (langBtnH - flagH) / 2, flagW, flagH);
            drawSmallText(curLangStr, textRightX, langBtnY + (langBtnH - SMALL_FONT_HEIGHT) / 2, 0xFF55FF55);

            escExitBtnHit[0] = rowX; escExitBtnHit[1] = exitY; escExitBtnHit[2] = isLanguageMenuOpen ? 0 : rowW; escExitBtnHit[3] = exitH;
            boolean exitHover = (!isResolutionMenuOpen && !isLanguageMenuOpen && mX >= rowX && mX <= rowX + rowW && mY >= exitY && mY <= exitY + exitH);
            fill(rowX, exitY, rowX + rowW, exitY + exitH, exitHover ? 0xFF661818 : 0xFF441111);
            drawOutline(rowX, exitY, rowW, exitH, 0xFFFF4444);
            String exitTxt = tr(13);
            drawText(exitTxt, rowX + (rowW - getTextWidth(exitTxt)) / 2, exitY + (exitH - FONT_HEIGHT) / 2, 0xFFFFAAAA);

            int langItemH = 18, langMenuH = LANGUAGE_NAMES.length * langItemH;
            int langMenuX = rowX, langMenuY = langBtnY - langMenuH - 2;
            escLangDropdownHit[0] = langMenuX; escLangDropdownHit[1] = langMenuY; escLangDropdownHit[2] = rowW; escLangDropdownHit[3] = langMenuH;

            if (leftClickedNow) {
                if (isLanguageMenuOpen && mX >= langMenuX && mX <= langMenuX + rowW && mY >= langMenuY && mY <= langMenuY + langMenuH) {
                    int chosenLang = (int) ((mY - langMenuY) / langItemH);
                    if (chosenLang >= 0 && chosenLang < LANGUAGE_NAMES.length) {
                        currentLanguage = chosenLang;
                        addNotification(tr(0) + ": " + LANGUAGE_NAMES[currentLanguage]);
                    }
                    isLanguageMenuOpen = false;
                } else if (mX >= rowX && mX <= rowX + rowW && mY >= langBtnY && mY <= langBtnY + langBtnH) {
                    isLanguageMenuOpen = !isLanguageMenuOpen;
                    isResolutionMenuOpen = false;
                } else if (isLanguageMenuOpen) {
                    isLanguageMenuOpen = false;
                }
            }

            if (isLanguageMenuOpen) {
                fill(langMenuX, langMenuY, langMenuX + rowW, langMenuY + langMenuH, 0xF5080B10);
                drawOutline(langMenuX, langMenuY, rowW, langMenuH, 0xFF55FF55);
                for (int lIdx = 0; lIdx < LANGUAGE_NAMES.length; lIdx++) {
                    int iy = langMenuY + lIdx * langItemH;
                    boolean lHov = (mX >= langMenuX && mX <= langMenuX + rowW && mY >= iy && mY < iy + langItemH);
                    if (lHov || lIdx == currentLanguage) {
                        fill(langMenuX + 1, iy, langMenuX + rowW - 1, iy + langItemH, lHov ? 0xFF223C28 : 0xFF142618);
                    }
                    drawCountryFlag(lIdx, langMenuX + 8, iy + (langItemH - flagH) / 2, flagW, flagH);
                    drawSmallText(LANGUAGE_NAMES[lIdx], langMenuX + 8 + flagW + 8, iy + (langItemH - SMALL_FONT_HEIGHT) / 2, lIdx == currentLanguage ? 0xFF55FF55 : 0xFFFFFFFF);
                }
            }

            if (isResolutionMenuOpen && !isLanguageMenuOpen) {
                int ddW = 105, ddItemH = 16, ddH = RESOLUTIONS.length * ddItemH;
                int ddX = rowX + rowW - ddW, ddY = escSettingHits[5][1] + rowH + 1;
                escResDropdownHit[0] = ddX; escResDropdownHit[1] = ddY; escResDropdownHit[2] = ddW; escResDropdownHit[3] = ddH;

                fill(ddX, ddY, ddX + ddW, ddY + ddH, 0xFF0A0A0D);
                drawOutline(ddX, ddY, ddW, ddH, 0xFF55FF55);
                for (int rIdx = 0; rIdx < RESOLUTIONS.length; rIdx++) {
                    int iy = ddY + rIdx * ddItemH;
                    boolean rHov = (mX >= ddX && mX <= ddX + ddW && mY >= iy && mY < iy + ddItemH);
                    if (rHov || rIdx == resolutionIndex) {
                        fill(ddX + 1, iy, ddX + ddW - 1, iy + ddItemH, rHov ? 0xFF284428 : 0xFF162816);
                    }
                    drawSmallText(RESOLUTIONS[rIdx][0] + "x" + RESOLUTIONS[rIdx][1], ddX + 8, iy + 2, rIdx == resolutionIndex ? 0xFF55FF55 : 0xFFFFFFFF);
                }
            }
        }

        if (showFpsOverlay && !isWelcomeMenuOpen) {
            fpsOverlayW = 325; fpsOverlayH = 112;
            fpsOverlayX = Math.max(4, Math.min(sWidth - fpsOverlayW - 4, fpsOverlayX));
            fpsOverlayY = Math.max(4, Math.min(rectY - fpsOverlayH - 4, fpsOverlayY));

            fill(fpsOverlayX, fpsOverlayY, fpsOverlayX + fpsOverlayW, fpsOverlayY + fpsOverlayH, 0xDD070709);
            fill(fpsOverlayX, fpsOverlayY, fpsOverlayX + fpsOverlayW, fpsOverlayY + 20, isDraggingFpsOverlay ? 0xFF223822 : 0xFF141820);
            drawOutline(fpsOverlayX, fpsOverlayY, fpsOverlayW, fpsOverlayH, isDraggingFpsOverlay ? 0xFF55FF55 : 0xFF445566);
            fill(fpsOverlayX, fpsOverlayY + 20, fpsOverlayX + fpsOverlayW, fpsOverlayY + 21, 0xFF334455);

            drawSmallText("Performance Monitor (Drag to move)", fpsOverlayX + 8, fpsOverlayY + 4, 0xFFFFFFFF);

            String fpsMain = String.format(Locale.US, "FPS: %d   |   Frame: %.2f ms", currentFps, smoothedFrameMs);
            String gpuPhys = String.format(Locale.US, "GPU Sector Physics Tick: %.2f ms", lastPhysicsTickMs);
            String resInfo = String.format(Locale.US, "Render: %dx%d  |  Shaders: %s", activeRenderW, activeRenderH, SHADER_MODES[shaderMode]);
            String maxDebStr = (particlePreset == 3) ? "∞" : String.valueOf(PhysicsEngine.getEffectiveDebrisLimit());
            String objInfo = String.format(Locale.US, "Planets: %d/64  |  Debris: %d/%s (%s)", planets.size(), fragments.size() + particles.size(), maxDebStr, getParticlePresetLabel(particlePreset));

            drawText(fpsMain, fpsOverlayX + 10, fpsOverlayY + 26, currentFps >= 60 ? 0xFF55FF55 : 0xFFFFFF55);
            drawSmallText(gpuPhys, fpsOverlayX + 10, fpsOverlayY + 48, lastPhysicsTickMs < 5.0 ? 0xFF55FF55 : 0xFFFFFF55);
            drawSmallText(resInfo, fpsOverlayX + 10, fpsOverlayY + 68, 0xFF88CCFF);
            drawSmallText(objInfo, fpsOverlayX + 10, fpsOverlayY + 88, particlePreset == 3 ? 0xFFFF6699 : 0xFFCCCCCC);
        }

        if (isWelcomeMenuOpen) {
            int panelW = 500, panelH = 345;
            int panelX = (sWidth - panelW) / 2, panelY = (sHeight - panelH) / 2;

            fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF050505);
            drawOutline(panelX, panelY, panelW, panelH, 0xFF333333);

            int logoW = 160, logoH = 58, logoY = panelY + 18;
            drawIcon(LOGO_TEX, panelX + (panelW - logoW) / 2, logoY, logoW, logoH);

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
                } else textY += 8;
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

            String closeTxt = tr(31);
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
            editMenuAnimX += ((isEditMenuMinimized ? -panelW : 0) - editMenuAnimX) * Math.min(1.0, dtUI * 15.0);
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
            flashBtnX = minBtnX + minBtnW + 4; flashBtnY = 10;
            drawIcon(ICON_FLASHLIGHT, flashBtnX, flashBtnY, flashBtnS, flashBtnS, p.flashlightOn ? 0xFFFFFFFF : 0xFF555555);

            raysBtnX = flashBtnX + flashBtnS + 4; raysBtnY = 10;
            drawIcon(ICON_RAYS, raysBtnX, raysBtnY, raysBtnS, raysBtnS, p.raysOn ? 0xFFFFFFFF : 0xFF555555);

            drawText(tr(24) + p.name, currentX + 10, 14, 0xFFFFFFFF);

            if (!isEditMenuMinimized) {
                int headerH = 38;
                String[] labels = {tr(25), tr(26), tr(27), tr(28), tr(29), tr(30)};
                String[][] units = {{"Earths", "Suns", "kg"}, {"km", "Earths", "Suns"}, {"hours", "days", "years"}, {"deg"}, {"km/s", "m/s", "km/h", "c"}, {"C", "K", "F"}};
                int yOff = headerH + 8 - (int) editMenuScrollY;

                for (int i = 0; i < 6; i++) {
                    int blockH = (i < 3) ? 50 : 28;
                    if (yOff + blockH > headerH && yOff < panelH) {
                        fill(currentX + 6, yOff, currentX + panelW - 6, yOff + blockH, 0xFF111111);
                        drawOutline(currentX + 6, yOff, panelW - 12, blockH, 0xFF2A2A2A);
                        drawSmallText(labels[i] + ":", currentX + 12, yOff + 7, 0xFFAAAAAA);

                        int unitW = 48, unitX = currentX + panelW - unitW - 10;
                        int boxX = currentX + 78, boxW = unitX - boxX - 6, boxH = 16, boxY = yOff + 6;

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
                            else if (i == 4) { double len = p.vel.length(); if (u == 0) v = len * 1260.0; else if (u == 1) v = len * 1260000.0; else if (u == 2) v = len * 1260.0 * 3600.0; else if (u == 3) v = len * 1260.0 / LIGHT_SPEED_KMS; }
                            else if (i == 5) { if (u == 0) v = p.temperature; else if (u == 1) v = p.temperature + 273.15; else if (u == 2) v = p.temperature * 1.8 + 32.0; }
                            valStr = formatCompact(v);
                        }

                        if (isActive) { while (getSmallTextWidth(valStr) > boxW - 6 && valStr.length() > 1) valStr = valStr.substring(1); }
                        drawSmallText(valStr, boxX + 4, boxY + 2, 0xFFFFFFFF);

                        fill(unitX, boxY, unitX + unitW, boxY + boxH, 0xFF222222);
                        drawOutline(unitX, boxY, unitW, boxH, openEditUnitMenu == i ? 0xFF55FF55 : 0xFF555555);
                        String uStr = units[i][fieldUnits[i]];
                        drawSmallText(uStr, unitX + (unitW - getSmallTextWidth(uStr)) / 2, boxY + 2, 0xFFAAAAAA);

                        if (i < 3) {
                            int mx = currentX + 12, my = yOff + 28;
                            String[] multLabels = {"x0.1", "x0.5", "x2", "x5", "x10"};
                            for (int j = 0; j < 5; j++) {
                                int mw = getSmallTextWidth(multLabels[j]) + 10, mh = 16;
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

                if (openEditUnitMenu >= 0 && openEditUnitMenu < 6 && units[openEditUnitMenu].length > 1) {
                    String[] uOpts = units[openEditUnitMenu];
                    editUnitMenuW = unitBoxHits[openEditUnitMenu][2];
                    editUnitMenuH = uOpts.length * 16;
                    editUnitMenuX = unitBoxHits[openEditUnitMenu][0];
                    editUnitMenuY = unitBoxHits[openEditUnitMenu][1] + unitBoxHits[openEditUnitMenu][3] + 1;

                    fill(editUnitMenuX, editUnitMenuY, editUnitMenuX + editUnitMenuW, editUnitMenuY + editUnitMenuH, 0xFF08080A);
                    drawOutline(editUnitMenuX, editUnitMenuY, editUnitMenuW, editUnitMenuH, 0xFF55FF55);
                    for (int uIdx = 0; uIdx < uOpts.length; uIdx++) {
                        int uy = editUnitMenuY + uIdx * 16;
                        if (mX >= editUnitMenuX && mX <= editUnitMenuX + editUnitMenuW && mY >= uy && mY < uy + 16) {
                            fill(editUnitMenuX + 1, uy, editUnitMenuX + editUnitMenuW - 1, uy + 16, 0xFF284428);
                        }
                        drawSmallText(uOpts[uIdx], editUnitMenuX + (editUnitMenuW - getSmallTextWidth(uOpts[uIdx])) / 2, uy + 2, fieldUnits[openEditUnitMenu] == uIdx ? 0xFF55FF55 : 0xFFFFFFFF);
                    }
                }
            }
        } else openEditUnitMenu = -1;

        if (animToolsY > 0.01 && !isWelcomeMenuOpen) {
            toolMenuW = 190; toolMenuH = 191;
            toolMenuX = sWidth - toolMenuW;
            toolMenuY = sHeight - (int) ((barHeight + toolMenuH) * animToolsY);

            fill(toolMenuX, toolMenuY, sWidth, toolMenuY + toolMenuH, 0xFF050505);
            fill(toolMenuX, toolMenuY, sWidth, toolMenuY + 1, 0xFF1A1A1A);
            fill(toolMenuX, toolMenuY, toolMenuX + 1, toolMenuY + toolMenuH, 0xFF1A1A1A);

            drawText(tr(15), toolMenuX + 10, toolMenuY + 5, 0xFFAAAAAA);
            int btnW = toolMenuW - 16, btnX = toolMenuX + 8, toggleH = 20;

            moveToolY     = toolMenuY + 24;
            gridToolY     = toolMenuY + 47;
            gravGridToolY = toolMenuY + 70;
            trailToolY    = toolMenuY + 93;
            hzToolY       = toolMenuY + 116;
            physicsToolY  = toolMenuY + 139;
            clearToolY    = toolMenuY + 162;

            if (leftClickedNow && isToolsMenuOpen && mX >= btnX && mX <= btnX + btnW) {
                if (mY >= gravGridToolY && mY <= gravGridToolY + toggleH) {
                    showGravityGrid = !showGravityGrid;
                    if (showGravityGrid) { showGrid = false; prevShowGrid = false; }
                } else if (mY >= gridToolY && mY <= gridToolY + toggleH) {
                    if (showGravityGrid) showGravityGrid = false;
                }
            }

            drawMenuToggle(tr(16), btnX, moveToolY,     btnW, toggleH, isMoveToolActive);
            drawMenuToggle(tr(17), btnX, gridToolY,     btnW, toggleH, showGrid);
            drawMenuToggle(tr(33), btnX, gravGridToolY, btnW, toggleH, showGravityGrid);
            drawMenuToggle(tr(18), btnX, trailToolY,    btnW, toggleH, showTrails);
            drawMenuToggle(tr(19), btnX, hzToolY,       btnW, toggleH, showHabitableZone);
            drawMenuToggle(tr(20), btnX, physicsToolY,  btnW, toggleH, physicsEnabled);
            drawDeleteToolToggle(tr(21), btnX, clearToolY, btnW, toggleH, isDeleteToolActive);
        }

        if (animAddY > 0.01 && !isWelcomeMenuOpen) {
            int menuHeight = 135;
            int menuY = sHeight - (int) ((barHeight + menuHeight) * animAddY);

            fill(0, menuY, sWidth, menuY + menuHeight, 0xFF050505);
            fill(0, menuY, sWidth, menuY + 1, 0xFF1A1A1A);

            int catX = 10, catY = menuY + 6, catSize = 18; catHitY = catY; catHitH = catSize;
            fill(catX, catY + 2, catX + 1, catY + catSize - 2, 0xFF3A3A3A); catX += 3;

            for (int c = 0; c < 5; c++) {
                int w;
                if (c == 0) {
                    String text = "ALL"; w = getTextWidth(text) + 12; catHitX[c] = catX; catHitW[c] = w;
                    drawText(text, catX + 6, catY + (catSize - FONT_HEIGHT) / 2, currentCategory == c ? 0xFF666666 : 0xFFFFFFFF); catX += w;
                } else {
                    w = catSize + 12; catHitX[c] = catX; catHitW[c] = w;
                    String tex = (c == 1) ? ICON_PLANETS : (c == 2 ? ICON_MOONS : (c == 3 ? ICON_STARS : ICON_HOLES));
                    drawIcon(tex, catX + (w - catSize) / 2, catY, catSize, catSize, currentCategory == c ? 0xFF555555 : 0xFFFFFFFF);
                    catX += w;
                }
                if (c < 4) { catX += 2; fill(catX, catY + 2, catX + 1, catY + catSize - 2, 0xFF3A3A3A); catX += 3; }
            }

            for (int i = 0; i < planetHitboxes.length; i++) planetHitboxes[i][0] = -1;

            int visibleCount = 0;
            for (int i = 0; i < menuItems.length && visibleCount < VISIBLE_ITEMS_BUF.length; i++) {
                boolean match = (currentCategory == 0)
                        || (currentCategory == 1 && "Planet".equals(menuItems[i].type))
                        || (currentCategory == 2 && "Satellite".equals(menuItems[i].type))
                        || (currentCategory == 3 && "Star".equals(menuItems[i].type))
                        || (currentCategory == 4 && "Hole".equals(menuItems[i].type));
                if (match) VISIBLE_ITEMS_BUF[visibleCount++] = i;
            }

            int sepX = sWidth - 185, iconSize = 22, startX = 14, startY = menuY + 34, rowHeight = 44;
            int simX = startX, totalRows = 1;
            for (int vi = 0; vi < visibleCount; vi++) {
                int itemW = Math.max(iconSize + 18, getSmallTextWidth(menuItems[VISIBLE_ITEMS_BUF[vi]].name) + 14);
                if (simX + itemW > sepX - 10 && simX > startX) { simX = startX; totalRows++; }
                simX += itemW + 8;
            }

            int visibleAreaH = menuHeight - 38;
            int maxScroll = Math.max(0, totalRows * rowHeight - visibleAreaH);
            if (menuScrollY > maxScroll) menuScrollY = maxScroll;
            if (menuScrollY < 0) menuScrollY = 0;

            int curX = startX, curRow = 0;
            for (int vi = 0; vi < visibleCount; vi++) {
                int i = VISIBLE_ITEMS_BUF[vi];
                String pName = menuItems[i].name;
                int tw = getSmallTextWidth(pName);
                int itemW = Math.max(iconSize + 18, tw + 14);

                if (curX + itemW > sepX - 10 && curX > startX) { curX = startX; curRow++; }

                int eY = startY + curRow * rowHeight - (int) menuScrollY;
                int iconX = curX + (itemW - iconSize) / 2, tX = curX + (itemW - tw) / 2, tY = eY + iconSize + 4;

                if (eY >= menuY + 26 && eY + iconSize + 14 <= menuY + menuHeight) {
                    String guiTexStr = menuItems[i].guiTex;
                    drawIcon((guiTexStr == null || guiTexStr.isEmpty()) ? ICON_PLANETS : guiTexStr, iconX, eY, iconSize, iconSize);
                    if (i == selectedPlanetIndex) {
                        fill(tX - 3, tY - 2, tX + tw + 3, tY + SMALL_FONT_HEIGHT + 1, 0xAA000000);
                        drawSmallText(pName, tX, tY, 0xFF55FF55);
                    } else drawSmallText(pName, tX, tY, 0xFFAAAAAA);

                    planetHitboxes[i][0] = curX; planetHitboxes[i][1] = eY;
                    planetHitboxes[i][2] = itemW; planetHitboxes[i][3] = iconSize + 18;
                }
                curX += itemW + 8;
            }

            if (maxScroll > 0) {
                int trackX = sepX - 5, trackY = menuY + 32, trackH = menuHeight - 40;
                fill(trackX, trackY, trackX + 3, trackY + trackH, 0xFF151515);
                int thumbH = Math.max(16, (int) ((float) visibleAreaH / (totalRows * rowHeight) * trackH));
                int thumbY = trackY + (int) ((menuScrollY / (float) maxScroll) * (trackH - thumbH));
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
            timeText = (year > 9999 || year < -9999) ? ("Year " + formatCompact(year)) : currentDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm"));
        } catch (Exception e) {
            timeText = "Year " + formatCompact(2026 + simTimeSeconds / 31536000.0);
        }

        String scaleText = formatTimeScale(timeScale);
        int timeX = rightArrowX + rightArrowW + 12, textY = centerY - FONT_HEIGHT / 2;
        drawText(timeText, timeX, textY, 0xFFFFFFFF);

        int sepX = timeX + getTextWidth(timeText) + 8;
        fill(sepX, centerY - 6, sepX + 1, centerY + 6, 0xFF555555);
        drawText(scaleText, sepX + 8, textY, 0xFFAAAAAA);

        String addTxt = tr(14), toolsTxt = tr(15);
        int btnPadX = 6, gap = 8;
        int totalRightWidth = (getTextWidth(addTxt) + btnPadX * 2 + gap) + (getTextWidth(toolsTxt) + btnPadX * 2 + gap);
        int currX = sWidth - totalRightWidth - 10, rightBtnH = FONT_HEIGHT + 4, rightBtnY = centerY - rightBtnH / 2;

        int wAdd = drawButton(addTxt, currX, rightBtnY, btnPadX, 2, isAddMenuOpen);
        addBtnX = currX; addBtnY = rightBtnY; addBtnW = wAdd; addBtnH = rightBtnH;
        currX += wAdd + gap;

        int wTools = drawButton(toolsTxt, currX, rightBtnY, btnPadX, 2, isToolsMenuOpen);
        toolsBtnX = currX; toolsBtnY = rightBtnY; toolsBtnW = wTools; toolsBtnH = rightBtnH;

        renderNotificationsAndSpeedometer(sWidth, dtUI);

        if (hoverDesc != null && !CameraManager.isRotating()) {
            drawHoverTooltip(hoverTitle, hoverDesc, (int) mX, (int) mY, sWidth, sHeight);
        }

        if (!CameraManager.isRotating()) {
            if (isDeleteToolActive && !isWelcomeMenuOpen) {
                drawIcon(CURSOR_TRASH_TEX, (int) mX - 11, (int) mY - 11, 22, 22);
            } else if (isPlacingPlanet && currentSpawnMode == SpawnMode.LAUNCH) {
                drawIcon(CURSOR_AIM_TEX, (int) mX - 12, (int) mY - 12, 24, 24);
            } else {
                drawIcon(CURSOR_TEX, (int) mX, (int) mY, 24, 24);
            }
        }
    }
}