#include "MapServiceObserverBridge.h"

#include <atomic>
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <thread>
#include <vector>

using namespace preface::amap95;

// Independent stand-in for the nine-slot callback ABI observed in libhsl.
// This test is not an E02 SDK, a map renderer, or a real vehicle callback.
class NativeDispatcherFixture {
public:
    virtual ~NativeDispatcherFixture() = default;
    virtual void created(int) noexcept = 0;
    virtual void destroyed(int) noexcept = 0;
    virtual void surfaceCreated(int, int, int, int) noexcept = 0;
    virtual void surfaceDestroyed(int, int, int, int) noexcept = 0;
    virtual void surfaceChanged(int, int, int, int) noexcept = 0;
    virtual void render(int, int) noexcept = 0;
    virtual void eglRender(int) noexcept = 0;
};

struct Recorder {
    std::vector<RenderEvent> events;
    static void accept(void* context, const RenderEvent& event) noexcept {
        static_cast<Recorder*>(context)->events.push_back(event);
    }
};

static void checkSlotOrderAndRawFields() {
    Recorder recorder;
    MapServiceObserverBridge bridge(17, 4, Recorder::accept, &recorder);
    // Call the same virtual slots as the native consumer, using the separate
    // fixture declaration to detect accidental changes to our vtable order.
    auto* sdk = reinterpret_cast<NativeDispatcherFixture*>(&bridge);
    sdk->created(4);
    sdk->surfaceCreated(4, 1920, 720, 8);
    sdk->surfaceChanged(4, 1920, 720, 8);
    sdk->render(4, 5);
    sdk->eglRender(4);
    sdk->surfaceDestroyed(4, 1920, 720, 8);
    MapServiceObserverBridge otherBridge(17, 4, Recorder::accept, &recorder);
    reinterpret_cast<NativeDispatcherFixture*>(&otherBridge)->destroyed(4);
    assert(recorder.events.size() == 7);
    const RenderEventKind expected[] = {
        RenderEventKind::DeviceCreated, RenderEventKind::SurfaceCreated,
        RenderEventKind::SurfaceChanged, RenderEventKind::DeviceRender,
        RenderEventKind::EglDoRender, RenderEventKind::SurfaceDestroyed,
        RenderEventKind::DeviceDestroyed,
    };
    for (int i = 0; i != 7; ++i) {
        const auto& event = recorder.events.at(i);
        assert(event.session == 17 && event.deviceId == 4);
        assert(event.kind == expected[i]);
    }
    assert(recorder.events[1].width == 1920 && recorder.events[1].height == 720);
    assert(recorder.events[1].colorBits == 8);
    assert(recorder.events[3].renderStatus == 5);
    // Unknown and negative statuses are preserved; no readiness is inferred.
    MapServiceObserverBridge rawStatusBridge(17, 4, Recorder::accept, &recorder);
    reinterpret_cast<NativeDispatcherFixture*>(&rawStatusBridge)->render(4, -19);
    assert(recorder.events.back().renderStatus == -19);
}

static void checkIdentityAndRetirement() {
    Recorder recorder;
    MapServiceObserverBridge bridge(18, 4, Recorder::accept, &recorder);
    bridge.onDeviceRender(0, 5);
    bridge.onDeviceRender(3, 5);
    assert(recorder.events.empty());
    bridge.onDeviceRender(4, 2);
    bridge.deactivate();
    bridge.onDeviceRender(4, 5);
    assert(recorder.events.size() == 1);
    MapServiceObserverBridge invalidSession(0, 4, Recorder::accept, &recorder);
    MapServiceObserverBridge mainDevice(19, 0, Recorder::accept, &recorder);
    invalidSession.onDeviceRender(4, 5);
    mainDevice.onDeviceRender(0, 5);
    assert(recorder.events.size() == 1);
}

static void checkDestroyedDeviceCannotBeReusedByAnOldSession() {
    Recorder recorder;
    MapServiceObserverBridge oldBridge(21, 4, Recorder::accept, &recorder);
    oldBridge.onDeviceDestroyed(4);
    oldBridge.onDeviceRender(4, 5);
    assert(recorder.events.size() == 1);
    MapServiceObserverBridge newBridge(22, 4, Recorder::accept, &recorder);
    newBridge.onDeviceRender(4, 5);
    newBridge.onSurfaceDestroyed(4, 1920, 720, 8);
    newBridge.onDeviceRender(4, 5);
    assert(recorder.events.size() == 3);
    assert(recorder.events[0].session == 21);
    assert(recorder.events[1].session == 22);
}

struct SelfDeactivatingRecorder {
    MapServiceObserverBridge* bridge = nullptr;
    int events = 0;
    static void accept(void* context, const RenderEvent&) noexcept {
        auto& self = *static_cast<SelfDeactivatingRecorder*>(context);
        ++self.events;
        self.bridge->deactivate();
    }
};

static void checkCallbackMayDeactivateItself() {
    SelfDeactivatingRecorder recorder;
    MapServiceObserverBridge bridge(23, 4, SelfDeactivatingRecorder::accept, &recorder);
    recorder.bridge = &bridge;
    bridge.onDeviceRender(4, 5);
    bridge.onDeviceRender(4, 5);
    assert(recorder.events == 1);
}

struct BlockingRecorder {
    std::mutex mutex;
    std::condition_variable condition;
    bool entered = false;
    bool release = false;
    static void accept(void* context, const RenderEvent&) noexcept {
        auto& self = *static_cast<BlockingRecorder*>(context);
        std::unique_lock<std::mutex> lock(self.mutex);
        self.entered = true;
        self.condition.notify_all();
        self.condition.wait(lock, [&] { return self.release; });
    }
};

static void checkDeactivationWaitsForCallback() {
    BlockingRecorder recorder;
    MapServiceObserverBridge bridge(20, 4, BlockingRecorder::accept, &recorder);
    std::thread sender([&] { bridge.onDeviceRender(4, 5); });
    {
        std::unique_lock<std::mutex> lock(recorder.mutex);
        recorder.condition.wait(lock, [&] { return recorder.entered; });
    }
    std::atomic<bool> attempted{false};
    std::atomic<bool> stopped{false};
    std::thread stopper([&] {
        attempted.store(true);
        recorder.condition.notify_all();
        bridge.deactivate();
        stopped.store(true);
        recorder.condition.notify_all();
    });
    // Let the stopping thread attempt deactivation while the callback is
    // blocked. It cannot complete until the owner releases that callback.
    {
        std::unique_lock<std::mutex> lock(recorder.mutex);
        recorder.condition.wait(lock, [&] { return attempted.load(); });
        const bool returnedEarly = recorder.condition.wait_for(
            lock, std::chrono::milliseconds(50), [&] { return stopped.load(); });
        assert(!returnedEarly);
        recorder.release = true;
        recorder.condition.notify_all();
    }
    sender.join();
    stopper.join();
    assert(stopped.load());
    bridge.onDeviceRender(4, 5);
}

static void checkPendingRegistrationCannotReplayOrRevive() {
    Recorder recorder;
    MapServiceObserverBridge pending(24, 4, Recorder::accept, &recorder, false);
    pending.onDeviceRender(4, 5);
    assert(recorder.events.empty());
    assert(pending.activate());
    assert(!pending.activate());
    assert(recorder.events.empty());
    pending.onDeviceRender(4, 2);
    assert(recorder.events.size() == 1 && recorder.events.back().renderStatus == 2);

    MapServiceObserverBridge lostSurface(25, 4, Recorder::accept, &recorder, false);
    lostSurface.onSurfaceDestroyed(4, 1920, 720, 8);
    assert(!lostSurface.activate());
    lostSurface.onDeviceRender(4, 5);
    assert(recorder.events.size() == 1);
    MapServiceObserverBridge cancelled(26, 4, Recorder::accept, &recorder, false);
    cancelled.deactivate();
    assert(!cancelled.activate());
}

int main() {
    checkSlotOrderAndRawFields();
    checkIdentityAndRetirement();
    checkDestroyedDeviceCannotBeReusedByAnOldSession();
    checkCallbackMayDeactivateItself();
    checkDeactivationWaitsForCallback();
    checkPendingRegistrationCannotReplayOrRevive();
    std::puts("Observer bridge: ABI fixture, identity, raw fields and teardown checks passed (no vendor SDK executed)");
}
