#pragma once

#include <cstdint>

namespace preface::amap95 {

enum class ObserverResult {
    LibraryBound,
    MapConfirmed,
    Attached,
    Closed,
    InvalidArgument,
    LibraryNotLoaded,
    LibraryMismatch,
    MissingSymbol,
    MapUnavailable,
    MapIdentityMismatch,
    AllocationFailed,
    CallbackMustDefer,
};

const char* observerResultName(ObserverResult result) noexcept;

// Only scalar arguments and borrowed handles cross this boundary. No vendor
// C++ parameter, string, container, object layout, or constructor is declared.
struct HslFunctions {
    void* (*mapService)();
    void* (*mapView)(int engineId);
    int (*engineId)(void* view);
    int (*deviceId)(void* view);
    int (*windowId)(void* multiScreen, int engineId);
    void (*addObserver)(void* service, void* observer);
    void (*removeObserver)(void* service, void* observer);
    void** multiScreenInstance;
};

struct MapIdentity {
    void* service = nullptr;
    void* view = nullptr;
    int engineId = -1;
    int deviceId = -1;
    int windowId = -1;
};

class PinnedHslApi final {
public:
    PinnedHslApi() = default;
    ~PinnedHslApi();
    PinnedHslApi(const PinnedHslApi&) = delete;
    PinnedHslApi& operator=(const PinnedHslApi&) = delete;

    // RTLD_NOLOAD: never starts the SDK or supplies another copy of its libs.
    ObserverResult bindAlreadyLoaded() noexcept;

    // Called on the actual window owner's thread while that owner keeps the
    // map alive. engineId comes from successful real map creation; it is not
    // inferred from window ID 1001. No GetMapEngineId/GetInstance is called.
    ObserverResult inspectExistingMap(int engineId, MapIdentity& result) const noexcept;
    void addObserver(void* service, void* observer) const noexcept;
    void removeObserver(void* service, void* observer) const noexcept;

#ifdef PREFACE_AMAP95_TESTING
    // Fixture seam exists only in standalone unit builds, never production.
    void useFixture(const HslFunctions& functions) noexcept { functions_ = functions; }
#endif

private:
    void* module_ = nullptr;
    HslFunctions functions_{};
};

}  // namespace preface::amap95
