package spacesb.remake;

import org.joml.Vector3d;
import spacesb.remake.render.SBoxCompute;
import spacesb.remake.render.SBoxVulkanUI;

import javax.imageio.ImageIO;
import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

public class SimulationManager {

    public static final File SIMULATIONS_DIR = new File("spacesb_simulations");
    public static final String SOLAR_SYSTEM_COVER_TEX = "assets/spacesb-remake/textures/gui/solarsystem.png";
    public static final String SOLAR_SYSTEM_COVER_ALT = "assets/spacesb-remake/textures/solarsystem.png";

    public static class SavedSimEntry {
        public String id;
        public String name;
        public File propFile;
        public File coverFile;
        public long lastModified;
        public long coverDescriptor = -1L;
        public boolean coverLoaded = false;
    }

    public static final List<SavedSimEntry> savedSimulations = new ArrayList<>();
    private static final Map<String, Long> coverCache = new HashMap<>();

    public static synchronized void refreshSavedSimulations() {
        if (!SIMULATIONS_DIR.exists()) SIMULATIONS_DIR.mkdirs();
        savedSimulations.clear();
        File[] files = SIMULATIONS_DIR.listFiles((dir, name) -> name.toLowerCase().endsWith(".sbox"));
        if (files == null) return;

        for (File f : files) {
            try (FileInputStream fis = new FileInputStream(f);
                 InputStreamReader reader = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
                Properties props = new Properties();
                props.load(reader);
                SavedSimEntry entry = new SavedSimEntry();
                String baseName = f.getName().substring(0, f.getName().length() - 5);
                entry.id = baseName;
                entry.name = props.getProperty("sim.name", baseName);
                entry.propFile = f;
                entry.coverFile = new File(SIMULATIONS_DIR, baseName + ".png");
                entry.lastModified = f.lastModified();
                Long cachedDesc = coverCache.get(entry.id);
                if (cachedDesc != null) {
                    entry.coverDescriptor = cachedDesc;
                    entry.coverLoaded = true;
                }
                savedSimulations.add(entry);
            } catch (Exception ignored) {}
        }
        savedSimulations.sort(Comparator.comparingLong((SavedSimEntry e) -> e.lastModified).reversed());
    }

    public static long getOrLoadSavedCoverDescriptor(SavedSimEntry entry) {
        if (entry == null) return -1L;
        if (entry.coverLoaded) return entry.coverDescriptor;
        entry.coverLoaded = true;
        if (entry.coverFile == null || !entry.coverFile.exists()) return -1L;

        try {
            BufferedImage raw = ImageIO.read(entry.coverFile);
            if (raw == null) return -1L;
            int tw = 256, th = 144;
            BufferedImage scaled = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = scaled.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(raw, 0, 0, tw, th, null);
            g2d.dispose();

            int[] pixels = scaled.getRGB(0, 0, tw, th, null, 0, tw);
            ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(tw * th * 4);
            for (int p : pixels) {
                buf.put((byte) ((p >> 16) & 0xFF))
                        .put((byte) ((p >> 8) & 0xFF))
                        .put((byte) (p & 0xFF))
                        .put((byte) ((p >> 24) & 0xFF));
            }
            buf.flip();
            long desc = SBoxVulkanUI.uploadTexture(buf, tw, th, VK_FORMAT_R8G8B8A8_UNORM);
            entry.coverDescriptor = desc;
            coverCache.put(entry.id, desc);
            return desc;
        } catch (Exception e) {
            return -1L;
        }
    }

    private static SpaceEngine.MenuItem findMenuItem(String name) {
        if (SpaceEngine.menuItems == null) return null;
        for (SpaceEngine.MenuItem item : SpaceEngine.menuItems) {
            if (item != null && item.name.equalsIgnoreCase(name)) return item;
        }
        return null;
    }

    /**
     * Загружает готовую симуляцию «Солнечная система» с реальными наклонами орбит (как в Universe Sandbox),
     * осевыми наклонами, периодами вращения и спутниками (Луна у Земли, Энцелад и Титан у Сатурна).
     */
    public static void loadSolarSystemPreset() {
        synchronized (SpaceEngine.STATE_LOCK) {
            SpaceEngine.planets.clear();
            SpaceEngine.fragments.clear();
            SpaceEngine.particles.clear();
            SpaceEngine.simTimeSeconds = 0.0;
            CameraManager.isLocked = false;
            CameraManager.lockedTarget = null;
            SpaceEngine.isPlacingPlanet = false;
            SpaceEngine.isDraggingOrbit = false;
            SpaceEngine.orbitTarget = null;

            SpaceEngine.MenuItem sunItem = findMenuItem("Sun");
            if (sunItem == null) return;

            SpaceEngine.Planet sun = new SpaceEngine.Planet(
                    sunItem.name, 0.0, 0.0, 0.0, new Vector3d(0, 0, 0),
                    sunItem.radius, sunItem.mass, sunItem.tilt, sunItem.rotationPeriod,
                    sunItem.temperature, sunItem.planetTex, sunItem.uvMap, sunItem.type, sunItem.isRigid
            );
            SpaceEngine.planets.add(sun);

            // Реальные астрономические элементы орбит (Universe Sandbox / J2000):
            // {Имя, Большая полуось в млн км, Наклон орбиты к эклиптике (град), Долгота восходящего узла (град), Истинная аномалия (град)}
            Object[][] planetOrbitalData = {
                    {"Mercury",  57.91,  7.005,  48.331,  252.25},
                    {"Venus",   108.21,  3.395,  76.680,  181.98},
                    {"Earth",   149.60,  0.000,   0.000,  100.46},
                    {"Mars",    227.92,  1.850,  49.558,  355.45},
                    {"Jupiter", 778.57,  1.304, 100.464,   34.40},
                    {"Saturn", 1433.53,  2.485, 113.665,   49.94},
                    {"Uranus", 2872.46,  0.773,  74.006,  313.23},
                    {"Neptune",4495.06,  1.770, 131.784,  304.88}
            };

            SpaceEngine.Planet earthRef = null;
            SpaceEngine.Planet saturnRef = null;

            for (Object[] row : planetOrbitalData) {
                String pName = (String) row[0];
                double distMillionKm = (Double) row[1];
                double incDeg = (Double) row[2];
                double nodeDeg = (Double) row[3];
                double anomDeg = (Double) row[4];

                SpaceEngine.MenuItem item = findMenuItem(pName);
                if (item == null) continue;

                double distBlocks = Math.max(sun.radius * 1.65 + item.radius * 2.0, (distMillionKm * 1_000_000.0) / SpaceEngine.BLOCK_TO_KM);
                SpaceEngine.Planet planet = createOrbitingBody(sun, item, distBlocks, incDeg, nodeDeg, anomDeg);
                SpaceEngine.planets.add(planet);

                if ("Earth".equalsIgnoreCase(pName)) earthRef = planet;
                else if ("Saturn".equalsIgnoreCase(pName)) saturnRef = planet;
            }

            // Спутник Земли: Луна (наклон орбиты 5.145° к эклиптике, расстояние 384 400 км)
            SpaceEngine.MenuItem moonItem = findMenuItem("Moon");
            if (earthRef != null && moonItem != null) {
                double moonDistBlocks = Math.max(earthRef.radius * 3.2, 384400.0 / SpaceEngine.BLOCK_TO_KM);
                SpaceEngine.Planet moon = createOrbitingBody(earthRef, moonItem, moonDistBlocks, 5.145, 125.08, 135.0);
                SpaceEngine.planets.add(moon);
            }

            // Спутники Сатурна: Энцелад и Титан (в плоскости экватора/колец Сатурна 26.73°)
            if (saturnRef != null) {
                SpaceEngine.MenuItem encItem = findMenuItem("Enceladus");
                if (encItem != null) {
                    double encDistBlocks = Math.max(saturnRef.radius * 3.45, 238040.0 / SpaceEngine.BLOCK_TO_KM);
                    SpaceEngine.Planet enc = createOrbitingBody(saturnRef, encItem, encDistBlocks, saturnRef.tilt, 0.0, 40.0);
                    SpaceEngine.planets.add(enc);
                }
                SpaceEngine.MenuItem titanItem = findMenuItem("Titan");
                if (titanItem != null) {
                    double titanDistBlocks = Math.max(saturnRef.radius * 5.2, 1221870.0 / SpaceEngine.BLOCK_TO_KM);
                    SpaceEngine.Planet titan = createOrbitingBody(saturnRef, titanItem, titanDistBlocks, saturnRef.tilt, 0.0, 210.0);
                    SpaceEngine.planets.add(titan);
                }
            }

            PhysicsEngine.ensureSaturnRings(SpaceEngine.planets);
            SpaceEngine.planetsNeedSort = true;

            // Ставим камеру с красивым обзором на Солнечную систему
            CameraManager.pos.set(0.0, 68000.0, 145000.0);
            CameraManager.rot.set(26.0f, 0.0f, 0.0f);
            SpaceEngine.playerFlightSpeed = 25000.0;
            SpaceUI.addNotification("Loaded: Solar System");
        }
    }

    private static SpaceEngine.Planet createOrbitingBody(
            SpaceEngine.Planet parent, SpaceEngine.MenuItem item,
            double orbitRadiusBlocks, double inclinationDeg, double ascNodeDeg, double anomalyDeg) {

        double incRad = Math.toRadians(inclinationDeg);
        double nodeRad = Math.toRadians(ascNodeDeg);
        double anomRad = Math.toRadians(anomalyDeg);

        Vector3d radial = new Vector3d(Math.cos(anomRad) * orbitRadiusBlocks, 0.0, Math.sin(anomRad) * orbitRadiusBlocks);
        Vector3d tangent = new Vector3d(-Math.sin(anomRad), 0.0, Math.cos(anomRad));

        // Поворачиваем орбиту на наклон (inclination) и долготу восходящего узла (ascending node)
        radial.rotateZ(incRad).rotateY(nodeRad);
        tangent.rotateZ(incRad).rotateY(nodeRad).normalize();

        Vector3d worldPos = new Vector3d(parent.pos).add(radial);

        double totalMass = parent.mass + item.mass;
        double schPotential = Math.min(2.5, 7.039726890892425e-9 * (totalMass / Math.max(orbitRadiusBlocks, 0.1)));
        double vOrbital = Math.sqrt((PhysicsEngine.G * totalMass * (1.0 + schPotential)) / Math.max(orbitRadiusBlocks, 0.1));

        Vector3d worldVel = new Vector3d(parent.vel).fma(vOrbital, tangent);

        return new SpaceEngine.Planet(
                item.name, worldPos.x, worldPos.y, worldPos.z, worldVel,
                item.radius, item.mass, item.tilt, item.rotationPeriod,
                item.temperature, item.planetTex, item.uvMap, item.type, item.isRigid
        );
    }

    public static SavedSimEntry saveCurrentSimulation(String customName, SavedSimEntry overwriteTarget) {
        synchronized (SpaceEngine.STATE_LOCK) {
            if (!SIMULATIONS_DIR.exists()) SIMULATIONS_DIR.mkdirs();

            String id = (overwriteTarget != null && overwriteTarget.id != null)
                    ? overwriteTarget.id
                    : ("sim_" + System.currentTimeMillis());
            String displayName = (customName != null && !customName.trim().isEmpty())
                    ? customName.trim()
                    : ((overwriteTarget != null) ? overwriteTarget.name : ("Simulation " + (savedSimulations.size() + 1)));

            File propFile = new File(SIMULATIONS_DIR, id + ".sbox");
            File coverFile = new File(SIMULATIONS_DIR, id + ".png");

            Properties props = new Properties();
            props.setProperty("sim.name", displayName);
            props.setProperty("sim.timeSeconds", String.valueOf(SpaceEngine.simTimeSeconds));
            props.setProperty("sim.timeScale", String.valueOf(SpaceEngine.timeScale));
            props.setProperty("cam.x", String.valueOf(CameraManager.pos.x));
            props.setProperty("cam.y", String.valueOf(CameraManager.pos.y));
            props.setProperty("cam.z", String.valueOf(CameraManager.pos.z));
            props.setProperty("cam.pitch", String.valueOf(CameraManager.rot.x));
            props.setProperty("cam.yaw", String.valueOf(CameraManager.rot.y));
            props.setProperty("cam.flightSpeed", String.valueOf(SpaceEngine.playerFlightSpeed));

            List<SpaceEngine.Planet> list = SpaceEngine.planets;
            int savedCount = 0;
            for (int i = 0; i < list.size(); i++) {
                SpaceEngine.Planet p = list.get(i);
                if (p == null || p.mass <= 0.0) continue;
                String prefix = "planet." + savedCount + ".";
                props.setProperty(prefix + "name", p.name);
                props.setProperty(prefix + "type", p.type);
                props.setProperty(prefix + "tex", p.texture != null ? p.texture : "");
                props.setProperty(prefix + "x", String.valueOf(p.pos.x));
                props.setProperty(prefix + "y", String.valueOf(p.pos.y));
                props.setProperty(prefix + "z", String.valueOf(p.pos.z));
                props.setProperty(prefix + "vx", String.valueOf(p.vel.x));
                props.setProperty(prefix + "vy", String.valueOf(p.vel.y));
                props.setProperty(prefix + "vz", String.valueOf(p.vel.z));
                props.setProperty(prefix + "radius", String.valueOf(p.radius));
                props.setProperty(prefix + "mass", String.valueOf(p.mass));
                props.setProperty(prefix + "tilt", String.valueOf(p.tilt));
                props.setProperty(prefix + "rot", String.valueOf(p.rotationPeriod));
                props.setProperty(prefix + "temp", String.valueOf(p.temperature));
                props.setProperty(prefix + "rigid", String.valueOf(p.isRigid));
                savedCount++;
            }
            props.setProperty("planet.count", String.valueOf(savedCount));

            try (FileOutputStream fos = new FileOutputStream(propFile);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                props.store(writer, "SpaceSB Saved Simulation");
            } catch (Exception ignored) {}

            // Снимаем скриншот текущей 3D-сцены для обложки симуляции
            BufferedImage thumb = SBoxCompute.captureThumbnailImage(SpaceEngine.activeRenderW, SpaceEngine.activeRenderH);
            if (thumb != null) {
                try {
                    ImageIO.write(thumb, "png", coverFile);
                } catch (Exception ignored) {}
            }
            coverCache.remove(id);
            refreshSavedSimulations();

            for (SavedSimEntry e : savedSimulations) {
                if (e.id.equals(id)) return e;
            }
            return savedSimulations.isEmpty() ? null : savedSimulations.get(0);
        }
    }

    public static void renameSimulation(SavedSimEntry entry, String newName) {
        if (entry == null || entry.propFile == null || !entry.propFile.exists()) return;
        String clean = (newName != null) ? newName.trim() : "";
        if (clean.isEmpty()) return;
        entry.name = clean;
        try {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(entry.propFile);
                 InputStreamReader reader = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            props.setProperty("sim.name", clean);
            try (FileOutputStream fos = new FileOutputStream(entry.propFile);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                props.store(writer, "SpaceSB Saved Simulation");
            }
        } catch (Exception ignored) {}
    }

    public static void loadSavedSimulation(SavedSimEntry entry) {
        if (entry == null || entry.propFile == null || !entry.propFile.exists()) return;
        synchronized (SpaceEngine.STATE_LOCK) {
            try (FileInputStream fis = new FileInputStream(entry.propFile);
                 InputStreamReader reader = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
                Properties props = new Properties();
                props.load(reader);

                SpaceEngine.planets.clear();
                SpaceEngine.fragments.clear();
                SpaceEngine.particles.clear();
                CameraManager.isLocked = false;
                CameraManager.lockedTarget = null;
                SpaceEngine.isPlacingPlanet = false;
                SpaceEngine.isDraggingOrbit = false;
                SpaceEngine.orbitTarget = null;

                SpaceEngine.simTimeSeconds = Double.parseDouble(props.getProperty("sim.timeSeconds", "0.0"));
                SpaceEngine.timeScale = Double.parseDouble(props.getProperty("sim.timeScale", "1.0"));
                CameraManager.pos.set(
                        Double.parseDouble(props.getProperty("cam.x", "0.0")),
                        Double.parseDouble(props.getProperty("cam.y", "150.0")),
                        Double.parseDouble(props.getProperty("cam.z", "0.0"))
                );
                CameraManager.rot.set(
                        Float.parseFloat(props.getProperty("cam.pitch", "45.0")),
                        Float.parseFloat(props.getProperty("cam.yaw", "0.0")),
                        0.0f
                );
                SpaceEngine.playerFlightSpeed = Double.parseDouble(props.getProperty("cam.flightSpeed", "100.0"));

                int count = Integer.parseInt(props.getProperty("planet.count", "0"));
                for (int i = 0; i < count; i++) {
                    String prefix = "planet." + i + ".";
                    String name = props.getProperty(prefix + "name", "Earth");
                    String type = props.getProperty(prefix + "type", "Planet");
                    SpaceEngine.MenuItem template = findMenuItem(name);
                    String tex = props.getProperty(prefix + "tex", template != null ? template.planetTex : "assets/spacesb-remake/textures/planet/earth.png");
                    float[][] uv = (template != null) ? template.uvMap : null;

                    double x = Double.parseDouble(props.getProperty(prefix + "x", "0.0"));
                    double y = Double.parseDouble(props.getProperty(prefix + "y", "0.0"));
                    double z = Double.parseDouble(props.getProperty(prefix + "z", "0.0"));
                    double vx = Double.parseDouble(props.getProperty(prefix + "vx", "0.0"));
                    double vy = Double.parseDouble(props.getProperty(prefix + "vy", "0.0"));
                    double vz = Double.parseDouble(props.getProperty(prefix + "vz", "0.0"));
                    double radius = Double.parseDouble(props.getProperty(prefix + "radius", "5.0"));
                    double mass = Double.parseDouble(props.getProperty(prefix + "mass", "1.0"));
                    double tilt = Double.parseDouble(props.getProperty(prefix + "tilt", "0.0"));
                    double rot = Double.parseDouble(props.getProperty(prefix + "rot", "24.0"));
                    double temp = Double.parseDouble(props.getProperty(prefix + "temp", "15.0"));
                    boolean rigid = Boolean.parseBoolean(props.getProperty(prefix + "rigid", "true"));

                    SpaceEngine.planets.add(new SpaceEngine.Planet(
                            name, x, y, z, new Vector3d(vx, vy, vz),
                            radius, mass, tilt, rot, temp, tex, uv, type, rigid
                    ));
                }
                PhysicsEngine.ensureSaturnRings(SpaceEngine.planets);
                SpaceEngine.planetsNeedSort = true;
                SpaceUI.addNotification("Loaded: " + entry.name);
            } catch (Exception ignored) {}
        }
    }

    public static void deleteSavedSimulation(SavedSimEntry entry) {
        if (entry == null) return;
        if (entry.propFile != null && entry.propFile.exists()) entry.propFile.delete();
        if (entry.coverFile != null && entry.coverFile.exists()) entry.coverFile.delete();
        coverCache.remove(entry.id);
        refreshSavedSimulations();
    }

    public static void openSimulationsFolder() {
        if (!SIMULATIONS_DIR.exists()) SIMULATIONS_DIR.mkdirs();
        new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(SIMULATIONS_DIR);
                    return;
                }
            } catch (Throwable ignored) {}
            try {
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                String absPath = SIMULATIONS_DIR.getAbsolutePath();
                if (os.contains("win")) {
                    new ProcessBuilder("explorer", absPath).start();
                } else if (os.contains("mac")) {
                    new ProcessBuilder("open", absPath).start();
                } else {
                    new ProcessBuilder("xdg-open", absPath).start();
                }
            } catch (Exception ignored) {}
        }, "SBox-Open-SimFolder").start();
    }
}