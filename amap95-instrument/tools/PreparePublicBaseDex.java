import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts the "floating version" deltas out of the working DEX and re-applies
 * them onto the clean public DEX. The result is the public base with exactly the
 * reviewed floating additions/changes and nothing else. This tool only writes a
 * local DEX; it never touches an APK, signing input or device.
 *
 * Input: public.dex  working.dex  output.dex
 *
 * Reviewed deltas (public -> working), pinned by the read-only audit
 * agent_docs/amap-public95-instrument-20261030/public-rebuild-audit/:
 *   - 18 added classes copied verbatim from working.
 *   - MapApplicationProxy: superclass Application -> KillerApplication and
 *     onCreate() body replaced with the working implementation.
 *   - com.alibaba.wireless.security.framework.e.c(): body replaced.
 *   - UsbFillActivity.onStop() removed.
 *   - kc.onPause() and kc.onStop() removed.
 * Every other public class is preserved unchanged.
 */
public final class PreparePublicBaseDex {
    static final Set<String> ADDED = new HashSet<>(Arrays.asList(
            "Lbin/mt/signature/KillerApplication;", "Lbin/mt/signature/KillerApplication$1;",
            "Lfloatapp/AMapViewManage;", "Lfloatapp/ApplicationInitialization;", "Lfloatapp/AutoamapPluginManage;",
            "Lfloatapp/ControlReceiver;", "Lfloatapp/FloatWindowsManage;", "Lfloatapp/LifecycleCallbacks;",
            "Lfloatapp/Utils;",
            "Lorg/lsposed/hiddenapibypass/Helper$AccessibleObject;", "Lorg/lsposed/hiddenapibypass/Helper$Class;",
            "Lorg/lsposed/hiddenapibypass/Helper$Executable;", "Lorg/lsposed/hiddenapibypass/Helper$HandleInfo;",
            "Lorg/lsposed/hiddenapibypass/Helper$InvokeStub;", "Lorg/lsposed/hiddenapibypass/Helper$MethodHandle;",
            "Lorg/lsposed/hiddenapibypass/Helper$MethodHandleImpl;", "Lorg/lsposed/hiddenapibypass/Helper$NeverCall;",
            "Lorg/lsposed/hiddenapibypass/HiddenApiBypass;"));

    static final String MAP_PROXY = "Lcom/autonavi/amapauto/app/MapApplicationProxy;";
    static final String KILLER = "Lbin/mt/signature/KillerApplication;";
    static final String SECURITY = "Lcom/alibaba/wireless/security/framework/e;";
    static final String USB_FILL = "Lcom/autonavi/auto/remote/fill/UsbFillActivity;";
    static final String KC = "Lkc;";

    static final int PUBLIC_CLASSES = 7239;
    static final int WORKING_CLASSES = 7257;

    static Map<String, ClassDef> classes(DexBackedDexFile dex) {
        Map<String, ClassDef> result = new HashMap<>();
        for (ClassDef cls : dex.getClasses()) {
            if (result.put(cls.getType(), cls) != null) throw new IllegalStateException("Duplicate class " + cls.getType());
        }
        return result;
    }

    static List<String> types(Method method) {
        List<String> out = new ArrayList<>();
        for (CharSequence p : method.getParameterTypes()) out.add(p.toString());
        return out;
    }

    static Method findMethod(ClassDef cls, String name, List<String> params, String ret) {
        if (cls == null) return null;
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getReturnType().equals(ret) && types(m).equals(params)) return m;
        }
        return null;
    }

    static boolean isReplace(String type, Method m) {
        if (type.equals(MAP_PROXY)) return m.getName().equals("onCreate") && types(m).isEmpty() && m.getReturnType().equals("V");
        if (type.equals(SECURITY)) return m.getName().equals("c") && types(m).isEmpty() && m.getReturnType().equals("Ljava/util/HashMap;");
        return false;
    }

    static boolean isRemoved(String type, Method m) {
        if (type.equals(USB_FILL)) return m.getName().equals("onStop") && types(m).isEmpty() && m.getReturnType().equals("V");
        if (type.equals(KC)) return (m.getName().equals("onPause") || m.getName().equals("onStop")) && types(m).isEmpty() && m.getReturnType().equals("V");
        return false;
    }

    static ImmutableMethod replaceWith(String type, Method original, Method working) {
        MethodImplementation impl = new MutableMethodImplementation(working.getImplementation());
        return new ImmutableMethod(type, original.getName(), original.getParameters(), original.getReturnType(),
                original.getAccessFlags(), original.getAnnotations(), original.getHiddenApiRestrictions(), impl);
    }

    static ImmutableClassDef copyClass(ClassDef c) {
        // Fully detach from the working DEX pools: reusing DexBacked annotations
        // across two source DEX files loses parameter/method annotations during
        // DexPool writing, so copy through the immutable representation instead.
        return ImmutableClassDef.of(c);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("public.dex working.dex output.dex");
        Opcodes opcodes = Opcodes.forApi(28);
        DexBackedDexFile publicDex = new DexBackedDexFile(opcodes, Files.readAllBytes(Path.of(args[0])));
        DexBackedDexFile workingDex = new DexBackedDexFile(opcodes, Files.readAllBytes(Path.of(args[1])));
        Map<String, ClassDef> pub = classes(publicDex);
        Map<String, ClassDef> work = classes(workingDex);
        if (pub.size() != PUBLIC_CLASSES) throw new IllegalStateException("public class count " + pub.size());
        if (work.size() != WORKING_CLASSES) throw new IllegalStateException("working class count " + work.size());
        for (String added : ADDED) {
            if (pub.containsKey(added)) throw new IllegalStateException("Added class already in public: " + added);
            if (!work.containsKey(added)) throw new IllegalStateException("Added class missing in working: " + added);
        }

        List<ClassDef> output = new ArrayList<>();
        int replaced = 0, removed = 0, superChanged = 0;
        for (ClassDef cls : pub.values()) {
            String type = cls.getType();
            if (ADDED.contains(type)) throw new IllegalStateException("Public already has added class");
            String superclass = cls.getSuperclass();
            if (type.equals(MAP_PROXY)) {
                ClassDef worked = work.get(type);
                if (worked == null) throw new IllegalStateException("Working lacks MapApplicationProxy");
                if (!worked.getSuperclass().equals(KILLER)) throw new IllegalStateException("Working superclass mismatch");
                superclass = KILLER;
                superChanged++;
            }
            List<Method> methods = new ArrayList<>();
            ClassDef worked = work.get(type);
            for (Method m : cls.getMethods()) {
                if (isRemoved(type, m)) { removed++; continue; }
                if (isReplace(type, m)) {
                    Method wm = findMethod(worked, m.getName(), types(m), m.getReturnType());
                    if (wm == null || wm.getImplementation() == null) throw new IllegalStateException("Missing working body for " + type + m.getName());
                    methods.add(replaceWith(type, m, wm));
                    replaced++;
                } else {
                    methods.add(m);
                }
            }
            output.add(new ImmutableClassDef(type, cls.getAccessFlags(), superclass, cls.getInterfaces(),
                    cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods));
        }
        // Removal sanity: the two removed methods must exist in public.
        if (replaced != 2) throw new IllegalStateException("Expected 2 replaced bodies, got " + replaced);
        if (removed != 3) throw new IllegalStateException("Expected 3 removed methods, got " + removed);
        if (superChanged != 1) throw new IllegalStateException("Expected 1 superclass change, got " + superChanged);
        for (String added : ADDED) output.add(copyClass(work.get(added)));

        if (output.size() != WORKING_CLASSES) throw new IllegalStateException("Output class count " + output.size());
        DexPool.writeTo(args[2], new ImmutableDexFile(opcodes, output));

        DexBackedDexFile checked = new DexBackedDexFile(opcodes, Files.readAllBytes(Path.of(args[2])));
        Map<String, ClassDef> out = classes(checked);
        if (out.size() != WORKING_CLASSES) throw new IllegalStateException("Reloaded class count " + out.size());
        for (String added : ADDED) if (!out.containsKey(added)) throw new IllegalStateException("Added class missing in output: " + added);
        // Structural checks on the four intentionally changed classes.
        ClassDef mapOut = out.get(MAP_PROXY);
        if (!mapOut.getSuperclass().equals(KILLER)) throw new IllegalStateException("Output superclass not updated");
        if (findMethod(mapOut, "onCreate", List.of(), "V") == null) throw new IllegalStateException("onCreate missing");
        if (findMethod(out.get(USB_FILL), "onStop", List.of(), "V") != null) throw new IllegalStateException("UsbFillActivity.onStop not removed");
        if (findMethod(out.get(KC), "onPause", List.of(), "V") != null || findMethod(out.get(KC), "onStop", List.of(), "V") != null)
            throw new IllegalStateException("kc lifecycle override not removed");
        System.out.println("Public base rebuilt: public=" + PUBLIC_CLASSES + " + added=" + ADDED.size()
                + " = " + WORKING_CLASSES + "; replacedBodies=" + replaced + ", removedMethods=" + removed
                + ", superclassChanged=" + superChanged + "; no APK generated");
    }
}
