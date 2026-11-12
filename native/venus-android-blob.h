/* SPDX-License-Identifier: MIT */

#include <android/hardware_buffer.h>
#include <fcntl.h>
#include <linux/dma-buf.h>
#include <linux/dma-heap.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <vulkan/vulkan_android.h>

static int
vkr_android_alloc_heap(const char *path, uint64_t size)
{
   int heap = open(path, O_RDONLY | O_CLOEXEC);
   if (heap < 0)
      return -1;

   struct dma_heap_allocation_data data = {
      .len = size,
      .fd_flags = O_RDWR | O_CLOEXEC,
      .heap_flags = 0,
   };
   int ret = ioctl(heap, DMA_HEAP_IOCTL_ALLOC, &data);
   int error = errno;
   close(heap);
   errno = error;
   return ret == 0 ? (int)data.fd : -1;
}

static VkResult
vkr_android_fd_properties(struct vkr_device *dev, int fd, uint32_t *types)
{
   VkMemoryFdPropertiesKHR properties = {
      .sType = VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR,
   };
   VkResult ret = dev->proc_table.GetMemoryFdPropertiesKHR(
      dev->base.handle.device, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, fd, &properties);
   *types = properties.memoryTypeBits;
   return ret;
}

static const char *const vkr_android_cached_heaps[] = {
   "/dev/dma_heap/system", "/dev/dma_heap/mtk_mm", NULL,
};

uint32_t
vkr_android_probe_linear_image_types(struct vkr_device *dev)
{
   const struct vkr_physical_device *physical = dev->physical_device;
   if (!dev->proc_table.GetMemoryFdPropertiesKHR || !physical->EXT_external_memory_dma_buf ||
       physical->is_dma_buf_fd_export_supported || physical->udmabuf_dev_fd >= 0)
      return 0;

   uint32_t cached_types = 0;
   for (uint32_t i = 0; i < physical->memory_properties.memoryTypeCount; i++) {
      const VkMemoryPropertyFlags flags = physical->memory_properties.memoryTypes[i].propertyFlags;
      const VkMemoryPropertyFlags required = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                             VK_MEMORY_PROPERTY_HOST_CACHED_BIT;
      if ((flags & required) == required)
         cached_types |= 1u << i;
   }

   for (size_t i = 0; cached_types && vkr_android_cached_heaps[i]; i++) {
      int fd = vkr_android_alloc_heap(vkr_android_cached_heaps[i], getpagesize());
      if (fd < 0)
         continue;

      uint32_t types;
      VkResult ret = vkr_android_fd_properties(dev, fd, &types);
      close(fd);
      if (ret == VK_SUCCESS && (types & cached_types))
         return types & cached_types;
   }
   return 0;
}

static bool
vkr_android_check_fd(struct vkr_device *dev, const VkMemoryAllocateInfo *alloc, int fd,
                     const char *source)
{
   off_t size = lseek(fd, 0, SEEK_END);
   if (size < 0 || (uint64_t)size < alloc->allocationSize) {
      vkr_log("LYRA_VENUS_HOST_BLOB source=%s fd_size=%" PRId64 " required=%" PRIu64,
              source, (int64_t)size, alloc->allocationSize);
      return false;
   }

   /* Exported AHBs back a Vulkan allocation that already has the requested type. */
   if (!dev)
      return true;

   uint32_t types;
   VkResult ret = vkr_android_fd_properties(dev, fd, &types);
   if (ret == VK_SUCCESS && (types & (1u << alloc->memoryTypeIndex)))
      return true;

   vkr_log("LYRA_VENUS_HOST_BLOB source=%s fd_properties_result=%d import_types=0x%x memory_type=%u",
           source, ret, types, alloc->memoryTypeIndex);
   return false;
}

static bool
vkr_android_is_dma_buf(int fd)
{
   /* A gralloc handle may also carry non-DMA-BUF metadata descriptors. */
   struct dma_buf_sync sync = { .flags = DMA_BUF_SYNC_READ | DMA_BUF_SYNC_START };
   if (ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync) < 0)
      return false;
   sync.flags = DMA_BUF_SYNC_READ | DMA_BUF_SYNC_END;
   return ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync) == 0;
}

static int
vkr_android_receive_ahb_fd(struct vkr_device *dev, const VkMemoryAllocateInfo *alloc, int socket)
{
   char payload[4096];
   struct iovec iov = { .iov_base = payload, .iov_len = sizeof(payload) };
   union {
      struct cmsghdr align;
      char bytes[CMSG_SPACE(16 * sizeof(int))];
   } control;
   struct msghdr msg = {
      .msg_iov = &iov,
      .msg_iovlen = 1,
      .msg_control = control.bytes,
      .msg_controllen = sizeof(control.bytes),
   };
   if (recvmsg(socket, &msg, MSG_CMSG_CLOEXEC) <= 0)
      return -1;

   int fd = -1;
   bool ambiguous = msg.msg_flags & (MSG_TRUNC | MSG_CTRUNC);
   for (struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg); cmsg; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
      if (cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS)
         continue;

      const size_t count = (cmsg->cmsg_len - CMSG_LEN(0)) / sizeof(int);
      const int *fds = (const int *)CMSG_DATA(cmsg);
      for (size_t i = 0; i < count; i++) {
         if (!ambiguous && vkr_android_is_dma_buf(fds[i]) &&
             vkr_android_check_fd(dev, alloc, fds[i], dev ? "ahb_import" : "ahb_export")) {
            if (fd < 0) {
               fd = fds[i];
               continue;
            }
            /* Gralloc may send metadata as well. Require one unambiguous backing. */
            ambiguous = true;
         }
         close(fds[i]);
      }
   }
   if (ambiguous && fd >= 0) {
      close(fd);
      return -1;
   }
   return fd;
}

static int
vkr_android_get_ahb_fd(AHardwareBuffer *buffer, struct vkr_device *dev,
                       const VkMemoryAllocateInfo *alloc)
{
   int sockets[2];
   if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, sockets) < 0) {
      vkr_log("LYRA_VENUS_HOST_BLOB ahb_stage=socket errno=%d", errno);
      return -1;
   }

   int fd = -1;
   int status = AHardwareBuffer_sendHandleToUnixSocket(buffer, sockets[0]);
   if (status == 0) {
      fd = vkr_android_receive_ahb_fd(dev, alloc, sockets[1]);
      if (fd < 0)
         vkr_log("LYRA_VENUS_HOST_BLOB ahb_stage=handle no_compatible_dma_buf=1 export=%d", !dev);
   } else {
      vkr_log("LYRA_VENUS_HOST_BLOB ahb_stage=send result=%d", status);
   }
   close(sockets[0]);
   close(sockets[1]);
   return fd;
}

static int
vkr_android_export_ahb_fd(struct vkr_device *dev, const VkMemoryAllocateInfo *alloc,
                          VkDeviceMemory memory)
{
   const VkDevice device = dev->base.handle.device;
   const PFN_vkGetMemoryAndroidHardwareBufferANDROID get_ahb =
      (PFN_vkGetMemoryAndroidHardwareBufferANDROID)dev->physical_device->proc_table.GetDeviceProcAddr(
         device, "vkGetMemoryAndroidHardwareBufferANDROID");
   if (!get_ahb)
      return -1;

   const VkMemoryGetAndroidHardwareBufferInfoANDROID info = {
      .sType = VK_STRUCTURE_TYPE_MEMORY_GET_ANDROID_HARDWARE_BUFFER_INFO_ANDROID,
      .memory = memory,
   };
   AHardwareBuffer *buffer = NULL;
   VkResult ret = get_ahb(device, &info, &buffer);
   if (ret != VK_SUCCESS || !buffer) {
      vkr_log("LYRA_VENUS_HOST_BLOB ahb_stage=export result=%d memory_type=%u",
              ret, alloc->memoryTypeIndex);
      return -1;
   }
   int fd = vkr_android_get_ahb_fd(buffer, NULL, alloc);
   AHardwareBuffer_release(buffer);
   if (fd >= 0) {
      static atomic_bool logged;
      if (!atomic_exchange_explicit(&logged, true, memory_order_relaxed))
         vkr_log("LYRA_VENUS_HOST_BLOB backend=ahb_export size=%" PRIu64 " memory_type=%u",
                 alloc->allocationSize, alloc->memoryTypeIndex);
   }
   return fd;
}

static int
vkr_android_alloc_ahb(struct vkr_device *dev, const VkMemoryAllocateInfo *alloc,
                      uint64_t size, bool cached)
{
   if (size > UINT32_MAX)
      return -1;

   /* CPU usage is a gralloc hint. The guest probe also checks mapped GPU writes. */
   const AHardwareBuffer_Desc desc = {
      .width = (uint32_t)size,
      .height = 1,
      .layers = 1,
      .format = AHARDWAREBUFFER_FORMAT_BLOB,
      .usage = AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER |
               (cached ? AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                            AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN
                       : AHARDWAREBUFFER_USAGE_CPU_READ_RARELY |
                            AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY),
   };
   AHardwareBuffer *buffer;
   int status = AHardwareBuffer_allocate(&desc, &buffer);
   if (status != 0) {
      vkr_log("LYRA_VENUS_HOST_BLOB ahb_stage=allocate result=%d", status);
      return -1;
   }

   int fd = vkr_android_get_ahb_fd(buffer, dev, alloc);
   AHardwareBuffer_release(buffer);
   return fd;
}

static VkResult
vkr_android_get_fd_info(struct vkr_device *dev, const VkMemoryAllocateInfo *alloc,
                        uint32_t flags, int *out_fd, VkImportMemoryFdInfoKHR *out_info)
{
   const uint64_t page = getpagesize();
   if (!alloc->allocationSize || alloc->allocationSize > INT64_MAX - (page - 1))
      return VK_ERROR_OUT_OF_DEVICE_MEMORY;
   if (!dev->proc_table.GetMemoryFdPropertiesKHR)
      return VK_ERROR_EXTENSION_NOT_PRESENT;

   const uint64_t size = (alloc->allocationSize + page - 1) & ~(page - 1);
   const bool cached = flags & VK_MEMORY_PROPERTY_HOST_CACHED_BIT;
   const char *const uncached_heaps[] = {
      "/dev/dma_heap/system-uncached", "/dev/dma_heap/mtk_mm-uncached",
      "/dev/dma_heap/system_uncached", NULL,
   };
   const char *const *heaps = cached ? vkr_android_cached_heaps : uncached_heaps;
   int fd = -1;
   int heap_error = 0;
   const char *heap_path = NULL;
   for (size_t i = 0; heaps[i]; i++) {
      fd = vkr_android_alloc_heap(heaps[i], size);
      if (fd < 0) {
         if (errno != ENOENT || !heap_error)
            heap_error = errno;
         if (errno != ENOENT)
            vkr_log("LYRA_VENUS_HOST_BLOB heap=%s allocation_errno=%d", heaps[i], errno);
         continue;
      }
      if (vkr_android_check_fd(dev, alloc, fd, heaps[i])) {
         heap_path = heaps[i];
         break;
      }
      close(fd);
      fd = -1;
   }
   if (fd < 0)
      fd = vkr_android_alloc_ahb(dev, alloc, size, cached);
   if (fd < 0) {
      vkr_log("LYRA_VENUS_HOST_BLOB backend=none size=%" PRIu64
              " memory_type=%u flags=0x%x heap_errno=%d",
              size, alloc->memoryTypeIndex, flags, heap_error);
      return VK_ERROR_OUT_OF_DEVICE_MEMORY;
   }

   int import_fd = os_dupfd_cloexec(fd);
   if (import_fd < 0) {
      close(fd);
      return VK_ERROR_OUT_OF_HOST_MEMORY;
   }
   *out_fd = fd;
   *out_info = (VkImportMemoryFdInfoKHR){
      .sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR,
      .pNext = alloc->pNext,
      .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
      .fd = import_fd,
   };

   static atomic_uint logged;
   const unsigned backend_bit = (heap_path ? 1u : 4u) << cached;
   if (!(atomic_fetch_or_explicit(&logged, backend_bit, memory_order_relaxed) & backend_bit))
      vkr_log("LYRA_VENUS_HOST_BLOB backend=%s heap=%s size=%" PRIu64
              " memory_type=%u flags=0x%x heap_errno=%d",
              heap_path ? "dma_heap" : "ahardwarebuffer", heap_path ? heap_path : "-",
              size, alloc->memoryTypeIndex, flags, heap_path ? 0 : heap_error);
   return VK_SUCCESS;
}
