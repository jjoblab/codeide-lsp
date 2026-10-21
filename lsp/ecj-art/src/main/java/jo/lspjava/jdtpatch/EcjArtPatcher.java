package jo.lspjava.jdtpatch;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Réécrit un jar Eclipse pour le runtime Android (ART, minSdk 26).
 *
 * <p>Trois transformations, appliquées en un seul passage ASM par classe :</p>
 * <ol>
 *   <li><b>Renommages de types</b> (paramétrables) — chaque entrée de la
 *       carte {@code nomInterne → shim} est appliquée via
 *       {@link ClassRemapper}, qui couvre toutes les formes de références
 *       (super-types, descripteurs de champs/méthodes, signatures
 *       génériques, stack-frames). Les renommages standard d'Eclipse :</li>
 *   <ul>
 *     <li>{@code java/lang/Runtime$Version} → shim
 *         {@code jo/lspjava/ecj/compat/ArtCompatVersion} — absent d'ART,
 *         non stubbable ({@code java.*}), référencé par le Parser d'ecj ;</li>
 *     <li>{@code java/lang/StackWalker} → shim
 *         {@code jo/lspjava/ecj/compat/ArtCompatStackWalker} — absent
 *         d'ART, référencé par les {@code <clinit>} de
 *         {@code org.eclipse.core.runtime.Status} et
 *         {@code InternalPlatform} (le renommage couvre aussi
 *         {@code StackWalker$Option} par préfixe).</li>
 *   </ul>
 *   <li><b>Réécriture de call-sites</b> — pour les classes
 *       {@code org.eclipse.*} uniquement : {@code INVOKEVIRTUAL
 *       java/io/InputStream.readAllBytes ()[B} (et {@code readNBytes (I)[B},
 *       Java 9, absents d'Android avant l'API 33) → {@code INVOKESTATIC
 *       jo/lspjava/ecj/compat/ArtCompatInputStream.*}. Le receveur devient le
 *       premier argument — l'effet de pile est identique.</li>
 *   <li><b>Nettoyage</b> — les entrées inutiles ou gênantes pour le dexing
 *       et le packaging APK sont retirées : {@code module-info.class},
 *       entrées multi-release ({@code META-INF/versions/**}), signatures
 *       JAR, méta-données OSGi et documentation embarquée (liste calquée
 *       sur les excludes packaging du projet tiers de référence).</li>
 * </ol>
 *
 * <p>Les renommages sont <em>inoffensifs quand la référence est absente</em>
 * (le remapper ne fait rien) et les shims sont inoffensifs sur une JVM
 * complète — le même jar patché sert donc desktop et appareil.</p>
 *
 * <p>Usage : {@code java EcjArtPatcher <input.jar> <output.jar>
 * [ancien=nouveau]...} — sans renommages supplémentaires, les deux
 * renommages Eclipse standard sont appliqués. Exécuté par la tâche Gradle
 * {@code :lspjava:patchEclipseForArt} pour chaque jar du graphe
 * {@code org.eclipse.jdt:org.eclipse.jdt.core}.</p>
 *
 * @author jo@Dev
 */
public final class EcjArtPatcher {

    /** Renommages Eclipse standard — appliqués à tous les jars du graphe. */
    public static final Map<String, String> ECLIPSE_RENAMES = Map.of(
            "java/lang/Runtime$Version", "jo/lspjava/ecj/compat/ArtCompatVersion",
            "java/lang/StackWalker", "jo/lspjava/ecj/compat/ArtCompatStackWalker");

    private static final String INPUT_STREAM = "java/io/InputStream";
    private static final String INPUT_STREAM_SHIM =
            "jo/lspjava/ecj/compat/ArtCompatInputStream";

    private EcjArtPatcher() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: EcjArtPatcher <input.jar> <output.jar> [ancien=nouveau]...");
            System.exit(2);
        }
        Map<String, String> renames = new LinkedHashMap<>(ECLIPSE_RENAMES);
        for (int i = 2; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq <= 0) {
                System.err.println("Renommage invalide (attendu ancien=nouveau) : " + args[i]);
                System.exit(2);
            }
            renames.put(args[i].substring(0, eq), args[i].substring(eq + 1));
        }
        patch(Path.of(args[0]), Path.of(args[1]), renames);
    }

    /** Patche {@code input} vers {@code output} avec les renommages Eclipse
     *  standard (compatibilité avec l'API historique à deux arguments). */
    public static void patch(Path input, Path output) throws IOException {
        patch(input, output, ECLIPSE_RENAMES);
    }

    /** Patche {@code input} vers {@code output} (créé avec ses parents). */
    public static void patch(Path input, Path output,
                             Map<String, String> renames) throws IOException {
        if (Files.notExists(input)) {
            throw new IOException("Jar source introuvable : " + input);
        }
        Files.createDirectories(output.getParent());

        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                // Correspondance exacte d'abord.
                String exact = renames.get(internalName);
                if (exact != null) return exact;
                // Les classes IMBRIQUÉES suivent leur type englobant :
                // java/lang/StackWalker$Option suit le renommage de
                // java/lang/StackWalker (pattern préfixe de RelocateTypesInJar
                // chez le projet tiers de référence). Sans ça, les descripteurs resteraient
                // mitigés → NoSuchMethodError au premier <clinit>.
                int dollar = internalName.lastIndexOf('$');
                while (dollar > 0) {
                    String outer = internalName.substring(0, dollar);
                    String target = renames.get(outer);
                    if (target != null) {
                        return target + internalName.substring(dollar);
                    }
                    dollar = internalName.lastIndexOf('$', dollar - 1);
                }
                return internalName;
            }
        };

        int classes = 0;
        try (ZipFile zip = new ZipFile(input.toFile());
             OutputStream os = Files.newOutputStream(output);
             JarOutputStream out = new JarOutputStream(os)) {

            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (isStripped(name)) {
                    continue;
                }
                out.putNextEntry(new JarEntry(name));
                if (name.endsWith(".class")) {
                    byte[] bytes = readAll(zip.getInputStream(entry));
                    String classFqn = name.substring(0, name.length() - 6)
                            .replace('/', '.');
                    out.write(rewrite(bytes, remapper, classFqn));
                    classes++;
                } else {
                    transfer(zip.getInputStream(entry), out);
                }
                out.closeEntry();
            }
        }
        System.out.println("[eclipse-art] " + classes + " classes patchées → " + output);
    }

    /**
     * Réécrit une classe : renommages (toutes les classes) + call-sites
     * InputStream (classes {@code org.eclipse.*} seulement — le scope du
     * pattern {@code EcjInputStreamArtPass} du projet tiers de référence).
     */
    static byte[] rewrite(byte[] classBytes, Remapper remapper, String classFqn) {
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(0);
        ClassVisitor chain = classFqn.startsWith("org.eclipse.")
                ? new InputStreamRewriter(new ClassRemapper(writer, remapper))
                : new ClassRemapper(writer, remapper);
        reader.accept(chain, 0);
        return writer.toByteArray();
    }

    /**
     * Réécrit les {@code INVOKEVIRTUAL InputStream.readAllBytes/readNBytes}
     * en {@code INVOKESTATIC InputStreamCompat.*}. Le receveur devient le
     * premier argument — l'effet de pile est inchangé.
     */
    static final class InputStreamRewriter extends ClassVisitor {

        InputStreamRewriter(ClassVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String methodName,
                                            String methodDescriptor, boolean isInterface) {
                    if (opcode == Opcodes.INVOKEVIRTUAL && INPUT_STREAM.equals(owner)) {
                        if ("readAllBytes".equals(methodName) && "()[B".equals(methodDescriptor)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, INPUT_STREAM_SHIM,
                                    "readAllBytes", "(L" + INPUT_STREAM + ";)[B", false);
                            return;
                        }
                        if ("readNBytes".equals(methodName) && "(I)[B".equals(methodDescriptor)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, INPUT_STREAM_SHIM,
                                    "readNBytes", "(L" + INPUT_STREAM + ";I)[B", false);
                            return;
                        }
                    }
                    super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                }
            };
        }
    }

    /**
     * @return true pour les entrées retirées du jar patché — méta-données
     * OSGi/Eclipse, signatures, multi-release, doc embarquée (liste calquée
     * sur les excludes packaging du projet tiers de référence ; évite les collisions de
     * ressources au merge APK et allège le dexing).
     */
    private static boolean isStripped(String name) {
        if (name.equals("module-info.class")) return true;
        if (name.startsWith("META-INF/versions/")) return true;
        if (name.startsWith("META-INF/")) {
            // Garde META-INF/services/** (ServiceLoader) ; strip le reste.
            if (name.startsWith("META-INF/services/")) return false;
            if (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA")) return true;
            if (name.equals("META-INF/MANIFEST.MF")) return true;
            if (name.equals("META-INF/eclipse.inf")) return true;
            if (name.startsWith("META-INF/ECLIPSE_")) return true;
            return true;
        }
        if (name.startsWith("OSGI-INF/") || name.startsWith("OSGI-OPT/")) return true;
        if (name.startsWith("about_files/")) return true;
        if (name.equals("plugin.xml") || name.equals("plugin.properties")) return true;
        if (name.equals("fragment.xml") || name.equals("fragment.properties")) return true;
        if (name.startsWith("about.")) return true;
        if (name.equals("systembundle.properties") || name.equals("profile.list")) return true;
        if (name.equals(".options") || name.equals(".api_description")) return true;
        if (name.endsWith(".profile") || name.endsWith(".api_description")) return true;
        if (name.endsWith(".html") || name.endsWith(".md")) return true;
        if (name.endsWith(".png") || name.endsWith(".gif") || name.endsWith(".svg")) return true;
        if (name.equals("license.html") || name.equals("notice.html")
                || name.equals("epl-v10.html") || name.equals("epl-v20.html")
                || name.equals("epl-2.0.html") || name.equals("about.html")) {
            return true;
        }
        if (name.endsWith(".jnilib") || name.endsWith(".so")) return true; // natif — inutile sur ART/dex
        return false;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }

    private static void transfer(InputStream in, OutputStream out) throws IOException {
        try (in) {
            in.transferTo(out);
        }
    }
}
