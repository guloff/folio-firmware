// Application descriptor of the Folio image. ESP-IDF defines esp_app_desc as a
// weak symbol filled in when the prebuilt Arduino libraries were compiled
// ("arduino-lib-builder"); this definition replaces it so the image names
// itself. The firmware-update confirmation screen reads project_name and
// version from here before installing an image.
#ifndef SIMULATOR

#include <esp_app_desc.h>
#include <esp_idf_version.h>
#include <sdkconfig.h>

#ifndef CROSSPOINT_VERSION
#define CROSSPOINT_VERSION "dev"
#endif

#define FOLIO_STR2(x) #x
#define FOLIO_STR(x) FOLIO_STR2(x)

namespace {
constexpr uint8_t log2u(uint32_t v) {
  uint8_t n = 0;
  while (v > 1) {
    v >>= 1;
    n++;
  }
  return n;
}
}  // namespace

extern "C" {
extern const esp_app_desc_t esp_app_desc;

__attribute__((section(".rodata_desc"), used)) const esp_app_desc_t esp_app_desc = {
    .magic_word = ESP_APP_DESC_MAGIC_WORD,
    .secure_version = 0,
    .reserv1 = {0, 0},
    .version = CROSSPOINT_VERSION,
    .project_name = "Folio",
    .time = __TIME__,
    .date = __DATE__,
    .idf_ver = "v" FOLIO_STR(ESP_IDF_VERSION_MAJOR) "." FOLIO_STR(ESP_IDF_VERSION_MINOR) "." FOLIO_STR(
        ESP_IDF_VERSION_PATCH),
    .app_elf_sha256 = {0},  // patched in by esptool elf2image
#ifdef CONFIG_ESP_EFUSE_BLOCK_REV_MIN_FULL
    .min_efuse_blk_rev_full = CONFIG_ESP_EFUSE_BLOCK_REV_MIN_FULL,
#else
    .min_efuse_blk_rev_full = 0,
#endif
#ifdef CONFIG_ESP_EFUSE_BLOCK_REV_MAX_FULL
    .max_efuse_blk_rev_full = CONFIG_ESP_EFUSE_BLOCK_REV_MAX_FULL,
#else
    .max_efuse_blk_rev_full = 199,
#endif
    .mmu_page_size = log2u(CONFIG_MMU_PAGE_SIZE),
    .reserv3 = {0, 0, 0},
    .reserv2 = {0},
};
}

#endif  // SIMULATOR
