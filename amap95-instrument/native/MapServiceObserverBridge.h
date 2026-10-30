#pragma once

#include <cstdint>
#include <mutex>

namespace preface::amap95 {

enum class RenderEventKind {
    DeviceCreated,
    DeviceDestroyed,
    SurfaceCreated,
    SurfaceDestroyed,
    SurfaceChanged,
    DeviceRender,
    EglDoRender,
};

struct RenderEvent {
    std::uint64_t session;
    RenderEventKind kind;
    int deviceId;
    int width;
    int height;
    int colorBits;
    int renderStatus;
};

using RenderEventCallback = void (*)(void* context, const RenderEvent& event) noexcept;

// The nine virtual slots match the inspected 9.5 IMapServiceObserver call
// sites: two destructors, then the seven callbacks below. This is our own
// callback object; no vendor object layout or allocation is reproduced.
//
// Consumer: pinned libhsl.so, GNU build ID
// 71b0534ad0e68635f46cd19c669e1ec6686f63c7.
// This class does not register itself, create a map, or publish readiness.
class MapServiceObserverBridge final {
public:
    MapServiceObserverBridge(std::uint64_t session, int deviceId,
                             RenderEventCallback callback, void* context,
                             bool initiallyActive = true);
    virtual ~MapServiceObserverBridge();

    virtual void onDeviceCreated(int deviceId) noexcept;
    virtual void onDeviceDestroyed(int deviceId) noexcept;
    virtual void onSurfaceCreated(int deviceId, int width, int height, int colorBits) noexcept;
    virtual void onSurfaceDestroyed(int deviceId, int width, int height, int colorBits) noexcept;
    virtual void onSurfaceChanged(int deviceId, int width, int height, int colorBits) noexcept;
    virtual void onDeviceRender(int deviceId, int renderStatus) noexcept;
    virtual void onEglDoRender(int deviceId) noexcept;

    MapServiceObserverBridge(const MapServiceObserverBridge&) = delete;
    MapServiceObserverBridge& operator=(const MapServiceObserverBridge&) = delete;

    // Destroyed device/surface notifications retire this bridge's lease;
    // reuse of the same device ID requires a new bridge and session.
    // Waits for another thread's current callback and rejects all later ones.
    // An active callback may deactivate itself, but must not destroy the
    // bridge. The owner must then call the pinned SDK's RemoveDeviceObserver
    // outside a callback and wait for that call before freeing this object.
    void deactivate() noexcept;

    // For registration owners: start pending, publish the owner handle, then
    // arm once. Destruction or stop while pending permanently retires it.
    bool activate() noexcept;

    // Removing any observer while one of our SDK callbacks is on this thread
    // would re-enter the SDK's observer lock. Defer removal to the owner loop.
    static bool isDispatchingOnCurrentThread() noexcept;

private:
    void deliver(RenderEventKind kind, int deviceId, int width = 0, int height = 0,
                 int colorBits = 0, int renderStatus = 0) noexcept;

    std::recursive_mutex mutex_;
    const std::uint64_t session_;
    const int deviceId_;
    const RenderEventCallback callback_;
    void* const context_;
    enum class State { Pending, Active, Retired };
    State state_;
    static thread_local unsigned int dispatchDepth_;
};

}  // namespace preface::amap95
