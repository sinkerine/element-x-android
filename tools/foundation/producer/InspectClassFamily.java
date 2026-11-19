/* Copyright 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 */
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.TraceClassVisitor;

/** Source attribution plus enclosing/nest closure; SKIP_DEBUG is the only trace normalization. */
public final class InspectClassFamily {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        Map<String, ClassNode> headers = new LinkedHashMap<>();
        Set<String> family = new LinkedHashSet<>();
        try (ZipFile archive = new ZipFile(args[0])) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                byte[] data = archive.getInputStream(entry).readAllBytes();
                ClassNode header = new ClassNode();
                new ClassReader(data).accept(header, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
                if (headers.put(header.name, header) != null) throw new IllegalStateException("Duplicate class " + header.name);
                bytes.put(header.name, data);
                if ("AnchoredDraggable.kt".equals(header.sourceFile)) family.add(header.name);
            }
        }
        boolean changed;
        do {
            int before = family.size();
            for (ClassNode header : headers.values()) {
                if (family.contains(header.outerClass) || family.contains(header.nestHostClass)) family.add(header.name);
                if (family.contains(header.name) && header.nestMembers != null) family.addAll(header.nestMembers);
                for (var inner : header.innerClasses) {
                    if (family.contains(inner.outerName)) family.add(inner.name);
                }
            }
            changed = before != family.size();
        } while (changed);
        var names = new ArrayList<String>();
        for (String name : family) {
            if (!bytes.containsKey(name)) throw new IllegalStateException("Missing enclosed family class " + name);
            names.add(name + ".class");
            StringWriter normalized = new StringWriter();
            new ClassReader(bytes.get(name)).accept(new TraceClassVisitor(new PrintWriter(normalized)), ClassReader.SKIP_DEBUG);
            Path target = output.resolve(name + ".class.txt");
            Files.createDirectories(target.getParent());
            Files.writeString(target, normalized.toString(), StandardCharsets.UTF_8);
        }
        names.sort(Comparator.naturalOrder());
        Files.write(output.resolve("classes.txt"), names, StandardCharsets.UTF_8);
        if (names.isEmpty()) throw new IllegalStateException("No source-attributed class family found");
        System.out.println("Source/enclosing/nest family: " + names.size() + " classes");
    }
}
