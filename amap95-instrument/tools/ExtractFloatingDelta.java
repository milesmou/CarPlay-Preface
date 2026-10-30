import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One-time extraction of the reviewed "floating version" deltas out of the
 * working DEX into a small, frozen delta DEX so the project no longer needs the
 * large third-party floating APK as a build input.
 *
 * The delta DEX holds ONLY:
 *   - the 18 added classes (KillerApplication, floatapp/*, hiddenapibypass/*)
 *   - the two donor classes whose method bodies must be copied onto public:
 *     MapApplicationProxy (superclass + onCreate) and
 *     com.alibaba.wireless.security.framework.e (c()).
 * PreparePublicBaseDex reads its source classes from this delta DEX instead of
 * the full working DEX. This tool writes only a local DEX; it never touches an
 * APK, signing input or device.
 *
 * Input: working.dex  output-delta.dex
 */
public final class ExtractFloatingDelta {
    static final List<String> ADDED = Arrays.asList(
            "Lbin/mt/signature/KillerApplication;", "Lbin/mt/signature/KillerApplication$1;",
            "Lfloatapp/AMapViewManage;", "Lfloatapp/ApplicationInitialization;", "Lfloatapp/AutoamapPluginManage;",
            "Lfloatapp/ControlReceiver;", "Lfloatapp/FloatWindowsManage;", "Lfloatapp/LifecycleCallbacks;",
            "Lfloatapp/Utils;",
            "Lorg/lsposed/hiddenapibypass/Helper$AccessibleObject;", "Lorg/lsposed/hiddenapibypass/Helper$Class;",
            "Lorg/lsposed/hiddenapibypass/Helper$Executable;", "Lorg/lsposed/hiddenapibypass/Helper$HandleInfo;",
            "Lorg/lsposed/hiddenapibypass/Helper$InvokeStub;", "Lorg/lsposed/hiddenapibypass/Helper$MethodHandle;",
            "Lorg/lsposed/hiddenapibypass/Helper$MethodHandleImpl;", "Lorg/lsposed/hiddenapibypass/Helper$NeverCall;",
            "Lorg/lsposed/hiddenapibypass/HiddenApiBypass;");
    static final List<String> DONORS = Arrays.asList(
            "Lcom/autonavi/amapauto/app/MapApplicationProxy;",
            "Lcom/alibaba/wireless/security/framework/e;");

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("working.dex output-delta.dex");
        Opcodes opcodes = Opcodes.forApi(28);
        Map<String, ClassDef> work = new HashMap<>();
        for (ClassDef c : new DexBackedDexFile(opcodes, Files.readAllBytes(Path.of(args[0]))).getClasses())
            work.put(c.getType(), c);
        List<String> required = new ArrayList<>();
        required.addAll(ADDED);
        required.addAll(DONORS);
        List<ClassDef> output = new ArrayList<>();
        for (String type : required) {
            ClassDef c = work.get(type);
            if (c == null) throw new IllegalStateException("Missing class in working DEX: " + type);
            output.add(ImmutableClassDef.of(c));
        }
        DexPool.writeTo(args[1], new ImmutableDexFile(opcodes, output));
        DexBackedDexFile checked = new DexBackedDexFile(opcodes, Files.readAllBytes(Path.of(args[1])));
        if (checked.getClasses().size() != required.size())
            throw new IllegalStateException("Delta class count " + checked.getClasses().size());
        System.out.println("Floating delta extracted: added=" + ADDED.size() + " donors=" + DONORS.size()
                + " total=" + required.size() + "; no APK generated");
    }
}
