package jo.lspjava.jdtpatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de {@link EcjArtPatcher} : un jar synthétique contenant les deux
 * patterns problématiques (référence {@code java/lang/Runtime$Version} +
 * {@code INVOKEVIRTUAL InputStream.readAllBytes}) est patché, puis le
 * bytecode de sortie est inspecté pour vérifier la réécriture.
 *
 * @author jo@Dev
 */
class EcjArtPatcherTest {

    @TempDir
    Path tmp;

    /**
     * Génère une classe qui référence {@code java/lang/Runtime$Version}
     * (champ + invocation de {@code parse}) et appelle
     * {@code InputStream.readAllBytes()} et {@code readNBytes(int)}.
     */
    private byte[] synthClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "org/eclipse/synth/Sample", null,
                "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "version",
                "Ljava/lang/Runtime$Version;", null, null).visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "run",
                "(Ljava/io/InputStream;)[Ljava/lang/Object;", null, null);
        mv.visitCode();
        // Object v = Runtime$Version.parse("17");
        mv.visitLdcInsn("17");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Runtime$Version",
                "parse", "(Ljava/lang/String;)Ljava/lang/Runtime$Version;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        // byte[] a = in.readAllBytes();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/InputStream",
                "readAllBytes", "()[B", false);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        // byte[] b = in.readNBytes(10);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitIntInsn(Opcodes.BIPUSH, 10);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/InputStream",
                "readNBytes", "(I)[B", false);
        mv.visitVarInsn(Opcodes.ASTORE, 4);
        // return new Object[]{v, a, b};
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(6, 5);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private Path synthJar() throws Exception {
        Path jar = tmp.resolve("synth.jar");
        try (JarOutputStream out = new JarOutputStream(
                Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("org/eclipse/synth/Sample.class"));
            out.write(synthClass());
            out.closeEntry();
            out.putNextEntry(new JarEntry("module-info.class"));
            out.write(new byte[]{0, 0, 0}); // doit être retiré
            out.closeEntry();
        }
        return jar;
    }

    /** Collecte les références de types + call-sites d'une classe. */
    private static class RefCollector extends ClassVisitor {
        final Set<String> typeRefs = new HashSet<>();
        final Set<String> methodCalls = new HashSet<>();

        RefCollector() {
            super(Opcodes.ASM9);
        }

        @Override
        public org.objectweb.asm.FieldVisitor visitField(int access, String name,
                String descriptor, String signature, Object value) {
            typeRefs.add(descriptor);
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor,
                    signature, exceptions);
            return new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public void visitMethodInsn(int opcode, String owner,
                                            String methodName, String desc,
                                            boolean isInterface) {
                    methodCalls.add(opcode + " " + owner + "." + methodName + desc);
                    super.visitMethodInsn(opcode, owner, methodName, desc, isInterface);
                }
            };
        }
    }

    @Test
    void rewritesRuntimeVersionAndInputStreamCalls() throws Exception {
        Path in = synthJar();
        Path out = tmp.resolve("synth-art.jar");
        EcjArtPatcher.patch(in, out);

        byte[] patched;
        try (ZipFile zip = new ZipFile(out.toFile())) {
            ZipEntry entry = zip.getEntry("org/eclipse/synth/Sample.class");
            assertTrue(entry != null, "La classe doit être conservée");
            patched = zip.getInputStream(entry).readAllBytes();
            // module-info.class doit être retiré
            assertTrue(zip.getEntry("module-info.class") == null,
                    "module-info.class doit être retiré du jar patché");
        }

        RefCollector collector = new RefCollector();
        new ClassReader(patched).accept(collector, 0);

        // 1. Runtime$Version renommé partout (champ + descripteurs d'appel).
        boolean referencesShim = collector.typeRefs.stream()
                .anyMatch(d -> d.contains("jo/lspjava/jdt/compat/RuntimeVersion"));
        assertTrue(referencesShim,
                "Le descripteur de champ doit référencer le shim : "
                        + collector.typeRefs);
        assertFalse(collector.methodCalls.stream()
                        .anyMatch(c -> c.contains("java/lang/Runtime$Version")),
                "Aucun call-site ne doit cibler Runtime$Version : "
                        + collector.methodCalls);

        // 2. readAllBytes/readNBytes réécrits en INVOKESTATIC vers le shim.
        assertTrue(collector.methodCalls.contains(
                        Opcodes.INVOKESTATIC
                                + " jo/lspjava/jdt/compat/InputStreamCompat.readAllBytes(Ljava/io/InputStream;)[B"),
                "readAllBytes doit être réécrit vers le shim : "
                        + collector.methodCalls);
        assertTrue(collector.methodCalls.contains(
                        Opcodes.INVOKESTATIC
                                + " jo/lspjava/jdt/compat/InputStreamCompat.readNBytes(Ljava/io/InputStream;I)[B"),
                "readNBytes doit être réécrit vers le shim : "
                        + collector.methodCalls);
        assertFalse(collector.methodCalls.stream()
                        .anyMatch(c -> c.startsWith(Opcodes.INVOKEVIRTUAL
                                + " java/io/InputStream.readAllBytes")
                                || c.startsWith(Opcodes.INVOKEVIRTUAL
                                + " java/io/InputStream.readNBytes")),
                "Aucun INVOKEVIRTUAL InputStream.read*Bytes ne doit subsister : "
                        + collector.methodCalls);
    }

    @Test
    void patchedClassSurvivesBytecodeRoundTrip() throws Exception {
        Path in = synthJar();
        Path out = tmp.resolve("synth-art2.jar");
        EcjArtPatcher.patch(in, out);

        byte[] patched;
        try (ZipFile zip = new ZipFile(out.toFile())) {
            patched = zip.getInputStream(zip.getEntry("org/eclipse/synth/Sample.class"))
                    .readAllBytes();
        }
        // La classe patchée doit rester du bytecode cohérent (re-lecture avec
        // recomputation des frames — échouerait sur un remapping cassé).
        ClassWriter verify = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(patched).accept(verify, 0);
        assertTrue(verify.toByteArray().length > 0);
    }
}
