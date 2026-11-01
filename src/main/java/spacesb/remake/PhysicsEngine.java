package spacesb.remake;

import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;
import spacesb.remake.render.SBoxVulkanCore;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.lwjgl.vulkan.VK10.*;

public class PhysicsEngine {

    public static final double G = 0.00019926275949971405;
    public static final double MAX_C_BLOCKS = 237.93052222222222;
    public static final double MAX_C_SQ = MAX_C_BLOCKS * MAX_C_BLOCKS;
    public static final double SECTOR_SIZE = 2.0;

    private static boolean gpuPhysicsReady = false;
    private static long descriptorSetLayout = VK_NULL_HANDLE, descriptorPool = VK_NULL_HANDLE, descriptorSet = VK_NULL_HANDLE;
    private static long pipelineLayout = VK_NULL_HANDLE, computePipeline = VK_NULL_HANDLE;

    private static long physicsPlanetBuffer = VK_NULL_HANDLE, physicsPlanetMemory = VK_NULL_HANDLE;
    private static ByteBuffer mappedPlanetBuffer;

    private static long physicsDebrisBuffer = VK_NULL_HANDLE, physicsDebrisMemory = VK_NULL_HANDLE;
    private static ByteBuffer mappedDebrisBuffer;

    private static VkCommandBuffer physicsCmdBuffer;
    private static long physicsFence = VK_NULL_HANDLE;

    private static final int MAX_GPU_PLANETS = 64, GPU_PLANET_STRIDE = 128, GPU_PLANET_BUFFER_SIZE = MAX_GPU_PLANETS * GPU_PLANET_STRIDE;
    public static final int MAX_GPU_DEBRIS = 65536;
    private static final int DEBRIS_POOL_MASK = MAX_GPU_DEBRIS - 1, GPU_DEBRIS_STRIDE = 64, GPU_DEBRIS_BUFFER_SIZE = MAX_GPU_DEBRIS * GPU_DEBRIS_STRIDE;

    private static final SpaceEngine.Fragment[] FRAGMENT_POOL = new SpaceEngine.Fragment[MAX_GPU_DEBRIS];
    private static final SpaceEngine.Particle[] PARTICLE_POOL = new SpaceEngine.Particle[MAX_GPU_DEBRIS];
    private static int fragPoolCursor = 0, partPoolCursor = 0;

    private static final int HASH_BUCKETS = 4096, HASH_MASK = HASH_BUCKETS - 1;
    private static final int[] HASH_HEAD = new int[HASH_BUCKETS];
    private static final int[] HASH_NEXT = new int[MAX_GPU_DEBRIS];
    private static final double[] MASS_WEIGHTS = new double[32];

    private static final double[] PREV_POS_X = new double[MAX_GPU_PLANETS], PREV_POS_Y = new double[MAX_GPU_PLANETS], PREV_POS_Z = new double[MAX_GPU_PLANETS];
    private static final double[] PREV_VEL_X = new double[MAX_GPU_PLANETS], PREV_VEL_Y = new double[MAX_GPU_PLANETS], PREV_VEL_Z = new double[MAX_GPU_PLANETS];

    private static final Vector3d SCRATCH_PULL = new Vector3d(), SCRATCH_POS = new Vector3d(), SCRATCH_VEL = new Vector3d();
    private static final Vector3d SCRATCH_SPIN = new Vector3d(), SCRATCH_OFFSET = new Vector3d(), SCRATCH_TANGENT = new Vector3d();
    private static final Vector3d SCRATCH_BASIS1 = new Vector3d(), SCRATCH_BASIS2 = new Vector3d();
    private static final Vector3d SCRATCH_HERMITE = new Vector3d(), SCRATCH_LOCAL_IMPACT = new Vector3d();

    public static final int MAX_CRATERS_PER_PLANET = 16;

    public static class CraterState {
        public final float[] dirX = new float[MAX_CRATERS_PER_PLANET];
        public final float[] dirY = new float[MAX_CRATERS_PER_PLANET];
        public final float[] dirZ = new float[]{1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1};
        public final float[] radius = new float[MAX_CRATERS_PER_PLANET];
        public final float[] tempC = new float[MAX_CRATERS_PER_PLANET];
        public final float[] depth = new float[MAX_CRATERS_PER_PLANET];
        public final float[] shockAge = new float[MAX_CRATERS_PER_PLANET];
        public float gravWaveAmp = 0.0f;
    }

    private static final Map<SpaceEngine.Planet, CraterState> CRATER_MAP = new IdentityHashMap<>();
    private static final Set<SpaceEngine.Planet> SATURN_RINGS_SPAWNED = Collections.newSetFromMap(new IdentityHashMap<>());

    public static CraterState getOrCreateCrater(SpaceEngine.Planet p) {
        return CRATER_MAP.computeIfAbsent(p, k -> new CraterState());
    }

    public static void fillCraterRenderData(SpaceEngine.Planet p, float[] out100) {
        for (int i = 0; i < 100; i++) out100[i] = 0.0f;
        CraterState cs = (p != null) ? CRATER_MAP.get(p) : null;
        if (cs == null) return;
        int activeCount = 0;
        for (int c = 0; c < MAX_CRATERS_PER_PLANET; c++) {
            if (cs.radius[c] > 0.005f && cs.depth[c] > 0.005f) {
                int base = activeCount * 4;
                float d = Math.max(0.05f, Math.min(1.0f, cs.depth[c]));
                out100[base]     = cs.dirX[c] * d;
                out100[base + 1] = cs.dirY[c] * d;
                out100[base + 2] = cs.dirZ[c] * d;
                out100[base + 3] = cs.radius[c];
                out100[64 + activeCount] = cs.tempC[c];
                out100[80 + activeCount] = cs.shockAge[c];
                activeCount++;
            }
        }
        out100[96] = cs.gravWaveAmp;
        out100[98] = (float) activeCount;
    }

    public static void ensureSaturnRings(List<SpaceEngine.Planet> planets) {
        if (planets == null) return;
        SATURN_RINGS_SPAWNED.retainAll(planets);
        for (int i = 0; i < planets.size(); i++) {
            SpaceEngine.Planet p = planets.get(i);
            if (p == null || p.mass <= 0.0) continue;
            boolean isSaturn = "Saturn".equalsIgnoreCase(p.name) || (p.texture != null && p.texture.toLowerCase().contains("saturn"));
            if (isSaturn && !SATURN_RINGS_SPAWNED.contains(p)) {
                SATURN_RINGS_SPAWNED.add(p);
                spawnSaturnRingSystem(p);
            }
        }
    }

    private static void spawnSaturnRingSystem(SpaceEngine.Planet saturn) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int maxAllowed = Math.max(128, getEffectiveDebrisLimit() / 2);
        int ringCount = Math.min(1800, Math.max(400, maxAllowed / 4));

        double tiltRad = Math.toRadians(saturn.tilt);
        double signRot = (saturn.rotationPeriod < 0.0) ? -1.0 : 1.0;
        SCRATCH_SPIN.set(0.0, signRot, 0.0).rotateZ(tiltRad).normalize();

        double rMinC = saturn.radius * 1.52, rMaxC = saturn.radius * 1.78;
        double rMinB = saturn.radius * 1.82, rMaxB = saturn.radius * 2.40;
        double rMinA = saturn.radius * 2.52, rMaxA = saturn.radius * 3.12;
        double pieceMass = Math.max(1e-7, saturn.mass * 3.2e-7);
        double pieceRadius = Math.max(0.011, saturn.radius * 0.0105);

        for (int k = 0; k < ringCount; k++) {
            if (SpaceEngine.fragments.size() >= maxAllowed) break;
            int bandRoll = k % 20;
            double dist = (bandRoll < 3) ? (rMinC + rng.nextDouble() * (rMaxC - rMinC))
                    : ((bandRoll < 14) ? (rMinB + rng.nextDouble() * (rMaxB - rMinB)) : (rMinA + rng.nextDouble() * (rMaxA - rMinA)));
            double theta = (2.0 * Math.PI * k) / ringCount + (rng.nextDouble() - 0.5) * 0.035;
            double thicknessY = (rng.nextDouble() - 0.5) * (saturn.radius * 0.010);

            SCRATCH_OFFSET.set(Math.cos(theta) * dist, thicknessY, Math.sin(theta) * dist).rotateZ(tiltRad);
            SCRATCH_POS.set(saturn.pos).add(SCRATCH_OFFSET);
            SCRATCH_TANGENT.set(SCRATCH_SPIN).cross(SCRATCH_OFFSET);
            if (SCRATCH_TANGENT.lengthSquared() > 1e-8) SCRATCH_TANGENT.normalize();
            else SCRATCH_TANGENT.set(-Math.sin(theta), 0.0, Math.cos(theta));

            double rRatio2 = (saturn.radius * saturn.radius) / Math.max(dist * dist, 0.01);
            double vKepler = Math.sqrt((G * saturn.mass * (1.0 + 0.008 * rRatio2)) / Math.max(dist, 0.1));
            SCRATCH_VEL.set(saturn.vel).fma(vKepler, SCRATCH_TANGENT);

            SpaceEngine.Fragment f = FRAGMENT_POOL[fragPoolCursor];
            if (f == null) { f = new SpaceEngine.Fragment(); FRAGMENT_POOL[fragPoolCursor] = f; }
            fragPoolCursor = (fragPoolCursor + 1) & DEBRIS_POOL_MASK;
            boolean alreadyActive = (f.life > 0.0);
            float px = rng.nextInt(16), py = rng.nextInt(16);
            double sizeJitter = pieceRadius * (0.55 + rng.nextDouble() * 0.60);

            f.set(SCRATCH_POS, SCRATCH_VEL, sizeJitter, 999990.0, pieceMass, -185.0,
                    SpaceEngine.TEX_FRAGMENT, px / 16.0f, py / 16.0f, (px + 1.0f) / 16.0f, (py + 1.0f) / 16.0f);
            if (!alreadyActive) SpaceEngine.fragments.add(f);
        }
    }

    private static void registerPlanetImpactCrater(
            SpaceEngine.Planet bigger, double worldDirX, double worldDirY, double worldDirZ,
            double impactorRad, double impactHeatC, double waveBoost, boolean triggerNewShockwave) {
        if (bigger == null || "Hole".equals(bigger.type)) return;
        double len = Math.sqrt(worldDirX * worldDirX + worldDirY * worldDirY + worldDirZ * worldDirZ);
        if (len < 1e-7) return;

        double tiltRad = Math.toRadians(bigger.tilt), rotRad = Math.toRadians(bigger.currentAngle);
        SCRATCH_LOCAL_IMPACT.set(worldDirX / len, worldDirY / len, worldDirZ / len).rotateZ(-tiltRad).rotateY(-rotRad).normalize();

        CraterState cs = getOrCreateCrater(bigger);
        cs.gravWaveAmp = (float) Math.min(4.0, cs.gravWaveAmp + waveBoost);
        if (!bigger.isRigid || "Star".equals(bigger.type)) return;

        float lx = (float) SCRATCH_LOCAL_IMPACT.x, ly = (float) SCRATCH_LOCAL_IMPACT.y, lz = (float) SCRATCH_LOCAL_IMPACT.z;
        float targetRad = (float) Math.max(0.045, Math.min(0.68, (impactorRad / Math.max(bigger.radius, 0.05)) * 0.90));

        int slot = -1;
        for (int c = 0; c < MAX_CRATERS_PER_PLANET; c++) {
            if (cs.radius[c] > 0.005f && (lx * cs.dirX[c] + ly * cs.dirY[c] + lz * cs.dirZ[c]) > 0.97f) { slot = c; break; }
        }
        if (slot == -1) {
            for (int c = 0; c < MAX_CRATERS_PER_PLANET; c++) {
                if (cs.radius[c] <= 0.005f) { slot = c; break; }
            }
            if (slot == -1) {
                int smallestIdx = 0; float minR = cs.radius[0];
                for (int c = 1; c < MAX_CRATERS_PER_PLANET; c++) {
                    if (cs.radius[c] < minR) { minR = cs.radius[c]; smallestIdx = c; }
                }
                if (targetRad >= minR * 0.75f || minR < 0.12f) {
                    slot = smallestIdx;
                } else {
                    int nearestIdx = 0; float bestDot = -2.0f;
                    for (int c = 0; c < MAX_CRATERS_PER_PLANET; c++) {
                        float d = lx * cs.dirX[c] + ly * cs.dirY[c] + lz * cs.dirZ[c];
                        if (d > bestDot) { bestDot = d; nearestIdx = c; }
                    }
                    cs.tempC[nearestIdx] = Math.max(cs.tempC[nearestIdx], (float) impactHeatC);
                    if (triggerNewShockwave && cs.shockAge[nearestIdx] <= 0.0f) cs.shockAge[nearestIdx] = 0.001f;
                    return;
                }
            }
            cs.dirX[slot] = lx; cs.dirY[slot] = ly; cs.dirZ[slot] = lz;
            cs.radius[slot] = targetRad;
            cs.depth[slot] = Math.min(1.0f, Math.max(0.35f, targetRad * 1.35f));
            cs.tempC[slot] = (float) Math.min(8500.0, Math.max(bigger.temperature + 950.0, impactHeatC));
            cs.shockAge[slot] = 0.001f;
        } else {
            cs.dirX[slot] = cs.dirX[slot] * 0.82f + lx * 0.18f;
            cs.dirY[slot] = cs.dirY[slot] * 0.82f + ly * 0.18f;
            cs.dirZ[slot] = cs.dirZ[slot] * 0.82f + lz * 0.18f;
            float nL = (float) Math.sqrt(cs.dirX[slot] * cs.dirX[slot] + cs.dirY[slot] * cs.dirY[slot] + cs.dirZ[slot] * cs.dirZ[slot]);
            if (nL > 1e-5f) { cs.dirX[slot] /= nL; cs.dirY[slot] /= nL; cs.dirZ[slot] /= nL; }
            cs.radius[slot] = Math.min(0.75f, Math.max(cs.radius[slot], targetRad));
            cs.depth[slot] = Math.min(1.0f, Math.max(cs.depth[slot], cs.radius[slot] * 1.35f));
            cs.tempC[slot] = (float) Math.min(8500.0, Math.max(cs.tempC[slot], impactHeatC));
            if (triggerNewShockwave || cs.shockAge[slot] <= 0.0f || cs.shockAge[slot] > 0.65f) cs.shockAge[slot] = 0.001f;
        }
    }

    private static void stepCratersThermodynamics(List<SpaceEngine.Planet> planets, double dtSim, double dtReal) {
        for (int i = 0; i < planets.size(); i++) {
            SpaceEngine.Planet p = planets.get(i);
            CraterState cs = CRATER_MAP.get(p);
            if (cs == null) continue;
            if (cs.gravWaveAmp > 0.001f) {
                cs.gravWaveAmp *= (float) Math.exp(-dtReal * 0.55);
                if (cs.gravWaveAmp < 0.002f) cs.gravWaveAmp = 0.0f;
            }
            double effDt = dtReal * 0.35 + Math.min(Math.abs(dtSim) / 3600.0, 48.0) * 0.015;
            double pK = Math.max(2.7, p.temperature + 273.15) * 0.001, pK4 = (pK * pK) * (pK * pK);

            for (int c = 0; c < MAX_CRATERS_PER_PLANET; c++) {
                if (cs.radius[c] > 0.005f) {
                    if (cs.shockAge[c] > 0.0f) {
                        cs.shockAge[c] += (float) (dtReal * 0.48);
                        if (cs.shockAge[c] >= 1.0f) cs.shockAge[c] = 0.0f;
                    }
                    if (cs.tempC[c] > p.temperature + 0.5) {
                        double cK = Math.max(2.7, cs.tempC[c] + 273.15) * 0.001, cK4 = (cK * cK) * (cK * cK);
                        double dT = Math.max(0.5, (cK4 - pK4) * 42.0) * effDt;
                        cs.tempC[c] = (float) Math.max(p.temperature, cs.tempC[c] - dT);
                    } else cs.tempC[c] = (float) p.temperature;
                }
            }
        }
    }

    public static int getEffectiveDebrisLimit() {
        int preset = SpaceEngine.particlePreset;
        return (preset <= 0) ? 3200 : (preset == 1 ? 12800 : (preset == 2 ? 25600 : MAX_GPU_DEBRIS));
    }

    public static double getEffectiveSpawnMultiplier() {
        int preset = SpaceEngine.particlePreset;
        return (preset <= 0) ? 0.5 : (preset == 1 ? 1.2 : (preset == 2 ? 2.5 : 4.5));
    }

    public static int toSectorCoord(double relCoord) { return (int) Math.floor(relCoord / SECTOR_SIZE + 0.5); }
    public static float toLocalCoord(double relCoord, int sector) { return (float) (relCoord - (double) sector * SECTOR_SIZE); }
    public static double fromSectorAndLocal(int sector, float local) { return (double) sector * SECTOR_SIZE + (double) local; }

    public static void init() {
        if (gpuPhysicsReady || SBoxVulkanCore.logicalDevice == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice device = SBoxVulkanCore.logicalDevice;

            String glsl =
                    "#version 450\n" +
                            "#extension GL_ARB_gpu_shader_fp64 : enable\n" +
                            "layout (local_size_x = 64) in;\n" +
                            "struct GpuPlanet { dvec4 posAndMass; dvec4 velAndRadius; dvec4 rotAndTemp; dvec4 stateAndShed; };\n" +
                            "struct GpuDebris { ivec4 sectorAndType; vec4 localPosAndSize; vec4 velAndStatus; vec4 massTempAngle; };\n" +
                            "layout (std430, binding = 0) buffer PlanetPhysicsBuffer { GpuPlanet planets[64]; } pb;\n" +
                            "layout (std430, binding = 1) buffer DebrisPhysicsBuffer { GpuDebris debris[65536]; } db;\n" +
                            "layout (push_constant) uniform PushConstants { double subDt; double dtReal; int planetCount; int debrisCount; int subSteps; int flags; } pc;\n" +
                            "const double G_D = 0.00019926275949971405LF, MAX_C_D = 237.93052222222222LF, MAX_C_SQ_D = 56610.93340559396LF, TWO_G_OVER_C2_D = 7.039726890892425e-9LF;\n" +
                            "const float G_F = 0.00019926276, MAX_C_F = 237.93052, MAX_C_SQ_F = 56610.934, TWO_G_OVER_C2_F = 7.039727e-9;\n" +
                            "const float PI_F = 3.14159265, DEG_TO_RAD_F = 0.0174532925, SECTOR_SIZE_F = 2.0, INV_SECTOR_SIZE_F = 0.5;\n" +
                            "shared dvec4 sPlanetPosMassD[64], sPlanetVelRadD[64];\n" +
                            "shared ivec4 sSector[64], sDebSec[64];\n" +
                            "shared vec4 sLocalMass[64], sVelRad[64], sTempType[64], sRadiosityPass1[64], sDebLocSize[64], sDebVelMass[64];\n" +
                            "void worldDoubleToSector(dvec3 wPos, out ivec3 sec, out vec3 loc) {\n" +
                            "dvec3 secD = floor(wPos * 0.5LF + dvec3(0.5LF)); sec = ivec3(secD); loc = vec3(wPos - secD * 2.0LF);\n" +
                            "}\n" +
                            "void normalizeSectorPos(inout ivec3 sec, inout vec3 loc) {\n" +
                            "ivec3 shift = ivec3(floor(loc * INV_SECTOR_SIZE_F + vec3(0.5))); sec += shift; loc -= vec3(shift) * SECTOR_SIZE_F;\n" +
                            "}\n" +
                            "vec3 sectorDelta(ivec3 secJ, vec3 locJ, ivec3 secI, vec3 locI) {\n" +
                            "return vec3(secJ - secI) * SECTOR_SIZE_F + (locJ - locI);\n" +
                            "}\n" +
                            "float cbrtFast(float v) {\n" +
                            "if (isnan(v) || isinf(v) || !(v > 1e-12)) return 0.01;\n" +
                            "float r = pow(v, 0.33333334); r = (2.0 * r + v / max(r * r, 1e-18)) * 0.33333334; return max(r, 0.01);\n" +
                            "}\n" +
                            "float cubeSupportRadius(vec3 unitDir, float halfExt, float tiltRad, float rotRad) {\n" +
                            "float ct = cos(-tiltRad), st = sin(-tiltRad), cr = cos(-rotRad), sr = sin(-rotRad);\n" +
                            "vec3 uz = vec3(ct * unitDir.x - st * unitDir.y, st * unitDir.x + ct * unitDir.y, unitDir.z);\n" +
                            "vec3 ul = abs(vec3(cr * uz.x + sr * uz.z, uz.y, -sr * uz.x + cr * uz.z));\n" +
                            "return halfExt / max(max(ul.x, ul.y), max(ul.z, 0.57735026));\n" +
                            "}\n" +
                            "float calcSnowCoverage(float tempC, float rad, float mass, float rotPeriod, bool isRigid) {\n" +
                            "if (!isRigid || tempC > 30.0) return 0.0;\n" +
                            "float safeRot = max(0.0001, abs(rotPeriod));\n" +
                            "float wCurr = (2.0 * PI_F) / (safeRot * 3600.0), wCrit = sqrt((G_F * max(mass, 1e-6)) / max(rad * rad * rad, 1e-6));\n" +
                            "float centrifRatio = clamp(wCurr / max(wCrit, 1e-6), 0.0, 1.5), baseSnow = clamp((26.0 - tempC) / 72.0, 0.0, 1.0);\n" +
                            "return clamp(baseSnow * (1.0 - centrifRatio * 0.35), 0.0, 1.0);\n" +
                            "}\n" +
                            "float stepStefanBoltzmann(float currTempC, float incomingRadT4, float areaToMassRatio, float greenhouseTau, float dt) {\n" +
                            "float eqTempK = sqrt(sqrt(max(incomingRadT4 * (1.0 + greenhouseTau), 53.1441))), currTempK = clamp(currTempC + 273.15, 2.7, 40000.0);\n" +
                            "float tNorm = currTempK * 0.001, eqNorm = eqTempK * 0.001;\n" +
                            "float t4 = (tNorm * tNorm) * (tNorm * tNorm), eq4 = (eqNorm * eqNorm) * (eqNorm * eqNorm);\n" +
                            "float phaseBuffer = (abs(currTempC) < 12.0 || abs(currTempC - 100.0) < 18.0) ? 0.45 : 1.0;\n" +
                            "float dT = (eq4 - t4) * 38.0 * areaToMassRatio * phaseBuffer * abs(dt);\n" +
                            "currTempK = (eqTempK > currTempK) ? min(eqTempK, currTempK + max(0.0, dT)) : max(eqTempK, currTempK + min(0.0, dT));\n" +
                            "return currTempK - 273.15;\n" +
                            "}\n" +
                            "dvec3 calcAccelerationDouble(int ignoreIdx, dvec3 posI, dvec3 velI, double radI, double massI, int n, bool gravOn) {\n" +
                            "if (!gravOn) return dvec3(0.0LF);\n" +
                            "dvec3 acc = dvec3(0.0LF);\n" +
                            "for (int j = 0; j < n; j++) {\n" +
                            "if (j == ignoreIdx) continue;\n" +
                            "dvec4 pmJ = sPlanetPosMassD[j]; double massJ = pmJ.w;\n" +
                            "if (!(massJ > 0.0LF)) continue;\n" +
                            "dvec4 vrJ = sPlanetVelRadD[j]; dvec3 d = pmJ.xyz - posI;\n" +
                            "double distSq = max(dot(d, d), 1e-12LF), invDist = inversesqrt(distSq), dist = distSq * invDist;\n" +
                            "double sumRad = max(radI, 0.01LF) + max(vrJ.w, 0.01LF), effDist = max(dist, max(sumRad * 0.65LF, 0.08LF)), invEff = 1.0LF / effDist;\n" +
                            "dvec3 dir = d * invDist, vRel = velI - vrJ.xyz, vTang = vRel - dir * dot(vRel, dir);\n" +
                            "double relFactor = 1.0LF + clamp(3.0LF * dot(vTang, vTang) / MAX_C_SQ_D, 0.0LF, 0.25LF) + clamp(TWO_G_OVER_C2_D * (massI + massJ) * invEff, 0.0LF, 0.20LF);\n" +
                            "double baseG = (G_D * massJ * (invEff * invEff)) * relFactor;\n" +
                            "acc += dir * baseG;\n" +
                            "float packedJ = sTempType[j].z;\n" +
                            "if (packedJ < 500.0 && sTempType[j].y < 0.5 && dist < vrJ.w * 8.0LF) {\n" +
                            "float tiltRadJ = packedJ * DEG_TO_RAD_F;\n" +
                            "dvec3 spinAxis = dvec3(double(-sin(tiltRadJ)), double(cos(tiltRadJ)), 0.0LF);\n" +
                            "double sinLat = dot(-dir, spinAxis), rRatio2 = (vrJ.w * vrJ.w) * (invEff * invEff);\n" +
                            "acc += (dir * (1.0LF - 5.0LF * sinLat * sinLat) + spinAxis * (2.0LF * sinLat)) * (baseG * 0.016LF * rRatio2);\n" +
                            "}\n" +
                            "}\n" +
                            "return acc;\n" +
                            "}\n" +
                            "vec3 calcAccelerationSector(ivec3 secI, vec3 locI, vec3 velI, float radI, float massI, int n, bool gravOn) {\n" +
                            "if (!gravOn || n <= 0) return vec3(0.0);\n" +
                            "vec3 acc = vec3(0.0);\n" +
                            "for (int j = 0; j < n; j++) {\n" +
                            "vec4 lmJ = sLocalMass[j]; float massJ = lmJ.w;\n" +
                            "if (!(massJ > 0.0)) continue;\n" +
                            "vec3 d = sectorDelta(sSector[j].xyz, lmJ.xyz, secI, locI);\n" +
                            "float distSq = max(dot(d, d), 1e-8), invDist = inversesqrt(distSq), dist = distSq * invDist;\n" +
                            "float rJ = max(sVelRad[j].w, 0.01), sumRad = max(radI, 0.01) + rJ, effDist = max(dist, max(sumRad * 0.65, 0.08)), invEff = 1.0 / effDist;\n" +
                            "vec3 dir = d * invDist, vRel = velI - sVelRad[j].xyz, vTang = fma(dir, vec3(-dot(vRel, dir)), vRel);\n" +
                            "float relFactor = 1.0 + clamp(3.0 * dot(vTang, vTang) / MAX_C_SQ_F, 0.0, 0.25) + clamp(TWO_G_OVER_C2_F * (massI + massJ) * invEff, 0.0, 0.20);\n" +
                            "float baseG = (G_F * massJ * (invEff * invEff)) * relFactor;\n" +
                            "acc = fma(dir, vec3(baseG), acc);\n" +
                            "float packedJ = sTempType[j].z;\n" +
                            "if (packedJ < 500.0 && sTempType[j].y < 0.5 && dist < rJ * 6.0) {\n" +
                            "float tiltRadJ = packedJ * DEG_TO_RAD_F;\n" +
                            "vec3 spinAxis = vec3(-sin(tiltRadJ), cos(tiltRadJ), 0.0);\n" +
                            "float sinLat = dot(-dir, spinAxis), rRatio2 = (rJ * rJ) * (invEff * invEff);\n" +
                            "acc += (dir * (1.0 - 5.0 * sinLat * sinLat) + spinAxis * (2.0 * sinLat)) * (baseG * 0.016 * rRatio2);\n" +
                            "}\n" +
                            "}\n" +
                            "return acc;\n" +
                            "}\n" +
                            "void main() {\n" +
                            "uint localIdx = gl_LocalInvocationID.x;\n" +
                            "int n = clamp(pc.planetCount, 0, 64);\n" +
                            "bool gravOn = (pc.flags & 1) != 0, rocheOn = (pc.flags & 2) != 0, centrifOn = (pc.flags & 4) != 0, collOn = (pc.flags & 8) != 0;\n" +
                            "int draggedIdx = (pc.flags >> 4) - 1;\n" +
                            "if (int(localIdx) < n) {\n" +
                            "dvec4 pm = pb.planets[localIdx].posAndMass, vr = pb.planets[localIdx].velAndRadius;\n" +
                            "sPlanetPosMassD[localIdx] = pm; sPlanetVelRadD[localIdx] = dvec4(vr.xyz, max(vr.w, 0.01LF));\n" +
                            "ivec3 sec; vec3 loc; worldDoubleToSector(pm.xyz, sec, loc);\n" +
                            "sSector[localIdx] = ivec4(sec, 0); sLocalMass[localIdx] = vec4(loc, float(pm.w));\n" +
                            "sVelRad[localIdx] = vec4(vec3(vr.xyz), max(float(vr.w), 0.01));\n" +
                            "sTempType[localIdx] = vec4(float(pb.planets[localIdx].rotAndTemp.w), float(pb.planets[localIdx].stateAndShed.x), float(pb.planets[localIdx].stateAndShed.y), float(pb.planets[localIdx].rotAndTemp.x));\n" +
                            "} else {\n" +
                            "sPlanetPosMassD[localIdx] = dvec4(0.0LF); sPlanetVelRadD[localIdx] = dvec4(0.0LF, 0.0LF, 0.0LF, 1.0LF);\n" +
                            "sSector[localIdx] = ivec4(0); sLocalMass[localIdx] = vec4(0.0); sVelRad[localIdx] = vec4(0,0,0,1); sTempType[localIdx] = vec4(0.0);\n" +
                            "}\n" +
                            "barrier();\n" +
                            "double subDtD = pc.subDt, halfDtD = 0.5LF * subDtD, totalDtD = subDtD * double(pc.subSteps);\n" +
                            "float totalDt = float(totalDtD), absTotalDt = abs(totalDt), dtRealF = float(pc.dtReal);\n" +
                            "if (gl_WorkGroupID.x > 0u) {\n" +
                            "uint dIdx = (gl_WorkGroupID.x - 1u) * 64u + localIdx;\n" +
                            "bool validDeb = int(dIdx) < pc.debrisCount;\n" +
                            "ivec4 secType = validDeb ? db.debris[dIdx].sectorAndType : ivec4(0);\n" +
                            "vec4 locSize = validDeb ? db.debris[dIdx].localPosAndSize : vec4(0.0);\n" +
                            "vec4 velStat = validDeb ? db.debris[dIdx].velAndStatus : vec4(0,0,0,-1);\n" +
                            "vec4 mta = validDeb ? db.debris[dIdx].massTempAngle : vec4(0.0);\n" +
                            "bool activeDeb = validDeb && (velStat.w > 0.0);\n" +
                            "ivec3 dSec = secType.xyz; int isFrag = secType.w;\n" +
                            "vec3 dLoc = locSize.xyz, dVel = velStat.xyz;\n" +
                            "float dSize = max(locSize.w, 0.01), dMass = max(mta.x, 1e-7), dTemp = mta.y, dAngle = mta.z, graceTimer = max(0.0, mta.w - dtRealF);\n" +
                            "int absorbedByPlanet = -1, dSteps = clamp(pc.subSteps, 1, 64);\n" +
                            "float dDt = totalDt / float(dSteps), dHalfDt = 0.5 * dDt;\n" +
                            "if (activeDeb) {\n" +
                            "vec3 dAcc = calcAccelerationSector(dSec, dLoc, dVel, dSize, dMass, n, gravOn);\n" +
                            "dVel = fma(dAcc, vec3(dHalfDt), dVel);\n" +
                            "}\n" +
                            "for (int s = 0; s < dSteps; s++) {\n" +
                            "if (activeDeb) {\n" +
                            "float spSq = dot(dVel, dVel); if (spSq > MAX_C_SQ_F) dVel *= (MAX_C_F * inversesqrt(spSq));\n" +
                            "dLoc = fma(dVel, vec3(dDt), dLoc); normalizeSectorPos(dSec, dLoc);\n" +
                            "if (collOn && graceTimer <= 0.0) {\n" +
                            "for (int j = 0; j < n; j++) {\n" +
                            "if (!(sLocalMass[j].w > 0.0)) continue;\n" +
                            "vec3 toP = sectorDelta(sSector[j].xyz, sLocalMass[j].xyz, dSec, dLoc);\n" +
                            "float distSq = max(dot(toP, toP), 1e-8), rJ = max(sVelRad[j].w, 0.01);\n" +
                            "if (distSq < (rJ + dSize) * (rJ + dSize) * 2.5) {\n" +
                            "float invD = inversesqrt(distSq), dist = distSq * invD, packedJ = sTempType[j].z;\n" +
                            "float tiltRadJ = ((packedJ >= 500.0) ? (packedJ - 1000.0) : packedJ) * DEG_TO_RAD_F;\n" +
                            "float rCubeJ = cubeSupportRadius(-toP * invD, rJ, tiltRadJ, sTempType[j].w * DEG_TO_RAD_F);\n" +
                            "if (dist < rCubeJ + dSize * 0.45) { absorbedByPlanet = j; activeDeb = false; break; }\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "sDebSec[localIdx] = ivec4(dSec, activeDeb ? (isFrag != 0 ? 1 : 2) : 0);\n" +
                            "sDebLocSize[localIdx] = vec4(dLoc, dSize); sDebVelMass[localIdx] = vec4(dVel, dMass);\n" +
                            "barrier();\n" +
                            "vec3 debGravAcc = vec3(0.0);\n" +
                            "if (activeDeb) {\n" +
                            "for (uint k = 0u; k < 64u; k++) {\n" +
                            "int kState = sDebSec[k].w;\n" +
                            "if (k == localIdx || kState == 0) continue;\n" +
                            "if (abs(dSec.x - sDebSec[k].x) > 4 || abs(dSec.y - sDebSec[k].y) > 4 || abs(dSec.z - sDebSec[k].z) > 4) continue;\n" +
                            "vec3 delta = sectorDelta(sDebSec[k].xyz, sDebLocSize[k].xyz, dSec, dLoc);\n" +
                            "float otherSize = sDebLocSize[k].w, otherMass = max(sDebVelMass[k].w, 1e-7);\n" +
                            "float minSep = (dSize + otherSize) * ((isFrag != 0 && kState == 1) ? 1.38 : 1.75);\n" +
                            "float rawDistSq = max(dot(delta, delta), 1e-8);\n" +
                            "if (gravOn) {\n" +
                            "float effSq = max(rawDistSq, minSep * minSep * 0.65);\n" +
                            "debGravAcc += delta * (inversesqrt(rawDistSq) * (G_F * otherMass * 6.0 / effSq));\n" +
                            "}\n" +
                            "if (collOn) {\n" +
                            "vec3 relV = dVel - sDebVelMass[k].xyz;\n" +
                            "float tSweep = clamp(-dot(delta, relV) / max(dot(relV, relV), 1e-6), 0.0, dDt);\n" +
                            "vec3 closestDelta = delta - relV * tSweep;\n" +
                            "if (dot(closestDelta, closestDelta) < minSep * minSep) {\n" +
                            "float dist = sqrt(rawDistSq);\n" +
                            "vec3 nDir = delta / max(dist, 1e-5);\n" +
                            "float approach = dot(relV, nDir);\n" +
                            "if (approach > 0.0) {\n" +
                            "float mShare = otherMass / (dMass + otherMass);\n" +
                            "float restitution = (isFrag != 0 && kState == 1) ? 1.58 : 1.12;\n" +
                            "dVel -= nDir * (restitution * approach * mShare);\n" +
                            "dTemp = min(8500.0, dTemp + approach * approach * 28.0 * mShare);\n" +
                            "dAngle = mod(dAngle + approach * 24.0, 360.0);\n" +
                            "}\n" +
                            "if (dist < minSep) { dLoc -= nDir * ((minSep - dist) * 0.52); normalizeSectorPos(dSec, dLoc); }\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "barrier();\n" +
                            "if (activeDeb) {\n" +
                            "vec3 nextAcc = calcAccelerationSector(dSec, dLoc, dVel, dSize, dMass, n, gravOn) + debGravAcc;\n" +
                            "dVel = fma(nextAcc, vec3((s + 1 < dSteps) ? dDt : dHalfDt), dVel);\n" +
                            "}\n" +
                            "}\n" +
                            "if (!validDeb) return;\n" +
                            "if (absorbedByPlanet >= 0) {\n" +
                            "db.debris[dIdx].sectorAndType = ivec4(dSec, isFrag); db.debris[dIdx].localPosAndSize = vec4(dLoc, dSize);\n" +
                            "db.debris[dIdx].velAndStatus = vec4(dVel, -10.0 - float(absorbedByPlanet)); db.debris[dIdx].massTempAngle = vec4(dMass, dTemp, dAngle, 0.0);\n" +
                            "return;\n" +
                            "}\n" +
                            "if (!activeDeb) return;\n" +
                            "float fragSnow = calcSnowCoverage(dTemp, dSize, dMass, 6.0, isFrag != 0);\n" +
                            "float absorb = 1.0 - ((isFrag != 0) ? mix(0.14, 0.82, fragSnow) : 0.38), radFluxT4 = 53.1441;\n" +
                            "for (int j = 0; j < n; j++) {\n" +
                            "if (!(sLocalMass[j].w > 0.0)) continue;\n" +
                            "vec3 toP = sectorDelta(sSector[j].xyz, sLocalMass[j].xyz, dSec, dLoc);\n" +
                            "float dist = max(length(toP), 0.01), rJ = max(sVelRad[j].w, 0.01), tJK = clamp(sTempType[j].x + 273.15, 2.7, 40000.0);\n" +
                            "if (tJK > 150.0) { float geom = (rJ * 0.72) / max(dist, rJ), t2 = tJK * tJK; radFluxT4 += (t2 * t2) * (geom * geom) * absorb; }\n" +
                            "if (sTempType[j].y > 1.5 && dist < rJ * 12.0) dTemp = max(dTemp, 1200.0 + 3800.0 * (rJ * 4.0 / max(dist, rJ)));\n" +
                            "}\n" +
                            "if (isFrag != 0) dAngle = mod(dAngle + 28.0 * (totalDt / 3600.0), 360.0);\n" +
                            "dTemp = stepStefanBoltzmann(dTemp, radFluxT4, clamp((dSize * dSize) / max(dMass * 60.0, 1e-6), 0.05, 18.0), 0.0, absTotalDt);\n" +
                            "db.debris[dIdx].sectorAndType = ivec4(dSec, isFrag); db.debris[dIdx].localPosAndSize = vec4(dLoc, dSize);\n" +
                            "db.debris[dIdx].velAndStatus = vec4(dVel, 1000000.0); db.debris[dIdx].massTempAngle = vec4(dMass, dTemp, dAngle, graceTimer);\n" +
                            "return;\n" +
                            "}\n" +
                            "uint idx = localIdx;\n" +
                            "bool isValid = int(idx) < n, isDragged = isValid && (int(idx) == draggedIdx);\n" +
                            "dvec3 posD = sPlanetPosMassD[idx].xyz; double massD = sPlanetPosMassD[idx].w;\n" +
                            "dvec3 velD = isDragged ? dvec3(0.0LF) : sPlanetVelRadD[idx].xyz; double radD = max(sPlanetVelRadD[idx].w, 0.01LF);\n" +
                            "double angle = isValid ? pb.planets[idx].rotAndTemp.x : 0.0LF, rotPeriod = isValid ? pb.planets[idx].rotAndTemp.y : 24.0LF;\n" +
                            "double targetRad = isValid ? max(pb.planets[idx].rotAndTemp.z, 0.01LF) : radD;\n" +
                            "float tempC = sTempType[idx].x, objType = sTempType[idx].y, packedRT = sTempType[idx].z;\n" +
                            "bool isRigid = (packedRT >= 500.0); float tiltRad = (isRigid ? (packedRT - 1000.0) : packedRT) * DEG_TO_RAD_F;\n" +
                            "float bondAlbedo = isValid ? clamp(float(pb.planets[idx].stateAndShed.z), 0.0, 0.95) : 0.3;\n" +
                            "double totalShedMass = 0.0LF, eventCode = -1.0LF;\n" +
                            "dvec3 accD = (isValid && !isDragged && massD > 0.0LF) ? calcAccelerationDouble(int(idx), posD, velD, radD, massD, n, gravOn) : dvec3(0.0LF);\n" +
                            "if (isValid && !isDragged && massD > 0.0LF) velD += accD * halfDtD;\n" +
                            "for (int step = 0; step < pc.subSteps; step++) {\n" +
                            "if (isValid && !isDragged && massD > 0.0LF) {\n" +
                            "double spSq = dot(velD, velD); if (spSq > MAX_C_SQ_D) velD *= (MAX_C_D * inversesqrt(spSq));\n" +
                            "posD += velD * subDtD;\n" +
                            "}\n" +
                            "sPlanetPosMassD[idx] = dvec4(posD, massD); sPlanetVelRadD[idx] = dvec4(velD, radD);\n" +
                            "barrier();\n" +
                            "dvec3 newAccD = (isValid && !isDragged && massD > 0.0LF) ? calcAccelerationDouble(int(idx), posD, velD, radD, massD, n, gravOn) : dvec3(0.0LF);\n" +
                            "if (isValid && !isDragged && massD > 0.0LF) {\n" +
                            "velD += newAccD * ((step + 1 < pc.subSteps) ? subDtD : halfDtD);\n" +
                            "double spSq2 = dot(velD, velD); if (spSq2 > MAX_C_SQ_D) velD *= (MAX_C_D * inversesqrt(spSq2));\n" +
                            "accD = newAccD;\n" +
                            "}\n" +
                            "barrier();\n" +
                            "}\n" +
                            "ivec3 sec; vec3 loc; worldDoubleToSector(posD, sec, loc);\n" +
                            "float mass = float(massD), rad = float(radD); vec3 vel = vec3(velD);\n" +
                            "sSector[idx] = ivec4(sec, 0); sLocalMass[idx] = vec4(loc, mass); sVelRad[idx] = vec4(vel, rad);\n" +
                            "float snowFrac = calcSnowCoverage(tempC, rad, mass, float(rotPeriod), isRigid && objType < 0.5);\n" +
                            "float effAlbedo = (objType < 0.5) ? clamp(mix(bondAlbedo, 0.86, snowFrac * 0.85), 0.05, 0.94) : 0.0;\n" +
                            "float tempSelfK = clamp(tempC + 273.15, 2.7, 40000.0), tSelf2 = tempSelfK * tempSelfK;\n" +
                            "sRadiosityPass1[idx] = vec4(tSelf2 * tSelf2, 0.0, effAlbedo, float(rotPeriod));\n" +
                            "barrier();\n" +
                            "float pass1IncomingT4 = 0.0;\n" +
                            "if (isValid && mass > 0.0) {\n" +
                            "for (int j = 0; j < n; j++) {\n" +
                            "if (uint(j) == idx || !(sLocalMass[j].w > 0.0)) continue;\n" +
                            "vec3 dVec = vec3(sPlanetPosMassD[j].xyz - posD);\n" +
                            "float dist = max(length(dVec), 1e-4), radJ = max(sVelRad[j].w, 0.01), geom = (radJ * 0.72) / max(dist, radJ);\n" +
                            "pass1IncomingT4 += sRadiosityPass1[j].x * (geom * geom);\n" +
                            "}\n" +
                            "}\n" +
                            "sRadiosityPass1[idx].y = pass1IncomingT4 * (effAlbedo * 0.65 + (1.0 - effAlbedo) * 0.35);\n" +
                            "barrier();\n" +
                            "if (isValid && mass > 0.0) {\n" +
                            "if (abs(rotPeriod) > 0.0001LF) angle = mod(angle + (360.0LF / (rotPeriod * 3600.0LF)) * totalDtD, 360.0LF);\n" +
                            "if (abs(radD - targetRad) > 0.0001LF) { radD = max(0.01LF, radD + (targetRad - radD) * min(1.0LF, 12.0LF * abs(totalDtD))); rad = float(radD); }\n" +
                            "if (centrifOn && objType < 1.5) {\n" +
                            "double signRot = (rotPeriod < 0.0LF) ? -1.0LF : 1.0LF, safeRot = max(0.0001LF, abs(rotPeriod));\n" +
                            "float wCurr = float((2.0LF * double(PI_F)) / (safeRot * 3600.0LF)), wCrit = sqrt((G_F * mass) / max(rad * rad * rad, 1e-6));\n" +
                            "if (wCurr > wCrit) {\n" +
                            "float excess = min((wCurr - wCrit) / max(wCrit, 1e-6), 2.0);\n" +
                            "rotPeriod = signRot * safeRot * (1.0LF + double(excess * 0.85 * absTotalDt));\n" +
                            "float shed = min(mass * 0.15, mass * 0.35 * absTotalDt * excess);\n" +
                            "if (shed > 1e-6 && (mass - shed) > 1e-6) {\n" +
                            "float oldMass = mass; mass -= shed; massD = double(mass); totalShedMass += double(shed);\n" +
                            "targetRad = max(0.02LF, targetRad * double(cbrtFast(mass / oldMass))); radD = targetRad; rad = float(radD);\n" +
                            "tempC += excess * 120.0 * absTotalDt;\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "float incomingRadT4 = 53.1441, rhoSelf = mass / max((4.0 / 3.0) * PI_F * rad * rad * rad, 1e-6);\n" +
                            "float rotRadSelf = float(angle) * DEG_TO_RAD_F, absorptionFactor = 1.0 - effAlbedo;\n" +
                            "for (int j = 0; j < n; j++) {\n" +
                            "if (uint(j) == idx || !(mass > 0.0)) continue;\n" +
                            "float massJ = sLocalMass[j].w; if (!(massJ > 0.0)) continue;\n" +
                            "vec3 velJ = sVelRad[j].xyz;\n" +
                            "float radJ = max(sVelRad[j].w, 0.01), tempJC = sTempType[j].x, typeJ = sTempType[j].y, packedJ = sTempType[j].z;\n" +
                            "float tiltRadJ = ((packedJ >= 500.0) ? (packedJ - 1000.0) : packedJ) * DEG_TO_RAD_F;\n" +
                            "vec3 dVec = vec3(sPlanetPosMassD[j].xyz - posD);\n" +
                            "float distSq = max(dot(dVec, dVec), 1e-10), invDist = inversesqrt(distSq), dist = distSq * invDist;\n" +
                            "vec3 unitDir = dVec * invDist;\n" +
                            "float sumCubeRad = cubeSupportRadius(unitDir, rad, tiltRad, rotRadSelf) + cubeSupportRadius(-unitDir, radJ, tiltRadJ, sTempType[j].w * DEG_TO_RAD_F);\n" +
                            "vec4 radJData = sRadiosityPass1[j]; float geom = (radJ * 0.72) / max(dist, radJ);\n" +
                            "incomingRadT4 += (radJData.x + radJData.y * 0.65) * (geom * geom) * absorptionFactor;\n" +
                            "if (collOn && dist < sumCubeRad) {\n" +
                            "bool selfBigger = (rad > radJ * 1.0001) || (abs(rad - radJ) <= rad * 0.0001 && mass > massJ) || (abs(rad - radJ) <= rad * 0.0001 && mass == massJ && idx < uint(j));\n" +
                            "float smallMass = selfBigger ? massJ : mass, smallRad = selfBigger ? radJ : rad, bigRad = selfBigger ? rad : radJ;\n" +
                            "float overlap = max(0.0, sumCubeRad - dist), penDepth = clamp(overlap / max(smallRad * 2.0, 0.02), 0.05, 1.0);\n" +
                            "float mergeStep = clamp(absTotalDt * (0.85 + penDepth * 2.8), 0.015, 0.38), transfer = min(smallMass, smallMass * mergeStep);\n" +
                            "float relSpeedSq = dot(vel - velJ, vel - velJ);\n" +
                            "if (selfBigger) {\n" +
                            "float splashFrac = (!isRigid) ? 0.28 : ((tempC < -15.0) ? 0.22 : 0.12);\n" +
                            "float splashMass = transfer * splashFrac, absorbedMass = transfer - splashMass;\n" +
                            "double newMassD = massD + double(absorbedMass);\n" +
                            "if (!isDragged) { velD = (velD * massD + sPlanetVelRadD[j].xyz * double(absorbedMass)) / max(newMassD, 1e-9LF); vel = vec3(velD); }\n" +
                            "if (objType < 1.5) {\n" +
                            "float massRatio = transfer / max(float(newMassD), 1e-9);\n" +
                            "tempC = min(35000.0, tempC + (max(0.0, tempJC - tempC) + 520.0 + relSpeedSq * 75.0) * massRatio);\n" +
                            "totalShedMass += double(splashMass);\n" +
                            "}\n" +
                            "massD = newMassD; mass = float(massD);\n" +
                            "float volGain = (smallRad * smallRad * smallRad) * (absorbedMass / max(smallMass, 1e-9));\n" +
                            "targetRad = max(0.02LF, double(cbrtFast(float(targetRad * targetRad * targetRad) + volGain)));\n" +
                            "radD = max(radD, targetRad); rad = float(radD); eventCode = double(200 + j);\n" +
                            "} else {\n" +
                            "float oldM = mass; mass -= transfer; massD = double(max(0.0, mass));\n" +
                            "if (!isDragged) {\n" +
                            "velD = mix(velD, sPlanetVelRadD[j].xyz, double(clamp(mergeStep * 1.65, 0.05, 0.85))); vel = vec3(velD);\n" +
                            "posD += dvec3(unitDir) * double(min(overlap * 0.35, smallRad * 0.4) * mergeStep);\n" +
                            "}\n" +
                            "if (objType < 1.5) {\n" +
                            "totalShedMass += double(transfer * 0.24);\n" +
                            "tempC = min(35000.0, tempC + (420.0 + relSpeedSq * 24.0) * (transfer / max(oldM, 1e-9)));\n" +
                            "}\n" +
                            "eventCode = double(j);\n" +
                            "if (mass <= 1e-5 || rad < bigRad * 0.08 || dist < bigRad * 0.55) {\n" +
                            "mass = 0.0; massD = 0.0LF; rad = 0.01; radD = 0.01LF; targetRad = 0.01LF;\n" +
                            "} else {\n" +
                            "targetRad = max(0.02LF, targetRad * double(cbrtFast(mass / max(oldM, 1e-9)))); radD = targetRad; rad = float(radD);\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "if (rocheOn && objType < 1.5 && mass > 1e-6 && radJ > rad * 1.35 && massJ > mass * 2.0) {\n" +
                            "float rhoJ = massJ / max((4.0 / 3.0) * PI_F * radJ * radJ * radJ, 1e-6);\n" +
                            "if (rhoSelf > 0.0 && rhoJ > 0.0) {\n" +
                            "float rocheLimit = (isRigid ? 1.44 : 2.456) * radJ * cbrtFast(rhoJ / rhoSelf);\n" +
                            "if (typeJ > 1.5) rocheLimit = max(rocheLimit * 0.35, radJ * 8.5);\n" +
                            "if (dist < rocheLimit && dist > sumCubeRad) {\n" +
                            "float depth = clamp(1.0 - (dist / max(rocheLimit, 1e-4)), 0.0, 1.0);\n" +
                            "float shed = min(mass * 0.25, mass * ((typeJ > 1.5) ? (0.35 + depth * 2.2) : (0.1 + depth * 0.9)) * absTotalDt);\n" +
                            "if (shed > 1e-6) {\n" +
                            "float oldM = mass;\n" +
                            "if (mass - shed <= 1e-6) { totalShedMass += double(mass); mass = 0.0; massD = 0.0LF; rad = 0.01; radD = 0.01LF; targetRad = 0.01LF; }\n" +
                            "else { mass -= shed; massD = double(mass); totalShedMass += double(shed); targetRad = max(0.02LF, targetRad * double(cbrtFast(mass / oldM))); radD = targetRad; rad = float(radD); }\n" +
                            "eventCode = double(j + (typeJ > 1.5 ? 100 : 0));\n" +
                            "tempC = min(35000.0, tempC + ((typeJ > 1.5 ? 1800.0 : 450.0) + depth * 3200.0) * absTotalDt);\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "if (objType < 1.5 && mass > 0.0) {\n" +
                            "float areaToMass = clamp((rad * rad) / max(mass * (isRigid ? 14.0 : 28.0), 1e-4), 0.001, 2.5);\n" +
                            "float steamGH = (objType < 0.5 && isRigid) ? (0.35 * clamp((tempC - 35.0) / 90.0, 0.0, 1.0) * clamp((260.0 - tempC) / 90.0, 0.0, 1.0)) : 0.0;\n" +
                            "float so2GH = (objType < 0.5 && bondAlbedo > 0.68 && bondAlbedo < 0.78) ? 1.85 : ((objType < 0.5 && bondAlbedo > 0.23 && bondAlbedo < 0.255) ? 0.08 : 0.0);\n" +
                            "if (objType < 0.5 || sqrt(sqrt(incomingRadT4)) > (tempC + 273.15)) {\n" +
                            "tempC = stepStefanBoltzmann(tempC, incomingRadT4, areaToMass, steamGH + so2GH, absTotalDt);\n" +
                            "}\n" +
                            "}\n" +
                            "if (isnan(posD.x) || isinf(posD.x) || isnan(velD.x) || isinf(velD.x) || isnan(rad) || isinf(rad)) {\n" +
                            "massD = 0.0LF; radD = 0.01LF; targetRad = 0.01LF; velD = dvec3(0.0LF);\n" +
                            "}\n" +
                            "}\n" +
                            "if (isValid) {\n" +
                            "pb.planets[idx].posAndMass = dvec4(posD, massD); pb.planets[idx].velAndRadius = dvec4(velD, radD);\n" +
                            "pb.planets[idx].rotAndTemp = dvec4(angle, rotPeriod, targetRad, double(tempC));\n" +
                            "pb.planets[idx].stateAndShed = dvec4(double(objType), double(packedRT), totalShedMass, eventCode);\n" +
                            "}\n" +
                            "}\n";

            long compiler = Shaderc.shaderc_compiler_initialize();
            long res = Shaderc.shaderc_compile_into_spv(compiler, glsl, Shaderc.shaderc_glsl_compute_shader, "physics_sector.comp", "main", 0);
            if (Shaderc.shaderc_result_get_compilation_status(res) != Shaderc.shaderc_compilation_status_success) {
                System.err.println("[SBox Physics] Ошибка компиляции шейдера:\n" + Shaderc.shaderc_result_get_error_message(res));
                Shaderc.shaderc_result_release(res); Shaderc.shaderc_compiler_release(compiler); return;
            }
            ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(res);

            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO).pCode(spirv);
            LongBuffer pShaderModule = stack.mallocLong(1);
            vkCreateShaderModule(device, moduleInfo, null, pShaderModule);
            long shaderModule = pShaderModule.get(0);

            Shaderc.shaderc_result_release(res);
            Shaderc.shaderc_compiler_release(compiler);

            int cachedCoherentFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT;
            int fallbackCoherentFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;

            VkBufferCreateInfo bufInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO).size(GPU_PLANET_BUFFER_SIZE)
                    .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuf = stack.mallocLong(1);
            vkCreateBuffer(device, bufInfo, null, pBuf);
            physicsPlanetBuffer = pBuf.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, physicsPlanetBuffer, memReqs);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO).allocationSize(memReqs.size())
                    .memoryTypeIndex(findMemoryTypeWithFallback(memReqs.memoryTypeBits(), cachedCoherentFlags, fallbackCoherentFlags));
            LongBuffer pMem = stack.mallocLong(1);
            vkAllocateMemory(device, allocInfo, null, pMem);
            physicsPlanetMemory = pMem.get(0);
            vkBindBufferMemory(device, physicsPlanetBuffer, physicsPlanetMemory, 0);

            PointerBuffer pData1 = stack.mallocPointer(1);
            vkMapMemory(device, physicsPlanetMemory, 0, GPU_PLANET_BUFFER_SIZE, 0, pData1);
            mappedPlanetBuffer = pData1.getByteBuffer(0, GPU_PLANET_BUFFER_SIZE).order(ByteOrder.nativeOrder());

            VkBufferCreateInfo debBufInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO).size(GPU_DEBRIS_BUFFER_SIZE)
                    .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            vkCreateBuffer(device, debBufInfo, null, pBuf);
            physicsDebrisBuffer = pBuf.get(0);

            vkGetBufferMemoryRequirements(device, physicsDebrisBuffer, memReqs);
            allocInfo.allocationSize(memReqs.size())
                    .memoryTypeIndex(findMemoryTypeWithFallback(memReqs.memoryTypeBits(), cachedCoherentFlags, fallbackCoherentFlags));
            vkAllocateMemory(device, allocInfo, null, pMem);
            physicsDebrisMemory = pMem.get(0);
            vkBindBufferMemory(device, physicsDebrisBuffer, physicsDebrisMemory, 0);

            PointerBuffer pData2 = stack.mallocPointer(1);
            vkMapMemory(device, physicsDebrisMemory, 0, GPU_DEBRIS_BUFFER_SIZE, 0, pData2);
            mappedDebrisBuffer = pData2.getByteBuffer(0, GPU_DEBRIS_BUFFER_SIZE).order(ByteOrder.nativeOrder());

            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(bindings);
            LongBuffer pSetLayout = stack.mallocLong(1);
            vkCreateDescriptorSetLayout(device, layoutInfo, null, pSetLayout);
            descriptorSetLayout = pSetLayout.get(0);

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(32);

            VkPipelineLayoutCreateInfo pLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout)).pPushConstantRanges(pushRange);
            LongBuffer pPipeLayout = stack.mallocLong(1);
            vkCreatePipelineLayout(device, pLayoutInfo, null, pPipeLayout);
            pipelineLayout = pPipeLayout.get(0);

            VkPipelineShaderStageCreateInfo stageInfo = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule).pName(stack.UTF8("main"));

            VkComputePipelineCreateInfo.Buffer pipeInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO).stage(stageInfo).layout(pipelineLayout);
            LongBuffer pPipe = stack.mallocLong(1);
            vkCreateComputePipelines(device, VK_NULL_HANDLE, pipeInfo, null, pPipe);
            computePipeline = pPipe.get(0);

            vkDestroyShaderModule(device, shaderModule, null);

            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack);
            poolSize.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO).pPoolSizes(poolSize).maxSets(1);
            LongBuffer pPool = stack.mallocLong(1);
            vkCreateDescriptorPool(device, poolInfo, null, pPool);
            descriptorPool = pPool.get(0);

            VkDescriptorSetAllocateInfo dsAlloc = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer pDs = stack.mallocLong(1);
            vkAllocateDescriptorSets(device, dsAlloc, pDs);
            descriptorSet = pDs.get(0);

            VkDescriptorBufferInfo.Buffer descPlanetBuf = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(physicsPlanetBuffer).offset(0).range(GPU_PLANET_BUFFER_SIZE);
            VkDescriptorBufferInfo.Buffer descDebrisBuf = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(physicsDebrisBuffer).offset(0).range(GPU_DEBRIS_BUFFER_SIZE);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSet).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(descPlanetBuf);
            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSet).dstBinding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(descDebrisBuf);
            vkUpdateDescriptorSets(device, writes, null);

            VkCommandBufferAllocateInfo cmdAlloc = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(SBoxVulkanCore.commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            vkAllocateCommandBuffers(device, cmdAlloc, pCmd);
            physicsCmdBuffer = new VkCommandBuffer(pCmd.get(0), device);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            LongBuffer pFence = stack.mallocLong(1);
            vkCreateFence(device, fenceInfo, null, pFence);
            physicsFence = pFence.get(0);

            gpuPhysicsReady = true;
        } catch (Exception e) {
            System.err.println("[SBox Physics] Ошибка инициализации GPU физики: " + e.getMessage());
            gpuPhysicsReady = false;
        }
    }

    private static void updateDraggedPlanetPosition() {
        if (SpaceEngine.isMoveToolActive && SpaceEngine.draggedPlanet != null) {
            Vector3f rayF = CameraManager.getMouseRay();
            SCRATCH_PULL.set(rayF.x, rayF.y, rayF.z);
            if (Math.abs(SCRATCH_PULL.y) > 0.0005) {
                double t = -CameraManager.pos.y / SCRATCH_PULL.y;
                if (t > 0.0) {
                    SpaceEngine.draggedPlanet.pos.set(CameraManager.pos).fma(t, SCRATCH_PULL);
                    SpaceEngine.draggedPlanet.pos.y = 0.0;
                    SpaceEngine.draggedPlanet.vel.zero();
                }
            }
        }
    }

    public static void tick(double dtReal) {
        ensureSaturnRings(SpaceEngine.planets);
        updateDraggedPlanetPosition();

        if (!SpaceEngine.isPaused && SpaceEngine.physicsEnabled) {
            long tickStart = System.nanoTime();
            double dtSim = dtReal * SpaceEngine.timeScale;
            int totalSubSteps = (int) Math.max(8, Math.min(1024, Math.ceil(Math.abs(dtSim) / 6.0)));
            double subDt = dtSim / totalSubSteps;

            runGpuPhysicsStep(subDt, dtReal, totalSubSteps, dtSim);
            stepCrossGroupDebrisInteractions();
            updateDraggedPlanetPosition();
            stepCratersThermodynamics(SpaceEngine.planets, dtSim, dtReal);

            SpaceEngine.simTimeSeconds += dtSim;
            SpaceEngine.lastPhysicsTickMs = (System.nanoTime() - tickStart) / 1_000_000.0;
        }
    }

    private static int spatialHash(double x, double y, double z, double invCell) {
        int ix = (int) Math.floor(x * invCell), iy = (int) Math.floor(y * invCell), iz = (int) Math.floor(z * invCell);
        return (ix * 73856093 ^ iy * 19349663 ^ iz * 83492791) & HASH_MASK;
    }

    private static void buildOrthoBasis(Vector3d normal, Vector3d outB1, Vector3d outB2) {
        if (Math.abs(normal.y) < 0.9) outB1.set(0.0, 1.0, 0.0).cross(normal).normalize();
        else outB1.set(1.0, 0.0, 0.0).cross(normal).normalize();
        outB2.set(normal).cross(outB1).normalize();
    }

    private static void stepCrossGroupDebrisInteractions() {
        if (!SpaceEngine.enableCollisions) return;
        List<SpaceEngine.Fragment> fragments = SpaceEngine.fragments;
        List<SpaceEngine.Particle> particles = SpaceEngine.particles;

        int fSize = Math.min(fragments.size(), MAX_GPU_DEBRIS);
        if (fSize > 1) {
            Arrays.fill(HASH_HEAD, -1);
            final double invCell = 0.25;
            for (int i = 0; i < fSize; i++) {
                SpaceEngine.Fragment f = fragments.get(i);
                if (f == null || f.life <= 0.0) continue;
                int b = spatialHash(f.pos.x, f.pos.y, f.pos.z, invCell);
                HASH_NEXT[i] = HASH_HEAD[b];
                HASH_HEAD[b] = i;
            }

            for (int i = 0; i < fSize; i++) {
                SpaceEngine.Fragment a = fragments.get(i);
                if (a == null || a.life <= 0.0) continue;
                int b = spatialHash(a.pos.x, a.pos.y, a.pos.z, invCell);
                int j = HASH_HEAD[b], checks = 0;
                while (j != -1 && checks < 20) {
                    if (j > i) {
                        SpaceEngine.Fragment c = fragments.get(j);
                        if (c != null && c.life > 0.0) {
                            double dx = c.pos.x - a.pos.x, dy = c.pos.y - a.pos.y, dz = c.pos.z - a.pos.z;
                            double minSep = (a.size + c.size) * 1.42;
                            double distSq = dx * dx + dy * dy + dz * dz;
                            if (distSq > 1e-10 && distSq < minSep * minSep) {
                                double dist = Math.sqrt(distSq);
                                double nx = dx / dist, ny = dy / dist, nz = dz / dist;
                                double rvx = a.vel.x - c.vel.x, rvy = a.vel.y - c.vel.y, rvz = a.vel.z - c.vel.z;
                                double approach = rvx * nx + rvy * ny + rvz * nz;
                                double relSpeedSq = rvx * rvx + rvy * rvy + rvz * rvz;
                                double totM = Math.max(1e-9, a.mass + c.mass);
                                double escSq = (2.0 * G * totM) / Math.max(minSep, 0.02);

                                if (relSpeedSq < escSq && Math.cbrt(a.size * a.size * a.size + c.size * c.size * c.size) < 1.8) {
                                    a.vel.x = (a.vel.x * a.mass + c.vel.x * c.mass) / totM;
                                    a.vel.y = (a.vel.y * a.mass + c.vel.y * c.mass) / totM;
                                    a.vel.z = (a.vel.z * a.mass + c.vel.z * c.mass) / totM;
                                    a.pos.x = (a.pos.x * a.mass + c.pos.x * c.mass) / totM;
                                    a.pos.y = (a.pos.y * a.mass + c.pos.y * c.mass) / totM;
                                    a.pos.z = (a.pos.z * a.mass + c.pos.z * c.mass) / totM;
                                    a.size = Math.cbrt(a.size * a.size * a.size + c.size * c.size * c.size);
                                    a.temperature = Math.min(8500.0, (a.temperature * a.mass + c.temperature * c.mass) / totM + relSpeedSq * 18.0);
                                    a.mass = totM;
                                    c.life = -1.0;
                                } else if (approach > 0.0) {
                                    double imp = 1.55 * approach / totM;
                                    a.vel.x -= nx * imp * c.mass; a.vel.y -= ny * imp * c.mass; a.vel.z -= nz * imp * c.mass;
                                    c.vel.x += nx * imp * a.mass; c.vel.y += ny * imp * a.mass; c.vel.z += nz * imp * a.mass;
                                    double heat = approach * approach * 16.0;
                                    a.temperature = Math.min(8500.0, a.temperature + heat * (c.mass / totM));
                                    c.temperature = Math.min(8500.0, c.temperature + heat * (a.mass / totM));
                                    double push = (minSep - dist) * 0.52;
                                    a.pos.x -= nx * push; a.pos.y -= ny * push; a.pos.z -= nz * push;
                                    c.pos.x += nx * push; c.pos.y += ny * push; c.pos.z += nz * push;
                                }
                            }
                        }
                    }
                    j = HASH_NEXT[j];
                    checks++;
                }
            }
            for (int i = fragments.size() - 1; i >= 0; i--) {
                SpaceEngine.Fragment f = fragments.get(i);
                if (f == null || f.life <= 0.0) fragments.remove(i);
            }
        }

        int pSize = Math.min(particles.size(), MAX_GPU_DEBRIS);
        if (pSize > 1) {
            Arrays.fill(HASH_HEAD, -1);
            final double invGasCell = 0.125;
            for (int i = 0; i < pSize; i++) {
                SpaceEngine.Particle pt = particles.get(i);
                if (pt == null || pt.life <= 0.0) continue;
                int b = spatialHash(pt.pos.x, pt.pos.y, pt.pos.z, invGasCell);
                HASH_NEXT[i] = HASH_HEAD[b];
                HASH_HEAD[b] = i;
            }

            for (int i = 0; i < pSize; i++) {
                SpaceEngine.Particle a = particles.get(i);
                if (a == null || a.life <= 0.0) continue;
                int b = spatialHash(a.pos.x, a.pos.y, a.pos.z, invGasCell);
                int j = HASH_HEAD[b], checks = 0;
                while (j != -1 && checks < 20) {
                    if (j > i) {
                        SpaceEngine.Particle c = particles.get(j);
                        if (c != null && c.life > 0.0) {
                            double dx = c.pos.x - a.pos.x, dy = c.pos.y - a.pos.y, dz = c.pos.z - a.pos.z;
                            double minSep = (a.size + c.size) * 1.65;
                            double distSq = dx * dx + dy * dy + dz * dz;
                            if (distSq > 1e-10 && distSq < minSep * minSep) {
                                double dist = Math.sqrt(distSq);
                                double nx = dx / dist, ny = dy / dist, nz = dz / dist;
                                double rvx = a.vel.x - c.vel.x, rvy = a.vel.y - c.vel.y, rvz = a.vel.z - c.vel.z;
                                double approach = rvx * nx + rvy * ny + rvz * nz;
                                double relSpeedSq = rvx * rvx + rvy * rvy + rvz * rvz;
                                double totM = Math.max(1e-9, a.mass + c.mass);
                                double escSq = (2.2 * G * totM) / Math.max(minSep, 0.05);

                                if (relSpeedSq < escSq && Math.cbrt(a.size * a.size * a.size + c.size * c.size * c.size) < 3.2) {
                                    a.vel.x = (a.vel.x * a.mass + c.vel.x * c.mass) / totM;
                                    a.vel.y = (a.vel.y * a.mass + c.vel.y * c.mass) / totM;
                                    a.vel.z = (a.vel.z * a.mass + c.vel.z * c.mass) / totM;
                                    a.pos.x = (a.pos.x * a.mass + c.pos.x * c.mass) / totM;
                                    a.pos.y = (a.pos.y * a.mass + c.pos.y * c.mass) / totM;
                                    a.pos.z = (a.pos.z * a.mass + c.pos.z * c.mass) / totM;
                                    a.r = (int) Math.round((a.r * a.mass + c.r * c.mass) / totM);
                                    a.g = (int) Math.round((a.g * a.mass + c.g * c.mass) / totM);
                                    a.b = (int) Math.round((a.b * a.mass + c.b * c.mass) / totM);
                                    a.size = Math.cbrt(a.size * a.size * a.size + c.size * c.size * c.size);
                                    a.temperature = Math.min(12000.0, (a.temperature * a.mass + c.temperature * c.mass) / totM + relSpeedSq * 14.0);
                                    a.mass = totM;
                                    c.life = -1.0;
                                } else if (approach > 0.0) {
                                    double imp = 1.15 * approach / totM;
                                    a.vel.x -= nx * imp * c.mass; a.vel.y -= ny * imp * c.mass; a.vel.z -= nz * imp * c.mass;
                                    c.vel.x += nx * imp * a.mass; c.vel.y += ny * imp * a.mass; c.vel.z += nz * imp * a.mass;
                                }
                            }
                        }
                    }
                    j = HASH_NEXT[j];
                    checks++;
                }
            }
            for (int i = particles.size() - 1; i >= 0; i--) {
                SpaceEngine.Particle pt = particles.get(i);
                if (pt == null || pt.life <= 0.0) particles.remove(i);
            }
        }
    }

    private static void runGpuPhysicsStep(double subDt, double dtReal, int subSteps, double dtSim) {
        if (!gpuPhysicsReady) return;

        List<SpaceEngine.Planet> planets = SpaceEngine.planets;
        List<SpaceEngine.Fragment> fragments = SpaceEngine.fragments;
        List<SpaceEngine.Particle> particles = SpaceEngine.particles;

        int pSize = Math.min(planets.size(), MAX_GPU_PLANETS);
        if (pSize == 0 && fragments.isEmpty() && particles.isEmpty()) return;

        int maxDebrisLimit = Math.min(MAX_GPU_DEBRIS, getEffectiveDebrisLimit());
        int maxPerType = maxDebrisLimit / 2;
        while (fragments.size() > maxPerType) {
            SpaceEngine.Fragment removed = fragments.remove(fragments.size() - 1);
            if (removed != null) removed.life = -1.0;
        }
        while (particles.size() > maxDebrisLimit - fragments.size()) {
            SpaceEngine.Particle removed = particles.remove(particles.size() - 1);
            if (removed != null) removed.life = -1.0;
        }

        double refOriginX = Math.floor(CameraManager.pos.x / SECTOR_SIZE) * SECTOR_SIZE;
        double refOriginY = Math.floor(CameraManager.pos.y / SECTOR_SIZE) * SECTOR_SIZE;
        double refOriginZ = Math.floor(CameraManager.pos.z / SECTOR_SIZE) * SECTOR_SIZE;

        int draggedIndex = -1;
        for (int i = 0; i < pSize; i++) {
            SpaceEngine.Planet p = planets.get(i);
            if (SpaceEngine.isMoveToolActive && p == SpaceEngine.draggedPlanet) draggedIndex = i;
            PREV_POS_X[i] = p.pos.x; PREV_POS_Y[i] = p.pos.y; PREV_POS_Z[i] = p.pos.z;
            PREV_VEL_X[i] = p.vel.x; PREV_VEL_Y[i] = p.vel.y; PREV_VEL_Z[i] = p.vel.z;

            int base = i * GPU_PLANET_STRIDE;
            mappedPlanetBuffer.putDouble(base,      p.pos.x - refOriginX);
            mappedPlanetBuffer.putDouble(base + 8,  p.pos.y - refOriginY);
            mappedPlanetBuffer.putDouble(base + 16, p.pos.z - refOriginZ);
            mappedPlanetBuffer.putDouble(base + 24, p.mass);

            mappedPlanetBuffer.putDouble(base + 32, p.vel.x);
            mappedPlanetBuffer.putDouble(base + 40, p.vel.y);
            mappedPlanetBuffer.putDouble(base + 48, p.vel.z);
            mappedPlanetBuffer.putDouble(base + 56, Math.max(0.01, p.radius));

            mappedPlanetBuffer.putDouble(base + 64, p.currentAngle);
            mappedPlanetBuffer.putDouble(base + 72, p.rotationPeriod);
            mappedPlanetBuffer.putDouble(base + 80, Math.max(0.01, p.targetRadius));
            mappedPlanetBuffer.putDouble(base + 88, p.temperature);

            double objType = "Star".equals(p.type) ? 1.0 : ("Hole".equals(p.type) ? 2.0 : 0.0);
            double packedRigidAndTilt = (p.isRigid ? 1000.0 : 0.0) + p.tilt;
            mappedPlanetBuffer.putDouble(base + 96,  objType);
            mappedPlanetBuffer.putDouble(base + 104, packedRigidAndTilt);
            mappedPlanetBuffer.putDouble(base + 112, p.bondAlbedo);
            mappedPlanetBuffer.putDouble(base + 120, -1.0);
        }

        int fCount = Math.min(fragments.size(), maxPerType);
        int ptCount = Math.min(particles.size(), maxDebrisLimit - fCount);
        int totalDebris = fCount + ptCount;

        for (int i = 0; i < fCount; i++) {
            SpaceEngine.Fragment f = fragments.get(i);
            int base = i * GPU_DEBRIS_STRIDE;
            double relX = f.pos.x - refOriginX, relY = f.pos.y - refOriginY, relZ = f.pos.z - refOriginZ;
            int sx = toSectorCoord(relX), sy = toSectorCoord(relY), sz = toSectorCoord(relZ);
            mappedDebrisBuffer.putInt(base, sx);
            mappedDebrisBuffer.putInt(base + 4, sy);
            mappedDebrisBuffer.putInt(base + 8, sz);
            mappedDebrisBuffer.putInt(base + 12, 1);

            mappedDebrisBuffer.putFloat(base + 16, toLocalCoord(relX, sx));
            mappedDebrisBuffer.putFloat(base + 20, toLocalCoord(relY, sy));
            mappedDebrisBuffer.putFloat(base + 24, toLocalCoord(relZ, sz));
            mappedDebrisBuffer.putFloat(base + 28, (float) Math.max(0.01, f.size));

            mappedDebrisBuffer.putFloat(base + 32, (float) f.vel.x);
            mappedDebrisBuffer.putFloat(base + 36, (float) f.vel.y);
            mappedDebrisBuffer.putFloat(base + 40, (float) f.vel.z);
            mappedDebrisBuffer.putFloat(base + 44, 1000000.0f);

            float grace = (f.life > 999990.0) ? (float) (f.life - 999990.0) : 0.0f;
            mappedDebrisBuffer.putFloat(base + 48, (float) f.mass);
            mappedDebrisBuffer.putFloat(base + 52, (float) f.temperature);
            mappedDebrisBuffer.putFloat(base + 56, (float) f.angle);
            mappedDebrisBuffer.putFloat(base + 60, grace);
        }

        for (int i = 0; i < ptCount; i++) {
            SpaceEngine.Particle pt = particles.get(i);
            int base = (fCount + i) * GPU_DEBRIS_STRIDE;
            double relX = pt.pos.x - refOriginX, relY = pt.pos.y - refOriginY, relZ = pt.pos.z - refOriginZ;
            int sx = toSectorCoord(relX), sy = toSectorCoord(relY), sz = toSectorCoord(relZ);
            mappedDebrisBuffer.putInt(base, sx);
            mappedDebrisBuffer.putInt(base + 4, sy);
            mappedDebrisBuffer.putInt(base + 8, sz);
            mappedDebrisBuffer.putInt(base + 12, 0);

            mappedDebrisBuffer.putFloat(base + 16, toLocalCoord(relX, sx));
            mappedDebrisBuffer.putFloat(base + 20, toLocalCoord(relY, sy));
            mappedDebrisBuffer.putFloat(base + 24, toLocalCoord(relZ, sz));
            mappedDebrisBuffer.putFloat(base + 28, (float) Math.max(0.01, pt.size));

            mappedDebrisBuffer.putFloat(base + 32, (float) pt.vel.x);
            mappedDebrisBuffer.putFloat(base + 36, (float) pt.vel.y);
            mappedDebrisBuffer.putFloat(base + 40, (float) pt.vel.z);
            mappedDebrisBuffer.putFloat(base + 44, 1000000.0f);

            float grace = (pt.life > 999990.0) ? (float) (pt.life - 999990.0) : 0.0f;
            mappedDebrisBuffer.putFloat(base + 48, (float) pt.mass);
            mappedDebrisBuffer.putFloat(base + 52, (float) pt.temperature);
            mappedDebrisBuffer.putFloat(base + 56, 0.0f);
            mappedDebrisBuffer.putFloat(base + 60, grace);
        }

        int flags = (SpaceEngine.enableGravity ? 1 : 0)
                | (SpaceEngine.enableRocheLimit ? 2 : 0)
                | (SpaceEngine.enableCentrifugalForce ? 4 : 0)
                | (SpaceEngine.enableCollisions ? 8 : 0)
                | ((draggedIndex + 1) << 4);

        int debrisGroups = (totalDebris + 63) / 64;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkResetCommandBuffer(physicsCmdBuffer, 0);
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(physicsCmdBuffer, beginInfo);

            VkMemoryBarrier.Buffer hostToShaderBarrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_HOST_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            vkCmdPipelineBarrier(physicsCmdBuffer, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, hostToShaderBarrier, null, null);

            vkCmdBindPipeline(physicsCmdBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, computePipeline);
            vkCmdBindDescriptorSets(physicsCmdBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptorSet), null);

            ByteBuffer push = stack.calloc(32).order(ByteOrder.nativeOrder());
            push.putDouble(0, subDt);
            push.putDouble(8, dtReal);
            push.putInt(16, pSize);
            push.putInt(20, totalDebris);
            push.putInt(24, subSteps);
            push.putInt(28, flags);
            push.position(0).limit(32);

            vkCmdPushConstants(physicsCmdBuffer, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(physicsCmdBuffer, 1 + debrisGroups, 1, 1);

            VkMemoryBarrier.Buffer shaderToHostBarrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(physicsCmdBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, shaderToHostBarrier, null, null);

            vkEndCommandBuffer(physicsCmdBuffer);

            vkResetFences(SBoxVulkanCore.logicalDevice, physicsFence);
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(physicsCmdBuffer));
            vkQueueSubmit(SBoxVulkanCore.graphicsQueue, submitInfo, physicsFence);
            vkWaitForFences(SBoxVulkanCore.logicalDevice, physicsFence, true, -1L);
        }

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        boolean anyRemoved = false;

        for (int i = 0; i < pSize; i++) {
            SpaceEngine.Planet p = planets.get(i);
            int base = i * GPU_PLANET_STRIDE;
            p.pos.set(
                    mappedPlanetBuffer.getDouble(base)      + refOriginX,
                    mappedPlanetBuffer.getDouble(base + 8)  + refOriginY,
                    mappedPlanetBuffer.getDouble(base + 16) + refOriginZ
            );
            p.mass = mappedPlanetBuffer.getDouble(base + 24);
            p.vel.set(mappedPlanetBuffer.getDouble(base + 32), mappedPlanetBuffer.getDouble(base + 40), mappedPlanetBuffer.getDouble(base + 48));
            p.radius = Math.max(0.01, mappedPlanetBuffer.getDouble(base + 56));
            p.currentAngle = mappedPlanetBuffer.getDouble(base + 64);
            p.rotationPeriod = mappedPlanetBuffer.getDouble(base + 72);
            p.targetRadius = Math.max(0.01, mappedPlanetBuffer.getDouble(base + 80));
            p.temperature = mappedPlanetBuffer.getDouble(base + 88);

            double shedMass = mappedPlanetBuffer.getDouble(base + 112);
            int rawEventCode = (int) Math.round(mappedPlanetBuffer.getDouble(base + 120));

            boolean isBigAbsorber = (rawEventCode >= 200);
            int absorbedIdx = isBigAbsorber ? (rawEventCode - 200) : -1;
            if (isBigAbsorber && absorbedIdx >= 0 && absorbedIdx < pSize) {
                SpaceEngine.Planet smaller = planets.get(absorbedIdx);
                double dx = smaller.pos.x - p.pos.x, dy = smaller.pos.y - p.pos.y, dz = smaller.pos.z - p.pos.z;
                double vRelSq = p.vel.distanceSquared(smaller.vel);
                double craterHeat = Math.max(p.temperature + 1350.0, 1650.0 + vRelSq * 90.0);
                registerPlanetImpactCrater(p, dx, dy, dz, Math.max(smaller.radius, p.radius * 0.20), craterHeat, 0.45, false);
            }

            boolean isBlackHoleSpaghettification = (!isBigAbsorber && rawEventCode >= 100);
            int partnerIdx = isBigAbsorber ? absorbedIdx : (isBlackHoleSpaghettification ? (rawEventCode - 100) : rawEventCode);

            if (!isBigAbsorber && !isBlackHoleSpaghettification && partnerIdx >= 0 && partnerIdx < pSize) {
                SpaceEngine.Planet bigger = planets.get(partnerIdx);
                double dx = p.pos.x - bigger.pos.x, dy = p.pos.y - bigger.pos.y, dz = p.pos.z - bigger.pos.z;
                double vRelSq = p.vel.distanceSquared(bigger.vel);
                double craterHeat = Math.max(bigger.temperature + 1350.0, 1650.0 + vRelSq * 90.0);
                registerPlanetImpactCrater(bigger, dx, dy, dz, Math.max(p.radius, bigger.radius * 0.18), craterHeat, 0.45, false);
            }

            if (shedMass > 1e-7 && !"Hole".equals(p.type)) {
                double parentTotalMass = Math.max(p.mass + shedMass, 1e-6);
                double parentOrigRadius = Math.max(p.radius, 0.05) * Math.cbrt(parentTotalMass / Math.max(p.mass, 1e-6));
                double targetPieceMass = Math.max(1e-7, parentTotalMass * (p.isRigid ? 0.005 : 0.0035));
                int count = Math.max(2, Math.min(28, (int) Math.ceil((shedMass / targetPieceMass) * getEffectiveSpawnMultiplier())));

                double weightSum = 0.0;
                for (int k = 0; k < count; k++) {
                    double u = 0.12 + 0.88 * rng.nextDouble();
                    double w = Math.pow(u, -1.35);
                    MASS_WEIGHTS[k] = w;
                    weightSum += w;
                }

                if (partnerIdx >= 0 && partnerIdx < pSize) {
                    SpaceEngine.Planet partner = planets.get(partnerIdx);
                    SCRATCH_PULL.set(partner.pos).sub(p.pos);
                    double distToPartner = SCRATCH_PULL.length();
                    if (distToPartner > 1e-6) SCRATCH_PULL.div(distToPartner);
                    else SCRATCH_PULL.set(1.0, 0.0, 0.0);

                    buildOrthoBasis(SCRATCH_PULL, SCRATCH_BASIS1, SCRATCH_BASIS2);

                    for (int k = 0; k < count; k++) {
                        double pieceMass = shedMass * (MASS_WEIGHTS[k] / weightSum);
                        if (isBlackHoleSpaghettification) {
                            double lobeSign = (k % 2 == 0) ? 1.0 : -0.85;
                            double phi = rng.nextDouble() * Math.PI * 2.0;
                            double coneSpread = 0.08 + rng.nextDouble() * 0.14;
                            SCRATCH_OFFSET.set(SCRATCH_PULL).mul(lobeSign)
                                    .fma(Math.cos(phi) * coneSpread, SCRATCH_BASIS1)
                                    .fma(Math.sin(phi) * coneSpread, SCRATCH_BASIS2).normalize();
                            SCRATCH_POS.set(p.pos).fma(parentOrigRadius * (1.35 + rng.nextDouble() * 0.85), SCRATCH_OFFSET);
                            double tidalJetSpeed = Math.sqrt((G * partner.mass) / Math.max(distToPartner, partner.radius)) * (0.28 + rng.nextDouble() * 0.25);
                            SCRATCH_VEL.set(p.vel).fma(lobeSign > 0 ? tidalJetSpeed : -tidalJetSpeed * 0.45, SCRATCH_OFFSET);
                            spawnPooledDebris(p, parentTotalMass, parentOrigRadius, SCRATCH_POS, SCRATCH_VEL, pieceMass, Math.max(p.temperature, 2600.0), rng);
                        } else {
                            SpaceEngine.Planet hostBody = isBigAbsorber ? p : partner;
                            SpaceEngine.Planet impBody = isBigAbsorber ? partner : p;
                            SCRATCH_PULL.set(impBody.pos).sub(hostBody.pos);
                            double dCenters = SCRATCH_PULL.length();
                            if (dCenters > 1e-6) SCRATCH_PULL.div(dCenters);
                            else SCRATCH_PULL.set(0.0, 1.0, 0.0);

                            buildOrthoBasis(SCRATCH_PULL, SCRATCH_BASIS1, SCRATCH_BASIS2);

                            double vRelX = impBody.vel.x - hostBody.vel.x, vRelY = impBody.vel.y - hostBody.vel.y, vRelZ = impBody.vel.z - hostBody.vel.z;
                            double vNorm = vRelX * SCRATCH_PULL.x + vRelY * SCRATCH_PULL.y + vRelZ * SCRATCH_PULL.z;
                            SCRATCH_TANGENT.set(vRelX - vNorm * SCRATCH_PULL.x, vRelY - vNorm * SCRATCH_PULL.y, vRelZ - vNorm * SCRATCH_PULL.z);
                            double vTangLen = SCRATCH_TANGENT.length();
                            if (vTangLen > 1e-5) SCRATCH_TANGENT.div(vTangLen);
                            else SCRATCH_TANGENT.set(SCRATCH_BASIS1);

                            double phi = (2.0 * Math.PI * k) / count + (rng.nextDouble() - 0.5) * 0.45;
                            double coneAngle = Math.toRadians(38.0 + rng.nextDouble() * 28.0);
                            double sinC = Math.sin(coneAngle), cosC = Math.cos(coneAngle);

                            SCRATCH_OFFSET.set(SCRATCH_PULL).mul(cosC)
                                    .fma(sinC * Math.cos(phi), SCRATCH_BASIS1)
                                    .fma(sinC * Math.sin(phi), SCRATCH_BASIS2)
                                    .fma(0.35, SCRATCH_TANGENT).normalize();

                            double spawnR = hostBody.radius * (1.42 + rng.nextDouble() * 0.35);
                            SCRATCH_POS.set(hostBody.pos).fma(hostBody.radius * 0.85, SCRATCH_PULL).fma(spawnR - hostBody.radius * 0.65, SCRATCH_OFFSET);

                            double escSpeed = Math.sqrt((2.0 * G * Math.max(hostBody.mass, 1e-4)) / Math.max(spawnR, 0.1));
                            double relImpactSpeed = Math.sqrt(vRelX * vRelX + vRelY * vRelY + vRelZ * vRelZ);
                            double massSpeedFactor = Math.pow(MASS_WEIGHTS[k] / (weightSum / count), -0.18);
                            double ejectSpeed = (escSpeed * (0.68 + rng.nextDouble() * 0.46) + relImpactSpeed * 0.28) * Math.min(1.45, Math.max(0.65, massSpeedFactor));

                            SCRATCH_VEL.set(hostBody.vel).fma(ejectSpeed, SCRATCH_OFFSET);

                            boolean isIcyImpact = "Uranus".equalsIgnoreCase(p.name) || "Enceladus".equalsIgnoreCase(p.name)
                                    || "Titan".equalsIgnoreCase(p.name) || p.temperature < -15.0;
                            double ejectTemp = isIcyImpact ? Math.min(-45.0, p.temperature) : (p.isRigid ? Math.max(p.temperature, 950.0 + relImpactSpeed * 35.0) : p.temperature);
                            spawnImpactDebrisOrGas(p, isIcyImpact, parentTotalMass, parentOrigRadius, SCRATCH_POS, SCRATCH_VEL, pieceMass, ejectTemp, rng);
                        }
                    }
                } else {
                    double tiltRad = Math.toRadians(p.tilt);
                    double signRot = (p.rotationPeriod < 0.0) ? -1.0 : 1.0;
                    SCRATCH_SPIN.set(0.0, signRot, 0.0).rotateZ(tiltRad);
                    double wCurrent = (2.0 * Math.PI) / (Math.max(0.0001, Math.abs(p.rotationPeriod)) * 3600.0);
                    double baseAngle = rng.nextDouble() * Math.PI * 2.0;

                    for (int k = 0; k < count; k++) {
                        double pieceMass = shedMass * (MASS_WEIGHTS[k] / weightSum);
                        double spawnDist = parentOrigRadius * (1.24 + rng.nextDouble() * 0.26);
                        double vOrbital = Math.sqrt((G * Math.max(p.mass, 1e-5)) / spawnDist);
                        double vTangential = Math.max(wCurrent * spawnDist, vOrbital * 1.04) * (0.86 + rng.nextDouble() * 0.28);

                        double ang = (k % 2 == 0) ? (baseAngle + (rng.nextDouble() - 0.5) * 0.42) : (rng.nextDouble() * Math.PI * 2.0);
                        SCRATCH_OFFSET.set(Math.cos(ang), (rng.nextDouble() - 0.5) * 0.08, Math.sin(ang)).normalize().rotateZ(tiltRad);
                        SCRATCH_POS.set(p.pos).fma(spawnDist, SCRATCH_OFFSET);
                        SCRATCH_TANGENT.set(SCRATCH_SPIN).cross(SCRATCH_OFFSET);
                        if (SCRATCH_TANGENT.lengthSquared() > 1e-8) SCRATCH_TANGENT.normalize();
                        else SCRATCH_TANGENT.set(-Math.sin(ang), 0.0, Math.cos(ang));

                        SCRATCH_VEL.set(p.vel)
                                .fma(vTangential, SCRATCH_TANGENT)
                                .fma(vOrbital * (0.06 + rng.nextDouble() * 0.18), SCRATCH_OFFSET);
                        spawnPooledDebris(p, parentTotalMass, parentOrigRadius, SCRATCH_POS, SCRATCH_VEL, pieceMass, p.temperature, rng);
                    }
                }
            }

            if (p.mass > 0 && !Double.isNaN(p.pos.x)) {
                recordSmoothHermiteTrail(p, i, dtSim);
            } else {
                p.mass = 0;
                p.clearTrail();
                CRATER_MAP.remove(p);
                SATURN_RINGS_SPAWNED.remove(p);
                anyRemoved = true;
            }
        }

        for (int i = fCount - 1; i >= 0; i--) {
            int base = i * GPU_DEBRIS_STRIDE;
            float status = mappedDebrisBuffer.getFloat(base + 44);
            int sx = mappedDebrisBuffer.getInt(base), sy = mappedDebrisBuffer.getInt(base + 4), sz = mappedDebrisBuffer.getInt(base + 8);
            double wx = refOriginX + fromSectorAndLocal(sx, mappedDebrisBuffer.getFloat(base + 16));
            double wy = refOriginY + fromSectorAndLocal(sy, mappedDebrisBuffer.getFloat(base + 20));
            double wz = refOriginZ + fromSectorAndLocal(sz, mappedDebrisBuffer.getFloat(base + 24));
            double dSize = mappedDebrisBuffer.getFloat(base + 28);
            double vx = mappedDebrisBuffer.getFloat(base + 32), vy = mappedDebrisBuffer.getFloat(base + 36), vz = mappedDebrisBuffer.getFloat(base + 40);
            double dMass = mappedDebrisBuffer.getFloat(base + 48), dTemp = mappedDebrisBuffer.getFloat(base + 52), dAngle = mappedDebrisBuffer.getFloat(base + 56);
            float grace = mappedDebrisBuffer.getFloat(base + 60);

            if (status <= 0.0f || Float.isNaN(status)) {
                if (status <= -5.0f && !Float.isNaN(status)) {
                    int hitIdx = Math.round(-status - 10.0f);
                    if (hitIdx >= 0 && hitIdx < pSize) absorbDebrisIntoPlanet(planets.get(hitIdx), wx, wy, wz, vx, vy, vz, dSize, dMass, dTemp, true);
                }
                SpaceEngine.Fragment removed = fragments.remove(i);
                if (removed != null) removed.life = -1.0;
            } else {
                SpaceEngine.Fragment f = fragments.get(i);
                f.pos.set(wx, wy, wz);
                f.size = dSize;
                f.vel.set(vx, vy, vz);
                f.life = 999990.0 + grace;
                f.temperature = dTemp;
                f.angle = dAngle;
            }
        }

        for (int i = ptCount - 1; i >= 0; i--) {
            int base = (fCount + i) * GPU_DEBRIS_STRIDE;
            float status = mappedDebrisBuffer.getFloat(base + 44);
            int sx = mappedDebrisBuffer.getInt(base), sy = mappedDebrisBuffer.getInt(base + 4), sz = mappedDebrisBuffer.getInt(base + 8);
            double wx = refOriginX + fromSectorAndLocal(sx, mappedDebrisBuffer.getFloat(base + 16));
            double wy = refOriginY + fromSectorAndLocal(sy, mappedDebrisBuffer.getFloat(base + 20));
            double wz = refOriginZ + fromSectorAndLocal(sz, mappedDebrisBuffer.getFloat(base + 24));
            double dSize = mappedDebrisBuffer.getFloat(base + 28);
            double vx = mappedDebrisBuffer.getFloat(base + 32), vy = mappedDebrisBuffer.getFloat(base + 36), vz = mappedDebrisBuffer.getFloat(base + 40);
            double dMass = mappedDebrisBuffer.getFloat(base + 48), dTemp = mappedDebrisBuffer.getFloat(base + 52);
            float grace = mappedDebrisBuffer.getFloat(base + 60);

            if (status <= 0.0f || Float.isNaN(status)) {
                if (status <= -5.0f && !Float.isNaN(status)) {
                    int hitIdx = Math.round(-status - 10.0f);
                    if (hitIdx >= 0 && hitIdx < pSize) absorbDebrisIntoPlanet(planets.get(hitIdx), wx, wy, wz, vx, vy, vz, dSize, dMass, dTemp, false);
                }
                SpaceEngine.Particle removed = particles.remove(i);
                if (removed != null) removed.life = -1.0;
            } else {
                SpaceEngine.Particle pt = particles.get(i);
                pt.pos.set(wx, wy, wz);
                pt.size = dSize;
                pt.vel.set(vx, vy, vz);
                pt.life = 999990.0 + grace;
                pt.maxLife = 1000000.0;
                pt.temperature = dTemp;
            }
        }

        if (anyRemoved) {
            for (int i = planets.size() - 1; i >= 0; i--) {
                if (planets.get(i).mass <= 0) {
                    planets.get(i).clearTrail();
                    CRATER_MAP.remove(planets.get(i));
                    SATURN_RINGS_SPAWNED.remove(planets.get(i));
                    planets.remove(i);
                }
            }
            SpaceEngine.planetsNeedSort = true;
        }
    }

    private static void recordSmoothHermiteTrail(SpaceEngine.Planet p, int idx, double dtSim) {
        if (p.trailCount == 0) { p.addTrailPoint(p.pos); return; }

        double p0x = PREV_POS_X[idx], p0y = PREV_POS_Y[idx], p0z = PREV_POS_Z[idx];
        double p1x = p.pos.x, p1y = p.pos.y, p1z = p.pos.z;
        double dx = p1x - p0x, dy = p1y - p0y, dz = p1z - p0z;
        double stepDist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        double minStep = Math.max(0.05, Math.max(p.radius * 0.25, stepDist * 0.14));
        Vector3d lastNode = p.getTrailPoint(p.trailCount - 1);
        if (lastNode.distanceSquared(p.pos) <= minStep * minStep) return;

        int subdivisions = (int) Math.min(16, Math.max(1, Math.ceil(stepDist / minStep)));
        if (subdivisions <= 1 || Math.abs(dtSim) < 1e-6) { p.addTrailPoint(p.pos); return; }

        double v0Len = Math.sqrt(PREV_VEL_X[idx] * PREV_VEL_X[idx] + PREV_VEL_Y[idx] * PREV_VEL_Y[idx] + PREV_VEL_Z[idx] * PREV_VEL_Z[idx]);
        double maxVel = Math.max(Math.max(v0Len, p.vel.length()), 1e-6);
        double clampedDt = Math.copySign(Math.min(Math.abs(dtSim), (stepDist * 1.15) / maxVel), dtSim);

        double m0x = PREV_VEL_X[idx] * clampedDt, m0y = PREV_VEL_Y[idx] * clampedDt, m0z = PREV_VEL_Z[idx] * clampedDt;
        double m1x = p.vel.x * clampedDt, m1y = p.vel.y * clampedDt, m1z = p.vel.z * clampedDt;

        for (int s = 1; s <= subdivisions; s++) {
            double t = (double) s / subdivisions, t2 = t * t, t3 = t2 * t;
            double h00 = 2.0 * t3 - 3.0 * t2 + 1.0, h10 = t3 - 2.0 * t2 + t;
            double h01 = -2.0 * t3 + 3.0 * t2, h11 = t3 - t2;

            SCRATCH_HERMITE.set(
                    h00 * p0x + h10 * m0x + h01 * p1x + h11 * m1x,
                    h00 * p0y + h10 * m0y + h01 * p1y + h11 * m1y,
                    h00 * p0z + h10 * m0z + h01 * p1z + h11 * m1z
            );
            p.addTrailPoint(SCRATCH_HERMITE);
        }
    }

    private static void spawnImpactDebrisOrGas(
            SpaceEngine.Planet source, boolean isIcyBody, double parentTotalMass, double parentOrigRadius,
            Vector3d pPos, Vector3d pVel, double pieceMass, double tempC, ThreadLocalRandom rng) {
        if ("Hole".equals(source.type)) return;

        boolean spawnAsSolidBlock = source.isRigid || (isIcyBody && rng.nextBoolean());
        int maxPerType = Math.max(16, getEffectiveDebrisLimit() / 2);

        double massRatioCbrt = Math.cbrt(Math.max(1e-8, pieceMass / Math.max(parentTotalMass, 1e-6)));
        double exactVolRadius = Math.max(0.025, Math.min(parentOrigRadius * 0.36, parentOrigRadius * massRatioCbrt));

        if (spawnAsSolidBlock) {
            if (SpaceEngine.fragments.size() < maxPerType) {
                SpaceEngine.Fragment f = FRAGMENT_POOL[fragPoolCursor];
                if (f == null) { f = new SpaceEngine.Fragment(); FRAGMENT_POOL[fragPoolCursor] = f; }
                fragPoolCursor = (fragPoolCursor + 1) & DEBRIS_POOL_MASK;
                boolean alreadyActive = (f.life > 0.0);
                float px = rng.nextInt(16), py = rng.nextInt(16);
                double blockTemp = isIcyBody ? Math.min(-60.0, tempC) : tempC;
                f.set(pPos, pVel, exactVolRadius, 999990.30, pieceMass, blockTemp,
                        SpaceEngine.TEX_FRAGMENT, px / 16.0f, py / 16.0f, (px + 1.0f) / 16.0f, (py + 1.0f) / 16.0f);
                if (!alreadyActive) SpaceEngine.fragments.add(f);
            }
        } else {
            if (SpaceEngine.particles.size() < maxPerType) {
                SpaceEngine.Particle pt = PARTICLE_POOL[partPoolCursor];
                if (pt == null) { pt = new SpaceEngine.Particle(); PARTICLE_POOL[partPoolCursor] = pt; }
                partPoolCursor = (partPoolCursor + 1) & DEBRIS_POOL_MASK;
                boolean alreadyActive = (pt.life > 0.0);
                int pr = Math.min(255, Math.max(40, source.rColor + rng.nextInt(30) - 15));
                int pg = Math.min(255, Math.max(40, source.gColor + rng.nextInt(30) - 15));
                int pb = Math.min(255, Math.max(40, source.bColor + rng.nextInt(30) - 15));
                pt.set(pPos, pVel, Math.max(0.05, exactVolRadius * 1.25), 999990.30, pr, pg, pb, 1, pieceMass, tempC);
                if (!alreadyActive) SpaceEngine.particles.add(pt);
            }
        }
    }

    private static void spawnPooledDebris(
            SpaceEngine.Planet source, double parentTotalMass, double parentOrigRadius,
            Vector3d pPos, Vector3d pVel, double pieceMass, double tempC, ThreadLocalRandom rng) {
        boolean isIcy = "Uranus".equalsIgnoreCase(source.name) || "Enceladus".equalsIgnoreCase(source.name)
                || "Titan".equalsIgnoreCase(source.name) || source.temperature < -15.0;
        spawnImpactDebrisOrGas(source, isIcy, parentTotalMass, parentOrigRadius, pPos, pVel, pieceMass, tempC, rng);
    }

    private static int findMemoryTypeWithFallback(int typeBits, int preferredProps, int fallbackProps) {
        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.calloc();
        vkGetPhysicalDeviceMemoryProperties(SBoxVulkanCore.physicalDevice, memProps);
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0 && (memProps.memoryTypes(i).propertyFlags() & preferredProps) == preferredProps) {
                memProps.free(); return i;
            }
        }
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0 && (memProps.memoryTypes(i).propertyFlags() & fallbackProps) == fallbackProps) {
                memProps.free(); return i;
            }
        }
        memProps.free();
        throw new RuntimeException("Не найден подходящий тип памяти Vulkan для PhysicsEngine!");
    }

    private static void absorbDebrisIntoPlanet(
            SpaceEngine.Planet pl,
            double dPosX, double dPosY, double dPosZ,
            double dVelX, double dVelY, double dVelZ,
            double dSize, double dMass, double dTemp, boolean isRigidDebris) {
        if (pl == null || pl.mass <= 0.0 || dMass <= 0.0) return;

        double oldMass = pl.mass, newMass = oldMass + dMass;
        double rx = dPosX - pl.pos.x, ry = dPosY - pl.pos.y, rz = dPosZ - pl.pos.z;
        double rvx = dVelX - pl.vel.x, rvy = dVelY - pl.vel.y, rvz = dVelZ - pl.vel.z;
        double relSpeedSq = rvx * rvx + rvy * rvy + rvz * rvz;

        pl.vel.x = (pl.vel.x * oldMass + dVelX * dMass) / newMass;
        pl.vel.y = (pl.vel.y * oldMass + dVelY * dMass) / newMass;
        pl.vel.z = (pl.vel.z * oldMass + dVelZ * dMass) / newMass;

        if (!"Hole".equals(pl.type)) {
            double impLx = dMass * (ry * rvz - rz * rvy);
            double impLy = dMass * (rz * rvx - rx * rvz);
            double impLz = dMass * (rx * rvy - ry * rvx);

            double tiltRad = Math.toRadians(pl.tilt);
            double safeRadius = Math.max(0.05, pl.radius);
            double inertiaFactor = "Star".equals(pl.type) ? 0.10 : ("Mars".equalsIgnoreCase(pl.name) ? 0.366 : (pl.isRigid ? 0.38 : 0.23));
            double inertiaOld = inertiaFactor * oldMass * safeRadius * safeRadius;
            double signRot = (pl.rotationPeriod < 0.0) ? -1.0 : 1.0;
            double safePeriodHours = Math.max(0.0001, Math.abs(pl.rotationPeriod));
            double omegaOld = signRot * (2.0 * Math.PI) / (safePeriodHours * 3600.0);

            double oldLx = inertiaOld * omegaOld * (-Math.sin(tiltRad));
            double oldLy = inertiaOld * omegaOld * Math.cos(tiltRad);

            double totLx = oldLx + impLx, totLy = oldLy + impLy, totLz = impLz;

            pl.targetRadius = Math.max(0.02, pl.targetRadius * Math.cbrt(newMass / oldMass));
            pl.radius = pl.targetRadius;

            double inertiaNew = Math.max(1e-12, inertiaFactor * newMass * pl.radius * pl.radius);
            double totalSpinMag = Math.sqrt(totLx * totLx + totLy * totLy + totLz * totLz);
            double newSign = (totLx * (-Math.sin(tiltRad)) + totLy * Math.cos(tiltRad)) >= 0.0 ? 1.0 : -1.0;
            double omegaNew = newSign * (totalSpinMag / inertiaNew);

            if (totalSpinMag > 1e-12) {
                pl.tilt = Math.toDegrees(Math.atan2(-totLx * newSign, totLy * newSign));
            }
            if (Math.abs(omegaNew) > 1e-12) {
                double newPeriodHours = (2.0 * Math.PI) / (omegaNew * 3600.0);
                if (Math.abs(newPeriodHours) < 0.0005) newPeriodHours = Math.copySign(0.0005, newPeriodHours);
                pl.rotationPeriod = newPeriodHours;
            } else {
                pl.rotationPeriod = 999999999.0;
            }

            double massFraction = dMass / newMass;
            double impactHeating = (relSpeedSq * 35.0 + Math.max(0.0, dTemp - pl.temperature)) * massFraction;
            pl.temperature = Math.min(35000.0, pl.temperature + impactHeating);

            if (isRigidDebris && pl.isRigid) {
                double effCraterSize = Math.max(dSize * 2.4, pl.radius * 0.075);
                double fragHeat = Math.max(dTemp, 1150.0 + relSpeedSq * 55.0);
                registerPlanetImpactCrater(pl, rx, ry, rz, effCraterSize, fragHeat, 0.08, true);
            }
        }
        pl.mass = newMass;
    }
}