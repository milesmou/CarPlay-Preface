"""Shared Preface95 payload compiler (base-agnostic).

Compiles the Preface95 native-hint sources plus the shared preparation protocol
into a small payload DEX. It has no dependency on any particular base APK (no
floating / public base), so it is safe to use from the public-only rebuild flow.
"""
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "amap95-instrument"

STUBS = {
    "a7.java": """import android.content.Context; public class a7 {
public static a7 c(){throw new UnsupportedOperationException();}
public void h(Context context){} public boolean d(){return false;} }
""",
    "hl.java": """import android.content.Context; import android.view.MotionEvent;
import com.autonavi.amapauto.jni.ViewInfo;
public abstract class hl { public Context b; public hl(Context c) { throw new UnsupportedOperationException(); }
public void o(int x) {} public void r(ViewInfo x) {} public abstract d20 f(); public abstract void i();
public abstract void n(MotionEvent e); public abstract void t(int x); public abstract void s();
public abstract void u(); public abstract void c(); public void b(int x, boolean y) {} }
""",
    "a20.java": """import java.util.List; public class a20 { public List c;
public static a20 l(){throw new UnsupportedOperationException();}
public hl k(int x){throw new UnsupportedOperationException();} }
""",
    "d20.java": "public enum d20 { NO_MUTIL_SCREEN, SYSTEM_BUFFER, SYSTEM_PRESENTATION }\n",
    "rv.java": """import android.content.Context; public class rv {
public static rv H(){throw new UnsupportedOperationException();}
public Context z(){throw new UnsupportedOperationException();} }
""",
    "ob.java": """import android.graphics.Bitmap; public class ob {
public static int[] b(byte[] x){throw new UnsupportedOperationException();}
public static Bitmap h(int x,int y,boolean z,boolean w){throw new UnsupportedOperationException();} }
""",
    "com/autonavi/amapauto/jni/ViewInfo.java": """package com.autonavi.amapauto.jni;
public class ViewInfo { public String a,b; public int c,i,n; }
""",
    "com/autonavi/amapauto/jni/protocol/AndroidProtocolExe.java": """package com.autonavi.amapauto.jni.protocol;
public class AndroidProtocolExe { public static void sendExScreenStatus(int a,int b,int c) {} }
""",
    "com/autonavi/amapauto/jni/protocol/data/GuideInfoProtocolData.java": """package com.autonavi.amapauto.jni.protocol.data;
import android.graphics.Bitmap; public class GuideInfoProtocolData {
public int getType(){return -1;} public int getIcon(){return -1;} public int getRoundAboutNum(){return -1;}
public int getSegRemainDis(){return -1;} public String getSegRemainDisAuto(){return null;}
public String getNextRoadName(){return null;} public Bitmap getTurnIconBitmap(){return null;}
public int getOnlineIconWidth(){return 0;} public int getOnlineIconHeight(){return 0;} public byte[] getOnlineIcon(){return null;} }
""",
    "com/autonavi/extscreen/dto/PresentationConfig.java": """package com.autonavi.extscreen.dto;
public class PresentationConfig { public int deviceId; public String pluginName; }
""",
}


def payload_sources():
    sources = sorted(p for p in (SOURCE / "java").glob("*.java")
                     if p.name not in ("Preface95PackageIdentity.java", "Preface95CertificatePin.java"))
    sources += sorted((SOURCE / "protocol").glob("*.java"))
    return sources


def source_hashes():
    def sha(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()
    return {p.relative_to(ROOT).as_posix(): sha(p) for p in payload_sources()}


def build_payload(java, javac, platform, tools_jar, out_dir, run):
    """Compile the payload DEX into out_dir/classes.dex and return its Path."""
    out_dir = Path(out_dir)
    stub_dir, api_dir, code_dir, dex_dir = (out_dir / n for n in ("stub", "api", "program", "payload"))
    for d in (stub_dir, api_dir, code_dir, dex_dir):
        d.mkdir(parents=True, exist_ok=True)
    for directory in (code_dir, dex_dir):
        for path in directory.rglob("*.class"):
            path.unlink()
        for path in directory.glob("classes.dex"):
            path.unlink()
    for name, text in STUBS.items():
        path = stub_dir / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    run([str(javac), "--release", "8", "-encoding", "UTF-8", "-classpath", str(platform),
         "-d", str(api_dir), *(str(stub_dir / name) for name in STUBS)], "compile payload API stubs")
    run([str(javac), "--release", "8", "-encoding", "UTF-8", "-classpath", f"{platform};{api_dir}",
         "-d", str(code_dir), *[str(p) for p in payload_sources()]], "compile Preface95 sources")
    program = sorted(code_dir.rglob("*.class"))
    if not program or any(not p.name.startswith("Preface95") for p in program):
        raise ValueError("Program classes contain non-payload types")
    run([str(java), "-cp", str(tools_jar), "com.android.tools.r8.D8", "--min-api", "28", "--lib", str(platform),
         "--classpath", str(api_dir), "--output", str(dex_dir), *[str(p) for p in program]], "payload D8 compilation")
    return dex_dir / "classes.dex"
