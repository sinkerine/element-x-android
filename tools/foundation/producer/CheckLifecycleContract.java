/* Copyright 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 */
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.Type;
import org.objectweb.asm.Handle;
import org.objectweb.asm.ConstantDynamic;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;
import org.objectweb.asm.util.TraceMethodVisitor;

/** Checks unchanged linkage/metadata and restricts code changes to the approved lifecycle methods.
 * Never edits classfile bytes. Original non-lifecycle classes must be retained by packaging.
 */
public final class CheckLifecycleContract {
    private static final String BASE = "androidx/compose/foundation/gestures/AnchoredDraggableState";
    private static final String OBSOLETE = BASE + "$anchoredDrag$3";
    private static final List<String> PAYLOAD = List.of(BASE, BASE + "$anchoredDrag$2", BASE + "$anchoredDrag$4");

    private static ClassNode read(ZipFile jar, String name, int flags) throws Exception {
        var node = new ClassNode();
        var entry = jar.getEntry(name + ".class");
        if (entry == null) throw new IllegalStateException("Missing lifecycle class " + name);
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, flags);
        return node;
    }

    private static void debugPositions(List<AnnotationNode> annotations) {
        if (annotations == null) return;
        annotations.removeIf(annotation -> annotation.desc.equals("Lkotlin/jvm/internal/SourceDebugExtension;"));
        for (var annotation : annotations) {
            if (!annotation.desc.equals("Lkotlin/coroutines/jvm/internal/DebugMetadata;") || annotation.values == null) continue;
            for (int i = 0; i < annotation.values.size(); i += 2) {
                // Keep all coroutine class/method/spill metadata except permitted source line positions.
                if (annotation.values.get(i).equals("l")) annotation.values.set(i + 1, List.of());
            }
        }
    }

    private static String header(ClassNode node) {
        debugPositions(node.visibleAnnotations);
        debugPositions(node.invisibleAnnotations);
        node.innerClasses.removeIf(inner -> inner.name.equals(OBSOLETE));
        var output = new StringWriter();
        node.accept(new TraceClassVisitor(new PrintWriter(output)));
        return output.toString();
    }

    private static String method(MethodNode node) {
        var printer = new Textifier();
        node.accept(new TraceMethodVisitor(printer));
        var output = new StringWriter();
        printer.print(new PrintWriter(output));
        return output.toString();
    }

    private static void constant(Object value, TreeSet<String> output) {
        if (value instanceof Type) output.add("constant-type " + ((Type) value).getDescriptor());
        if (value instanceof Handle) {
            var handle = (Handle) value;
            output.add("handle " + handle.getTag() + " " + handle.getOwner() + "." + handle.getName() + handle.getDesc());
        }
        if (value instanceof ConstantDynamic) {
            var dynamic = (ConstantDynamic) value;
            output.add("dynamic " + dynamic.getName() + dynamic.getDescriptor());
            constant(dynamic.getBootstrapMethod(), output);
            for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) constant(dynamic.getBootstrapMethodArgument(i), output);
        }
    }

    private static void references(ClassNode node, TreeSet<String> output) {
        for (var method : node.methods) {
          for (var handler : method.tryCatchBlocks) if (handler.type != null) output.add("catch " + handler.type);
          for (var instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode) {
                var field = (FieldInsnNode) instruction;
                output.add("field " + field.owner + "." + field.name + field.desc);
            } else if (instruction instanceof MethodInsnNode) {
                var call = (MethodInsnNode) instruction;
                output.add("method " + call.owner + "." + call.name + call.desc);
            } else if (instruction instanceof TypeInsnNode) {
                output.add("type " + ((TypeInsnNode) instruction).desc);
            } else if (instruction instanceof LdcInsnNode) {
                constant(((LdcInsnNode) instruction).cst, output);
            } else if (instruction instanceof InvokeDynamicInsnNode) {
                var dynamic = (InvokeDynamicInsnNode) instruction;
                output.add("invoke-dynamic " + dynamic.name + dynamic.desc);
                constant(dynamic.bsm, output);
                for (Object argument : dynamic.bsmArgs) constant(argument, output);
            }
          }
        }
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[2]);
        Files.createDirectories(output);
        var changed = new ArrayList<String>();
        var originalReferences = new TreeSet<String>();
        var candidateReferences = new TreeSet<String>();
        try (var original = new ZipFile(args[0]); var candidate = new ZipFile(args[1])) {
            for (String name : PAYLOAD) {
                references(read(original, name, ClassReader.SKIP_DEBUG), originalReferences);
                references(read(candidate, name, ClassReader.SKIP_DEBUG), candidateReferences);
                String oldHeader = header(read(original, name, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES));
                String newHeader = header(read(candidate, name, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES));
                Files.writeString(output.resolve(name.substring(name.lastIndexOf('/') + 1) + ".original-header.txt"), oldHeader);
                Files.writeString(output.resolve(name.substring(name.lastIndexOf('/') + 1) + ".candidate-header.txt"), newHeader);
                if (!oldHeader.equals(newHeader)) throw new IllegalStateException("Lifecycle declarations/metadata differ: " + name);
                var oldMethods = new TreeMap<String, String>();
                var newMethods = new TreeMap<String, String>();
                for (var m : read(original, name, ClassReader.SKIP_DEBUG).methods) oldMethods.put(m.name + m.desc, method(m));
                for (var m : read(candidate, name, ClassReader.SKIP_DEBUG).methods) newMethods.put(m.name + m.desc, method(m));
                if (!oldMethods.keySet().equals(newMethods.keySet())) throw new IllegalStateException("Member set differs: " + name);
                for (String key : oldMethods.keySet()) {
                    if (oldMethods.get(key).equals(newMethods.get(key))) continue;
                    boolean allowed = name.equals(BASE)
                        ? key.equals("anchoredDrag(Ljava/lang/Object;Landroidx/compose/foundation/MutatePriority;Lkotlin/jvm/functions/Function4;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;")
                        : key.equals("invokeSuspend(Ljava/lang/Object;)Ljava/lang/Object;");
                    if (!allowed) throw new IllegalStateException("Unexpected method code difference: " + name + "." + key);
                    changed.add(name + "." + key);
                    String stem = name.substring(name.lastIndexOf('/') + 1) + "-" + changed.size();
                    Files.writeString(output.resolve(stem + ".original-code.txt"), oldMethods.get(key));
                    Files.writeString(output.resolve(stem + ".candidate-code.txt"), newMethods.get(key));
                }
            }
        }
        if (changed.size() != 3) throw new IllegalStateException("Expected exactly three lifecycle method changes: " + changed);
        Files.write(output.resolve("original-references.txt"), originalReferences);
        Files.write(output.resolve("candidate-references.txt"), candidateReferences);
        var addedReferences = new TreeSet<String>(candidateReferences);
        addedReferences.removeAll(originalReferences);
        Files.write(output.resolve("added-references.txt"), addedReferences);
        if (!addedReferences.isEmpty()) throw new IllegalStateException("New symbolic references require review: " + addedReferences);
        Files.write(output.resolve("changed-methods.txt"), changed);
        System.out.println("Lifecycle linkage/metadata and method-scope contract PASS; original other classes must be retained");
    }
}
