package spacesb.remake.render;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK13.*;

public class SBoxVulkanCore {

    public static VkInstance instance;
    public static VkPhysicalDevice physicalDevice;
    public static VkDevice logicalDevice;
    public static VkQueue graphicsQueue;
    public static int graphicsQueueFamilyIndex = -1;

    public static long commandPool;
    public static VkCommandBuffer mainCommandBuffer;
    public static VkCommandBuffer copyCommandBuffer;

    public static boolean supportsFp64 = false;

    public static void init() {
        if (instance != null) return;
        System.out.println("[SBox Vulkan] Запуск ядра Vulkan 1.3 (Dynamic Rendering + FP64 Compute)...");

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("SpaceEngine DIY"))
                    .apiVersion(VK_MAKE_VERSION(1, 3, 0));

            PointerBuffer glfwExtensions = GLFWVulkan.glfwGetRequiredInstanceExtensions();
            PointerBuffer instanceExtensions = stack.mallocPointer(glfwExtensions.capacity() + 1);
            instanceExtensions.put(glfwExtensions);
            instanceExtensions.put(stack.UTF8("VK_KHR_get_physical_device_properties2"));
            instanceExtensions.flip();

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo)
                    .ppEnabledExtensionNames(instanceExtensions);

            PointerBuffer pInstance = stack.mallocPointer(1);
            if (vkCreateInstance(createInfo, null, pInstance) != VK_SUCCESS) {
                throw new RuntimeException("ОШИБКА: Не удалось создать VkInstance");
            }
            instance = new VkInstance(pInstance.get(0), createInfo);

            pickPhysicalDevice(stack);
            createLogicalDevice(stack);
            createCommandPool(stack);
        }
    }

    private static void pickPhysicalDevice(MemoryStack stack) {
        IntBuffer deviceCount = stack.mallocInt(1);
        vkEnumeratePhysicalDevices(instance, deviceCount, null);
        PointerBuffer pDevices = stack.mallocPointer(deviceCount.get(0));
        vkEnumeratePhysicalDevices(instance, deviceCount, pDevices);

        for (int i = 0; i < deviceCount.get(0); i++) {
            VkPhysicalDevice device = new VkPhysicalDevice(pDevices.get(i), instance);
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(device, props);

            if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
                physicalDevice = device;
                System.out.println("[SBox Vulkan] УСПЕХ! Захвачена дискретная видеокарта: " + props.deviceNameString());
                return;
            }
        }
        physicalDevice = new VkPhysicalDevice(pDevices.get(0), instance);
    }

    private static void createLogicalDevice(MemoryStack stack) {
        IntBuffer queueFamilyCount = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, queueFamilyCount, null);
        VkQueueFamilyProperties.Buffer queueFamilies = VkQueueFamilyProperties.calloc(queueFamilyCount.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, queueFamilyCount, queueFamilies);

        for (int i = 0; i < queueFamilies.capacity(); i++) {
            if ((queueFamilies.get(i).queueFlags() & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) != 0) {
                graphicsQueueFamilyIndex = i;
                break;
            }
        }

        // Проверяем поддержку 64-битной математики (double / dvec3 / dvec4) на GPU
        VkPhysicalDeviceFeatures supported = VkPhysicalDeviceFeatures.calloc(stack);
        vkGetPhysicalDeviceFeatures(physicalDevice, supported);
        supportsFp64 = supported.shaderFloat64();

        VkPhysicalDeviceDynamicRenderingFeatures dynamicRenderingFeatures = VkPhysicalDeviceDynamicRenderingFeatures.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES)
                .dynamicRendering(true);

        VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                .pNext(dynamicRenderingFeatures.address());

        if (supportsFp64) {
            features2.features().shaderFloat64(true);
            System.out.println("[SBox Vulkan] Аппаратная 64-битная физика (shaderFloat64) АКТИВИРОВАНА.");
        }

        VkDeviceQueueCreateInfo.Buffer queueCreateInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(graphicsQueueFamilyIndex)
                .pQueuePriorities(stack.floats(1.0f));

        PointerBuffer deviceExtensions = stack.mallocPointer(1);
        deviceExtensions.put(stack.UTF8("VK_KHR_swapchain")).flip();

        VkDeviceCreateInfo deviceCreateInfo = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pNext(features2.address())
                .pQueueCreateInfos(queueCreateInfo)
                .ppEnabledExtensionNames(deviceExtensions);

        PointerBuffer pDevice = stack.mallocPointer(1);
        vkCreateDevice(physicalDevice, deviceCreateInfo, null, pDevice);
        logicalDevice = new VkDevice(pDevice.get(0), physicalDevice, deviceCreateInfo);

        PointerBuffer pQueue = stack.mallocPointer(1);
        vkGetDeviceQueue(logicalDevice, graphicsQueueFamilyIndex, 0, pQueue);
        graphicsQueue = new VkQueue(pQueue.get(0), logicalDevice);
    }

    private static void createCommandPool(MemoryStack stack) {
        VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .queueFamilyIndex(graphicsQueueFamilyIndex)
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

        LongBuffer pCommandPool = stack.mallocLong(1);
        vkCreateCommandPool(logicalDevice, poolInfo, null, pCommandPool);
        commandPool = pCommandPool.get(0);

        VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(2);

        PointerBuffer pCommandBuffers = stack.mallocPointer(2);
        vkAllocateCommandBuffers(logicalDevice, allocInfo, pCommandBuffers);

        mainCommandBuffer = new VkCommandBuffer(pCommandBuffers.get(0), logicalDevice);
        copyCommandBuffer = new VkCommandBuffer(pCommandBuffers.get(1), logicalDevice);
    }
}