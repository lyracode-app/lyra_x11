/* SPDX-License-Identifier: MIT */

static bool
vkr_android_is_linear_dma_buf_image(struct vkr_device *dev, const struct vkr_image *image,
                                    const VkImageCreateInfo *info)
{
   if (!dev->android_linear_image_types || info->imageType != VK_IMAGE_TYPE_2D ||
       info->mipLevels != 1 || info->arrayLayers != 1 || info->samples != VK_SAMPLE_COUNT_1_BIT ||
       (info->format != VK_FORMAT_B8G8R8A8_UNORM && info->format != VK_FORMAT_B8G8R8A8_SRGB))
      return false;

   const VkExternalMemoryImageCreateInfo *external =
      vkr_find_struct(info->pNext, VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO);
   if (!external || external->handleTypes != VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT)
      return false;

   if (info->tiling == VK_IMAGE_TILING_LINEAR)
      return true;
   if (info->tiling != VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT ||
       !dev->proc_table.GetImageDrmFormatModifierPropertiesEXT)
      return false;

   VkImageDrmFormatModifierPropertiesEXT props = {
      .sType = VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_PROPERTIES_EXT,
   };
   return dev->proc_table.GetImageDrmFormatModifierPropertiesEXT(
             dev->base.handle.device, image->base.handle.image, &props) == VK_SUCCESS &&
          props.drmFormatModifier == 0; /* DRM_FORMAT_MOD_LINEAR */
}

static void
vkr_android_filter_linear_image_types(const struct vkr_device *dev, const struct vkr_image *image,
                                      VkMemoryRequirements *requirements)
{
   if (!image->android_linear_dma_buf)
      return;

   const uint32_t original = requirements->memoryTypeBits;
   const uint32_t compatible = original & dev->android_linear_image_types;
   if (!compatible || compatible == original)
      return;

   requirements->memoryTypeBits = compatible;
   static atomic_bool logged;
   if (!atomic_exchange_explicit(&logged, true, memory_order_relaxed))
      vkr_log("LYRA_VENUS_LINEAR_IMAGE image_types=0x%x import_types=0x%x exposed_types=0x%x",
              original, dev->android_linear_image_types, compatible);
}
