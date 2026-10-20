package com.android.vpndialogs;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class BluetoothHandoffLeaseTest {
    final Clock clock = new Clock();
    final Phone phone = new Phone();
    final List<Boolean> results = new ArrayList<>();
    int closed;
    final BluetoothHandoffLease lease = new BluetoothHandoffLease(phone, clock, () -> closed++);

    @Test public void acceptedDisconnectIsNotConfirmationAndOnlyOwnedDisconnectIsRestored() {
        lease.acquire("first", (ok, reason) -> results.add(ok));
        assertEquals(1, phone.disconnects); assertTrue(results.isEmpty());
        phone.state = 0; clock.advance(100);
        assertEquals(List.of(true), results);
        lease.release("first"); clock.advance(0);
        assertEquals(1, phone.connects); assertEquals(0, closed);
        phone.state = 2; clock.advance(250);
        assertEquals(1, closed);
    }
    @Test public void alreadyDisconnectedPhoneIsNeverConnectedOnRelease() {
        phone.state = 0;
        lease.acquire("first", (ok, reason) -> results.add(ok));
        lease.release("first"); clock.advance(0);
        assertEquals(List.of(true), results);
        assertEquals(0, phone.disconnects); assertEquals(0, phone.connects); assertEquals(1, closed);
    }
    @Test public void replacementOwnerKeepsTheLeaseUntilBothOwnersRelease() {
        lease.acquire("first", (ok, reason) -> results.add(ok));
        phone.state = 0; clock.advance(100);
        lease.acquire("second", (ok, reason) -> results.add(ok));
        lease.release("first"); clock.advance(500);
        assertEquals(0, phone.connects);
        lease.release("second"); clock.advance(0);
        assertEquals(1, phone.connects); assertEquals(1, phone.disconnects);
    }
    @Test public void callAndAnotherPhoneBlockDisconnection() {
        phone.canChange = false;
        lease.acquire("first", (ok, reason) -> results.add(ok)); clock.advance(0);
        assertEquals(List.of(false), results); assertEquals(0, phone.disconnects); assertEquals(0, phone.connects);
    }
    @Test public void cancelledPendingDisconnectWaitsThenRestores() {
        lease.acquire("first", (ok, reason) -> results.add(ok));
        lease.release("first"); clock.advance(100);
        assertEquals(0, phone.connects);
        phone.state = 0; clock.advance(250);
        assertEquals(1, phone.connects); assertTrue(results.isEmpty());
    }
    @Test public void restorationDefersThroughCallAndStopsWhenAnotherPhoneTakesOver() {
        lease.acquire("first", (ok, reason) -> results.add(ok));
        phone.state = 0; clock.advance(100);
        phone.canChange = false; lease.release("first"); clock.advance(1_000);
        assertEquals(0, phone.connects);
        phone.valid = false; phone.canChange = true; clock.advance(250);
        assertEquals(0, phone.connects); assertEquals(1, closed);
    }
    @Test public void unexpectedReconnectionFailsWithoutAnInfiniteDisconnectLoop() {
        lease.acquire("first", (ok, reason) -> results.add(ok));
        phone.state = 0; clock.advance(100);
        phone.state = 2; clock.advance(1_000);
        assertEquals(List.of(true, false), results);
        assertEquals(1, phone.disconnects); assertEquals(0, phone.connects); assertEquals(1, closed);
    }
    @Test public void timeoutNeverReportsAConfirmedDisconnect() {
        lease.acquire("first", (ok, reason) -> results.add(ok)); clock.advance(5_100);
        assertEquals(List.of(false), results); assertEquals(1, phone.disconnects);
        clock.advance(30_000); assertEquals(1, closed); assertEquals(0, phone.connects);
    }
    @Test public void connectIsRequestedOnceEvenWhenSystemAcceptsButDoesNotConnect() {
        lease.acquire("first", (ok, reason) -> results.add(ok));
        phone.state = 0; clock.advance(100);
        lease.release("first"); clock.advance(31_000);
        assertEquals(1, phone.connects); assertEquals(1, closed);
    }
    @Test public void identityChangesCancelAnActiveLeaseWithoutReconnectingUnderANewIdentity() {
        lease.acquire("first", (ok, reason) -> results.add(ok));
        phone.state = 0; clock.advance(100);
        phone.valid = false; clock.advance(1_000);
        assertEquals(List.of(true, false), results); assertEquals(0, phone.connects); assertEquals(1, closed);
    }
    static final class Phone implements BluetoothHandoffLease.Backend {
        int state = 2, connects, disconnects;
        boolean valid = true, canChange = true;
        @Override public boolean allowed(boolean changing) { return valid && (!changing || canChange); }
        @Override public int state() { return state; }
        @Override public boolean disconnect() { disconnects++; state = 3; return true; }
        @Override public boolean connect() { connects++; return true; }
    }
    static final class Clock implements BluetoothHandoffLease.Scheduler {
        long time;
        final Map<Runnable, Long> tasks = new LinkedHashMap<>();
        @Override public long now() { return time; }
        @Override public void schedule(Runnable task, long delay) { tasks.put(task, time + delay); }
        @Override public void cancel(Runnable task) { tasks.remove(task); }
        void advance(long millis) {
            long end = time + millis;
            while (true) {
                Map.Entry<Runnable, Long> next = tasks.entrySet().stream().filter(e -> e.getValue() <= end)
                    .min(Map.Entry.comparingByValue()).orElse(null);
                if (next == null) break;
                Runnable task = next.getKey(); time = next.getValue(); tasks.remove(task); task.run();
            }
            time = end;
        }
    }
}
