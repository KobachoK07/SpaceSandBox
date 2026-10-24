package spacesb.remake.render;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
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

    public static long defaultDescriptorSet = VK_NULL_HANDLE;

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
    private static final List<DrawCall> drawCalls = new ArrayList<>();
    private static DrawCall currentCall = null;

    public static void init() {
        System.out.println("[SBox UI] Инициализация Vulkan Batcher'а (Надежный Vertex Attributes Mode)...");

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice device = SBoxVulkanCore.logicalDevice;

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(200);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO).pPoolSizes(poolSizes).maxSets(200);
            LongBuffer pDescriptorPool = stack.mallocLong(1);
            vkCreateDescriptorPool(device, poolInfo, null, pDescriptorPool);
            descriptorPool = pDescriptorPool.get(0);

            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0.0f).maxLod(0.0f)
                    .unnormalizedCoordinates(false);
            LongBuffer pSampler = stack.mallocLong(1);
            vkCreateSampler(device, samplerInfo, null, pSampler);
            sampler = pSampler.get(0);

            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size((long) MAX_VERTICES * VERTEX_STRIDE)
                    .usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            vkCreateBuffer(device, bufferInfo, null, pBuffer);
            vertexBuffer = pBuffer.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, vertexBuffer, memReqs);
            int memoryTypeIndex = getMemoryType(memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO).allocationSize(memReqs.size()).memoryTypeIndex(memoryTypeIndex);
            LongBuffer pMemory = stack.mallocLong(1);
            vkAllocateMemory(device, allocInfo, null, pMemory);
            vertexMemory = pMemory.get(0);
            vkBindBufferMemory(device, vertexBuffer, vertexMemory, 0);

            PointerBuffer pData = stack.mallocPointer(1);
            vkMapMemory(device, vertexMemory, 0, (long) MAX_VERTICES * VERTEX_STRIDE, 0, pData);
            mappedBuffer = pData.getByteBuffer(0, MAX_VERTICES * VERTEX_STRIDE).order(java.nio.ByteOrder.nativeOrder());

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
                    "    gl_Position = pc.proj * vec4(inPos, 0.0, 1.0);\n" +
                    "    fragTexCoord = inTexCoord;\n" +
                    "    fragColor = inColor;\n" +
                    "    fragMode = inMode;\n" +
                    "}\n";

            String fragCode = "#version 450\n" +
                    "layout(location = 0) in vec2 fragTexCoord;\n" +
                    "layout(location = 1) in vec4 fragColor;\n" +
                    "layout(location = 2) in float fragMode;\n" +
                    "layout(binding = 0) uniform sampler2D texSampler;\n" +
                    "layout(location = 0) out vec4 outColor;\n" +
                    "void main() {\n" +
                    "    vec4 tex = texture(texSampler, fragTexCoord);\n" +
                    "    if (fragMode < 0.5) {\n" +
                    "        outColor = fragColor;\n" +
                    "    } else if (fragMode < 1.5) {\n" +
                    "        outColor = tex * fragColor;\n" +
                    "    } else {\n" +
                    "        outColor = vec4(fragColor.rgb, tex.r * fragColor.a);\n" +
                    "    }\n" +
                    "}\n";

            long vertModule = compileShader(vertCode, Shaderc.shaderc_glsl_vertex_shader, "ui.vert");
            long fragModule = compileShader(fragCode, Shaderc.shaderc_glsl_fragment_shader, "ui.frag");

            VkVertexInputBindingDescription.Buffer bindingDescription = VkVertexInputBindingDescription.calloc(1, stack).binding(0).stride(VERTEX_STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

            VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription.calloc(4, stack);
            attributeDescriptions.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
            attributeDescriptions.get(1).binding(0).location(1).format(VK_FORMAT_R32G32_SFLOAT).offset(8);
            attributeDescriptions.get(2).binding(0).location(2).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(16);
            attributeDescriptions.get(3).binding(0).location(3).format(VK_FORMAT_R32_SFLOAT).offset(32);

            VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO).pVertexBindingDescriptions(bindingDescription).pVertexAttributeDescriptions(attributeDescriptions);
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).primitiveRestartEnable(false);
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1);
            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO).depthClampEnable(false).rasterizerDiscardEnable(false).polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1.0f).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_CLOCKWISE).depthBiasEnable(false);
            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO).sampleShadingEnable(false).rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(true).srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK_BLEND_OP_ADD).srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO).alphaBlendOp(VK_BLEND_OP_ADD);
            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).logicOpEnable(false).pAttachments(colorBlendAttachment);

            IntBuffer dynamicStates = stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR);
            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(dynamicStates);

            VkDescriptorSetLayoutBinding.Buffer uboLayoutBinding = VkDescriptorSetLayoutBinding.calloc(1, stack).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(uboLayoutBinding);
            LongBuffer pDescriptorSetLayout = stack.mallocLong(1);
            vkCreateDescriptorSetLayout(device, layoutInfo, null, pDescriptorSetLayout);
            descriptorSetLayout = pDescriptorSetLayout.get(0);

            VkPushConstantRange.Buffer pushConstant = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT)
                    .offset(0)
                    .size(64);

            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO).pSetLayouts(stack.longs(descriptorSetLayout)).pPushConstantRanges(pushConstant);
            LongBuffer pPipelineLayout = stack.mallocLong(1);
            vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pPipelineLayout);
            pipelineLayout = pPipelineLayout.get(0);

            VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            shaderStages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(stack.UTF8("main"));
            shaderStages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(stack.UTF8("main"));

            VkPipelineRenderingCreateInfo pipelineRenderingInfo = VkPipelineRenderingCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO).pColorAttachmentFormats(stack.ints(VK_FORMAT_B8G8R8A8_UNORM));

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO).pNext(pipelineRenderingInfo.address())
                    .pStages(shaderStages).pVertexInputState(vertexInputInfo).pInputAssemblyState(inputAssembly).pViewportState(viewportState)
                    .pRasterizationState(rasterizer).pMultisampleState(multisampling).pColorBlendState(colorBlending).pDynamicState(dynamicState).layout(pipelineLayout);

            LongBuffer pGraphicsPipeline = stack.mallocLong(1);
            vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pGraphicsPipeline);
            graphicsPipeline = pGraphicsPipeline.get(0);

            vkDestroyShaderModule(device, vertModule, null); vkDestroyShaderModule(device, fragModule, null);

            ByteBuffer whitePixel = MemoryUtil.memAlloc(4);
            whitePixel.putInt(0xFFFFFFFF).flip();
            defaultDescriptorSet = uploadTexture(whitePixel, 1, 1, VK_FORMAT_R8G8B8A8_UNORM);
            MemoryUtil.memFree(whitePixel);
        }
    }

    public static long uploadTexture(ByteBuffer pixels, int width, int height, int format) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice device = SBoxVulkanCore.logicalDevice;
            long size = (long) pixels.capacity();

            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO).size(size).usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            LongBuffer pStagingBuffer = stack.mallocLong(1);
            vkCreateBuffer(device, bufferInfo, null, pStagingBuffer);
            long stagingBuffer = pStagingBuffer.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, stagingBuffer, memReqs);
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO).allocationSize(memReqs.size()).memoryTypeIndex(getMemoryType(memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));

            LongBuffer pStagingMemory = stack.mallocLong(1);
            vkAllocateMemory(device, allocInfo, null, pStagingMemory);
            long stagingMemory = pStagingMemory.get(0);
            vkBindBufferMemory(device, stagingBuffer, stagingMemory, 0);

            PointerBuffer data = stack.mallocPointer(1);
            vkMapMemory(device, stagingMemory, 0, size, 0, data);
            MemoryUtil.memCopy(MemoryUtil.memAddress(pixels), data.get(0), size);
            vkUnmapMemory(device, stagingMemory);

            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D).extent(it -> it.width(width).height(height).depth(1)).mipLevels(1).arrayLayers(1)
                    .format(format).tiling(VK_IMAGE_TILING_OPTIMAL).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT).samples(VK_SAMPLE_COUNT_1_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pImage = stack.mallocLong(1);
            vkCreateImage(device, imageInfo, null, pImage);
            long image = pImage.get(0);

            vkGetImageMemoryRequirements(device, image, memReqs);
            allocInfo.allocationSize(memReqs.size()).memoryTypeIndex(getMemoryType(memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            LongBuffer pImageMemory = stack.mallocLong(1);
            vkAllocateMemory(device, allocInfo, null, pImageMemory);
            long imageMemory = pImageMemory.get(0);
            vkBindImageMemory(device, image, imageMemory, 0);

            VkCommandBuffer cmd = beginSingleTimeCommands(stack);

            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image).subresourceRange(it -> it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1))
                    .srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
                    .imageSubresource(it -> it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1))
                    .imageOffset(it -> it.x(0).y(0).z(0)).imageExtent(it -> it.width(width).height(height).depth(1));
            vkCmdCopyBufferToImage(cmd, stagingBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

            barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, null, null, barrier);

            endSingleTimeCommands(cmd);
            vkDestroyBuffer(device, stagingBuffer, null); vkFreeMemory(device, stagingMemory, null);

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

    // Отрисовка произвольно повернутого 4-угольника (для 3D линий орбиты и наклонного текста на линии)
    public static void drawQuadVertices(
            float x0, float y0, float u0, float v0,
            float x1, float y1, float u1, float v1,
            float x2, float y2, float u2, float v2,
            float x3, float y3, float u3, float v3,
            float r, float g, float b, float a, int mode, long descSet) {

        if (vertexCount + 6 > MAX_VERTICES) return;

        long actualSet = (descSet == -1 || mode == 0) ? defaultDescriptorSet : descSet;
        float floatMode = (float) mode;

        if (currentCall == null || currentCall.descriptorSet != actualSet || currentCall.mode != floatMode) {
            currentCall = new DrawCall();
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
        float dx = x2 - x1;
        float dy = y2 - y1;
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
            VkMappedMemoryRange.Buffer flushRange = VkMappedMemoryRange.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE)
                    .memory(vertexMemory)
                    .offset(0)
                    .size(VK_WHOLE_SIZE);
            vkFlushMappedMemoryRanges(SBoxVulkanCore.logicalDevice, flushRange);

            VkRenderingAttachmentInfo.Buffer colorAttachment = VkRenderingAttachmentInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO).imageView(targetImageView).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_RENDERING_INFO).renderArea(a -> a.offset(o -> o.set(0, 0)).extent(e -> e.width(physW).height(physH))).layerCount(1).pColorAttachments(colorAttachment);

            vkCmdBeginRendering(cmd, renderingInfo);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline);

            VkViewport.Buffer viewport = VkViewport.calloc(1, stack).x(0).y(0).width((float)physW).height((float)physH).minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(cmd, 0, viewport);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack); scissor.offset(it -> it.x(0).y(0)).extent(it -> it.width(physW).height(physH));
            vkCmdSetScissor(cmd, 0, scissor);

            java.nio.FloatBuffer pushData = stack.callocFloat(16);
            pushData.put(0, 2.0f / logicalW);
            pushData.put(5, 2.0f / logicalH);
            pushData.put(10, -1.0f);
            pushData.put(12, -1.0f);
            pushData.put(13, -1.0f);
            pushData.put(15, 1.0f);

            vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, pushData);
            vkCmdBindVertexBuffers(cmd, 0, stack.longs(vertexBuffer), stack.longs(0));

            for (DrawCall call : drawCalls) {
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(call.descriptorSet), null);
                vkCmdDraw(cmd, call.vertexCount, 1, call.vertexStart, 0);
            }

            vkCmdEndRendering(cmd);
        }
        drawCalls.clear(); currentCall = null; vertexCount = 0;
    }

    private static int getMemoryType(int typeBits, int properties) {
        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.calloc();
        vkGetPhysicalDeviceMemoryProperties(SBoxVulkanCore.physicalDevice, memProps);
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0 && (memProps.memoryTypes(i).propertyFlags() & properties) == properties) {
                memProps.free(); return i;
            }
        }
        memProps.free(); throw new RuntimeException("Требуемый тип памяти не найден");
    }

    private static VkCommandBuffer beginSingleTimeCommands(MemoryStack stack) {
        VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandPool(SBoxVulkanCore.commandPool).commandBufferCount(1);
        PointerBuffer pCmd = stack.mallocPointer(1);
        vkAllocateCommandBuffers(SBoxVulkanCore.logicalDevice, allocInfo, pCmd);
        VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), SBoxVulkanCore.logicalDevice);
        VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO).flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        vkBeginCommandBuffer(cmd, beginInfo); return cmd;
    }

    private static void endSingleTimeCommands(VkCommandBuffer cmd) {
        vkEndCommandBuffer(cmd);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SUBMIT_INFO).pCommandBuffers(stack.pointers(cmd));

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
        if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) throw new RuntimeException("ОШИБКА UI ШЕЙДЕРА:\n" + Shaderc.shaderc_result_get_error_message(result));
        ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO).pCode(spirv);
            LongBuffer pModule = stack.mallocLong(1);
            vkCreateShaderModule(SBoxVulkanCore.logicalDevice, createInfo, null, pModule);
            Shaderc.shaderc_result_release(result); Shaderc.shaderc_compiler_release(compiler); return pModule.get(0);
        }
    }
}