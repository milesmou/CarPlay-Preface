"""Run the actual hints owner against host lifecycle/Surface test doubles.

No APK, signing, installation, vendor SDK or vehicle protocol is executed.
"""
import json
import hashlib
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "agent_docs/amap-public95-instrument-20261030/hint-lifecycle-regression"
STUBS = {
    "a7.java": """public class a7 {
 public static boolean finished=true; public static int starts;
 public static a7 c(){return new a7();} public void h(android.content.Context c){starts++;}
 public boolean d(){return finished;}
}
""",
    "android/content/Context.java": """package android.content;
public class Context {
 public static final String DISPLAY_SERVICE = "display";
 public Context getApplicationContext() { return android.app.Application.current; }
 public Object getSystemService(String name) { return android.app.Application.current.displays; }
 public android.content.pm.PackageManager getPackageManager() { return android.app.Application.current.packages; }
}
""",
    "android/app/Application.java": """package android.app;
import android.os.Bundle; import java.util.*;
public class Application extends android.content.Context {
 public static Application current;
 public final android.hardware.display.DisplayManager displays = new android.hardware.display.DisplayManager();
 public final android.content.pm.PackageManager packages = new android.content.pm.PackageManager();
 public final List<ActivityLifecycleCallbacks> callbacks = new ArrayList<>();
 public static String getProcessName() { return "com.autonavi.amapauto"; }
 public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks c) { callbacks.add(c); }
 public void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks c) { callbacks.remove(c); }
 public interface ActivityLifecycleCallbacks {
  void onActivityCreated(Activity a, Bundle b); void onActivityStarted(Activity a);
  void onActivityResumed(Activity a); void onActivityPaused(Activity a); void onActivityStopped(Activity a);
  void onActivitySaveInstanceState(Activity a, Bundle b); void onActivityDestroyed(Activity a);
 }
 public void created(Activity a) { for (ActivityLifecycleCallbacks c : new ArrayList<>(callbacks)) c.onActivityCreated(a,null); }
 public void resumed(Activity a) { for (ActivityLifecycleCallbacks c : new ArrayList<>(callbacks)) c.onActivityResumed(a); }
 public void background(Activity a) { for (ActivityLifecycleCallbacks c : new ArrayList<>(callbacks)) { c.onActivityPaused(a); c.onActivityStopped(a); } }
 public void destroyed(Activity a) { for (ActivityLifecycleCallbacks c : new ArrayList<>(callbacks)) c.onActivityDestroyed(a); }
}
""",
    "android/app/Activity.java": """package android.app;
public class Activity extends android.content.Context {
 public boolean finishing, changing;
 public boolean isFinishing() { return finishing; }
 public boolean isChangingConfigurations() { return changing; }
 public Application getApplication() { return Application.current; }
}
""",
    "com/autonavi/amapauto/MainMapActivity.java": """package com.autonavi.amapauto;
public class MainMapActivity extends android.app.Activity { }
""",
    "android/hardware/display/DisplayManager.java": """package android.hardware.display;
import java.util.*; import android.view.Display; import android.os.Handler;
public class DisplayManager {
 public final List<DisplayListener> listeners = new ArrayList<>();
 public Display display = new Display();
 public interface DisplayListener { void onDisplayAdded(int id); void onDisplayChanged(int id); void onDisplayRemoved(int id); }
 public void registerDisplayListener(DisplayListener l, Handler h) { if (listeners.contains(l)) throw new AssertionError("duplicate display listener"); listeners.add(l); }
 public void unregisterDisplayListener(DisplayListener l) { if (!listeners.remove(l)) throw new AssertionError("missing display listener"); }
 public Display getDisplay(int id) { return display; }
}
""",
    "android/view/Display.java": """package android.view;
public class Display {
 public static final int DEFAULT_DISPLAY=0, STATE_ON=2;
 public boolean isValid() { return true; } public int getDisplayId() { return 1; }
 public int getState() { return STATE_ON; } public int getType() { return 2; }
}
""",
    "android/os/Bundle.java": "package android.os; public class Bundle {}",
    "android/os/Looper.java": """package android.os; public class Looper {
 private static final Looper MAIN=new Looper();
 public static Looper getMainLooper() { return MAIN; } public static Looper myLooper() { return MAIN; }
}
""",
    "android/os/Handler.java": """package android.os;
import java.util.*;
public class Handler {
 private static final Queue<Runnable> queue=new ArrayDeque<>();
 private static final java.util.TreeMap<Long,java.util.List<Runnable>> delayed=new java.util.TreeMap<>();
 public Handler(Looper l) {} public boolean post(Runnable r) { queue.add(r); return true; }
 public boolean postDelayed(Runnable r,long ms) { delayed.computeIfAbsent(SystemClock.now+ms,k->new java.util.ArrayList<>()).add(r); return true; }
 public static void drain() { int n=0; while (true) {
  while (!delayed.isEmpty() && delayed.firstKey()<=SystemClock.now) queue.addAll(delayed.pollFirstEntry().getValue());
  if (queue.isEmpty()) return; if (++n>1000) throw new AssertionError("queue loop"); queue.remove().run();
 } }
}
""",
    "android/os/SystemClock.java": "package android.os; public class SystemClock { public static long now=1234; public static long elapsedRealtime() { return now; } }",
    "android/util/Log.java": """package android.util; public class Log {
 public static int i(String t,String s) { return 0; } public static int w(String t,String s) { return 0; }
 public static int e(String t,String s) { return 0; }
}
""",
    "com/autonavi/extscreen/dto/PresentationConfig.java": """package com.autonavi.extscreen.dto;
public class PresentationConfig { public int deviceId=1001; public String pluginName="InstrumentPannelLarge"; }
""",
    "com/autonavi/amapauto/jni/protocol/data/GuideInfoProtocolData.java": """package com.autonavi.amapauto.jni.protocol.data;
public class GuideInfoProtocolData { }
""",
    "hl.java": "public class hl { }",
    "a20.java": """import java.util.*; public class a20 {
 public static final a20 CURRENT=new a20(); public final List<hl> c=new ArrayList<>();
 public static a20 l() { return CURRENT; } public hl k(int id) { return c.isEmpty()?null:c.get(0); }
}
""",
    "rv.java": """public class rv {
 public static rv H() { return new rv(); } public android.content.Context z() { return android.app.Application.current; }
}
""",
    "Preface95Guidance.java": """public class Preface95Guidance {
 public static int releases; public int type=0, icon=3; public String distance="20m", road="test";
 public Preface95Guidance(com.autonavi.amapauto.jni.protocol.data.GuideInfoProtocolData d,long t) { }
 public boolean navigates() { return true; } public void release() { releases++; }
}
""",
    "Preface95HintScreen.java": """public class Preface95HintScreen extends hl {
 public final Preface95FrameGate gate=new Preface95FrameGate();
 public int accepted; public boolean stopped; public boolean hasContent;
 public java.util.function.BooleanSupplier background;
 public Preface95HintScreen(android.content.Context c,android.view.Display d,a20 m) { }
 public void s() { stopped=false; gate.openSurface(); }
 public void accept(Preface95Guidance g) {
  if (stopped) throw new AssertionError("accept after stop");
  accepted++; hasContent=true; gate.changeContent();
  if (!gate.current(gate.generation(),gate.revision())) gate.openSurface();
 }
 public void clearGuidance() { hasContent=false; gate.changeContent(); gate.closeSurface(); if (background!=null && background.getAsBoolean()) gate.openSurface(); }
 public void u() { stopped=true; clearGuidance(); } public void c() { u(); }
 public void requestStop() { stopped=true; }
 public boolean confirmReadback() { return !stopped && gate.confirm(gate.generation(),gate.revision(),hasContent || (background!=null && background.getAsBoolean()),true); }
 public boolean hasGuidance() { return hasContent; }
 public void prepareBackground(java.util.function.BooleanSupplier live) { background=live; }
 public boolean bufferConfirmed() { return !stopped && (background==null || background.getAsBoolean()) && gate.ready(); }
}
""",
    "android/app/Service.java": "package android.app; public class Service extends android.content.Context {}",
    "android/content/Intent.java": "package android.content; public class Intent { private String action; public Intent(String a){action=a;} public String getAction(){return action;} }",
    "android/content/pm/Signature.java": "package android.content.pm; public class Signature { private byte[] data; public Signature(byte[] d){data=d;} public byte[] toByteArray(){return data;} }",
    "android/content/pm/ApplicationInfo.java": "package android.content.pm; public class ApplicationInfo { public int uid; public boolean enabled=true; }",
    "android/content/pm/PackageInfo.java": "package android.content.pm; public class PackageInfo { public ApplicationInfo applicationInfo=new ApplicationInfo(); public Signature[] signatures; }",
    "android/content/pm/PackageManager.java": """package android.content.pm;
public class PackageManager {
 public static final int GET_SIGNATURES=64;
 public PackageInfo info=new PackageInfo(); public String[] names={"com.preface.carplay"};
 public PackageInfo getPackageInfo(String name,int flags) throws Exception { if (!name.equals("com.preface.carplay")) throw new Exception(); return info; }
 public String[] getPackagesForUid(int uid) { return names; }
}
""",
    "android/os/IBinder.java": """package android.os;
public interface IBinder {
 int INTERFACE_TRANSACTION=1598968902;
 interface DeathRecipient { void binderDied(); }
 boolean isBinderAlive(); void linkToDeath(DeathRecipient r,int f) throws RemoteException;
 boolean unlinkToDeath(DeathRecipient r,int f);
 boolean transact(int c,Parcel d,Parcel r,int f) throws RemoteException;
}
""",
    "android/os/RemoteException.java": "package android.os; public class RemoteException extends Exception {}",
    "android/os/Binder.java": """package android.os; import java.util.*;
public class Binder implements IBinder {
 public static int uid=123; public static int getCallingUid(){return uid;}
 public boolean alive=true; private String descriptor; public final List<DeathRecipient> deaths=new ArrayList<>();
 public void attachInterface(Object owner,String d){descriptor=d;}
 public boolean isBinderAlive(){return alive;}
 public void linkToDeath(DeathRecipient r,int f) throws RemoteException { if(!alive) throw new RemoteException(); deaths.add(r); }
 public boolean unlinkToDeath(DeathRecipient r,int f){return deaths.remove(r);}
 public void die(){alive=false; for(DeathRecipient d:new ArrayList<>(deaths))d.binderDied();}
 public boolean transact(int c,Parcel d,Parcel r,int f)throws RemoteException{return onTransact(c,d,r,f);}
 protected boolean onTransact(int c,Parcel d,Parcel r,int f)throws RemoteException { if(c==INTERFACE_TRANSACTION){r.writeString(descriptor);return true;}return false; }
}
""",
    "android/os/Parcel.java": """package android.os; import java.util.*;
public class Parcel {
 private final List<Object> values=new ArrayList<>(); private int position;
 public static Parcel obtain(){return new Parcel();} public void recycle(){}
 public int dataAvail(){return (values.size()-position)*8;}
 public void writeInterfaceToken(String s){writeString(s);} public void enforceInterface(String s){if(!s.equals(readString()))throw new SecurityException();}
 public void writeString(String s){values.add(s);} public String readString(){return (String)values.get(position++);}
 public void writeInt(int v){values.add(v);} public int readInt(){return (Integer)values.get(position++);}
 public void writeLong(long v){values.add(v);} public long readLong(){return (Long)values.get(position++);}
 public void writeStrongBinder(IBinder b){values.add(b);} public IBinder readStrongBinder(){return (IBinder)values.get(position++);}
 public void writeNoException(){writeInt(0);} public void readException(){if(readInt()!=0)throw new RuntimeException();}
}
""",
}


def main():
    config = json.loads((ROOT / "agent_docs/build-environment.local.json").read_text(encoding="utf-8-sig"))
    java = Path(config["javaHome"]) / "bin/java.exe"
    javac = Path(config["javaHome"]) / "bin/javac.exe"
    for tool in (java, javac):
        if not tool.is_file():
            raise RuntimeError(f"缺少缓存 JDK 工具，请提供路径：{tool}")
    sources = OUTPUT / "stubs"
    classes = OUTPUT / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    inputs = []
    for name, content in STUBS.items():
        path = sources / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        inputs.append(path)
    production = [ROOT / "amap95-instrument/java/Preface95NativeHints.java",
               ROOT / "amap95-instrument/java/Preface95FrameGate.java",
               ROOT / "amap95-instrument/java/Preface95BackgroundHost.java",
               ROOT / "amap95-instrument/protocol/Preface95PreparationProtocol.java",
               ROOT / "amap95-instrument/tests/Preface95HintLifecycleTest.java",
               ROOT / "amap95-instrument/tests/Preface95BackgroundHostTest.java"]
    inputs += production
    subprocess.run([str(javac), "--release", "8", "-encoding", "UTF-8", "-d", str(classes), *map(str, inputs)], check=True, timeout=45)
    scenarios = ["reopen-no-frame", "reopen", "replacement", "configuration", "background", "explicit-stop", "failure"]
    results = []
    for scenario in scenarios:
        result = subprocess.run([str(java), "-cp", str(classes), "Preface95HintLifecycleTest", scenario],
                                timeout=15, capture_output=True, text=True)
        if result.returncode:
            raise RuntimeError(f"{scenario}: {result.stdout}\n{result.stderr}")
        print(result.stdout.strip())
        results.append({"scenario": scenario, "output": result.stdout.strip()})
    for scenario in ["cold", "cancel", "death", "heartbeat", "destroy", "local", "identity", "stale", "replacement", "engine-timeout", "worker-wait", "surface-failure"]:
        result = subprocess.run([str(java), "-cp", str(classes), "Preface95BackgroundHostTest", scenario,
                                 str(ROOT / "signing/carplay-release.cer")], timeout=15, capture_output=True, text=True)
        if result.returncode: raise RuntimeError(f"{scenario}: {result.stdout}\n{result.stderr}")
        print(result.stdout.strip())
        results.append({"scenario": "host-" + scenario, "output": result.stdout.strip()})
    (OUTPUT / "verification.json").write_text(json.dumps({
        "success": True, "scenarios": results, "actualOwnerSourceExecuted": True,
        "sourceSha256": {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                         for p in [*production, Path(__file__).resolve()]},
        "androidAndSurfaceAreTestDoubles": True, "vehicleVerified": False,
        "apkBuiltSignedInstalled": False,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
