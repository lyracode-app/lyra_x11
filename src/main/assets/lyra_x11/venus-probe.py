"""Bounded, headless Vulkan smoke test for the guest Mesa Venus ICD."""

import ctypes as c
from pathlib import Path
import re
import sys


U32 = c.c_uint32
U64 = c.c_uint64
Ptr = c.c_void_p
Header = [("type", U32), ("next", Ptr)]  # sType and pNext
VK_DRIVER_ID_MESA_VENUS = 22
TWO_SECONDS_NS = 2_000_000_000
BLOB_SIZE = 64 * 1024
HOST_VISIBLE_COHERENT = 0x2 | 0x4


class ApplicationInfo(c.Structure):
    _fields_ = Header + [("name", c.c_char_p), ("version", U32),
                         ("engine", c.c_char_p), ("engine_version", U32), ("api_version", U32)]


class InstanceInfo(c.Structure):
    _fields_ = Header + [("flags", U32), ("application", c.POINTER(ApplicationInfo)),
                         ("layer_count", U32), ("layers", Ptr),
                         ("extension_count", U32), ("extensions", Ptr)]


class DeviceProperties(c.Structure):
    # Only read the prefix of VkPhysicalDeviceProperties. Reserve aligned space
    # for its remaining UUID, limits and sparse properties in the Vulkan 1.x ABI.
    _fields_ = [("api_version", U32), ("driver_version", U32), ("vendor", U32),
                ("device", U32), ("device_type", U32), ("name", c.c_char * 256),
                ("remainder", U64 * 128)]


class DriverProperties(c.Structure):
    _fields_ = Header + [("driver_id", U32), ("name", c.c_char * 256),
                         ("info", c.c_char * 256), ("conformance_version", c.c_uint8 * 4)]


class DeviceProperties2(c.Structure):
    _fields_ = Header + [("properties", DeviceProperties)]


class QueueFamily(c.Structure):
    _fields_ = [("flags", U32), ("count", U32), ("timestamp_bits", U32), ("granularity", U32 * 3)]


class QueueInfo(c.Structure):
    _fields_ = Header + [("flags", U32), ("family", U32), ("count", U32),
                         ("priorities", c.POINTER(c.c_float))]


class DeviceInfo(c.Structure):
    _fields_ = Header + [("flags", U32), ("queue_count", U32), ("queues", c.POINTER(QueueInfo)),
                         ("layer_count", U32), ("layers", Ptr), ("extension_count", U32),
                         ("extensions", Ptr), ("features", Ptr)]


class FenceInfo(c.Structure):
    _fields_ = Header + [("flags", U32)]


class SubmitInfo(c.Structure):
    _fields_ = Header + [("wait_count", U32), ("wait_semaphores", Ptr), ("wait_stages", Ptr),
                         ("command_count", U32), ("commands", Ptr),
                         ("signal_count", U32), ("signal_semaphores", Ptr)]


class BufferInfo(c.Structure):
    _fields_ = Header + [("flags", U32), ("size", U64), ("usage", U32), ("sharing_mode", U32),
                         ("family_count", U32), ("families", Ptr)]


class MemoryRequirements(c.Structure):
    _fields_ = [("size", U64), ("alignment", U64), ("type_bits", U32)]


class MemoryType(c.Structure):
    _fields_ = [("flags", U32), ("heap_index", U32)]


class MemoryHeap(c.Structure):
    _fields_ = [("size", U64), ("flags", U32)]


class MemoryProperties(c.Structure):
    _fields_ = [("type_count", U32), ("types", MemoryType * 32),
                ("heap_count", U32), ("heaps", MemoryHeap * 16)]


class MemoryInfo(c.Structure):
    _fields_ = Header + [("size", U64), ("type_index", U32)]


class CommandPoolInfo(c.Structure):
    _fields_ = Header + [("flags", U32), ("family", U32)]


class CommandBufferInfo(c.Structure):
    _fields_ = Header + [("pool", U64), ("level", U32), ("count", U32)]


class CommandBeginInfo(c.Structure):
    _fields_ = Header + [("flags", U32), ("inheritance", Ptr)]


class MemoryBarrier(c.Structure):
    _fields_ = Header + [("source_access", U32), ("destination_access", U32)]


def check(operation, result):
    if result:
        raise RuntimeError(f"{operation} returned VkResult {result}")


def probe_blob(bind, step, physical, device, queue, family, fence):
    result = c.c_int32
    buffer, memory, pool = U64(), U64(), U64()
    mapping, command = Ptr(), Ptr()

    step("allocate_memory")
    buffer_info = BufferInfo(12, None, 0, BLOB_SIZE, 0x2)  # TRANSFER_DST
    check("vkCreateBuffer", bind("vkCreateBuffer", result, Ptr, Ptr, Ptr, Ptr)(
        device, c.byref(buffer_info), None, c.byref(buffer)))
    requirements = MemoryRequirements()
    bind("vkGetBufferMemoryRequirements", None, Ptr, U64, Ptr)(device, buffer, c.byref(requirements))
    properties = MemoryProperties()
    bind("vkGetPhysicalDeviceMemoryProperties", None, Ptr, Ptr)(physical, c.byref(properties))
    type_index = next((i for i, memory_type in enumerate(properties.types[:properties.type_count])
                       if requirements.type_bits & (1 << i) and
                       memory_type.flags & HOST_VISIBLE_COHERENT == HOST_VISIBLE_COHERENT), None)
    if type_index is None:
        raise RuntimeError("GPU0 has no host-visible coherent memory type for the probe buffer")
    print(f"LYRA_VENUS_GUEST blob size={requirements.size} memory_type={type_index} "
          f"flags=0x{properties.types[type_index].flags:x}", flush=True)
    memory_info = MemoryInfo(5, None, requirements.size, type_index)
    check("vkAllocateMemory", bind("vkAllocateMemory", result, Ptr, Ptr, Ptr, Ptr)(
        device, c.byref(memory_info), None, c.byref(memory)))

    step("map_memory")
    check("vkBindBufferMemory", bind("vkBindBufferMemory", result, Ptr, U64, U64, U64)(
        device, buffer, memory, 0))
    check("vkMapMemory", bind("vkMapMemory", result, Ptr, U64, U64, U64, U32, Ptr)(
        device, memory, 0, BLOB_SIZE, 0, c.byref(mapping)))
    c.memset(mapping, 0, BLOB_SIZE)

    step("verify_memory")
    pool_info = CommandPoolInfo(39, None, 0, family)
    check("vkCreateCommandPool", bind("vkCreateCommandPool", result, Ptr, Ptr, Ptr, Ptr)(
        device, c.byref(pool_info), None, c.byref(pool)))
    command_info = CommandBufferInfo(40, None, pool.value, 0, 1)
    check("vkAllocateCommandBuffers", bind("vkAllocateCommandBuffers", result, Ptr, Ptr, Ptr)(
        device, c.byref(command_info), c.byref(command)))
    begin_info = CommandBeginInfo(42, None, 1)  # ONE_TIME_SUBMIT
    check("vkBeginCommandBuffer", bind("vkBeginCommandBuffer", result, Ptr, Ptr)(
        command, c.byref(begin_info)))
    pattern = 0xB10BDA7A
    bind("vkCmdFillBuffer", None, Ptr, U64, U64, U64, U32)(command, buffer, 0, BLOB_SIZE, pattern)
    barrier = MemoryBarrier(46, None, 0x1000, 0x2000)  # TRANSFER_WRITE to HOST_READ
    bind("vkCmdPipelineBarrier", None, Ptr, U32, U32, U32, U32, Ptr, U32, Ptr, U32, Ptr)(
        command, 0x1000, 0x4000, 0, 1, c.byref(barrier), 0, None, 0, None)  # TRANSFER to HOST
    check("vkEndCommandBuffer", bind("vkEndCommandBuffer", result, Ptr)(command))
    check("vkResetFences", bind("vkResetFences", result, Ptr, U32, Ptr)(device, 1, c.byref(fence)))
    submit = SubmitInfo(4, command_count=1, commands=c.cast(c.pointer(command), Ptr))
    check("vkQueueSubmit (blob)", bind("vkQueueSubmit", result, Ptr, U32, Ptr, U64)(
        queue, 1, c.byref(submit), fence))
    check("vkWaitForFences (blob)", bind("vkWaitForFences", result, Ptr, U32, Ptr, U32, U64)(
        device, 1, c.byref(fence), 1, TWO_SECONDS_NS))
    if any(word != pattern for word in (U32 * (BLOB_SIZE // 4)).from_address(mapping.value)):
        raise RuntimeError("GPU writes to the shared blob did not reach the guest mapping")
    step("blob_ok")

    bind("vkDestroyCommandPool", None, Ptr, U64, Ptr)(device, pool, None)
    bind("vkUnmapMemory", None, Ptr, U64)(device, memory)
    bind("vkDestroyBuffer", None, Ptr, U64, Ptr)(device, buffer, None)
    bind("vkFreeMemory", None, Ptr, U64, Ptr)(device, memory, None)


def probe(stage):
    def step(name):
        stage.write_text(f"venus_{name}\n", encoding="ascii")
        print(f"LYRA_VENUS_GUEST {name}", flush=True)

    def bind(name, result, *args):
        function = getattr(vk, name)
        function.restype, function.argtypes = result, args
        return function

    if c.sizeof(Ptr) != 8:
        raise RuntimeError("Venus startup probe requires a 64-bit guest")
    step("loader")
    vk = c.CDLL("libvulkan.so.1")
    instance, device, queue, fence = Ptr(), Ptr(), Ptr(), U64()
    result = c.c_int32
    step("instance")
    api_1_2 = (1 << 22) | (2 << 12)
    application = ApplicationInfo(0, None, b"Lyra Venus probe", 1, None, 0, api_1_2)
    info = InstanceInfo(1, None, 0, c.pointer(application), 0, None, 0, None)
    check("vkCreateInstance", bind("vkCreateInstance", result, Ptr, Ptr, Ptr)(c.byref(info), None, c.byref(instance)))

    step("enumerate")
    enumerate_devices = bind("vkEnumeratePhysicalDevices", result, Ptr, c.POINTER(U32), c.POINTER(Ptr))
    count = U32()
    check("vkEnumeratePhysicalDevices", enumerate_devices(instance, c.byref(count), None))
    if not 0 < count.value <= 64:
        raise RuntimeError(f"Unexpected Vulkan physical device count {count.value}")
    devices = (Ptr * count.value)()
    check("vkEnumeratePhysicalDevices", enumerate_devices(instance, c.byref(count), devices))
    physical = devices[0]

    step("driver_properties")
    driver = DriverProperties(1000196000)
    props = DeviceProperties2(1000059001, c.cast(c.pointer(driver), Ptr))
    bind("vkGetPhysicalDeviceProperties2", None, Ptr, Ptr)(physical, c.byref(props))
    gpu = props.properties
    name = gpu.name.decode(errors="replace")
    api = gpu.api_version
    driver_id = "DRIVER_ID_MESA_VENUS" if driver.driver_id == VK_DRIVER_ID_MESA_VENUS else driver.driver_id
    print(f"GPU0: {name} api={(api >> 22) & 127}.{(api >> 12) & 1023}.{api & 4095} "
          f"driverID={driver_id} deviceType={gpu.device_type}", flush=True)
    if (driver.driver_id != VK_DRIVER_ID_MESA_VENUS or not name or gpu.device_type == 4 or
            re.search(r"llvmpipe|lavapipe|swiftshader|softpipe|software rasterizer", name, re.IGNORECASE)):
        raise RuntimeError("GPU0 is not a hardware Mesa Venus device")

    step("device")
    get_queues = bind("vkGetPhysicalDeviceQueueFamilyProperties", None, Ptr, c.POINTER(U32), c.POINTER(QueueFamily))
    get_queues(physical, c.byref(count), None)
    families = (QueueFamily * count.value)()
    get_queues(physical, c.byref(count), families)
    family = next((i for i, properties in enumerate(families) if properties.count and properties.flags & 1), None)
    if family is None:
        raise RuntimeError("GPU0 has no graphics queue")
    priority = c.c_float(1.0)
    queue_info = QueueInfo(2, None, 0, family, 1, c.pointer(priority))
    device_info = DeviceInfo(3, None, 0, 1, c.pointer(queue_info), 0, None, 0, None, None)
    check("vkCreateDevice", bind("vkCreateDevice", result, Ptr, Ptr, Ptr, Ptr)(physical, c.byref(device_info), None, c.byref(device)))
    bind("vkGetDeviceQueue", None, Ptr, U32, U32, Ptr)(device, family, 0, c.byref(queue))

    step("create_fence")
    fence_info = FenceInfo(8)
    check("vkCreateFence", bind("vkCreateFence", result, Ptr, Ptr, Ptr, Ptr)(device, c.byref(fence_info), None, c.byref(fence)))
    step("submit")
    submit_info = SubmitInfo(4)
    check("vkQueueSubmit", bind("vkQueueSubmit", result, Ptr, U32, Ptr, U64)(queue, 1, c.byref(submit_info), fence))
    step("wait_fence")
    check("vkWaitForFences", bind("vkWaitForFences", result, Ptr, U32, Ptr, U32, U64)(
        device, 1, c.byref(fence), 1, TWO_SECONDS_NS))
    probe_blob(bind, step, physical, device, queue, family, fence)

    # Only clean up synchronously after completion. If a call above failed or
    # stalled, the isolated process and its host worker own the abandoned state.
    step("destroy_device")
    bind("vkDestroyFence", None, Ptr, U64, Ptr)(device, fence, None)
    bind("vkDestroyDevice", None, Ptr, Ptr)(device, None)
    bind("vkDestroyInstance", None, Ptr, Ptr)(instance, None)
    step("headless_ok")


if __name__ == "__main__":
    try:
        probe(Path(sys.argv[1]))
    except (OSError, RuntimeError) as error:
        sys.exit(f"LYRA_VENUS_GUEST {error}")
