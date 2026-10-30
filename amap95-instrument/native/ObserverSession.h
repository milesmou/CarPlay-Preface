#pragma once

#include "MapServiceObserverBridge.h"
#include "PinnedHslApi.h"

namespace preface::amap95 {

// Owner operations are serialized on the real window's lifecycle thread.
// The owner retains the SDK map/service until closeAndDestroy succeeds.
// A callback may requestStop or call closeAndDestroy to request deferred
// closure, but must not destroy its context or this session inside a callback.
class ObserverSession final {
public:
    static ObserverResult open(std::uint64_t sessionId, int actualEngineId,
                               RenderEventCallback callback, void* context,
                               ObserverSession*& output) noexcept;

    void requestStop() noexcept;

    // On CallbackMustDefer, the pointer remains owned by the caller; post a
    // task to the window owner loop and call again outside all SDK callbacks.
    // Only success nulls the pointer and permits freeing the callback context.
    static ObserverResult closeAndDestroy(ObserverSession*& session) noexcept;

#ifdef PREFACE_AMAP95_TESTING
    static ObserverResult openFixture(std::uint64_t sessionId, int actualEngineId,
                                      RenderEventCallback callback, void* context,
                                      const HslFunctions& functions,
                                      ObserverSession*& output) noexcept;
#endif

private:
    ObserverSession(std::uint64_t sessionId, RenderEventCallback callback,
                    void* context) noexcept;
    ~ObserverSession() = default;
    ObserverResult attach(int actualEngineId) noexcept;
    static ObserverResult publish(ObserverSession* session, ObserverSession*& output) noexcept;

    PinnedHslApi api_;
    const std::uint64_t sessionId_;
    const RenderEventCallback callback_;
    void* const context_;
    MapIdentity identity_{};
    MapServiceObserverBridge* bridge_ = nullptr;
    bool registered_ = false;
};

}  // namespace preface::amap95
