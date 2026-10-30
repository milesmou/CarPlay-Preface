import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.value.*;
import com.android.tools.smali.dexlib2.ValueType;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Read-only method comparison; emits metadata/hashes, never vendor code. */
public final class AuditApkDex {
    static String digest(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(bytes);
    }

    static Map<String, Method> methods(ClassDef cls) {
        Map<String, Method> result = new TreeMap<>();
        for (Method method : cls.getMethods()) result.put(PrepareHintDex.key(method), method);
        return result;
    }

    static Map<String, Field> fields(ClassDef cls) {
        Map<String, Field> result = new TreeMap<>();
        for (Field field : cls.getFields()) result.put(field.getName() + ":" + field.getType(), field);
        return result;
    }

    static boolean encodedEqual(EncodedValue a, EncodedValue b) {
        // DEX omits trailing false/zero/null static initializers. A rewrite
        // may encode them explicitly without changing runtime defaults.
        if (defaultValue(a) && defaultValue(b)) return true;
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    static boolean defaultValue(EncodedValue v) {
        if (v == null) return true;
        switch (v.getValueType()) {
            case ValueType.NULL: return true;
            case ValueType.BOOLEAN: return !((BooleanEncodedValue)v).getValue();
            case ValueType.BYTE: return ((ByteEncodedValue)v).getValue() == 0;
            case ValueType.SHORT: return ((ShortEncodedValue)v).getValue() == 0;
            case ValueType.CHAR: return ((CharEncodedValue)v).getValue() == 0;
            case ValueType.INT: return ((IntEncodedValue)v).getValue() == 0;
            case ValueType.LONG: return ((LongEncodedValue)v).getValue() == 0;
            case ValueType.FLOAT: return Float.floatToRawIntBits(((FloatEncodedValue)v).getValue()) == 0;
            case ValueType.DOUBLE: return Double.doubleToRawLongBits(((DoubleEncodedValue)v).getValue()) == 0;
            default: return false;
        }
    }

    static boolean fieldEqual(Field a, Field b) {
        return a.getAccessFlags() == b.getAccessFlags() && a.getAnnotations().equals(b.getAnnotations()) &&
                a.getHiddenApiRestrictions().equals(b.getHiddenApiRestrictions()) &&
                encodedEqual(a.getInitialValue(), b.getInitialValue());
    }

    static List<String> opcodes(Method method) {
        List<String> result = new ArrayList<>();
        if (method.getImplementation() != null)
            for (com.android.tools.smali.dexlib2.iface.instruction.Instruction i : method.getImplementation().getInstructions())
                result.add(i.getOpcode().name());
        return result;
    }

    static boolean methodContractEqual(Method a, Method b) {
        if (a.getAccessFlags() != b.getAccessFlags() || !a.getAnnotations().equals(b.getAnnotations()) ||
                !a.getHiddenApiRestrictions().equals(b.getHiddenApiRestrictions())) return false;
        for (int i = 0; i < a.getParameters().size(); i++) {
            if (!a.getParameters().get(i).getAnnotations().equals(b.getParameters().get(i).getAnnotations())) return false;
        }
        return true;
    }

    static Set<String> difference(Set<String> a, Set<String> b) {
        Set<String> result = new TreeSet<>(a); result.removeAll(b); return result;
    }

    static Map<String, Object> compare(Map<String, ClassDef> before, Map<String, ClassDef> after) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("classesBefore", before.size()); result.put("classesAfter", after.size());
        result.put("addedClasses", difference(after.keySet(), before.keySet()));
        result.put("removedClasses", difference(before.keySet(), after.keySet()));
        List<Object> changedClasses = new ArrayList<>();
        int equalBodies = 0, changedBodies = 0, addedMethods = 0, removedMethods = 0;
        for (String name : new TreeSet<>(before.keySet())) {
            if (!after.containsKey(name)) continue;
            ClassDef a = before.get(name), b = after.get(name);
            Map<String, Object> change = new LinkedHashMap<>(); change.put("class", name);
            boolean headerChanged = a.getAccessFlags() != b.getAccessFlags() ||
                    !Objects.equals(a.getSuperclass(), b.getSuperclass()) ||
                    !a.getInterfaces().equals(b.getInterfaces()) || !a.getAnnotations().equals(b.getAnnotations());
            if (headerChanged) {
                change.put("headerChanged", true);
                change.put("superclassBefore", a.getSuperclass()); change.put("superclassAfter", b.getSuperclass());
                change.put("flagsBefore", a.getAccessFlags()); change.put("flagsAfter", b.getAccessFlags());
            }
            Map<String, Field> af = fields(a), bf = fields(b);
            List<String> changedFields = new ArrayList<>();
            for (String key : af.keySet()) if (bf.containsKey(key) && !fieldEqual(af.get(key), bf.get(key))) changedFields.add(key);
            Set<String> newFields = difference(bf.keySet(), af.keySet()), oldFields = difference(af.keySet(), bf.keySet());
            if (!newFields.isEmpty()) change.put("addedFields", newFields);
            if (!oldFields.isEmpty()) change.put("removedFields", oldFields);
            if (!changedFields.isEmpty()) change.put("changedFields", changedFields);
            Map<String, Method> am = methods(a), bm = methods(b);
            Set<String> newMethods = difference(bm.keySet(), am.keySet()), oldMethods = difference(am.keySet(), bm.keySet());
            addedMethods += newMethods.size(); removedMethods += oldMethods.size();
            if (!newMethods.isEmpty()) change.put("addedMethods", newMethods);
            if (!oldMethods.isEmpty()) change.put("removedMethods", oldMethods);
            List<Object> changed = new ArrayList<>();
            for (String key : am.keySet()) {
                if (!bm.containsKey(key)) continue;
                Method first = am.get(key), second = bm.get(key);
                String bodyA = PrepareHintDex.body(first), bodyB = PrepareHintDex.body(second);
                boolean bodyChanged = !bodyA.equals(bodyB), contractChanged = !methodContractEqual(first, second);
                if (bodyChanged) changedBodies++; else equalBodies++;
                if (bodyChanged || contractChanged) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("method", key); item.put("bodyChanged", bodyChanged); item.put("contractChanged", contractChanged);
                    item.put("bodyBeforeSha256", digest(bodyA)); item.put("bodyAfterSha256", digest(bodyB));
                    if (name.equals("Lcom/alibaba/wireless/security/framework/e;") && first.getName().equals("c")) {
                        item.put("opcodesBefore", opcodes(first)); item.put("opcodesAfter", opcodes(second));
                    }
                    changed.add(item);
                }
            }
            if (!changed.isEmpty()) change.put("changedMethods", changed);
            if (change.size() > 1) changedClasses.add(change);
        }
        result.put("changedClasses", changedClasses); result.put("equalCommonMethodBodies", equalBodies);
        result.put("changedCommonMethodBodies", changedBodies); result.put("addedMethodsInCommonClasses", addedMethods);
        result.put("removedMethodsInCommonClasses", removedMethods);
        result.put("debugLineTablesIgnored", true);
        result.put("comparisonIncludesRegistersOperandsBranchesAndTryRanges", true);
        result.put("compilerEquivalentInstructionRewritesNotNormalized", true);
        result.put("omittedAndExplicitDefaultStaticValuesTreatedAsEqual", true);
        return result;
    }

    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?>) {
            List<String> items = new ArrayList<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) items.add(json(entry.getKey()) + ":" + json(entry.getValue()));
            return "{" + String.join(",", items) + "}";
        }
        if (value instanceof Iterable<?>) {
            List<String> items = new ArrayList<>(); for (Object item : (Iterable<?>) value) items.add(json(item));
            return "[" + String.join(",", items) + "]";
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32) out.append(String.format("\\u%04x", (int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("before.dex after.dex report.json");
        Opcodes opcodes = Opcodes.forApi(28);
        Map<String, ClassDef> a = PrepareHintDex.classes(new DexBackedDexFile(opcodes, Files.readAllBytes(Path.of(args[0]))));
        Map<String, ClassDef> b = PrepareHintDex.classes(new DexBackedDexFile(opcodes, Files.readAllBytes(Path.of(args[1]))));
        Map<String, Object> result = compare(a, b);
        Files.writeString(Path.of(args[2]), json(result));
        System.out.println("Read-only DEX comparison: common bodies changed=" + result.get("changedCommonMethodBodies") +
                ", added classes=" + ((Set<?>)result.get("addedClasses")).size());
    }
}
