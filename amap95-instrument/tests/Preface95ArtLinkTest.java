import java.lang.reflect.Array;
import java.lang.reflect.Method;

/** ART linkage only: does not construct a Presentation or invoke protocol hooks. */
public final class Preface95ArtLinkTest {
    public static void main(String[] args) throws Exception {
        String[] names = {"Preface95FrameGate", "Preface95Guidance", "Preface95HintScreen",
                "Preface95NativeHints", "hl", "a20", "ob", "rv",
                "com.autonavi.amapauto.jni.ViewInfo",
                "com.autonavi.amapauto.jni.protocol.AndroidProtocolExe",
                "com.autonavi.amapauto.jni.protocol.data.GuideInfoProtocolData"};
        for (String name : names) {
            Class<?> cls = Class.forName(name, false, Preface95ArtLinkTest.class.getClassLoader());
            for (Method method : cls.getDeclaredMethods()) {
                method.getParameterTypes();
                method.getReturnType();
            }
        }
        Class<?> kind = Class.forName("d20");
        Object values = kind.getMethod("values").invoke(null);
        Enum<?> presentation = (Enum<?>) Array.get(values, 2);
        if (!"SYSTEM_PRESENTATION".equals(presentation.name())) throw new AssertionError("Enum ABI mismatch");
        Preface95FrameGate gate = new Preface95FrameGate();
        long surface = gate.openSurface(), content = gate.changeContent();
        if (gate.confirm(surface, content, true, false)) throw new AssertionError("Readback gate bypass");
        gate.closeSurface();
        if (gate.confirm(surface, content, true, true)) throw new AssertionError("Stale surface accepted");
        System.out.println("ART_LINKAGE_PASSED: original 9.5 DEX plus candidate; enum ordinal 2 confirmed");
        System.out.println("NO_WINDOW_SURFACE_OR_READY_PROTOCOL_EXECUTED");
    }
}
