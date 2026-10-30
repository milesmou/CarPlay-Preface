#include "ObserverSession.h"

#include <new>

namespace preface::amap95 {

ObserverSession::ObserverSession(std::uint64_t sessionId,
        RenderEventCallback callback, void* context) noexcept
    : sessionId_(sessionId), callback_(callback), context_(context) {}

ObserverResult ObserverSession::open(std::uint64_t sessionId, int actualEngineId,
        RenderEventCallback callback, void* context, ObserverSession*& output) noexcept {
    if (output != nullptr || sessionId == 0 || actualEngineId <= 1 || callback == nullptr) {
        return ObserverResult::InvalidArgument;
    }
    if (MapServiceObserverBridge::isDispatchingOnCurrentThread()) {
        return ObserverResult::CallbackMustDefer;
    }
    auto* session = new (std::nothrow) ObserverSession(sessionId, callback, context);
    if (session == nullptr) {
        return ObserverResult::AllocationFailed;
    }
    ObserverResult result = session->api_.bindAlreadyLoaded();
    if (result == ObserverResult::LibraryBound) {
        result = session->attach(actualEngineId);
    }
    if (result != ObserverResult::Attached) {
        delete session;
        return result;
    }
    return publish(session, output);
}

ObserverResult ObserverSession::publish(ObserverSession* session, ObserverSession*& output) noexcept {
    // Publication precedes arming; the bridge mutex also makes this write
    // visible to a callback that follows activation on another SDK thread.
    output = session;
    if (!session->bridge_->activate()) {
        closeAndDestroy(output);
        return ObserverResult::MapUnavailable;
    }
    return ObserverResult::Attached;
}

ObserverResult ObserverSession::attach(int actualEngineId) noexcept {
    ObserverResult result = api_.inspectExistingMap(actualEngineId, identity_);
    if (result != ObserverResult::MapConfirmed) {
        return result;
    }
    bridge_ = new (std::nothrow) MapServiceObserverBridge(
        sessionId_, identity_.deviceId, callback_, context_, false);
    if (bridge_ == nullptr) {
        return ObserverResult::AllocationFailed;
    }
    api_.addObserver(identity_.service, bridge_);
    registered_ = true;
    // Registration itself is not map readiness. Recheck the same borrowed
    // objects before making this session available to the window owner.
    MapIdentity after;
    result = api_.inspectExistingMap(actualEngineId, after);
    if (result != ObserverResult::MapConfirmed || after.service != identity_.service ||
        after.view != identity_.view || after.deviceId != identity_.deviceId) {
        bridge_->deactivate();
        api_.removeObserver(identity_.service, bridge_);
        registered_ = false;
        delete bridge_;
        bridge_ = nullptr;
        return result == ObserverResult::MapConfirmed ? ObserverResult::MapIdentityMismatch : result;
    }
    return ObserverResult::Attached;
}

void ObserverSession::requestStop() noexcept {
    if (bridge_ != nullptr) {
        bridge_->deactivate();
    }
}

ObserverResult ObserverSession::closeAndDestroy(ObserverSession*& session) noexcept {
    if (session == nullptr) {
        return ObserverResult::Closed;
    }
    session->requestStop();
    if (MapServiceObserverBridge::isDispatchingOnCurrentThread()) {
        return ObserverResult::CallbackMustDefer;
    }
    if (session->registered_) {
        session->api_.removeObserver(session->identity_.service, session->bridge_);
        session->registered_ = false;
    }
    delete session->bridge_;
    session->bridge_ = nullptr;
    delete session;
    session = nullptr;
    return ObserverResult::Closed;
}

#ifdef PREFACE_AMAP95_TESTING
ObserverResult ObserverSession::openFixture(std::uint64_t sessionId, int actualEngineId,
        RenderEventCallback callback, void* context, const HslFunctions& functions,
        ObserverSession*& output) noexcept {
    if (output != nullptr || sessionId == 0 || actualEngineId <= 1 || callback == nullptr ||
        functions.addObserver == nullptr || functions.removeObserver == nullptr) {
        return ObserverResult::InvalidArgument;
    }
    if (MapServiceObserverBridge::isDispatchingOnCurrentThread()) {
        return ObserverResult::CallbackMustDefer;
    }
    auto* session = new (std::nothrow) ObserverSession(sessionId, callback, context);
    if (session == nullptr) {
        return ObserverResult::AllocationFailed;
    }
    session->api_.useFixture(functions);
    const ObserverResult result = session->attach(actualEngineId);
    if (result != ObserverResult::Attached) {
        delete session;
        return result;
    }
    return publish(session, output);
}
#endif

}  // namespace preface::amap95
