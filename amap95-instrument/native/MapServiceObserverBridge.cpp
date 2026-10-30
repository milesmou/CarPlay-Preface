#include "MapServiceObserverBridge.h"

namespace preface::amap95 {

thread_local unsigned int MapServiceObserverBridge::dispatchDepth_ = 0;

MapServiceObserverBridge::MapServiceObserverBridge(
        std::uint64_t session, int deviceId, RenderEventCallback callback, void* context,
        bool initiallyActive)
    : session_(session), deviceId_(deviceId), callback_(callback), context_(context),
      state_(session == 0 || deviceId <= 0 || callback == nullptr ? State::Retired :
             initiallyActive ? State::Active : State::Pending) {}

MapServiceObserverBridge::~MapServiceObserverBridge() {
    deactivate();
}

void MapServiceObserverBridge::deactivate() noexcept {
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    state_ = State::Retired;
}

bool MapServiceObserverBridge::activate() noexcept {
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    if (state_ != State::Pending) {
        return false;
    }
    state_ = State::Active;
    return true;
}

bool MapServiceObserverBridge::isDispatchingOnCurrentThread() noexcept {
    return dispatchDepth_ != 0;
}

void MapServiceObserverBridge::deliver(RenderEventKind kind, int deviceId,
        int width, int height, int colorBits, int renderStatus) noexcept {
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    if (state_ == State::Retired || deviceId != deviceId_) {
        return;
    }
    const bool wasActive = state_ == State::Active;
    if (kind == RenderEventKind::DeviceDestroyed || kind == RenderEventKind::SurfaceDestroyed) {
        state_ = State::Retired;
    }
    if (!wasActive) {
        return;
    }
    const RenderEvent event{session_, kind, deviceId, width, height, colorBits, renderStatus};
    ++dispatchDepth_;
    callback_(context_, event);
    --dispatchDepth_;
}

void MapServiceObserverBridge::onDeviceCreated(int deviceId) noexcept {
    deliver(RenderEventKind::DeviceCreated, deviceId);
}

void MapServiceObserverBridge::onDeviceDestroyed(int deviceId) noexcept {
    deliver(RenderEventKind::DeviceDestroyed, deviceId);
}

void MapServiceObserverBridge::onSurfaceCreated(int deviceId, int width, int height,
        int colorBits) noexcept {
    deliver(RenderEventKind::SurfaceCreated, deviceId, width, height, colorBits);
}

void MapServiceObserverBridge::onSurfaceDestroyed(int deviceId, int width, int height,
        int colorBits) noexcept {
    deliver(RenderEventKind::SurfaceDestroyed, deviceId, width, height, colorBits);
}

void MapServiceObserverBridge::onSurfaceChanged(int deviceId, int width, int height,
        int colorBits) noexcept {
    deliver(RenderEventKind::SurfaceChanged, deviceId, width, height, colorBits);
}

void MapServiceObserverBridge::onDeviceRender(int deviceId, int renderStatus) noexcept {
    deliver(RenderEventKind::DeviceRender, deviceId, 0, 0, 0, renderStatus);
}

void MapServiceObserverBridge::onEglDoRender(int deviceId) noexcept {
    deliver(RenderEventKind::EglDoRender, deviceId);
}

}  // namespace preface::amap95
