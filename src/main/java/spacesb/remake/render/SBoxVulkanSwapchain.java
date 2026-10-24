package spacesb.remake.render;

import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public class SBoxVulkanSwapchain {
    public static long surface = VK_NULL_HANDLE;
    public static long swapchain = VK_NULL_HANDLE;
    public static long[] swapchainImages;
    public static long[] swapchainImageViews;
    public static int imageCount;

    public static long imageAvailableSemaphore = VK_NULL_HANDLE;
    public static long renderFinishedSemaphore = VK_NULL_HANDLE;

    public static void destroySwapchain() {
        VkDevice device = SBoxVulkanCore.logicalDevice;
        if (device == null) return;

        for (long view : swapchainImageViews) {
            if (view != VK_NULL_HANDLE) vkDestroyImageView(device, view, null);
        }
        if (swapchain != VK_NULL_HANDLE) {
            vkDestroySwapchainKHR(device, swapchain, null);
            swapchain = VK_NULL_HANDLE;
        }
        if (surface != VK_NULL_HANDLE) {
            vkDestroySurfaceKHR(SBoxVulkanCore.instance, surface, null);
            surface = VK_NULL_HANDLE;
        }
    }

    public static void initSwapchain(long windowHandle, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkInstance instance = SBoxVulkanCore.instance;
            VkDevice device = SBoxVulkanCore.logicalDevice;
            VkPhysicalDevice physicalDevice = SBoxVulkanCore.physicalDevice;

            long hwnd = GLFWNativeWin32.glfwGetWin32Window(windowHandle);
            long hinstance = org.lwjgl.system.windows.User32.GetWindowLongPtr(hwnd, -6);

            if (hinstance == 0L) {
                hinstance = 1L;
            }

            VkWin32SurfaceCreateInfoKHR surfaceInfo = VkWin32SurfaceCreateInfoKHR.calloc(stack)
                    .sType(KHRWin32Surface.VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR)
                    .hinstance(hinstance)
                    .hwnd(hwnd);

            LongBuffer pSurface = stack.mallocLong(1);
            if (KHRWin32Surface.vkCreateWin32SurfaceKHR(instance, surfaceInfo, null, pSurface) != VK_SUCCESS) {
                throw new RuntimeException("Не удалось захватить Win32 Vulkan Surface!");
            }
            surface = pSurface.get(0);

            VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.calloc(stack);
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, capabilities);

            int minImageCount = capabilities.minImageCount() + 1;
            if (capabilities.maxImageCount() > 0 && minImageCount > capabilities.maxImageCount()) {
                minImageCount = capabilities.maxImageCount();
            }

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                    .surface(surface)
                    .minImageCount(minImageCount)
                    .imageFormat(VK_FORMAT_B8G8R8A8_UNORM)
                    .imageColorSpace(VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
                    .imageExtent(it -> it.width(width).height(height))
                    .imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .preTransform(capabilities.currentTransform())
                    .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .presentMode(VK_PRESENT_MODE_FIFO_KHR)
                    .clipped(true)
                    .oldSwapchain(VK_NULL_HANDLE);

            LongBuffer pSwapchain = stack.mallocLong(1);
            if (vkCreateSwapchainKHR(device, createInfo, null, pSwapchain) != VK_SUCCESS) {
                throw new RuntimeException("Не удалось создать Swapchain!");
            }
            swapchain = pSwapchain.get(0);

            IntBuffer pImageCount = stack.mallocInt(1);
            vkGetSwapchainImagesKHR(device, swapchain, pImageCount, null);
            imageCount = pImageCount.get(0);
            LongBuffer pSwapchainImages = stack.mallocLong(imageCount);
            vkGetSwapchainImagesKHR(device, swapchain, pImageCount, pSwapchainImages);

            swapchainImages = new long[imageCount];
            swapchainImageViews = new long[imageCount];

            for (int i = 0; i < imageCount; i++) {
                swapchainImages[i] = pSwapchainImages.get(i);

                VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                        .image(swapchainImages[i])
                        .viewType(VK_IMAGE_VIEW_TYPE_2D)
                        .format(VK_FORMAT_B8G8R8A8_UNORM)
                        .subresourceRange(it -> it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));

                LongBuffer pView = stack.mallocLong(1);
                vkCreateImageView(device, viewInfo, null, pView);
                swapchainImageViews[i] = pView.get(0);
            }

            if (imageAvailableSemaphore == VK_NULL_HANDLE) {
                VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
                LongBuffer pSemaphore = stack.mallocLong(1);
                vkCreateSemaphore(device, semaphoreInfo, null, pSemaphore);
                imageAvailableSemaphore = pSemaphore.get(0);
                vkCreateSemaphore(device, semaphoreInfo, null, pSemaphore);
                renderFinishedSemaphore = pSemaphore.get(0);
            }
        }
    }
}