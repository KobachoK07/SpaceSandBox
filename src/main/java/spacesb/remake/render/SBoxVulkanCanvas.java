package spacesb.remake.render;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

public class SBoxVulkanCanvas {
    public static long canvasImage = VK_NULL_HANDLE;
    public static long canvasMemory = VK_NULL_HANDLE;
    public static long canvasMemorySize;
    public static long canvasImageView = VK_NULL_HANDLE;

    public static int canvasWidth;
    public static int canvasHeight;

    public static void destroyCanvas() {
        VkDevice device = SBoxVulkanCore.logicalDevice;
        if (device == null) return;

        if (canvasImageView != VK_NULL_HANDLE) {
            vkDestroyImageView(device, canvasImageView, null);
            canvasImageView = VK_NULL_HANDLE;
        }
        if (canvasImage != VK_NULL_HANDLE) {
            vkDestroyImage(device, canvasImage, null);
            canvasImage = VK_NULL_HANDLE;
        }
        if (canvasMemory != VK_NULL_HANDLE) {
            vkFreeMemory(device, canvasMemory, null);
            canvasMemory = VK_NULL_HANDLE;
        }
    }

    public static void createCanvas(int width, int height) {
        canvasWidth = width;
        canvasHeight = height;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice device = SBoxVulkanCore.logicalDevice;

            // Чистое создание картинки. Без VK_EXTERNAL_MEMORY!
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .extent(it -> it.width(width).height(height).depth(1))
                    .mipLevels(1).arrayLayers(1)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    // ДОБАВЛЕН TRANSFER_SRC_BIT для копирования на экран
                    .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pImage = stack.mallocLong(1);
            vkCreateImage(device, imageInfo, null, pImage);
            canvasImage = pImage.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, canvasImage, memReqs);
            canvasMemorySize = memReqs.size();

            VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(SBoxVulkanCore.physicalDevice, memProps);
            int memType = -1;
            for (int i = 0; i < memProps.memoryTypeCount(); i++) {
                if ((memReqs.memoryTypeBits() & (1 << i)) != 0 && (memProps.memoryTypes(i).propertyFlags() & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) {
                    memType = i;
                    break;
                }
            }

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(canvasMemorySize)
                    .memoryTypeIndex(memType);

            LongBuffer pMem = stack.mallocLong(1);
            vkAllocateMemory(device, allocInfo, null, pMem);
            canvasMemory = pMem.get(0);
            vkBindImageMemory(device, canvasImage, canvasMemory, 0);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(canvasImage)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .subresourceRange(it -> it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));

            LongBuffer pView = stack.mallocLong(1);
            vkCreateImageView(device, viewInfo, null, pView);
            canvasImageView = pView.get(0);
        }
    }
}