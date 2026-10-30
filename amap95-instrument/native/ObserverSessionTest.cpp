#include "ObserverSession.h"

#include <algorithm>
#include <atomic>
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <dlfcn.h>
#include <mutex>
#include <thread>
#include <vector>

using namespace preface::amap95;

// Only an observer collection and borrowed-handle fixture. It neither loads
// a vendor SDK nor constructs a map or sends any readiness protocol.
struct SdkFixture {
    static SdkFixture* current;
    std::mutex observerLock;
    std::vector<void*> observers;
    int viewObject = 0;
    int otherViewObject = 0;
    void* multi = this;
    int engine = 37;
    int device = 4;
    int window = 1001;
    int adds = 0;
    int removes = 0;
    bool mapPresent = true;
    bool changeIdentityOnAdd = false;
    bool destroySurfaceOnAdd = false;

    SdkFixture() { current = this; }
    ~SdkFixture() { assert(observers.empty()); current = nullptr; }
    static void* service() { return current; }
    static void* view(int) { return current->mapPresent ? &current->viewObject : nullptr; }
    static int engineId(void*) { return current->engine; }
    static int deviceId(void*) { return current->device; }
    static int windowId(void*, int) { return current->window; }
    static void add(void*, void* observer) {
        std::lock_guard<std::mutex> lock(current->observerLock);
        ++current->adds;
        current->observers.push_back(observer);
        if (current->changeIdentityOnAdd) {
            ++current->device;
        }
        // A callback can arrive before the registration owner is published.
        static_cast<MapServiceObserverBridge*>(observer)->onDeviceRender(current->device, 5);
        if (current->destroySurfaceOnAdd) {
            static_cast<MapServiceObserverBridge*>(observer)->onSurfaceDestroyed(
                current->device, 1920, 720, 8);
        }
    }
    static void remove(void*, void* observer) {
        std::lock_guard<std::mutex> lock(current->observerLock);
        ++current->removes;
        auto& values = current->observers;
        values.erase(std::remove(values.begin(), values.end(), observer), values.end());
    }
    HslFunctions functions() {
        return {service, view, engineId, deviceId, windowId, add, remove, &multi};
    }
    void render(int forDevice, int status) {
        std::lock_guard<std::mutex> lock(observerLock);
        for (void* observer : observers) {
            static_cast<MapServiceObserverBridge*>(observer)->onDeviceRender(forDevice, status);
        }
    }
};

SdkFixture* SdkFixture::current = nullptr;

struct Events {
    std::vector<RenderEvent> values;
    static void accept(void* context, const RenderEvent& event) noexcept {
        static_cast<Events*>(context)->values.push_back(event);
    }
};

static void attachStopAndKeepOtherObservers() {
    SdkFixture sdk;
    Events existingEvents;
    Events events;
    MapServiceObserverBridge existing(9, 4, Events::accept, &existingEvents);
    sdk.observers.push_back(&existing);
    ObserverSession* session = nullptr;
    assert(ObserverSession::openFixture(10, 37, Events::accept, &events,
                                       sdk.functions(), session) == ObserverResult::Attached);
    assert(session != nullptr && sdk.adds == 1 && events.values.empty());
    sdk.render(0, 5);
    sdk.render(3, 5);
    sdk.render(4, -19);
    assert(events.values.size() == 1 && events.values[0].renderStatus == -19);
    assert(events.values[0].session == 10);
    session->requestStop();
    sdk.render(4, 5);
    assert(events.values.size() == 1);
    assert(ObserverSession::closeAndDestroy(session) == ObserverResult::Closed);
    assert(session == nullptr && sdk.removes == 1 && sdk.observers.size() == 1);
    assert(ObserverSession::closeAndDestroy(session) == ObserverResult::Closed);
    sdk.render(4, 5);
    assert(existingEvents.values.size() == 3);
    sdk.observers.clear();
}

static void rejectAbsentOrIncorrectMapsBeforeRegistering() {
    SdkFixture sdk;
    Events events;
    ObserverSession* session = nullptr;
    sdk.mapPresent = false;
    assert(ObserverSession::openFixture(11, 37, Events::accept, &events,
        sdk.functions(), session) == ObserverResult::MapUnavailable);
    sdk.mapPresent = true;
    for (int kind = 0; kind != 3; ++kind) {
        sdk.engine = kind == 0 ? 1 : 37;
        sdk.device = kind == 1 ? 0 : 4;
        sdk.window = kind == 2 ? 1000 : 1001;
        assert(ObserverSession::openFixture(11, 37, Events::accept, &events,
            sdk.functions(), session) == ObserverResult::MapIdentityMismatch);
    }
    assert(session == nullptr && sdk.adds == 0 && sdk.removes == 0);
}

static void rollBackIdentityChangeDuringRegistration() {
    SdkFixture sdk;
    Events events;
    ObserverSession* session = nullptr;
    sdk.changeIdentityOnAdd = true;
    assert(ObserverSession::openFixture(12, 37, Events::accept, &events,
        sdk.functions(), session) == ObserverResult::MapIdentityMismatch);
    assert(session == nullptr && sdk.adds == 1 && sdk.removes == 1);
    sdk.render(5, 5);
    assert(events.values.empty());
}

static void surfaceLossDuringRegistrationCannotArmTheSession() {
    SdkFixture sdk;
    Events events;
    ObserverSession* session = nullptr;
    sdk.destroySurfaceOnAdd = true;
    assert(ObserverSession::openFixture(17, 37, Events::accept, &events,
        sdk.functions(), session) == ObserverResult::MapUnavailable);
    assert(session == nullptr && sdk.adds == 1 && sdk.removes == 1);
    assert(events.values.empty());
}

struct DeferredClose {
    ObserverSession* session = nullptr;
    ObserverResult result = ObserverResult::InvalidArgument;
    int calls = 0;
    static void accept(void* context, const RenderEvent&) noexcept {
        auto& owner = *static_cast<DeferredClose*>(context);
        assert(MapServiceObserverBridge::isDispatchingOnCurrentThread());
        ++owner.calls;
        owner.result = ObserverSession::closeAndDestroy(owner.session);
    }
};

static void callbackDefersSdkRemovalWithoutDeadlocking() {
    SdkFixture sdk;
    DeferredClose owner;
    assert(ObserverSession::openFixture(13, 37, DeferredClose::accept, &owner,
        sdk.functions(), owner.session) == ObserverResult::Attached);
    sdk.render(4, 5);
    assert(owner.calls == 1 && owner.result == ObserverResult::CallbackMustDefer);
    assert(owner.session != nullptr && sdk.removes == 0);
    sdk.render(4, 5);
    assert(owner.calls == 1);
    assert(!MapServiceObserverBridge::isDispatchingOnCurrentThread());
    assert(ObserverSession::closeAndDestroy(owner.session) == ObserverResult::Closed);
    assert(owner.session == nullptr && sdk.removes == 1);
}

struct BlockingEvent {
    std::mutex mutex;
    std::condition_variable condition;
    bool entered = false;
    bool released = false;
    static void accept(void* context, const RenderEvent&) noexcept {
        auto& event = *static_cast<BlockingEvent*>(context);
        std::unique_lock<std::mutex> lock(event.mutex);
        event.entered = true;
        event.condition.notify_all();
        event.condition.wait(lock, [&] { return event.released; });
    }
};

static void closeWaitsForRealInFlightFixtureCallback() {
    SdkFixture sdk;
    BlockingEvent event;
    ObserverSession* session = nullptr;
    assert(ObserverSession::openFixture(14, 37, BlockingEvent::accept, &event,
        sdk.functions(), session) == ObserverResult::Attached);
    std::thread renderer([&] { sdk.render(4, 5); });
    {
        std::unique_lock<std::mutex> lock(event.mutex);
        event.condition.wait(lock, [&] { return event.entered; });
    }
    std::atomic<bool> attempted{false};
    std::atomic<bool> closed{false};
    std::thread closer([&] {
        attempted.store(true);
        event.condition.notify_all();
        assert(ObserverSession::closeAndDestroy(session) == ObserverResult::Closed);
        closed.store(true);
        event.condition.notify_all();
    });
    {
        std::unique_lock<std::mutex> lock(event.mutex);
        event.condition.wait(lock, [&] { return attempted.load(); });
        assert(!event.condition.wait_for(lock, std::chrono::milliseconds(50),
                                         [&] { return closed.load(); }));
        event.released = true;
        event.condition.notify_all();
    }
    renderer.join();
    closer.join();
    assert(session == nullptr && sdk.removes == 1);
    sdk.render(4, 5);
}

static void invalidRequestsDoNotReplaceAnActiveSession() {
    SdkFixture sdk;
    Events events;
    ObserverSession* session = nullptr;
    assert(ObserverSession::openFixture(0, 37, Events::accept, &events,
        sdk.functions(), session) == ObserverResult::InvalidArgument);
    assert(ObserverSession::openFixture(15, 1, Events::accept, &events,
        sdk.functions(), session) == ObserverResult::InvalidArgument);
    assert(ObserverSession::openFixture(15, 37, Events::accept, &events,
        sdk.functions(), session) == ObserverResult::Attached);
    ObserverSession* original = session;
    assert(ObserverSession::openFixture(16, 37, Events::accept, &events,
        sdk.functions(), session) == ObserverResult::InvalidArgument);
    assert(session == original && sdk.adds == 1);
    assert(ObserverSession::closeAndDestroy(session) == ObserverResult::Closed);
}

static void noLoadGuardDoesNotStartAnSdk(const char* foreignLibrary) {
    PinnedHslApi absent;
    assert(absent.bindAlreadyLoaded() == ObserverResult::LibraryNotLoaded);
    if (foreignLibrary != nullptr) {
        // This deliberately incompatible fixture exports only an aborting
        // anchor. The production guard must reject it without calling it.
        void* module = dlopen(foreignLibrary, RTLD_NOW | RTLD_LOCAL);
        if (module == nullptr) {
            std::fprintf(stderr, "Foreign rejection fixture failed to load: %s\n", dlerror());
        }
        assert(module != nullptr);
        {
            PinnedHslApi foreign;
            assert(foreign.bindAlreadyLoaded() == ObserverResult::LibraryMismatch);
        }
        assert(dlclose(module) == 0);
    }
}

int main(int argc, char** argv) {
    attachStopAndKeepOtherObservers();
    rejectAbsentOrIncorrectMapsBeforeRegistering();
    rollBackIdentityChangeDuringRegistration();
    surfaceLossDuringRegistrationCannotArmTheSession();
    callbackDefersSdkRemovalWithoutDeadlocking();
    closeWaitsForRealInFlightFixtureCallback();
    invalidRequestsDoNotReplaceAnActiveSession();
    noLoadGuardDoesNotStartAnSdk(argc > 1 ? argv[1] : nullptr);
    std::puts("Observer session: 8 lifecycle/identity/loaded-library fixture groups passed; no vendor SDK or map executed");
}
