package spacesb.remake;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;
import spacesb.remake.planets.PlanetRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class SpaceEngine {

    public static final double BLOCK_TO_KM = 1260.0;

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

    public static class Notification {
        public String text;
        public double life = 2.5;
        public double targetY = 10;
        public double currentY = -30;
        public double alpha = 0.0;
        public Notification(String text) { this.text = text; }
    }
    public static final List<Notification> notifications = new CopyOnWriteArrayList<>();

    public static final Identifier ICON_PLANETS = Identifier.of("spacesb-remake", "textures/gui/planets.png");
    public static final Identifier ICON_MOONS = Identifier.of("spacesb-remake", "textures/gui/moons.png");
    public static final Identifier ICON_STARS = Identifier.of("spacesb-remake", "textures/gui/stars.png");
    public static final Identifier ICON_HOLES = Identifier.of("spacesb-remake", "textures/gui/holes.png");
    public static final Identifier CURSOR_TEX = Identifier.of("spacesb-remake", "textures/gui/cursor.png");
    public static final Identifier CURSOR_AIM_TEX = Identifier.of("spacesb-remake", "textures/gui/cursor1.png");
    public static final Identifier ICON_FLASHLIGHT = Identifier.of("spacesb-remake", "textures/gui/flashlight.png");
    public static final Identifier ICON_RAYS = Identifier.of("spacesb-remake", "textures/gui/rays.png");
    public static final Identifier LOGO_TEX = Identifier.of("spacesb-remake", "textures/gui/mod_logo.png");
    public static final Identifier MODRINTH_TEX = Identifier.of("spacesb-remake", "textures/gui/mod.png");
    public static final Identifier TELEGRAM_TEX = Identifier.of("spacesb-remake", "textures/gui/tgg.png");
    public static final Identifier DONATE_TEX = Identifier.of("spacesb-remake", "textures/gui/da.png");

    public static final Identifier SKY_FRONT = Identifier.of("spacesb-remake", "textures/sky/sky_front.png");
    public static final Identifier SKY_BACK = Identifier.of("spacesb-remake", "textures/sky/sky_back.png");
    public static final Identifier SKY_LEFT = Identifier.of("spacesb-remake", "textures/sky/sky_left.png");
    public static final Identifier SKY_RIGHT = Identifier.of("spacesb-remake", "textures/sky/sky_right.png");
    public static final Identifier SKY_TOP = Identifier.of("spacesb-remake", "textures/sky/sky_top.png");
    public static final Identifier SKY_BOTTOM = Identifier.of("spacesb-remake", "textures/sky/sky_bottom.png");

    public static final Identifier TEX_EXPLOSION = Identifier.of("spacesb-remake", "textures/particle/explosionsmini.png");
    public static final Identifier TEX_FRAGMENT = Identifier.of("spacesb-remake", "textures/particle/fragments.png");

    public static class Scorch {
        public Vector3d localNorm;
        public double size, life, maxLife;
        public Scorch(Vector3d norm, double size, double life) {
            this.localNorm = new Vector3d(norm); this.size = size; this.life = life; this.maxLife = life;
        }
    }

    public static class Planet {
        public String name;
        public Vector3d pos, vel;
        public double radius, targetRadius;
        public double mass, tilt, rotationPeriod, temperature;
        public Identifier texture;
        public float[][] uvMap;
        public String type;
        public boolean isRigid;
        public boolean raysOn = true, flashlightOn = false;
        public int rColor, gColor, bColor;

        // НОВОЕ: Накопленный угол вращения для плавности
        public double currentAngle = 0.0;

        public List<Vector3d> trail = new CopyOnWriteArrayList<>();
        public List<Scorch> scorches = new CopyOnWriteArrayList<>();

        public Planet(String name, double x, double y, double z, Vector3d vel, double radius, double mass, double tilt, double rot, double temp, Identifier tex, float[][] uv, String type, boolean isRigid) {
            this.name = name; this.pos = new Vector3d(x, y, z); this.vel = new Vector3d(vel); this.radius = radius; this.targetRadius = radius;
            this.mass = mass; this.tilt = tilt; this.rotationPeriod = rot; this.temperature = temp;
            this.texture = tex; this.uvMap = uv; this.type = type; this.isRigid = isRigid;
            this.rColor = (int)(Math.random() * 155 + 100); this.gColor = (int)(Math.random() * 155 + 100); this.bColor = (int)(Math.random() * 155 + 100);
        }

        public double getDensity() {
            double vol = (4.0 / 3.0) * Math.PI * Math.pow(radius, 3);
            if (vol <= 0) return 0;
            return mass / vol;
        }
    }

    public static class Fragment {
        public Vector3d pos, vel;
        public double size, life, maxLife, mass;
        public Identifier tex;
        public float u1, v1, u2, v2;
        public Vector3f rotAxis;
        public float angle, rotSpeed;

        public Fragment(Vector3d pos, Vector3d vel, double size, double life, double mass, Identifier tex, float u1, float v1, float u2, float v2) {
            this.pos = new Vector3d(pos); this.vel = new Vector3d(vel);
            this.size = size; this.life = life; this.maxLife = life; this.mass = mass;
            this.tex = tex; this.u1 = u1; this.v1 = v1; this.u2 = u2; this.v2 = v2;
            this.rotAxis = new Vector3f((float)Math.random(), (float)Math.random(), (float)Math.random()).normalize();
            this.rotSpeed = (float)(Math.random() * 360.0 - 180.0);
            this.angle = (float)(Math.random() * 360.0);
        }
    }

    public static class Particle {
        public Vector3d pos, vel;
        public double size, life, maxLife;
        public int r, g, b, type;
        public double mass;

        public Particle(Vector3d pos, Vector3d vel, double size, double life, int r, int g, int b, int type, double mass) {
            this.pos = new Vector3d(pos); this.vel = new Vector3d(vel); this.size = size;
            this.life = life; this.maxLife = life; this.r = r; this.g = g; this.b = b; this.type = type; this.mass = mass;
        }
    }

    public enum SpawnMode { STATIC, ORBIT, LAUNCH }

    public static class MenuItem {
        public String name, type;
        public boolean isRigid;
        public Identifier guiTex, planetTex;
        public float radius;
        public double mass, tilt, rotationPeriod, temperature;
        public float[][] uvMap;
        public MenuItem(String n, Identifier gui, Identifier tex, float r, double m, double t, double rot, double temp, float[][] uv, String type, boolean isRigid) {
            name = n; guiTex = gui; planetTex = tex; radius = r; mass = m; tilt = t; rotationPeriod = rot; temperature = temp; uvMap = uv; this.type = type; this.isRigid = isRigid;
        }
    }

    public static MenuItem[] menuItems;
    static {
        PlanetRegistry.init();
        menuItems = PlanetRegistry.menuItems;
    }

    public static final List<Planet> planets = new CopyOnWriteArrayList<>();
    public static final List<Particle> particles = new CopyOnWriteArrayList<>();
    public static final List<Fragment> fragments = new CopyOnWriteArrayList<>();

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
    public static int currentEditMenuX = 0, currentEditMenuW = 180, activeEditField = -1;
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
    public static int toolMenuX, toolMenuY, toolMenuW = 140, toolMenuH = 113, physicsToolY, moveToolY, gridToolY, trailToolY, clearToolY;
    public static int modBtnX, modBtnY, modBtnS = 24, tgBtnX, tgBtnY, tgBtnS = 24, daBtnX, daBtnY, daBtnS = 24, closeBtnX, closeBtnY, closeBtnW, closeBtnH;
    public static int[][] editBoxHits = new int[6][4], unitBoxHits = new int[6][4];
    public static int[][][] multBoxHits = new int[3][5][4];

    public static boolean[] keyStates = new boolean[GLFW.GLFW_KEY_LAST + 1];
    public static boolean[] justPressed = new boolean[GLFW.GLFW_KEY_LAST + 1];

    public static long lastScrollTime = 0;

    public static void onScroll(double yoffset) { SpaceUI.onScroll(yoffset); }

    public static void processInput(long window) {
        SpaceInput.processInput(window);

        if (isToolsMenuOpen && !isWelcomeMenuOpen) {
            double mX = MinecraftClient.getInstance().mouse.getX() * MinecraftClient.getInstance().getWindow().getScaledWidth() / (double) MinecraftClient.getInstance().getWindow().getWidth();
            double mY = MinecraftClient.getInstance().mouse.getY() * MinecraftClient.getInstance().getWindow().getScaledHeight() / (double) MinecraftClient.getInstance().getWindow().getHeight();
            if (isLMBPressed && mX >= toolMenuX + 8 && mX <= toolMenuX + toolMenuW - 8 && mY >= clearToolY && mY <= clearToolY + 16) {
                fragments.clear();
            }
        }
    }

    public static void renderUI(DrawContext context) { SpaceUI.renderUI(context); }

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
                    case 0:
                        if (u == 0) p.mass = val; else if (u == 1) p.mass = val * 333000.0; else if (u == 2) p.mass = val / 5.9722e24; break;
                    case 1:
                        if (u == 0) p.radius = ((val / 1260.0) / 2.0); else if (u == 1) p.radius = ((val * 12742.0 / 1260.0) / 2.0); else if (u == 2) p.radius = ((val * 1392700.0 / 1260.0) / 2.0);
                        p.targetRadius = p.radius;
                        break;
                    case 2:
                        if (u == 0) p.rotationPeriod = val; else if (u == 1) p.rotationPeriod = val * 24.0; else if (u == 2) p.rotationPeriod = val * 8760.0; break;
                    case 3:
                        p.tilt = val; break;
                    case 4:
                        double vNew = 0;
                        if (u == 0) vNew = (val / 1260.0); else if (u == 1) vNew = (val / 1260000.0); else if (u == 2) vNew = (val / (1260.0 * 3600.0)); else if (u == 3) vNew = (val * 299792.458 / 1260.0);
                        double maxSpeedBlocks = PhysicsEngine.MAX_C_BLOCKS;
                        if (vNew > maxSpeedBlocks) vNew = maxSpeedBlocks;
                        if (p.vel.lengthSquared() > 0.0001) p.vel.normalize().mul(vNew); else p.vel.set(vNew, 0, 0); break;
                    case 5:
                        if (u == 0) p.temperature = val; else if (u == 1) p.temperature = val + 273.15; else if (u == 2) p.temperature = (val - 32.0) / 1.8; break;
                }
            }
        } catch (Exception e) {}
        activeEditField = -1;
    }

    public static Vector3d getHologramPos(double radius) {
        org.joml.Vector3f rayF = CameraManager.getMouseRay();
        Vector3d dir = new Vector3d(rayF.x, rayF.y, rayF.z);
        if (isPlacingPlanet && currentSpawnMode == SpawnMode.LAUNCH) return new Vector3d(CameraManager.pos).add(dir.mul(radius * 2.5 + 5.0));
        double t = (dir.y < -0.001) ? -CameraManager.pos.y / dir.y : 1000.0;
        Vector3d pos = new Vector3d(CameraManager.pos).add(dir.mul(t)); pos.y = 0.0; return pos;
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

    public static Vector3d calculateSpawnVelocity(Vector3d pos, Vector3d up) {
        Vector3d velocity = new Vector3d(0, 0, 0);
        projectionText = "";
        double maxSpeedBlocks = PhysicsEngine.MAX_C_BLOCKS;

        if (currentSpawnMode == SpawnMode.LAUNCH) {
            org.joml.Vector3f rayF = CameraManager.getMouseRay();
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
                projectionText = SpaceUI.formatDistanceDynamic(distBlocks * BLOCK_TO_KM);
            }
        }
        if (velocity.length() > maxSpeedBlocks) velocity.normalize().mul(maxSpeedBlocks);
        if (Double.isNaN(velocity.x)) velocity.set(0,0,0);
        return velocity;
    }

    public static void spawnPlanetWithOrbit(Vector3d basePos, double inclination) {
        MenuItem item = customPlanet != null ? customPlanet : menuItems[selectedPlanetIndex];
        Vector3d spawnPos = new Vector3d(), spawnUp = new Vector3d();
        getInclinedOrbitState(basePos, inclination, orbitTarget, spawnPos, spawnUp);
        Vector3d velocity = calculateSpawnVelocity(spawnPos, spawnUp);
        planets.add(new Planet(item.name, spawnPos.x, spawnPos.y, spawnPos.z, velocity, item.radius, item.mass, item.tilt, item.rotationPeriod, item.temperature, item.planetTex, item.uvMap, item.type, item.isRigid));
        customPlanet = null;
    }

    public static void spawnPlanet(Vector3d pos, Vector3d up) {
        MenuItem item = customPlanet != null ? customPlanet : menuItems[selectedPlanetIndex];
        Vector3d velocity = calculateSpawnVelocity(pos, up);
        planets.add(new Planet(item.name, pos.x, pos.y, pos.z, velocity, item.radius, item.mass, item.tilt, item.rotationPeriod, item.temperature, item.planetTex, item.uvMap, item.type, item.isRigid));
        customPlanet = null;
    }

    public static void render3D(DrawContext context) {
        double currentRealTime = GLFW.glfwGetTime();
        if (lastRenderTime == 0) lastRenderTime = currentRealTime;
        double dtReal = currentRealTime - lastRenderTime;
        lastRenderTime = currentRealTime;

        MinecraftClient client = MinecraftClient.getInstance();
        client.getWindow().setTitle("Space SandBox");

        PhysicsEngine.tick(dtReal);

        MenuItem selectedItem = customPlanet != null ? customPlanet : (menuItems == null || menuItems.length == 0 ? null : menuItems[selectedPlanetIndex]);
        boolean hasBH = false;
        Planet holoPlanet = null;
        Vector3d holoPos = new Vector3d();
        Vector3d holoUp = new Vector3d(0, 1, 0);
        validSpawn = true;

        boolean drawLine = false;
        Vector3d startPos = new Vector3d();

        if (isPlacingPlanet && !isWelcomeMenuOpen && selectedItem != null) {
            if (currentSpawnMode == SpawnMode.ORBIT) {
                if (isDraggingOrbit && orbitTarget != null) getInclinedOrbitState(baseOrbitPos, orbitInclination, orbitTarget, holoPos, holoUp);
                else holoPos.set(getHologramPos(selectedItem.radius));

                holoPlanet = new Planet(selectedItem.name, holoPos.x, holoPos.y, holoPos.z, new Vector3d(0,0,0), selectedItem.radius, selectedItem.mass, selectedItem.tilt, selectedItem.rotationPeriod, selectedItem.temperature, selectedItem.planetTex, selectedItem.uvMap, selectedItem.type, selectedItem.isRigid);
                if (holoPlanet.type.equals("Hole")) hasBH = true;
                startPos.set(holoPos); drawLine = true;
            } else if (currentSpawnMode == SpawnMode.STATIC) {
                holoPos.set(getHologramPos(selectedItem.radius));
                holoPlanet = new Planet(selectedItem.name, holoPos.x, holoPos.y, holoPos.z, new Vector3d(0,0,0), selectedItem.radius, selectedItem.mass, selectedItem.tilt, selectedItem.rotationPeriod, selectedItem.temperature, selectedItem.planetTex, selectedItem.uvMap, selectedItem.type, selectedItem.isRigid);
                if (holoPlanet.type.equals("Hole")) hasBH = true;
            } else if (currentSpawnMode == SpawnMode.LAUNCH) {
                org.joml.Vector3f rayF = CameraManager.getMouseRay();
                Vector3d forward = new Vector3d(rayF.x, rayF.y, rayF.z);
                startPos.set(CameraManager.pos).add(new Vector3d(forward).mul(selectedItem.radius * 2.5 + 5.0));
                drawLine = true;
            }
        }

        CameraManager.update(holoPlanet);

        double maxRenderDist = 1000000.0;
        if (CameraManager.isLocked && CameraManager.lockedTarget != null) {
            maxRenderDist = Math.max(maxRenderDist, CameraManager.lockedTarget.radius * 20.0);
        }
        maxRenderDist = Math.max(maxRenderDist, Math.abs(CameraManager.pos.y) * 10.0);

        float zFar = (float) (maxRenderDist * 10.0);
        float zNear = Math.max(0.1f, zFar / 10000000.0f);

        float aspect = (float) client.getWindow().getFramebufferWidth() / client.getWindow().getFramebufferHeight();
        Matrix4f projMatrix = new Matrix4f().setPerspective((float) Math.toRadians(70), aspect, zNear, zFar);

        Matrix4f rotViewMatrix = new Matrix4f().rotateX((float) Math.toRadians(CameraManager.rot.x)).rotateY((float) Math.toRadians(CameraManager.rot.y));

        if (!isMusicStarted || currentRealTime - lastMusicPlayTime >= 168.0) {
            lastMusicPlayTime = currentRealTime;
            isMusicStarted = true;
            net.minecraft.sound.SoundEvent musicEvent = net.minecraft.sound.SoundEvent.of(Identifier.of("spacesb-remake", "space_music"));
            client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(musicEvent, 1.0f, 1.0f));
        }

        RenderSystem.depthMask(false);
        Tessellator tessellator = Tessellator.getInstance();

        Matrix4f skyProj = new Matrix4f().setPerspective((float) Math.toRadians(70), aspect, 0.1f, 1000.0f);
        RenderSystem.setProjectionMatrix(skyProj, VertexSorter.BY_DISTANCE);

        float skyS = 100.0f;
        SpaceUI.drawSkyFace(tessellator, rotViewMatrix, SKY_FRONT, -skyS, skyS, -skyS, -skyS, -skyS, -skyS, skyS, -skyS, -skyS, skyS, skyS, -skyS);
        SpaceUI.drawSkyFace(tessellator, rotViewMatrix, SKY_BACK, skyS, skyS, skyS, skyS, -skyS, skyS, -skyS, -skyS, skyS, -skyS, skyS, skyS);
        SpaceUI.drawSkyFace(tessellator, rotViewMatrix, SKY_LEFT, -skyS, skyS, skyS, -skyS, -skyS, skyS, -skyS, -skyS, -skyS, -skyS, skyS, -skyS);
        SpaceUI.drawSkyFace(tessellator, rotViewMatrix, SKY_RIGHT, skyS, skyS, -skyS, skyS, -skyS, -skyS, skyS, -skyS, skyS, skyS, skyS, skyS);
        SpaceUI.drawSkyFace(tessellator, rotViewMatrix, SKY_TOP, -skyS, skyS, skyS, -skyS, skyS, -skyS, skyS, skyS, -skyS, skyS, skyS, skyS);
        SpaceUI.drawSkyFace(tessellator, rotViewMatrix, SKY_BOTTOM, -skyS, -skyS, -skyS, -skyS, -skyS, skyS, skyS, -skyS, skyS, skyS, -skyS, -skyS);

        RenderSystem.setProjectionMatrix(projMatrix, VertexSorter.BY_DISTANCE);
        RenderSystem.depthMask(true);

        if (holoPlanet != null) {
            for (Planet p : planets) {
                if (p.pos.distance(holoPlanet.pos) < p.radius + selectedItem.radius) { validSpawn = false; break; }
            }
        }

        MatrixStack matrices = context.getMatrices();
        matrices.push();
        matrices.peek().getPositionMatrix().identity();
        matrices.multiply(new Quaternionf().rotationX((float) Math.toRadians(CameraManager.rot.x)));
        matrices.multiply(new Quaternionf().rotationY((float) Math.toRadians(CameraManager.rot.y)));

        Matrix4f fullViewMatrix = matrices.peek().getPositionMatrix();

        double camX = CameraManager.pos.x;
        double camY = CameraManager.pos.y;
        double camZ = CameraManager.pos.z;

        float[] suns = new float[64];
        for (int i = 0; i < 64; i++) suns[i] = -2.0f;
        int sunCount = 0;

        for (Planet sp : planets) {
            if (sp.type.equals("Star") && sunCount < 16) {
                suns[sunCount * 4] = (float)(sp.pos.x - camX);
                suns[sunCount * 4 + 1] = (float)(sp.pos.y - camY);
                suns[sunCount * 4 + 2] = (float)(sp.pos.z - camZ);
                suns[sunCount * 4 + 3] = -1.0f;
                sunCount++;
            }
        }
        if (holoPlanet != null && holoPlanet.type.equals("Star") && sunCount < 16) {
            suns[sunCount * 4] = (float)(holoPlanet.pos.x - camX);
            suns[sunCount * 4 + 1] = (float)(holoPlanet.pos.y - camY);
            suns[sunCount * 4 + 2] = (float)(holoPlanet.pos.z - camZ);
            suns[sunCount * 4 + 3] = -1.0f;
            sunCount++;
        }

        for (Particle pt : particles) {
            if (sunCount < 16 && pt.life > 0) {
                float aRatio = (float) Math.max(0, pt.life / pt.maxLife);
                float power = aRatio * aRatio * 80.0f;
                if (power > 0.1f) {
                    suns[sunCount * 4] = (float)(pt.pos.x - camX);
                    suns[sunCount * 4 + 1] = (float)(pt.pos.y - camY);
                    suns[sunCount * 4 + 2] = (float)(pt.pos.z - camZ);
                    suns[sunCount * 4 + 3] = power;
                    sunCount++;
                }
            }
        }

        for (Planet pl : planets) {
            for (Scorch s : pl.scorches) {
                if (sunCount < 16 && s.life > 0) {
                    float aRatio = (float) Math.max(0, s.life / s.maxLife);
                    float power = aRatio * aRatio * 8.0f;
                    if (power > 0.1f) {

                        double rot = pl.currentAngle;

                        double fl = 0;
                        if(enableCentrifugalForce && !pl.type.equals("Hole") && pl.mass>0) {
                            double rP = Math.max(0.1, pl.rotationPeriod);
                            fl = (0.011 * (pl.radius * pl.radius * pl.radius) / (pl.mass * rP * rP));
                            if (fl > 0.15) fl = 0.15;
                        }

                        org.joml.Vector3f localNormF = new org.joml.Vector3f((float)s.localNorm.x, (float)s.localNorm.y, (float)s.localNorm.z);
                        Matrix4f tModel = new Matrix4f().rotateZ((float)Math.toRadians(pl.tilt)).rotateY((float)Math.toRadians(-rot)).scale(1.0f + (float)fl * 0.5f, 1.0f - (float)fl, 1.0f + (float)fl * 0.5f);
                        tModel.transformPosition(localNormF);

                        Vector3d worldP = new Vector3d(pl.pos).add(new Vector3d(localNormF.x, localNormF.y, localNormF.z).mul(pl.radius));
                        suns[sunCount * 4] = (float)(worldP.x - camX);
                        suns[sunCount * 4 + 1] = (float)(worldP.y - camY);
                        suns[sunCount * 4 + 2] = (float)(worldP.z - camZ);
                        suns[sunCount * 4 + 3] = power;
                        sunCount++;
                    }
                }
            }
        }

        double minBHDist = Double.MAX_VALUE;
        for (Planet p : planets) {
            if (p.type.equals("Hole")) {
                hasBH = true;
                double d = CameraManager.pos.distance(p.pos);
                if (d < minBHDist) minBHDist = d;
            }
        }
        if (holoPlanet != null && holoPlanet.type.equals("Hole")) {
            hasBH = true;
            double d = CameraManager.pos.distance(holoPlanet.pos);
            if (d < minBHDist) minBHDist = d;
        }

        List<Planet> bgPlanets = new ArrayList<>();
        List<Planet> fgPlanets = new ArrayList<>();
        for (Planet p : planets) {
            if (!p.type.equals("Hole")) {
                double d = CameraManager.pos.distance(p.pos);
                if (!hasBH || d >= minBHDist) bgPlanets.add(p);
                else fgPlanets.add(p);
            }
        }

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        for (Planet p : bgPlanets) {
            boolean flash = editFlashlight && CameraManager.isLocked && CameraManager.lockedTarget == p;
            Shader.renderPlanet(fullViewMatrix, projMatrix, p, suns, sunCount, CameraManager.pos, 1.0f, 1.0f, 1.0f, 1.0f, false, flash);
        }

        if (holoPlanet != null && !holoPlanet.type.equals("Hole") && currentSpawnMode != SpawnMode.LAUNCH) {
            double d = CameraManager.pos.distance(holoPlanet.pos);
            if (!hasBH || d >= minBHDist) {
                float r = 1.0f, g = validSpawn ? 1.0f : 0.2f, b = validSpawn ? 1.0f : 0.2f;
                Shader.renderPlanet(fullViewMatrix, projMatrix, holoPlanet, suns, sunCount, CameraManager.pos, r, g, b, 0.6f, false, false);
            }
        }
        RenderSystem.disableCull();

        Matrix4f particleMvp = new Matrix4f(projMatrix).mul(fullViewMatrix);
        Matrix4f invView = new Matrix4f(fullViewMatrix).invert();

        org.joml.Vector3f camRight = new org.joml.Vector3f(1, 0, 0); invView.transformDirection(camRight); camRight.normalize();
        org.joml.Vector3f camUp = new org.joml.Vector3f(0, 1, 0); invView.transformDirection(camUp); camUp.normalize();

        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(-3.0f, -3.0f);
        Shader.renderScorches(fullViewMatrix, projMatrix, planets);
        RenderSystem.disablePolygonOffset();

        boolean hasVisibleParticles = false;
        for (Particle pt : particles) {
            if (pt.life > 0) { hasVisibleParticles = true; break; }
        }

        if (hasVisibleParticles) {
            RenderSystem.setShaderTexture(0, TEX_EXPLOSION);
            RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(false);
            RenderSystem.enableDepthTest();

            BufferBuilder pBuffer = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
            boolean pDrawn = false;

            for (Particle pt : particles) {
                if (pt.life <= 0) continue;
                int a = (int)(255 * Math.max(0, pt.life / pt.maxLife));
                if (a < 5) continue;

                pDrawn = true;
                float s = (float) pt.size;
                float cx = (float)(pt.pos.x - camX);
                float cy = (float)(pt.pos.y - camY);
                float cz = (float)(pt.pos.z - camZ);

                float x1 = cx - camRight.x * s - camUp.x * s; float y1 = cy - camRight.y * s - camUp.y * s; float z1 = cz - camRight.z * s - camUp.z * s;
                float x2 = cx + camRight.x * s - camUp.x * s; float y2 = cy + camRight.y * s - camUp.y * s; float z2 = cz + camRight.z * s - camUp.z * s;
                float x3 = cx + camRight.x * s + camUp.x * s; float y3 = cy + camRight.y * s + camUp.y * s; float z3 = cz + camRight.z * s + camUp.z * s;
                float x4 = cx - camRight.x * s + camUp.x * s; float y4 = cy - camRight.y * s + camUp.y * s; float z4 = cz - camRight.z * s + camUp.z * s;

                pBuffer.vertex(fullViewMatrix, x1, y1, z1).texture(0f, 1f).color(pt.r, pt.g, pt.b, a);
                pBuffer.vertex(fullViewMatrix, x2, y2, z2).texture(1f, 1f).color(pt.r, pt.g, pt.b, a);
                pBuffer.vertex(fullViewMatrix, x3, y3, z3).texture(1f, 0f).color(pt.r, pt.g, pt.b, a);
                pBuffer.vertex(fullViewMatrix, x4, y4, z4).texture(0f, 0f).color(pt.r, pt.g, pt.b, a);
            }
            if (pDrawn) {
                BufferRenderer.drawWithGlobalProgram(pBuffer.end());
            }
        }

        boolean hasVisibleFragments = false;
        for (Fragment f : fragments) {
            if (f.life > 0) { hasVisibleFragments = true; break; }
        }

        if (hasVisibleFragments) {
            RenderSystem.setShaderTexture(0, TEX_FRAGMENT);
            RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.depthMask(true);

            BufferBuilder fBuffer = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
            boolean fDrawn = false;

            for (Fragment f : fragments) {
                if (f.life <= 0) continue;

                int a = 255;

                fDrawn = true;
                Matrix4f fMat = new Matrix4f(fullViewMatrix);
                fMat.translate((float)(f.pos.x - camX), (float)(f.pos.y - camY), (float)(f.pos.z - camZ));
                fMat.rotate((float)Math.toRadians(f.angle), f.rotAxis);
                float s = (float)f.size;

                fBuffer.vertex(fMat, -s, -s,  s).texture(f.u1, f.v2).color(255, 255, 255, a);
                fBuffer.vertex(fMat,  s, -s,  s).texture(f.u2, f.v2).color(255, 255, 255, a);
                fBuffer.vertex(fMat,  s,  s,  s).texture(f.u2, f.v1).color(255, 255, 255, a);
                fBuffer.vertex(fMat, -s,  s,  s).texture(f.u1, f.v1).color(255, 255, 255, a);
                fBuffer.vertex(fMat,  s, -s, -s).texture(f.u1, f.v2).color(255, 255, 255, a);
                fBuffer.vertex(fMat, -s, -s, -s).texture(f.u2, f.v2).color(255, 255, 255, a);
                fBuffer.vertex(fMat, -s,  s, -s).texture(f.u2, f.v1).color(255, 255, 255, a);
                fBuffer.vertex(fMat,  s,  s, -s).texture(f.u1, f.v1).color(255, 255, 255, a);
                fBuffer.vertex(fMat, -s,  s,  s).texture(f.u1, f.v2).color(255, 255, 255, a);
                fBuffer.vertex(fMat,  s,  s,  s).texture(f.u2, f.v2).color(255, 255, 255, a);
                fBuffer.vertex(fMat,  s,  s, -s).texture(f.u2, f.v1).color(255, 255, 255, a);
                fBuffer.vertex(fMat, -s,  s, -s).texture(f.u1, f.v1).color(255, 255, 255, a);
                fBuffer.vertex(fMat, -s, -s, -s).texture(f.u1, f.v2).color(255, 255, 255, a);
                fBuffer.vertex(fMat,  s, -s, -s).texture(f.u2, f.v2).color(255, 255, 255, a);
                fBuffer.vertex(fMat,  s, -s,  s).texture(f.u2, f.v1).color(255, 255, 255, a);
                fBuffer.vertex(fMat, -s, -s,  s).texture(f.u1, f.v1).color(255, 255, 255, a);
                fBuffer.vertex(fMat, -s, -s, -s).texture(f.u1, f.v2).color(255, 255, 255, a);
                fBuffer.vertex(fMat, -s, -s,  s).texture(f.u2, f.v2).color(255, 255, 255, a);
                fBuffer.vertex(fMat, -s,  s,  s).texture(f.u2, f.v1).color(255, 255, 255, a);
                fBuffer.vertex(fMat, -s,  s, -s).texture(f.u1, f.v1).color(255, 255, 255, a);
                fBuffer.vertex(fMat,  s, -s,  s).texture(f.u1, f.v2).color(255, 255, 255, a);
                fBuffer.vertex(fMat,  s, -s, -s).texture(f.u2, f.v2).color(255, 255, 255, a);
                fBuffer.vertex(fMat,  s,  s, -s).texture(f.u2, f.v1).color(255, 255, 255, a);
                fBuffer.vertex(fMat,  s,  s,  s).texture(f.u1, f.v1).color(255, 255, 255, a);
            }
            if (fDrawn) {
                BufferRenderer.drawWithGlobalProgram(fBuffer.end());
            }
        }
        RenderSystem.enableCull();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        BufferBuilder lineBuffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        lineBuffer.vertex(fullViewMatrix, 0, 0, 0).color(0, 0, 0, 0);
        lineBuffer.vertex(fullViewMatrix, 0, 0, 0).color(0, 0, 0, 0);

        if (showTrails) {
            for (Planet p : planets) {
                if (p.trail.isEmpty()) continue;

                Object[] snap = p.trail.toArray();
                if (snap.length < 2) continue;

                for (int i = 0; i < snap.length - 1; i++) {
                    Vector3d v1 = (Vector3d) snap[i];
                    Vector3d v2 = (Vector3d) snap[i+1];
                    int alpha = (int)(255.0f * (i / (float)snap.length));

                    lineBuffer.vertex(fullViewMatrix, (float)(v1.x - camX), (float)(v1.y - camY), (float)(v1.z - camZ)).color(p.rColor, p.gColor, p.bColor, alpha);
                    lineBuffer.vertex(fullViewMatrix, (float)(v2.x - camX), (float)(v2.y - camY), (float)(v2.z - camZ)).color(p.rColor, p.gColor, p.bColor, alpha);
                }

                Vector3d last = (Vector3d) snap[snap.length - 1];
                Vector3d dirToPlanet = new Vector3d(p.pos).sub(last);
                double distToCenter = dirToPlanet.length();

                if (distToCenter > p.radius) {
                    Vector3d surfacePos = new Vector3d(last).add(dirToPlanet.normalize().mul(distToCenter - p.radius));
                    lineBuffer.vertex(fullViewMatrix, (float)(last.x - camX), (float)(last.y - camY), (float)(last.z - camZ)).color(p.rColor, p.gColor, p.bColor, 255);
                    lineBuffer.vertex(fullViewMatrix, (float)(surfacePos.x - camX), (float)(surfacePos.y - camY), (float)(surfacePos.z - camZ)).color(p.rColor, p.gColor, p.bColor, 255);
                }
            }
        }

        lineStart3D = null; lineEnd3D = null; textMidPoint3D = null;

        if (drawLine) {
            calculateSpawnVelocity(startPos, holoUp);

            if (currentSpawnMode == SpawnMode.ORBIT && orbitTarget != null && planets.contains(orbitTarget)) {
                Planet attractor = orbitTarget;
                lineBuffer.vertex(fullViewMatrix, (float)(startPos.x - camX), (float)(startPos.y - camY), (float)(startPos.z - camZ)).color(255, 255, 255, 255);
                lineBuffer.vertex(fullViewMatrix, (float)(attractor.pos.x - camX), (float)(attractor.pos.y - camY), (float)(attractor.pos.z - camZ)).color(255, 255, 255, 255);
                lineStart3D = new Vector3d(startPos); lineEnd3D = new Vector3d(attractor.pos); textMidPoint3D = new Vector3d(startPos).add(attractor.pos).mul(0.5);

                Vector3d R = new Vector3d(startPos).sub(attractor.pos); Vector3d T = new Vector3d(holoUp).cross(R); double orbitRadius = R.length();
                if (orbitRadius > 0.001) {
                    R.normalize(); T.normalize(); int segments = 100;
                    for (int i = 0; i < segments; i++) {
                        double a1 = 2.0 * Math.PI * i / segments, a2 = 2.0 * Math.PI * (i + 1) / segments;
                        double x1 = attractor.pos.x + Math.cos(a1)*orbitRadius*R.x + Math.sin(a1)*orbitRadius*T.x;
                        double y1 = attractor.pos.y + Math.cos(a1)*orbitRadius*R.y + Math.sin(a1)*orbitRadius*T.y;
                        double z1 = attractor.pos.z + Math.cos(a1)*orbitRadius*R.z + Math.sin(a1)*orbitRadius*T.z;
                        double x2 = attractor.pos.x + Math.cos(a2)*orbitRadius*R.x + Math.sin(a2)*orbitRadius*T.x;
                        double y2 = attractor.pos.y + Math.cos(a2)*orbitRadius*R.y + Math.sin(a2)*orbitRadius*T.y;
                        double z2 = attractor.pos.z + Math.cos(a2)*orbitRadius*R.z + Math.sin(a2)*orbitRadius*T.z;
                        lineBuffer.vertex(fullViewMatrix, (float)(x1 - camX), (float)(y1 - camY), (float)(z1 - camZ)).color(255, 255, 255, 200);
                        lineBuffer.vertex(fullViewMatrix, (float)(x2 - camX), (float)(y2 - camY), (float)(z2 - camZ)).color(255, 255, 255, 200);
                    }
                }
            } else if (currentSpawnMode == SpawnMode.LAUNCH) {
                org.joml.Vector3f rayF = CameraManager.getMouseRay();
                Vector3d forward = new Vector3d(rayF.x, rayF.y, rayF.z);
                lineStart3D = new Vector3d(startPos); lineEnd3D = new Vector3d(startPos).add(new Vector3d(forward).mul(50.0));

                Vector3d predPos = new Vector3d(startPos), prevPos = new Vector3d(predPos);
                double speedInKmS = getSpeedInKmS(); double simDt = 0.5; double distBlocks = speedInKmS / BLOCK_TO_KM;
                for(int i=0; i<100; i++) {
                    predPos.add(new Vector3d(forward).mul(distBlocks * simDt));
                    lineBuffer.vertex(fullViewMatrix, (float)(prevPos.x - camX), (float)(prevPos.y - camY), (float)(prevPos.z - camZ)).color(255, 255, 255, 255);
                    lineBuffer.vertex(fullViewMatrix, (float)(predPos.x - camX), (float)(prevPos.y - camY), (float)(prevPos.z - camZ)).color(255, 255, 255, 255);
                    prevPos.set(predPos);
                }
            }
        }

        if (showGrid) {
            double gridBase = Math.max(10.0, Math.abs(CameraManager.pos.y));
            if (CameraManager.isLocked && CameraManager.lockedTarget != null) {
                gridBase = Math.max(gridBase, CameraManager.lockedTarget.radius * 2.0);
            }
            double logY = Math.log10(gridBase);
            double floorLog = Math.floor(logY);
            double minorStep = Math.pow(10, floorLog);
            double minorAlphaMod = Math.pow(1.0 - (logY - floorLog), 1.5);

            double renderDist = minorStep * 200.0;
            float fRenderDist = (float)renderDist;

            double snapX = Math.floor(camX / minorStep) * minorStep;
            double snapZ = Math.floor(camZ / minorStep) * minorStep;
            float relY = (float)(-camY);

            for (double i = -renderDist; i <= renderDist; i += minorStep) {
                double worldX = snapX + i;
                float relX = (float)(worldX - camX);

                float distToLine = Math.abs(relX);
                float fadeCenter = (float)Math.pow(1.0 - Math.min(1.0, distToLine / fRenderDist), 3.0);
                if (fadeCenter <= 0) continue;

                long indexX = Math.round(worldX / minorStep);
                boolean isMajor = (indexX % 10 == 0);
                float baseAlpha = isMajor ? 180f : 120f * (float)minorAlphaMod;
                int color = isMajor ? 255 : 180;
                int alphaCenter = (int)(baseAlpha * fadeCenter);

                if (alphaCenter > 2) {
                    float dist1 = (float)Math.sqrt(relX*relX + fRenderDist*fRenderDist);
                    float a1 = baseAlpha * (float)Math.pow(1.0 - Math.min(1.0f, dist1 / fRenderDist), 3.0);

                    lineBuffer.vertex(fullViewMatrix, relX, relY, -fRenderDist).color(color, color, color, (int)Math.max(0, a1));
                    lineBuffer.vertex(fullViewMatrix, relX, relY, 0.0f).color(color, color, color, alphaCenter);

                    lineBuffer.vertex(fullViewMatrix, relX, relY, 0.0f).color(color, color, color, alphaCenter);
                    lineBuffer.vertex(fullViewMatrix, relX, relY, fRenderDist).color(color, color, color, (int)Math.max(0, a1));
                }
            }

            for (double i = -renderDist; i <= renderDist; i += minorStep) {
                double worldZ = snapZ + i;
                float relZ = (float)(worldZ - camZ);

                float distToLine = Math.abs(relZ);
                float fadeCenter = (float)Math.pow(1.0 - Math.min(1.0, distToLine / fRenderDist), 3.0);
                if (fadeCenter <= 0) continue;

                long indexZ = Math.round(worldZ / minorStep);
                boolean isMajor = (indexZ % 10 == 0);
                float baseAlpha = isMajor ? 180f : 120f * (float)minorAlphaMod;
                int color = isMajor ? 255 : 180;
                int alphaCenter = (int)(baseAlpha * fadeCenter);

                if (alphaCenter > 2) {
                    float dist1 = (float)Math.sqrt(fRenderDist*fRenderDist + relZ*relZ);
                    float a1 = baseAlpha * (float)Math.pow(1.0 - Math.min(1.0f, dist1 / fRenderDist), 3.0);

                    lineBuffer.vertex(fullViewMatrix, -fRenderDist, relY, relZ).color(color, color, color, (int)Math.max(0, a1));
                    lineBuffer.vertex(fullViewMatrix, 0.0f, relY, relZ).color(color, color, color, alphaCenter);

                    lineBuffer.vertex(fullViewMatrix, 0.0f, relY, relZ).color(color, color, color, alphaCenter);
                    lineBuffer.vertex(fullViewMatrix, fRenderDist, relY, relZ).color(color, color, color, (int)Math.max(0, a1));
                }
            }
        }

        BufferRenderer.drawWithGlobalProgram(lineBuffer.end());
        RenderSystem.depthMask(true);

        if (hasBH) {
            Shader.renderAllLensing(planets, holoPlanet, rotViewMatrix, projMatrix);
        }

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        for (Planet p : planets) {
            if (p.type.equals("Hole")) Shader.renderPlanet(fullViewMatrix, projMatrix, p, suns, sunCount, CameraManager.pos, 1.0f, 1.0f, 1.0f, 1.0f, true, false);
        }
        if (holoPlanet != null && holoPlanet.type.equals("Hole") && currentSpawnMode != SpawnMode.LAUNCH) {
            Shader.renderPlanet(fullViewMatrix, projMatrix, holoPlanet, suns, sunCount, CameraManager.pos, 1.0f, 1.0f, 1.0f, 1.0f, true, false);
        }
        RenderSystem.disableCull();

        if (hasBH) {
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();

            for (Planet p : fgPlanets) {
                boolean flash = editFlashlight && CameraManager.isLocked && CameraManager.lockedTarget == p;
                Shader.renderPlanet(fullViewMatrix, projMatrix, p, suns, sunCount, CameraManager.pos, 1.0f, 1.0f, 1.0f, 1.0f, false, flash);
            }

            if (holoPlanet != null && !holoPlanet.type.equals("Hole") && currentSpawnMode != SpawnMode.LAUNCH) {
                double d = CameraManager.pos.distance(holoPlanet.pos);
                if (d < minBHDist) {
                    float r = 1.0f, g = validSpawn ? 1.0f : 0.2f, b = validSpawn ? 1.0f : 0.2f;
                    Shader.renderPlanet(fullViewMatrix, projMatrix, holoPlanet, suns, sunCount, CameraManager.pos, r, g, b, 0.6f, false, false);
                }
            }
            RenderSystem.disableCull();
        }

        for (Planet p : planets) {
            if (p.type.equals("Star")) {
                boolean showRays = true;
                if (CameraManager.isLocked && CameraManager.lockedTarget == p && !editRays) showRays = false;
                if (showRays) SpaceUI.drawSunGlow(p, rotViewMatrix, projMatrix);
            }
        }
        if (holoPlanet != null && holoPlanet.type.equals("Star") && currentSpawnMode != SpawnMode.LAUNCH) {
            SpaceUI.drawSunGlow(holoPlanet, rotViewMatrix, projMatrix);
        }

        matrices.pop();
        RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, false);
    }
}