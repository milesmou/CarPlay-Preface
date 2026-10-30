import android.app.Application;
import android.app.Service;
import android.content.Intent;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import com.preface.carplay.instrument.Preface95PreparationProtocol;
import com.autonavi.amapauto.jni.protocol.data.GuideInfoProtocolData;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.cert.CertificateFactory;

/** Executes the production Binder, identity checks and rendering owner with Android doubles. */
public final class Preface95BackgroundHostTest {
    private static Service service;
    private static IBinder host;
    private static Binder token = new Binder();
    private static long sequence;
    private static long sessionId = 1;
    private static void check(boolean ok, String text) { if (!ok) throw new AssertionError(text); }
    private static Preface95HintScreen screen() { return (Preface95HintScreen) a20.l().k(1001); }
    private static int call(int code) throws Exception { return call(code, token, sessionId, ++sequence, 1, false); }
    private static int call(int code, IBinder identity, long session, long seq, int version, boolean extra) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        data.writeInterfaceToken(Preface95PreparationProtocol.DESCRIPTOR);
        data.writeInt(version); data.writeStrongBinder(identity); data.writeLong(session); data.writeLong(seq);
        if (extra) data.writeInt(42);
        check(host.transact(code, data, reply, 0), "supported protocol");
        reply.readException(); check(reply.readInt() == 1, "response version"); return reply.readInt();
    }
    private static void rejected(Checked block) throws Exception {
        try { block.run(); throw new AssertionError("invalid caller/lease accepted"); }
        catch (SecurityException expected) { }
    }
    private interface Checked { void run() throws Exception; }
    private static void advance(long ms) { SystemClock.now += ms; Handler.drain(); }
    private static void initializedWindow() throws Exception {
        call(1); Handler.drain();
        check(screen() == null && call(2) == 0, "binding is not engine initialization or READY");
        Preface95NativeHints.onEngineInitialized();
        advance(250);
        check(screen() != null && !screen().gate.ready() && call(2) == 0, "real initialization still waits for readback");
    }
    private static void confirmed() throws Exception {
        initializedWindow();
        check(screen().confirmReadback(), "actual idle frame required by owner");
        advance(250);
        check(call(2) == 1, "only readback confirmation can report buffer ready");
    }
    public static void main(String[] args) throws Exception {
        Application.current = new Application(); service = new Service();
        byte[] certificate = CertificateFactory.getInstance("X.509").generateCertificate(
                new java.io.ByteArrayInputStream(Files.readAllBytes(Paths.get(args[1])))).getEncoded();
        Application.current.packages.info.applicationInfo.uid = 123;
        Application.current.packages.info.signatures = new Signature[]{new Signature(certificate)};
        host = Preface95BackgroundHost.bind(service, new Intent(Preface95PreparationProtocol.ACTION));
        String scenario = args[0];
        if ("cold".equals(scenario)) {
            confirmed();
            Preface95HintScreen before = screen();
            Preface95NativeHints.onAutoStatus(9); Handler.drain();
            check(!before.gate.ready(), "map exit must require a NEW idle readback");
            advance(250); check(call(2) == 0, "stale READY cannot survive exit");
            check(before.confirmReadback(), "new idle frame can restore preparation");
            advance(250); check(call(2) == 1, "fresh frame recovers");
            call(3); check(screen() == null && !before.gate.ready(), "close releases own Surface");
            rejected(() -> call(1));
        } else if ("cancel".equals(scenario)) {
            call(1); call(3); Preface95NativeHints.onEngineInitialized(); Handler.drain();
            check(screen() == null && token.deaths.isEmpty(), "cancel invalidates queued initialization");
        } else if ("death".equals(scenario)) {
            confirmed(); Preface95HintScreen old = screen(); token.die(); Handler.drain();
            check(screen() == null && !old.gate.ready() && !old.confirmReadback(), "death invalidates in-flight frame and releases");
        } else if ("heartbeat".equals(scenario)) {
            confirmed(); advance(3001);
            check(screen() == null, "lost heartbeat releases");
            rejected(() -> call(2));
        } else if ("destroy".equals(scenario)) {
            confirmed(); Preface95BackgroundHost.unbind(new Service(), new Intent(Preface95PreparationProtocol.ACTION));
            check(screen() != null, "unrelated service cannot release");
            Preface95BackgroundHost.destroy(service); Handler.drain();
            check(screen() == null, "service destruction releases owned resources");
        } else if ("local".equals(scenario)) {
            confirmed(); Preface95HintScreen old = screen();
            Preface95NativeHints.onGuide(new GuideInfoProtocolData()); Handler.drain();
            check(old.stopped && screen() != old && screen().accepted == 1, "real local navigation preempts background lease");
            rejected(() -> call(2));
            check(screen().confirmReadback(), "local navigation remains functional");
        } else if ("identity".equals(scenario)) {
            Binder.uid = 124; rejected(() -> call(1)); Binder.uid = 123;
            Application.current.packages.names = new String[]{"com.preface.carplay", "another.package"};
            rejected(() -> call(1)); Application.current.packages.names = new String[]{"com.preface.carplay"};
            Application.current.packages.info.signatures = new Signature[]{new Signature(new byte[]{1})};
            rejected(() -> call(1));
            Application.current.packages.info.signatures = new Signature[]{new Signature(certificate)};
            rejected(() -> call(1, token, 1, ++sequence, 2, false));
            rejected(() -> call(1, token, 1, ++sequence, 1, true));
            check(Preface95BackgroundHost.bind(service, new Intent("ordinary.oem.action")) == null, "OEM Binder fallback preserved");
            confirmed();
        } else if ("stale".equals(scenario)) {
            confirmed(); rejected(() -> call(2, token, 1, sequence, 1, false));
            rejected(() -> call(3, new Binder(), 1, ++sequence, 1, false));
            rejected(() -> call(3, token, 2, ++sequence, 1, false));
            check(call(2) == 1 && screen() != null, "bad requests cannot release a valid lease");
        } else if ("replacement".equals(scenario)) {
            confirmed(); Binder oldToken = token; Preface95HintScreen old = screen(); call(3);
            token = new Binder(); sequence = 0; sessionId++; call(1); Handler.drain();
            check(screen() == null, "replacement waits for old cleanup barrier");
            advance(600); check(screen() != null && screen() != old, "replacement creates fresh window");
            oldToken.die(); Handler.drain();
            check(!old.confirmReadback() && screen().confirmReadback(), "old callbacks cannot prepare or destroy replacement");
        } else if ("engine-timeout".equals(scenario)) {
            call(1); Handler.drain();
            for (int i = 0; i < 38; i++) { call(2); advance(500); }
            advance(1001); rejected(() -> call(2));
            check(screen() == null && token.deaths.isEmpty(), "uninitialized engine times out without READY");
        } else if ("worker-wait".equals(scenario)) {
            a7.finished = false;
            call(1); Preface95NativeHints.onEngineInitialized(); Handler.drain();
            check(a7.starts == 1 && screen() == null && call(2) == 0, "worker start is not completion");
            advance(250); check(a7.starts == 1 && screen() == null, "no repeated worker start");
            a7.finished = true; advance(250);
            check(screen() != null && !screen().gate.ready(), "real worker completion still waits for readback");
        } else if ("surface-failure".equals(scenario)) {
            initializedWindow(); Preface95NativeHints.fail(new IllegalStateException("test readback failure"));
            advance(250); rejected(() -> call(2));
            check(screen() == null, "readback failure stops instead of retrying");
        } else throw new AssertionError("unknown scenario");
        System.out.println("PASS host-" + scenario + " (actual Binder/identity/owner; Android and Surface doubles)");
    }
}
