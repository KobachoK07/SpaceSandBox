package spacesb.remake.render;

import org.joml.Vector3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;
import spacesb.remake.CameraManager;
import spacesb.remake.SpaceEngine;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

public class SBoxCompute {
    public static long pipelineLayout = VK_NULL_HANDLE;
    public static long computePipeline = VK_NULL_HANDLE;
    public static long descriptorSetLayout = VK_NULL_HANDLE;
    public static long descriptorPool = VK_NULL_HANDLE;
    public static long descriptorSet = VK_NULL_HANDLE;

    public static long planetBuffer = VK_NULL_HANDLE;
    public static long planetBufferMemory = VK_NULL_HANDLE;
    public static ByteBuffer mappedPlanetBuffer;

    public static long debrisBuffer = VK_NULL_HANDLE;
    public static long debrisBufferMemory = VK_NULL_HANDLE;
    public static ByteBuffer mappedDebrisBuffer;

    public static long planetArrayImage = VK_NULL_HANDLE;
    public static long planetArrayMemory = VK_NULL_HANDLE;
    public static long planetArrayImageView = VK_NULL_HANDLE;
    public static long planetSampler = VK_NULL_HANDLE;

    private static final int MAX_PLANETS = 64;
    private static final int PLANET_STRIDE = 160;
    private static final int BUFFER_SIZE = MAX_PLANETS * PLANET_STRIDE;

    private static final int MAX_DEBRIS = 512;
    private static final int DEBRIS_STRIDE = 64;
    private static final int DEBRIS_BUFFER_SIZE = MAX_DEBRIS * DEBRIS_STRIDE;

    private static final int TEX_SIZE = 256;
    private static final String[] PLANET_TEXTURES = {
            "assets/spacesb-remake/textures/planet/earth.png",
            "assets/spacesb-remake/textures/planet/mercury.png",
            "assets/spacesb-remake/textures/planet/venus.png",
            "assets/spacesb-remake/textures/planet/moon.png",
            "assets/spacesb-remake/textures/planet/jupiter.png",
            "assets/spacesb-remake/textures/planet/saturn.png",
            "assets/spacesb-remake/textures/planet/uranus.png",
            "assets/spacesb-remake/textures/planet/titan.png",
            "assets/spacesb-remake/textures/planet/enceladus.png",
            "assets/spacesb-remake/textures/planet/sun.png",
            "assets/spacesb-remake/textures/planet/stephenson2_18.png",
            "assets/spacesb-remake/textures/planet/sa.png",
            "assets/spacesb-remake/textures/particle/fragments.png"
    };
    private static final Map<String, Integer> texIndexMap = new HashMap<>();
    static {
        for (int i = 0; i < PLANET_TEXTURES.length; i++) {
            texIndexMap.put(PLANET_TEXTURES[i], i);
        }
    }

    private static final long START_TIME = System.currentTimeMillis();

    public static void destroyCompute() {
        VkDevice device = SBoxVulkanCore.logicalDevice;
        if (device == null) return;

        if (computePipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(device, computePipeline, null);
            computePipeline = VK_NULL_HANDLE;
        }
        if (pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device, pipelineLayout, null);
            pipelineLayout = VK_NULL_HANDLE;
        }
        if (descriptorSetLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
            descriptorSetLayout = VK_NULL_HANDLE;
        }
        if (descriptorPool != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device, descriptorPool, null);
            descriptorPool = VK_NULL_HANDLE;
        }
    }

    public static void updateDescriptorSet() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
            imageInfo.get(0).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            imageInfo.get(0).imageView(SBoxVulkanCanvas.canvasImageView);

            VkWriteDescriptorSet.Buffer writeSet = VkWriteDescriptorSet.calloc(1, stack);
            writeSet.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
            writeSet.get(0).dstSet(descriptorSet);
            writeSet.get(0).dstBinding(0);
            writeSet.get(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            writeSet.get(0).descriptorCount(1);
            writeSet.get(0).pImageInfo(imageInfo);

            vkUpdateDescriptorSets(SBoxVulkanCore.logicalDevice, writeSet, null);
        }
    }

    private static byte[] readResourceBytes(String path) {
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        try {
            InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(cleanPath);
            if (is != null) { byte[] d = is.readAllBytes(); is.close(); return d; }

            is = SBoxCompute.class.getClassLoader().getResourceAsStream(cleanPath);
            if (is != null) { byte[] d = is.readAllBytes(); is.close(); return d; }

            String[] devPaths = { "src/main/resources/", "../src/main/resources/", "../../src/main/resources/" };
            for (String prefix : devPaths) {
                java.nio.file.Path p = Paths.get(prefix + cleanPath);
                if (Files.exists(p)) return Files.readAllBytes(p);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static void init() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice device = SBoxVulkanCore.logicalDevice;

            createBuffers(stack, device);
            loadAllPlanetTextures(stack, device);

            String glsl =
                    "#version 450\n" +
                            "#extension GL_ARB_gpu_shader_fp64 : enable\n" +
                            "layout (local_size_x = 16, local_size_y = 16) in;\n" +
                            "layout (binding = 0, rgba8) uniform writeonly image2D resultImage;\n" +
                            "\n" +
                            "struct PlanetData {\n" +
                            "    dvec4 posAndRadius; // 0..31\n" +
                            "    vec4 rotAndFlags;   // 32..47\n" +
                            "    vec4 shadeParams;   // 48..63\n" +
                            "    vec4 uvFaces[6];    // 64..159\n" +
                            "};\n" +
                            "\n" +
                            "layout (std430, binding = 1) readonly buffer PlanetBuffer {\n" +
                            "    PlanetData planets[64];\n" +
                            "} pb;\n" +
                            "\n" +
                            "layout (binding = 2) uniform sampler2DArray planetTexArray;\n" +
                            "\n" +
                            "struct DebrisData {\n" +
                            "    dvec4 posAndSize;   // 0..31\n" +
                            "    vec4 colorAndTemp;  // 32..47\n" +
                            "    vec4 rotAndType;    // 48..63\n" +
                            "};\n" +
                            "\n" +
                            "layout (std430, binding = 3) readonly buffer DebrisBuffer {\n" +
                            "    DebrisData debris[512];\n" +
                            "} db;\n" +
                            "\n" +
                            "layout(push_constant) uniform PushConstants {\n" +
                            "    dvec4 camPosAndDist; // 0..31\n" +
                            "    vec4 screenAndTime;  // 32..47\n" +
                            "    vec2 camRot;         // 48..55\n" +
                            "    int planetCount;     // 56..59\n" +
                            "    int debrisCount;     // 60..63\n" +
                            "} pc;\n" +
                            "\n" +
                            "float hash(vec3 p) {\n" +
                            "    p = fract(p * vec3(123.34, 456.21, 789.12));\n" +
                            "    p += dot(p, p + 45.32);\n" +
                            "    return fract(p.x * p.y * p.z);\n" +
                            "}\n" +
                            "\n" +
                            "float hash2D(vec2 p) {\n" +
                            "    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);\n" +
                            "}\n" +
                            "\n" +
                            "float noise3D(vec3 p) {\n" +
                            "    vec3 i = floor(p);\n" +
                            "    vec3 f = fract(p);\n" +
                            "    f = f * f * (3.0 - 2.0 * f);\n" +
                            "    float n000 = hash(i);\n" +
                            "    float n100 = hash(i + vec3(1,0,0));\n" +
                            "    float n010 = hash(i + vec3(0,1,0));\n" +
                            "    float n110 = hash(i + vec3(1,1,0));\n" +
                            "    float n001 = hash(i + vec3(0,0,1));\n" +
                            "    float n101 = hash(i + vec3(1,0,1));\n" +
                            "    float n011 = hash(i + vec3(0,1,1));\n" +
                            "    float n111 = hash(i + vec3(1,1,1));\n" +
                            "    return mix(mix(mix(n000, n100, f.x), mix(n010, n110, f.x), f.y),\n" +
                            "               mix(mix(n001, n101, f.x), mix(n011, n111, f.x), f.y), f.z);\n" +
                            "}\n" +
                            "\n" +
                            "float noiseAngle(float a, float freq, float t) {\n" +
                            "    float s = sin(a * freq + t) * 0.5 + 0.5;\n" +
                            "    s += 0.5 * (sin(a * (freq * 2.3) - t * 1.4 + 1.9) * 0.5 + 0.5);\n" +
                            "    s += 0.25 * (sin(a * (freq * 5.1) + t * 0.8 + 4.1) * 0.5 + 0.5);\n" +
                            "    return s / 1.75;\n" +
                            "}\n" +
                            "\n" +
                            "vec3 rotateY(vec3 v, float a) {\n" +
                            "    float c = cos(a), s = sin(a);\n" +
                            "    return vec3(c * v.x + s * v.z, v.y, -s * v.x + c * v.z);\n" +
                            "}\n" +
                            "\n" +
                            "vec3 rotateZ(vec3 v, float a) {\n" +
                            "    float c = cos(a), s = sin(a);\n" +
                            "    return vec3(c * v.x - s * v.y, s * v.x + c * v.y, v.z);\n" +
                            "}\n" +
                            "\n" +
                            "dvec3 rotateYD(dvec3 v, float a) {\n" +
                            "    double c = double(cos(a)), s = double(sin(a));\n" +
                            "    return dvec3(c * v.x + s * v.z, v.y, -s * v.x + c * v.z);\n" +
                            "}\n" +
                            "\n" +
                            "dvec3 rotateZD(dvec3 v, float a) {\n" +
                            "    double c = double(cos(a)), s = double(sin(a));\n" +
                            "    return dvec3(c * v.x - s * v.y, s * v.x + c * v.y, v.z);\n" +
                            "}\n" +
                            "\n" +
                            "double sanitizeRadiusD(double rawR) {\n" +
                            "    return (isnan(rawR) || isinf(rawR) || !(rawR > 1e-4LF)) ? 1e-4LF : rawR;\n" +
                            "}\n" +
                            "\n" +
                            "dvec3 applyTidalSpaghettificationD(dvec3 relVecD, dvec3 pPosWorldD, double rD, int holeCount, int holeIndices[16]) {\n" +
                            "    for (int h = 0; h < holeCount; h++) {\n" +
                            "        int hIdx = holeIndices[h];\n" +
                            "        dvec3 bhPosD = pb.planets[hIdx].posAndRadius.xyz;\n" +
                            "        double bhRadD = sanitizeRadiusD(pb.planets[hIdx].posAndRadius.w);\n" +
                            "        dvec3 toBHD = bhPosD - pPosWorldD;\n" +
                            "        double distD = length(toBHD);\n" +
                            "        double tidalZoneD = max(bhRadD * 9.0LF, rD * 6.0LF);\n" +
                            "        if (distD > 1e-4LF && distD < tidalZoneD) {\n" +
                            "            dvec3 tidalAxisD = toBHD / distD;\n" +
                            "            double factor = clamp(1.0LF - distD / tidalZoneD, 0.0LF, 0.88LF);\n" +
                            "            double stretch = 1.0LF + factor * factor * 3.8LF;\n" +
                            "            double compress = sqrt(stretch);\n" +
                            "            double parallel = dot(relVecD, tidalAxisD);\n" +
                            "            double absParallel = abs(parallel);\n" +
                            "            double signParallel = (parallel >= 0.0LF) ? 1.0LF : -1.0LF;\n" +
                            "            dvec3 perp = relVecD - tidalAxisD * parallel;\n" +
                            "            relVecD = tidalAxisD * (signParallel * (absParallel / stretch)) + perp * compress;\n" +
                            "        }\n" +
                            "    }\n" +
                            "    return relVecD;\n" +
                            "}\n" +
                            "\n" +
                            "vec3 acesToneMap(vec3 x) {\n" +
                            "    const float a = 2.51;\n" +
                            "    const float b = 0.03;\n" +
                            "    const float c = 2.43;\n" +
                            "    const float d = 0.59;\n" +
                            "    const float e = 0.14;\n" +
                            "    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);\n" +
                            "}\n" +
                            "\n" +
                            "vec3 getBlackbodyColor(float tempC) {\n" +
                            "    float tempK = max(300.0, tempC + 273.15);\n" +
                            "    if (tempK < 1400.0) {\n" +
                            "        return mix(vec3(0.85, 0.08, 0.01), vec3(1.0, 0.28, 0.04), clamp((tempK - 773.0) / 627.0, 0.0, 1.0));\n" +
                            "    } else if (tempK < 3500.0) {\n" +
                            "        return mix(vec3(1.0, 0.28, 0.04), vec3(1.0, 0.68, 0.28), clamp((tempK - 1400.0) / 2100.0, 0.0, 1.0));\n" +
                            "    } else if (tempK < 6500.0) {\n" +
                            "        return mix(vec3(1.0, 0.68, 0.28), vec3(1.0, 0.94, 0.84), clamp((tempK - 3500.0) / 3000.0, 0.0, 1.0));\n" +
                            "    }\n" +
                            "    return mix(vec3(1.0, 0.94, 0.84), vec3(0.70, 0.84, 1.0), clamp((tempK - 6500.0) / 12000.0, 0.0, 1.0));\n" +
                            "}\n" +
                            "\n" +
                            "float intersectPlanetBox(vec3 rayOrigRel, vec3 rayD, int idx, dvec3 camPosD) {\n" +
                            "    dvec3 pPosWorldD = pb.planets[idx].posAndRadius.xyz;\n" +
                            "    double rD = sanitizeRadiusD(pb.planets[idx].posAndRadius.w);\n" +
                            "    float tilt = pb.planets[idx].rotAndFlags.x;\n" +
                            "    float rot = pb.planets[idx].rotAndFlags.y;\n" +
                            "    dvec3 relOrigD = (camPosD + dvec3(rayOrigRel)) - pPosWorldD;\n" +
                            "    dvec3 roNormD = rotateYD(rotateZD(relOrigD, -tilt), -rot) / rD;\n" +
                            "    vec3 ro = vec3(roNormD);\n" +
                            "    vec3 rd = rotateY(rotateZ(rayD, -tilt), -rot);\n" +
                            "    vec3 safeRd = vec3(\n" +
                            "        abs(rd.x) < 1e-7 ? 1e-7 : rd.x,\n" +
                            "        abs(rd.y) < 1e-7 ? 1e-7 : rd.y,\n" +
                            "        abs(rd.z) < 1e-7 ? 1e-7 : rd.z\n" +
                            "    );\n" +
                            "    vec3 invDir = 1.0 / safeRd;\n" +
                            "    vec3 t0 = (-vec3(1.0) - ro) * invDir;\n" +
                            "    vec3 t1 = ( vec3(1.0) - ro) * invDir;\n" +
                            "    vec3 tmin = min(t0, t1);\n" +
                            "    vec3 tmax = max(t0, t1);\n" +
                            "    float tNear = max(max(tmin.x, tmin.y), tmin.z);\n" +
                            "    float tFar = min(min(tmax.x, tmax.y), tmax.z);\n" +
                            "    if (tNear < tFar && tFar > 0.0) {\n" +
                            "        return float(double((tNear > 0.0) ? tNear : tFar) * rD);\n" +
                            "    }\n" +
                            "    return -1.0;\n" +
                            "}\n" +
                            "\n" +
                            "float traceShadow(vec3 relWorldPt, vec3 lightDir, float distToEmitter, float emitterRadius, int ignoreIdx, int emitterIdx, int count, dvec3 camPosD) {\n" +
                            "    float shadow = 1.0;\n" +
                            "    for (int k = 0; k < count; k++) {\n" +
                            "        if (k == ignoreIdx || k == emitterIdx) continue;\n" +
                            "        if (pb.planets[k].rotAndFlags.z < 0.99) continue;\n" +
                            "        if (pb.planets[k].shadeParams.x > 0.5 && pb.planets[k].shadeParams.x < 1.5) continue;\n" +
                            "        vec3 occPos = vec3(pb.planets[k].posAndRadius.xyz - camPosD);\n" +
                            "        float occRad = float(sanitizeRadiusD(pb.planets[k].posAndRadius.w));\n" +
                            "        vec3 toOcc = occPos - relWorldPt;\n" +
                            "        float proj = dot(toOcc, lightDir);\n" +
                            "        if (proj > 0.0 && proj < distToEmitter) {\n" +
                            "            float hitBox = intersectPlanetBox(relWorldPt, lightDir, k, camPosD);\n" +
                            "            if (hitBox > 0.0 && hitBox < distToEmitter) {\n" +
                            "                return 0.0;\n" +
                            "            }\n" +
                            "            vec3 closest = toOcc - lightDir * proj;\n" +
                            "            float perpDist = length(closest);\n" +
                            "            float effRad = occRad * 1.15;\n" +
                            "            float penumbra = max(occRad * 0.15, emitterRadius * (proj / max(distToEmitter, 1.0)) * 0.5);\n" +
                            "            float s = smoothstep(effRad - penumbra * 0.3, effRad + penumbra, perpDist);\n" +
                            "            shadow = min(shadow, s);\n" +
                            "        }\n" +
                            "    }\n" +
                            "    return shadow;\n" +
                            "}\n" +
                            "\n" +
                            "void main() {\n" +
                            "    ivec2 coord = ivec2(gl_GlobalInvocationID.xy);\n" +
                            "    float width = pc.screenAndTime.x;\n" +
                            "    float height = pc.screenAndTime.y;\n" +
                            "    float time = pc.screenAndTime.z;\n" +
                            "    float showGrid = pc.screenAndTime.w;\n" +
                            "    if (coord.x >= int(width) || coord.y >= int(height)) return;\n" +
                            "    \n" +
                            "    dvec3 camPosD = pc.camPosAndDist.xyz;\n" +
                            "    vec2 uv = (vec2(coord) - 0.5 * vec2(width, height)) / height;\n" +
                            "    \n" +
                            "    float pitch = pc.camRot.x;\n" +
                            "    float yaw = pc.camRot.y;\n" +
                            "    int count = clamp(pc.planetCount, 0, 64);\n" +
                            "    int debCount = clamp(pc.debrisCount, 0, 512);\n" +
                            "    \n" +
                            "    vec3 forward = normalize(vec3(-sin(yaw) * cos(pitch), -sin(pitch), -cos(yaw) * cos(pitch)));\n" +
                            "    vec3 right = normalize(cross(forward, vec3(0.0, 1.0, 0.0)));\n" +
                            "    vec3 up = normalize(cross(right, forward));\n" +
                            "    \n" +
                            "    vec3 rayDir = normalize(forward + uv.x * right - uv.y * up);\n" +
                            "    \n" +
                            "    int emitterIndices[32];\n" +
                            "    int emitterCount = 0;\n" +
                            "    int holeIndices[16];\n" +
                            "    int holeCount = 0;\n" +
                            "    for (int i = 0; i < count; i++) {\n" +
                            "        float t = pb.planets[i].shadeParams.x;\n" +
                            "        float tempC = pb.planets[i].shadeParams.w;\n" +
                            "        float alpha = pb.planets[i].rotAndFlags.z;\n" +
                            "        if (t > 1.5) {\n" +
                            "            if (holeCount < 16) holeIndices[holeCount++] = i;\n" +
                            "        } else if (alpha > 0.99 && ((t > 0.5 && t < 1.5) || tempC > 550.0)) {\n" +
                            "            if (emitterCount < 32) emitterIndices[emitterCount++] = i;\n" +
                            "        }\n" +
                            "    }\n" +
                            "    \n" +
                            "    // 1. ЧЁРНАЯ ДЫРА — КУБИК + ЧИСТАЯ ГРАВИТАЦИОННАЯ ЛИНЗА ОТО БЕЗ ДИСКА\n" +
                            "    bool hitBlackHoleCube = false;\n" +
                            "    float closestT = 1e38;\n" +
                            "    int hitPlanetIdx = -1;\n" +
                            "    \n" +
                            "    for (int h = 0; h < holeCount; h++) {\n" +
                            "        int hIdx = holeIndices[h];\n" +
                            "        float alpha = pb.planets[hIdx].rotAndFlags.z;\n" +
                            "        float tBox = intersectPlanetBox(vec3(0.0), rayDir, hIdx, camPosD);\n" +
                            "        if (tBox > 0.0 && alpha > 0.99) {\n" +
                            "            if (tBox < closestT) {\n" +
                            "                closestT = tBox;\n" +
                            "                hitBlackHoleCube = true;\n" +
                            "                hitPlanetIdx = hIdx;\n" +
                            "            }\n" +
                            "        } else {\n" +
                            "            dvec3 bhRelD = pb.planets[hIdx].posAndRadius.xyz - camPosD;\n" +
                            "            double rsD = sanitizeRadiusD(pb.planets[hIdx].posAndRadius.w);\n" +
                            "            vec3 bhNorm = vec3(bhRelD / rsD);\n" +
                            "            float projNorm = dot(bhNorm, rayDir);\n" +
                            "            if (projNorm > 0.0) {\n" +
                            "                vec3 impactNorm = (rayDir * projNorm) - bhNorm;\n" +
                            "                float bNorm = length(impactNorm);\n" +
                            "                float rLensNorm = 1.15;\n" +
                            "                if (bNorm > 1e-5) {\n" +
                            "                    float ratio = rLensNorm / max(bNorm, rLensNorm * 0.45);\n" +
                            "                    float deflection = (1.65 * ratio + 1.95 * ratio * ratio) * alpha / (1.0 + 0.35 * ratio * ratio);\n" +
                            "                    vec3 bendDir = -normalize(impactNorm);\n" +
                            "                    rayDir = normalize(rayDir + bendDir * deflection);\n" +
                            "                }\n" +
                            "            }\n" +
                            "        }\n" +
                            "    }\n" +
                            "    \n" +
                            "    vec3 col = vec3(0.005, 0.007, 0.013);\n" +
                            "    float starBg = hash(rayDir * 100.0);\n" +
                            "    if (starBg > 0.995 && !hitBlackHoleCube) {\n" +
                            "        float intensity = (starBg - 0.995) * 200.0;\n" +
                            "        intensity *= 0.85 + 0.15 * sin(time * 1.5 + starBg * 100.0);\n" +
                            "        col += vec3(intensity);\n" +
                            "    }\n" +
                            "    if (hitBlackHoleCube) {\n" +
                            "        col = vec3(0.0);\n" +
                            "    }\n" +
                            "    \n" +
                            "    // 2. РЕНДЕР ПЛАНЕТ И ЗВЁЗД\n" +
                            "    for (int i = 0; i < count; i++) {\n" +
                            "        float objType = pb.planets[i].shadeParams.x;\n" +
                            "        float alpha = pb.planets[i].rotAndFlags.z;\n" +
                            "        \n" +
                            "        dvec3 pPosWorldD = pb.planets[i].posAndRadius.xyz;\n" +
                            "        double rD = sanitizeRadiusD(pb.planets[i].posAndRadius.w);\n" +
                            "        float r = float(rD);\n" +
                            "        float tilt = pb.planets[i].rotAndFlags.x;\n" +
                            "        float rot = pb.planets[i].rotAndFlags.y;\n" +
                            "        float texLayer = pb.planets[i].rotAndFlags.w;\n" +
                            "        bool flashlightOn = (pb.planets[i].shadeParams.y > 0.5);\n" +
                            "        float tempC = pb.planets[i].shadeParams.w;\n" +
                            "        \n" +
                            "        dvec3 relOriginD = camPosD - pPosWorldD;\n" +
                            "        dvec3 relDirD = dvec3(rayDir);\n" +
                            "        if (objType < 1.5 && holeCount > 0) {\n" +
                            "            relOriginD = applyTidalSpaghettificationD(relOriginD, pPosWorldD, rD, holeCount, holeIndices);\n" +
                            "            relDirD = applyTidalSpaghettificationD(relDirD, pPosWorldD, rD, holeCount, holeIndices);\n" +
                            "        }\n" +
                            "        \n" +
                            "        vec3 roNorm = vec3(rotateYD(rotateZD(relOriginD, -tilt), -rot) / rD);\n" +
                            "        vec3 rd = vec3(rotateYD(rotateZD(relDirD, -tilt), -rot));\n" +
                            "        \n" +
                            "        vec3 safeRd = vec3(\n" +
                            "            abs(rd.x) < 1e-7 ? 1e-7 : rd.x,\n" +
                            "            abs(rd.y) < 1e-7 ? 1e-7 : rd.y,\n" +
                            "            abs(rd.z) < 1e-7 ? 1e-7 : rd.z\n" +
                            "        );\n" +
                            "        vec3 invDir = 1.0 / safeRd;\n" +
                            "        vec3 t0 = (-vec3(1.0) - roNorm) * invDir;\n" +
                            "        vec3 t1 = ( vec3(1.0) - roNorm) * invDir;\n" +
                            "        vec3 tmin = min(t0, t1);\n" +
                            "        vec3 tmax = max(t0, t1);\n" +
                            "        float tNearNorm = max(max(tmin.x, tmin.y), tmin.z);\n" +
                            "        float tFarNorm = min(min(tmax.x, tmax.y), tmax.z);\n" +
                            "        \n" +
                            "        if (tNearNorm < tFarNorm && tFarNorm > 0.0) {\n" +
                            "            float hitNorm = (tNearNorm > 0.0) ? tNearNorm : tFarNorm;\n" +
                            "            float hitT = float(double(hitNorm) * rD);\n" +
                            "            if (hitT < closestT) {\n" +
                            "                vec3 nPos = clamp(roNorm + rd * hitNorm, -1.0, 1.0);\n" +
                            "                vec3 absP = abs(nPos);\n" +
                            "                int faceIdx = 0;\n" +
                            "                vec2 fuv = vec2(0.0);\n" +
                            "                vec3 nLocal = vec3(0.0, 1.0, 0.0);\n" +
                            "                \n" +
                            "                if (absP.z >= absP.x && absP.z >= absP.y) {\n" +
                            "                    if (nPos.z < 0.0) {\n" +
                            "                        faceIdx = 0;\n" +
                            "                        fuv = vec2(0.5 - nPos.x * 0.5, 0.5 - nPos.y * 0.5);\n" +
                            "                        nLocal = vec3(0.0, 0.0, -1.0);\n" +
                            "                    } else {\n" +
                            "                        faceIdx = 2;\n" +
                            "                        fuv = vec2(nPos.x * 0.5 + 0.5, 0.5 - nPos.y * 0.5);\n" +
                            "                        nLocal = vec3(0.0, 0.0, 1.0);\n" +
                            "                    }\n" +
                            "                } else if (absP.x >= absP.y) {\n" +
                            "                    if (nPos.x > 0.0) {\n" +
                            "                        faceIdx = 1;\n" +
                            "                        fuv = vec2(0.5 - nPos.z * 0.5, 0.5 - nPos.y * 0.5);\n" +
                            "                        nLocal = vec3(1.0, 0.0, 0.0);\n" +
                            "                    } else {\n" +
                            "                        faceIdx = 3;\n" +
                            "                        fuv = vec2(nPos.z * 0.5 + 0.5, 0.5 - nPos.y * 0.5);\n" +
                            "                        nLocal = vec3(-1.0, 0.0, 0.0);\n" +
                            "                    }\n" +
                            "                } else {\n" +
                            "                    if (nPos.y > 0.0) {\n" +
                            "                        faceIdx = 4;\n" +
                            "                        fuv = vec2(nPos.x * 0.5 + 0.5, nPos.z * 0.5 + 0.5);\n" +
                            "                        nLocal = vec3(0.0, 1.0, 0.0);\n" +
                            "                    } else {\n" +
                            "                        faceIdx = 5;\n" +
                            "                        fuv = vec2(nPos.x * 0.5 + 0.5, 0.5 - nPos.z * 0.5);\n" +
                            "                        nLocal = vec3(0.0, -1.0, 0.0);\n" +
                            "                    }\n" +
                            "                }\n" +
                            "                \n" +
                            "                vec4 uvRect = pb.planets[i].uvFaces[faceIdx];\n" +
                            "                vec2 texUV = mix(uvRect.xy, uvRect.zw, clamp(fuv, 0.001, 0.999));\n" +
                            "                vec3 albedo = textureLod(planetTexArray, vec3(texUV, texLayer), 0.0).rgb;\n" +
                            "                \n" +
                            "                vec3 pCol = albedo;\n" +
                            "                if (objType > 1.5) {\n" +
                            "                    pCol = vec3(0.0);\n" +
                            "                } else if (objType > 0.5 && objType < 1.5) {\n" +
                            "                    vec3 starTint = getBlackbodyColor(tempC);\n" +
                            "                    float limbDarkening = 1.0 - 0.25 * length(fuv - vec2(0.5));\n" +
                            "                    pCol = mix(albedo, starTint, 0.65) * (3.2 * limbDarkening);\n" +
                            "                } else {\n" +
                            "                    vec3 blendedLocalN = normalize(mix(nLocal, normalize(nPos), 0.42));\n" +
                            "                    vec3 worldFaceN = normalize(rotateZ(rotateY(nLocal, rot), tilt));\n" +
                            "                    vec3 worldN = normalize(rotateZ(rotateY(blendedLocalN, rot), tilt));\n" +
                            "                    vec3 hitRelWorld = rayDir * hitT;\n" +
                            "                    vec3 viewDir = -rayDir;\n" +
                            "                    \n" +
                            "                    vec3 totalLight = (flashlightOn || alpha < 0.99) ? vec3(1.05) : vec3(0.015, 0.018, 0.026);\n" +
                            "                    vec3 totalSpec = vec3(0.0);\n" +
                            "                    float isWater = step(albedo.r * 1.35, albedo.b) * step(albedo.g * 1.15, albedo.b);\n" +
                            "                    \n" +
                            "                    if (!flashlightOn && alpha >= 0.99) {\n" +
                            "                        if (emitterCount > 0) {\n" +
                            "                            for (int s = 0; s < emitterCount; s++) {\n" +
                            "                                int eIdx = emitterIndices[s];\n" +
                            "                                if (eIdx == i) continue;\n" +
                            "                                vec3 ePos = vec3(pb.planets[eIdx].posAndRadius.xyz - camPosD);\n" +
                            "                                float eRad = float(sanitizeRadiusD(pb.planets[eIdx].posAndRadius.w));\n" +
                            "                                float eTempC = pb.planets[eIdx].shadeParams.w;\n" +
                            "                                bool isStar = (pb.planets[eIdx].shadeParams.x > 0.5);\n" +
                            "                                \n" +
                            "                                float thermalPower = isStar ? 1.65 : clamp((eTempC - 550.0) / 1400.0, 0.0, 2.2);\n" +
                            "                                vec3 toLight = ePos - hitRelWorld;\n" +
                            "                                float distToLight = length(toLight);\n" +
                            "                                float atten = isStar ? 1.0 : clamp((eRad * 18.0) / max(distToLight, eRad), 0.05, 1.5);\n" +
                            "                                vec3 eColor = getBlackbodyColor(eTempC) * (thermalPower * atten);\n" +
                            "                                \n" +
                            "                                vec3 L = toLight / max(distToLight, 1e-4);\n" +
                            "                                float ndotl = dot(worldN, L);\n" +
                            "                                float faceDot = dot(worldFaceN, L);\n" +
                            "                                float diff = smoothstep(-0.08, 0.85, ndotl) * smoothstep(-0.28, 0.22, faceDot);\n" +
                            "                                \n" +
                            "                                if (diff > 0.001) {\n" +
                            "                                    float shadow = traceShadow(hitRelWorld + worldFaceN * (r * 0.015), L, distToLight, eRad, i, eIdx, count, camPosD);\n" +
                            "                                    totalLight += eColor * diff * shadow;\n" +
                            "                                    \n" +
                            "                                    vec3 H = normalize(L + viewDir);\n" +
                            "                                    float spec = pow(max(dot(worldN, H), 0.0), 56.0) * isWater * diff * shadow;\n" +
                            "                                    totalSpec += eColor * spec * 0.65;\n" +
                            "                                }\n" +
                            "                            }\n" +
                            "                        } else {\n" +
                            "                            vec3 L = normalize(vec3(0.65, 0.55, -0.52));\n" +
                            "                            float ndotl = dot(worldN, L);\n" +
                            "                            float faceDot = dot(worldFaceN, L);\n" +
                            "                            float diff = smoothstep(-0.08, 0.85, ndotl) * smoothstep(-0.28, 0.22, faceDot);\n" +
                            "                            float shadow = traceShadow(hitRelWorld + worldFaceN * (r * 0.015), L, 1e12, 500.0, i, -1, count, camPosD);\n" +
                            "                            totalLight += vec3(1.2, 1.18, 1.14) * diff * shadow;\n" +
                            "                        }\n" +
                            "                    }\n" +
                            "                    \n" +
                            "                    pCol = albedo * totalLight + totalSpec;\n" +
                            "                    \n" +
                            "                    if (tempC > 550.0) {\n" +
                            "                        float glowStrength = clamp((tempC - 550.0) / 1600.0, 0.0, 2.8);\n" +
                            "                        vec3 bbCol = getBlackbodyColor(tempC);\n" +
                            "                        pCol = mix(pCol, bbCol * (1.2 + glowStrength), clamp(glowStrength * 0.75, 0.0, 0.95));\n" +
                            "                    }\n" +
                            "                }\n" +
                            "                \n" +
                            "                if (alpha < 0.99) {\n" +
                            "                    col = mix(col, pCol, alpha);\n" +
                            "                } else {\n" +
                            "                    closestT = hitT;\n" +
                            "                    hitPlanetIdx = i;\n" +
                            "                    col = pCol;\n" +
                            "                }\n" +
                            "            }\n" +
                            "        }\n" +
                            "    }\n" +
                            "    \n" +
                            "    // 3. 3D-РЕНДЕР КАМЕННЫХ ОБЛОМКОВ И ОБЪЁМНЫХ ОБЛАКОВ ГАЗА\n" +
                            "    vec3 gasAccumColor = vec3(0.0);\n" +
                            "    float gasAlphaAccum = 0.0;\n" +
                            "    \n" +
                            "    for (int d = 0; d < debCount; d++) {\n" +
                            "        float isFrag = db.debris[d].rotAndType.y;\n" +
                            "        if (isFrag < 0.5 && gasAlphaAccum >= 0.95) break;\n" +
                            "        \n" +
                            "        dvec3 dRelD = db.debris[d].posAndSize.xyz - camPosD;\n" +
                            "        double dSizeD = sanitizeRadiusD(db.debris[d].posAndSize.w);\n" +
                            "        vec3 dPos = vec3(dRelD);\n" +
                            "        float dSize = float(dSizeD);\n" +
                            "        float proj = dot(dPos, rayDir);\n" +
                            "        if (proj <= 0.0 || proj >= closestT) continue;\n" +
                            "        \n" +
                            "        vec3 ortho = dPos - rayDir * proj;\n" +
                            "        float bSq = dot(ortho, ortho);\n" +
                            "        \n" +
                            "        if (isFrag > 0.5) {\n" +
                            "            if (bSq > dSize * dSize * 3.1) continue;\n" +
                            "            float ang = db.debris[d].rotAndType.x;\n" +
                            "            vec3 ro = vec3(rotateYD(rotateZD(-dRelD, -ang * 0.7), -ang) / dSizeD);\n" +
                            "            vec3 rd = rotateY(rotateZ(rayDir, -ang * 0.7), -ang);\n" +
                            "            vec3 safeRd = vec3(\n" +
                            "                abs(rd.x) < 1e-7 ? 1e-7 : rd.x,\n" +
                            "                abs(rd.y) < 1e-7 ? 1e-7 : rd.y,\n" +
                            "                abs(rd.z) < 1e-7 ? 1e-7 : rd.z\n" +
                            "            );\n" +
                            "            vec3 invDir = 1.0 / safeRd;\n" +
                            "            vec3 t0 = (-vec3(1.0) - ro) * invDir;\n" +
                            "            vec3 t1 = ( vec3(1.0) - ro) * invDir;\n" +
                            "            vec3 tmin = min(t0, t1);\n" +
                            "            vec3 tmax = max(t0, t1);\n" +
                            "            float tNear = max(max(tmin.x, tmin.y), tmin.z);\n" +
                            "            float tFar = min(min(tmax.x, tmax.y), tmax.z);\n" +
                            "            if (tNear < tFar && tFar > 0.0) {\n" +
                            "                float hitT = float(double((tNear > 0.0) ? tNear : tFar) * dSizeD);\n" +
                            "                if (hitT < closestT) {\n" +
                            "                    closestT = hitT;\n" +
                            "                    float dTemp = db.debris[d].colorAndTemp.w;\n" +
                            "                    vec3 nPos = clamp(ro + rd * (hitT / dSize), -1.0, 1.0);\n" +
                            "                    vec2 fuv = vec2(nPos.x * 0.5 + 0.5, nPos.y * 0.5 + 0.5);\n" +
                            "                    vec2 uv0 = db.debris[d].rotAndType.zw;\n" +
                            "                    vec2 texUV = uv0 + clamp(fuv, 0.01, 0.99) * (1.0 / 16.0);\n" +
                            "                    vec3 baseCol = textureLod(planetTexArray, vec3(texUV, 12.0), 0.0).rgb * 0.75;\n" +
                            "                    if (dTemp > 550.0) {\n" +
                            "                        float glow = clamp((dTemp - 550.0) / 1500.0, 0.0, 2.5);\n" +
                            "                        baseCol = mix(baseCol, getBlackbodyColor(dTemp) * (1.3 + glow), clamp(glow * 0.8, 0.0, 0.95));\n" +
                            "                    }\n" +
                            "                    col = baseCol;\n" +
                            "                }\n" +
                            "            }\n" +
                            "        } else {\n" +
                            "            float gasRad = dSize * 2.4;\n" +
                            "            if (bSq < gasRad * gasRad) {\n" +
                            "                float rNorm = sqrt(bSq) / max(gasRad, 1e-4);\n" +
                            "                vec3 sampleWorld = (rayDir * proj - dPos) / max(gasRad, 1e-4);\n" +
                            "                float turb = 0.65 * noise3D(sampleWorld * 2.8 + vec3(time * 0.25)) + 0.35 * noise3D(sampleWorld * 5.6 - vec3(time * 0.4));\n" +
                            "                float puffDensity = smoothstep(1.0, 0.1, rNorm + (turb - 0.5) * 0.38);\n" +
                            "                float lifeFade = clamp(db.debris[d].rotAndType.x, 0.0, 1.0);\n" +
                            "                float density = puffDensity * puffDensity * lifeFade * 0.32;\n" +
                            "                if (density > 0.002) {\n" +
                            "                    vec3 gasCol = db.debris[d].colorAndTemp.rgb;\n" +
                            "                    float dTemp = db.debris[d].colorAndTemp.w;\n" +
                            "                    if (dTemp > 550.0) {\n" +
                            "                        float glow = clamp((dTemp - 550.0) / 1600.0, 0.0, 2.2);\n" +
                            "                        gasCol = mix(gasCol, getBlackbodyColor(dTemp) * (1.2 + glow), clamp(glow * 0.85, 0.0, 0.95));\n" +
                            "                    } else if (emitterCount > 0) {\n" +
                            "                        int eIdx = emitterIndices[0];\n" +
                            "                        vec3 ePos = vec3(pb.planets[eIdx].posAndRadius.xyz - camPosD);\n" +
                            "                        vec3 L = normalize(ePos - dPos);\n" +
                            "                        float phase = 0.45 + 0.55 * max(dot(normalize(-ortho + rayDir * dSize * 0.5), L), 0.0);\n" +
                            "                        gasCol *= phase;\n" +
                            "                    }\n" +
                            "                    gasAccumColor += gasCol * density * (1.0 - gasAlphaAccum);\n" +
                            "                    gasAlphaAccum = min(0.96, gasAlphaAccum + density * (1.0 - gasAlphaAccum));\n" +
                            "                }\n" +
                            "            }\n" +
                            "        }\n" +
                            "    }\n" +
                            "    \n" +
                            "    // 4. 100% GPU 64-BIT DOUBLE БЕСКОНЕЧНАЯ СЕТКА\n" +
                            "    if (showGrid > 0.5 && abs(rayDir.y) > 0.0001) {\n" +
                            "        float t = float(-camPosD.y) / rayDir.y;\n" +
                            "        if (t > 0.0 && t < closestT) {\n" +
                            "            double effDist = max(10.0LF, abs(pc.camPosAndDist.w));\n" +
                            "            float logY = log(float(effDist)) / 2.302585093;\n" +
                            "            float floorLog = floor(logY);\n" +
                            "            double minorStepD = double(pow(10.0, floorLog));\n" +
                            "            float minorStep = float(minorStepD);\n" +
                            "            float minorAlphaMod = pow(1.0 - (logY - floorLog), 1.5);\n" +
                            "            \n" +
                            "            vec2 hitRelXZ = rayDir.xz * t;\n" +
                            "            float renderDist = minorStep * 220.0;\n" +
                            "            float distToCam = length(hitRelXZ);\n" +
                            "            \n" +
                            "            if (distToCam < renderDist) {\n" +
                            "                float fadeCenter = pow(1.0 - (distToCam / renderDist), 2.5);\n" +
                            "                dvec2 worldXZ = camPosD.xz + dvec2(rayDir.xz) * double(t);\n" +
                            "                dvec2 scaledXZ = worldXZ / minorStepD;\n" +
                            "                vec2 gridFract = vec2(abs(fract(scaledXZ + dvec2(0.5LF)) - dvec2(0.5LF)));\n" +
                            "                vec2 majorGridFract = vec2(abs(fract(scaledXZ * 0.1LF + dvec2(0.5LF)) - dvec2(0.5LF)));\n" +
                            "                \n" +
                            "                float invY = 1.0 / max(abs(rayDir.y), 0.04);\n" +
                            "                float basePx = (t / (height * minorStep)) * 1.35;\n" +
                            "                vec2 deriv = vec2(\n" +
                            "                    basePx * sqrt(1.0 + rayDir.x * rayDir.x * invY * invY),\n" +
                            "                    basePx * sqrt(1.0 + rayDir.z * rayDir.z * invY * invY)\n" +
                            "                );\n" +
                            "                deriv = clamp(deriv, vec2(0.0015), vec2(0.22));\n" +
                            "                vec2 horizonFade = 1.0 - smoothstep(vec2(0.06), vec2(0.20), deriv);\n" +
                            "                \n" +
                            "                vec2 lines = smoothstep(deriv * 1.4, deriv * 0.4, gridFract) * horizonFade;\n" +
                            "                float minorLine = max(lines.x, lines.y);\n" +
                            "                \n" +
                            "                vec2 majorDeriv = clamp(deriv * 0.1, vec2(0.0005), vec2(0.22));\n" +
                            "                vec2 majorFade = 1.0 - smoothstep(vec2(0.06), vec2(0.20), majorDeriv);\n" +
                            "                vec2 majorLines = smoothstep(majorDeriv * 1.4, majorDeriv * 0.4, majorGridFract) * majorFade;\n" +
                            "                float majorLine = max(majorLines.x, majorLines.y);\n" +
                            "                \n" +
                            "                float grazingFade = smoothstep(0.005, 0.08, abs(rayDir.y));\n" +
                            "                float lineAlpha = max(minorLine * 0.45 * minorAlphaMod, majorLine * 0.82) * fadeCenter * grazingFade;\n" +
                            "                col = mix(col, vec3(1.0), clamp(lineAlpha, 0.0, 1.0));\n" +
                            "            }\n" +
                            "        }\n" +
                            "    }\n" +
                            "    \n" +
                            "    col = col * (1.0 - gasAlphaAccum) + gasAccumColor;\n" +
                            "    \n" +
                            "    // 5. СОЛНЕЧНЫЕ И ТЕРМИЧЕСКИЕ ЛУЧИ (БЕЗ ПРОСВЕЧИВАНИЯ СКВОЗЬ СОЛНЦЕ И ПЛАНЕТЫ!)\n" +
                            "    vec3 solarEffects = vec3(0.0);\n" +
                            "    float dither = hash2D(vec2(coord) + fract(time * 0.73));\n" +
                            "    \n" +
                            "    for (int s = 0; s < emitterCount; s++) {\n" +
                            "        int sIdx = emitterIndices[s];\n" +
                            "        if (pb.planets[sIdx].shadeParams.z < 0.5) continue;\n" +
                            "        \n" +
                            "        bool isStar = (pb.planets[sIdx].shadeParams.x > 0.5);\n" +
                            "        float tempC = pb.planets[sIdx].shadeParams.w;\n" +
                            "        if (!isStar && tempC < 900.0) continue;\n" +
                            "        \n" +
                            "        vec3 toStar = vec3(pb.planets[sIdx].posAndRadius.xyz - camPosD);\n" +
                            "        float sRad = float(sanitizeRadiusD(pb.planets[sIdx].posAndRadius.w));\n" +
                            "        float distStar = length(toStar);\n" +
                            "        \n" +
                            "        // ИСПРАВЛЕНО: если пиксель попал в сам куб звезды (hitPlanetIdx == sIdx) ИЛИ в твёрдую планету перед звездой (closestT < distStar),\n" +
                            "        // лучи НЕ рисуются поверх поверхности и НЕ просвечивают сквозь планеты и Солнце!\n" +
                            "        if (hitPlanetIdx == sIdx || closestT < distStar) continue;\n" +
                            "        \n" +
                            "        vec3 starDir = toStar / max(distStar, 1e-4);\n" +
                            "        float cosTheta = dot(rayDir, starDir);\n" +
                            "        float viewFade = smoothstep(0.12, 0.62, cosTheta);\n" +
                            "        if (viewFade > 0.001 && !hitBlackHoleCube) {\n" +
                            "            float tProj = dot(toStar, rayDir);\n" +
                            "            vec3 orthoVec = (rayDir * tProj) - toStar;\n" +
                            "            vec3 sRight = normalize(cross(starDir, abs(starDir.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));\n" +
                            "            vec3 sUp = cross(sRight, starDir);\n" +
                            "            \n" +
                            "            vec2 deltaUV = vec2(dot(orthoVec, sRight), dot(orthoVec, sUp)) / distStar;\n" +
                            "            float screenDist = length(deltaUV);\n" +
                            "            float appRad = max(0.004, (sRad * 1.15) / distStar);\n" +
                            "            float angle = atan(deltaUV.y, deltaUV.x);\n" +
                            "            \n" +
                            "            const int RAY_SAMPLES = 18;\n" +
                            "            float unoccludedSamples = 0.0;\n" +
                            "            \n" +
                            "            for (int m = 0; m < RAY_SAMPLES; m++) {\n" +
                            "                float frac = (float(m) + dither) / float(RAY_SAMPLES);\n" +
                            "                vec3 targetPt = toStar + normalize(orthoVec + vec3(1e-5)) * (sRad * 0.78 * (1.0 - frac));\n" +
                            "                vec3 sampleDir = normalize(mix(rayDir, normalize(targetPt), frac));\n" +
                            "                \n" +
                            "                float sampleBlocked = 1.0;\n" +
                            "                for (int k = 0; k < count; k++) {\n" +
                            "                    if (k == sIdx || pb.planets[k].rotAndFlags.z < 0.99) continue;\n" +
                            "                    if (pb.planets[k].shadeParams.x > 0.5 && pb.planets[k].shadeParams.x < 1.5) continue;\n" +
                            "                    float tOcc = intersectPlanetBox(vec3(0.0), sampleDir, k, camPosD);\n" +
                            "                    if (tOcc > 0.0 && tOcc < distStar) {\n" +
                            "                        sampleBlocked = 0.0;\n" +
                            "                        break;\n" +
                            "                    }\n" +
                            "                }\n" +
                            "                unoccludedSamples += sampleBlocked;\n" +
                            "            }\n" +
                            "            float shaftVisibility = (unoccludedSamples / float(RAY_SAMPLES)) * viewFade;\n" +
                            "            \n" +
                            "            // Лучи начинаются строго от внешней поверхности звезды (surfaceDist >= 0), не создавая точки в центре!\n" +
                            "            float normDist = max(0.0, (screenDist - appRad * 0.72) / appRad);\n" +
                            "            float innerCorona = 1.55 / (1.0 + normDist * normDist * 2.2);\n" +
                            "            float outerBloom  = 0.52 / (1.0 + normDist * 1.45 + normDist * normDist * 0.18);\n" +
                            "            \n" +
                            "            float streamer = noiseAngle(angle, 9.0, time * 0.18) * noiseAngle(angle + 1.3, 17.0, -time * 0.12);\n" +
                            "            float streamerRays = pow(streamer, 2.2) * (2.6 / (1.0 + normDist * 2.5 + normDist * normDist * 0.22));\n" +
                            "            \n" +
                            "            float spike6 = pow(max(0.0, cos(angle * 3.0)), 36.0) * (1.6 / (1.0 + normDist * 1.8));\n" +
                            "            float anamorphic = exp(-abs(deltaUV.y) / max(appRad * 0.35, 0.0015)) / (1.0 + abs(deltaUV.x) / max(appRad * 4.5, 0.01));\n" +
                            "            \n" +
                            "            float rayPower = isStar ? 1.0 : clamp((tempC - 900.0) / 2200.0, 0.0, 1.0);\n" +
                            "            vec3 sCol = getBlackbodyColor(tempC) * rayPower;\n" +
                            "            vec3 starGlare = sCol * (innerCorona + outerBloom + streamerRays + spike6 * 0.7 + anamorphic * 0.65) * shaftVisibility;\n" +
                            "            starGlare.r *= 1.0 + 0.12 * smoothstep(1.0, 6.0, normDist);\n" +
                            "            starGlare.b *= 1.0 - 0.08 * smoothstep(1.0, 6.0, normDist);\n" +
                            "            \n" +
                            "            solarEffects += starGlare;\n" +
                            "        }\n" +
                            "    }\n" +
                            "    \n" +
                            "    col += solarEffects;\n" +
                            "    col = acesToneMap(col);\n" +
                            "    \n" +
                            "    imageStore(resultImage, coord, vec4(col, 1.0));\n" +
                            "}\n";

            long compiler = Shaderc.shaderc_compiler_initialize();
            long result = Shaderc.shaderc_compile_into_spv(compiler, glsl, Shaderc.shaderc_glsl_compute_shader, "space.comp", "main", 0);
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                throw new RuntimeException("[SBox Compute] ОШИБКА ШЕЙДЕРА:\n" + Shaderc.shaderc_result_get_error_message(result));
            }
            ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);

            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spirv);

            LongBuffer pShaderModule = stack.mallocLong(1);
            vkCreateShaderModule(device, moduleInfo, null, pShaderModule);
            long shaderModule = pShaderModule.get(0);

            Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compiler_release(compiler);

            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(4, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(3).binding(3).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);

            LongBuffer pSetLayout = stack.mallocLong(1);
            vkCreateDescriptorSetLayout(device, layoutInfo, null, pSetLayout);
            descriptorSetLayout = pSetLayout.get(0);

            VkPushConstantRange.Buffer pushConstant = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                    .offset(0)
                    .size(64);

            VkPipelineLayoutCreateInfo pPipelineLayoutCreateInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout))
                    .pPushConstantRanges(pushConstant);

            LongBuffer pPipelineLayout = stack.mallocLong(1);
            vkCreatePipelineLayout(device, pPipelineLayoutCreateInfo, null, pPipelineLayout);
            pipelineLayout = pPipelineLayout.get(0);

            VkPipelineShaderStageCreateInfo stageInfo = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule)
                    .pName(stack.UTF8("main"));

            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                    .stage(stageInfo)
                    .layout(pipelineLayout);

            LongBuffer pComputePipeline = stack.mallocLong(1);
            vkCreateComputePipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pComputePipeline);
            computePipeline = pComputePipeline.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);
            poolSizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .pPoolSizes(poolSizes)
                    .maxSets(1);

            LongBuffer pDescriptorPool = stack.mallocLong(1);
            vkCreateDescriptorPool(device, poolInfo, null, pDescriptorPool);
            descriptorPool = pDescriptorPool.get(0);

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout));

            LongBuffer pDescriptorSet = stack.mallocLong(1);
            vkAllocateDescriptorSets(device, allocInfo, pDescriptorSet);
            descriptorSet = pDescriptorSet.get(0);

            VkDescriptorImageInfo.Buffer canvasInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .imageView(SBoxVulkanCanvas.canvasImageView);

            VkDescriptorBufferInfo.Buffer bufInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(planetBuffer)
                    .offset(0)
                    .range(BUFFER_SIZE);

            VkDescriptorImageInfo.Buffer planetTexInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(planetArrayImageView)
                    .sampler(planetSampler);

            VkDescriptorBufferInfo.Buffer debBufInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(debrisBuffer)
                    .offset(0)
                    .range(DEBRIS_BUFFER_SIZE);

            VkWriteDescriptorSet.Buffer writeSets = VkWriteDescriptorSet.calloc(4, stack);
            writeSets.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSet).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(canvasInfo);
            writeSets.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSet).dstBinding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(bufInfo);
            writeSets.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSet).dstBinding(2)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(planetTexInfo);
            writeSets.get(3).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSet).dstBinding(3)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(debBufInfo);

            vkUpdateDescriptorSets(device, writeSets, null);
            vkDestroyShaderModule(device, shaderModule, null);

            VkCommandBuffer cmd = SBoxVulkanCore.mainCommandBuffer;
            vkResetCommandBuffer(cmd, 0);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(cmd, beginInfo);

            VkImageMemoryBarrier.Buffer initBarrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcAccessMask(0)
                    .dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(SBoxVulkanCanvas.canvasImage);
            initBarrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);

            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, initBarrier);
            vkEndCommandBuffer(cmd);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(cmd));
            vkQueueSubmit(SBoxVulkanCore.graphicsQueue, submitInfo, VK_NULL_HANDLE);
            vkQueueWaitIdle(SBoxVulkanCore.graphicsQueue);
        }
    }

    private static void createBuffers(MemoryStack stack, VkDevice device) {
        VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(BUFFER_SIZE)
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        LongBuffer pBuffer = stack.mallocLong(1);
        vkCreateBuffer(device, bufferInfo, null, pBuffer);
        planetBuffer = pBuffer.get(0);

        VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
        vkGetBufferMemoryRequirements(device, planetBuffer, memReqs);

        VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(memReqs.size())
                .memoryTypeIndex(findMemoryType(memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
        LongBuffer pMemory = stack.mallocLong(1);
        vkAllocateMemory(device, allocInfo, null, pMemory);
        planetBufferMemory = pMemory.get(0);
        vkBindBufferMemory(device, planetBuffer, planetBufferMemory, 0);

        PointerBuffer pData1 = stack.mallocPointer(1);
        vkMapMemory(device, planetBufferMemory, 0, BUFFER_SIZE, 0, pData1);
        mappedPlanetBuffer = pData1.getByteBuffer(0, BUFFER_SIZE).order(ByteOrder.nativeOrder());

        VkBufferCreateInfo debInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(DEBRIS_BUFFER_SIZE)
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        vkCreateBuffer(device, debInfo, null, pBuffer);
        debrisBuffer = pBuffer.get(0);

        vkGetBufferMemoryRequirements(device, debrisBuffer, memReqs);
        allocInfo.allocationSize(memReqs.size())
                .memoryTypeIndex(findMemoryType(memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
        vkAllocateMemory(device, allocInfo, null, pMemory);
        debrisBufferMemory = pMemory.get(0);
        vkBindBufferMemory(device, debrisBuffer, debrisBufferMemory, 0);

        PointerBuffer pData2 = stack.mallocPointer(1);
        vkMapMemory(device, debrisBufferMemory, 0, DEBRIS_BUFFER_SIZE, 0, pData2);
        mappedDebrisBuffer = pData2.getByteBuffer(0, DEBRIS_BUFFER_SIZE).order(ByteOrder.nativeOrder());
    }

    private static void loadAllPlanetTextures(MemoryStack stack, VkDevice device) {
        int layerCount = PLANET_TEXTURES.length;
        int layerBytes = TEX_SIZE * TEX_SIZE * 4;
        long totalSize = (long) layerCount * layerBytes;

        ByteBuffer allPixels = BufferUtils.createByteBuffer((int) totalSize);

        for (int layer = 0; layer < layerCount; layer++) {
            String path = PLANET_TEXTURES[layer];
            try {
                byte[] bytes = readResourceBytes(path);
                BufferedImage bi = (bytes != null) ? ImageIO.read(new ByteArrayInputStream(bytes)) : null;
                if (bi != null) {
                    int srcW = bi.getWidth();
                    int srcH = bi.getHeight();
                    int[] rgb = bi.getRGB(0, 0, srcW, srcH, null, 0, srcW);
                    for (int y = 0; y < TEX_SIZE; y++) {
                        int sy = (y * srcH) / TEX_SIZE;
                        for (int x = 0; x < TEX_SIZE; x++) {
                            int sx = (x * srcW) / TEX_SIZE;
                            int p = rgb[sy * srcW + sx];
                            allPixels.put((byte) ((p >> 16) & 0xFF));
                            allPixels.put((byte) ((p >> 8) & 0xFF));
                            allPixels.put((byte) (p & 0xFF));
                            allPixels.put((byte) ((p >> 24) & 0xFF));
                        }
                    }
                } else {
                    for (int i = 0; i < TEX_SIZE * TEX_SIZE; i++) {
                        allPixels.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 255);
                    }
                }
            } catch (Exception e) {
                for (int i = 0; i < TEX_SIZE * TEX_SIZE; i++) {
                    allPixels.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 255);
                }
            }
        }
        allPixels.flip();

        VkBufferCreateInfo stagingInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(totalSize)
                .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        LongBuffer pStagingBuf = stack.mallocLong(1);
        vkCreateBuffer(device, stagingInfo, null, pStagingBuf);
        long stagingBuf = pStagingBuf.get(0);

        VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
        vkGetBufferMemoryRequirements(device, stagingBuf, memReqs);
        VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(memReqs.size())
                .memoryTypeIndex(findMemoryType(memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
        LongBuffer pStagingMem = stack.mallocLong(1);
        vkAllocateMemory(device, allocInfo, null, pStagingMem);
        long stagingMem = pStagingMem.get(0);
        vkBindBufferMemory(device, stagingBuf, stagingMem, 0);

        PointerBuffer data = stack.mallocPointer(1);
        vkMapMemory(device, stagingMem, 0, totalSize, 0, data);
        MemoryUtil.memCopy(MemoryUtil.memAddress(allPixels), data.get(0), totalSize);
        vkUnmapMemory(device, stagingMem);

        VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK_IMAGE_TYPE_2D)
                .extent(it -> it.width(TEX_SIZE).height(TEX_SIZE).depth(1))
                .mipLevels(1)
                .arrayLayers(layerCount)
                .format(VK_FORMAT_R8G8B8A8_UNORM)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        LongBuffer pImg = stack.mallocLong(1);
        vkCreateImage(device, imageInfo, null, pImg);
        planetArrayImage = pImg.get(0);

        vkGetImageMemoryRequirements(device, planetArrayImage, memReqs);
        allocInfo.allocationSize(memReqs.size())
                .memoryTypeIndex(findMemoryType(memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
        LongBuffer pImgMem = stack.mallocLong(1);
        vkAllocateMemory(device, allocInfo, null, pImgMem);
        planetArrayMemory = pImgMem.get(0);
        vkBindImageMemory(device, planetArrayImage, planetArrayMemory, 0);

        VkCommandBuffer cmd = SBoxVulkanCore.mainCommandBuffer;
        vkResetCommandBuffer(cmd, 0);
        VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        vkBeginCommandBuffer(cmd, beginInfo);

        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(planetArrayImage)
                .subresourceRange(it -> it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(layerCount))
                .srcAccessMask(0)
                .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);

        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
                .imageSubresource(it -> it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(layerCount))
                .imageOffset(it -> it.x(0).y(0).z(0))
                .imageExtent(it -> it.width(TEX_SIZE).height(TEX_SIZE).depth(1));
        vkCmdCopyBufferToImage(cmd, stagingBuf, planetArrayImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

        barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, barrier);

        vkEndCommandBuffer(cmd);
        VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SUBMIT_INFO).pCommandBuffers(stack.pointers(cmd));
        vkQueueSubmit(SBoxVulkanCore.graphicsQueue, submitInfo, VK_NULL_HANDLE);
        vkQueueWaitIdle(SBoxVulkanCore.graphicsQueue);

        vkDestroyBuffer(device, stagingBuf, null);
        vkFreeMemory(device, stagingMem, null);

        VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(planetArrayImage)
                .viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY)
                .format(VK_FORMAT_R8G8B8A8_UNORM)
                .subresourceRange(it -> it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(layerCount));
        LongBuffer pView = stack.mallocLong(1);
        vkCreateImageView(device, viewInfo, null, pView);
        planetArrayImageView = pView.get(0);

        VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(VK_FILTER_NEAREST)
                .minFilter(VK_FILTER_NEAREST)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
        LongBuffer pSampler = stack.mallocLong(1);
        vkCreateSampler(device, samplerInfo, null, pSampler);
        planetSampler = pSampler.get(0);
    }

    private static void writePlanetToBuffer(
            int index, Vector3d pos, double radius, float tiltDeg, float angleDeg,
            float alpha, String texPath, String type, boolean flashlightOn, boolean raysOn,
            float temp, float[] flatUv) {
        int base = index * PLANET_STRIDE;
        double safeRadius = (Double.isNaN(radius) || Double.isInfinite(radius) || radius < 1e-4) ? 1e-4 : radius;

        mappedPlanetBuffer.putDouble(base, pos.x);
        mappedPlanetBuffer.putDouble(base + 8, pos.y);
        mappedPlanetBuffer.putDouble(base + 16, pos.z);
        mappedPlanetBuffer.putDouble(base + 24, safeRadius);

        int texLayer = texIndexMap.getOrDefault(texPath, 0);
        mappedPlanetBuffer.putFloat(base + 32, (float) Math.toRadians(tiltDeg));
        mappedPlanetBuffer.putFloat(base + 36, (float) Math.toRadians(angleDeg));
        mappedPlanetBuffer.putFloat(base + 40, alpha);
        mappedPlanetBuffer.putFloat(base + 44, (float) texLayer);

        float objType = "Star".equals(type) ? 1.0f : ("Hole".equals(type) ? 2.0f : 0.0f);
        mappedPlanetBuffer.putFloat(base + 48, objType);
        mappedPlanetBuffer.putFloat(base + 52, flashlightOn ? 1.0f : 0.0f);
        mappedPlanetBuffer.putFloat(base + 56, raysOn ? 1.0f : 0.0f);
        mappedPlanetBuffer.putFloat(base + 60, temp);

        for (int k = 0; k < 24; k++) {
            mappedPlanetBuffer.putFloat(base + 64 + (k << 2), flatUv[k]);
        }
    }

    // ИСПРАВЛЕНО (Пункт 2 ревью): атомарная синхронизация по SpaceEngine.STATE_LOCK и динамическая проверка границ списка
    private static int[] updateSSBOs() {
        synchronized (SpaceEngine.STATE_LOCK) {
            if (SpaceEngine.planetsNeedSort) {
                SpaceEngine.planets.sort(SpaceEngine.WARP_GROUP_COMPARATOR);
                SpaceEngine.planetsNeedSort = false;
            }

            List<SpaceEngine.Planet> planets = SpaceEngine.planets;
            int pCount = 0;
            for (int i = 0; i < planets.size() && pCount < MAX_PLANETS - 1; i++) {
                SpaceEngine.Planet p = planets.get(i);
                if (p == null || p.mass <= 0.0) continue;
                writePlanetToBuffer(
                        pCount, p.pos, p.radius, (float) p.tilt, (float) p.currentAngle,
                        1.0f, p.texture, p.type, p.flashlightOn, p.raysOn, (float) p.temperature, p.flatUv
                );
                pCount++;
            }

            if (SpaceEngine.isPlacingPlanet && SpaceEngine.menuItems != null && SpaceEngine.selectedPlanetIndex < SpaceEngine.menuItems.length) {
                SpaceEngine.MenuItem item = SpaceEngine.menuItems[SpaceEngine.selectedPlanetIndex];
                Vector3d holoPos = SpaceEngine.getHologramPos(item.radius);
                if (SpaceEngine.isDraggingOrbit && SpaceEngine.orbitTarget != null) {
                    Vector3d outUp = new Vector3d();
                    SpaceEngine.getInclinedOrbitState(SpaceEngine.baseOrbitPos, SpaceEngine.orbitInclination, SpaceEngine.orbitTarget, holoPos, outUp);
                }
                writePlanetToBuffer(
                        pCount, holoPos, item.radius, (float) item.tilt, 0.0f,
                        0.65f, item.planetTex, item.type, true, false, (float) item.temperature, item.flatUv
                );
                pCount++;
            }

            List<SpaceEngine.Fragment> fragments = SpaceEngine.fragments;
            int dCount = 0;
            for (int i = 0; i < fragments.size() && dCount < MAX_DEBRIS; i++) {
                SpaceEngine.Fragment f = fragments.get(i);
                if (f == null || f.life <= 0.0) continue;
                int base = dCount * DEBRIS_STRIDE;
                mappedDebrisBuffer.putDouble(base, f.pos.x);
                mappedDebrisBuffer.putDouble(base + 8, f.pos.y);
                mappedDebrisBuffer.putDouble(base + 16, f.pos.z);
                mappedDebrisBuffer.putDouble(base + 24, Math.max(0.01, f.size));

                mappedDebrisBuffer.putFloat(base + 32, 0.7f);
                mappedDebrisBuffer.putFloat(base + 36, 0.65f);
                mappedDebrisBuffer.putFloat(base + 40, 0.6f);
                mappedDebrisBuffer.putFloat(base + 44, (float) f.temperature);

                mappedDebrisBuffer.putFloat(base + 48, (float) Math.toRadians(f.angle));
                mappedDebrisBuffer.putFloat(base + 52, 1.0f);
                mappedDebrisBuffer.putFloat(base + 56, f.u1);
                mappedDebrisBuffer.putFloat(base + 60, f.v1);
                dCount++;
            }

            List<SpaceEngine.Particle> particles = SpaceEngine.particles;
            for (int i = 0; i < particles.size() && dCount < MAX_DEBRIS; i++) {
                SpaceEngine.Particle pt = particles.get(i);
                if (pt == null || pt.life <= 0.0) continue;
                int base = dCount * DEBRIS_STRIDE;
                mappedDebrisBuffer.putDouble(base, pt.pos.x);
                mappedDebrisBuffer.putDouble(base + 8, pt.pos.y);
                mappedDebrisBuffer.putDouble(base + 16, pt.pos.z);
                mappedDebrisBuffer.putDouble(base + 24, Math.max(0.01, pt.size));

                mappedDebrisBuffer.putFloat(base + 32, pt.r / 255.0f);
                mappedDebrisBuffer.putFloat(base + 36, pt.g / 255.0f);
                mappedDebrisBuffer.putFloat(base + 40, pt.b / 255.0f);
                mappedDebrisBuffer.putFloat(base + 44, (float) pt.temperature);

                float lifeRatio = (float) Math.max(0.0, Math.min(1.0, pt.life / Math.max(pt.maxLife, 0.001)));
                mappedDebrisBuffer.putFloat(base + 48, lifeRatio);
                mappedDebrisBuffer.putFloat(base + 52, 0.0f);
                mappedDebrisBuffer.putFloat(base + 56, 0.0f);
                mappedDebrisBuffer.putFloat(base + 60, 0.0f);
                dCount++;
            }
            return new int[]{pCount, dCount};
        }
    }

    public static void renderSpace(int width, int height) {
        int[] counts = updateSSBOs();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd = SBoxVulkanCore.mainCommandBuffer;

            vkResetCommandBuffer(cmd, 0);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(cmd, beginInfo);

            VkImageMemoryBarrier.Buffer preBarrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(SBoxVulkanCanvas.canvasImage);
            preBarrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);

            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, preBarrier);

            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, computePipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptorSet), null);

            double effDist = Math.max(10.0, Math.abs(CameraManager.pos.y));
            if (CameraManager.isLocked && CameraManager.lockedTarget != null) {
                effDist = Math.max(effDist, Math.max(10.0, SpaceEngine.lockedOrbitDistance));
            }

            float pitch = CameraManager.rot.x;
            float yaw = CameraManager.rot.y;
            float time = (System.currentTimeMillis() - START_TIME) / 1000.0f;

            ByteBuffer pushData = stack.calloc(64).order(ByteOrder.nativeOrder());
            pushData.putDouble(0, CameraManager.pos.x);
            pushData.putDouble(8, CameraManager.pos.y);
            pushData.putDouble(16, CameraManager.pos.z);
            pushData.putDouble(24, effDist);

            pushData.putFloat(32, (float) width);
            pushData.putFloat(36, (float) height);
            pushData.putFloat(40, time);
            pushData.putFloat(44, SpaceEngine.showGrid ? 1.0f : 0.0f);

            pushData.putFloat(48, (float) Math.toRadians(pitch));
            pushData.putFloat(52, (float) Math.toRadians(yaw));
            pushData.putInt(56, counts[0]);
            pushData.putInt(60, counts[1]);
            pushData.position(0).limit(64);

            vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pushData);

            vkCmdDispatch(cmd, (width + 15) / 16, (height + 15) / 16, 1);

            // ИСПРАВЛЕНО (Пункт 5 ревью): синхронизация записи COMPUTE_SHADER_BIT со стадией VK_PIPELINE_STAGE_TRANSFER_BIT для vkCmdBlitImage!
            VkImageMemoryBarrier.Buffer postBarrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(SBoxVulkanCanvas.canvasImage);
            postBarrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);

            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, postBarrier);

            vkEndCommandBuffer(cmd);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(cmd));

            vkQueueSubmit(SBoxVulkanCore.graphicsQueue, submitInfo, VK_NULL_HANDLE);
            vkQueueWaitIdle(SBoxVulkanCore.graphicsQueue);
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
        throw new RuntimeException("Не найден подходящий тип памяти Vulkan!");
    }
}