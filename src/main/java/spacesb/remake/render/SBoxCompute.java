package spacesb.remake.render;

import org.joml.Vector3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.windows.User32;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;
import spacesb.remake.CameraManager;
import spacesb.remake.PhysicsEngine;
import spacesb.remake.SpaceEngine;
import spacesb.remake.SpaceUI;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

public class SBoxCompute {
    public static long pipelineLayout = VK_NULL_HANDLE, computePipeline = VK_NULL_HANDLE;
    public static long descriptorSetLayout = VK_NULL_HANDLE, descriptorPool = VK_NULL_HANDLE, descriptorSet = VK_NULL_HANDLE;
    public static long frameFence = VK_NULL_HANDLE;

    public static long planetBuffer = VK_NULL_HANDLE, planetBufferMemory = VK_NULL_HANDLE;
    public static ByteBuffer mappedPlanetBuffer;

    public static long debrisBuffer = VK_NULL_HANDLE, debrisBufferMemory = VK_NULL_HANDLE;
    public static ByteBuffer mappedDebrisBuffer;

    public static long planetArrayImage = VK_NULL_HANDLE, planetArrayMemory = VK_NULL_HANDLE;
    public static long planetArrayImageView = VK_NULL_HANDLE, planetSampler = VK_NULL_HANDLE;

    private static double shaderAnimTime = 0.0, cloudSimTime = 0.0;
    private static long lastShaderFrameMs = System.currentTimeMillis();
    private static boolean windowIconApplied = false;

    private static final int MAX_PLANETS = 64, PLANET_STRIDE = 576, BUFFER_SIZE = MAX_PLANETS * PLANET_STRIDE;
    private static final int MAX_DEBRIS = PhysicsEngine.MAX_GPU_DEBRIS, DEBRIS_STRIDE = 48, DEBRIS_BUFFER_SIZE = MAX_DEBRIS * DEBRIS_STRIDE;
    private static final float[] SCRATCH_CRATER_DATA = new float[100];
    private static final int TEX_SIZE = 512;

    private static final String[] PLANET_TEXTURES = {
            "assets/spacesb-remake/textures/planet/earth.png",
            "assets/spacesb-remake/textures/planet/mercury.png",
            "assets/spacesb-remake/textures/planet/venus.png",
            "assets/spacesb-remake/textures/planet/moon.png",
            "assets/spacesb-remake/textures/planet/mars.png",
            "assets/spacesb-remake/textures/planet/jupiter.png",
            "assets/spacesb-remake/textures/planet/saturn.png",
            "assets/spacesb-remake/textures/planet/uranus.png",
            "assets/spacesb-remake/textures/planet/neptune.png",
            "assets/spacesb-remake/textures/planet/titan.png",
            "assets/spacesb-remake/textures/planet/enceladus.png",
            "assets/spacesb-remake/textures/planet/sun.png",
            "assets/spacesb-remake/textures/planet/stephenson2_18.png",
            "assets/spacesb-remake/textures/planet/sa.png",
            "assets/spacesb-remake/textures/particle/fragments.png"
    };
    private static final Map<String, Integer> texIndexMap = new HashMap<>();
    private static int fragmentLayerIndex = 14;

    static {
        for (int i = 0; i < PLANET_TEXTURES.length; i++) {
            texIndexMap.put(PLANET_TEXTURES[i], i);
            if (PLANET_TEXTURES[i].endsWith("fragments.png")) fragmentLayerIndex = i;
        }
    }

    private static int resolveTexLayer(String texPath, String objName) {
        if (texPath != null) {
            Integer idx = texIndexMap.get(texPath);
            if (idx != null) return idx;
            String lower = texPath.toLowerCase();
            if (lower.contains("mars")) return 4;
            if (lower.contains("neptune")) return 8;
            if (lower.contains("uranus")) return 7;
            if (lower.contains("saturn")) return 6;
            if (lower.contains("jupiter")) return 5;
            if (lower.contains("moon")) return 3;
            if (lower.contains("venus")) return 2;
            if (lower.contains("mercury")) return 1;
            if (lower.contains("titan")) return 9;
            if (lower.contains("enceladus")) return 10;
            if (lower.contains("stephenson")) return 12;
            if (lower.contains("sun")) return 11;
            if (lower.contains("sa.png")) return 13;
        }
        if (objName != null) {
            String lowerName = objName.toLowerCase();
            if (lowerName.contains("mars")) return 4;
            if (lowerName.contains("neptune")) return 8;
        }
        return 0;
    }

    public static void refreshWindowIcons(long window) {
        windowIconApplied = false;
        applyCustomWindowIcons(window);
    }

    public static void applyCustomWindowIcons(long window) {
        if (window == 0L || windowIconApplied) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            String[] iconPaths = {"assets/spacesb-remake/textures/icon.png", "assets/spacesb-remake/icon.png"};
            BufferedImage baseImg = null;
            for (String path : iconPaths) {
                byte[] bytes = readResourceBytes(path);
                if (bytes != null) {
                    baseImg = ImageIO.read(new ByteArrayInputStream(bytes));
                    if (baseImg != null) break;
                }
            }
            if (baseImg == null) return;
            try {
                long hwnd = GLFWNativeWin32.glfwGetWin32Window(window);
                if (hwnd != 0L) {
                    int gwlExStyle = User32.GWL_EXSTYLE;
                    long exStyle = User32.GetWindowLongPtr(hwnd, gwlExStyle);
                    if ((exStyle & 0x00000001L) != 0L) User32.SetWindowLongPtr(hwnd, gwlExStyle, exStyle & ~0x00000001L);
                }
            } catch (Throwable ignored) {}

            int[] sizes = {16, 24, 32, 48, 64, 128, 256};
            ByteBuffer[] nativeBuffers = new ByteBuffer[sizes.length];
            GLFWImage.Buffer images = GLFWImage.malloc(sizes.length, stack);
            try {
                for (int idx = 0; idx < sizes.length; idx++) {
                    int s = sizes[idx];
                    BufferedImage scaled = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g2d = scaled.createGraphics();
                    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    g2d.drawImage(baseImg, 0, 0, s, s, null);
                    g2d.dispose();
                    int[] rgb = scaled.getRGB(0, 0, s, s, null, 0, s);
                    ByteBuffer pixels = MemoryUtil.memAlloc(s * s * 4);
                    nativeBuffers[idx] = pixels;
                    for (int p : rgb) {
                        pixels.put((byte) ((p >> 16) & 0xFF)).put((byte) ((p >> 8) & 0xFF)).put((byte) (p & 0xFF)).put((byte) ((p >> 24) & 0xFF));
                    }
                    pixels.flip();
                    images.get(idx).set(s, s, pixels);
                }
                GLFW.glfwSetWindowIcon(window, images);
                windowIconApplied = true;
            } finally {
                for (ByteBuffer buf : nativeBuffers) if (buf != null) MemoryUtil.memFree(buf);
            }
        } catch (Exception ignored) {}
    }

    public static void destroyCompute() {
        VkDevice device = SBoxVulkanCore.logicalDevice;
        if (device == null) return;
        if (frameFence != VK_NULL_HANDLE) { vkDestroyFence(device, frameFence, null); frameFence = VK_NULL_HANDLE; }
        if (computePipeline != VK_NULL_HANDLE) { vkDestroyPipeline(device, computePipeline, null); computePipeline = VK_NULL_HANDLE; }
        if (pipelineLayout != VK_NULL_HANDLE) { vkDestroyPipelineLayout(device, pipelineLayout, null); pipelineLayout = VK_NULL_HANDLE; }
        if (descriptorSetLayout != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null); descriptorSetLayout = VK_NULL_HANDLE; }
        if (descriptorPool != VK_NULL_HANDLE) { vkDestroyDescriptorPool(device, descriptorPool, null); descriptorPool = VK_NULL_HANDLE; }
    }

    public static void waitForFrameFence() {
        if (frameFence != VK_NULL_HANDLE) {
            vkWaitForFences(SBoxVulkanCore.logicalDevice, frameFence, true, -1L);
            vkResetFences(SBoxVulkanCore.logicalDevice, frameFence);
        }
    }

    public static void updateDescriptorSet() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL).imageView(SBoxVulkanCanvas.canvasImageView);
            VkWriteDescriptorSet.Buffer writeSet = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(descriptorSet).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(imageInfo);
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
            String[] devPaths = {"src/main/resources/", "../src/main/resources/", "../../src/main/resources/"};
            for (String prefix : devPaths) {
                java.nio.file.Path p = Paths.get(prefix + cleanPath);
                if (Files.exists(p)) return Files.readAllBytes(p);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static float getActivePlanetHzFactor() {
        double albedo = 0.32;
        String name = "Earth";
        if (CameraManager.isLocked && CameraManager.lockedTarget != null && !"Star".equals(CameraManager.lockedTarget.type) && !"Hole".equals(CameraManager.lockedTarget.type)) {
            albedo = CameraManager.lockedTarget.bondAlbedo;
            name = CameraManager.lockedTarget.name;
        } else if (SpaceEngine.isPlacingPlanet && SpaceEngine.menuItems != null && SpaceEngine.selectedPlanetIndex < SpaceEngine.menuItems.length) {
            SpaceEngine.MenuItem item = SpaceEngine.menuItems[SpaceEngine.selectedPlanetIndex];
            if (!"Star".equals(item.type) && !"Hole".equals(item.type)) {
                name = item.name;
                if ("Enceladus".equalsIgnoreCase(name)) albedo = 0.88;
                else if ("Venus".equalsIgnoreCase(name)) albedo = 0.72;
                else if ("Uranus".equalsIgnoreCase(name) || "Neptune".equalsIgnoreCase(name)) albedo = 0.60;
                else if ("Jupiter".equalsIgnoreCase(name)) albedo = 0.50;
                else if ("Saturn".equalsIgnoreCase(name)) albedo = 0.48;
                else if ("Earth".equalsIgnoreCase(name)) albedo = 0.32;
                else if ("Titan".equalsIgnoreCase(name)) albedo = 0.26;
                else if ("Mars".equalsIgnoreCase(name)) albedo = 0.25;
                else if ("Moon".equalsIgnoreCase(name)) albedo = 0.12;
                else if ("Mercury".equalsIgnoreCase(name)) albedo = 0.06;
                else albedo = 0.30;
            }
        }
        double greenhouse = 1.0;
        if ("Venus".equalsIgnoreCase(name)) greenhouse = 2.85;
        else if ("Earth".equalsIgnoreCase(name)) greenhouse = 1.18;
        else if ("Titan".equalsIgnoreCase(name)) greenhouse = 1.12;
        else if ("Mars".equalsIgnoreCase(name)) greenhouse = 1.04;
        return (float) (0.72 * Math.sqrt(Math.sqrt(Math.max(0.05, (1.0 - albedo) * greenhouse))));
    }

    public static void init() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice device = SBoxVulkanCore.logicalDevice;
            createBuffers(stack, device);
            loadAllPlanetTextures(stack, device);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO).flags(VK_FENCE_CREATE_SIGNALED_BIT);
            LongBuffer pFence = stack.mallocLong(1);
            vkCreateFence(device, fenceInfo, null, pFence);
            frameFence = pFence.get(0);

            String glsl =
                    "#version 450\n" +
                            "#extension GL_ARB_gpu_shader_fp64 : enable\n" +
                            "layout (local_size_x = 16, local_size_y = 16) in;\n" +
                            "layout (binding = 0, rgba8) uniform writeonly image2D resultImage;\n" +
                            "struct PlanetData { dvec4 posAndRadius; vec4 rotAndFlags; vec4 shadeParams; vec4 uvFaces[6]; vec4 craters[16]; vec4 craterTemps[4]; vec4 shockAges[4]; vec4 waveAndWell; vec4 padAlign; };\n" +
                            "layout (std430, binding = 1) readonly buffer PlanetBuffer { PlanetData planets[64]; } pb;\n" +
                            "layout (binding = 2) uniform sampler2DArray planetTexArray;\n" +
                            "struct DebrisData { vec4 relPosAndSize; vec4 colorAndTemp; vec4 rotAndType; };\n" +
                            "layout (std430, binding = 3) readonly buffer DebrisBuffer { DebrisData debris[65536]; } db;\n" +
                            "layout(push_constant) uniform PushConstants { dvec4 camPosAndDist; vec4 screenAndTime; vec2 camRot; int planetCount; int packedDebrisFlags; } pc;\n" +
                            "const float FRAG_TEX_LAYER = " + fragmentLayerIndex + ".0;\n" +
                            "shared vec4 sPlanetRelAndBound[64], sPlanetRotAndFlags[64], sPlanetShadeParams[64], sPlanetRadiosity[64], sPlanetStarDir[64];\n" +
                            "shared int sEmitterIndices[32], sHoleIndices[16], sTileDebrisIdx[256];\n" +
                            "shared uint sEmitterCount, sHoleCount, sMinDotUint, sTileDebrisCount;\n" +
                            "shared vec3 sTileCenterRay;\n" +
                            "shared vec4 sTileDebrisPosRad[256];\n" +
                            "vec3 fastNorm(vec3 v) { return v * inversesqrt(max(dot(v, v), 1e-12)); }\n" +
                            "float hash(vec3 p) { p = fract(p * vec3(123.34, 456.21, 789.12)); p += dot(p, p + 45.32); return fract(p.x * p.y * p.z); }\n" +
                            "float hash2D(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123); }\n" +
                            "float noise3D(vec3 p) {\n" +
                            "vec3 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);\n" +
                            "return mix(mix(mix(hash(i), hash(i + vec3(1,0,0)), f.x), mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),\n" +
                            "mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x), mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y), f.z);\n" +
                            "}\n" +
                            "float noiseAngle(float a, float freq, float t) {\n" +
                            "float s = sin(a * freq + t) * 0.5 + 0.5;\n" +
                            "s += 0.5 * (sin(a * (freq * 2.3) - t * 1.4 + 1.9) * 0.5 + 0.5);\n" +
                            "s += 0.25 * (sin(a * (freq * 5.1) + t * 0.8 + 4.1) * 0.5 + 0.5);\n" +
                            "return s / 1.75;\n" +
                            "}\n" +
                            "float sampleSmoothStars(vec3 dir, float invHeight, float time) {\n" +
                            "vec3 p = dir * 165.0, cell = floor(p), local = fract(p) - 0.5;\n" +
                            "float rnd = hash(cell); if (rnd < 0.91) return 0.0;\n" +
                            "vec3 starOffset = (vec3(hash(cell + 13.7), hash(cell + 29.3), hash(cell + 53.1)) - 0.5) * 0.55;\n" +
                            "float dist = length(local - starOffset), starRadius = max(0.085, invHeight * 195.0);\n" +
                            "float core = smoothstep(starRadius, starRadius * 0.15, dist);\n" +
                            "float twinkle = 0.88 + 0.12 * sin(time * 1.2 + rnd * 120.0), b = (rnd - 0.91) / 0.09;\n" +
                            "return core * core * (0.35 + 0.95 * b * b) * twinkle;\n" +
                            "}\n" +
                            "vec3 rotateY(vec3 v, float a) { float c = cos(a), s = sin(a); return vec3(c * v.x + s * v.z, v.y, -s * v.x + c * v.z); }\n" +
                            "vec3 rotateZ(vec3 v, float a) { float c = cos(a), s = sin(a); return vec3(c * v.x - s * v.y, s * v.x + c * v.y, v.z); }\n" +
                            "double sanitizeRadiusD(double rawR) { return (isnan(rawR) || isinf(rawR) || !(rawR > 1e-4LF)) ? 1e-4LF : rawR; }\n" +
                            "vec3 applyTidalSpaghettificationFast(vec3 relVec, vec3 pRelPos, float r, int holeCount) {\n" +
                            "for (int h = 0; h < holeCount; h++) {\n" +
                            "int hIdx = sHoleIndices[h]; vec4 bhSphere = sPlanetRelAndBound[hIdx]; vec3 toBH = bhSphere.xyz - pRelPos;\n" +
                            "float minSafeR = max(bhSphere.w * 0.35, 0.05), distSq = max(dot(toBH, toBH), minSafeR * minSafeR), tidalZone = max(bhSphere.w * 9.0, r * 6.0);\n" +
                            "if (distSq < tidalZone * tidalZone) {\n" +
                            "float invDist = inversesqrt(distSq), dist = distSq * invDist; vec3 tidalAxis = toBH * invDist;\n" +
                            "float factor = clamp(1.0 - dist / tidalZone, 0.0, 0.88), stretch = 1.0 + factor * factor * 3.8, compress = sqrt(stretch);\n" +
                            "float parallel = dot(relVec, tidalAxis); vec3 perp = fma(tidalAxis, vec3(-parallel), relVec);\n" +
                            "relVec = fma(tidalAxis, vec3(parallel / stretch), perp * compress);\n" +
                            "}\n" +
                            "}\n" +
                            "return relVec;\n" +
                            "}\n" +
                            "float computeVoxelSnowMask(vec3 nPos, float localTempC, float centrifRatio, float gridRes) {\n" +
                            "if (localTempC > 35.0) return 0.0;\n" +
                            "if (localTempC <= -48.0 && centrifRatio < 0.75) return 1.0;\n" +
                            "vec3 voxCenter = (floor(clamp(nPos, -0.999, 0.999) * gridRes) + 0.5) / gridRes;\n" +
                            "float lat = abs(fastNorm(voxCenter).y), eqFactor = 1.0 - lat;\n" +
                            "float snowLine = clamp(0.73 + (localTempC / 52.0) * 0.42, -0.05, 1.05);\n" +
                            "float centrifStrip = clamp(centrifRatio, 0.0, 1.8) * eqFactor * eqFactor * 0.48 * clamp((localTempC + 90.0) / 90.0, 0.0, 1.0);\n" +
                            "return smoothstep(snowLine + centrifStrip - 0.02, snowLine + centrifStrip + 0.02, lat);\n" +
                            "}\n" +
                            "float sampleSeamlessSquareClouds(vec3 nPos, float cloudTime, float heatEvap) {\n" +
                            "if (heatEvap <= 0.001) return 0.0;\n" +
                            "vec3 safePos = clamp(nPos, -0.999, 0.999);\n" +
                            "vec3 voxMacro = (floor(safePos * 32.0) + 0.5) / 32.0, voxMicro = (floor(safePos * 48.0) + 0.5) / 48.0;\n" +
                            "vec3 pMacro = rotateY(voxMacro, cloudTime * 0.14), pMicro = rotateY(voxMicro, cloudTime * 0.14);\n" +
                            "vec3 morph = vec3(cloudTime * 0.08, -cloudTime * 0.06, cloudTime * 0.07);\n" +
                            "float densityField = noise3D(pMacro * 2.1 + morph) * 0.55 + noise3D(pMacro * 4.5 - morph * 1.35) * 0.30 + noise3D(pMicro * 8.0 + morph * 1.8) * 0.15;\n" +
                            "float cutoff = 0.61 + 0.04 * sin(cloudTime * 0.3 + voxMacro.y * 4.0);\n" +
                            "return step(cutoff, densityField) * 0.74 * heatEvap;\n" +
                            "}\n" +
                            "vec4 sampleSmartGravityFunnel(vec2 relXZ, int count, float time, out vec2 radialPullXZ) {\n" +
                            "float totalDispY = 0.0, gradX = 0.0, gradZ = 0.0, maxNormDepth = 0.0;\n" +
                            "radialPullXZ = vec2(0.0);\n" +
                            "for (int k = 0; k < count; k++) {\n" +
                            "vec4 pSph = sPlanetRelAndBound[k], ww = pb.planets[k].waveAndWell;\n" +
                            "float r = max(pSph.w, 0.05), wellDepth = ww.y;\n" +
                            "if (wellDepth <= 0.001) continue;\n" +
                            "float holoMult = (sPlanetRotAndFlags[k].z < 0.99) ? 0.65 : 1.0, wScale = max(ww.w, 1.15);\n" +
                            "vec2 dXZ = relXZ - pSph.xz; float distSq = dot(dXZ, dXZ);\n" +
                            "float throatR = r * wScale * 1.25, throatSq = throatR * throatR;\n" +
                            "float invDenom = inversesqrt(distSq + throatSq * 0.45);\n" +
                            "float depth = wellDepth * holoMult * r * 3.2 * throatR * invDenom;\n" +
                            "float innerCore = wellDepth * holoMult * r * 2.4 / (distSq / max(r * r * 0.85, 1e-4) + 0.65);\n" +
                            "float combinedDepth = depth + innerCore;\n" +
                            "totalDispY -= combinedDepth;\n" +
                            "float dDer = wellDepth * holoMult * r * 3.2 * throatR * (invDenom * invDenom * invDenom)\n" +
                            "           + (wellDepth * holoMult * r * 4.8 / max(r * r * 0.85, 1e-4)) / ((distSq / max(r * r * 0.85, 1e-4) + 0.65) * (distSq / max(r * r * 0.85, 1e-4) + 0.65));\n" +
                            "gradX += dXZ.x * dDer; gradZ += dXZ.y * dDer;\n" +
                            "float pullFactor = clamp(combinedDepth / max(sqrt(distSq) + throatR * 0.65, 0.05), 0.0, 0.86);\n" +
                            "radialPullXZ += dXZ * (pullFactor * 0.62);\n" +
                            "maxNormDepth = max(maxNormDepth, combinedDepth / max(r * 4.5, 0.5));\n" +
                            "float waveAmp = ww.x;\n" +
                            "if (waveAmp > 0.005) {\n" +
                            "float dist = sqrt(distSq), phase = dist * (1.65 / max(r, 0.4)) - time * 7.5;\n" +
                            "float env = exp(-dist / max(r * 26.0, 8.0)) * smoothstep(r * 0.5, r * 2.2, dist);\n" +
                            "totalDispY += sin(phase) * waveAmp * r * 0.48 * env;\n" +
                            "}\n" +
                            "}\n" +
                            "return vec4(totalDispY, gradX, gradZ, maxNormDepth);\n" +
                            "}\n" +
                            "void applyWarp(vec2 ctr, float rs, inout vec2 warp_uv) {\n" +
                            "if (rs <= 0.0) return;\n" +
                            "vec2 p = warp_uv - ctr; float d = length(p);\n" +
                            "if (d > rs * 0.08) {\n" +
                            "float fade = 1.0 - smoothstep(rs * 0.65, rs * 36.0, d);\n" +
                            "if (fade > 0.0) warp_uv -= (p / d) * ((rs * rs * 4.6) / max(d, rs * 0.20)) * fade;\n" +
                            "}\n" +
                            "}\n" +
                            "vec3 applyToneMap(vec3 x, bool hdrOn) {\n" +
                            "if (hdrOn) { x *= 1.32; vec3 m = clamp((x * (2.85 * x + 0.05)) / (x * (2.60 * x + 0.48) + 0.11), 0.0, 1.0); return pow(m, vec3(0.92)); }\n" +
                            "return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);\n" +
                            "}\n" +
                            "vec3 getBlackbodyColor(float tempC) {\n" +
                            "float tempK = max(300.0, tempC + 273.15);\n" +
                            "if (tempK < 1400.0) return mix(vec3(0.85, 0.08, 0.01), vec3(1.0, 0.28, 0.04), clamp((tempK - 773.0) / 627.0, 0.0, 1.0));\n" +
                            "if (tempK < 3500.0) return mix(vec3(1.0, 0.28, 0.04), vec3(1.0, 0.68, 0.28), clamp((tempK - 1400.0) / 2100.0, 0.0, 1.0));\n" +
                            "if (tempK < 6500.0) return mix(vec3(1.0, 0.68, 0.28), vec3(1.0, 0.94, 0.84), clamp((tempK - 3500.0) / 3000.0, 0.0, 1.0));\n" +
                            "return mix(vec3(1.0, 0.94, 0.84), vec3(0.70, 0.84, 1.0), clamp((tempK - 6500.0) / 12000.0, 0.0, 1.0));\n" +
                            "}\n" +
                            "float intersectPlanetBox(vec3 rayOrigRel, vec3 rayD, int idx) {\n" +
                            "vec4 pSphere = sPlanetRelAndBound[idx]; vec3 toCenter = pSphere.xyz - rayOrigRel;\n" +
                            "float r = pSphere.w, proj = dot(toCenter, rayD), boundR = r * 1.75;\n" +
                            "if (proj + boundR <= 0.0) return -1.0;\n" +
                            "vec3 ortho = toCenter - rayD * proj; if (dot(ortho, ortho) > boundR * boundR) return -1.0;\n" +
                            "vec4 rf = sPlanetRotAndFlags[idx];\n" +
                            "vec3 ro = rotateY(rotateZ(-toCenter / r, -rf.x), -rf.y), rd = rotateY(rotateZ(rayD, -rf.x), -rf.y);\n" +
                            "vec3 invDir = 1.0 / vec3(abs(rd.x) < 1e-7 ? 1e-7 : rd.x, abs(rd.y) < 1e-7 ? 1e-7 : rd.y, abs(rd.z) < 1e-7 ? 1e-7 : rd.z);\n" +
                            "vec3 t0 = (-vec3(1.0) - ro) * invDir, t1 = (vec3(1.0) - ro) * invDir;\n" +
                            "vec3 tmin = min(t0, t1), tmax = max(t0, t1);\n" +
                            "float tNear = max(max(tmin.x, tmin.y), tmin.z), tFar = min(min(tmax.x, tmax.y), tmax.z);\n" +
                            "if (tNear < tFar && tFar > 0.0) return ((tNear > 0.0) ? tNear : tFar) * r;\n" +
                            "return -1.0;\n" +
                            "}\n" +
                            "float traceShadow(vec3 relWorldPt, vec3 lightDir, float distToEmitter, float emitterRadius, int ignoreIdx, int emitterIdx, int count, bool softShadows) {\n" +
                            "float shadow = 1.0;\n" +
                            "for (int k = 0; k < count; k++) {\n" +
                            "if (k == ignoreIdx || k == emitterIdx || sPlanetRotAndFlags[k].z < 0.99) continue;\n" +
                            "vec4 sp = sPlanetShadeParams[k]; if (sp.x > 0.5 && sp.x < 1.5 && sp.w > 550.0) continue;\n" +
                            "vec4 occSphere = sPlanetRelAndBound[k]; vec3 toOcc = occSphere.xyz - relWorldPt;\n" +
                            "float proj = dot(toOcc, lightDir);\n" +
                            "if (proj > 0.0 && proj < distToEmitter) {\n" +
                            "vec3 closest = toOcc - lightDir * proj; float perpDistSq = dot(closest, closest);\n" +
                            "float maxPenumbra = occSphere.w * 1.8 + emitterRadius * 0.5;\n" +
                            "if (perpDistSq > maxPenumbra * maxPenumbra) continue;\n" +
                            "float hitBox = intersectPlanetBox(relWorldPt, lightDir, k);\n" +
                            "if (hitBox > 0.0 && hitBox < distToEmitter) return 0.0;\n" +
                            "if (softShadows) {\n" +
                            "float perpDist = sqrt(perpDistSq), effRad = occSphere.w * 1.15;\n" +
                            "float penumbra = max(occSphere.w * 0.15, emitterRadius * (proj / max(distToEmitter, 1.0)) * 0.5);\n" +
                            "shadow = min(shadow, smoothstep(effRad - penumbra * 0.3, effRad + penumbra, perpDist));\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "return shadow;\n" +
                            "}\n" +
                            "float getPlanetMaxCraterTemp(int pIdx) {\n" +
                            "int activeC = clamp(int(round(pb.planets[pIdx].waveAndWell.z)), 0, 16);\n" +
                            "float m = 0.0;\n" +
                            "for (int c = 0; c < activeC; c++) m = max(m, pb.planets[pIdx].craterTemps[c >> 2][c & 3]);\n" +
                            "return m;\n" +
                            "}\n" +
                            "void main() {\n" +
                            "uint tid = gl_LocalInvocationIndex;\n" +
                            "ivec2 rawCoord = ivec2(gl_GlobalInvocationID.xy);\n" +
                            "float width = pc.screenAndTime.x, height = pc.screenAndTime.y, time = pc.screenAndTime.z, hzPlanetFactor = pc.screenAndTime.w;\n" +
                            "bool inBounds = (rawCoord.x < int(width) && rawCoord.y < int(height));\n" +
                            "ivec2 coord = min(rawCoord, ivec2(max(0, int(width) - 1), max(0, int(height) - 1)));\n" +
                            "dvec3 camPosD = pc.camPosAndDist.xyz;\n" +
                            "vec2 uv = (vec2(coord) - 0.5 * vec2(width, height)) / height;\n" +
                            "float pitch = pc.camRot.x, yaw = pc.camRot.y;\n" +
                            "int count = clamp(pc.planetCount, 0, 64), debCount = clamp(pc.packedDebrisFlags & 262143, 0, 65536), shaderMode = (pc.packedDebrisFlags >> 18) & 3;\n" +
                            "bool validSpawn = (pc.packedDebrisFlags & (1 << 20)) != 0, hdrOn = (pc.packedDebrisFlags & (1 << 21)) != 0;\n" +
                            "bool showGrid = (pc.packedDebrisFlags & (1 << 22)) != 0, showHZ = (pc.packedDebrisFlags & (1 << 23)) != 0, showGravGrid = (pc.packedDebrisFlags & (1 << 24)) != 0;\n" +
                            "if (tid == 0u) { sEmitterCount = 0u; sHoleCount = 0u; sMinDotUint = 0x3F800000u; sTileDebrisCount = 0u; }\n" +
                            "barrier();\n" +
                            "if (tid < 64u) {\n" +
                            "if (int(tid) < count) {\n" +
                            "dvec3 pRelD = pb.planets[tid].posAndRadius.xyz - camPosD;\n" +
                            "float r = float(sanitizeRadiusD(pb.planets[tid].posAndRadius.w));\n" +
                            "vec4 rf = pb.planets[tid].rotAndFlags, sp = pb.planets[tid].shadeParams;\n" +
                            "sPlanetRelAndBound[tid] = vec4(vec3(pRelD), r); sPlanetRotAndFlags[tid] = rf; sPlanetShadeParams[tid] = sp;\n" +
                            "float effEmitTemp = max(sp.w, getPlanetMaxCraterTemp(int(tid)) * 0.75);\n" +
                            "if (sp.x > 1.5) { uint hSlot = atomicAdd(sHoleCount, 1u); if (hSlot < 16u) sHoleIndices[hSlot] = int(tid); }\n" +
                            "else if (rf.z > 0.99 && effEmitTemp > 550.0) { uint eSlot = atomicAdd(sEmitterCount, 1u); if (eSlot < 32u) sEmitterIndices[eSlot] = int(tid); }\n" +
                            "} else { sPlanetRelAndBound[tid] = vec4(0,0,0,-1); sPlanetRotAndFlags[tid] = vec4(0.0); sPlanetShadeParams[tid] = vec4(0.0); }\n" +
                            "}\n" +
                            "barrier();\n" +
                            "int emitterCount = int(min(sEmitterCount, 32u)), holeCount = int(min(sHoleCount, 16u));\n" +
                            "if (tid < 64u) {\n" +
                            "if (int(tid) < count) {\n" +
                            "vec4 sp = sPlanetShadeParams[tid], uv0 = pb.planets[tid].uvFaces[0];\n" +
                            "float maxCraterT = getPlanetMaxCraterTemp(int(tid)), texLayer = sPlanetRotAndFlags[tid].w;\n" +
                            "vec3 baseTone = textureLod(planetTexArray, vec3(mix(uv0.xy, uv0.zw, vec2(0.5)), texLayer), 0.0).rgb;\n" +
                            "vec3 pPos = sPlanetRelAndBound[tid].xyz, domLightDir = vec3(0.0, 1.0, 0.0), bounceFlux = vec3(0.0);\n" +
                            "float maxWeight = 0.0;\n" +
                            "for (int s = 0; s < emitterCount; s++) {\n" +
                            "int eIdx = sEmitterIndices[s]; if (eIdx == int(tid)) continue;\n" +
                            "vec4 eSphere = sPlanetRelAndBound[eIdx]; vec3 toE = eSphere.xyz - pPos;\n" +
                            "float dE = max(length(toE), 1e-3), eTemp = max(sPlanetShadeParams[eIdx].w, getPlanetMaxCraterTemp(eIdx) * 0.85);\n" +
                            "float w = (sPlanetShadeParams[eIdx].x > 0.5) ? (eSphere.w * 25.0 / dE) : (eSphere.w * 6.0 / dE);\n" +
                            "bounceFlux += baseTone * (getBlackbodyColor(eTemp) * clamp(w, 0.05, 1.6)) * 0.55;\n" +
                            "if (w > maxWeight) { maxWeight = w; domLightDir = toE / dE; }\n" +
                            "}\n" +
                            "float effTemp = max(sp.w, maxCraterT * 0.65);\n" +
                            "if (effTemp > 550.0) bounceFlux += getBlackbodyColor(effTemp) * clamp((effTemp - 550.0) / 1100.0, 0.15, 2.5);\n" +
                            "sPlanetRadiosity[tid] = vec4(bounceFlux, effTemp); sPlanetStarDir[tid] = vec4(domLightDir, sp.x);\n" +
                            "} else { sPlanetRadiosity[tid] = vec4(0.0); sPlanetStarDir[tid] = vec4(0,1,0,0); }\n" +
                            "}\n" +
                            "barrier();\n" +
                            "vec3 forward = fastNorm(vec3(-sin(yaw) * cos(pitch), -sin(pitch), -cos(yaw) * cos(pitch)));\n" +
                            "vec3 right = fastNorm(cross(forward, vec3(0.0, 1.0, 0.0))), up = fastNorm(cross(right, forward));\n" +
                            "vec3 rawRayDir = fastNorm(forward + uv.x * right - uv.y * up);\n" +
                            "vec2 warp_uv = uv;\n" +
                            "bool hitBlackHoleCube = false, hitHoloBlackHole = false;\n" +
                            "float closestT = 1e38, bloomMask = 0.0;\n" +
                            "int hitPlanetIdx = -1;\n" +
                            "for (int h = 0; h < holeCount; h++) {\n" +
                            "int hIdx = sHoleIndices[h]; float alpha = sPlanetRotAndFlags[hIdx].z;\n" +
                            "float tBox = intersectPlanetBox(vec3(0.0), rawRayDir, hIdx);\n" +
                            "if (tBox > 0.0 && tBox < closestT) { closestT = tBox; hitBlackHoleCube = true; hitHoloBlackHole = (alpha < 0.99); hitPlanetIdx = hIdx; }\n" +
                            "if (shaderMode < 2 && alpha > 0.99) {\n" +
                            "vec4 bhSphere = sPlanetRelAndBound[hIdx]; vec3 toBH = bhSphere.xyz; float distSq = dot(toBH, toBH);\n" +
                            "if (distSq > 0.01) {\n" +
                            "vec3 bhDir = toBH * inversesqrt(distSq); float viewZ = dot(toBH, forward);\n" +
                            "if (dot(bhDir, forward) >= 0.15 && viewZ > 0.1) applyWarp(vec2(dot(toBH, right) / viewZ, -dot(toBH, up) / viewZ), min((bhSphere.w / viewZ) * 1.75, 4.2), warp_uv);\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "vec3 rayDir = hitBlackHoleCube ? rawRayDir : fastNorm(forward + warp_uv.x * right - warp_uv.y * up);\n" +
                            "if (tid == 136u) sTileCenterRay = rayDir;\n" +
                            "barrier();\n" +
                            "if (debCount > 0) atomicMin(sMinDotUint, floatBitsToUint(clamp(dot(rayDir, sTileCenterRay), 0.0, 1.0)));\n" +
                            "barrier();\n" +
                            "if (debCount > 0) {\n" +
                            "float tileCos = uintBitsToFloat(sMinDotUint), tileSin = sqrt(max(0.0, 1.0 - tileCos * tileCos));\n" +
                            "for (int d = int(tid); d < debCount; d += 256) {\n" +
                            "vec4 posSize = db.debris[d].relPosAndSize; vec3 dPos = posSize.xyz; float dSize = max(posSize.w, 1e-4);\n" +
                            "float boundR = (db.debris[d].rotAndType.y > 0.5) ? (dSize * 1.76) : (dSize * 2.4), distSq = dot(dPos, dPos);\n" +
                            "bool visibleInTile = (distSq <= boundR * boundR * 1.05);\n" +
                            "if (!visibleInTile) {\n" +
                            "float invDist = inversesqrt(distSq), projCenter = dot(dPos, sTileCenterRay);\n" +
                            "if (projCenter + boundR > 0.0) {\n" +
                            "float sinSphere = min(1.0, boundR * invDist), cosSphere = sqrt(max(0.0, 1.0 - sinSphere * sinSphere));\n" +
                            "if ((projCenter * invDist) >= (tileCos * cosSphere - tileSin * sinSphere) - 0.002) visibleInTile = true;\n" +
                            "}\n" +
                            "}\n" +
                            "if (visibleInTile) {\n" +
                            "uint slot = atomicAdd(sTileDebrisCount, 1u);\n" +
                            "if (slot < 256u) { sTileDebrisIdx[slot] = d; sTileDebrisPosRad[slot] = vec4(dPos, dSize); }\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "barrier();\n" +
                            "if (!inBounds) return;\n" +
                            "vec3 col = vec3(0.004, 0.006, 0.011);\n" +
                            "if (!hitBlackHoleCube) col += vec3(sampleSmoothStars(rayDir, 1.0 / max(height, 1.0), time));\n" +
                            "if (hitBlackHoleCube) col = (hitHoloBlackHole && !validSpawn) ? vec3(0.30, 0.02, 0.02) : vec3(0.0);\n" +
                            "vec3 bestNPos = vec3(0.0), holoNPos = vec3(0.0);\n" +
                            "float bestHitNorm = 0.0, holoHitT = 1e38;\n" +
                            "int holoIdx = -1;\n" +
                            "for (int i = 0; i < count; i++) {\n" +
                            "vec4 sp = sPlanetShadeParams[i]; if (sp.x > 1.5) continue;\n" +
                            "vec4 pSphere = sPlanetRelAndBound[i], rf = sPlanetRotAndFlags[i];\n" +
                            "float r = pSphere.w; bool isHolo = (rf.z < 0.99);\n" +
                            "float boundR = r * ((!isHolo && shaderMode == 0 && holeCount > 0) ? 4.2 : 1.75);\n" +
                            "vec3 toPlanet = pSphere.xyz; float projP = dot(toPlanet, rayDir);\n" +
                            "if (projP + boundR <= 0.0 || projP - boundR >= closestT) continue;\n" +
                            "vec3 orthoP = toPlanet - rayDir * projP; if (dot(orthoP, orthoP) > boundR * boundR) continue;\n" +
                            "vec3 roNorm, rd;\n" +
                            "if (!isHolo && shaderMode == 0 && sp.x < 1.5 && holeCount > 0) {\n" +
                            "vec3 relOrigin = applyTidalSpaghettificationFast(-toPlanet, toPlanet, r, holeCount);\n" +
                            "vec3 relDir = applyTidalSpaghettificationFast(rayDir, toPlanet, r, holeCount);\n" +
                            "roNorm = rotateY(rotateZ(relOrigin / r, -rf.x), -rf.y); rd = rotateY(rotateZ(relDir, -rf.x), -rf.y);\n" +
                            "} else {\n" +
                            "roNorm = rotateY(rotateZ(-toPlanet / r, -rf.x), -rf.y); rd = rotateY(rotateZ(rayDir, -rf.x), -rf.y);\n" +
                            "}\n" +
                            "vec3 invDir = 1.0 / vec3(abs(rd.x) < 1e-7 ? 1e-7 : rd.x, abs(rd.y) < 1e-7 ? 1e-7 : rd.y, abs(rd.z) < 1e-7 ? 1e-7 : rd.z);\n" +
                            "vec3 t0 = (-vec3(1.0) - roNorm) * invDir, t1 = (vec3(1.0) - roNorm) * invDir;\n" +
                            "vec3 tmin = min(t0, t1), tmax = max(t0, t1);\n" +
                            "float tNearNorm = max(max(tmin.x, tmin.y), tmin.z), tFarNorm = min(min(tmax.x, tmax.y), tmax.z);\n" +
                            "if (tNearNorm < tFarNorm && tFarNorm > 0.0) {\n" +
                            "float hitNorm = (tNearNorm > 0.0) ? tNearNorm : tFarNorm, hitT = hitNorm * r;\n" +
                            "if (hitT < closestT) {\n" +
                            "vec3 candNPos = clamp(roNorm + rd * hitNorm, -1.0, 1.0);\n" +
                            "if (isHolo) { holoIdx = i; holoNPos = candNPos; holoHitT = hitT; }\n" +
                            "else { closestT = hitT; hitPlanetIdx = i; hitBlackHoleCube = false; bestNPos = candNPos; bestHitNorm = hitNorm; }\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "if (hitPlanetIdx >= 0 && !hitBlackHoleCube) {\n" +
                            "int i = hitPlanetIdx;\n" +
                            "vec4 pSphere = sPlanetRelAndBound[i], rf = sPlanetRotAndFlags[i], sp = sPlanetShadeParams[i];\n" +
                            "float r = pSphere.w, tilt = rf.x, rot = rf.y, texLayer = rf.w, objType = sp.x, centrifRatio = sp.z, tempC = sp.w;\n" +
                            "int layerId = int(round(texLayer)), packedFlags = int(round(sp.y));\n" +
                            "bool flashlightOn = (packedFlags & 1) != 0, raysOn = (packedFlags & 2) != 0, isRigid = (packedFlags & 4) != 0;\n" +
                            "vec3 nPos = bestNPos, absP = abs(nPos), nLocal = vec3(0.0, 1.0, 0.0);\n" +
                            "int faceIdx = 0; vec2 fuv = vec2(0.0);\n" +
                            "if (absP.z >= absP.x && absP.z >= absP.y) {\n" +
                            "if (nPos.z < 0.0) { faceIdx = 0; fuv = vec2(0.5 - nPos.x * 0.5, 0.5 - nPos.y * 0.5); nLocal = vec3(0,0,-1); }\n" +
                            "else { faceIdx = 2; fuv = vec2(nPos.x * 0.5 + 0.5, 0.5 - nPos.y * 0.5); nLocal = vec3(0,0,1); }\n" +
                            "} else if (absP.x >= absP.y) {\n" +
                            "if (nPos.x > 0.0) { faceIdx = 1; fuv = vec2(0.5 - nPos.z * 0.5, 0.5 - nPos.y * 0.5); nLocal = vec3(1,0,0); }\n" +
                            "else { faceIdx = 3; fuv = vec2(nPos.z * 0.5 + 0.5, 0.5 - nPos.y * 0.5); nLocal = vec3(-1,0,0); }\n" +
                            "} else {\n" +
                            "if (nPos.y > 0.0) { faceIdx = 4; fuv = vec2(nPos.x * 0.5 + 0.5, nPos.z * 0.5 + 0.5); nLocal = vec3(0,1,0); }\n" +
                            "else { faceIdx = 5; fuv = vec2(nPos.x * 0.5 + 0.5, 0.5 - nPos.z * 0.5); nLocal = vec3(0,-1,0); }\n" +
                            "}\n" +
                            "vec4 uvRect = pb.planets[i].uvFaces[faceIdx];\n" +
                            "vec3 albedo = textureLod(planetTexArray, vec3(mix(uvRect.xy, uvRect.zw, clamp(fuv, 0.001, 0.999)), texLayer), 0.0).rgb;\n" +
                            "bool isHotStar = (objType > 0.5 && objType < 1.5 && tempC > 550.0);\n" +
                            "if (objType > 0.5 && objType < 1.5 && !isHotStar) {\n" +
                            "float gray = dot(albedo, vec3(0.299, 0.587, 0.114));\n" +
                            "albedo = mix(vec3(gray * 0.22 + 0.03), albedo * 0.5, clamp((tempC - 100.0) / 450.0, 0.0, 1.0));\n" +
                            "}\n" +
                            "if (isHotStar) {\n" +
                            "vec3 starTint = getBlackbodyColor(tempC);\n" +
                            "float starPower = clamp((tempC - 550.0) / 1200.0, 0.25, 1.0);\n" +
                            "if (shaderMode == 0 && bestHitNorm < 32.0) {\n" +
                            "float plasma = 0.80 + 0.35 * noise3D(nPos * 6.5 + vec3(time * 0.25, -time * 0.18, time * 0.12));\n" +
                            "col = mix(albedo, starTint, 0.65) * (3.1 * plasma * (1.0 - 0.24 * length(fuv - vec2(0.5))) * starPower);\n" +
                            "} else if (shaderMode == 1) {\n" +
                            "col = mix(albedo, starTint, 0.50) * (2.1 * starPower);\n" +
                            "if (raysOn) bloomMask = starPower;\n" +
                            "} else col = albedo * 1.15;\n" +
                            "} else {\n" +
                            "float craterHeatBoostC = 0.0, craterBowlMask = 0.0, craterLavaVein = 0.0, hottestCraterT = tempC, shockCloudClear = 0.0;\n" +
                            "vec3 shockRingEmission = vec3(0.0), craterNormalShift = vec3(0.0), selfCraterGlowLight = vec3(0.0);\n" +
                            "int activeCraterCount = clamp(int(round(pb.planets[i].waveAndWell.z)), 0, 16);\n" +
                            "if (isRigid && activeCraterCount > 0) {\n" +
                            "vec3 safeVox = (floor(clamp(nPos, -0.999, 0.999) * 24.0) + 0.5) / 24.0, voxDir = fastNorm(safeVox);\n" +
                            "for (int c = 0; c < activeCraterCount; c++) {\n" +
                            "vec4 cData = pb.planets[i].craters[c]; float cRad = cData.w;\n" +
                            "if (cRad <= 0.005) continue;\n" +
                            "float cDepth = clamp(length(cData.xyz), 0.05, 1.0);\n" +
                            "vec3 impDir = cData.xyz / max(cDepth, 1e-4);\n" +
                            "float cTempC = pb.planets[i].craterTemps[c >> 2][c & 3], sAge = pb.planets[i].shockAges[c >> 2][c & 3];\n" +
                            "float cosAng = clamp(dot(voxDir, impDir), -1.0, 1.0), angDist = sqrt(max(0.0, 2.0 - 2.0 * cosAng));\n" +
                            "vec3 radialTangent = fastNorm(voxDir - impDir * cosAng + vec3(1e-5));\n" +
                            "if (sAge > 0.0005 && sAge < 0.999) {\n" +
                            "float shockFront = cRad * 0.55 + sAge * clamp(cRad * 4.2 + 0.65, 0.35, 2.5), shockWidth = 0.042 + 0.035 * sAge;\n" +
                            "float dFront = abs(angDist - shockFront);\n" +
                            "if (dFront < shockWidth * 1.8) {\n" +
                            "float ringMask = smoothstep(shockWidth * 1.4, 0.0, dFront), fadeWave = (1.0 - sAge) * (1.0 - sAge);\n" +
                            "shockRingEmission += mix(vec3(1.0, 0.52, 0.12), vec3(1.0, 0.92, 0.68), ringMask) * (ringMask * fadeWave * 2.8);\n" +
                            "shockCloudClear = max(shockCloudClear, ringMask * (1.0 - sAge * 0.5));\n" +
                            "craterHeatBoostC = max(craterHeatBoostC, fadeWave * 650.0 * ringMask);\n" +
                            "craterNormalShift += radialTangent * (((angDist < shockFront) ? 1.0 : -1.0) * ringMask * fadeWave * 0.55);\n" +
                            "}\n" +
                            "if (angDist < shockFront) shockCloudClear = max(shockCloudClear, (1.0 - sAge) * 0.88);\n" +
                            "}\n" +
                            "if (angDist < cRad * 1.95) {\n" +
                            "float thermalFalloff = pow(clamp(1.0 - angDist / (cRad * 1.95), 0.0, 1.0), 1.6), heatAdd = max(0.0, cTempC - tempC) * thermalFalloff;\n" +
                            "if (heatAdd > craterHeatBoostC) { craterHeatBoostC = heatAdd; hottestCraterT = max(hottestCraterT, cTempC); }\n" +
                            "if (cTempC > 550.0) selfCraterGlowLight += getBlackbodyColor(cTempC) * (clamp((cTempC - 550.0) / 1400.0, 0.0, 2.0) * thermalFalloff * 0.85);\n" +
                            "if (angDist < cRad * 1.15) {\n" +
                            "float rNorm = angDist / max(cRad, 1e-3);\n" +
                            "craterBowlMask = max(craterBowlMask, smoothstep(1.10, 0.65, rNorm) * cDepth);\n" +
                            "craterNormalShift += radialTangent * (((rNorm < 0.82) ? (-rNorm * 0.75) : ((1.15 - rNorm) * 1.45)) * cDepth * 0.65);\n" +
                            "float crackNoise = noise3D(safeVox * 16.0 + impDir * float(c * 5 + 3));\n" +
                            "float vein = clamp(max(smoothstep(0.52, 0.12, rNorm) * 0.95, smoothstep(0.55, 0.78, crackNoise) * smoothstep(1.02, 0.32, rNorm) * 0.85) * cDepth, 0.0, 1.0);\n" +
                            "if (cTempC > 480.0 && vein > craterLavaVein) { craterLavaVein = vein; hottestCraterT = max(hottestCraterT, cTempC); }\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "if (craterBowlMask > 0.01) albedo = mix(albedo, albedo * 0.22 + vec3(0.05, 0.04, 0.04), clamp(craterBowlMask * 0.92, 0.0, 0.95));\n" +
                            "}\n" +
                            "vec3 worldFaceN = fastNorm(rotateZ(rotateY(nLocal, rot), tilt));\n" +
                            "vec3 blendedLocalN = (shaderMode == 0) ? fastNorm(mix(nLocal, fastNorm(nPos), 0.42) + craterNormalShift) : nLocal;\n" +
                            "vec3 worldN = fastNorm(rotateZ(rotateY(blendedLocalN, rot), tilt)), hitRelWorld = rayDir * closestT, viewDir = -rayDir;\n" +
                            "float directionalHeatC = craterHeatBoostC; vec3 planetshineLight = vec3(0.0);\n" +
                            "if (shaderMode == 0) {\n" +
                            "for (int k = 0; k < count; k++) {\n" +
                            "if (k == i || sPlanetRotAndFlags[k].z < 0.99) continue;\n" +
                            "float kType = sPlanetStarDir[k].w; if (kType > 1.5) continue;\n" +
                            "vec4 kSphere = sPlanetRelAndBound[k]; vec3 toK = kSphere.xyz - hitRelWorld;\n" +
                            "float distKSq = max(dot(toK, toK), 1e-6), distK = sqrt(distKSq);\n" +
                            "if (distK > (r + kSphere.w) * 14.0) continue;\n" +
                            "vec3 dirK = toK / distK;\n" +
                            "float facingK = max(0.0, dot(worldN, dirK)) * smoothstep(-0.25, 0.25, dot(worldFaceN, dirK));\n" +
                            "if (facingK <= 0.001) continue;\n" +
                            "float kTempC = sPlanetRadiosity[k].w;\n" +
                            "if (kTempC > 250.0 && kTempC > tempC) {\n" +
                            "float geomK = (kSphere.w * 0.85) / max(distK, kSphere.w);\n" +
                            "directionalHeatC += (kTempC - tempC) * (geomK * geomK) * facingK * 1.25;\n" +
                            "}\n" +
                            "if (kType < 0.5) {\n" +
                            "float kPhase = smoothstep(-0.2, 0.85, dot(-dirK, sPlanetStarDir[k].xyz));\n" +
                            "float solidAngle = clamp((kSphere.w * kSphere.w * 2.8) / max(distKSq, kSphere.w * kSphere.w), 0.0, 0.65);\n" +
                            "planetshineLight += sPlanetRadiosity[k].rgb * (facingK * kPhase * solidAngle);\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "float localTempC = tempC + directionalHeatC, snowCapMask = 0.0;\n" +
                            "if (shaderMode == 0 && isRigid && objType < 0.5 && craterBowlMask < 0.35) {\n" +
                            "snowCapMask = computeVoxelSnowMask(nPos, localTempC, centrifRatio, 16.0) * clamp(1.0 - craterBowlMask * 2.5, 0.0, 1.0);\n" +
                            "if (snowCapMask > 0.01) albedo = mix(albedo, vec3(0.93, 0.97, 1.0), snowCapMask * 0.92);\n" +
                            "}\n" +
                            "float squareCloudMask = 0.0, squareCloudShadow = 1.0;\n" +
                            "if (shaderMode == 0 && layerId == 0 && localTempC > -55.0 && localTempC < 160.0 && bestHitNorm < 28.0) {\n" +
                            "float heatEvap = (1.0 - smoothstep(95.0, 150.0, localTempC)) * (1.0 - smoothstep(0.05, 0.45, craterBowlMask)) * clamp(1.0 - shockCloudClear, 0.0, 1.0);\n" +
                            "squareCloudMask = sampleSeamlessSquareClouds(nPos, time, heatEvap);\n" +
                            "float shCloud = sampleSeamlessSquareClouds(clamp(nPos + vec3(0.025, -0.025, 0.025), -1.0, 1.0), time, heatEvap);\n" +
                            "squareCloudShadow = 1.0 - shCloud * (1.0 - squareCloudMask) * 0.38;\n" +
                            "}\n" +
                            "if (shaderMode == 2) {\n" +
                            "col = mix(albedo, vec3(0.95, 0.97, 1.0), squareCloudMask) * 1.05;\n" +
                            "} else {\n" +
                            "float texLuma = dot(albedo, vec3(0.299, 0.587, 0.114));\n" +
                            "float maxCh = max(max(albedo.r, albedo.g), albedo.b), minCh = min(min(albedo.r, albedo.g), albedo.b);\n" +
                            "float sat = (maxCh > 1e-4) ? ((maxCh - minCh) / maxCh) : 0.0;\n" +
                            "float whiteIce = max(snowCapMask, smoothstep(0.35, 0.76, texLuma) * (1.0 - sat * 0.55)), darkAbsorb = smoothstep(0.05, 0.22, texLuma);\n" +
                            "float coldIceFactor = clamp(1.0 - max(0.0, localTempC - 50.0) / 500.0, 0.12, 1.0);\n" +
                            "float isWater = (shaderMode == 0 && snowCapMask < 0.5 && craterBowlMask < 0.2) ? (step(albedo.r * 1.35, albedo.b) * step(albedo.g * 1.15, albedo.b)) : 0.0;\n" +
                            "float reflectivity = max(isWater * 0.65, whiteIce * (0.48 + 0.48 * coldIceFactor)) * darkAbsorb * (1.0 - squareCloudMask) * (1.0 - craterBowlMask);\n" +
                            "vec3 baseAmbient = (objType > 0.5) ? vec3(0.18, 0.19, 0.22) : ((emitterCount > 0) ? (vec3(0.010, 0.013, 0.020) * darkAbsorb) : vec3(0.0));\n" +
                            "vec3 camSpotLight = vec3(0.0), camSpotSpec = vec3(0.0);\n" +
                            "if (flashlightOn) {\n" +
                            "camSpotLight = vec3(0.96, 0.98, 1.0) * (0.22 + 0.88 * max(0.0, dot(worldN, viewDir)));\n" +
                            "camSpotSpec = vec3(0.95, 0.98, 1.0) * pow(max(dot(worldN, viewDir), 0.0), 32.0) * reflectivity * 0.55;\n" +
                            "}\n" +
                            "vec3 totalLight = baseAmbient + planetshineLight + selfCraterGlowLight + camSpotLight, totalSpec = camSpotSpec;\n" +
                            "vec3 cloudLight = baseAmbient + planetshineLight + selfCraterGlowLight * 1.35 + camSpotLight;\n" +
                            "float maxFaceDot = -1.0;\n" +
                            "for (int s = 0; s < emitterCount; s++) {\n" +
                            "int eIdx = sEmitterIndices[s]; if (eIdx == i) continue;\n" +
                            "vec4 eSphere = sPlanetRelAndBound[eIdx]; vec3 toLight = eSphere.xyz - hitRelWorld; float distToLight = length(toLight);\n" +
                            "float eTempC = max(sPlanetShadeParams[eIdx].w, getPlanetMaxCraterTemp(eIdx) * 0.85);\n" +
                            "bool isStar = (sPlanetShadeParams[eIdx].x > 0.5);\n" +
                            "float thermalPower = isStar ? 1.65 : clamp((eTempC - 550.0) / 1400.0, 0.0, 2.2);\n" +
                            "float atten = isStar ? 1.0 : clamp((eSphere.w * 18.0) / max(distToLight, eSphere.w), 0.05, 1.5);\n" +
                            "vec3 eColor = getBlackbodyColor(eTempC) * (thermalPower * atten), L = toLight / max(distToLight, 1e-4);\n" +
                            "float ndotl = dot(worldN, L), faceDot = dot(worldFaceN, L);\n" +
                            "maxFaceDot = max(maxFaceDot, faceDot);\n" +
                            "float diff = (shaderMode == 0) ? (smoothstep(-0.08, 0.85, ndotl) * smoothstep(-0.28, 0.22, faceDot)) : max(0.0, faceDot);\n" +
                            "if (diff > 0.001) {\n" +
                            "float shadow = traceShadow(hitRelWorld + worldFaceN * (r * 0.015), L, distToLight, eSphere.w, i, eIdx, count, shaderMode == 0);\n" +
                            "totalLight += eColor * diff * shadow * squareCloudShadow * (1.0 + whiteIce * 0.50 * coldIceFactor);\n" +
                            "cloudLight += eColor * diff * shadow;\n" +
                            "if (reflectivity > 0.01) {\n" +
                            "vec3 H = fastNorm(L + viewDir);\n" +
                            "float spec = pow(max(dot(worldN, H), 0.0), mix(24.0, 58.0, coldIceFactor)) * diff * shadow * squareCloudShadow;\n" +
                            "float iceRim = pow(1.0 - max(dot(worldN, viewDir), 0.0), 2.8) * diff * shadow * whiteIce * coldIceFactor * 0.42;\n" +
                            "totalSpec += eColor * (spec + iceRim) * reflectivity;\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "col = (albedo * darkAbsorb) * totalLight + totalSpec;\n" +
                            "if (squareCloudMask > 0.01) col = mix(col, vec3(0.95, 0.97, 1.0) * cloudLight, squareCloudMask);\n" +
                            "if (shaderMode == 0 && layerId == 0 && emitterCount > 0 && maxFaceDot < 0.08 && snowCapMask < 0.5 && craterBowlMask < 0.1 && localTempC > -35.0 && localTempC < 75.0 && bestHitNorm < 24.0) {\n" +
                            "bool isContinentTex = (albedo.g > albedo.b * 1.04 || albedo.r > albedo.b * 1.08) && (minCh < 0.72) && (maxCh > 0.12);\n" +
                            "if (isContinentTex) {\n" +
                            "vec2 safeFuv = clamp(fuv, 0.001, 0.999), cityCell = floor(safeFuv * 64.0), localSub = fract(safeFuv * 64.0);\n" +
                            "float regionDensity = hash(vec3(floor(safeFuv * 16.0), float(faceIdx) * 5.0)), dotHash = hash(vec3(cityCell, float(faceIdx) * 19.0));\n" +
                            "if (localSub.x > 0.22 && localSub.x < 0.78 && localSub.y > 0.22 && localSub.y < 0.78 && regionDensity > 0.36 && dotHash > 0.74) {\n" +
                            "float nightFade = smoothstep(0.06, -0.15, maxFaceDot) * (1.0 - squareCloudMask * 0.85);\n" +
                            "col += vec3(1.0, 0.78, 0.32) * ((0.75 + 0.25 * fract(dotHash * 31.7)) * nightFade * 1.35);\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "if (craterLavaVein > 0.01 && hottestCraterT > 480.0) {\n" +
                            "float cGlow = clamp((hottestCraterT - 480.0) / 1400.0, 0.0, 3.2);\n" +
                            "col = mix(col, getBlackbodyColor(hottestCraterT) * (1.4 + cGlow), clamp(craterLavaVein * clamp((hottestCraterT - 480.0) / 500.0, 0.0, 0.96), 0.0, 0.96));\n" +
                            "}\n" +
                            "col += shockRingEmission;\n" +
                            "if (localTempC > 550.0) {\n" +
                            "float glowStrength = clamp((localTempC - 550.0) / 1500.0, 0.0, 2.8);\n" +
                            "col = mix(col, getBlackbodyColor(localTempC) * (1.2 + glowStrength), clamp(glowStrength * 0.72, 0.0, 0.95));\n" +
                            "if (shaderMode == 1 && localTempC > 900.0 && raysOn) bloomMask = clamp((localTempC - 900.0) / 2000.0, 0.2, 1.0);\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "if (holoIdx >= 0 && holoHitT < closestT) {\n" +
                            "vec3 absH = abs(holoNPos);\n" +
                            "int hFace = (absH.z >= absH.x && absH.z >= absH.y) ? (holoNPos.z < 0.0 ? 0 : 2) : ((absH.x >= absH.y) ? (holoNPos.x > 0.0 ? 1 : 3) : (holoNPos.y > 0.0 ? 4 : 5));\n" +
                            "vec2 hFuv = (hFace == 0) ? vec2(0.5 - holoNPos.x * 0.5, 0.5 - holoNPos.y * 0.5) : ((hFace == 2) ? vec2(holoNPos.x * 0.5 + 0.5, 0.5 - holoNPos.y * 0.5) : ((hFace == 1) ? vec2(0.5 - holoNPos.z * 0.5, 0.5 - holoNPos.y * 0.5) : ((hFace == 3) ? vec2(holoNPos.z * 0.5 + 0.5, 0.5 - holoNPos.y * 0.5) : vec2(holoNPos.x * 0.5 + 0.5, holoNPos.z * 0.5 + 0.5))));\n" +
                            "vec4 hRect = pb.planets[holoIdx].uvFaces[hFace];\n" +
                            "vec3 hCol = textureLod(planetTexArray, vec3(mix(hRect.xy, hRect.zw, clamp(hFuv, 0.01, 0.99)), sPlanetRotAndFlags[holoIdx].w), 0.0).rgb * 1.05;\n" +
                            "if (!validSpawn) hCol = mix(hCol, vec3(1.0, 0.12, 0.12), 0.78);\n" +
                            "col = mix(col, hCol, sPlanetRotAndFlags[holoIdx].z);\n" +
                            "}\n" +
                            "vec3 gasAccumColor = vec3(0.0); float gasWeightSum = 0.0, gasTransmittance = 1.0;\n" +
                            "int tileDebCount = int(min(sTileDebrisCount, 256u));\n" +
                            "for (int td = 0; td < tileDebCount; td++) {\n" +
                            "int d = sTileDebrisIdx[td]; if (db.debris[d].rotAndType.y < 0.5) continue;\n" +
                            "vec4 posRad = sTileDebrisPosRad[td]; vec3 dPos = posRad.xyz; float dSize = posRad.w, proj = dot(dPos, rayDir);\n" +
                            "if (proj <= 0.0 || proj >= closestT) continue;\n" +
                            "vec3 ortho = dPos - rayDir * proj; if (dot(ortho, ortho) > dSize * dSize * 3.1) continue;\n" +
                            "float ang = db.debris[d].rotAndType.x;\n" +
                            "vec3 ro = rotateY(rotateZ(-dPos, -ang * 0.7), -ang) / dSize, rd = rotateY(rotateZ(rayDir, -ang * 0.7), -ang);\n" +
                            "vec3 invDir = 1.0 / vec3(abs(rd.x) < 1e-7 ? 1e-7 : rd.x, abs(rd.y) < 1e-7 ? 1e-7 : rd.y, abs(rd.z) < 1e-7 ? 1e-7 : rd.z);\n" +
                            "vec3 t0 = (-vec3(1.0) - ro) * invDir, t1 = (vec3(1.0) - ro) * invDir;\n" +
                            "vec3 tmin = min(t0, t1), tmax = max(t0, t1);\n" +
                            "float tNear = max(max(tmin.x, tmin.y), tmin.z), tFar = min(min(tmax.x, tmax.y), tmax.z);\n" +
                            "if (tNear < tFar && tFar > 0.0) {\n" +
                            "float hitT = ((tNear > 0.0) ? tNear : tFar) * dSize;\n" +
                            "if (hitT < closestT) {\n" +
                            "closestT = hitT; hitBlackHoleCube = false;\n" +
                            "float dTemp = db.debris[d].colorAndTemp.w;\n" +
                            "vec3 nPos = clamp(ro + rd * (hitT / dSize), -1.0, 1.0), absF = abs(nPos), fLocalN = vec3(0,1,0);\n" +
                            "vec2 fuv = vec2(0.5);\n" +
                            "if (absF.z >= absF.x && absF.z >= absF.y) { fuv = vec2(nPos.x * 0.5 + 0.5, 0.5 - nPos.y * 0.5); fLocalN = vec3(0,0,nPos.z < 0.0 ? -1.0 : 1.0); }\n" +
                            "else if (absF.x >= absF.y) { fuv = vec2(nPos.z * 0.5 + 0.5, 0.5 - nPos.y * 0.5); fLocalN = vec3(nPos.x < 0.0 ? -1.0 : 1.0, 0, 0); }\n" +
                            "else { fuv = vec2(nPos.x * 0.5 + 0.5, nPos.z * 0.5 + 0.5); fLocalN = vec3(0, nPos.y < 0.0 ? -1.0 : 1.0, 0); }\n" +
                            "vec2 texUV = db.debris[d].rotAndType.zw + clamp(fuv, 0.02, 0.98) * (1.0 / 16.0);\n" +
                            "vec3 texCol = textureLod(planetTexArray, vec3(texUV, FRAG_TEX_LAYER), 0.0).rgb;\n" +
                            "if (shaderMode == 0 && dTemp <= 35.0) {\n" +
                            "float fragSnow = computeVoxelSnowMask(nPos, dTemp, 0.08, 6.0);\n" +
                            "if (fragSnow > 0.01) texCol = mix(texCol, vec3(0.92, 0.96, 1.0), fragSnow * 0.92);\n" +
                            "}\n" +
                            "vec3 baseCol = texCol;\n" +
                            "if (shaderMode < 2) {\n" +
                            "if (emitterCount > 0) {\n" +
                            "int eIdx = sEmitterIndices[0]; vec4 eSphere = sPlanetRelAndBound[eIdx];\n" +
                            "vec3 hitFragWorld = rayDir * hitT, toE = eSphere.xyz - hitFragWorld;\n" +
                            "float distE = max(length(toE), 1e-4); vec3 L = toE / distE;\n" +
                            "vec3 fragWorldN = fastNorm(rotateZ(rotateY(fastNorm(mix(fLocalN, nPos, 0.35)), ang), ang * 0.7));\n" +
                            "float shadow = traceShadow(hitFragWorld, L, distE, eSphere.w, -1, eIdx, count, false);\n" +
                            "baseCol *= (0.08 + 0.92 * max(0.0, dot(fragWorldN, L)) * shadow);\n" +
                            "} else baseCol *= 0.04;\n" +
                            "}\n" +
                            "if (dTemp > 550.0) {\n" +
                            "float glow = clamp((dTemp - 550.0) / 1500.0, 0.0, 2.5);\n" +
                            "baseCol = mix(baseCol, getBlackbodyColor(dTemp) * (1.3 + glow), clamp(glow * 0.8, 0.0, 0.95));\n" +
                            "}\n" +
                            "col = baseCol; bloomMask = 0.0;\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "for (int td = 0; td < tileDebCount; td++) {\n" +
                            "int d = sTileDebrisIdx[td]; if (db.debris[d].rotAndType.y > 0.5) continue;\n" +
                            "vec4 posRad = sTileDebrisPosRad[td]; vec3 dPos = posRad.xyz; float dSize = posRad.w, proj = dot(dPos, rayDir);\n" +
                            "if (proj <= 0.0 || proj >= closestT) continue;\n" +
                            "vec3 ortho = dPos - rayDir * proj; float bSq = dot(ortho, ortho), gasRad = dSize * 2.4;\n" +
                            "if (bSq < gasRad * gasRad) {\n" +
                            "float rNorm = sqrt(bSq) / max(gasRad, 1e-4), puffDensity = smoothstep(1.0, 0.12, rNorm), density = puffDensity * puffDensity * 0.28;\n" +
                            "if (density > 0.002) {\n" +
                            "vec3 gasCol = db.debris[d].colorAndTemp.rgb; float dTemp = db.debris[d].colorAndTemp.w;\n" +
                            "if (dTemp > 550.0) {\n" +
                            "float glow = clamp((dTemp - 550.0) / 1600.0, 0.0, 2.2);\n" +
                            "gasCol = mix(gasCol, getBlackbodyColor(dTemp) * (1.2 + glow), clamp(glow * 0.85, 0.0, 0.95));\n" +
                            "} else if (shaderMode < 2) {\n" +
                            "if (emitterCount > 0) {\n" +
                            "vec3 L = fastNorm(sPlanetRelAndBound[sEmitterIndices[0]].xyz - dPos);\n" +
                            "gasCol *= (0.28 + 0.72 * max(dot(fastNorm(-ortho + rayDir * dSize * 0.5), L), 0.0));\n" +
                            "} else gasCol *= 0.02;\n" +
                            "}\n" +
                            "gasAccumColor += gasCol * density; gasWeightSum += density; gasTransmittance *= (1.0 - density);\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "if (!hitBlackHoleCube && (showGrid || showGravGrid || showHZ)) {\n" +
                            "float camY = float(camPosD.y), tFlat = (abs(rayDir.y) > 0.0001) ? (-camY / rayDir.y) : -1.0;\n" +
                            "if (showHZ && emitterCount > 0 && tFlat > 0.0 && tFlat < closestT) {\n" +
                            "float grazingFade = smoothstep(0.002, 0.045, abs(rayDir.y));\n" +
                            "vec2 hitRelXZ = rayDir.xz * tFlat; vec3 hzColorAccum = vec3(0.0); float hzAlphaAccum = 0.0;\n" +
                            "for (int s = 0; s < emitterCount; s++) {\n" +
                            "int sIdx = sEmitterIndices[s]; if (sPlanetShadeParams[sIdx].x < 0.5) continue;\n" +
                            "float starTempK = max(800.0, sPlanetShadeParams[sIdx].w + 273.15);\n" +
                            "vec4 sSphere = sPlanetRelAndBound[sIdx]; float sRad = sSphere.w, dStar = length(hitRelXZ - sSphere.xz);\n" +
                            "float kFactor = max(0.25, hzPlanetFactor) * sRad * starTempK * starTempK;\n" +
                            "float rHot = max(sRad * 1.15, kFactor / (373.15 * 373.15)), rOuter = max(rHot * 1.15, kFactor / (273.15 * 273.15)), rCold = max(rOuter * 1.25, kFactor / (195.0 * 195.0));\n" +
                            "if (dStar > sRad * 1.05 && dStar < rCold) {\n" +
                            "float edgeSoft = max(0.15, dStar * 0.012);\n" +
                            "if (dStar < rHot) {\n" +
                            "float innerFade = smoothstep(sRad * 1.05, sRad * 1.35, dStar), border = smoothstep(edgeSoft * 1.5, 0.0, abs(dStar - rHot)), aHot = (0.20 + border * 0.28) * innerFade;\n" +
                            "hzColorAccum += mix(vec3(0.95, 0.22, 0.08), vec3(1.0, 0.55, 0.20), border) * aHot; hzAlphaAccum = max(hzAlphaAccum, aHot);\n" +
                            "} else if (dStar <= rOuter) {\n" +
                            "float border = max(smoothstep(edgeSoft * 1.5, 0.0, abs(dStar - rHot)), smoothstep(edgeSoft * 1.5, 0.0, abs(dStar - rOuter))), aHab = 0.26 + border * 0.32;\n" +
                            "hzColorAccum += mix(vec3(0.10, 0.88, 0.32), vec3(0.45, 1.0, 0.62), border) * aHab; hzAlphaAccum = max(hzAlphaAccum, aHab);\n" +
                            "} else {\n" +
                            "float outerFade = 1.0 - smoothstep(rOuter, rCold, dStar), border = smoothstep(edgeSoft * 1.5, 0.0, abs(dStar - rCold)), aCold = (0.18 * outerFade) + border * 0.22;\n" +
                            "hzColorAccum += vec3(0.15, 0.58, 1.0) * aCold; hzAlphaAccum = max(hzAlphaAccum, aCold);\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "if (hzAlphaAccum > 0.001) { float visHz = clamp(hzAlphaAccum * grazingFade, 0.0, 0.65); col = col * (1.0 - visHz) + hzColorAccum * grazingFade; }\n" +
                            "}\n" +
                            "if (showGrid || showGravGrid) {\n" +
                            "vec3 gRay = rayDir;\n" +
                            "double effDist = max(10.0LF, abs(pc.camPosAndDist.w));\n" +
                            "float logY = log(float(effDist)) / 2.302585093, floorLog = floor(logY);\n" +
                            "double minorStepD = double(pow(10.0, floorLog));\n" +
                            "float minorStep = float(minorStepD), minorAlphaMod = pow(1.0 - (logY - floorLog), 1.35), renderDist = minorStep * 1400.0, tGrid = -1.0;\n" +
                            "vec4 funnelInfo = vec4(0.0); vec2 radialPullXZ = vec2(0.0);\n" +
                            "if (showGravGrid && count > 0) {\n" +
                            "float tNearG = 0.15, tFlatRaw = (abs(gRay.y) > 0.0001) ? (-camY / gRay.y) : -1.0;\n" +
                            "float tMaxG = min(closestT, (tFlatRaw > 0.0) ? min(renderDist, tFlatRaw * 3.8 + minorStep * 80.0) : min(renderDist, float(effDist) * 18.0));\n" +
                            "if (tMaxG > tNearG) {\n" +
                            "float prevT = tNearG;\n" +
                            "vec4 f0 = sampleSmartGravityFunnel(gRay.xz * prevT, count, time, radialPullXZ);\n" +
                            "float prevH = (camY + gRay.y * prevT) - f0.x;\n" +
                            "const int MARCH_STEPS = 40;\n" +
                            "for (int m = 1; m <= MARCH_STEPS; m++) {\n" +
                            "float frac = float(m) / float(MARCH_STEPS), currT = mix(tNearG, tMaxG, frac * frac);\n" +
                            "vec4 fCurr = sampleSmartGravityFunnel(gRay.xz * currT, count, time, radialPullXZ);\n" +
                            "float currH = (camY + gRay.y * currT) - fCurr.x;\n" +
                            "if (prevH * currH <= 0.0) {\n" +
                            "float lo = prevT, hi = currT;\n" +
                            "for (int b = 0; b < 8; b++) {\n" +
                            "float mid = 0.5 * (lo + hi);\n" +
                            "vec4 fMid = sampleSmartGravityFunnel(gRay.xz * mid, count, time, radialPullXZ);\n" +
                            "float midH = (camY + gRay.y * mid) - fMid.x;\n" +
                            "if (prevH * midH <= 0.0) hi = mid; else { lo = mid; prevH = midH; }\n" +
                            "}\n" +
                            "tGrid = 0.5 * (lo + hi);\n" +
                            "funnelInfo = sampleSmartGravityFunnel(gRay.xz * tGrid, count, time, radialPullXZ);\n" +
                            "break;\n" +
                            "}\n" +
                            "prevT = currT; prevH = currH;\n" +
                            "}\n" +
                            "}\n" +
                            "} else tGrid = tFlat;\n" +
                            "if (tGrid > 0.0 && tGrid < closestT) {\n" +
                            "vec2 hitRelXZ = gRay.xz * tGrid; float distToCam = length(hitRelXZ);\n" +
                            "if (distToCam < renderDist) {\n" +
                            "float fadeCenter = pow(1.0 - (distToCam / renderDist), 1.6);\n" +
                            "vec2 slopeGrad = showGravGrid ? funnelInfo.yz : vec2(0.0);\n" +
                            "vec3 surfNorm = fastNorm(vec3(-slopeGrad.x, 1.0, -slopeGrad.y));\n" +
                            "float cosView = abs(dot(gRay, surfNorm)), grazingFadeG = smoothstep(0.002, 0.045, max(abs(gRay.y), cosView));\n" +
                            "dvec2 worldGridXZ = camPosD.xz + dvec2(hitRelXZ + (showGravGrid ? radialPullXZ : vec2(0.0))), scaledXZ = worldGridXZ / minorStepD;\n" +
                            "vec2 gridFract0 = vec2(abs(fract(scaledXZ + dvec2(0.5LF)) - dvec2(0.5LF)));\n" +
                            "vec2 gridFract1 = vec2(abs(fract(scaledXZ * 0.1LF + dvec2(0.5LF)) - dvec2(0.5LF)));\n" +
                            "vec2 gridFract2 = vec2(abs(fract(scaledXZ * 0.01LF + dvec2(0.5LF)) - dvec2(0.5LF)));\n" +
                            "vec2 metricScale = clamp(sqrt(vec2(1.0) + slopeGrad * slopeGrad), vec2(1.0), vec2(6.5));\n" +
                            "gridFract0 /= metricScale; gridFract1 /= metricScale; gridFract2 /= metricScale;\n" +
                            "float slant = 1.0 / max(cosView, 0.12);\n" +
                            "float isoPx0 = (tGrid / (height * minorStep)) * 1.25 * sqrt(slant), isoPx1 = isoPx0 * 0.1, isoPx2 = isoPx0 * 0.01;\n" +
                            "float w0 = clamp(isoPx0, 0.0016, 0.22), w1 = clamp(isoPx1, 0.0007, 0.22), w2 = clamp(isoPx2, 0.00035, 0.22);\n" +
                            "vec2 lines0 = smoothstep(vec2(w0 * 1.35), vec2(w0 * 0.35), gridFract0) * (1.0 - smoothstep(0.08, 0.22, isoPx0));\n" +
                            "vec2 lines1 = smoothstep(vec2(w1 * 1.35), vec2(w1 * 0.35), gridFract1) * (1.0 - smoothstep(0.08, 0.22, isoPx1));\n" +
                            "vec2 lines2 = smoothstep(vec2(w2 * 1.35), vec2(w2 * 0.35), gridFract2) * (1.0 - smoothstep(0.08, 0.22, isoPx2));\n" +
                            "float cell0 = max(lines0.x, lines0.y) * 0.55 * minorAlphaMod, cell1 = max(lines1.x, lines1.y) * 0.88, cell2 = max(lines2.x, lines2.y) * 0.96, contourRing = 0.0;\n" +
                            "if (showGravGrid && -funnelInfo.x > minorStep * 0.05) {\n" +
                            "float slopeLen = length(slopeGrad), yScaled = (-funnelInfo.x) / (minorStep * 0.5);\n" +
                            "float yFract = abs(fract(yScaled + 0.5) - 0.5) / max(1.0, slopeLen * 0.45), wY = clamp(isoPx0 * 1.2, 0.002, 0.20);\n" +
                            "contourRing = smoothstep(wY * 1.4, wY * 0.35, yFract) * clamp(slopeLen * 0.65, 0.0, 0.85);\n" +
                            "}\n" +
                            "float lineAlpha = max(max(max(cell0, cell1), cell2), contourRing) * fadeCenter * grazingFadeG;\n" +
                            "float throatShade = showGravGrid ? clamp(exp(-funnelInfo.w * 0.10), 0.28, 1.0) : 1.0;\n" +
                            "col = mix(col, vec3(throatShade), clamp(lineAlpha, 0.0, 1.0));\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "if (!hitBlackHoleCube && gasWeightSum > 1e-5) col = mix(col, gasAccumColor / gasWeightSum, clamp(1.0 - gasTransmittance, 0.0, 0.95));\n" +
                            "if (shaderMode == 0 && !hitBlackHoleCube) {\n" +
                            "vec3 solarEffects = vec3(0.0); float dither = hash2D(vec2(coord) + fract(time * 0.73));\n" +
                            "for (int s = 0; s < emitterCount; s++) {\n" +
                            "int sIdx = sEmitterIndices[s]; if ((int(round(sPlanetShadeParams[sIdx].y)) & 2) == 0) continue;\n" +
                            "bool isStar = (sPlanetShadeParams[sIdx].x > 0.5);\n" +
                            "float tempC = max(sPlanetShadeParams[sIdx].w, getPlanetMaxCraterTemp(sIdx) * 0.85);\n" +
                            "if ((isStar && tempC < 550.0) || (!isStar && tempC < 900.0)) continue;\n" +
                            "vec4 sSphere = sPlanetRelAndBound[sIdx]; vec3 toStar = sSphere.xyz; float sRad = sSphere.w, distStar = length(toStar);\n" +
                            "if (hitPlanetIdx == sIdx || closestT < distStar) continue;\n" +
                            "float trueAppRad = (sRad * 1.15) / max(distStar, 1e-4), distAtten = clamp(trueAppRad * 450.0, 0.0, 1.0);\n" +
                            "if (distAtten <= 0.002) continue;\n" +
                            "vec3 starDir = toStar / max(distStar, 1e-4); float viewFade = smoothstep(0.12, 0.62, dot(rayDir, starDir));\n" +
                            "if (viewFade > 0.001) {\n" +
                            "vec3 orthoVec = (rayDir * dot(toStar, rayDir)) - toStar;\n" +
                            "vec3 sRight = fastNorm(cross(starDir, abs(starDir.y) < 0.99 ? vec3(0,1,0) : vec3(1,0,0))), sUp = cross(sRight, starDir);\n" +
                            "vec2 deltaUV = vec2(dot(orthoVec, sRight), dot(orthoVec, sUp)) / distStar;\n" +
                            "float screenDist = length(deltaUV), appRad = max(1e-5, trueAppRad), normDist = max(0.0, (screenDist - appRad * 0.72) / appRad), angle = atan(deltaUV.y, deltaUV.x);\n" +
                            "float innerCorona = 1.55 / (1.0 + normDist * normDist * 2.2), outerBloom = 0.52 / (1.0 + normDist * 1.45 + normDist * normDist * 0.18);\n" +
                            "float streamer = noiseAngle(angle, 9.0, time * 0.18) * noiseAngle(angle + 1.3, 17.0, -time * 0.12);\n" +
                            "float streamerRays = pow(streamer, 2.2) * (2.6 / (1.0 + normDist * 2.5 + normDist * normDist * 0.22));\n" +
                            "float spike6 = pow(max(0.0, cos(angle * 3.0)), 36.0) * (1.6 / (1.0 + normDist * 1.8));\n" +
                            "float anamorphic = exp(-abs(deltaUV.y) / max(appRad * 0.35, 1e-5)) / (1.0 + abs(deltaUV.x) / max(appRad * 4.5, 1e-4));\n" +
                            "float rayPower = (isStar ? clamp((tempC - 550.0) / 1200.0, 0.0, 1.0) : clamp((tempC - 900.0) / 2200.0, 0.0, 1.0)) * distAtten;\n" +
                            "float rawGlare = (innerCorona + outerBloom + streamerRays + spike6 * 0.7 + anamorphic * 0.65) * rayPower * viewFade;\n" +
                            "if (rawGlare > 0.004) {\n" +
                            "const int RAY_SAMPLES = 12; float unoccludedSamples = 0.0;\n" +
                            "for (int m = 0; m < RAY_SAMPLES; m++) {\n" +
                            "float frac = (float(m) + dither) / float(RAY_SAMPLES);\n" +
                            "vec3 targetPt = toStar + fastNorm(orthoVec + vec3(1e-5)) * (sRad * 0.78 * (1.0 - frac));\n" +
                            "vec3 sampleDir = fastNorm(mix(rayDir, fastNorm(targetPt), frac)); float sampleBlocked = 1.0;\n" +
                            "for (int k = 0; k < count; k++) {\n" +
                            "if (k == sIdx || sPlanetRotAndFlags[k].z < 0.99) continue;\n" +
                            "if (sPlanetShadeParams[k].x > 0.5 && sPlanetShadeParams[k].x < 1.5 && sPlanetShadeParams[k].w > 550.0) continue;\n" +
                            "float tOcc = intersectPlanetBox(vec3(0.0), sampleDir, k);\n" +
                            "if (tOcc > 0.0 && tOcc < distStar) { sampleBlocked = 0.0; break; }\n" +
                            "}\n" +
                            "unoccludedSamples += sampleBlocked;\n" +
                            "}\n" +
                            "vec3 starGlare = getBlackbodyColor(tempC) * (rawGlare * (unoccludedSamples / float(RAY_SAMPLES)));\n" +
                            "starGlare.r *= 1.0 + 0.12 * smoothstep(1.0, 6.0, normDist); starGlare.b *= 1.0 - 0.08 * smoothstep(1.0, 6.0, normDist);\n" +
                            "solarEffects += starGlare;\n" +
                            "}\n" +
                            "}\n" +
                            "}\n" +
                            "col += solarEffects;\n" +
                            "}\n" +
                            "col = hitBlackHoleCube ? col : applyToneMap(col, hdrOn);\n" +
                            "imageStore(resultImage, coord, vec4(col, hitBlackHoleCube ? 0.0 : bloomMask));\n" +
                            "}\n";

            long compiler = Shaderc.shaderc_compiler_initialize();
            long result = Shaderc.shaderc_compile_into_spv(compiler, glsl, Shaderc.shaderc_glsl_compute_shader, "space.comp", "main", 0);
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                throw new RuntimeException("[SBox Compute] ОШИБКА ШЕЙДЕРА:\n" + Shaderc.shaderc_result_get_error_message(result));
            }
            ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);

            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO).pCode(spirv);
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
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(bindings);
            LongBuffer pSetLayout = stack.mallocLong(1);
            vkCreateDescriptorSetLayout(device, layoutInfo, null, pSetLayout);
            descriptorSetLayout = pSetLayout.get(0);

            VkPushConstantRange.Buffer pushConstant = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(64);

            VkPipelineLayoutCreateInfo pPipelineLayoutCreateInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout)).pPushConstantRanges(pushConstant);
            LongBuffer pPipelineLayout = stack.mallocLong(1);
            vkCreatePipelineLayout(device, pPipelineLayoutCreateInfo, null, pPipelineLayout);
            pipelineLayout = pPipelineLayout.get(0);

            VkPipelineShaderStageCreateInfo stageInfo = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule).pName(stack.UTF8("main"));

            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO).stage(stageInfo).layout(pipelineLayout);
            LongBuffer pComputePipeline = stack.mallocLong(1);
            vkCreateComputePipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pComputePipeline);
            computePipeline = pComputePipeline.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);
            poolSizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO).pPoolSizes(poolSizes).maxSets(1);
            LongBuffer pDescriptorPool = stack.mallocLong(1);
            vkCreateDescriptorPool(device, poolInfo, null, pDescriptorPool);
            descriptorPool = pDescriptorPool.get(0);

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer pDescriptorSet = stack.mallocLong(1);
            vkAllocateDescriptorSets(device, allocInfo, pDescriptorSet);
            descriptorSet = pDescriptorSet.get(0);

            VkDescriptorImageInfo.Buffer canvasInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL).imageView(SBoxVulkanCanvas.canvasImageView);
            VkDescriptorBufferInfo.Buffer bufInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(planetBuffer).offset(0).range(BUFFER_SIZE);
            VkDescriptorImageInfo.Buffer planetTexInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(planetArrayImageView).sampler(planetSampler);
            VkDescriptorBufferInfo.Buffer debBufInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(debrisBuffer).offset(0).range(DEBRIS_BUFFER_SIZE);

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
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO).flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(cmd, beginInfo);

            VkImageMemoryBarrier.Buffer initBarrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcAccessMask(0).dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(SBoxVulkanCanvas.canvasImage);
            initBarrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);

            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, initBarrier);
            vkEndCommandBuffer(cmd);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO).pCommandBuffers(stack.pointers(cmd));
            vkQueueSubmit(SBoxVulkanCore.graphicsQueue, submitInfo, VK_NULL_HANDLE);
            vkQueueWaitIdle(SBoxVulkanCore.graphicsQueue);
        }
    }

    private static void createBuffers(MemoryStack stack, VkDevice device) {
        int rebarFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        int fallbackFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;

        VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO).size(BUFFER_SIZE)
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        LongBuffer pBuffer = stack.mallocLong(1);
        vkCreateBuffer(device, bufferInfo, null, pBuffer);
        planetBuffer = pBuffer.get(0);

        VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
        vkGetBufferMemoryRequirements(device, planetBuffer, memReqs);

        VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO).allocationSize(memReqs.size())
                .memoryTypeIndex(findMemoryTypeWithFallback(memReqs.memoryTypeBits(), rebarFlags, fallbackFlags));
        LongBuffer pMemory = stack.mallocLong(1);
        vkAllocateMemory(device, allocInfo, null, pMemory);
        planetBufferMemory = pMemory.get(0);
        vkBindBufferMemory(device, planetBuffer, planetBufferMemory, 0);

        PointerBuffer pData1 = stack.mallocPointer(1);
        vkMapMemory(device, planetBufferMemory, 0, BUFFER_SIZE, 0, pData1);
        mappedPlanetBuffer = pData1.getByteBuffer(0, BUFFER_SIZE).order(ByteOrder.nativeOrder());

        VkBufferCreateInfo debInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO).size(DEBRIS_BUFFER_SIZE)
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        vkCreateBuffer(device, debInfo, null, pBuffer);
        debrisBuffer = pBuffer.get(0);

        vkGetBufferMemoryRequirements(device, debrisBuffer, memReqs);
        allocInfo.allocationSize(memReqs.size())
                .memoryTypeIndex(findMemoryTypeWithFallback(memReqs.memoryTypeBits(), rebarFlags, fallbackFlags));
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
                    int srcW = bi.getWidth(), srcH = bi.getHeight();
                    int[] rgb = bi.getRGB(0, 0, srcW, srcH, null, 0, srcW);
                    for (int y = 0; y < TEX_SIZE; y++) {
                        int sy = (y * srcH) / TEX_SIZE;
                        for (int x = 0; x < TEX_SIZE; x++) {
                            int sx = (x * srcW) / TEX_SIZE;
                            int p = rgb[sy * srcW + sx];
                            allPixels.put((byte) ((p >> 16) & 0xFF)).put((byte) ((p >> 8) & 0xFF)).put((byte) (p & 0xFF)).put((byte) ((p >> 24) & 0xFF));
                        }
                    }
                } else {
                    for (int i = 0; i < TEX_SIZE * TEX_SIZE; i++) allPixels.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 255);
                }
            } catch (Exception e) {
                for (int i = 0; i < TEX_SIZE * TEX_SIZE; i++) allPixels.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 255);
            }
        }
        allPixels.flip();

        VkBufferCreateInfo stagingInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO).size(totalSize).usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        LongBuffer pStagingBuf = stack.mallocLong(1);
        vkCreateBuffer(device, stagingInfo, null, pStagingBuf);
        long stagingBuf = pStagingBuf.get(0);

        VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
        vkGetBufferMemoryRequirements(device, stagingBuf, memReqs);
        VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO).allocationSize(memReqs.size())
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
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO).imageType(VK_IMAGE_TYPE_2D)
                .extent(it -> it.width(TEX_SIZE).height(TEX_SIZE).depth(1))
                .mipLevels(1).arrayLayers(layerCount).format(VK_FORMAT_R8G8B8A8_UNORM)
                .tiling(VK_IMAGE_TILING_OPTIMAL).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                .samples(VK_SAMPLE_COUNT_1_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
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
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO).flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        vkBeginCommandBuffer(cmd, beginInfo);

        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(planetArrayImage)
                .subresourceRange(it -> it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(layerCount))
                .srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);

        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
                .imageSubresource(it -> it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(layerCount))
                .imageOffset(it -> it.x(0).y(0).z(0)).imageExtent(it -> it.width(TEX_SIZE).height(TEX_SIZE).depth(1));
        vkCmdCopyBufferToImage(cmd, stagingBuf, planetArrayImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

        barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, barrier);

        vkEndCommandBuffer(cmd);
        VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SUBMIT_INFO).pCommandBuffers(stack.pointers(cmd));
        vkQueueSubmit(SBoxVulkanCore.graphicsQueue, submitInfo, VK_NULL_HANDLE);
        vkQueueWaitIdle(SBoxVulkanCore.graphicsQueue);

        vkDestroyBuffer(device, stagingBuf, null);
        vkFreeMemory(device, stagingMem, null);

        VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO).image(planetArrayImage)
                .viewType(VK_IMAGE_VIEW_TYPE_2D_ARRAY).format(VK_FORMAT_R8G8B8A8_UNORM)
                .subresourceRange(it -> it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(layerCount));
        LongBuffer pView = stack.mallocLong(1);
        vkCreateImageView(device, viewInfo, null, pView);
        planetArrayImageView = pView.get(0);

        VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
        LongBuffer pSampler = stack.mallocLong(1);
        vkCreateSampler(device, samplerInfo, null, pSampler);
        planetSampler = pSampler.get(0);
    }

    private static void writePlanetToBuffer(
            int index, Vector3d pos, double radius, double mass, float tiltDeg, float angleDeg,
            float alpha, String texPath, String objName, String type, boolean flashlightOn, boolean raysOn,
            boolean isRigid, float centrifRatio, float temp, float[] flatUv, float[] crater100) {
        int base = index * PLANET_STRIDE;
        double safeRadius = (Double.isNaN(radius) || Double.isInfinite(radius) || radius < 1e-4) ? 1e-4 : radius;

        mappedPlanetBuffer.putDouble(base, pos.x);
        mappedPlanetBuffer.putDouble(base + 8, pos.y);
        mappedPlanetBuffer.putDouble(base + 16, pos.z);
        mappedPlanetBuffer.putDouble(base + 24, safeRadius);

        int texLayer = resolveTexLayer(texPath, objName);
        mappedPlanetBuffer.putFloat(base + 32, (float) Math.toRadians(tiltDeg));
        mappedPlanetBuffer.putFloat(base + 36, (float) Math.toRadians(angleDeg));
        mappedPlanetBuffer.putFloat(base + 40, alpha);
        mappedPlanetBuffer.putFloat(base + 44, (float) texLayer);

        float objType = "Star".equals(type) ? 1.0f : ("Hole".equals(type) ? 2.0f : 0.0f);
        int packedFlags = (flashlightOn ? 1 : 0) | (raysOn ? 2 : 0) | (isRigid ? 4 : 0);
        mappedPlanetBuffer.putFloat(base + 48, objType);
        mappedPlanetBuffer.putFloat(base + 52, (float) packedFlags);
        mappedPlanetBuffer.putFloat(base + 56, centrifRatio);
        mappedPlanetBuffer.putFloat(base + 60, temp);

        for (int k = 0; k < 24; k++) mappedPlanetBuffer.putFloat(base + 64 + (k << 2), flatUv[k]);

        float wellDepth = 0.0f, wellWidth = 1.0f;
        if (mass > 0.0) {
            double earthNormR = safeRadius / 5.05635;
            double expectedMass = Math.max(1e-7, earthNormR * earthNormR * earthNormR
                    * ("Hole".equals(type) ? 4500.0 : ("Star".equals(type) ? 0.28 : (isRigid ? 1.0 : 0.24))));
            double densityRatio = Math.max(0.0, mass / expectedMass);
            double massResponse = Math.pow(densityRatio, 0.55);
            double absMassScale = 1.0 + 0.16 * Math.max(0.0, Math.log10(Math.max(1e-4, mass) * 100.0));
            double typeMult = "Hole".equals(type) ? 5.8 : ("Star".equals(type) ? 3.2 : 2.4);

            wellDepth = (float) Math.max(0.15, typeMult * massResponse * absMassScale);
            wellWidth = (float) Math.max(1.15, 1.85 * Math.pow(densityRatio, 0.25));
        }
        crater100[97] = wellDepth;
        crater100[99] = wellWidth;

        for (int k = 0; k < 100; k++) mappedPlanetBuffer.putFloat(base + 160 + (k << 2), crater100[k]);
    }

    public static int[] updateSSBOs() {
        synchronized (SpaceEngine.STATE_LOCK) {
            PhysicsEngine.ensureSaturnRings(SpaceEngine.planets);

            if (SpaceEngine.planetsNeedSort) {
                SpaceEngine.planets.sort(SpaceEngine.WARP_GROUP_COMPARATOR);
                SpaceEngine.planetsNeedSort = false;
            }

            List<SpaceEngine.Planet> planets = SpaceEngine.planets;
            int pCount = 0;
            for (int i = 0; i < planets.size() && pCount < MAX_PLANETS - 1; i++) {
                SpaceEngine.Planet p = planets.get(i);
                if (p == null || p.mass <= 0.0) continue;
                double safeRot = Math.max(0.0001, Math.abs(p.rotationPeriod));
                double wCurr = (2.0 * Math.PI) / (safeRot * 3600.0);
                double safeR = Math.max(0.02, p.radius);
                double wCrit = Math.sqrt((PhysicsEngine.G * p.mass) / (safeR * safeR * safeR));
                float centrifRatio = (float) Math.min(2.0, wCurr / Math.max(wCrit, 1e-9));

                PhysicsEngine.fillCraterRenderData(p, SCRATCH_CRATER_DATA);

                writePlanetToBuffer(
                        pCount, p.pos, p.radius, p.mass, (float) p.tilt, (float) p.currentAngle,
                        1.0f, p.texture, p.name, p.type, p.flashlightOn, p.raysOn,
                        p.isRigid, centrifRatio, (float) p.temperature, p.flatUv, SCRATCH_CRATER_DATA
                );
                pCount++;
            }

            if (!SpaceEngine.isPhotoModeActive && SpaceEngine.isPlacingPlanet && SpaceEngine.menuItems != null && SpaceEngine.selectedPlanetIndex < SpaceEngine.menuItems.length) {
                SpaceEngine.MenuItem item = SpaceEngine.menuItems[SpaceEngine.selectedPlanetIndex];
                Vector3d holoPos = SpaceEngine.getHologramPos(item.radius);
                if (SpaceEngine.isDraggingOrbit && SpaceEngine.orbitTarget != null) {
                    Vector3d outUp = new Vector3d();
                    SpaceEngine.getInclinedOrbitState(SpaceEngine.baseOrbitPos, SpaceEngine.orbitInclination, SpaceEngine.orbitTarget, holoPos, outUp);
                }
                SpaceEngine.validSpawn = !SpaceEngine.isOverlappingAnyPlanet(holoPos, item.radius);
                for (int z = 0; z < 100; z++) SCRATCH_CRATER_DATA[z] = 0.0f;

                writePlanetToBuffer(
                        pCount, holoPos, item.radius, item.mass, (float) item.tilt, 0.0f,
                        0.65f, item.planetTex, item.name, item.type, true, false,
                        item.isRigid, 0.0f, (float) item.temperature, item.flatUv, SCRATCH_CRATER_DATA
                );
                pCount++;
            } else {
                SpaceEngine.validSpawn = true;
            }

            double camX = CameraManager.pos.x, camY = CameraManager.pos.y, camZ = CameraManager.pos.z;
            int maxDebris = Math.min(MAX_DEBRIS, PhysicsEngine.getEffectiveDebrisLimit());
            List<SpaceEngine.Fragment> fragments = SpaceEngine.fragments;
            int dCount = 0;
            for (int i = 0; i < fragments.size() && dCount < maxDebris; i++) {
                SpaceEngine.Fragment f = fragments.get(i);
                if (f == null || f.life <= 0.0) continue;
                int base = dCount * DEBRIS_STRIDE;
                mappedDebrisBuffer.putFloat(base, (float) (f.pos.x - camX));
                mappedDebrisBuffer.putFloat(base + 4, (float) (f.pos.y - camY));
                mappedDebrisBuffer.putFloat(base + 8, (float) (f.pos.z - camZ));
                mappedDebrisBuffer.putFloat(base + 12, (float) Math.max(0.01, f.size));
                mappedDebrisBuffer.putFloat(base + 16, 0.7f);
                mappedDebrisBuffer.putFloat(base + 20, 0.65f);
                mappedDebrisBuffer.putFloat(base + 24, 0.6f);
                mappedDebrisBuffer.putFloat(base + 28, (float) f.temperature);
                mappedDebrisBuffer.putFloat(base + 32, (float) Math.toRadians(f.angle));
                mappedDebrisBuffer.putFloat(base + 36, 1.0f);
                mappedDebrisBuffer.putFloat(base + 40, f.u1);
                mappedDebrisBuffer.putFloat(base + 44, f.v1);
                dCount++;
            }

            List<SpaceEngine.Particle> particles = SpaceEngine.particles;
            for (int i = 0; i < particles.size() && dCount < maxDebris; i++) {
                SpaceEngine.Particle pt = particles.get(i);
                if (pt == null || pt.life <= 0.0) continue;
                int base = dCount * DEBRIS_STRIDE;
                mappedDebrisBuffer.putFloat(base, (float) (pt.pos.x - camX));
                mappedDebrisBuffer.putFloat(base + 4, (float) (pt.pos.y - camY));
                mappedDebrisBuffer.putFloat(base + 8, (float) (pt.pos.z - camZ));
                mappedDebrisBuffer.putFloat(base + 12, (float) Math.max(0.01, pt.size));
                mappedDebrisBuffer.putFloat(base + 16, pt.r / 255.0f);
                mappedDebrisBuffer.putFloat(base + 20, pt.g / 255.0f);
                mappedDebrisBuffer.putFloat(base + 24, pt.b / 255.0f);
                mappedDebrisBuffer.putFloat(base + 28, (float) pt.temperature);
                mappedDebrisBuffer.putFloat(base + 32, 0.92f);
                mappedDebrisBuffer.putFloat(base + 36, 0.0f);
                mappedDebrisBuffer.putFloat(base + 40, 0.0f);
                mappedDebrisBuffer.putFloat(base + 44, 0.0f);
                dCount++;
            }
            return new int[]{pCount, dCount};
        }
    }

    public static void recordSpacePass(VkCommandBuffer cmd, MemoryStack stack, int width, int height, int[] counts) {
        VkMemoryBarrier.Buffer hostToShaderBarrier = VkMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                .srcAccessMask(VK_ACCESS_HOST_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

        VkImageMemoryBarrier.Buffer preBarrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(SBoxVulkanCanvas.canvasImage);
        preBarrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);

        vkCmdPipelineBarrier(cmd,
                VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, hostToShaderBarrier, null, preBarrier);

        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, computePipeline);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptorSet), null);

        double effDist = Math.max(10.0, Math.abs(CameraManager.pos.y));
        if (CameraManager.isLocked && CameraManager.lockedTarget != null) {
            effDist = Math.max(effDist, Math.max(10.0, SpaceEngine.lockedOrbitDistance));
        }

        float pitch = CameraManager.rot.x, yaw = CameraManager.rot.y;
        long nowMs = System.currentTimeMillis();
        double deltaSec = Math.min(0.1, (nowMs - lastShaderFrameMs) / 1000.0);
        lastShaderFrameMs = nowMs;

        if (!SpaceEngine.isPaused && !SpaceEngine.isPhotoModeActive && SpaceEngine.physicsEnabled) {
            double ts = Math.abs(SpaceEngine.timeScale);
            double rateFactor = (ts <= 1.0) ? ts : (1.0 + Math.pow(Math.min(ts, 1e8) - 1.0, 0.36) * 0.45);
            cloudSimTime += deltaSec * rateFactor * Math.signum(SpaceEngine.timeScale);
            shaderAnimTime = cloudSimTime;
        }
        float time = (float) (shaderAnimTime % 100000.0);

        int packedDebrisFlags = (counts[1] & 262143)
                | ((SpaceEngine.shaderMode & 3) << 18)
                | (SpaceEngine.validSpawn ? (1 << 20) : 0)
                | (SpaceEngine.hdrEnabled ? (1 << 21) : 0)
                | (SpaceEngine.showGrid ? (1 << 22) : 0)
                | (SpaceUI.showHabitableZone ? (1 << 23) : 0)
                | (SpaceUI.showGravityGrid ? (1 << 24) : 0);

        ByteBuffer pushData = stack.calloc(64).order(ByteOrder.nativeOrder());
        pushData.putDouble(0, CameraManager.pos.x);
        pushData.putDouble(8, CameraManager.pos.y);
        pushData.putDouble(16, CameraManager.pos.z);
        pushData.putDouble(24, effDist);
        pushData.putFloat(32, (float) width);
        pushData.putFloat(36, (float) height);
        pushData.putFloat(40, time);
        pushData.putFloat(44, getActivePlanetHzFactor());
        pushData.putFloat(48, (float) Math.toRadians(pitch));
        pushData.putFloat(52, (float) Math.toRadians(yaw));
        pushData.putInt(56, counts[0]);
        pushData.putInt(60, packedDebrisFlags);
        pushData.position(0).limit(64);

        vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pushData);
        vkCmdDispatch(cmd, (width + 15) / 16, (height + 15) / 16, 1);

        VkImageMemoryBarrier.Buffer postBarrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(SBoxVulkanCanvas.canvasImage);
        postBarrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);

        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, postBarrier);
    }

    public static void renderSpace(int width, int height) {
        int[] counts = updateSSBOs();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd = SBoxVulkanCore.mainCommandBuffer;
            vkResetCommandBuffer(cmd, 0);
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO).flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(cmd, beginInfo);
            recordSpacePass(cmd, stack, width, height, counts);
            vkEndCommandBuffer(cmd);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO).pCommandBuffers(stack.pointers(cmd));
            vkQueueSubmit(SBoxVulkanCore.graphicsQueue, submitInfo, VK_NULL_HANDLE);
            vkQueueWaitIdle(SBoxVulkanCore.graphicsQueue);
        }
        if (SpaceEngine.requestScreenshot) {
            SpaceEngine.requestScreenshot = false;
            captureScreenshot(width, height);
        }
    }

    public static void captureScreenshot(int width, int height) {
        if (width <= 0 || height <= 0 || SBoxVulkanCanvas.canvasImage == VK_NULL_HANDLE) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice device = SBoxVulkanCore.logicalDevice;
            long imageSize = (long) width * height * 4L;

            VkBufferCreateInfo bufInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO).size(imageSize)
                    .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuf = stack.mallocLong(1);
            vkCreateBuffer(device, bufInfo, null, pBuf);
            long stagingBuf = pBuf.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, stagingBuf, memReqs);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO).allocationSize(memReqs.size())
                    .memoryTypeIndex(findMemoryType(memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            LongBuffer pMem = stack.mallocLong(1);
            vkAllocateMemory(device, allocInfo, null, pMem);
            long stagingMem = pMem.get(0);
            vkBindBufferMemory(device, stagingBuf, stagingMem, 0);

            VkCommandBuffer cmd = SBoxVulkanCore.mainCommandBuffer;
            vkResetCommandBuffer(cmd, 0);
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO).flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(cmd, beginInfo);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                    .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
                    .imageSubresource(it -> it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1))
                    .imageOffset(it -> it.x(0).y(0).z(0)).imageExtent(it -> it.width(width).height(height).depth(1));

            vkCmdCopyImageToBuffer(cmd, SBoxVulkanCanvas.canvasImage, VK_IMAGE_LAYOUT_GENERAL, stagingBuf, region);
            vkEndCommandBuffer(cmd);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO).pCommandBuffers(stack.pointers(cmd));
            vkQueueSubmit(SBoxVulkanCore.graphicsQueue, submitInfo, VK_NULL_HANDLE);
            vkQueueWaitIdle(SBoxVulkanCore.graphicsQueue);

            PointerBuffer pData = stack.mallocPointer(1);
            vkMapMemory(device, stagingMem, 0, imageSize, 0, pData);
            ByteBuffer raw = pData.getByteBuffer(0, (int) imageSize);

            final int[] rgbArray = new int[width * height];
            for (int i = 0; i < width * height; i++) {
                int base = i * 4;
                int r = raw.get(base) & 0xFF, g = raw.get(base + 1) & 0xFF, b = raw.get(base + 2) & 0xFF;
                rgbArray[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            }
            vkUnmapMemory(device, stagingMem);
            vkDestroyBuffer(device, stagingBuf, null);
            vkFreeMemory(device, stagingMem, null);

            final int w = width, h = height;
            new Thread(() -> {
                try {
                    File dir = new File("spacesb_screenshots");
                    if (!dir.exists()) dir.mkdirs();
                    String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS"));
                    File outFile = new File(dir, "screenshot_" + ts + ".png");
                    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                    img.setRGB(0, 0, w, h, rgbArray, 0, w);
                    ImageIO.write(img, "png", outFile);
                    SpaceUI.addNotification("Saved: " + outFile.getName());
                } catch (Exception e) {
                    System.err.println("[SBox Photo] Ошибка сохранения скриншота: " + e.getMessage());
                }
            }, "SBox-Screenshot-Saver").start();
        }
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
        throw new RuntimeException("Не найден подходящий тип памяти Vulkan!");
    }

    private static int findMemoryType(int typeBits, int properties) {
        return findMemoryTypeWithFallback(typeBits, properties, properties);
    }
}