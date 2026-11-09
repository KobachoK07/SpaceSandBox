package spacesb.remake;

import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import spacesb.remake.planets.BasePlanet;
import spacesb.remake.planets.PlanetRegistry;
import spacesb.remake.render.SBoxCompute;
import spacesb.remake.render.SBoxVulkanCanvas;
import spacesb.remake.render.SBoxVulkanCore;
import spacesb.remake.render.SBoxVulkanSwapchain;
import spacesb.remake.render.SBoxVulkanUI;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.locks.LockSupport;

import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public class SpaceEngine {

    public static final Object STATE_LOCK = new Object();
    private static final File SETTINGS_FILE = new File("spacesb_settings.properties");
    private static long lastSettingsHash = 0L;
    private static long lastSettingsSaveMs = 0L;
    private static boolean settingsDirty = false;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(SpaceEngine::saveSettings, "SBox-Settings-ShutdownSave"));
    }

    public static final Comparator<Planet> WARP_GROUP_COMPARATOR =
            (a, b) -> Integer.compare(a.getWarpGroupKey(), b.getWarpGroupKey());
    public static volatile boolean planetsNeedSort = false;
    public static volatile boolean needsSwapchainRecreate = false;

    public static long windowHandle = 0;

    public static final double BLOCK_TO_KM = 1260.0;
    public static final double LIGHT_SPEED_KMS = 299792.458;
    public static final double MAX_SPEED_C = 100.0;
    public static final double MAX_SPEED_KMS = LIGHT_SPEED_KMS * MAX_SPEED_C;

    public static final String TEX_FRAGMENT = "assets/spacesb-remake/textures/particle/fragments.png";

    public static boolean enableCentrifugalForce = true;
    public static boolean enableRocheLimit = true;
    public static boolean enableGravity = true;
    public static boolean enableCollisions = true;
    public static boolean showFpsOverlay = false;
    public static double lastPhysicsTickMs = 0;
    public static int lastPhysicsSubSteps = 8;
    public static int currentFps = 60;
    public static double smoothedFrameMs = 16.6;
    public static int activeRenderW = 1920;
    public static int activeRenderH = 1080;

    public static int fpsOverlayX = 260, fpsOverlayY = 12, fpsOverlayW = 480, fpsOverlayH = 178;
    public static boolean isDraggingFpsOverlay = false;
    public static double fpsDragOffsetX = 0, fpsDragOffsetY = 0;

    public static MenuItem customPlanet = null;

    public static double lockedOrbitDistance = -1.0, targetOrbitDistance = -1.0;
    public static Planet prevLockedTarget = null;

    public static boolean isVulkanInitialized = false;
    public static boolean isDeleteToolActive = false;
    public static boolean isEscMenuOpen = false, escPressed = false;

    public static boolean photoModeFeatureEnabled = true;
    public static boolean isPhotoModeActive = false, wasPausedBeforePhoto = false, cKeyPressed = false;
    public static volatile boolean requestScreenshot = false;

    public static int shaderMode = 0;
    public static final String[] SHADER_MODES = {"Ultra", "Simple", "OFF"};
    public static float shaderSliderPos = 0.833f;
    public static boolean isDraggingShaderCube = false;

    public static int particlePreset = 3;
    public static final String[] PARTICLE_PRESETS = {"Min", "Med", "Hard", "Ultra"};
    public static float particleSliderPos = 0.875f;
    public static boolean isDraggingParticleCube = false;

    public static float musicVolume = 0.20f;
    public static boolean isDraggingVolumeCube = false;
    public static final int[] escVolumeTrackHit = new int[4];

    public static boolean fsrEnabled = false;
    public static int fsrQualityIndex = 0;
    public static final String[] FSR_MODES = {"Ultra Quality", "Quality", "Balanced", "Performance"};
    public static final float[] FSR_SCALES = {0.77f, 0.67f, 0.59f, 0.50f};
    public static float fsrSliderPos = 0.125f;
    public static boolean isDraggingFsrCube = false;

    public static boolean hdrEnabled = false;
    public static boolean vsyncEnabled = true;
    private static long lastFrameSyncNs = System.nanoTime();

    public static int resolutionIndex = 2;
    public static final int[][] RESOLUTIONS = {{1280, 720}, {1600, 900}, {1920, 1080}, {2560, 1440}, {3840, 2160}};
    public static boolean isFullscreen = true, isResolutionMenuOpen = false;
    public static final int[] escResDropdownHit = new int[4];

    public static boolean isVisualTabOpen = false;
    public static boolean isPhysicsTabOpen = false;
    public static boolean isMiscTabOpen = false;
    public static double escMenuScrollY = 0.0;
    public static int escMenuMaxScroll = 0;

    // Меню «Симуляции» в ESC (Готовые и Сохранённые симуляции)
    public static boolean isSimulationsMenuOpen = false;
    public static double simMenuAnimProgress = 0.0;
    public static int simMenuTab = 0; // 0 = Готовые симуляции, 1 = Сохранённые симуляции
    public static int selectedPresetSimIndex = 0;
    public static int selectedSavedSimIndex = 0;
    public static double simListScrollY = 0.0;
    public static int simListMaxScroll = 0;
    public static boolean isRenamingSim = false;
    public static int renamingSimIndex = -1;
    public static String simRenameBuffer = "";
    public static long lastSimItemClickMs = 0L;
    public static int lastClickedSimItemIndex = -1;

    public static final int[][] escSimSubTabHits = new int[2][4];
    public static final int[] escSimNewSaveBtnHit = new int[4];
    public static final int[][] escSimItemHits = new int[64][4];
    public static final int[] escSimPresetLoadBtnHit = new int[4];
    public static final int[][] escSimSavedBottomBtnHits = new int[4][4];

    public static int physicsPrecisionMode = 2;
    public static boolean isMathBitsMenuOpen = false;
    public static final String[] MATH_BITS_OPTIONS = {"32 bit", "32+64 bit", "64 bit"};
    public static final int[] escMathBitsBtnHit = new int[4];
    public static final int[] escMathBitsDropdownHit = new int[4];

    public static int physicsSimMode = 1;
    public static float physicsModeSliderPos = 0.75f;
    public static boolean isDraggingPhysicsModeCube = false;

    public static boolean enableSaturnRings = true;
    public static boolean enableAccretionDisk = false;
    public static int uiColor1 = 0xFF050505;
    public static int uiColor2 = 0xFFFFFFFF;
    public static int openColorPicker = 0;

    public static final int[] PALETTE_COLOR_1 = {
            0xFF050505, 0xFF0B101C, 0xFF140B1D, 0xFF1D0A0E,
            0xFF071A12, 0xFF1A1408, 0xFF08181E, 0xFF181818,
            0xFF1F0F24, 0xFF0E1F14, 0xFF24120C, 0xFF101228,
            0xFF24262C, 0xFF2B151D, 0xFF0D2426, 0xFF000000
    };

    public static final int[] PALETTE_COLOR_2 = {
            0xFFFFFFFF, 0xFF55FF55, 0xFF44AAFF, 0xFFFF5555,
            0xFFFFCC33, 0xFFCC55FF, 0xFF00FFCC, 0xFFFF7733,
            0xFFFF44AA, 0xFF7788FF, 0xFFAADD44, 0xFF44FFFF,
            0xFFFFAA88, 0xFFBBBBBB, 0xFF88FFAA, 0xFFFF2266
    };

    public static final int[] escVisualTabHit = new int[4];
    public static final int[] escPhysicsTabHit = new int[4];
    public static final int[] escMiscTabHit = new int[4];
    public static final int[] escPhysicsModeTrackHit = new int[4];
    public static final int[] escSaturnRingsHit = new int[4];
    public static final int[] escAccretionDiskHit = new int[4];
    public static final int[] escColor1Hit = new int[4];
    public static final int[] escColor2Hit = new int[4];
    public static final int[] escColorPopupHit = new int[4];

    public static final int[][] escTopBtnHits = new int[2][4];
    public static final int[][] escSettingHits = new int[9][4];
    public static final int[] escFsrTrackHit = new int[4];
    public static final int[] escShaderTrackHit = new int[4];
    public static final int[] escParticleTrackHit = new int[4];
    public static final int[] escExitBtnHit = new int[4];

    public static int openEditUnitMenu = -1;
    public static int editUnitMenuX, editUnitMenuY, editUnitMenuW, editUnitMenuH;

    public static boolean isObjectPreviewOpen = true;
    public static boolean isDraggingObjectPreview = false;
    public static float objectPreviewYaw = -35.0f, objectPreviewPitch = 20.0f, objectPreviewZoom = 4.8f;
    public static double previewLastMouseX = 0, previewLastMouseY = 0;
    public static int viewBtnX, viewBtnY, viewBtnS = 20;
    public static final int[] previewBoxHit = new int[4];
    public static final float[] previewClipRectRender = new float[4];
    public static final float[] previewCenterScaleRender = new float[4];
    public static int lockedTargetShaderIdx = -1;

    private static final Vector3d SCRATCH_HOLO = new Vector3d();
    private static final Vector3d SCRATCH_DIR = new Vector3d();
    private static final Vector3d SCRATCH_R = new Vector3d();
    private static final Vector3d SCRATCH_AXIS = new Vector3d();
    private static final Vector3d SCRATCH_RIGHT = new Vector3d();
    private static final Vector3d SCRATCH_SPAWN_POS = new Vector3d();
    private static final Vector3d SCRATCH_SPAWN_UP = new Vector3d();
    private static final Vector3d SCRATCH_VEL_OUT = new Vector3d();

    public static int getMaxDebrisLimit() { return PhysicsEngine.getEffectiveDebrisLimit(); }
    public static double getDebrisSpawnMultiplier() { return PhysicsEngine.getEffectiveSpawnMultiplier(); }
    public static float getInternalRenderScale() { return fsrEnabled ? FSR_SCALES[fsrQualityIndex] : 1.0f; }

    private static long computeSettingsHash() {
        long h = 1125899906842597L;
        h = 31L * h + (fsrEnabled ? 1 : 0);
        h = 31L * h + fsrQualityIndex;
        h = 31L * h + (hdrEnabled ? 1 : 0);
        h = 31L * h + (vsyncEnabled ? 1 : 0);
        h = 31L * h + resolutionIndex;
        h = 31L * h + (isFullscreen ? 1 : 0);
        h = 31L * h + (showFpsOverlay ? 1 : 0);
        h = 31L * h + fpsOverlayX;
        h = 31L * h + fpsOverlayY;
        h = 31L * h + (photoModeFeatureEnabled ? 1 : 0);
        h = 31L * h + shaderMode;
        h = 31L * h + particlePreset;
        h = 31L * h + Math.round(musicVolume * 1000.0f);
        h = 31L * h + SpaceUI.currentLanguage;
        h = 31L * h + (enableSaturnRings ? 1 : 0);
        h = 31L * h + (enableAccretionDisk ? 1 : 0);
        h = 31L * h + uiColor1;
        h = 31L * h + uiColor2;
        h = 31L * h + physicsPrecisionMode;
        h = 31L * h + physicsSimMode;
        h = 31L * h + (showGrid ? 1 : 0);
        h = 31L * h + (SpaceUI.showGravityGrid ? 1 : 0);
        h = 31L * h + (showTrails ? 1 : 0);
        h = 31L * h + (SpaceUI.showHabitableZone ? 1 : 0);
        h = 31L * h + (physicsEnabled ? 1 : 0);
        h = 31L * h + (isObjectPreviewOpen ? 1 : 0);
        return h;
    }

    public static synchronized void saveSettings() {
        try {
            Properties props = new Properties();
            props.setProperty("fsrEnabled", String.valueOf(fsrEnabled));
            props.setProperty("fsrQualityIndex", String.valueOf(fsrQualityIndex));
            props.setProperty("hdrEnabled", String.valueOf(hdrEnabled));
            props.setProperty("vsyncEnabled", String.valueOf(vsyncEnabled));
            props.setProperty("resolutionIndex", String.valueOf(resolutionIndex));
            props.setProperty("isFullscreen", String.valueOf(isFullscreen));
            props.setProperty("showFpsOverlay", String.valueOf(showFpsOverlay));
            props.setProperty("fpsOverlayX", String.valueOf(fpsOverlayX));
            props.setProperty("fpsOverlayY", String.valueOf(fpsOverlayY));
            props.setProperty("photoModeFeatureEnabled", String.valueOf(photoModeFeatureEnabled));
            props.setProperty("shaderMode", String.valueOf(shaderMode));
            props.setProperty("particlePreset", String.valueOf(particlePreset));
            props.setProperty("musicVolume", String.valueOf(musicVolume));
            props.setProperty("language", String.valueOf(SpaceUI.currentLanguage));
            props.setProperty("enableSaturnRings", String.valueOf(enableSaturnRings));
            props.setProperty("enableAccretionDisk", String.valueOf(enableAccretionDisk));
            props.setProperty("uiColor1", String.valueOf(uiColor1));
            props.setProperty("uiColor2", String.valueOf(uiColor2));
            props.setProperty("physicsPrecisionMode", String.valueOf(physicsPrecisionMode));
            props.setProperty("physicsSimMode", String.valueOf(physicsSimMode));
            props.setProperty("showGrid", String.valueOf(showGrid));
            props.setProperty("showGravityGrid", String.valueOf(SpaceUI.showGravityGrid));
            props.setProperty("showTrails", String.valueOf(showTrails));
            props.setProperty("showHabitableZone", String.valueOf(SpaceUI.showHabitableZone));
            props.setProperty("physicsEnabled", String.valueOf(physicsEnabled));
            props.setProperty("isObjectPreviewOpen", String.valueOf(isObjectPreviewOpen));
            try (FileOutputStream fos = new FileOutputStream(SETTINGS_FILE)) {
                props.store(fos, "Space SandBox Settings");
            }
            settingsDirty = false;
            lastSettingsSaveMs = System.currentTimeMillis();
        } catch (Exception ignored) {}
    }

    public static synchronized void loadSettings() {
        if (!SETTINGS_FILE.exists()) {
            lastSettingsHash = computeSettingsHash();
            return;
        }
        try (FileInputStream fis = new FileInputStream(SETTINGS_FILE)) {
            Properties props = new Properties();
            props.load(fis);

            fsrEnabled = Boolean.parseBoolean(props.getProperty("fsrEnabled", String.valueOf(fsrEnabled)));
            fsrQualityIndex = Math.max(0, Math.min(3, Integer.parseInt(props.getProperty("fsrQualityIndex", String.valueOf(fsrQualityIndex)))));
            fsrSliderPos = (fsrQualityIndex + 0.5f) / 4.0f;

            hdrEnabled = Boolean.parseBoolean(props.getProperty("hdrEnabled", String.valueOf(hdrEnabled)));
            vsyncEnabled = Boolean.parseBoolean(props.getProperty("vsyncEnabled", String.valueOf(vsyncEnabled)));
            resolutionIndex = Math.max(0, Math.min(RESOLUTIONS.length - 1, Integer.parseInt(props.getProperty("resolutionIndex", String.valueOf(resolutionIndex)))));
            isFullscreen = Boolean.parseBoolean(props.getProperty("isFullscreen", String.valueOf(isFullscreen)));

            showFpsOverlay = Boolean.parseBoolean(props.getProperty("showFpsOverlay", String.valueOf(showFpsOverlay)));
            fpsOverlayX = Integer.parseInt(props.getProperty("fpsOverlayX", String.valueOf(fpsOverlayX)));
            fpsOverlayY = Integer.parseInt(props.getProperty("fpsOverlayY", String.valueOf(fpsOverlayY)));
            photoModeFeatureEnabled = Boolean.parseBoolean(props.getProperty("photoModeFeatureEnabled", String.valueOf(photoModeFeatureEnabled)));

            shaderMode = Math.max(0, Math.min(2, Integer.parseInt(props.getProperty("shaderMode", String.valueOf(shaderMode)))));
            shaderSliderPos = (shaderMode == 2) ? 0.166f : (shaderMode == 1 ? 0.5f : 0.833f);

            particlePreset = Math.max(0, Math.min(3, Integer.parseInt(props.getProperty("particlePreset", String.valueOf(particlePreset)))));
            particleSliderPos = (particlePreset + 0.5f) / 4.0f;

            musicVolume = Math.max(0.0f, Math.min(1.0f, Float.parseFloat(props.getProperty("musicVolume", String.valueOf(musicVolume)))));
            SpaceUI.currentLanguage = Math.max(0, Math.min(SpaceUI.LANGUAGE_NAMES.length - 1, Integer.parseInt(props.getProperty("language", String.valueOf(SpaceUI.currentLanguage)))));

            enableSaturnRings = Boolean.parseBoolean(props.getProperty("enableSaturnRings", String.valueOf(enableSaturnRings)));
            enableAccretionDisk = Boolean.parseBoolean(props.getProperty("enableAccretionDisk", String.valueOf(enableAccretionDisk)));
            uiColor1 = Integer.parseInt(props.getProperty("uiColor1", String.valueOf(uiColor1)));
            uiColor2 = Integer.parseInt(props.getProperty("uiColor2", String.valueOf(uiColor2)));

            int savedPrec = Integer.parseInt(props.getProperty("physicsPrecisionMode", String.valueOf(physicsPrecisionMode)));
            physicsPrecisionMode = (!SBoxVulkanCore.supportsFp64 && savedPrec == 2) ? 1 : Math.max(0, Math.min(2, savedPrec));

            physicsSimMode = Math.max(0, Math.min(1, Integer.parseInt(props.getProperty("physicsSimMode", String.valueOf(physicsSimMode)))));
            physicsModeSliderPos = (physicsSimMode == 0) ? 0.25f : 0.75f;

            showGrid = Boolean.parseBoolean(props.getProperty("showGrid", String.valueOf(showGrid)));
            SpaceUI.showGravityGrid = Boolean.parseBoolean(props.getProperty("showGravityGrid", String.valueOf(SpaceUI.showGravityGrid)));
            if (showGrid && SpaceUI.showGravityGrid) showGrid = false;

            showTrails = Boolean.parseBoolean(props.getProperty("showTrails", String.valueOf(showTrails)));
            SpaceUI.showHabitableZone = Boolean.parseBoolean(props.getProperty("showHabitableZone", String.valueOf(SpaceUI.showHabitableZone)));
            physicsEnabled = Boolean.parseBoolean(props.getProperty("physicsEnabled", String.valueOf(physicsEnabled)));
            isObjectPreviewOpen = Boolean.parseBoolean(props.getProperty("isObjectPreviewOpen", String.valueOf(isObjectPreviewOpen)));
        } catch (Exception ignored) {}
        lastSettingsHash = computeSettingsHash();
    }

    private static void checkAndAutoSaveSettings() {
        long curHash = computeSettingsHash();
        if (curHash != lastSettingsHash) {
            lastSettingsHash = curHash;
            settingsDirty = true;
        }
        if (settingsDirty && !isDraggingFsrCube && !isDraggingShaderCube && !isDraggingParticleCube
                && !isDraggingPhysicsModeCube && !isDraggingVolumeCube && !isDraggingFpsOverlay) {
            long now = System.currentTimeMillis();
            if (now - lastSettingsSaveMs >= 300L) {
                saveSettings();
            }
        }
    }

    public static float[] buildFlatUv(float[][] uv) {
        float[] flat = new float[24];
        float[][] src = (uv != null) ? uv : BasePlanet.UV_DEFAULT;
        for (int f = 0; f < 6; f++) {
            float[] face = (f < src.length && src[f] != null && src[f].length >= 4) ? src[f] : BasePlanet.UV_DEFAULT[f];
            int base = f * 4;
            flat[base]     = face[0] / 16.0f;
            flat[base + 1] = face[1] / 16.0f;
            flat[base + 2] = face[2] / 16.0f;
            flat[base + 3] = face[3] / 16.0f;
        }
        return flat;
    }

    public static class Notification {
        public String text;
        public double life = 2.5, targetY = 10, currentY = -30, alpha = 0.0;
        public Notification(String text) { this.text = text; }
    }
    public static final List<Notification> notifications = new ArrayList<>();

    public static class Planet {
        public String name;
        public final Vector3d pos = new Vector3d(), vel = new Vector3d();
        public double radius, targetRadius, mass, tilt, rotationPeriod, temperature, bondAlbedo;
        public String texture, type;
        public float[][] uvMap;
        public final float[] flatUv;
        public boolean isRigid, raysOn = true, flashlightOn = false;
        public int rColor, gColor, bColor;
        public double currentAngle = 0.0;

        public static final int MAX_TRAIL_POINTS = 300;
        public final Vector3d[] trailRing = new Vector3d[MAX_TRAIL_POINTS];
        public int trailHead = 0, trailCount = 0;

        public void addTrailPoint(Vector3d pt) {
            if (trailCount < MAX_TRAIL_POINTS) {
                int idx = (trailHead + trailCount) % MAX_TRAIL_POINTS;
                if (trailRing[idx] == null) trailRing[idx] = new Vector3d(pt);
                else trailRing[idx].set(pt);
                trailCount++;
            } else {
                trailRing[trailHead].set(pt);
                trailHead = (trailHead + 1) % MAX_TRAIL_POINTS;
            }
        }

        public Vector3d getTrailPoint(int logicalIdx) { return trailRing[(trailHead + logicalIdx) % MAX_TRAIL_POINTS]; }
        public void clearTrail() { trailHead = 0; trailCount = 0; }

        public Planet(String name, double x, double y, double z, Vector3d vel, double radius, double mass, double tilt, double rot, double temp, String tex, float[][] uv, String type, boolean isRigid) {
            this.name = name;
            this.pos.set(x, y, z);
            this.vel.set(vel);
            this.radius = Math.max(1e-4, radius);
            this.targetRadius = this.radius;
            this.mass = Math.max(0.0, mass);
            this.tilt = tilt;
            this.rotationPeriod = rot;
            this.temperature = temp;
            this.texture = tex;
            this.uvMap = uv;
            this.flatUv = buildFlatUv(uv);
            this.type = type;
            this.isRigid = !"Hole".equals(type) && isRigid;

            if ("Jupiter".equalsIgnoreCase(name)) {
                this.rColor = 225; this.gColor = 175; this.bColor = 125; this.bondAlbedo = 0.50;
            } else if ("Saturn".equalsIgnoreCase(name)) {
                this.rColor = 235; this.gColor = 205; this.bColor = 140; this.bondAlbedo = 0.48;
            } else if ("Uranus".equalsIgnoreCase(name)) {
                this.rColor = 135; this.gColor = 215; this.bColor = 235; this.bondAlbedo = 0.60;
            } else if ("Neptune".equalsIgnoreCase(name)) {
                this.rColor = 75; this.gColor = 130; this.bColor = 245; this.bondAlbedo = 0.60;
            } else if ("Enceladus".equalsIgnoreCase(name)) {
                this.rColor = 245; this.gColor = 250; this.bColor = 255; this.bondAlbedo = 0.88;
            } else if ("Venus".equalsIgnoreCase(name)) {
                this.rColor = 235; this.gColor = 210; this.bColor = 160; this.bondAlbedo = 0.72;
            } else if ("Earth".equalsIgnoreCase(name)) {
                this.rColor = 100; this.gColor = 165; this.bColor = 235; this.bondAlbedo = 0.32;
            } else if ("Mars".equalsIgnoreCase(name)) {
                this.rColor = 215; this.gColor = 105; this.bColor = 65; this.bondAlbedo = 0.25;
            } else if ("Titan".equalsIgnoreCase(name)) {
                this.rColor = 220; this.gColor = 160; this.bColor = 85; this.bondAlbedo = 0.26;
            } else if ("Moon".equalsIgnoreCase(name)) {
                this.rColor = 165; this.gColor = 165; this.bColor = 165; this.bondAlbedo = 0.12;
            } else if ("Mercury".equalsIgnoreCase(name)) {
                this.rColor = 125; this.gColor = 120; this.bColor = 115; this.bondAlbedo = 0.06;
            } else if ("Hole".equalsIgnoreCase(type)) {
                this.rColor = 0; this.gColor = 0; this.bColor = 0; this.bondAlbedo = 0.0;
            } else if ("Sun".equalsIgnoreCase(name)) {
                this.rColor = 255; this.gColor = 200; this.bColor = 110; this.bondAlbedo = 0.0;
            } else if ("Stephenson 2-18".equalsIgnoreCase(name)) {
                this.rColor = 255; this.gColor = 95; this.bColor = 45; this.bondAlbedo = 0.0;
            } else {
                this.rColor = (int)(Math.random() * 155 + 100);
                this.gColor = (int)(Math.random() * 155 + 100);
                this.bColor = (int)(Math.random() * 155 + 100);
                this.bondAlbedo = 0.30;
            }
        }

        public double getDensity() {
            if (radius <= 1e-6 || mass <= 0.0) return 0.0;
            double vol = (4.0 / 3.0) * Math.PI * (radius * radius * radius);
            return (vol <= 1e-18 || Double.isInfinite(vol) || Double.isNaN(vol)) ? 0.0 : (mass / vol);
        }

        public int getWarpGroupKey() {
            if ("Hole".equals(type)) return 0;
            if ("Star".equals(type)) return 1;
            return isRigid ? 2 : 3;
        }
    }

    public static class Fragment {
        public final Vector3d pos = new Vector3d(), vel = new Vector3d(), rotAxis = new Vector3d(0, 1, 0);
        public double size, life, maxLife, mass, temperature, angle, rotSpeed;
        public String tex;
        public float u1, v1, u2, v2;
        public boolean isSaturnRing = false;

        public Fragment() {}
        public Fragment(Vector3d pos, Vector3d vel, double size, double life, double mass, double temperature, String tex, float u1, float v1, float u2, float v2) {
            set(pos, vel, size, life, mass, temperature, tex, u1, v1, u2, v2);
        }

        public void set(Vector3d p, Vector3d v, double s, double l, double m, double temp, String tx, float u0, float v0, float u1_, float v1_) {
            this.pos.set(p); this.vel.set(v);
            this.size = s; this.life = l; this.maxLife = l;
            this.mass = m; this.temperature = temp; this.tex = tx;
            this.u1 = u0; this.v1 = v0; this.u2 = u1_; this.v2 = v1_;
            this.angle = Math.random() * 360.0;
            this.rotSpeed = Math.random() * 360.0 - 180.0;
            this.isSaturnRing = false;
        }
    }

    public static class Particle {
        public final Vector3d pos = new Vector3d(), vel = new Vector3d();
        public double size, life, maxLife, mass, temperature;
        public int r, g, b, type;

        public Particle() {}
        public Particle(Vector3d pos, Vector3d vel, double size, double life, int r, int g, int b, int type, double mass, double temperature) {
            set(pos, vel, size, life, r, g, b, type, mass, temperature);
        }

        public void set(Vector3d p, Vector3d v, double s, double l, int r_, int g_, int b_, int t, double m, double temp) {
            this.pos.set(p); this.vel.set(v);
            this.size = s; this.life = l; this.maxLife = l;
            this.r = r_; this.g = g_; this.b = b_;
            this.type = t; this.mass = m; this.temperature = temp;
        }
    }

    public enum SpawnMode { STATIC, ORBIT, LAUNCH }

    public static class MenuItem {
        public String name, type, guiTex, planetTex;
        public boolean isRigid;
        public double radius, mass, tilt, rotationPeriod, temperature;
        public float[][] uvMap;
        public final float[] flatUv;

        public MenuItem(String n, String gui, String tex, double r, double m, double t, double rot, double temp, float[][] uv, String type, boolean isRigid) {
            this(n, gui, tex, r, m, t, rot, temp, uv, buildFlatUv(uv), type, isRigid);
        }

        public MenuItem(String n, String gui, String tex, double r, double m, double t, double rot, double temp, float[][] uv, float[] flatUv, String type, boolean isRigid) {
            name = n; guiTex = gui; planetTex = tex; radius = r; mass = m; tilt = t;
            rotationPeriod = rot; temperature = temp; uvMap = uv;
            this.flatUv = (flatUv != null && flatUv.length == 24) ? flatUv : buildFlatUv(uv);
            this.type = type; this.isRigid = isRigid;
        }
    }

    public static MenuItem[] menuItems;
    static {
        PlanetRegistry.init();
        menuItems = PlanetRegistry.menuItems;
    }

    public static final List<Planet> planets = new ArrayList<>();
    public static final List<Particle> particles = new ArrayList<>(PhysicsEngine.MAX_GPU_DEBRIS);
    public static final List<Fragment> fragments = new ArrayList<>(PhysicsEngine.MAX_GPU_DEBRIS);

    public static volatile double simTimeSeconds = 0;
    public static double timeScale = 1.0;
    public static boolean isPaused = false;
    public static boolean spacePressed = false, upPressed = false, downPressed = false, isLMBPressed = false, isRMBPressed = false;
    public static boolean isWelcomeMenuOpen = true, isAddMenuOpen = false, isToolsMenuOpen = false, physicsEnabled = true;
    public static boolean showGrid = true, showTrails = true, isMoveToolActive = false;
    public static Planet draggedPlanet = null;
    public static boolean isPlacingPlanet = false;
    public static SpawnMode currentSpawnMode = SpawnMode.STATIC;
    public static Planet orbitTarget = null;

    public static boolean isDraggingOrbit = false;
    public static double orbitDragStartY = 0, orbitInclination = 0.0;
    public static final Vector3d baseOrbitPos = new Vector3d();

    public static double inputSpeed = 30.0, playerFlightSpeed = 100.0;
    public static int speedUnitIndex = 1, playerSpeedUnit = 1;
    public static boolean isTypingSpeed = false, isCustomSpeed = false, isUnitMenuOpen = false, autoOrbitSpeed = true;
    public static String typedSpeed = "";

    public static boolean isEditMenuMinimized = false, isTypingPlanetEdit = false;
    public static double editMenuScrollY = 0;
    public static int currentEditMenuX = 0, currentEditMenuW = 230, activeEditField = -1;
    public static String planetEditBuffer = "";
    public static final int[] fieldUnits = new int[6];

    public static int selectedPlanetIndex = 0, currentCategory = 0;
    public static double menuScrollY = 0;

    public static final int[] catHitX = new int[5], catHitW = new int[5];
    public static int catHitY = 0, catHitH = 0;
    public static boolean validSpawn = true;

    public static double lastRenderTime = 0.0;

    public static int minBtnX, minBtnY, minBtnW, minBtnH, raysBtnX, raysBtnY, raysBtnS = 20, flashBtnX, flashBtnY, flashBtnS = 20;
    public static int addBtnX, addBtnY, addBtnW, addBtnH, toolsBtnX, toolsBtnY, toolsBtnW, toolsBtnH;
    public static int staticBtnX, staticBtnY, staticBtnW, staticBtnH, orbitBtnX, orbitBtnY, orbitBtnW, orbitBtnH, launchBtnX, launchBtnY, launchBtnW, launchBtnH;
    public static final int[][] planetHitboxes = new int[64][4];
    public static int speedBoxHitX, speedBoxHitY, speedBoxHitW, speedBoxHitH, unitBoxHitX, unitBoxHitY, unitBoxHitW, unitBoxHitH, unitMenuHitX, unitMenuHitY, unitMenuHitW, unitMenuHitH, autoToggleHitX, autoToggleHitY, autoToggleHitW, autoToggleHitH;
    public static int playHitX, playHitY, playHitW, playHitH, leftArrowHitX, leftArrowHitY, leftArrowHitW, leftArrowHitH, rightArrowHitX, rightArrowHitY, rightArrowHitW, rightArrowHitH;
    public static int toolMenuX, toolMenuY, toolMenuW = 190, toolMenuH = 191, physicsToolY, moveToolY, gridToolY, trailToolY, clearToolY;
    public static int modBtnX, modBtnY, modBtnS = 24, tgBtnX, tgBtnY, tgBtnS = 24, daBtnX, daBtnY, daBtnS = 24, closeBtnX, closeBtnY, closeBtnW, closeBtnH;
    public static final int[][] editBoxHits = new int[6][4], unitBoxHits = new int[6][4];
    public static final int[][][] multBoxHits = new int[3][5][4];

    public static final boolean[] keyStates = new boolean[GLFW.GLFW_KEY_LAST + 1];
    public static final boolean[] justPressed = new boolean[GLFW.GLFW_KEY_LAST + 1];
    public static long lastScrollTime = 0;

    public static boolean isOverlappingAnyPlanet(Vector3d pos, double radius) {
        for (int i = 0, sz = planets.size(); i < sz; i++) {
            Planet p = planets.get(i);
            if (p == null || p.mass <= 0.0) continue;
            double minAllowedDist = (radius + p.radius) * 1.15;
            if (pos.distanceSquared(p.pos) < minAllowedDist * minAllowedDist) return true;
        }
        return false;
    }

    public static void applyDisplaySettings(long window) {
        if (window == 0) return;
        int[] res = RESOLUTIONS[resolutionIndex];
        long monitor = GLFW.glfwGetPrimaryMonitor();
        if (isFullscreen && monitor != 0) {
            GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
            int w = (mode != null) ? mode.width() : res[0];
            int h = (mode != null) ? mode.height() : res[1];
            int rate = (mode != null) ? mode.refreshRate() : 60;
            GLFW.glfwSetWindowMonitor(window, monitor, 0, 0, w, h, rate);
        } else {
            int posX = 80, posY = 50;
            if (monitor != 0) {
                GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
                if (mode != null) {
                    posX = Math.max(0, (mode.width() - res[0]) / 2);
                    posY = Math.max(28, (mode.height() - res[1]) / 2);
                }
            }
            GLFW.glfwSetWindowMonitor(window, 0, posX, posY, res[0], res[1], 0);
        }
        SBoxCompute.refreshWindowIcons(window);
        needsSwapchainRecreate = true;
    }

    public static void adjustSpeed(double vertical) {
        if (isAddMenuOpen && !isPhotoModeActive) {
            menuScrollY = Math.max(0.0, menuScrollY - vertical * 22.0);
            return;
        }
        if (CameraManager.isLocked && CameraManager.lockedTarget != null) {
            if (!isEditMenuMinimized && isObjectPreviewOpen && previewBoxHit[2] > 0 && previewBoxHit[3] > 0 && windowHandle != 0L) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    DoubleBuffer mx = stack.mallocDouble(1), my = stack.mallocDouble(1);
                    IntBuffer ww = stack.mallocInt(1), wh = stack.mallocInt(1);
                    IntBuffer fw = stack.mallocInt(1), fh = stack.mallocInt(1);
                    GLFW.glfwGetCursorPos(windowHandle, mx, my);
                    GLFW.glfwGetWindowSize(windowHandle, ww, wh);
                    GLFW.glfwGetFramebufferSize(windowHandle, fw, fh);
                    int uiScale = Math.max(1, fh.get(0) / 480);
                    double logW = (double) fw.get(0) / uiScale;
                    double logH = (double) fh.get(0) / uiScale;
                    double curX = mx.get(0) * (logW / Math.max(1, ww.get(0)));
                    double curY = my.get(0) * (logH / Math.max(1, wh.get(0)));
                    if (curX >= previewBoxHit[0] && curX <= previewBoxHit[0] + previewBoxHit[2]
                            && curY >= previewBoxHit[1] && curY <= previewBoxHit[1] + previewBoxHit[3]) {
                        if (vertical > 0) objectPreviewZoom = Math.max(2.0f, objectPreviewZoom / 1.14f);
                        else if (vertical < 0) objectPreviewZoom = Math.min(24.0f, objectPreviewZoom * 1.14f);
                        return;
                    }
                }
            }
            if (targetOrbitDistance < 0) targetOrbitDistance = CameraManager.lockedTarget.radius * 4.0;
            if (vertical > 0) targetOrbitDistance = Math.max(CameraManager.lockedTarget.radius * 1.85 + 0.5, targetOrbitDistance / 1.18);
            else if (vertical < 0) targetOrbitDistance *= 1.18;
            return;
        }
        lastScrollTime = System.currentTimeMillis();
        if (vertical > 0) playerFlightSpeed = Math.min(MAX_SPEED_KMS, playerFlightSpeed * 1.15);
        else if (vertical < 0) playerFlightSpeed = Math.max(0.1, playerFlightSpeed / 1.15);
    }

    public static void getInclinedOrbitState(Vector3d baseP, double inclination, Planet target, Vector3d outPos, Vector3d outUp) {
        SCRATCH_R.set(baseP).sub(target.pos);
        if (SCRATCH_R.lengthSquared() < 0.001) { outPos.set(baseP); outUp.set(0, 1, 0); return; }
        SCRATCH_AXIS.set(SCRATCH_R).cross(0, 1, 0);
        if (SCRATCH_AXIS.lengthSquared() < 0.001) SCRATCH_AXIS.set(1, 0, 0); else SCRATCH_AXIS.normalize();
        SCRATCH_R.rotateAxis(inclination, SCRATCH_AXIS.x, SCRATCH_AXIS.y, SCRATCH_AXIS.z);
        outPos.set(target.pos).add(SCRATCH_R);
        outUp.set(0, 1, 0).rotateAxis(inclination, SCRATCH_AXIS.x, SCRATCH_AXIS.y, SCRATCH_AXIS.z);
    }

    public static Vector3d getHologramPos(double radius) {
        CameraManager.ensureFitsOnScreen(radius);
        Vector3f rayF = CameraManager.getMouseRay();
        SCRATCH_DIR.set(rayF.x, rayF.y, rayF.z);
        if (isPlacingPlanet && currentSpawnMode == SpawnMode.LAUNCH) {
            return SCRATCH_HOLO.set(CameraManager.pos).fma(radius * 3.8 + 8.0, SCRATCH_DIR);
        }
        double minSafeDist = radius * 4.2 + 5.0;
        double t = (SCRATCH_DIR.y < -0.001) ? -CameraManager.pos.y / SCRATCH_DIR.y : minSafeDist;
        if (t < minSafeDist) t = minSafeDist;
        SCRATCH_HOLO.set(CameraManager.pos).fma(t, SCRATCH_DIR);
        SCRATCH_HOLO.y = 0.0;
        return SCRATCH_HOLO;
    }

    public static void applyTypedSpeed() {
        isTypingSpeed = false;
        try { if (!typedSpeed.isEmpty()) { inputSpeed = Double.parseDouble(typedSpeed); isCustomSpeed = true; } } catch (Exception ignored) {}
        capSpeed();
    }

    public static void changeUnit(int newUnit) {
        double kmS = getSpeedInKmS();
        speedUnitIndex = newUnit;
        if (speedUnitIndex == 0) inputSpeed = kmS / LIGHT_SPEED_KMS;
        else if (speedUnitIndex == 1) inputSpeed = kmS;
        else if (speedUnitIndex == 2) inputSpeed = kmS * 3600.0;
        else if (speedUnitIndex == 3) inputSpeed = kmS * 1000.0;
        capSpeed();
    }

    public static double getSpeedInKmS() {
        if (speedUnitIndex == 0) return inputSpeed * LIGHT_SPEED_KMS;
        if (speedUnitIndex == 1) return inputSpeed;
        if (speedUnitIndex == 2) return inputSpeed / 3600.0;
        if (speedUnitIndex == 3) return inputSpeed / 1000.0;
        return 0;
    }

    public static void capSpeed() {
        if (getSpeedInKmS() > LIGHT_SPEED_KMS) {
            if (speedUnitIndex == 0) inputSpeed = 1.0;
            else if (speedUnitIndex == 1) inputSpeed = LIGHT_SPEED_KMS;
            else if (speedUnitIndex == 2) inputSpeed = LIGHT_SPEED_KMS * 3600.0;
            else if (speedUnitIndex == 3) inputSpeed = LIGHT_SPEED_KMS * 1000.0;
        }
    }

    public static void applyPlanetEdit() {
        isTypingPlanetEdit = false;
        if (CameraManager.lockedTarget == null || activeEditField == -1) return;
        Planet p = CameraManager.lockedTarget;
        try {
            if (!planetEditBuffer.isEmpty()) {
                double val = Double.parseDouble(planetEditBuffer);
                int u = fieldUnits[activeEditField];
                switch (activeEditField) {
                    case 0:
                        if (u == 0) p.mass = val;
                        else if (u == 1) p.mass = val * 333000.0;
                        else if (u == 2) p.mass = val / 5.9722e24;
                        break;
                    case 1:
                        if (u == 0) p.radius = (val / 1260.0) / 2.0;
                        else if (u == 1) p.radius = (val * 12742.0 / 1260.0) / 2.0;
                        else if (u == 2) p.radius = (val * 1392700.0 / 1260.0) / 2.0;
                        p.targetRadius = p.radius;
                        break;
                    case 2:
                        if (u == 0) p.rotationPeriod = val;
                        else if (u == 1) p.rotationPeriod = val * 24.0;
                        else if (u == 2) p.rotationPeriod = val * 8760.0;
                        break;
                    case 3:
                        p.tilt = val;
                        break;
                    case 4:
                        double vNew = 0;
                        if (u == 0) vNew = val / 1260.0;
                        else if (u == 1) vNew = val / 1260000.0;
                        else if (u == 2) vNew = val / (1260.0 * 3600.0);
                        else if (u == 3) vNew = val * LIGHT_SPEED_KMS / 1260.0;
                        vNew = Math.min(vNew, PhysicsEngine.MAX_C_BLOCKS);
                        if (p.vel.lengthSquared() > 0.0001) p.vel.normalize().mul(vNew);
                        else p.vel.set(vNew, 0, 0);
                        break;
                    case 5:
                        if (u == 0) p.temperature = val;
                        else if (u == 1) p.temperature = val - 273.15;
                        else if (u == 2) p.temperature = (val - 32.0) / 1.8;
                        break;
                }
            }
        } catch (Exception ignored) {}
        activeEditField = -1;
    }

    public static Vector3d calculateSpawnVelocity(Vector3d pos, Vector3d up) {
        SCRATCH_VEL_OUT.zero();
        if (currentSpawnMode == SpawnMode.LAUNCH) {
            Vector3f rayF = CameraManager.getMouseRay();
            capSpeed();
            double launchSpeedBlocks = getSpeedInKmS() / BLOCK_TO_KM;
            SCRATCH_VEL_OUT.set(rayF.x, rayF.y, rayF.z).mul(launchSpeedBlocks);
        } else if (currentSpawnMode == SpawnMode.ORBIT && orbitTarget != null && planets.contains(orbitTarget)) {
            Planet attractor = orbitTarget;
            MenuItem item = customPlanet != null ? customPlanet : (menuItems != null && selectedPlanetIndex < menuItems.length ? menuItems[selectedPlanetIndex] : null);
            double m1 = Math.max(0.0, attractor.mass);
            double m2 = (item != null) ? Math.max(0.0, item.mass) : 0.0;
            double r1 = Math.max(0.01, attractor.radius);
            double r2 = (item != null) ? Math.max(0.01, item.radius) : 0.01;

            SCRATCH_DIR.set(attractor.pos).sub(pos);
            double distBlocks = SCRATCH_DIR.length();

            if (distBlocks > 0.1) {
                SCRATCH_DIR.div(distBlocks);
                if (autoOrbitSpeed) {
                    double totalMass = m1 + m2;
                    double effDist = Math.max(distBlocks, Math.max((r1 + r2) * 0.5, 0.1));
                    double schPotential = Math.min(2.5, 7.039726890892425e-9 * (totalMass / effDist));
                    double vBlocks = Math.sqrt((PhysicsEngine.G * totalMass * (1.0 + schPotential)) / effDist);
                    double requiredKmS = vBlocks * BLOCK_TO_KM;
                    if (speedUnitIndex == 0) inputSpeed = requiredKmS / LIGHT_SPEED_KMS;
                    else if (speedUnitIndex == 1) inputSpeed = requiredKmS;
                    else if (speedUnitIndex == 2) inputSpeed = requiredKmS * 3600.0;
                    else if (speedUnitIndex == 3) inputSpeed = requiredKmS * 1000.0;
                    capSpeed();
                }

                double orbitalVelocity = getSpeedInKmS() / BLOCK_TO_KM;
                SCRATCH_RIGHT.set(SCRATCH_DIR).cross(up);
                if (SCRATCH_RIGHT.lengthSquared() < 0.001) { up.set(1, 0, 0); SCRATCH_RIGHT.set(SCRATCH_DIR).cross(up); }
                SCRATCH_RIGHT.normalize();
                SCRATCH_VEL_OUT.set(SCRATCH_RIGHT).mul(orbitalVelocity).add(attractor.vel);
            }
        }
        if (Double.isNaN(SCRATCH_VEL_OUT.x)) SCRATCH_VEL_OUT.zero();
        return SCRATCH_VEL_OUT;
    }

    public static void spawnPlanetWithOrbit(Vector3d basePos, double inclination) {
        synchronized (STATE_LOCK) {
            MenuItem item = customPlanet != null ? customPlanet : menuItems[selectedPlanetIndex];
            getInclinedOrbitState(basePos, inclination, orbitTarget, SCRATCH_SPAWN_POS, SCRATCH_SPAWN_UP);
            if (isOverlappingAnyPlanet(SCRATCH_SPAWN_POS, item.radius)) {
                SpaceUI.addNotification("Cannot spawn inside another object!");
                return;
            }
            Vector3d velocity = calculateSpawnVelocity(SCRATCH_SPAWN_POS, SCRATCH_SPAWN_UP);
            planets.add(new Planet(item.name, SCRATCH_SPAWN_POS.x, SCRATCH_SPAWN_POS.y, SCRATCH_SPAWN_POS.z, velocity, item.radius, item.mass, item.tilt, item.rotationPeriod, item.temperature, item.planetTex, item.uvMap, item.type, item.isRigid));
            planetsNeedSort = true;
            customPlanet = null;
        }
    }

    public static void spawnPlanet(Vector3d pos, Vector3d up) {
        synchronized (STATE_LOCK) {
            MenuItem item = customPlanet != null ? customPlanet : menuItems[selectedPlanetIndex];
            if (isOverlappingAnyPlanet(pos, item.radius)) {
                SpaceUI.addNotification("Cannot spawn inside another object!");
                return;
            }
            Vector3d velocity = calculateSpawnVelocity(pos, up);
            planets.add(new Planet(item.name, pos.x, pos.y, pos.z, velocity, item.radius, item.mass, item.tilt, item.rotationPeriod, item.temperature, item.planetTex, item.uvMap, item.type, item.isRigid));
            planetsNeedSort = true;
            customPlanet = null;
        }
    }

    private static void syncFrameToMonitorRefreshRate() {
        if (!vsyncEnabled) {
            lastFrameSyncNs = System.nanoTime();
            return;
        }
        int refreshRate = 60;
        long monitor = GLFW.glfwGetPrimaryMonitor();
        if (monitor != 0L) {
            GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
            if (mode != null && mode.refreshRate() > 20) refreshRate = mode.refreshRate();
        }
        long targetFrameNs = 1_000_000_000L / refreshRate;
        long now = System.nanoTime();
        long elapsed = now - lastFrameSyncNs;
        if (elapsed < targetFrameNs) {
            long remainingNs = targetFrameNs - elapsed;
            if (remainingNs > 2_000_000L) {
                LockSupport.parkNanos(remainingNs - 1_500_000L);
            }
            while (System.nanoTime() - lastFrameSyncNs < targetFrameNs) {
                Thread.onSpinWait();
            }
        }
        lastFrameSyncNs = System.nanoTime();
    }

    public static void renderFrame() {
        long window = windowHandle != 0 ? windowHandle : GLFW.glfwGetCurrentContext();
        if (window == 0) return;
        windowHandle = window;

        syncFrameToMonitorRefreshRate();

        GLFW.glfwPollEvents();
        if (!CameraManager.isRotating()) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
        }

        double currentRealTime = GLFW.glfwGetTime();
        if (lastRenderTime == 0) lastRenderTime = currentRealTime;
        double dtReal = currentRealTime - lastRenderTime;
        lastRenderTime = currentRealTime;

        if (dtReal > 0.0001) {
            smoothedFrameMs = smoothedFrameMs * 0.9 + (dtReal * 1000.0) * 0.1;
            currentFps = Math.max(1, (int) Math.round(1000.0 / smoothedFrameMs));
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer fw = stack.mallocInt(1), fh = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(window, fw, fh);
            int fWidth = fw.get(0), fHeight = fh.get(0);
            if (fWidth <= 0 || fHeight <= 0) return;

            if (!isVulkanInitialized) {
                GLFW.glfwSetWindowTitle(window, "Space SandBox");
                SBoxVulkanCore.init();
                if (!SBoxVulkanCore.supportsFp64) {
                    physicsPrecisionMode = 1;
                }
                SBoxVulkanSwapchain.initSwapchain(window, fWidth, fHeight);
                SBoxVulkanCanvas.createCanvas(fWidth, fHeight);
                SBoxCompute.init();
                SBoxVulkanUI.init();
                SBoxVulkanUI.updateCanvasDescriptor();
                PhysicsEngine.init();
                SpaceUI.preloadAllResources();
                SimulationManager.refreshSavedSimulations();
                loadSettings();
                SBoxAudioEngine.init();
                SBoxAudioEngine.setMusicVolume(musicVolume);
                isVulkanInitialized = true;
            } else if (needsSwapchainRecreate || fWidth != SBoxVulkanCanvas.canvasWidth || fHeight != SBoxVulkanCanvas.canvasHeight) {
                needsSwapchainRecreate = false;
                vkDeviceWaitIdle(SBoxVulkanCore.logicalDevice);
                SBoxVulkanSwapchain.destroySwapchain();
                SBoxVulkanCanvas.destroyCanvas();
                SBoxVulkanSwapchain.initSwapchain(window, fWidth, fHeight);
                SBoxVulkanCanvas.createCanvas(fWidth, fHeight);
                SBoxCompute.updateDescriptorSet();
                SBoxVulkanUI.updateCanvasDescriptor();
            }

            int uiScale = Math.max(1, fHeight / 480);
            int logicalW = fWidth / uiScale;
            int logicalH = fHeight / uiScale;

            float renderScale = getInternalRenderScale();
            activeRenderW = Math.max(320, Math.min(fWidth, Math.round(fWidth * renderScale)));
            activeRenderH = Math.max(180, Math.min(fHeight, Math.round(fHeight * renderScale)));

            VkDevice device = SBoxVulkanCore.logicalDevice;
            IntBuffer pImageIndex = stack.mallocInt(1);
            int[] ssboCounts;

            synchronized (STATE_LOCK) {
                SBoxCompute.waitForFrameFence();
                SpaceInput.processInput(window, logicalW, logicalH, uiScale);
                if (CameraManager.isLocked) isEscMenuOpen = false;
                if (isPhotoModeActive) isPaused = true;
                if (planetsNeedSort) {
                    planets.sort(WARP_GROUP_COMPARATOR);
                    planetsNeedSort = false;
                }
                PhysicsEngine.tick(dtReal);
                if (planetsNeedSort) {
                    planets.sort(WARP_GROUP_COMPARATOR);
                    planetsNeedSort = false;
                }
                CameraManager.update(null);

                ssboCounts = SBoxCompute.updateSSBOs();
                SpaceUI.renderDirectUI(window, logicalW, logicalH, uiScale);
                checkAndAutoSaveSettings();

                int acquireRes = vkAcquireNextImageKHR(device, SBoxVulkanSwapchain.swapchain, -1L, SBoxVulkanSwapchain.imageAvailableSemaphore, VK_NULL_HANDLE, pImageIndex);
                if (acquireRes == VK_ERROR_OUT_OF_DATE_KHR) {
                    needsSwapchainRecreate = true;
                    return;
                }
            }

            int imageIndex = pImageIndex.get(0);
            VkCommandBuffer cmd = SBoxVulkanCore.copyCommandBuffer;
            vkResetCommandBuffer(cmd, 0);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(cmd, beginInfo);

            SBoxCompute.recordSpacePass(cmd, stack, activeRenderW, activeRenderH, ssboCounts);

            if (!fsrEnabled) {
                VkImageMemoryBarrier.Buffer initSwapBarrier = VkImageMemoryBarrier.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .srcAccessMask(0)
                        .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(SBoxVulkanSwapchain.swapchainImages[imageIndex]);
                initSwapBarrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, initSwapBarrier);

                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                blit.srcOffsets(1).set(activeRenderW, activeRenderH, 1);
                blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                blit.dstOffsets(1).set(fWidth, fHeight, 1);
                blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                vkCmdBlitImage(cmd, SBoxVulkanCanvas.canvasImage, VK_IMAGE_LAYOUT_GENERAL, SBoxVulkanSwapchain.swapchainImages[imageIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_NEAREST);

                VkImageMemoryBarrier.Buffer uiBarrier = VkImageMemoryBarrier.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(SBoxVulkanSwapchain.swapchainImages[imageIndex]);
                uiBarrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, null, null, uiBarrier);
            } else {
                VkImageMemoryBarrier.Buffer fsrSwapBarrier = VkImageMemoryBarrier.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .srcAccessMask(0)
                        .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(SBoxVulkanSwapchain.swapchainImages[imageIndex]);
                fsrSwapBarrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, null, null, fsrSwapBarrier);
            }

            SBoxVulkanUI.renderUI(cmd, SBoxVulkanSwapchain.swapchainImageViews[imageIndex], fWidth, fHeight, logicalW, logicalH);

            VkImageMemoryBarrier.Buffer presentBarrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                    .dstAccessMask(0)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(SBoxVulkanSwapchain.swapchainImages[imageIndex]);
            presentBarrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, presentBarrier);

            vkEndCommandBuffer(cmd);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .waitSemaphoreCount(1).pWaitSemaphores(stack.longs(SBoxVulkanSwapchain.imageAvailableSemaphore))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBuffers(stack.pointers(cmd))
                    .pSignalSemaphores(stack.longs(SBoxVulkanSwapchain.renderFinishedSemaphore));

            vkResetFences(device, SBoxCompute.frameFence);
            vkQueueSubmit(SBoxVulkanCore.graphicsQueue, submitInfo, SBoxCompute.frameFence);

            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack).sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(stack.longs(SBoxVulkanSwapchain.renderFinishedSemaphore))
                    .swapchainCount(1).pSwapchains(stack.longs(SBoxVulkanSwapchain.swapchain))
                    .pImageIndices(pImageIndex);

            int presentRes = vkQueuePresentKHR(SBoxVulkanCore.graphicsQueue, presentInfo);
            if (presentRes == VK_ERROR_OUT_OF_DATE_KHR || presentRes == VK_SUBOPTIMAL_KHR) {
                needsSwapchainRecreate = true;
            }

            if (requestScreenshot) {
                requestScreenshot = false;
                vkWaitForFences(device, SBoxCompute.frameFence, true, -1L);
                SBoxCompute.captureScreenshot(activeRenderW, activeRenderH);
            }
        }
    }
}