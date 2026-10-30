// Standalone load/binding probe. Never link this into either application.
#include "PinnedHslApi.h"

#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <unistd.h>

namespace {

[[noreturn]] void finish(int code) {
    std::fflush(stdout);
    // This process has not run the vendor application's initialization or
    // shutdown. Do not run its global destructors as if either had occurred.
    // The OS releases this isolated process's mappings on exit. This does not
    // exercise vendor SDK teardown, and is not an application teardown fix.
    _exit(code);
}

bool belongsTo(void* symbol, void* anchor) {
    Dl_info symbolInfo{}, anchorInfo{};
    return symbol != nullptr && anchor != nullptr &&
        dladdr(symbol, &symbolInfo) != 0 && dladdr(anchor, &anchorInfo) != 0 &&
        symbolInfo.dli_fbase == anchorInfo.dli_fbase;
}

}  // namespace

int main(int argc, char** argv) {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    if (argc != 2 || (std::strcmp(argv[1], "hsl") != 0 &&
                      std::strcmp(argv[1], "tbt") != 0)) {
        std::puts("INVALID_PROBE_ARGUMENT");
        finish(1);
    }
    const bool tbt = std::strcmp(argv[1], "tbt") == 0;
    std::puts("VENDOR_LOAD_BEGIN");
    // The host script verifies the exact public APK and every uploaded ELF.
    // Loading vendor libraries is explicit and isolated to this diagnostic.
    void* module = dlopen(tbt ? "libautonavi.so" : "libhsl.so", RTLD_NOW | RTLD_GLOBAL);
    if (module == nullptr) {
        std::printf("VENDOR_LOAD_FAILED: %.700s\n", dlerror());
        finish(2);
    }
    std::puts(tbt ? "VENDOR_AUTONAVI_LOADED" : "VENDOR_HSL_LOADED");
    int result = 0;
    {
        preface::amap95::PinnedHslApi api;
        const auto bound = api.bindAlreadyLoaded();
        std::printf("BIND_RESULT: %s\n", preface::amap95::observerResultName(bound));
        if (bound != preface::amap95::ObserverResult::LibraryBound) {
            result = 3;
        } else {
            // The fixed implementation checks its Context pointer before
            // reading the service. It does not create or initialize one.
            void* symbol = dlsym(module, "_ZN3hsl3HSL19GetMapServiceHandleEv");
            void* (*getService)() = nullptr;
            static_assert(sizeof(getService) == sizeof(symbol));
            std::memcpy(&getService, &symbol, sizeof(getService));
            if (getService == nullptr) {
                std::puts("MAP_SERVICE_GETTER_MISSING");
                result = 4;
            } else {
                std::puts(getService() == nullptr ? "VENDOR_MAP_SERVICE_ABSENT" :
                          "VENDOR_MAP_SERVICE_PRESENT_NOT_USED");
            }
        }
        if (result == 0 && tbt) {
            void* start = dlsym(module, "Startuptbt");
            void* stop = dlsym(module, "Exittbt");
            if (!belongsTo(start, stop)) {
                std::puts("TBT_ENTRYPOINTS_MISSING_OR_FOREIGN");
                result = 5;
            } else {
                std::puts("TBT_ENTRYPOINTS_RESOLVED_NOT_INVOKED");
            }
        }
    }
    // The production binding wrapper's destructor above has returned. The
    // explicit diagnostic load remains held until process termination.
    std::puts("BINDING_WRAPPER_RELEASED");
    std::puts("NO_ENGINE_INITIALIZATION_OR_RENDER_EXECUTED");
    finish(result);
}
