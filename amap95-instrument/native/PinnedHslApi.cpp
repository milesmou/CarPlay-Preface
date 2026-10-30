#include "PinnedHslApi.h"

#include <cstring>
#include <dlfcn.h>
#include <elf.h>
#include <link.h>

namespace preface::amap95 {
namespace {

constexpr unsigned char kBuildId[] = {
    0x71, 0xb0, 0x53, 0x4a, 0xd0, 0xe6, 0x86, 0x35, 0xf4, 0x6c,
    0xd1, 0x9c, 0x66, 0x9e, 0x1e, 0xc6, 0x68, 0x6f, 0x63, 0xc7,
};

struct ImageCheck {
    const void* base;
    bool matches = false;
};

// Read mapped ELF notes, not vendor object memory. The identity is fixed to
// the reviewed public 9.5 binary; a filename or a version label is insufficient.
int inspectImage(dl_phdr_info* image, std::size_t, void* opaque) {
    auto& check = *static_cast<ImageCheck*>(opaque);
    if (reinterpret_cast<const void*>(image->dlpi_addr) != check.base) {
        return 0;
    }
    const auto* header = static_cast<const ElfW(Ehdr)*>(check.base);
    if (std::memcmp(header->e_ident, ELFMAG, SELFMAG) != 0 ||
        header->e_ident[EI_CLASS] != ELFCLASS64 ||
        header->e_ident[EI_DATA] != ELFDATA2LSB || header->e_machine != EM_AARCH64) {
        return 1;
    }
    for (std::size_t i = 0; i < image->dlpi_phnum; ++i) {
        const auto& note = image->dlpi_phdr[i];
        if (note.p_type != PT_NOTE || note.p_filesz < sizeof(ElfW(Nhdr))) {
            continue;
        }
        // A note must also be inside one readable mapped LOAD segment.
        bool readable = false;
        for (std::size_t j = 0; j < image->dlpi_phnum; ++j) {
            const auto& load = image->dlpi_phdr[j];
            if (load.p_type == PT_LOAD && (load.p_flags & PF_R) &&
                note.p_vaddr >= load.p_vaddr && note.p_filesz <= load.p_memsz &&
                note.p_vaddr - load.p_vaddr <= load.p_memsz - note.p_filesz) {
                readable = true;
                break;
            }
        }
        if (!readable) {
            continue;
        }
        const auto* bytes = reinterpret_cast<const unsigned char*>(
            image->dlpi_addr + note.p_vaddr);
        std::size_t offset = 0;
        while (note.p_filesz - offset >= sizeof(ElfW(Nhdr))) {
            ElfW(Nhdr) entry{};
            std::memcpy(&entry, bytes + offset, sizeof(entry));
            offset += sizeof(entry);
            const std::uint64_t nameBytes = (std::uint64_t(entry.n_namesz) + 3) & ~std::uint64_t(3);
            const std::uint64_t valueBytes = (std::uint64_t(entry.n_descsz) + 3) & ~std::uint64_t(3);
            if (nameBytes > note.p_filesz - offset ||
                valueBytes > note.p_filesz - offset - nameBytes) {
                break;
            }
            if (entry.n_type == NT_GNU_BUILD_ID && entry.n_namesz == 4 &&
                entry.n_descsz == sizeof(kBuildId) &&
                std::memcmp(bytes + offset, "GNU\0", 4) == 0 &&
                std::memcmp(bytes + offset + nameBytes, kBuildId, sizeof(kBuildId)) == 0) {
                check.matches = true;
                return 1;
            }
            offset += static_cast<std::size_t>(nameBytes + valueBytes);
        }
    }
    return 1;
}

template <class T>
bool resolve(void* module, const char* name, const void* base, T& output) noexcept {
    void* symbol = dlsym(module, name);
    Dl_info info{};
    if (symbol == nullptr || dladdr(symbol, &info) == 0 || info.dli_fbase != base) {
        return false;
    }
    static_assert(sizeof(output) == sizeof(symbol));
    std::memcpy(&output, &symbol, sizeof(output));
    return true;
}

}  // namespace

const char* observerResultName(ObserverResult result) noexcept {
    switch (result) {
        case ObserverResult::LibraryBound: return "HSL_EXPORTS_BOUND";
        case ObserverResult::MapConfirmed: return "EXISTING_MAP_IDENTITY_CONFIRMED";
        case ObserverResult::Attached: return "OBSERVER_ATTACHED";
        case ObserverResult::Closed: return "OBSERVER_CLOSED";
        case ObserverResult::InvalidArgument: return "INVALID_ARGUMENT";
        case ObserverResult::LibraryNotLoaded: return "HSL_NOT_LOADED";
        case ObserverResult::LibraryMismatch: return "HSL_IDENTITY_MISMATCH";
        case ObserverResult::MissingSymbol: return "HSL_SYMBOL_MISSING_OR_FOREIGN";
        case ObserverResult::MapUnavailable: return "EXISTING_MAP_UNAVAILABLE";
        case ObserverResult::MapIdentityMismatch: return "MAP_WINDOW_IDENTITY_MISMATCH";
        case ObserverResult::AllocationFailed: return "OBSERVER_ALLOCATION_FAILED";
        case ObserverResult::CallbackMustDefer: return "SDK_CALLBACK_MUST_DEFER_REMOVAL";
    }
    return "UNKNOWN_OBSERVER_RESULT";
}

PinnedHslApi::~PinnedHslApi() {
    if (module_ != nullptr) {
        dlclose(module_);
    }
}

ObserverResult PinnedHslApi::bindAlreadyLoaded() noexcept {
    if (module_ != nullptr) {
        return ObserverResult::InvalidArgument;
    }
    void* candidate = dlopen("libhsl.so", RTLD_NOW | RTLD_NOLOAD);
    if (candidate == nullptr) {
        return ObserverResult::LibraryNotLoaded;
    }
    void* anchor = dlsym(candidate, "_ZN3hsl3HSL19GetMapServiceHandleEv");
    Dl_info info{};
    if (anchor == nullptr || dladdr(anchor, &info) == 0) {
        dlclose(candidate);
        return ObserverResult::MissingSymbol;
    }
    ImageCheck check{info.dli_fbase};
    dl_iterate_phdr(inspectImage, &check);
    if (!check.matches) {
        dlclose(candidate);
        return ObserverResult::LibraryMismatch;
    }
    HslFunctions found{};
    const bool complete =
        resolve(candidate, "_ZN3hsl3HSL19GetMapServiceHandleEv", check.base, found.mapService) &&
        resolve(candidate, "_ZN3hsl3HSL16GetMapViewHandleEN2bl11MapEngineIDE", check.base, found.mapView) &&
        resolve(candidate, "_ZN3hsl15MapViewBaseImpl11GetEngineIdEv", check.base, found.engineId) &&
        resolve(candidate, "_ZN3hsl15MapViewBaseImpl11GetDeviceIdEv", check.base, found.deviceId) &&
        resolve(candidate, "_ZN3hsl18MultiScreenService11GetWindowIdEN2bl11MapEngineIDE", check.base, found.windowId) &&
        resolve(candidate, "_ZN3hsl18MapViewServiceImpl17SetDeviceObserverEPNS_19IMapServiceObserverE", check.base, found.addObserver) &&
        resolve(candidate, "_ZN3hsl18MapViewServiceImpl20RemoveDeviceObserverEPNS_19IMapServiceObserverE", check.base, found.removeObserver) &&
        resolve(candidate, "_ZN3hsl18MultiScreenService10s_instanceE", check.base, found.multiScreenInstance);
    if (!complete) {
        dlclose(candidate);
        return ObserverResult::MissingSymbol;
    }
    functions_ = found;
    module_ = candidate;
    return ObserverResult::LibraryBound;
}

ObserverResult PinnedHslApi::inspectExistingMap(int engineId, MapIdentity& result) const noexcept {
    result = {};
    if (engineId <= 1) {
        return ObserverResult::InvalidArgument;
    }
    if (functions_.mapService == nullptr || functions_.mapView == nullptr ||
        functions_.engineId == nullptr || functions_.deviceId == nullptr ||
        functions_.windowId == nullptr || functions_.multiScreenInstance == nullptr) {
        return ObserverResult::LibraryNotLoaded;
    }
    void* service = functions_.mapService();
    void* view = functions_.mapView(engineId);
    void* multi = *functions_.multiScreenInstance;
    if (service == nullptr || view == nullptr || multi == nullptr) {
        return ObserverResult::MapUnavailable;
    }
    const int actualEngine = functions_.engineId(view);
    const int device = functions_.deviceId(view);
    const int window = functions_.windowId(multi, engineId);
    if (actualEngine != engineId || device <= 0 || window != 1001) {
        return ObserverResult::MapIdentityMismatch;
    }
    result = {service, view, engineId, device, window};
    return ObserverResult::MapConfirmed;
}

void PinnedHslApi::addObserver(void* service, void* observer) const noexcept {
    functions_.addObserver(service, observer);
}

void PinnedHslApi::removeObserver(void* service, void* observer) const noexcept {
    functions_.removeObserver(service, observer);
}

}  // namespace preface::amap95
