import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.*;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.nio.file.*;
import java.util.*;

/** Writes only a local candidate DEX, never an APK. Fixed caller pins input hash. */
public final class PrepareHintDex {
    static final String GUIDE = "Lcom/autonavi/amapauto/jni/protocol/data/GuideInfoProtocolData;";
    static final String PROTOCOL = "Lcom/autonavi/amapauto/jni/protocol/AndroidProtocolExe;";
    static final String CONFIG = "Lcom/autonavi/extscreen/dto/PresentationConfig;";
    static final String HOOK = "LPreface95NativeHints;";
    static final String JSON_SERVICE = "Lcom/autonavi/amapauto/protocol/service/JsonProtocolService;";
    static final String INTENT = "Landroid/content/Intent;";
    static final String BINDER = "Landroid/os/IBinder;";
    static final String BACKGROUND = "LPreface95BackgroundHost;";
    static final String SERVICE = "Landroid/app/Service;";
    static final Set<String> VENDOR = new HashSet<>(Arrays.asList(
            "La20;", "La7;", "Lhl;", "Lhl$a;", "Ld20;", "Lrv;", "Lob;",
            GUIDE, PROTOCOL, "Lcom/autonavi/amapauto/jni/ViewInfo;", CONFIG));

    static String key(MethodReference method) {
        return method.getDefiningClass() + "->" + method.getName() + method.getParameterTypes() + method.getReturnType();
    }

    static Map<String, ClassDef> classes(DexBackedDexFile dex) {
        Map<String, ClassDef> result = new HashMap<>();
        for (ClassDef cls : dex.getClasses()) result.put(cls.getType(), cls);
        return result;
    }

    static ImmutableMethod withCode(Method method, String name, MethodImplementation impl) {
        return new ImmutableMethod(method.getDefiningClass(), name, method.getParameters(),
                method.getReturnType(), method.getAccessFlags(), method.getAnnotations(),
                method.getHiddenApiRestrictions(), impl);
    }

    static MethodImplementation inject(Method method, String hook, List<String> params, int paramWords, boolean beforeReturn) {
        MethodImplementation original = Objects.requireNonNull(method.getImplementation());
        MutableMethodImplementation code = new MutableMethodImplementation(original);
        int first = original.getRegisterCount() - paramWords;
        if (first < 0) throw new IllegalArgumentException("No parameter register");
        ImmutableMethodReference call = new ImmutableMethodReference(HOOK, hook, params, "V");
        int added = 0;
        if (beforeReturn) {
            for (int i = code.getInstructions().size() - 1; i >= 0; i--) {
                if (code.getInstructions().get(i).getOpcode() == Opcode.RETURN_VOID) {
                    code.addInstruction(i, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, first, params.size(), call));
                    added++;
                }
            }
        } else {
            code.addInstruction(0, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, first, params.size(), call));
            added = 1;
        }
        if (added != 1) throw new IllegalArgumentException("Unexpected hook return layout: " + key(method));
        return code;
    }

    static MethodImplementation wrapper(String hook, String parameter, String fallback) {
        MutableMethodImplementation code = new MutableMethodImplementation(3);
        code.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 1, 2,
                new ImmutableMethodReference(HOOK, hook, Arrays.asList("La20;", parameter), "Z")));
        code.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
        // Label is attached after building the fallback location.
        code.addInstruction(new BuilderInstruction10x(Opcode.NOP));
        code.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        code.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 1, 2,
                new ImmutableMethodReference("La20;", fallback, Arrays.asList(parameter), "V")));
        code.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        code.replaceInstruction(2, new BuilderInstruction21t(Opcode.IF_EQZ, 0, code.newLabelForIndex(4)));
        return code;
    }

    static MethodImplementation startupLog() {
        MutableMethodImplementation code = new MutableMethodImplementation(3);
        List<String> parameters = Arrays.asList("Ljava/lang/String;", "Ljava/lang/String;", "[Ljava/lang/Object;");
        code.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 0, 3,
                new ImmutableMethodReference("LPreface95StartupTrace;", "onLog", parameters, "V")));
        code.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 0, 3,
                new ImmutableMethodReference("Lcz;", "preface95OriginalLog", parameters, "V")));
        code.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        return code;
    }

    static MethodImplementation initialized(Method method) {
        MutableMethodImplementation code = new MutableMethodImplementation(1);
        code.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 0, 1,
                new ImmutableMethodReference(method.getDefiningClass(), "preface95OriginalInitialized", Collections.emptyList(), "V")));
        code.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                new ImmutableMethodReference(HOOK, "onEngineInitialized", Collections.emptyList(), "V")));
        code.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        return code;
    }

    static MethodImplementation bind() {
        MutableMethodImplementation code = new MutableMethodImplementation(4);
        code.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 2, 2,
                new ImmutableMethodReference(BACKGROUND, "bind", Arrays.asList(SERVICE, INTENT), BINDER)));
        code.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        code.addInstruction(new BuilderInstruction10x(Opcode.NOP));
        code.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT, 0));
        code.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 2, 2,
                new ImmutableMethodReference(JSON_SERVICE, "preface95OriginalBind", Arrays.asList(INTENT), BINDER)));
        code.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        code.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT, 0));
        code.replaceInstruction(2, new BuilderInstruction21t(Opcode.IF_EQZ, 0, code.newLabelForIndex(4)));
        return code;
    }

    static MethodImplementation destroy() {
        MutableMethodImplementation code = new MutableMethodImplementation(1);
        code.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 0, 1,
                new ImmutableMethodReference(BACKGROUND, "destroy", Arrays.asList(SERVICE), "V")));
        code.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 0, 1,
                new ImmutableMethodReference(JSON_SERVICE, "preface95OriginalDestroy", Collections.emptyList(), "V")));
        code.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        return code;
    }

    static ImmutableMethod unbind() {
        MutableMethodImplementation code = new MutableMethodImplementation(3);
        code.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 1, 2,
                new ImmutableMethodReference(BACKGROUND, "unbind", Arrays.asList(SERVICE, INTENT), "V")));
        code.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_SUPER_RANGE, 1, 2,
                new ImmutableMethodReference(SERVICE, "onUnbind", Arrays.asList(INTENT), "Z")));
        code.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
        code.addInstruction(new BuilderInstruction11x(Opcode.RETURN, 0));
        return new ImmutableMethod(JSON_SERVICE, "onUnbind", Arrays.asList(
                new ImmutableMethodParameter(INTENT, Collections.emptySet(), null)), "Z", 1,
                Collections.emptySet(), Collections.emptySet(), code);
    }

    static void verifyPayload(Map<String, ClassDef> original, Map<String, ClassDef> payload) {
        for (ClassDef cls : payload.values()) {
            if (!cls.getType().startsWith("LPreface95") &&
                    !cls.getType().equals("Lcom/preface/carplay/instrument/Preface95PreparationProtocol;"))
                throw new IllegalArgumentException("Non-payload class in DEX: " + cls.getType());
            if (original.containsKey(cls.getType())) throw new IllegalArgumentException("Payload class collision");
            for (Method method : cls.getMethods()) {
                if (method.getImplementation() == null) continue;
                for (Instruction inst : method.getImplementation().getInstructions()) {
                    if (!(inst instanceof ReferenceInstruction)) continue;
                    Reference ref = ((ReferenceInstruction) inst).getReference();
                    if (ref instanceof MethodReference) {
                        MethodReference target = (MethodReference) ref;
                        if (!VENDOR.contains(target.getDefiningClass())) continue;
                        Method found = findMethod(original, target);
                        if (found == null || (found.getAccessFlags() & 2) != 0) {
                            throw new IllegalArgumentException("Unverified vendor method: " + key(target));
                        }
                        boolean staticCall = inst.getOpcode() == Opcode.INVOKE_STATIC ||
                                inst.getOpcode() == Opcode.INVOKE_STATIC_RANGE;
                        if (staticCall != ((found.getAccessFlags() & 8) != 0)) {
                            throw new IllegalArgumentException("Static/instance mismatch: " + key(target));
                        }
                    } else if (ref instanceof FieldReference) {
                        FieldReference target = (FieldReference) ref;
                        if (!VENDOR.contains(target.getDefiningClass())) continue;
                        ClassDef owner = original.get(target.getDefiningClass());
                        boolean found = false;
                        if (owner != null) for (Field f : owner.getFields()) {
                            if (f.getName().equals(target.getName()) && f.getType().equals(target.getType()) &&
                                    (f.getAccessFlags() & 1) != 0) found = true;
                        }
                        if (!found) throw new IllegalArgumentException("Unverified vendor field: " + target);
                    }
                }
            }
        }
    }

    static Method findMethod(Map<String, ClassDef> classes, MethodReference target) {
        ClassDef owner = classes.get(target.getDefiningClass());
        while (owner != null) {
            for (Method candidate : owner.getMethods()) {
                if (candidate.getName().equals(target.getName()) &&
                        candidate.getReturnType().equals(target.getReturnType()) &&
                        candidate.getParameterTypes().equals(target.getParameterTypes())) return candidate;
            }
            owner = classes.get(owner.getSuperclass());
        }
        return null;
    }

    static String operand(Object value) {
        if (value == null) return "null";
        if (value instanceof Reference || value instanceof Number || value instanceof CharSequence) return value.toString();
        if (value instanceof Enum<?>) return ((Enum<?>) value).name();
        if (value instanceof SwitchElement) {
            SwitchElement item = (SwitchElement) value;
            return item.getKey() + ":" + item.getOffset();
        }
        if (value instanceof Iterable<?>) {
            StringBuilder list = new StringBuilder("[");
            for (Object item : (Iterable<?>) value) list.append(operand(item)).append(';');
            return list.append(']').toString();
        }
        throw new IllegalStateException("Unreviewed DEX operand: " + value.getClass().getName());
    }

    static String body(Method method) throws Exception {
        MethodImplementation code = method.getImplementation();
        if (code == null) return "no-code";
        StringBuilder out = new StringBuilder().append(code.getRegisterCount()).append('|');
        for (Instruction instruction : code.getInstructions()) {
            List<java.lang.reflect.Method> getters = new ArrayList<>();
            for (java.lang.reflect.Method getter : instruction.getClass().getMethods()) {
                if (getter.getName().startsWith("get") && !getter.getName().equals("getClass") && getter.getParameterCount() == 0) getters.add(getter);
            }
            getters.sort(Comparator.comparing(java.lang.reflect.Method::getName));
            for (java.lang.reflect.Method getter : getters) {
                out.append(getter.getName()).append('=').append(operand(getter.invoke(instruction))).append(';');
            }
            out.append('|');
        }
        for (TryBlock<? extends ExceptionHandler> block : code.getTryBlocks()) {
            out.append("try:").append(block.getStartCodeAddress()).append(':').append(block.getCodeUnitCount());
            for (ExceptionHandler handler : block.getExceptionHandlers()) {
                out.append(':').append(handler.getExceptionType()).append(':').append(handler.getHandlerCodeAddress());
            }
        }
        return out.toString();
    }

    static void verifyPreserved(Map<String, ClassDef> original, Map<String, ClassDef> rewritten) throws Exception {
        int checked = 0;
        for (ClassDef cls : original.values()) {
            ClassDef changed = Objects.requireNonNull(rewritten.get(cls.getType()));
            if (cls.getAccessFlags() != changed.getAccessFlags() || !Objects.equals(cls.getSuperclass(), changed.getSuperclass())) {
                throw new IllegalStateException("Original class identity changed");
            }
            for (Method before : cls.getMethods()) {
                boolean patched = cls.getType().equals("Loo;") && before.getName().equals("<init>") &&
                        before.getParameterTypes().equals(Arrays.asList(GUIDE));
                patched |= cls.getType().equals(PROTOCOL) && before.getParameterTypes().equals(Arrays.asList("I", "I")) &&
                        (before.getName().equals("sendAutoStatus") || before.getName().equals("dispatchAutoStatus"));
                if (patched) continue;
                String name = before.getName();
                if (cls.getType().equals("Lcz;") && name.equals("l") && before.getParameterTypes().equals(Arrays.asList("Ljava/lang/String;", "Ljava/lang/String;", "[Ljava/lang/Object;"))) name = "preface95OriginalLog";
                if (cls.getType().equals("La20;") && name.equals("i") && before.getParameterTypes().equals(Arrays.asList(CONFIG))) name = "preface95OriginalCreate";
                if (cls.getType().equals("La20;") && name.equals("v") && before.getParameterTypes().equals(Arrays.asList("I"))) name = "preface95OriginalStop";
                if (cls.getType().equals("Lg7;") && name.equals("b") && before.getParameterTypes().isEmpty()) name = "preface95OriginalInitialized";
                if (cls.getType().equals(JSON_SERVICE) && name.equals("onBind")) name = "preface95OriginalBind";
                if (cls.getType().equals(JSON_SERVICE) && name.equals("onDestroy")) name = "preface95OriginalDestroy";
                Method after = findMethod(rewritten, new ImmutableMethodReference(cls.getType(), name,
                        before.getParameterTypes(), before.getReturnType()));
                if (after == null || before.getAccessFlags() != after.getAccessFlags() || !body(before).equals(body(after))) {
                    throw new IllegalStateException("Unrelated original method changed: " + key(before));
                }
                checked++;
            }
        }
        System.out.println("Original method bodies and exception ranges preserved: " + checked + " (3 intentional hook bodies excluded)");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("original.dex payload.dex output.dex");
        Opcodes opcodes = Opcodes.forApi(28);
        DexBackedDexFile input = new DexBackedDexFile(opcodes, Files.readAllBytes(Path.of(args[0])));
        DexBackedDexFile addition = new DexBackedDexFile(opcodes, Files.readAllBytes(Path.of(args[1])));
        Map<String, ClassDef> original = classes(input), payload = classes(addition);
        verifyPayload(original, payload);
        List<ClassDef> output = new ArrayList<>();
        int guides = 0, statuses = 0, creates = 0, stops = 0, traces = 0, inits = 0, binds = 0, destroys = 0;
        for (ClassDef cls : original.values()) {
            List<Method> methods = new ArrayList<>();
            for (Method method : cls.getMethods()) {
                List<? extends CharSequence> params = method.getParameterTypes();
                if (cls.getType().equals("Loo;") && method.getName().equals("<init>") &&
                        params.equals(Arrays.asList(GUIDE))) {
                    // Instance constructor: p0=this, p1=guide; inject at return
                    // using only p1, after original constructor work completes.
                    methods.add(withCode(method, method.getName(), inject(method, "onGuide", Arrays.asList(GUIDE), 1, true)));
                    guides++;
                } else if (cls.getType().equals(PROTOCOL) &&
                        (method.getName().equals("dispatchAutoStatus") || method.getName().equals("sendAutoStatus")) &&
                        params.equals(Arrays.asList("I", "I"))) {
                    methods.add(withCode(method, method.getName(), inject(method, "onAutoStatus", Arrays.asList("I"), 2, false)));
                    statuses++;
                } else if (cls.getType().equals("La20;") && method.getName().equals("i") &&
                        params.equals(Arrays.asList(CONFIG)) && method.getReturnType().equals("V")) {
                    methods.add(withCode(method, "preface95OriginalCreate", method.getImplementation()));
                    methods.add(withCode(method, "i", wrapper("tryCreate", CONFIG, "preface95OriginalCreate")));
                    creates++;
                } else if (cls.getType().equals("La20;") && method.getName().equals("v") &&
                        params.equals(Arrays.asList("I")) && method.getReturnType().equals("V")) {
                    methods.add(withCode(method, "preface95OriginalStop", method.getImplementation()));
                    methods.add(withCode(method, "v", wrapper("tryStop", "I", "preface95OriginalStop")));
                    stops++;
                } else if (cls.getType().equals("Lcz;") && method.getName().equals("l") &&
                        params.equals(Arrays.asList("Ljava/lang/String;", "Ljava/lang/String;", "[Ljava/lang/Object;")) &&
                        method.getReturnType().equals("V") && (method.getAccessFlags() & 8) != 0) {
                    methods.add(withCode(method, "preface95OriginalLog", method.getImplementation()));
                    methods.add(withCode(method, "l", startupLog()));
                    traces++;
                } else if (cls.getType().equals("Lg7;") && method.getName().equals("b") && params.isEmpty() && method.getReturnType().equals("V")) {
                    methods.add(withCode(method, "preface95OriginalInitialized", method.getImplementation()));
                    methods.add(withCode(method, "b", initialized(method)));
                    inits++;
                } else if (cls.getType().equals(JSON_SERVICE) && method.getName().equals("onBind") && params.equals(Arrays.asList(INTENT))) {
                    methods.add(withCode(method, "preface95OriginalBind", method.getImplementation()));
                    methods.add(withCode(method, "onBind", bind()));
                    binds++;
                } else if (cls.getType().equals(JSON_SERVICE) && method.getName().equals("onDestroy") && params.isEmpty()) {
                    methods.add(withCode(method, "preface95OriginalDestroy", method.getImplementation()));
                    methods.add(withCode(method, "onDestroy", destroy()));
                    destroys++;
                } else methods.add(method);
            }
            if (cls.getType().equals(JSON_SERVICE)) {
                for (Method method : cls.getMethods()) if (method.getName().equals("onUnbind"))
                    throw new IllegalArgumentException("Unexpected existing JSON onUnbind");
                methods.add(unbind());
            }
            output.add(new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(),
                    cls.getInterfaces(), cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods));
        }
        if (guides != 1 || statuses != 2 || creates != 1 || stops != 1 || traces != 1 || inits != 1 || binds != 1 || destroys != 1)
            throw new IllegalArgumentException("Hook count mismatch");
        output.addAll(payload.values());
        DexPool.writeTo(args[2], new ImmutableDexFile(opcodes, output));
        DexBackedDexFile checked = new DexBackedDexFile(opcodes, Files.readAllBytes(Path.of(args[2])));
        if (checked.getClasses().size() != original.size() + payload.size()) throw new IllegalStateException("Class count changed");
        verifyPreserved(original, classes(checked));
        System.out.println("Candidate DEX: verified original vendor references; guide=1 status=2 create=1 stop=1 trace=1 init=1 bind=1 destroy=1 unbind=1; base startup, OEM JSON Binder and signature bodies preserved; no APK generated");
    }
}
