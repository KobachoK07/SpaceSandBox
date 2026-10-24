package spacesb.remake;

import org.joml.Vector3f;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;
import spacesb.remake.planets.BasePlanet;
import spacesb.remake.planets.PlanetRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SpaceEngine {

    public static final Object STATE_LOCK = new Object();

    // ИСПРАВЛЕНО (Пункт 2 ревью): единый статический компаратор без аллокации лямбд в куче!
    public static final Comparator<Planet> WARP_GROUP_COMPARATOR =
            (a, b) -> Integer.compare(a.getWarpGroupKey(), b.getWarpGroupKey());
    public static volatile boolean planetsNeedSort = false;

    public static long windowHandle = 0;

    public static final double BLOCK_TO_KM = 1260.0;
    public static final String TEX_FRAGMENT = "assets/spacesb-remake/textures/particle/fragments.png";

    public static boolean enableCentrifugalForce = true;
    public static boolean enableRocheLimit = true;
    public static boolean enableGravity = true;
    public static boolean enableCollisions = true;
    public static boolean showFpsOverlay = false;
    public static double lastPhysicsTickMs = 0;
    public static MenuItem customPlanet = null;

    public static double lockedOrbitDistance = -1.0;
    public static double targetOrbitDistance = -1.0;
    public static Planet prevLockedTarget = null;

    public static boolean isVulkanInitialized = false;

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
        public double life = 2.5;
        public double targetY = 10;
        public double currentY = -30;
        public double alpha = 0.0;
        public Notification(String text) { this.text = text; }
    }
    public static final List<Notification> notifications = new ArrayList<>();

    public static class Scorch {
        public Vector3d localNorm;
        public double size, life, maxLife;
        public Scorch(Vector3d norm, double size, double life) {
            this.localNorm = new Vector3d(norm); this.size = size; this.life = life; this.maxLife = life;
        }
    }

    public static class Planet {
        public String name;
        public final Vector3d pos = new Vector3d();
        public final Vector3d vel = new Vector3d();
        public double radius, targetRadius;
        public double mass, tilt, rotationPeriod, temperature;
        public String texture;
        public float[][] uvMap;
        public final float[] flatUv;
        public String type;
        public boolean isRigid;
        public boolean raysOn = true, flashlightOn = false;
        public int rColor, gColor, bColor;
        public double currentAngle = 0.0;

        public final List<Vector3d> trail = new ArrayList<>();
        public final List<Scorch> scorches = new ArrayList<>();

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
                this.rColor = 225; this.gColor = 175; this.bColor = 125;
            } else if ("Saturn".equalsIgnoreCase(name)) {
                this.rColor = 235; this.gColor = 205; this.bColor = 140;
            } else if ("Uranus".equalsIgnoreCase(name)) {
                this.rColor = 135; this.gColor = 215; this.bColor = 235;
            } else if ("Sun".equalsIgnoreCase(name)) {
                this.rColor = 255; this.gColor = 200; this.bColor = 110;
            } else if ("Stephenson 2-18".equalsIgnoreCase(name)) {
                this.rColor = 255; this.gColor = 95; this.bColor = 45;
            } else {
                this.rColor = (int)(Math.random() * 155 + 100);
                this.gColor = (int)(Math.random() * 155 + 100);
                this.bColor = (int)(Math.random() * 155 + 100);
            }
        }

        public double getDensity() {
            if (radius <= 1e-6 || mass <= 0.0) return 0.0;
            double vol = (4.0 / 3.0) * Math.PI * (radius * radius * radius);
            if (vol <= 1e-18 || Double.isInfinite(vol) || Double.isNaN(vol)) return 0.0;
            return mass / vol;
        }

        public int getWarpGroupKey() {
            if ("Hole".equals(type)) return 0;
            if ("Star".equals(type)) return 1;
            return isRigid ? 2 : 3;
        }
    }

    public static class Fragment {
        public final Vector3d pos = new Vector3d();
        public final Vector3d vel = new Vector3d();
        public double size, life, maxLife, mass, temperature;
        public String tex;
        public float u1, v1, u2, v2;
        public final Vector3d rotAxis = new Vector3d(0, 1, 0);
        public double angle, rotSpeed;

        public Fragment() {}

        public Fragment(Vector3d pos, Vector3d vel, double size, double life, double mass, double temperature, String tex, float u1, float v1, float u2, float v2) {
            set(pos, vel, size, life, mass, temperature, tex, u1, v1, u2, v2);
        }

        public void set(Vector3d p, Vector3d v, double s, double l, double m, double temp, String tx, float u0, float v0, float u1_, float v1_) {
            this.pos.set(p);
            this.vel.set(v);
            this.size = s;
            this.life = l;
            this.maxLife = l;
            this.mass = m;
            this.temperature = temp;
            this.tex = tx;
            this.u1 = u0; this.v1 = v0; this.u2 = u1_; this.v2 = v1_;
            this.angle = Math.random() * 360.0;
            this.rotSpeed = Math.random() * 360.0 - 180.0;
        }
    }

    public static class Particle {
        public final Vector3d pos = new Vector3d();
        public final Vector3d vel = new Vector3d();
        public double size, life, maxLife;
        public int r, g, b, type;
        public double mass, temperature;

        public Particle() {}

        public Particle(Vector3d pos, Vector3d vel, double size, double life, int r, int g, int b, int type, double mass, double temperature) {
            set(pos, vel, size, life, r, g, b, type, mass, temperature);
        }

        public void set(Vector3d p, Vector3d v, double s, double l, int r_, int g_, int b_, int t, double m, double temp) {
            this.pos.set(p);
            this.vel.set(v);
            this.size = s;
            this.life = l;
            this.maxLife = l;
            this.r = r_; this.g = g_; this.b = b_;
            this.type = t;
            this.mass = m;
            this.temperature = temp;
        }
    }

    public enum SpawnMode { STATIC, ORBIT, LAUNCH }

    public static class MenuItem {
        public String name, type;
        public boolean isRigid;
        public String guiTex, planetTex;
        public double radius;
        public double mass, tilt, rotationPeriod, temperature;
        public float[][] uvMap;
        public final float[] flatUv;

        // Конструктор с поддержкой как 11, так и 12 аргументов для полной совместимости с PlanetRegistry
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
    public static final List<Particle> particles = new ArrayList<>();
    public static final List<Fragment> fragments = new ArrayList<>();

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
    public static double orbitDragStartY = 0;
    public static Vector3d baseOrbitPos = new Vector3d();
    public static double orbitInclination = 0.0;

    public static double inputSpeed = 30.0;
    public static int speedUnitIndex = 1;
    public static double playerFlightSpeed = 100.0;
    public static int playerSpeedUnit = 1;

    public static boolean isTypingSpeed = false, isCustomSpeed = false, isUnitMenuOpen = false, autoOrbitSpeed = true;
    public static String typedSpeed = "";

    public static boolean editFlashlight = false, editRays = true, isEditMenuMinimized = false;
    public static double editMenuScrollY = 0, editMenuAnimX = 0, lastUiTime = 0;
    public static int currentEditMenuX = 0, currentEditMenuW = 230, activeEditField = -1;
    public static boolean isTypingPlanetEdit = false;
    public static String planetEditBuffer = "";
    public static int[] fieldUnits = new int[6];

    public static int selectedPlanetIndex = 0;
    public static double menuScrollY = 0;
    public static int currentCategory = 0;

    public static int[] catHitX = new int[5], catHitW = new int[5];
    public static int catHitY = 0, catHitH = 0;
    public static boolean validSpawn = true;
    public static Vector3d lineStart3D = null, lineEnd3D = null, textMidPoint3D = null;
    public static String projectionText = "";

    public static boolean isMusicStarted = false;
    public static double lastMusicPlayTime = 0.0;
    public static double lastRenderTime = 0.0;

    public static int minBtnX, minBtnY, minBtnW, minBtnH, raysBtnX, raysBtnY, raysBtnS = 20, flashBtnX, flashBtnY, flashBtnS = 20;
    public static int addBtnX, addBtnY, addBtnW, addBtnH, toolsBtnX, toolsBtnY, toolsBtnW, toolsBtnH;
    public static int staticBtnX, staticBtnY, staticBtnW, staticBtnH, orbitBtnX, orbitBtnY, orbitBtnW, orbitBtnH, launchBtnX, launchBtnY, launchBtnW, launchBtnH;
    public static int[][] planetHitboxes = new int[64][4];
    public static int speedBoxHitX, speedBoxHitY, speedBoxHitW, speedBoxHitH, unitBoxHitX, unitBoxHitY, unitBoxHitW, unitBoxHitH, unitMenuHitX, unitMenuHitY, unitMenuHitW, unitMenuHitH, autoToggleHitX, autoToggleHitY, autoToggleHitW, autoToggleHitH;
    public static int playHitX, playHitY, playHitW, playHitH, leftArrowHitX, leftArrowHitY, leftArrowHitW, leftArrowHitH, rightArrowHitX, rightArrowHitY, rightArrowHitW, rightArrowHitH;
    public static int toolMenuX, toolMenuY, toolMenuW = 190, toolMenuH = 145, physicsToolY, moveToolY, gridToolY, trailToolY, clearToolY;
    public static int modBtnX, modBtnY, modBtnS = 24, tgBtnX, tgBtnY, tgBtnS = 24, daBtnX, daBtnY, daBtnS = 24, closeBtnX, closeBtnY, closeBtnW, closeBtnH;
    public static int[][] editBoxHits = new int[6][4], unitBoxHits = new int[6][4];
    public static int[][][] multBoxHits = new int[3][5][4];

    public static boolean[] keyStates = new boolean[GLFW.GLFW_KEY_LAST + 1];
    public static boolean[] justPressed = new boolean[GLFW.GLFW_KEY_LAST + 1];
    public static long lastScrollTime = 0;

    public static void adjustSpeed(double vertical) {
        if (isAddMenuOpen) {
            menuScrollY -= vertical * 22.0;
            if (menuScrollY < 0) menuScrollY = 0;
            return;
        }

        if (CameraManager.isLocked && CameraManager.lockedTarget != null) {
            if (targetOrbitDistance < 0) {
                targetOrbitDistance = CameraManager.lockedTarget.radius * 4.0;
            }
            if (vertical > 0) {
                targetOrbitDistance /= 1.18;
                double minDist = CameraManager.lockedTarget.radius * 1.85 + 0.5;
                if (targetOrbitDistance < minDist) targetOrbitDistance = minDist;
            } else if (vertical < 0) {
                targetOrbitDistance *= 1.18;
                double maxDist = CameraManager.lockedTarget.radius * 250.0;
                if (targetOrbitDistance > maxDist) targetOrbitDistance = maxDist;
            }
            return;
        }

        lastScrollTime = System.currentTimeMillis();
        if (vertical > 0) {
            playerFlightSpeed *= 1.15;
            if (playerFlightSpeed > 1000000.0) playerFlightSpeed = 1000000.0;
        } else if (vertical < 0) {
            playerFlightSpeed /= 1.15;
            if (playerFlightSpeed < 0.1) playerFlightSpeed = 0.1;
        }
    }

    public static void getInclinedOrbitState(Vector3d baseP, double inclination, Planet target, Vector3d outPos, Vector3d outUp) {
        Vector3d R = new Vector3d(baseP).sub(target.pos);
        if (R.lengthSquared() < 0.001) { outPos.set(baseP); outUp.set(0, 1, 0); return; }
        Vector3d axis = new Vector3d(R).cross(new Vector3d(0, 1, 0));
        if (axis.lengthSquared() < 0.001) axis.set(1, 0, 0); else axis.normalize();
        Vector3d rotR = new Vector3d(R).rotateAxis(inclination, axis.x, axis.y, axis.z);
        outPos.set(target.pos).add(rotR);
        outUp.set(0, 1, 0).rotateAxis(inclination, axis.x, axis.y, axis.z);
    }

    public static Vector3d getHologramPos(double radius) {
        CameraManager.ensureFitsOnScreen(radius);
        Vector3f rayF = CameraManager.getMouseRay();
        Vector3d dir = new Vector3d(rayF.x, rayF.y, rayF.z);
        if (isPlacingPlanet && currentSpawnMode == SpawnMode.LAUNCH) {
            return new Vector3d(CameraManager.pos).add(dir.mul(radius * 3.8 + 8.0));
        }
        double minSafeDist = radius * 4.2 + 5.0;
        double t = (dir.y < -0.001) ? -CameraManager.pos.y / dir.y : minSafeDist;
        if (t < minSafeDist) t = minSafeDist;
        Vector3d pos = new Vector3d(CameraManager.pos).add(dir.mul(t));
        pos.y = 0.0;
        return pos;
    }

    public static void applyTypedSpeed() {
        isTypingSpeed = false;
        try { if (!typedSpeed.isEmpty()) { inputSpeed = Double.parseDouble(typedSpeed); isCustomSpeed = true; } } catch (Exception e) {}
        capSpeed();
    }

    public static void changeUnit(int newUnit) {
        double kmS = getSpeedInKmS();
        speedUnitIndex = newUnit;
        if (speedUnitIndex == 0) inputSpeed = kmS / 299792.458;
        if (speedUnitIndex == 1) inputSpeed = kmS;
        if (speedUnitIndex == 2) inputSpeed = kmS * 3600.0;
        if (speedUnitIndex == 3) inputSpeed = kmS * 1000.0;
        capSpeed();
    }

    public static double getSpeedInKmS() {
        if (speedUnitIndex == 0) return inputSpeed * 299792.458;
        if (speedUnitIndex == 1) return inputSpeed;
        if (speedUnitIndex == 2) return inputSpeed / 3600.0;
        if (speedUnitIndex == 3) return inputSpeed / 1000.0;
        return 0;
    }

    public static void capSpeed() {
        double kmS = getSpeedInKmS();
        if (kmS > 299792.458) {
            if (speedUnitIndex == 0) inputSpeed = 1.0;
            if (speedUnitIndex == 1) inputSpeed = 299792.458;
            if (speedUnitIndex == 2) inputSpeed = 299792.458 * 3600.0;
            if (speedUnitIndex == 3) inputSpeed = 299792.458 * 1000.0;
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
                    case 0: if (u == 0) p.mass = val; else if (u == 1) p.mass = val * 333000.0; else if (u == 2) p.mass = val / 5.9722e24; break;
                    case 1: if (u == 0) p.radius = ((val / 1260.0) / 2.0); else if (u == 1) p.radius = ((val * 12742.0 / 1260.0) / 2.0); else if (u == 2) p.radius = ((val * 1392700.0 / 1260.0) / 2.0); p.targetRadius = p.radius; break;
                    case 2: if (u == 0) p.rotationPeriod = val; else if (u == 1) p.rotationPeriod = val * 24.0; else if (u == 2) p.rotationPeriod = val * 8760.0; break;
                    case 3: p.tilt = val; break;
                    case 4: double vNew = 0; if (u == 0) vNew = (val / 1260.0); else if (u == 1) vNew = (val / 1260000.0); else if (u == 2) vNew = (val / (1260.0 * 3600.0)); else if (u == 3) vNew = (val * 299792.458 / 1260.0);
                        if (p.vel.lengthSquared() > 0.0001) p.vel.normalize().mul(vNew); else p.vel.set(vNew, 0, 0); break;
                    case 5: if (u == 0) p.temperature = val; else if (u == 1) p.temperature = val - 273.15; else if (u == 2) p.temperature = (val - 32.0) / 1.8; break;
                }
            }
        } catch (Exception e) {}
        activeEditField = -1;
    }

    public static Vector3d calculateSpawnVelocity(Vector3d pos, Vector3d up) {
        Vector3d velocity = new Vector3d(0, 0, 0);
        projectionText = "";

        if (currentSpawnMode == SpawnMode.LAUNCH) {
            Vector3f rayF = CameraManager.getMouseRay();
            Vector3d forward = new Vector3d(rayF.x, rayF.y, rayF.z);
            capSpeed(); double speedInKmS = getSpeedInKmS();
            double launchSpeedBlocks = speedInKmS / BLOCK_TO_KM; velocity = new Vector3d(forward).mul(launchSpeedBlocks);
        } else if (currentSpawnMode == SpawnMode.ORBIT && orbitTarget != null && planets.contains(orbitTarget)) {
            Planet attractor = orbitTarget;
            Vector3d dirToAttractor = new Vector3d(attractor.pos).sub(pos);
            double distBlocks = dirToAttractor.length();

            if (distBlocks > 0.1) {
                dirToAttractor.normalize();
                if (autoOrbitSpeed) {
                    double vBlocks = Math.sqrt((PhysicsEngine.G * attractor.mass) / distBlocks);
                    double requiredKmS = vBlocks * BLOCK_TO_KM;
                    if (speedUnitIndex == 0) inputSpeed = requiredKmS / 299792.458;
                    else if (speedUnitIndex == 1) inputSpeed = requiredKmS;
                    else if (speedUnitIndex == 2) inputSpeed = requiredKmS * 3600.0;
                    else if (speedUnitIndex == 3) inputSpeed = requiredKmS * 1000.0;
                    capSpeed();
                }

                double speedInKmS = getSpeedInKmS();
                double orbitalVelocity = speedInKmS / BLOCK_TO_KM;
                Vector3d right = new Vector3d(dirToAttractor).cross(up);
                if (right.lengthSquared() < 0.001) { up.set(1, 0, 0); right = new Vector3d(dirToAttractor).cross(up); }
                right.normalize();
                velocity = right.mul(orbitalVelocity).add(attractor.vel);
            }
        }
        if (Double.isNaN(velocity.x)) velocity.set(0,0,0);
        return velocity;
    }

    // ИСПРАВЛЕНО (Пункт 1 ревью): методы спавна только выставляют флаг planetsNeedSort = true, а сама сортировка выполняется строго внутри STATE_LOCK в renderFrame!
    public static void spawnPlanetWithOrbit(Vector3d basePos, double inclination) {
        synchronized (STATE_LOCK) {
            MenuItem item = customPlanet != null ? customPlanet : menuItems[selectedPlanetIndex];
            Vector3d spawnPos = new Vector3d(), spawnUp = new Vector3d();
            getInclinedOrbitState(basePos, inclination, orbitTarget, spawnPos, spawnUp);
            Vector3d velocity = calculateSpawnVelocity(spawnPos, spawnUp);
            planets.add(new Planet(item.name, spawnPos.x, spawnPos.y, spawnPos.z, velocity, item.radius, item.mass, item.tilt, item.rotationPeriod, item.temperature, item.planetTex, item.uvMap, item.type, item.isRigid));
            planetsNeedSort = true;
            customPlanet = null;
        }
    }

    public static void spawnPlanet(Vector3d pos, Vector3d up) {
        synchronized (STATE_LOCK) {
            MenuItem item = customPlanet != null ? customPlanet : menuItems[selectedPlanetIndex];
            Vector3d velocity = calculateSpawnVelocity(pos, up);
            planets.add(new Planet(item.name, pos.x, pos.y, pos.z, velocity, item.radius, item.mass, item.tilt, item.rotationPeriod, item.temperature, item.planetTex, item.uvMap, item.type, item.isRigid));
            planetsNeedSort = true;
            customPlanet = null;
        }
    }

    public static void renderFrame() {
        long window = windowHandle != 0 ? windowHandle : GLFW.glfwGetCurrentContext();
        if (window == 0) return;
        windowHandle = window;

        GLFW.glfwPollEvents();
        if (!CameraManager.isRotating()) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
        }

        try {
            Class.forName("org.lwjgl.vulkan.VK10");
        } catch (ClassNotFoundException e) {
            System.err.println("[SpaceSandBox] КРИТИЧЕСКАЯ ОШИБКА: Библиотеки Vulkan не найдены!");
            return;
        }

        double currentRealTime = GLFW.glfwGetTime();
        if (lastRenderTime == 0) lastRenderTime = currentRealTime;
        double dtReal = currentRealTime - lastRenderTime;
        lastRenderTime = currentRealTime;

        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.IntBuffer fw = stack.mallocInt(1);
            java.nio.IntBuffer fh = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(window, fw, fh);
            int fWidth = fw.get(0);
            int fHeight = fh.get(0);

            if (fWidth <= 0 || fHeight <= 0) return;

            if (!isVulkanInitialized) {
                GLFW.glfwSetWindowTitle(window, "Space SandBox");

                spacesb.remake.render.SBoxVulkanCore.init();
                spacesb.remake.render.SBoxVulkanSwapchain.initSwapchain(window, fWidth, fHeight);
                spacesb.remake.render.SBoxVulkanCanvas.createCanvas(fWidth, fHeight);
                spacesb.remake.render.SBoxCompute.init();
                spacesb.remake.render.SBoxVulkanUI.init();
                PhysicsEngine.init();

                SpaceUI.preloadAllResources();

                isVulkanInitialized = true;
            } else if (fWidth != spacesb.remake.render.SBoxVulkanCanvas.canvasWidth || fHeight != spacesb.remake.render.SBoxVulkanCanvas.canvasHeight) {
                org.lwjgl.vulkan.VK10.vkDeviceWaitIdle(spacesb.remake.render.SBoxVulkanCore.logicalDevice);
                spacesb.remake.render.SBoxVulkanSwapchain.destroySwapchain();
                spacesb.remake.render.SBoxVulkanCanvas.destroyCanvas();

                spacesb.remake.render.SBoxVulkanSwapchain.initSwapchain(window, fWidth, fHeight);
                spacesb.remake.render.SBoxVulkanCanvas.createCanvas(fWidth, fHeight);
                spacesb.remake.render.SBoxCompute.updateDescriptorSet();
            }

            int uiScale = Math.max(1, fHeight / 480);
            int logicalW = fWidth / uiScale;
            int logicalH = fHeight / uiScale;

            org.lwjgl.vulkan.VkDevice device = spacesb.remake.render.SBoxVulkanCore.logicalDevice;
            java.nio.IntBuffer pImageIndex = stack.mallocInt(1);

            synchronized (STATE_LOCK) {
                SpaceInput.processInput(window, logicalW, logicalH, uiScale);
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

                org.lwjgl.vulkan.KHRSwapchain.vkAcquireNextImageKHR(device, spacesb.remake.render.SBoxVulkanSwapchain.swapchain, -1L, spacesb.remake.render.SBoxVulkanSwapchain.imageAvailableSemaphore, org.lwjgl.vulkan.VK10.VK_NULL_HANDLE, pImageIndex);
                spacesb.remake.render.SBoxCompute.renderSpace(fWidth, fHeight);
                SpaceUI.renderDirectUI(window, logicalW, logicalH, uiScale);
            }

            int imageIndex = pImageIndex.get(0);

            org.lwjgl.vulkan.VkCommandBuffer cmd = spacesb.remake.render.SBoxVulkanCore.copyCommandBuffer;
            org.lwjgl.vulkan.VK10.vkResetCommandBuffer(cmd, 0);

            org.lwjgl.vulkan.VkCommandBufferBeginInfo beginInfo = org.lwjgl.vulkan.VkCommandBufferBeginInfo.calloc(stack).sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            org.lwjgl.vulkan.VK10.vkBeginCommandBuffer(cmd, beginInfo);

            org.lwjgl.vulkan.VkImageMemoryBarrier.Buffer initSwapBarrier = org.lwjgl.vulkan.VkImageMemoryBarrier.calloc(1, stack)
                    .sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcAccessMask(0)
                    .dstAccessMask(org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .srcQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(spacesb.remake.render.SBoxVulkanSwapchain.swapchainImages[imageIndex]);
            initSwapBarrier.subresourceRange().aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier(cmd, org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, initSwapBarrier);

            org.lwjgl.vulkan.VkImageBlit.Buffer blit = org.lwjgl.vulkan.VkImageBlit.calloc(1, stack);
            blit.srcOffsets(1).set(fWidth, fHeight, 1);
            blit.srcSubresource().aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blit.dstOffsets(1).set(fWidth, fHeight, 1);
            blit.dstSubresource().aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            org.lwjgl.vulkan.VK10.vkCmdBlitImage(cmd, spacesb.remake.render.SBoxVulkanCanvas.canvasImage, org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL, spacesb.remake.render.SBoxVulkanSwapchain.swapchainImages[imageIndex], org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST);

            org.lwjgl.vulkan.VkImageMemoryBarrier.Buffer uiBarrier = org.lwjgl.vulkan.VkImageMemoryBarrier.calloc(1, stack)
                    .sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .srcAccessMask(org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                    .srcQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(spacesb.remake.render.SBoxVulkanSwapchain.swapchainImages[imageIndex]);
            uiBarrier.subresourceRange().aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier(cmd, org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, null, null, uiBarrier);

            spacesb.remake.render.SBoxVulkanUI.renderUI(cmd, spacesb.remake.render.SBoxVulkanSwapchain.swapchainImageViews[imageIndex], fWidth, fHeight, logicalW, logicalH);

            org.lwjgl.vulkan.VkImageMemoryBarrier.Buffer presentBarrier = org.lwjgl.vulkan.VkImageMemoryBarrier.calloc(1, stack)
                    .sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .newLayout(org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                    .srcAccessMask(org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                    .dstAccessMask(0)
                    .srcQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(spacesb.remake.render.SBoxVulkanSwapchain.swapchainImages[imageIndex]);
            presentBarrier.subresourceRange().aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier(cmd, org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, presentBarrier);

            org.lwjgl.vulkan.VK10.vkEndCommandBuffer(cmd);

            org.lwjgl.vulkan.VkSubmitInfo submitInfo = org.lwjgl.vulkan.VkSubmitInfo.calloc(stack).sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .waitSemaphoreCount(1).pWaitSemaphores(stack.longs(spacesb.remake.render.SBoxVulkanSwapchain.imageAvailableSemaphore))
                    .pWaitDstStageMask(stack.ints(org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBuffers(stack.pointers(cmd))
                    .pSignalSemaphores(stack.longs(spacesb.remake.render.SBoxVulkanSwapchain.renderFinishedSemaphore));

            org.lwjgl.vulkan.VK10.vkQueueSubmit(spacesb.remake.render.SBoxVulkanCore.graphicsQueue, submitInfo, org.lwjgl.vulkan.VK10.VK_NULL_HANDLE);

            org.lwjgl.vulkan.VkPresentInfoKHR presentInfo = org.lwjgl.vulkan.VkPresentInfoKHR.calloc(stack).sType(org.lwjgl.vulkan.KHRSwapchain.VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(stack.longs(spacesb.remake.render.SBoxVulkanSwapchain.renderFinishedSemaphore))
                    .swapchainCount(1).pSwapchains(stack.longs(spacesb.remake.render.SBoxVulkanSwapchain.swapchain))
                    .pImageIndices(pImageIndex);

            org.lwjgl.vulkan.KHRSwapchain.vkQueuePresentKHR(spacesb.remake.render.SBoxVulkanCore.graphicsQueue, presentInfo);
            org.lwjgl.vulkan.VK10.vkQueueWaitIdle(spacesb.remake.render.SBoxVulkanCore.graphicsQueue);
        }
    }
}