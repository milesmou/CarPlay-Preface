package com.android.vpndialogs;

import java.util.LinkedHashMap;
import java.util.Map;

/** Session lease for one paired phone. Only a disconnection requested here may be restored. */
final class BluetoothHandoffLease {
    interface Backend {
        boolean allowed(boolean changingConnection);
        int state();
        boolean disconnect();
        boolean connect();
    }
    interface Scheduler {
        long now();
        void schedule(Runnable task, long delay);
        void cancel(Runnable task);
    }
    interface Listener { void result(boolean disconnected, String reason); }
    private final Backend backend;
    private final Scheduler scheduler;
    private final Runnable idle;
    private final Map<String, Listener> owners = new LinkedHashMap<>();
    private final Runnable tick = this::tick;
    private boolean changed, ready, restoring, disconnectRequested, connectRequested, finished;
    private long deadline;

    BluetoothHandoffLease(Backend backend, Scheduler scheduler, Runnable idle) {
        this.backend = backend; this.scheduler = scheduler; this.idle = idle;
    }

    void acquire(String owner, Listener listener) {
        if (finished) { listener.result(false, "handoff lease ended"); return; }
        owners.put(owner, listener);
        if (ready && !restoring && backend.allowed(false) && backend.state() == 0) {
            listener.result(true, "selected A2DP disconnection confirmed"); return;
        }
        if (restoring) disconnectRequested = false;
        restoring = false;
        ready = false;
        deadline = scheduler.now() + 5_000L;
        scheduler.cancel(tick);
        tick();
    }

    void release(String owner) {
        owners.remove(owner);
        if (!owners.isEmpty() || finished || restoring) return;
        ready = false;
        restoring = true;
        deadline = scheduler.now() + 30_000L;
        scheduler.cancel(tick);
        // Service lifetime is retained before this task can outlive the last binding.
        scheduler.schedule(tick, 0);
    }

    boolean restoring() { return restoring && !finished; }

    private void tick() {
        scheduler.cancel(tick);
        if (finished) return;
        try {
            if (restoring) { restore(); return; }
            if (owners.isEmpty()) { finish(); return; }
            if (!backend.allowed(false)) { fail("selected phone, caller or Bluetooth policy changed"); return; }
            int state = backend.state();
            if (ready) {
                // An external reconnection invalidates ownership. Do not fight it in a loop.
                if (state != 0) { fail("selected A2DP reconnected; reconnect CarPlay explicitly"); return; }
            } else if (state == 0) {
                ready = true;
                for (Listener listener : owners.values().toArray(new Listener[0]))
                    listener.result(true, "selected A2DP disconnection confirmed");
                if (restoring || owners.isEmpty()) return;
            } else if (state == 2) {
                if (!disconnectRequested) {
                    if (!backend.allowed(true)) { fail("Bluetooth handoff blocked by call or policy"); return; }
                    if (!backend.disconnect()) { fail("selected A2DP disconnect rejected"); return; }
                    changed = true;
                    disconnectRequested = true;
                    connectRequested = false;
                }
            } else if (!(changed && state == 3) && !(connectRequested && state == 1)) {
                fail("selected A2DP connection is changing"); return;
            }
            if (!ready && scheduler.now() >= deadline) { fail("selected A2DP disconnect timed out"); return; }
            scheduler.schedule(tick, ready ? 1_000L : 100L);
        } catch (RuntimeException error) {
            if (restoring) finish();
            else fail("Bluetooth handoff unavailable: " + error.getClass().getSimpleName());
        }
    }

    private void restore() {
        if (!changed || !backend.allowed(false) || scheduler.now() >= deadline) { finish(); return; }
        int state = backend.state();
        if (state == 2) { changed = false; finish(); return; }
        if (state == 0 && !connectRequested && backend.allowed(true)) {
            connectRequested = true;
            if (!backend.connect()) { finish(); return; }
        }
        scheduler.schedule(tick, 250L);
    }

    private void fail(String reason) {
        Listener[] listeners = owners.values().toArray(new Listener[0]);
        owners.clear();
        ready = false;
        restoring = true;
        deadline = scheduler.now() + 30_000L;
        for (Listener listener : listeners) listener.result(false, reason);
        scheduler.schedule(tick, 0);
    }

    private void finish() {
        if (finished) return;
        finished = true;
        scheduler.cancel(tick);
        owners.clear();
        idle.run();
    }
}
