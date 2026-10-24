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
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.lwjgl.vulkan.VK10.*;

public class PhysicsEngine {

    public static final double G = 0.00019926275949971405;
    public static final double MAX_C_BLOCKS = 237.93052222222222;
    public static final double MAX_C_SQ = MAX_C_BLOCKS * MAX_C_BLOCKS;

    private static boolean gpuPhysicsReady = false;
    private static long descriptorSetLayout = VK_NULL_HANDLE;
    private static long descriptorPool = VK_NULL_HANDLE;
    private static long descriptorSet = VK_NULL_HANDLE;
    private static long pipelineLayout = VK_NULL_HANDLE;
    private static long computePipeline = VK_NULL_HANDLE;

    private static long physicsPlanetBuffer = VK_NULL_HANDLE;
    private static long physicsPlanetMemory = VK_NULL_HANDLE;
    private static ByteBuffer mappedPlanetBuffer;

    private static long physicsDebrisBuffer = VK_NULL_HANDLE;
    private static long physicsDebrisMemory = VK_NULL_HANDLE;
    private static ByteBuffer mappedDebrisBuffer;

    private static VkCommandBuffer physicsCmdBuffer;
    private static long physicsFence = VK_NULL_HANDLE;

    private static final int MAX_GPU_PLANETS = 64;
    private static final int GPU_PLANET_STRIDE = 128; // 4 * dvec4
    private static final int GPU_PLANET_BUFFER_SIZE = MAX_GPU_PLANETS * GPU_PLANET_STRIDE;

    private static final int MAX_GPU_DEBRIS = 512;
    private static final int GPU_DEBRIS_STRIDE = 96;  // 3 * dvec4
    private static final int GPU_DEBRIS_BUFFER_SIZE = MAX_GPU_DEBRIS * GPU_DEBRIS_STRIDE;

    private static final SpaceEngine.Fragment[] FRAGMENT_POOL = new SpaceEngine.Fragment[MAX_GPU_DEBRIS];
    private static final SpaceEngine.Particle[] PARTICLE_POOL = new SpaceEngine.Particle[MAX_GPU_DEBRIS];
    private static int fragPoolCursor = 0;
    private static int partPoolCursor = 0;

    private static final Vector3d SCRATCH_PULL = new Vector3d();
    private static final Vector3d SCRATCH_POS = new Vector3d();
    private static final Vector3d SCRATCH_VEL = new Vector3d();
    private static final Vector3d SCRATCH_SPIN = new Vector3d();
    private static final Vector3d SCRATCH_OFFSET = new Vector3d();

    static {
        for (int i = 0; i < MAX_GPU_DEBRIS; i++) {
            FRAGMENT_POOL[i] = new SpaceEngine.Fragment();
            PARTICLE_POOL[i] = new SpaceEngine.Particle();
        }
    }

    public static void init() {
        if (gpuPhysicsReady || !SBoxVulkanCore.supportsFp64 || SBoxVulkanCore.logicalDevice == null) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice device = SBoxVulkanCore.logicalDevice;

            String glsl =
                    "#version 450\n" +
                            "#extension GL_ARB_gpu_shader_fp64 : enable\n" +
                            "layout (local_size_x = 64) in;\n" +
                            "\n" +
                            "struct GpuPlanet {\n" +
                            "    dvec4 posAndMass;   // xyz = pos, w = mass\n" +
                            "    dvec4 velAndRadius; // xyz = vel, w = radius\n" +
                            "    dvec4 rotAndTemp;   // x = currentAngleDeg, y = rotationPeriod, z = targetRadius, w = temperatureC\n" +
                            "    dvec4 stateAndShed; // x = objType (0=Planet,1=Star,2=Hole), y = packedRigidAndTilt, z = shedMassOut, w = eventCode\n" +
                            "};\n" +
                            "\n" +
                            "struct GpuDebris {\n" +
                            "    dvec4 posAndSize;      // xyz = pos, w = size\n" +
                            "    dvec4 velAndLife;      // xyz = vel, w = remainingLife\n" +
                            "    dvec4 massTempAndType; // x = mass, y = tempC, z = angleDeg, w = isFragment\n" +
                            "};\n" +
                            "\n" +
                            "layout (std430, binding = 0) buffer PlanetPhysicsBuffer {\n" +
                            "    GpuPlanet planets[64];\n" +
                            "} pb;\n" +
                            "\n" +
                            "layout (std430, binding = 1) buffer DebrisPhysicsBuffer {\n" +
                            "    GpuDebris debris[512];\n" +
                            "} db;\n" +
                            "\n" +
                            "layout (push_constant) uniform PushConstants {\n" +
                            "    double subDt;    // 0..7\n" +
                            "    double dtReal;   // 8..15\n" +
                            "    int planetCount; // 16..19\n" +
                            "    int debrisCount; // 20..23\n" +
                            "    int subSteps;    // 24..27\n" +
                            "    int flags;       // 28..31\n" +
                            "} pc;\n" +
                            "\n" +
                            "const double G = 0.00019926275949971405LF;\n" +
                            "const double MAX_C = 237.93052222222222LF;\n" +
                            "const double MAX_C_SQ = 56610.93340548207LF;\n" +
                            "const double PI_D = 3.14159265358979323846LF;\n" +
                            "const double DEG_TO_RAD = 0.017453292519943295LF;\n" +
                            "\n" +
                            "shared dvec4 sPosMass[64];\n" +
                            "shared dvec4 sVelRad[64];\n" +
                            "shared dvec4 sTempType[64]; // x = tempC, y = objType, z = packedRigidAndTilt, w = currentAngleDeg\n" +
                            "\n" +
                            "double cbrt64(double v) {\n" +
                            "    if (isnan(v) || isinf(v) || !(v > 1e-12LF)) return 0.01LF;\n" +
                            "    double r = double(pow(float(v), 0.33333334));\n" +
                            "    r = (2.0LF * r + v / max(r * r, 1e-18LF)) / 3.0LF;\n" +
                            "    return max(r, 0.01LF);\n" +
                            "}\n" +
                            "\n" +
                            "// Безопасное возведение доли массы в степень без генерации NaN и схлопывания в 0\n" +
                            "double safeMassScale(double ratioM, bool rigid) {\n" +
                            "    double clamped = clamp(ratioM, 1e-4LF, 1.0LF);\n" +
                            "    float expF = rigid ? (1.0 / 2.2) : (1.0 / 3.0);\n" +
                            "    return max(double(pow(float(clamped), expF)), 0.05LF);\n" +
                            "}\n" +
                            "\n" +
                            "// Точный 3D-радиус повернутого куба (OBB Support Function) вдоль вектора столкновения u\n" +
                            "double cubeSupportRadius(dvec3 unitDir, double halfExt, double tiltRad, double rotRad) {\n" +
                            "    float ct = cos(float(-tiltRad)), st = sin(float(-tiltRad));\n" +
                            "    float cr = cos(float(-rotRad)),  sr = sin(float(-rotRad));\n" +
                            "    vec3 u = vec3(unitDir);\n" +
                            "    vec3 uz = vec3(ct * u.x - st * u.y, st * u.x + ct * u.y, u.z);\n" +
                            "    vec3 ul = abs(vec3(cr * uz.x + sr * uz.z, uz.y, -sr * uz.x + cr * uz.z));\n" +
                            "    float maxComp = max(max(ul.x, ul.y), max(ul.z, 0.57735026));\n" +
                            "    return halfExt / double(maxComp);\n" +
                            "}\n" +
                            "\n" +
                            "// Честное дифференциальное уравнение Стефана-Больцмана в вакууме: dT/dt = k * (T_eq^4 - T^4)\n" +
                            "double stepStefanBoltzmann(double currTempC, double incomingRadT4, double thermalInertia, double dt) {\n" +
                            "    double eqTempK = sqrt(sqrt(max(incomingRadT4, 53.1441LF)));\n" +
                            "    double currTempK = clamp(currTempC + 273.15LF, 2.7LF, 100000.0LF);\n" +
                            "    double tNorm = currTempK * 0.001LF;\n" +
                            "    double eqNorm = eqTempK * 0.001LF;\n" +
                            "    double t4 = (tNorm * tNorm) * (tNorm * tNorm);\n" +
                            "    double eq4 = (eqNorm * eqNorm) * (eqNorm * eqNorm);\n" +
                            "    double dT = (eq4 - t4) * 95.0LF * thermalInertia * abs(dt);\n" +
                            "    if (eqTempK > currTempK) {\n" +
                            "        currTempK = min(eqTempK, currTempK + max(0.0LF, dT));\n" +
                            "    } else {\n" +
                            "        currTempK = max(eqTempK, currTempK + min(0.0LF, dT));\n" +
                            "    }\n" +
                            "    return currTempK - 273.15LF;\n" +
                            "}\n" +
                            "\n" +
                            "dvec3 calcAcceleration(uint selfIdx, dvec3 posI, dvec3 velI, double radI, double massI, int n, bool gravOn) {\n" +
                            "    if (!gravOn || !(massI > 0.0LF)) return dvec3(0.0LF);\n" +
                            "    dvec3 acc = dvec3(0.0LF);\n" +
                            "    const double TWO_G_OVER_C2 = 7.039726890892425e-9LF;\n" +
                            "    for (int j = 0; j < n; j++) {\n" +
                            "        if (uint(j) == selfIdx) continue;\n" +
                            "        dvec4 pmJ = sPosMass[j];\n" +
                            "        double massJ = pmJ.w;\n" +
                            "        if (!(massJ > 0.0LF)) continue;\n" +
                            "        dvec4 vrJ = sVelRad[j];\n" +
                            "        dvec3 d = pmJ.xyz - posI;\n" +
                            "        double distSq = dot(d, d);\n" +
                            "        double dist = sqrt(max(distSq, 1e-8LF));\n" +
                            "        double sumRad = max(radI, 0.01LF) + max(vrJ.w, 0.01LF);\n" +
                            "        double effDist = max(dist, max(sumRad * 0.5LF, 0.1LF));\n" +
                            "        dvec3 dir = d / dist;\n" +
                            "        dvec3 vRel = velI - vrJ.xyz;\n" +
                            "        double betaSq = clamp(dot(vRel, vRel) / MAX_C_SQ, 0.0LF, 1.0LF);\n" +
                            "        double schRatio = clamp(TWO_G_OVER_C2 * ((massI + massJ) / effDist), 0.0LF, 3.0LF);\n" +
                            "        double relFactor = 1.0LF + 3.0LF * betaSq + schRatio;\n" +
                            "        double f = (G * massJ / (effDist * effDist)) * relFactor;\n" +
                            "        acc += dir * f;\n" +
                            "    }\n" +
                            "    return acc;\n" +
                            "}\n" +
                            "\n" +
                            "void main() {\n" +
                            "    uint localIdx = gl_LocalInvocationID.x;\n" +
                            "    int n = clamp(pc.planetCount, 0, 64);\n" +
                            "    bool gravOn = (pc.flags & 1) != 0;\n" +
                            "    bool rocheOn = (pc.flags & 2) != 0;\n" +
                            "    bool centrifOn = (pc.flags & 4) != 0;\n" +
                            "    bool collOn = (pc.flags & 8) != 0;\n" +
                            "\n" +
                            "    if (int(localIdx) < n) {\n" +
                            "        sPosMass[localIdx] = pb.planets[localIdx].posAndMass;\n" +
                            "        sVelRad[localIdx] = pb.planets[localIdx].velAndRadius;\n" +
                            "        sTempType[localIdx] = dvec4(pb.planets[localIdx].rotAndTemp.w, pb.planets[localIdx].stateAndShed.x, pb.planets[localIdx].stateAndShed.y, pb.planets[localIdx].rotAndTemp.x);\n" +
                            "    } else {\n" +
                            "        sPosMass[localIdx] = dvec4(0.0LF);\n" +
                            "        sVelRad[localIdx] = dvec4(0.0LF, 0.0LF, 0.0LF, 1.0LF);\n" +
                            "        sTempType[localIdx] = dvec4(0.0LF);\n" +
                            "    }\n" +
                            "    barrier();\n" +
                            "\n" +
                            "    // WORKGROUPS 1..8: ФИЗИКА ГАЗА И ОСКОЛКОВ НА GPU\n" +
                            "    if (gl_WorkGroupID.x > 0u) {\n" +
                            "        uint dIdx = (gl_WorkGroupID.x - 1u) * 64u + localIdx;\n" +
                            "        if (int(dIdx) >= pc.debrisCount) return;\n" +
                            "\n" +
                            "        dvec3 dPos = db.debris[dIdx].posAndSize.xyz;\n" +
                            "        double dSize = max(db.debris[dIdx].posAndSize.w, 0.01LF);\n" +
                            "        dvec3 dVel = db.debris[dIdx].velAndLife.xyz;\n" +
                            "        double dLife = db.debris[dIdx].velAndLife.w - pc.dtReal;\n" +
                            "        double dMass = db.debris[dIdx].massTempAndType.x;\n" +
                            "        double dTemp = db.debris[dIdx].massTempAndType.y;\n" +
                            "        double dAngle = db.debris[dIdx].massTempAndType.z;\n" +
                            "        double isFrag = db.debris[dIdx].massTempAndType.w;\n" +
                            "\n" +
                            "        if (dLife <= 0.0LF) {\n" +
                            "            db.debris[dIdx].velAndLife.w = -1.0LF;\n" +
                            "            return;\n" +
                            "        }\n" +
                            "\n" +
                            "        double totalSimDt = pc.subDt * double(pc.subSteps);\n" +
                            "        int dSteps = min(pc.subSteps, 16);\n" +
                            "        double dStepDt = totalSimDt / double(max(dSteps, 1));\n" +
                            "        double radFluxT4 = 53.1441LF;\n" +
                            "\n" +
                            "        for (int s = 0; s < dSteps; s++) {\n" +
                            "            dvec3 dAcc = dvec3(0.0LF);\n" +
                            "            for (int j = 0; j < n; j++) {\n" +
                            "                double mJ = sPosMass[j].w;\n" +
                            "                if (!(mJ > 0.0LF)) continue;\n" +
                            "                dvec3 toP = sPosMass[j].xyz - dPos;\n" +
                            "                double dist = max(length(toP), 1e-4LF);\n" +
                            "                double rJ = max(sVelRad[j].w, 0.01LF);\n" +
                            "                double typeJ = sTempType[j].y;\n" +
                            "\n" +
                            "                if (collOn && dist < rJ) {\n" +
                            "                    db.debris[dIdx].velAndLife.w = -1.0LF;\n" +
                            "                    return;\n" +
                            "                }\n" +
                            "                if (gravOn) {\n" +
                            "                    double effD = max(dist, rJ * 0.5LF);\n" +
                            "                    dvec3 dir = toP / dist;\n" +
                            "                    double f = (G * mJ) / (effD * effD);\n" +
                            "                    if (typeJ > 1.5LF && dist < rJ * 12.0LF) {\n" +
                            "                        f *= (1.0LF + 1.5LF * (rJ / max(dist, rJ)));\n" +
                            "                        dTemp = max(dTemp, 1200.0LF + 3800.0LF * (rJ * 4.0LF / max(dist, rJ)));\n" +
                            "                    }\n" +
                            "                    dAcc += dir * f;\n" +
                            "                }\n" +
                            "                if (s == 0) {\n" +
                            "                    double tJK = max(2.7LF, sTempType[j].x + 273.15LF);\n" +
                            "                    if (tJK > 150.0LF) {\n" +
                            "                        double geom = (rJ * 0.72LF) / max(dist, rJ);\n" +
                            "                        double t2 = tJK * tJK;\n" +
                            "                        radFluxT4 += (t2 * t2) * (geom * geom);\n" +
                            "                    }\n" +
                            "                }\n" +
                            "            }\n" +
                            "            dVel += dAcc * dStepDt;\n" +
                            "            double spSq = dot(dVel, dVel);\n" +
                            "            if (spSq > MAX_C_SQ) dVel *= (MAX_C / sqrt(spSq));\n" +
                            "            dPos += dVel * dStepDt;\n" +
                            "        }\n" +
                            "\n" +
                            "        if (isFrag < 0.5LF) {\n" +
                            "            dSize *= (1.0LF + 0.14LF * pc.dtReal);\n" +
                            "        } else {\n" +
                            "            dAngle = mod(dAngle + 140.0LF * pc.dtReal, 360.0LF);\n" +
                            "        }\n" +
                            "\n" +
                            "        // Нелинейный закон охлаждения Стефана-Больцмана T^4 для обломков и плазмы!\n" +
                            "        dTemp = stepStefanBoltzmann(dTemp, radFluxT4, 0.85LF, pc.dtReal);\n" +
                            "\n" +
                            "        db.debris[dIdx].posAndSize = dvec4(dPos, dSize);\n" +
                            "        db.debris[dIdx].velAndLife = dvec4(dVel, dLife);\n" +
                            "        db.debris[dIdx].massTempAndType = dvec4(dMass, dTemp, dAngle, isFrag);\n" +
                            "        return;\n" +
                            "    }\n" +
                            "\n" +
                            "    // WORKGROUP 0: ФИЗИКА ПЛАНЕТ, ЗВЁЗД И ЧЁРНЫХ ДЫР\n" +
                            "    uint idx = localIdx;\n" +
                            "    bool isValid = int(idx) < n;\n" +
                            "\n" +
                            "    dvec3 pos = sPosMass[idx].xyz;\n" +
                            "    double mass = sPosMass[idx].w;\n" +
                            "    dvec3 vel = sVelRad[idx].xyz;\n" +
                            "    double rad = max(sVelRad[idx].w, 0.01LF);\n" +
                            "    double angle = sTempType[idx].w;\n" +
                            "    double rotPeriod = isValid ? pb.planets[idx].rotAndTemp.y : 0.0LF;\n" +
                            "    double targetRad = isValid ? max(pb.planets[idx].rotAndTemp.z, 0.01LF) : rad;\n" +
                            "    double tempC = sTempType[idx].x;\n" +
                            "    double objType = sTempType[idx].y;\n" +
                            "    double packedRT = sTempType[idx].z;\n" +
                            "    bool isRigid = (packedRT >= 500.0LF);\n" +
                            "    double tiltDeg = isRigid ? (packedRT - 1000.0LF) : packedRT;\n" +
                            "    double tiltRad = tiltDeg * DEG_TO_RAD;\n" +
                            "    double totalShedMass = 0.0LF;\n" +
                            "    double eventCode = -1.0LF;\n" +
                            "\n" +
                            "    dvec3 acc = isValid ? calcAcceleration(idx, pos, vel, rad, mass, n, gravOn) : dvec3(0.0LF);\n" +
                            "    double dt = pc.subDt;\n" +
                            "    double absDt = abs(dt);\n" +
                            "    double halfDt = 0.5LF * dt;\n" +
                            "\n" +
                            "    for (int step = 0; step < pc.subSteps; step++) {\n" +
                            "        if (isValid && mass > 0.0LF) {\n" +
                            "            vel += acc * halfDt;\n" +
                            "            double spSq = dot(vel, vel);\n" +
                            "            if (spSq > MAX_C_SQ) vel *= (MAX_C / sqrt(spSq));\n" +
                            "            pos += vel * dt;\n" +
                            "            if (abs(rotPeriod) > 0.0001LF) {\n" +
                            "                double rotSpeed = 360.0LF / (rotPeriod * 3600.0LF);\n" +
                            "                angle = mod(angle + rotSpeed * dt, 360.0LF);\n" +
                            "            }\n" +
                            "            if (abs(rad - targetRad) > 0.0001LF) {\n" +
                            "                rad = max(0.01LF, rad + (targetRad - rad) * min(1.0LF, 15.0LF * absDt));\n" +
                            "            }\n" +
                            "        }\n" +
                            "        sPosMass[idx] = dvec4(pos, mass);\n" +
                            "        sVelRad[idx] = dvec4(vel, rad);\n" +
                            "        sTempType[idx].w = angle;\n" +
                            "        barrier();\n" +
                            "\n" +
                            "        dvec3 newAcc = isValid ? calcAcceleration(idx, pos, vel, rad, mass, n, gravOn) : dvec3(0.0LF);\n" +
                            "        if (isValid && mass > 0.0LF) {\n" +
                            "            vel += newAcc * halfDt;\n" +
                            "            double spSq2 = dot(vel, vel);\n" +
                            "            if (spSq2 > MAX_C_SQ) vel *= (MAX_C / sqrt(spSq2));\n" +
                            "            acc = newAcc;\n" +
                            "\n" +
                            "            // 1. ЦЕНТРОБЕЖНАЯ СИЛА (с сохранением знака периода для ретроградных планет)\n" +
                            "            if (centrifOn && objType < 1.5LF) {\n" +
                            "                double signRot = (rotPeriod < 0.0LF) ? -1.0LF : 1.0LF;\n" +
                            "                double safeRot = max(0.0001LF, abs(rotPeriod));\n" +
                            "                double wCurr = (2.0LF * PI_D) / (safeRot * 3600.0LF);\n" +
                            "                double wCrit = sqrt((G * mass) / max(rad * rad * rad, 1e-6LF));\n" +
                            "                if (wCurr > wCrit) {\n" +
                            "                    double excess = min((wCurr - wCrit) / max(wCrit, 1e-6LF), 2.0LF);\n" +
                            "                    safeRot *= (1.0LF + excess * 0.8LF * absDt);\n" +
                            "                    rotPeriod = signRot * safeRot;\n" +
                            "                    double shed = min(mass * 0.2LF, mass * 0.5LF * absDt * excess);\n" +
                            "                    if (shed > 1e-6LF && (mass - shed) > 1e-6LF) {\n" +
                            "                        double ratioM = (mass - shed) / mass;\n" +
                            "                        mass -= shed;\n" +
                            "                        totalShedMass += shed;\n" +
                            "                        targetRad = max(0.02LF, targetRad * safeMassScale(ratioM, isRigid));\n" +
                            "                        rad = targetRad;\n" +
                            "                        tempC += excess * 180.0LF * absDt;\n" +
                            "                    }\n" +
                            "                }\n" +
                            "            }\n" +
                            "\n" +
                            "            // 2. КУБИЧЕСКИЕ 3D OBB-СТОЛКНОВЕНИЯ, ПРЕДЕЛ РОША И СТЕФАН-БОЛЬЦМАН\n" +
                            "            double incomingRadT4 = 53.1441LF;\n" +
                            "            double rhoSelf = mass / max((4.0LF / 3.0LF) * PI_D * rad * rad * rad, 1e-6LF);\n" +
                            "            double rotRadSelf = angle * DEG_TO_RAD;\n" +
                            "\n" +
                            "            for (int j = 0; j < n; j++) {\n" +
                            "                if (uint(j) == idx || !(mass > 0.0LF)) continue;\n" +
                            "                dvec3 posJ = sPosMass[j].xyz;\n" +
                            "                double massJ = sPosMass[j].w;\n" +
                            "                if (!(massJ > 0.0LF)) continue;\n" +
                            "                dvec3 velJ = sVelRad[j].xyz;\n" +
                            "                double radJ = max(sVelRad[j].w, 0.01LF);\n" +
                            "                double tempJC = sTempType[j].x;\n" +
                            "                double typeJ = sTempType[j].y;\n" +
                            "                double packedJ = sTempType[j].z;\n" +
                            "                double tiltRadJ = ((packedJ >= 500.0LF) ? (packedJ - 1000.0LF) : packedJ) * DEG_TO_RAD;\n" +
                            "                double rotRadJ = sTempType[j].w * DEG_TO_RAD;\n" +
                            "\n" +
                            "                dvec3 dVec = posJ - pos;\n" +
                            "                double dist = max(length(dVec), 1e-5LF);\n" +
                            "                dvec3 unitDir = dVec / dist;\n" +
                            "\n" +
                            "                // Точная 3D-граница соприкосновения двух повернутых кубов (углы, рёбра и грани!)\n" +
                            "                double rCubeSelf = cubeSupportRadius(unitDir, rad, tiltRad, rotRadSelf);\n" +
                            "                double rCubeJ = cubeSupportRadius(-unitDir, radJ, tiltRadJ, rotRadJ);\n" +
                            "                double sumCubeRad = rCubeSelf + rCubeJ;\n" +
                            "\n" +
                            "                double tempJK = max(2.7LF, tempJC + 273.15LF);\n" +
                            "                if (tempJK > 150.0LF) {\n" +
                            "                    double geom = (radJ * 0.72LF) / max(dist, radJ);\n" +
                            "                    double t2 = tempJK * tempJK;\n" +
                            "                    incomingRadT4 += (t2 * t2) * (geom * geom);\n" +
                            "                }\n" +
                            "\n" +
                            "                // Строго консервативное слияние масс без нарушения закона сохранения импульса!\n" +
                            "                if (collOn && dist < sumCubeRad) {\n" +
                            "                    bool selfBigger = (mass > massJ) || (mass == massJ && idx < uint(j));\n" +
                            "                    double smallMass = selfBigger ? massJ : mass;\n" +
                            "                    double smallRad = selfBigger ? radJ : rad;\n" +
                            "                    double overlap = max(0.0LF, sumCubeRad - dist);\n" +
                            "                    double transfer = smallMass * 2.0LF * absDt * (1.0LF + (overlap / max(0.01LF, smallRad)) * 10.0LF);\n" +
                            "                    transfer = min(transfer, smallMass);\n" +
                            "                    dvec3 vRel = vel - velJ;\n" +
                            "                    double relSpeedSq = dot(vRel, vRel);\n" +
                            "\n" +
                            "                    if (selfBigger) {\n" +
                            "                        double newMass = mass + transfer;\n" +
                            "                        // Точное сохранение импульса P_new = P_big + dm * v_small\n" +
                            "                        vel = (vel * mass + velJ * transfer) / max(newMass, 1e-9LF);\n" +
                            "                        if (objType < 1.5LF) {\n" +
                            "                            double impactHeat = (850.0LF + relSpeedSq * 140.0LF) * (transfer / max(mass, 1e-5LF));\n" +
                            "                            tempC = min(35000.0LF, tempC + max(impactHeat, 180.0LF * absDt));\n" +
                            "                        }\n" +
                            "                        mass = newMass;\n" +
                            "                        double volGain = (smallRad * smallRad * smallRad) * (transfer / max(smallMass, 1e-9LF));\n" +
                            "                        targetRad = max(0.02LF, cbrt64(targetRad * targetRad * targetRad + volGain));\n" +
                            "                    } else {\n" +
                            "                        double oldM = mass;\n" +
                            "                        mass -= transfer;\n" +
                            "                        if (objType < 1.5LF) {\n" +
                            "                            totalShedMass += transfer;\n" +
                            "                            tempC = min(35000.0LF, tempC + 1200.0LF * absDt + relSpeedSq * 25.0LF);\n" +
                            "                        }\n" +
                            "                        eventCode = double(j);\n" +
                            "                        if (mass <= 1e-6LF || rad < radJ * 0.05LF) {\n" +
                            "                            mass = 0.0LF;\n" +
                            "                            rad = 0.01LF;\n" +
                            "                            targetRad = 0.01LF;\n" +
                            "                        } else {\n" +
                            "                            // Никакого фейкового mix(vel, velJ) — скорость остатка тела сохраняется строго по Ньютону!\n" +
                            "                            targetRad = max(0.02LF, targetRad * cbrt64(mass / max(oldM, 1e-9LF)));\n" +
                            "                            rad = targetRad;\n" +
                            "                        }\n" +
                            "                    }\n" +
                            "                }\n" +
                            "\n" +
                            "                // Предел Роша и Спагеттификация\n" +
                            "                if (rocheOn && objType < 1.5LF && mass > 1e-6LF && massJ > mass * 2.0LF) {\n" +
                            "                    double rhoJ = massJ / max((4.0LF / 3.0LF) * PI_D * radJ * radJ * radJ, 1e-6LF);\n" +
                            "                    if (rhoSelf > 0.0LF && rhoJ > 0.0LF) {\n" +
                            "                        double rocheConst = isRigid ? 1.26LF : 2.44LF;\n" +
                            "                        double rocheLimit = rocheConst * radJ * cbrt64(rhoJ / rhoSelf);\n" +
                            "                        if (typeJ > 1.5LF) {\n" +
                            "                            rocheLimit = max(rocheLimit * 0.35LF, radJ * 8.5LF);\n" +
                            "                        }\n" +
                            "                        if (dist < rocheLimit) {\n" +
                            "                            double depth = clamp(1.0LF - (dist / max(rocheLimit, 1e-4LF)), 0.0LF, 1.0LF);\n" +
                            "                            double shedRate = (typeJ > 1.5LF) ? (0.35LF + depth * 2.2LF) : (0.1LF + depth * 0.9LF);\n" +
                            "                            double shed = min(mass * 0.25LF, mass * shedRate * absDt);\n" +
                            "                            if (shed > 1e-6LF) {\n" +
                            "                                if (mass - shed <= 1e-6LF) {\n" +
                            "                                    totalShedMass += mass;\n" +
                            "                                    mass = 0.0LF;\n" +
                            "                                    rad = 0.01LF;\n" +
                            "                                    targetRad = 0.01LF;\n" +
                            "                                } else {\n" +
                            "                                    double ratioM = (mass - shed) / mass;\n" +
                            "                                    mass -= shed;\n" +
                            "                                    totalShedMass += shed;\n" +
                            "                                    targetRad = max(0.02LF, targetRad * safeMassScale(ratioM, isRigid));\n" +
                            "                                    rad = targetRad;\n" +
                            "                                }\n" +
                            "                                eventCode = double(j + (typeJ > 1.5LF ? 100 : 0));\n" +
                            "                                tempC = min(35000.0LF, tempC + ((typeJ > 1.5LF ? 1800.0LF : 450.0LF) + depth * 3200.0LF) * absDt);\n" +
                            "                            }\n" +
                            "                        }\n" +
                            "                    }\n" +
                            "                }\n" +
                            "            }\n" +
                            "\n" +
                            "            // Нелинейное охлаждение/нагрев Стефана-Больцмана (T_eq^4 - T^4)\n" +
                            "            if (objType < 1.5LF && mass > 0.0LF) {\n" +
                            "                double thermalInertia = clamp(1.0LF / max(rad * 0.35LF, 0.25LF), 0.002LF, 2.0LF);\n" +
                            "                if (objType < 0.5LF || sqrt(sqrt(incomingRadT4)) > (tempC + 273.15LF)) {\n" +
                            "                    tempC = stepStefanBoltzmann(tempC, incomingRadT4, thermalInertia, absDt);\n" +
                            "                }\n" +
                            "            }\n" +
                            "        }\n" +
                            "\n" +
                            "        // Санитизация от любых NaN/Inf перед синхронизацией в shared-памяти\n" +
                            "        if (isnan(pos.x) || isinf(pos.x) || isnan(vel.x) || isinf(vel.x) || isnan(rad) || isinf(rad)) {\n" +
                            "            mass = 0.0LF;\n" +
                            "            rad = 0.01LF;\n" +
                            "            targetRad = 0.01LF;\n" +
                            "            vel = dvec3(0.0LF);\n" +
                            "        }\n" +
                            "\n" +
                            "        sPosMass[idx] = dvec4(pos, mass);\n" +
                            "        sVelRad[idx] = dvec4(vel, rad);\n" +
                            "        sTempType[idx] = dvec4(tempC, objType, packedRT, angle);\n" +
                            "        barrier();\n" +
                            "    }\n" +
                            "\n" +
                            "    if (isValid) {\n" +
                            "        pb.planets[idx].posAndMass = dvec4(pos, mass);\n" +
                            "        pb.planets[idx].velAndRadius = dvec4(vel, rad);\n" +
                            "        pb.planets[idx].rotAndTemp = dvec4(angle, rotPeriod, targetRad, tempC);\n" +
                            "        pb.planets[idx].stateAndShed = dvec4(objType, packedRT, totalShedMass, eventCode);\n" +
                            "    }\n" +
                            "}\n";

            long compiler = Shaderc.shaderc_compiler_initialize();
            long res = Shaderc.shaderc_compile_into_spv(compiler, glsl, Shaderc.shaderc_glsl_compute_shader, "physics_fp64.comp", "main", 0);
            if (Shaderc.shaderc_result_get_compilation_status(res) != Shaderc.shaderc_compilation_status_success) {
                System.err.println("[SBox Physics] Ошибка компиляции FP64 шейдера:\n" + Shaderc.shaderc_result_get_error_message(res));
                Shaderc.shaderc_result_release(res);
                Shaderc.shaderc_compiler_release(compiler);
                return;
            }
            ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(res);

            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spirv);
            LongBuffer pShaderModule = stack.mallocLong(1);
            vkCreateShaderModule(device, moduleInfo, null, pShaderModule);
            long shaderModule = pShaderModule.get(0);

            Shaderc.shaderc_result_release(res);
            Shaderc.shaderc_compiler_release(compiler);

            VkBufferCreateInfo bufInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(GPU_PLANET_BUFFER_SIZE)
                    .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuf = stack.mallocLong(1);
            vkCreateBuffer(device, bufInfo, null, pBuf);
            physicsPlanetBuffer = pBuf.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, physicsPlanetBuffer, memReqs);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(findMemoryType(memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            LongBuffer pMem = stack.mallocLong(1);
            vkAllocateMemory(device, allocInfo, null, pMem);
            physicsPlanetMemory = pMem.get(0);
            vkBindBufferMemory(device, physicsPlanetBuffer, physicsPlanetMemory, 0);

            PointerBuffer pData1 = stack.mallocPointer(1);
            vkMapMemory(device, physicsPlanetMemory, 0, GPU_PLANET_BUFFER_SIZE, 0, pData1);
            mappedPlanetBuffer = pData1.getByteBuffer(0, GPU_PLANET_BUFFER_SIZE).order(ByteOrder.nativeOrder());

            VkBufferCreateInfo debBufInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(GPU_DEBRIS_BUFFER_SIZE)
                    .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            vkCreateBuffer(device, debBufInfo, null, pBuf);
            physicsDebrisBuffer = pBuf.get(0);

            vkGetBufferMemoryRequirements(device, physicsDebrisBuffer, memReqs);
            allocInfo.allocationSize(memReqs.size())
                    .memoryTypeIndex(findMemoryType(memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
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
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);
            LongBuffer pSetLayout = stack.mallocLong(1);
            vkCreateDescriptorSetLayout(device, layoutInfo, null, pSetLayout);
            descriptorSetLayout = pSetLayout.get(0);

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                    .offset(0)
                    .size(32);

            VkPipelineLayoutCreateInfo pLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout))
                    .pPushConstantRanges(pushRange);
            LongBuffer pPipeLayout = stack.mallocLong(1);
            vkCreatePipelineLayout(device, pLayoutInfo, null, pPipeLayout);
            pipelineLayout = pPipeLayout.get(0);

            VkPipelineShaderStageCreateInfo stageInfo = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule)
                    .pName(stack.UTF8("main"));

            VkComputePipelineCreateInfo.Buffer pipeInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                    .stage(stageInfo)
                    .layout(pipelineLayout);
            LongBuffer pPipe = stack.mallocLong(1);
            vkCreateComputePipelines(device, VK_NULL_HANDLE, pipeInfo, null, pPipe);
            computePipeline = pPipe.get(0);

            vkDestroyShaderModule(device, shaderModule, null);

            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack);
            poolSize.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .pPoolSizes(poolSize)
                    .maxSets(1);
            LongBuffer pPool = stack.mallocLong(1);
            vkCreateDescriptorPool(device, poolInfo, null, pPool);
            descriptorPool = pPool.get(0);

            VkDescriptorSetAllocateInfo dsAlloc = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer pDs = stack.mallocLong(1);
            vkAllocateDescriptorSets(device, dsAlloc, pDs);
            descriptorSet = pDs.get(0);

            VkDescriptorBufferInfo.Buffer descPlanetBuf = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(physicsPlanetBuffer)
                    .offset(0)
                    .range(GPU_PLANET_BUFFER_SIZE);

            VkDescriptorBufferInfo.Buffer descDebrisBuf = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(physicsDebrisBuffer)
                    .offset(0)
                    .range(GPU_DEBRIS_BUFFER_SIZE);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(descPlanetBuf);
            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet).dstBinding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(descDebrisBuf);
            vkUpdateDescriptorSets(device, writes, null);

            VkCommandBufferAllocateInfo cmdAlloc = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(SBoxVulkanCore.commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            vkAllocateCommandBuffers(device, cmdAlloc, pCmd);
            physicsCmdBuffer = new VkCommandBuffer(pCmd.get(0), device);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            LongBuffer pFence = stack.mallocLong(1);
            vkCreateFence(device, fenceInfo, null, pFence);
            physicsFence = pFence.get(0);

            gpuPhysicsReady = true;
            System.out.println("[SBox Physics] Полный 64-bit Double GPU-шейдер (OBB Cube Collisions + T^4 Stefan-Boltzmann) АКТИВИРОВАН.");
        } catch (Exception e) {
            System.err.println("[SBox Physics] Ошибка инициализации GPU физики: " + e.getMessage());
            gpuPhysicsReady = false;
        }
    }

    public static void tick(double dtReal) {
        if (SpaceEngine.isMoveToolActive && SpaceEngine.draggedPlanet != null) {
            Vector3f rayF = CameraManager.getMouseRay();
            SCRATCH_PULL.set(rayF.x, rayF.y, rayF.z);
            if (SCRATCH_PULL.y < -0.001) {
                double t = -CameraManager.pos.y / SCRATCH_PULL.y;
                SpaceEngine.draggedPlanet.pos.set(CameraManager.pos).fma(t, SCRATCH_PULL);
                SpaceEngine.draggedPlanet.pos.y = 0.0;
                SpaceEngine.draggedPlanet.vel.zero();
            }
        }

        if (!SpaceEngine.isPaused && SpaceEngine.physicsEnabled) {
            long tickStart = System.nanoTime();

            double dtSim = dtReal * SpaceEngine.timeScale;
            int totalSubSteps = (int) Math.max(1, Math.min(2500, Math.abs(dtSim) / 5.0));
            double subDt = dtSim / totalSubSteps;

            runGpuPhysicsStep(subDt, dtReal, totalSubSteps);

            SpaceEngine.simTimeSeconds += dtSim;
            SpaceEngine.lastPhysicsTickMs = (System.nanoTime() - tickStart) / 1_000_000.0;
        }
    }

    private static void runGpuPhysicsStep(double subDt, double dtReal, int subSteps) {
        List<SpaceEngine.Planet> planets = SpaceEngine.planets;
        List<SpaceEngine.Fragment> fragments = SpaceEngine.fragments;
        List<SpaceEngine.Particle> particles = SpaceEngine.particles;

        int pSize = Math.min(planets.size(), MAX_GPU_PLANETS);
        if (pSize == 0 || !gpuPhysicsReady) return;

        for (int i = 0; i < pSize; i++) {
            SpaceEngine.Planet p = planets.get(i);
            int base = i * GPU_PLANET_STRIDE;
            mappedPlanetBuffer.putDouble(base, p.pos.x);
            mappedPlanetBuffer.putDouble(base + 8, p.pos.y);
            mappedPlanetBuffer.putDouble(base + 16, p.pos.z);
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
            // Упаковываем флаг isRigid и угол наклона оси tilt в одно double-поле без расширения структуры!
            double packedRigidAndTilt = (p.isRigid ? 1000.0 : 0.0) + p.tilt;
            mappedPlanetBuffer.putDouble(base + 96, objType);
            mappedPlanetBuffer.putDouble(base + 104, packedRigidAndTilt);
            mappedPlanetBuffer.putDouble(base + 112, 0.0);
            mappedPlanetBuffer.putDouble(base + 120, -1.0);
        }

        int fCount = Math.min(fragments.size(), MAX_GPU_DEBRIS / 2);
        int ptCount = Math.min(particles.size(), MAX_GPU_DEBRIS - fCount);
        int totalDebris = fCount + ptCount;

        for (int i = 0; i < fCount; i++) {
            SpaceEngine.Fragment f = fragments.get(i);
            int base = i * GPU_DEBRIS_STRIDE;
            mappedDebrisBuffer.putDouble(base, f.pos.x);
            mappedDebrisBuffer.putDouble(base + 8, f.pos.y);
            mappedDebrisBuffer.putDouble(base + 16, f.pos.z);
            mappedDebrisBuffer.putDouble(base + 24, f.size);

            mappedDebrisBuffer.putDouble(base + 32, f.vel.x);
            mappedDebrisBuffer.putDouble(base + 40, f.vel.y);
            mappedDebrisBuffer.putDouble(base + 48, f.vel.z);
            mappedDebrisBuffer.putDouble(base + 56, f.life);

            mappedDebrisBuffer.putDouble(base + 64, f.mass);
            mappedDebrisBuffer.putDouble(base + 72, f.temperature);
            mappedDebrisBuffer.putDouble(base + 80, f.angle);
            mappedDebrisBuffer.putDouble(base + 88, 1.0);
        }

        for (int i = 0; i < ptCount; i++) {
            SpaceEngine.Particle pt = particles.get(i);
            int base = (fCount + i) * GPU_DEBRIS_STRIDE;
            mappedDebrisBuffer.putDouble(base, pt.pos.x);
            mappedDebrisBuffer.putDouble(base + 8, pt.pos.y);
            mappedDebrisBuffer.putDouble(base + 16, pt.pos.z);
            mappedDebrisBuffer.putDouble(base + 24, pt.size);

            mappedDebrisBuffer.putDouble(base + 32, pt.vel.x);
            mappedDebrisBuffer.putDouble(base + 40, pt.vel.y);
            mappedDebrisBuffer.putDouble(base + 48, pt.vel.z);
            mappedDebrisBuffer.putDouble(base + 56, pt.life);

            mappedDebrisBuffer.putDouble(base + 64, pt.mass);
            mappedDebrisBuffer.putDouble(base + 72, pt.temperature);
            mappedDebrisBuffer.putDouble(base + 80, 0.0);
            mappedDebrisBuffer.putDouble(base + 88, 0.0);
        }

        int flags = (SpaceEngine.enableGravity ? 1 : 0)
                | (SpaceEngine.enableRocheLimit ? 2 : 0)
                | (SpaceEngine.enableCentrifugalForce ? 4 : 0)
                | (SpaceEngine.enableCollisions ? 8 : 0);

        int debrisGroups = (totalDebris + 63) / 64;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkResetCommandBuffer(physicsCmdBuffer, 0);
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(physicsCmdBuffer, beginInfo);

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

            // УБРАН лишний барьер VK_PIPELINE_STAGE_HOST_BIT: синхронизацию с HOST_COHERENT памятью выполняет vkWaitForFences!
            vkEndCommandBuffer(physicsCmdBuffer);

            vkResetFences(SBoxVulkanCore.logicalDevice, physicsFence);
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(physicsCmdBuffer));
            vkQueueSubmit(SBoxVulkanCore.graphicsQueue, submitInfo, physicsFence);
            vkWaitForFences(SBoxVulkanCore.logicalDevice, physicsFence, true, -1L);
        }

        for (int i = fCount - 1; i >= 0; i--) {
            int base = i * GPU_DEBRIS_STRIDE;
            double life = mappedDebrisBuffer.getDouble(base + 56);
            if (life <= 0.0 || Double.isNaN(life)) {
                fragments.remove(i);
            } else {
                SpaceEngine.Fragment f = fragments.get(i);
                f.pos.set(mappedDebrisBuffer.getDouble(base), mappedDebrisBuffer.getDouble(base + 8), mappedDebrisBuffer.getDouble(base + 16));
                f.size = mappedDebrisBuffer.getDouble(base + 24);
                f.vel.set(mappedDebrisBuffer.getDouble(base + 32), mappedDebrisBuffer.getDouble(base + 40), mappedDebrisBuffer.getDouble(base + 48));
                f.life = life;
                f.temperature = mappedDebrisBuffer.getDouble(base + 72);
                f.angle = mappedDebrisBuffer.getDouble(base + 80);
            }
        }

        for (int i = ptCount - 1; i >= 0; i--) {
            int base = (fCount + i) * GPU_DEBRIS_STRIDE;
            double life = mappedDebrisBuffer.getDouble(base + 56);
            if (life <= 0.0 || Double.isNaN(life)) {
                particles.remove(i);
            } else {
                SpaceEngine.Particle pt = particles.get(i);
                pt.pos.set(mappedDebrisBuffer.getDouble(base), mappedDebrisBuffer.getDouble(base + 8), mappedDebrisBuffer.getDouble(base + 16));
                pt.size = mappedDebrisBuffer.getDouble(base + 24);
                pt.vel.set(mappedDebrisBuffer.getDouble(base + 32), mappedDebrisBuffer.getDouble(base + 40), mappedDebrisBuffer.getDouble(base + 48));
                pt.life = life;
                pt.temperature = mappedDebrisBuffer.getDouble(base + 72);
            }
        }

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        boolean anyRemoved = false;

        for (int i = 0; i < pSize; i++) {
            SpaceEngine.Planet p = planets.get(i);
            int base = i * GPU_PLANET_STRIDE;
            p.pos.set(
                    mappedPlanetBuffer.getDouble(base),
                    mappedPlanetBuffer.getDouble(base + 8),
                    mappedPlanetBuffer.getDouble(base + 16)
            );
            p.mass = mappedPlanetBuffer.getDouble(base + 24);
            p.vel.set(
                    mappedPlanetBuffer.getDouble(base + 32),
                    mappedPlanetBuffer.getDouble(base + 40),
                    mappedPlanetBuffer.getDouble(base + 48)
            );
            p.radius = Math.max(0.01, mappedPlanetBuffer.getDouble(base + 56));
            p.currentAngle = mappedPlanetBuffer.getDouble(base + 64);
            p.rotationPeriod = mappedPlanetBuffer.getDouble(base + 72);
            p.targetRadius = Math.max(0.01, mappedPlanetBuffer.getDouble(base + 80));
            p.temperature = mappedPlanetBuffer.getDouble(base + 88);

            double shedMass = mappedPlanetBuffer.getDouble(base + 112);
            int rawEventCode = (int) Math.round(mappedPlanetBuffer.getDouble(base + 120));
            boolean isBlackHoleSpaghettification = rawEventCode >= 100;
            int partnerIdx = isBlackHoleSpaghettification ? (rawEventCode - 100) : rawEventCode;

            if (shedMass > 1e-6 && !"Hole".equals(p.type)) {
                double idealMass = Math.max(1e-7, Math.max(p.mass, shedMass) * (p.isRigid ? 0.012 : 0.006));
                double expectedSpawns = shedMass / idealMass;
                int count = Math.min(20, (int) expectedSpawns + (rng.nextDouble() < (expectedSpawns - (int) expectedSpawns) ? 1 : 0));

                if (partnerIdx >= 0 && partnerIdx < pSize) {
                    SpaceEngine.Planet massive = planets.get(partnerIdx);
                    for (int k = 0; k < count; k++) {
                        SCRATCH_PULL.set(massive.pos).sub(p.pos);
                        double distToMassive = SCRATCH_PULL.length();
                        if (distToMassive > 1e-6) SCRATCH_PULL.div(distToMassive);
                        else SCRATCH_PULL.set(1.0, 0.0, 0.0);

                        if (isBlackHoleSpaghettification) {
                            double lobeSign = (k % 2 == 0) ? 1.0 : -0.85;
                            double streamSpread = 0.14;
                            SCRATCH_OFFSET.set(SCRATCH_PULL).mul(lobeSign)
                                    .add((rng.nextDouble() - 0.5) * streamSpread, (rng.nextDouble() - 0.5) * streamSpread, (rng.nextDouble() - 0.5) * streamSpread)
                                    .normalize();
                            double radialFraction = 0.8 + rng.nextDouble() * 1.4;
                            SCRATCH_POS.set(p.pos).fma(Math.max(p.radius, 0.5) * radialFraction, SCRATCH_OFFSET);
                            double tidalJetSpeed = Math.sqrt((G * massive.mass) / Math.max(distToMassive, massive.radius)) * 0.35;
                            SCRATCH_VEL.set(p.vel).fma(lobeSign > 0 ? tidalJetSpeed : -tidalJetSpeed * 0.4, SCRATCH_PULL);
                            spawnPooledDebris(p, SCRATCH_POS, SCRATCH_VEL, idealMass, Math.max(p.temperature, 2600.0), rng);
                        } else {
                            SCRATCH_PULL.add((rng.nextDouble() - 0.5) * 0.55, (rng.nextDouble() - 0.5) * 0.55, (rng.nextDouble() - 0.5) * 0.55).normalize();
                            SCRATCH_POS.set(p.pos).fma(Math.max(p.radius, 0.5) * 1.15, SCRATCH_PULL);
                            SCRATCH_VEL.set(p.vel).fma(rng.nextDouble() * 6.0 + 2.0, SCRATCH_PULL);
                            spawnPooledDebris(p, SCRATCH_POS, SCRATCH_VEL, idealMass, Math.max(p.temperature, p.isRigid ? 1400.0 : p.temperature), rng);
                        }
                    }
                } else {
                    double tiltRad = Math.toRadians(p.tilt);
                    SCRATCH_SPIN.set(0.0, 1.0, 0.0).rotateZ(tiltRad);
                    double wCurrent = (2.0 * Math.PI) / (Math.max(0.0001, Math.abs(p.rotationPeriod)) * 3600.0);
                    for (int k = 0; k < count; k++) {
                        double ang = rng.nextDouble() * Math.PI * 2.0;
                        double h = (rng.nextDouble() - 0.5) * 0.25;
                        SCRATCH_OFFSET.set(Math.cos(ang), h, Math.sin(ang)).normalize().mul(Math.max(p.radius, 0.5) * 1.12).rotateZ(tiltRad);
                        SCRATCH_POS.set(p.pos).add(SCRATCH_OFFSET);
                        SCRATCH_VEL.set(SCRATCH_SPIN).cross(SCRATCH_OFFSET).normalize().mul(wCurrent * p.radius);
                        SCRATCH_VEL.add(p.vel).fma(rng.nextDouble() * 2.2, SCRATCH_OFFSET.normalize());
                        double shedTemp = p.isRigid ? Math.max(p.temperature, 950.0) : p.temperature;
                        spawnPooledDebris(p, SCRATCH_POS, SCRATCH_VEL, idealMass, shedTemp, rng);
                    }
                }
            }

            if (p.mass > 0 && !Double.isNaN(p.pos.x)) {
                recordTrailPoint(p);
            } else {
                p.mass = 0;
                anyRemoved = true;
            }
        }

        if (anyRemoved) {
            for (int i = planets.size() - 1; i >= 0; i--) {
                if (planets.get(i).mass <= 0) {
                    planets.remove(i);
                }
            }
            SpaceEngine.planetsNeedSort = true;
        }
    }

    private static void recordTrailPoint(SpaceEngine.Planet p) {
        double minDistSq = (p.radius * 1.5) * (p.radius * 1.5);
        if (p.trail.isEmpty()) {
            p.trail.add(new Vector3d(p.pos));
        } else {
            Vector3d lastNode = p.trail.get(p.trail.size() - 1);
            if (lastNode.distanceSquared(p.pos) > minDistSq) {
                if (p.trail.size() >= 300) {
                    Vector3d recycled = p.trail.remove(0);
                    recycled.set(p.pos);
                    p.trail.add(recycled);
                } else {
                    p.trail.add(new Vector3d(p.pos));
                }
            }
        }
    }

    private static void spawnPooledDebris(SpaceEngine.Planet source, Vector3d pPos, Vector3d pVel, double idealMass, double tempC, ThreadLocalRandom rng) {
        // Чёрные дыры никогда не испаряются ни газом, ни камнями!
        if ("Hole".equals(source.type)) return;

        double baseRad = Math.max(source.radius, 0.8);
        if (source.isRigid) {
            if (SpaceEngine.fragments.size() < MAX_GPU_DEBRIS / 2) {
                float px = rng.nextInt(16);
                float py = rng.nextInt(16);
                double fragSize = baseRad * (rng.nextDouble() * 0.08 + 0.03);
                SpaceEngine.Fragment f = FRAGMENT_POOL[fragPoolCursor];
                fragPoolCursor = (fragPoolCursor + 1) % MAX_GPU_DEBRIS;
                f.set(pPos, pVel, fragSize, 12.0 + rng.nextDouble() * 14.0, idealMass, tempC,
                        SpaceEngine.TEX_FRAGMENT, px / 16.0f, py / 16.0f, (px + 1.0f) / 16.0f, (py + 1.0f) / 16.0f);
                if (!SpaceEngine.fragments.contains(f)) {
                    SpaceEngine.fragments.add(f);
                }
            }
        } else {
            if (SpaceEngine.particles.size() < MAX_GPU_DEBRIS / 2) {
                int pr = Math.min(255, Math.max(40, source.rColor + rng.nextInt(30) - 15));
                int pg = Math.min(255, Math.max(40, source.gColor + rng.nextInt(30) - 15));
                int pb = Math.min(255, Math.max(40, source.bColor + rng.nextInt(30) - 15));
                double gasCloudSize = baseRad * (0.14 + rng.nextDouble() * 0.16);
                SpaceEngine.Particle pt = PARTICLE_POOL[partPoolCursor];
                partPoolCursor = (partPoolCursor + 1) % MAX_GPU_DEBRIS;
                pt.set(pPos, pVel, gasCloudSize, 9.0 + rng.nextDouble() * 10.0, pr, pg, pb, 1, idealMass, tempC);
                if (!SpaceEngine.particles.contains(pt)) {
                    SpaceEngine.particles.add(pt);
                }
            }
        }
    }

    private static int findMemoryType(int typeBits, int properties) {
        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.calloc();
        vkGetPhysicalDeviceMemoryProperties(SBoxVulkanCore.physicalDevice, memProps);
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0 && (memProps.memoryTypes(i).propertyFlags() & properties) == properties) {
                memProps.free();
                return i;
            }
        }
        memProps.free();
        throw new RuntimeException("Не найден подходящий тип памяти Vulkan для PhysicsEngine!");
    }
}