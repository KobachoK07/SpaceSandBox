package spacesb.remake.render;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

public class SBoxVulkanUI {

    public static long descriptorSetLayout = VK_NULL_HANDLE;
    public static long descriptorPool = VK_NULL_HANDLE;
    public static long pipelineLayout = VK_NULL_HANDLE;
    public static long graphicsPipeline = VK_NULL_HANDLE;
    public static long sampler = VK_NULL_HANDLE;
    public static long linearBloomSampler = VK_NULL_HANDLE;

    public static long defaultDescriptorSet = VK_NULL_HANDLE;
    public static long canvasBloomDescriptorSet = VK_NULL_HANDLE;

    public static long vertexBuffer = VK_NULL_HANDLE;
    public static long vertexMemory = VK_NULL_HANDLE;
    public static ByteBuffer mappedBuffer;
    public static int vertexCount = 0;

    public static final int MAX_VERTICES = 60000;
    public static final int VERTEX_STRIDE = 36;

    private static class DrawCall {
        int vertexStart, vertexCount;
        float mode;
        long descriptorSet;
    }

    private static final int MAX_DRAW_CALLS = 2048;
    private static final DrawCall[] DRAW_CALL_POOL = new DrawCall[MAX_DRAW_CALLS];
    private static final List<DrawCall> drawCalls = new ArrayList<>(256);
    private static DrawCall currentCall = null;

    static {
        for (int i = 0; i < MAX_DRAW_CALLS; i++) {
            DRAW_CALL_POOL[i] = new DrawCall();
        }
    }

    public static void init() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice device = SBoxVulkanCore.logicalDevice;

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(220);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .pPoolSizes(poolSizes)
                    .maxSets(220);
            LongBuffer pDescriptorPool = stack.mallocLong(1);
            vkCreateDescriptorPool(device, poolInfo, null, pDescriptorPool);
            descriptorPool = pDescriptorPool.get(0);

            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_NEAREST)
                    .minFilter(VK_FILTER_NEAREST)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0.0f)
                    .maxLod(0.0f)
                    .unnormalizedCoordinates(false);
            LongBuffer pSampler = stack.mallocLong(1);
            vkCreateSampler(device, samplerInfo, null, pSampler);
            sampler = pSampler.get(0);

            VkSamplerCreateInfo linearInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_LINEAR)
                    .minFilter(VK_FILTER_LINEAR)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0.0f)
                    .maxLod(0.0f)
                    .unnormalizedCoordinates(false);
            vkCreateSampler(device, linearInfo, null, pSampler);
            linearBloomSampler = pSampler.get(0);

            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size((long) MAX_VERTICES * VERTEX_STRIDE)
                    .usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            vkCreateBuffer(device, bufferInfo, null, pBuffer);
            vertexBuffer = pBuffer.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, vertexBuffer, memReqs);
            int memoryTypeIndex = getMemoryType(memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(memoryTypeIndex);
            LongBuffer pMemory = stack.mallocLong(1);
            vkAllocateMemory(device, allocInfo, null, pMemory);
            vertexMemory = pMemory.get(0);
            vkBindBufferMemory(device, vertexBuffer, vertexMemory, 0);

            PointerBuffer pData = stack.mallocPointer(1);
            vkMapMemory(device, vertexMemory, 0, (long) MAX_VERTICES * VERTEX_STRIDE, 0, pData);
            mappedBuffer = pData.getByteBuffer(0, MAX_VERTICES * VERTEX_STRIDE).order(ByteOrder.nativeOrder());

            String vertCode = "#version 450\n" +
                    "layout(location = 0) in vec2 inPos;\n" +
                    "layout(location = 1) in vec2 inTexCoord;\n" +
                    "layout(location = 2) in vec4 inColor;\n" +
                    "layout(location = 3) in float inMode;\n" +
                    "layout(push_constant) uniform PushConstants { mat4 proj; } pc;\n" +
                    "layout(location = 0) out vec2 fragTexCoord;\n" +
                    "layout(location = 1) out vec4 fragColor;\n" +
                    "layout(location = 2) out float fragMode;\n" +
                    "void main() {\n" +
                    "gl_Position = pc.proj * vec4(inPos, 0.0, 1.0);\n" +
                    "fragTexCoord = inTexCoord;\n" +
                    "fragColor = inColor;\n" +
                    "fragMode = inMode;\n" +
                    "}\n";

            String fragCode = "#version 450\n" +
                    "layout(location = 0) in vec2 fragTexCoord;\n" +
                    "layout(location = 1) in vec4 fragColor;\n" +
                    "layout(location = 2) in float fragMode;\n" +
                    "layout(binding = 0) uniform sampler2D texSampler;\n" +
                    "layout(location = 0) out vec4 outColor;\n" +
                    "float fsrLuma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }\n" +
                    "void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {\n" +
                    "vec2 v = vec2(dot(off, dir), off.x * (-dir.y) + off.y * dir.x) * len;\n" +
                    "float d2 = min(dot(v, v), clp);\n" +
                    "float wB = 0.4 * d2 - 1.0;\n" +
                    "float wA = lob * d2 - 1.0;\n" +
                    "wB *= wB; wA *= wA;\n" +
                    "wB = 1.5625 * wB - 0.5625;\n" +
                    "float w = wB * wA;\n" +
                    "aC += c * w; aW += w;\n" +
                    "}\n" +
                    "void easuSet(inout vec2 dir, inout float len, vec2 pp, bool biS, bool biT, bool biU, bool biV, float lA, float lB, float lC, float lD, float lE) {\n" +
                    "float w = 0.0;\n" +
                    "if (biS) w = (1.0 - pp.x) * (1.0 - pp.y);\n" +
                    "if (biT) w = pp.x * (1.0 - pp.y);\n" +
                    "if (biU) w = (1.0 - pp.x) * pp.y;\n" +
                    "if (biV) w = pp.x * pp.y;\n" +
                    "float dc = lD - lC, cb = lC - lB;\n" +
                    "float lenX = max(abs(dc), abs(cb)), dirX = lD - lB;\n" +
                    "dir.x += dirX * w;\n" +
                    "lenX = clamp(abs(dirX) / max(lenX, 1e-5), 0.0, 1.0);\n" +
                    "len += (lenX * lenX) * w;\n" +
                    "float ec = lE - lC, ca = lC - lA;\n" +
                    "float lenY = max(abs(ec), abs(ca)), dirY = lE - lA;\n" +
                    "dir.y += dirY * w;\n" +
                    "lenY = clamp(abs(dirY) / max(lenY, 1e-5), 0.0, 1.0);\n" +
                    "len += (lenY * lenY) * w;\n" +
                    "}\n" +
                    "void main() {\n" +
                    "if (fragMode < 0.5) {\n" +
                    "outColor = fragColor;\n" +
                    "} else if (fragMode < 1.5) {\n" +
                    "outColor = texture(texSampler, fragTexCoord) * fragColor;\n" +
                    "} else if (fragMode < 2.5) {\n" +
                    "vec4 tex = texture(texSampler, fragTexCoord);\n" +
                    "outColor = vec4(fragColor.rgb, tex.r * fragColor.a);\n" +
                    "} else if (fragMode < 3.5) {\n" +
                    "vec4 tex = texture(texSampler, fragTexCoord);\n" +
                    "vec2 texel = 1.0 / vec2(textureSize(texSampler, 0));\n" +
                    "vec3 bloomSum = vec3(0.0);\n" +
                    "float weightSum = 0.0;\n" +
                    "const float radii[4] = float[4](3.5, 10.0, 24.0, 52.0);\n" +
                    "const float weights[4] = float[4](0.40, 0.28, 0.18, 0.10);\n" +
                    "for (int r = 0; r < 4; r++) {\n" +
                    "float rad = radii[r], w = weights[r];\n" +
                    "for (int a = 0; a < 8; a++) {\n" +
                    "float ang = float(a) * 0.78539816;\n" +
                    "vec4 s = texture(texSampler, fragTexCoord + vec2(cos(ang), sin(ang)) * texel * rad);\n" +
                    "bloomSum += s.rgb * s.a * w; weightSum += w;\n" +
                    "}\n" +
                    "}\n" +
                    "for (int h = -3; h <= 3; h++) {\n" +
                    "vec4 hs = texture(texSampler, fragTexCoord + vec2(float(h) * 18.0 * texel.x, 0.0));\n" +
                    "bloomSum += hs.rgb * hs.a * 0.12;\n" +
                    "}\n" +
                    "vec3 glow = (bloomSum / max(weightSum, 1.0)) * 2.4;\n" +
                    "float coronaMask = 1.0 - tex.a * 0.65;\n" +
                    "float glowLum = clamp(max(max(glow.r, glow.g), glow.b) * coronaMask, 0.0, 0.92);\n" +
                    "outColor = vec4(glow * coronaMask, glowLum);\n" +
                    "} else {\n" +
                    "vec2 fullTexSize = vec2(textureSize(texSampler, 0));\n" +
                    "vec2 invTexSize = 1.0 / fullTexSize;\n" +
                    "vec2 maxUV = (fragColor.rg - 0.5) * invTexSize;\n" +
                    "vec2 pp = fragTexCoord * fullTexSize - 0.5;\n" +
                    "vec2 fp = floor(pp); pp -= fp;\n" +
                    "vec2 p0 = clamp((fp + vec2( 0.0, -1.0) + 1.0) * invTexSize, invTexSize * 0.5, maxUV);\n" +
                    "vec2 p1 = clamp((fp + vec2(-1.0,  0.0) + 1.0) * invTexSize, invTexSize * 0.5, maxUV);\n" +
                    "vec2 p2 = clamp((fp + vec2( 1.0,  0.0) + 1.0) * invTexSize, invTexSize * 0.5, maxUV);\n" +
                    "vec2 p3 = clamp((fp + vec2( 0.0,  1.0) + 1.0) * invTexSize, invTexSize * 0.5, maxUV);\n" +
                    "vec4 r0 = textureGather(texSampler, p0, 0), g0 = textureGather(texSampler, p0, 1), b0 = textureGather(texSampler, p0, 2);\n" +
                    "vec4 r1 = textureGather(texSampler, p1, 0), g1 = textureGather(texSampler, p1, 1), b1 = textureGather(texSampler, p1, 2);\n" +
                    "vec4 r2 = textureGather(texSampler, p2, 0), g2 = textureGather(texSampler, p2, 1), b2 = textureGather(texSampler, p2, 2);\n" +
                    "vec4 r3 = textureGather(texSampler, p3, 0), g3 = textureGather(texSampler, p3, 1), b3 = textureGather(texSampler, p3, 2);\n" +
                    "vec3 b = vec3(r0.w, g0.w, b0.w), c = vec3(r0.z, g0.z, b0.z);\n" +
                    "vec3 e = vec3(r1.w, g1.w, b1.w), f = vec3(r1.z, g1.z, b1.z), g = vec3(r2.w, g2.w, b2.w), h = vec3(r2.z, g2.z, b2.z);\n" +
                    "vec3 i = vec3(r1.x, g1.x, b1.x), j = vec3(r1.y, g1.y, b1.y), k = vec3(r2.x, g2.x, b2.x), l = vec3(r2.y, g2.y, b2.y);\n" +
                    "vec3 n = vec3(r3.x, g3.x, b3.x), o = vec3(r3.y, g3.y, b3.y);\n" +
                    "float bL = fsrLuma(b), cL = fsrLuma(c), eL = fsrLuma(e), fL = fsrLuma(f), gL = fsrLuma(g), hL = fsrLuma(h);\n" +
                    "float iL = fsrLuma(i), jL = fsrLuma(j), kL = fsrLuma(k), lL = fsrLuma(l), nL = fsrLuma(n), oL = fsrLuma(o);\n" +
                    "vec2 dir = vec2(0.0); float len = 0.0;\n" +
                    "easuSet(dir, len, pp, true, false, false, false, bL, eL, fL, gL, jL);\n" +
                    "easuSet(dir, len, pp, false, true, false, false, cL, fL, gL, hL, kL);\n" +
                    "easuSet(dir, len, pp, false, false, true, false, fL, iL, jL, kL, nL);\n" +
                    "easuSet(dir, len, pp, false, false, false, true, gL, jL, kL, lL, oL);\n" +
                    "vec2 dir2 = dir * dir;\n" +
                    "float dirR = dir2.x + dir2.y;\n" +
                    "bool zro = dirR < (1.0 / 32768.0);\n" +
                    "dirR = zro ? 1.0 : inversesqrt(max(dirR, 1e-8));\n" +
                    "dir.x = zro ? 1.0 : dir.x;\n" +
                    "dir *= vec2(dirR);\n" +
                    "len = len * 0.5; len *= len;\n" +
                    "float stretch = dot(dir, dir) / max(abs(dir.x), abs(dir.y));\n" +
                    "vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);\n" +
                    "float lob = 0.5 - 0.29 * len, clp = 1.0 / lob;\n" +
                    "vec3 min4 = min(min(f, g), min(j, k)), max4 = max(max(f, g), max(j, k));\n" +
                    "vec3 aC = vec3(0.0); float aW = 0.0;\n" +
                    "easuTap(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, b);\n" +
                    "easuTap(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, c);\n" +
                    "easuTap(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, i);\n" +
                    "easuTap(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, j);\n" +
                    "easuTap(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, f);\n" +
                    "easuTap(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, e);\n" +
                    "easuTap(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, k);\n" +
                    "easuTap(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, l);\n" +
                    "easuTap(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, h);\n" +
                    "easuTap(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, g);\n" +
                    "easuTap(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, o);\n" +
                    "easuTap(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, n);\n" +
                    "vec3 easuCol = clamp(aC / max(aW, 1e-5), min4, max4);\n" +
                    "float easuL = clamp(fsrLuma(easuCol), 0.0, 1.0);\n" +
                    "float mn4L = clamp(min(min(fL, gL), min(jL, kL)), 0.0, 1.0);\n" +
                    "float mx4L = clamp(max(max(fL, gL), max(jL, kL)), 0.0, 1.0);\n" +
                    "float hitMin = min(mn4L, easuL) / max(4.0 * max(mx4L, easuL), 1e-4);\n" +
                    "float hitMax = max(0.0, 1.0 - max(mx4L, easuL)) / (-max(abs(4.0 - 4.0 * min(mn4L, easuL)), 1e-4));\n" +
                    "float rcasW = clamp(max(-hitMin, hitMax), -0.1875, 0.0) * fragColor.b;\n" +
                    "vec3 rcasCol = (easuCol + rcasW * (f + g + j + k)) / max(1.0 + 4.0 * rcasW, 1e-4);\n" +
                    "outColor = vec4(clamp(rcasCol, 0.0, 1.0), 1.0);\n" +
                    "}\n" +
                    "}\n";

            long vertModule = compileShader(vertCode, Shaderc.shaderc_glsl_vertex_shader, "ui.vert");
            long fragModule = compileShader(fragCode, Shaderc.shaderc_glsl_fragment_shader, "ui.frag");

            VkVertexInputBindingDescription.Buffer bindingDescription = VkVertexInputBindingDescription.calloc(1, stack)
                    .binding(0).stride(VERTEX_STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

            VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription.calloc(4, stack);
            attributeDescriptions.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
            attributeDescriptions.get(1).binding(0).location(1).format(VK_FORMAT_R32G32_SFLOAT).offset(8);
            attributeDescriptions.get(2).binding(0).location(2).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(16);
            attributeDescriptions.get(3).binding(0).location(3).format(VK_FORMAT_R32_SFLOAT).offset(32);

            VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                    .pVertexBindingDescriptions(bindingDescription)
                    .pVertexAttributeDescriptions(attributeDescriptions);
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                    .primitiveRestartEnable(false);
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1).scissorCount(1);
            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .depthClampEnable(false).rasterizerDiscardEnable(false)
                    .polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1.0f)
                    .cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_CLOCKWISE).depthBiasEnable(false);
            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .sampleShadingEnable(false).rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(true)
                    .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                    .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                    .dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO)
                    .alphaBlendOp(VK_BLEND_OP_ADD);
            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .logicOpEnable(false).pAttachments(colorBlendAttachment);

            IntBuffer dynamicStates = stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR);
            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(dynamicStates);

            VkDescriptorSetLayoutBinding.Buffer uboLayoutBinding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                    .binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(uboLayoutBinding);
            LongBuffer pDescriptorSetLayout = stack.mallocLong(1);
            vkCreateDescriptorSetLayout(device, layoutInfo, null, pDescriptorSetLayout);
            descriptorSetLayout = pDescriptorSetLayout.get(0);

            VkPushConstantRange.Buffer pushConstant = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(64);

            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout))
                    .pPushConstantRanges(pushConstant);
            LongBuffer pPipelineLayout = stack.mallocLong(1);
            vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pPipelineLayout);
            pipelineLayout = pPipelineLayout.get(0);

            VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            shaderStages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(stack.UTF8("main"));
            shaderStages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(stack.UTF8("main"));

            VkPipelineRenderingCreateInfo pipelineRenderingInfo = VkPipelineRenderingCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
                    .pColorAttachmentFormats(stack.ints(VK_FORMAT_B8G8R8A8_UNORM));

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pNext(pipelineRenderingInfo.address())
                    .pStages(shaderStages)
                    .pVertexInputState(vertexInputInfo)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(rasterizer)
                    .pMultisampleState(multisampling)
                    .pColorBlendState(colorBlending)
                    .pDynamicState(dynamicState)
                    .layout(pipelineLayout);

            LongBuffer pGraphicsPipeline = stack.mallocLong(1);
            vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pGraphicsPipeline);
            graphicsPipeline = pGraphicsPipeline.get(0);

            vkDestroyShaderModule(device, vertModule, null);
            vkDestroyShaderModule(device, fragModule, null);

            ByteBuffer whitePixel = MemoryUtil.memAlloc(4);
            whitePixel.putInt(0xFFFFFFFF).flip();
            defaultDescriptorSet = uploadTexture(whitePixel, 1, 1, VK_FORMAT_R8G8B8A8_UNORM);
            MemoryUtil.memFree(whitePixel);

            VkDescriptorSetAllocateInfo dsAllocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer pDs = stack.mallocLong(1);
            vkAllocateDescriptorSets(device, dsAllocInfo, pDs);
            canvasBloomDescriptorSet = pDs.get(0);
        }
    }

    public static void updateCanvasDescriptor() {
        if (canvasBloomDescriptorSet == VK_NULL_HANDLE || SBoxVulkanCanvas.canvasImageView == VK_NULL_HANDLE) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imageInfoDesc = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .imageView(SBoxVulkanCanvas.canvasImageView)
                    .sampler(linearBloomSampler);

            VkWriteDescriptorSet.Buffer writeSet = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(canvasBloomDescriptorSet)
                    .dstBinding(0)
                    .dstArrayElement(0)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(imageInfoDesc);

            vkUpdateDescriptorSets(SBoxVulkanCore.logicalDevice, writeSet, null);
        }
    }

    public static void drawFsrEasuRcasPass(int sWidth, int sHeight, int activeW, int activeH, int canvasW, int canvasH) {
        if (canvasBloomDescriptorSet == VK_NULL_HANDLE) return;
        float uMax = (float) activeW / Math.max(1, canvasW);
        float vMax = (float) activeH / Math.max(1, canvasH);
        drawQuad(0, 0, sWidth, sHeight, 0.0f, 0.0f, uMax, vMax, (float) activeW, (float) activeH, 0.88f, 1.0f, 4, canvasBloomDescriptorSet);
    }

    public static long uploadTexture(ByteBuffer pixels, int width, int height, int format) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice device = SBoxVulkanCore.logicalDevice;
            long size = pixels.capacity();

            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            LongBuffer pStagingBuffer = stack.mallocLong(1);
            vkCreateBuffer(device, bufferInfo, null, pStagingBuffer);
            long stagingBuffer = pStagingBuffer.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, stagingBuffer, memReqs);
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(getMemoryType(memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));

            LongBuffer pStagingMemory = stack.mallocLong(1);
            vkAllocateMemory(device, allocInfo, null, pStagingMemory);
            long stagingMemory = pStagingMemory.get(0);
            vkBindBufferMemory(device, stagingBuffer, stagingMemory, 0);

            PointerBuffer data = stack.mallocPointer(1);
            vkMapMemory(device, stagingMemory, 0, size, 0, data);
            MemoryUtil.memCopy(MemoryUtil.memAddress(pixels), data.get(0), size);
            vkUnmapMemory(device, stagingMemory);

            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .extent(it -> it.width(width).height(height).depth(1))
                    .mipLevels(1).arrayLayers(1)
                    .format(format).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pImage = stack.mallocLong(1);
            vkCreateImage(device, imageInfo, null, pImage);
            long image = pImage.get(0);

            vkGetImageMemoryRequirements(device, image, memReqs);
            allocInfo.allocationSize(memReqs.size())
                    .memoryTypeIndex(getMemoryType(memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            LongBuffer pImageMemory = stack.mallocLong(1);
            vkAllocateMemory(device, allocInfo, null, pImageMemory);
            long imageMemory = pImageMemory.get(0);
            vkBindImageMemory(device, image, imageMemory, 0);

            VkCommandBuffer cmd = beginSingleTimeCommands(stack);

            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .subresourceRange(it -> it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1))
                    .srcAccessMask(0)
                    .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                    .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
                    .imageSubresource(it -> it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1))
                    .imageOffset(it -> it.x(0).y(0).z(0))
                    .imageExtent(it -> it.width(width).height(height).depth(1));
            vkCmdCopyBufferToImage(cmd, stagingBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

            barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, barrier);

            endSingleTimeCommands(cmd);
            vkDestroyBuffer(device, stagingBuffer, null);
            vkFreeMemory(device, stagingMemory, null);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format)
                    .subresourceRange(it -> it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
            LongBuffer pView = stack.mallocLong(1);
            vkCreateImageView(device, viewInfo, null, pView);
            long imageView = pView.get(0);

            VkDescriptorSetAllocateInfo dsAllocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer pDescriptorSet = stack.mallocLong(1);
            if (vkAllocateDescriptorSets(device, dsAllocInfo, pDescriptorSet) != VK_SUCCESS) {
                throw new RuntimeException("[SBox UI] Не удалось выделить VkDescriptorSet для текстуры!");
            }
            long texDescriptorSet = pDescriptorSet.get(0);

            VkDescriptorImageInfo.Buffer imageInfoDesc = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(imageView)
                    .sampler(sampler);

            VkWriteDescriptorSet.Buffer writeSet = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(texDescriptorSet)
                    .dstBinding(0)
                    .dstArrayElement(0)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(imageInfoDesc);

            vkUpdateDescriptorSets(device, writeSet, null);
            return texDescriptorSet;
        }
    }

    public static void drawQuad(float x, float y, float w, float h, float u1, float v1, float u2, float v2, float r, float g, float b, float a, int mode, long descSet) {
        drawQuadVertices(
                x,     y,     u1, v1,
                x,     y + h, u1, v2,
                x + w, y + h, u2, v2,
                x + w, y,     u2, v1,
                r, g, b, a, mode, descSet
        );
    }

    public static void drawQuadVertices(
            float x0, float y0, float u0, float v0,
            float x1, float y1, float u1, float v1,
            float x2, float y2, float u2, float v2,
            float x3, float y3, float u3, float v3,
            float r, float g, float b, float a, int mode, long descSet) {

        if (vertexCount + 6 > MAX_VERTICES) return;

        long actualSet = (descSet == -1 || mode == 0) ? defaultDescriptorSet : descSet;
        float floatMode = (float) mode;

        if (currentCall == null || currentCall.descriptorSet != actualSet) {
            int callIdx = drawCalls.size();
            currentCall = (callIdx < MAX_DRAW_CALLS) ? DRAW_CALL_POOL[callIdx] : new DrawCall();
            currentCall.vertexStart = vertexCount;
            currentCall.vertexCount = 0;
            currentCall.mode = floatMode;
            currentCall.descriptorSet = actualSet;
            drawCalls.add(currentCall);
        }

        int offset = vertexCount * VERTEX_STRIDE;
        writeVertex(offset,       x0, y0, u0, v0, r, g, b, a, floatMode);
        writeVertex(offset + 36,  x1, y1, u1, v1, r, g, b, a, floatMode);
        writeVertex(offset + 72,  x2, y2, u2, v2, r, g, b, a, floatMode);
        writeVertex(offset + 108, x0, y0, u0, v0, r, g, b, a, floatMode);
        writeVertex(offset + 144, x2, y2, u2, v2, r, g, b, a, floatMode);
        writeVertex(offset + 180, x3, y3, u3, v3, r, g, b, a, floatMode);

        vertexCount += 6;
        currentCall.vertexCount += 6;
    }

    public static void drawLine(float x1, float y1, float x2, float y2, float thickness, float r, float g, float b, float a) {
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001f) return;
        float nx = (-dy / len) * (thickness * 0.5f);
        float ny = (dx / len) * (thickness * 0.5f);

        drawQuadVertices(
                x1 + nx, y1 + ny, 0, 0,
                x1 - nx, y1 - ny, 0, 0,
                x2 - nx, y2 - ny, 0, 0,
                x2 + nx, y2 + ny, 0, 0,
                r, g, b, a, 0, -1
        );
    }

    private static void writeVertex(int offset, float x, float y, float u, float v, float r, float g, float b, float a, float mode) {
        mappedBuffer.putFloat(offset, x);
        mappedBuffer.putFloat(offset + 4, y);
        mappedBuffer.putFloat(offset + 8, u);
        mappedBuffer.putFloat(offset + 12, v);
        mappedBuffer.putFloat(offset + 16, r);
        mappedBuffer.putFloat(offset + 20, g);
        mappedBuffer.putFloat(offset + 24, b);
        mappedBuffer.putFloat(offset + 28, a);
        mappedBuffer.putFloat(offset + 32, mode);
    }

    public static void renderUI(VkCommandBuffer cmd, long targetImageView, int physW, int physH, int logicalW, int logicalH) {
        if (drawCalls.isEmpty()) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkRenderingAttachmentInfo.Buffer colorAttachment = VkRenderingAttachmentInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                    .imageView(targetImageView)
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_INFO)
                    .renderArea(a -> a.offset(o -> o.set(0, 0)).extent(e -> e.width(physW).height(physH)))
                    .layerCount(1)
                    .pColorAttachments(colorAttachment);

            vkCmdBeginRendering(cmd, renderingInfo);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline);

            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(0).y(0).width((float) physW).height((float) physH).minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(cmd, 0, viewport);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset(it -> it.x(0).y(0)).extent(it -> it.width(physW).height(physH));
            vkCmdSetScissor(cmd, 0, scissor);

            FloatBuffer pushData = stack.callocFloat(16);
            pushData.put(0, 2.0f / logicalW);
            pushData.put(5, 2.0f / logicalH);
            pushData.put(10, -1.0f);
            pushData.put(12, -1.0f);
            pushData.put(13, -1.0f);
            pushData.put(15, 1.0f);

            vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, pushData);
            vkCmdBindVertexBuffers(cmd, 0, stack.longs(vertexBuffer), stack.longs(0));

            for (int i = 0, sz = drawCalls.size(); i < sz; i++) {
                DrawCall call = drawCalls.get(i);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(call.descriptorSet), null);
                vkCmdDraw(cmd, call.vertexCount, 1, call.vertexStart, 0);
            }

            vkCmdEndRendering(cmd);
        }
        drawCalls.clear();
        currentCall = null;
        vertexCount = 0;
    }

    private static int getMemoryType(int typeBits, int properties) {
        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.calloc();
        vkGetPhysicalDeviceMemoryProperties(SBoxVulkanCore.physicalDevice, memProps);
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0 && (memProps.memoryTypes(i).propertyFlags() & properties) == properties) {
                memProps.free();
                return i;
            }
        }
        memProps.free();
        throw new RuntimeException("Требуемый тип памяти не найден");
    }

    private static VkCommandBuffer beginSingleTimeCommands(MemoryStack stack) {
        VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandPool(SBoxVulkanCore.commandPool)
                .commandBufferCount(1);
        PointerBuffer pCmd = stack.mallocPointer(1);
        vkAllocateCommandBuffers(SBoxVulkanCore.logicalDevice, allocInfo, pCmd);
        VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), SBoxVulkanCore.logicalDevice);
        VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        vkBeginCommandBuffer(cmd, beginInfo);
        return cmd;
    }

    private static void endSingleTimeCommands(VkCommandBuffer cmd) {
        vkEndCommandBuffer(cmd);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(cmd));

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            LongBuffer pFence = stack.mallocLong(1);
            vkCreateFence(SBoxVulkanCore.logicalDevice, fenceInfo, null, pFence);
            long fence = pFence.get(0);

            vkQueueSubmit(SBoxVulkanCore.graphicsQueue, submitInfo, fence);
            vkWaitForFences(SBoxVulkanCore.logicalDevice, stack.longs(fence), true, -1);
            vkDestroyFence(SBoxVulkanCore.logicalDevice, fence, null);

            vkFreeCommandBuffers(SBoxVulkanCore.logicalDevice, SBoxVulkanCore.commandPool, cmd);
        }
    }

    private static long compileShader(String glslCode, int shaderType, String name) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long result = Shaderc.shaderc_compile_into_spv(compiler, glslCode, shaderType, name, "main", 0);
        if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
            throw new RuntimeException("ОШИБКА UI ШЕЙДЕРА:\n" + Shaderc.shaderc_result_get_error_message(result));
        }
        ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spirv);
            LongBuffer pModule = stack.mallocLong(1);
            vkCreateShaderModule(SBoxVulkanCore.logicalDevice, createInfo, null, pModule);
            Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compiler_release(compiler);
            return pModule.get(0);
        }
    }
}