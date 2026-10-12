package jo.lspjava.ecj;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import jo.lspjava.api.Diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repro du constat production (v0.107.0, retour propriétaire) :
 * « The type java.lang.invoke.StringConcatFactory cannot be resolved ».
 *
 * <p><strong>Fait établi (1)</strong> : la classe n'existe dans AUCUNE
 * plateforme Android (vérifié android-31 et android-36 : seul
 * {@code StringConcatException} y figure) — D8 désugare TOUJOURS les
 * concaténations {@code invokedynamic} vers StringBuilder, le code
 * compilé est donc LÉGAL pour Android quel que soit le compileSdk. Le
 * seul fournisseur possible de la classe est l'image <b>jrt</b> du JDK
 * du toolchain (java.base).</p>
 *
 * <p><strong>Fait établi (2) — mécanisme du message</strong> : le
 * diagnostic natif ecj « ... cannot be resolved. It is indirectly
 * referenced from required .class files » ({@code IProblem#
 * IsClassPathCorrect}) apparaît quand une classe BINAIRE du classpath
 * RÉFÉRENCE le type absent — le mécanisme prouvé par
 * {@code EcjHierarchyDiagnosticsTest} (super-classe binaire
 * manquante). La fixture reproduit le mécanisme EXACT : un .class
 * {@code Biblio} dont la super-classe est
 * {@code java.lang.invoke.StringConcatFactory} (fabriqué par ASM :
 * javac interdit les paquets scellés, ecj accepte mais ASM est
 * trivial). Sur l'appareil du propriétaire, la référence vient du
 * code compilé par le serveur Gradle (javac target ≥ 9 → chaque
 * concat laisse un {@code CONSTANT_MethodHandle} vers
 * {@code makeConcatWithConstants} dans le pool) relu par ecj pour le
 * binding des types du projet.</p>
 *
 * <p><strong>Ce que le correctif garantit</strong> : un stub EMBARQUÉ
 * des intrinsèques du compilateur ({@code StringConcatFactory} — le
 * bug rapporté ; {@code ObjectMethods} des records ;
 * {@code SwitchBootstraps} des switch pattern) répond au repli 4 de
 * {@code find} — APRÈS sources, jars et jrt — uniquement pour ces
 * FQCN : la hiérarchie binaire se referme, le diagnostic disparaît,
 * et plateforme/jrt réels RESTENT prioritaires (le stub ne les
 * masque jamais : un android.jar qui contiendrait la classe ou un
 * jdkHome chargé répondent AVANT).</p>
 *
 * <p>Skip : sans android.jar API &lt; 33 ni JDK 21 sur la machine, les
 * tests concernés sont ignorés (motif {@code AndroidClasspathReproTest}).</p>
 *
 * @author jo@Dev
 */
class StringConcatFactoryReproTest {

    /** La source éditée : elle ÉTEND le type binaire → ecj réifie la
     *  super-classe de Biblio → le message natif « ... indirectly
     *  referenced from required .class files » (IProblem.IsClassPathCorrect). */
    private static final String SOURCE_EDITEE =
            "package p;\n"
            + "\n"
            + "public class A extends batisse.Biblio {\n"
            + "}\n";

    @TempDir
    Path tmp;

    /** android.jar API 31 (SANS StringConcatFactory), candidats machine. */
    private static File findAndroidJarAvant33() {
        String[] candidats = {
                // plateforme 31 (le zip r01 de Google étiquette le dossier
                // « android-12 » — contenu réel API 31, bug d'étiquetage)
                "/home/z/android-sdk/platforms/android-31-bis/android.jar",
                "/home/z/android-sdk/platforms/android-12/android.jar",
                "/home/z/my-project/android-sdk/platforms/android-31-bis/android.jar",
                "/home/z/my-project/android-sdk/platforms/android-12/android.jar",
                "/home/z/tools/android-sdk/platforms/android-31/android.jar",
        };
        for (String c : candidats) {
            File f = new File(c);
            if (f.isFile()) return f;
        }
        return null;
    }

    /** JDK 9+ réel (image jrt complète — java.base contient la classe). */
    private static Path findJdkAvecJrt() {
        String[] candidats = {
                "/home/z/tools/jdk-21",
                "/usr/lib/jvm/java-21-openjdk-amd64",
                System.getProperty("java.home"),
        };
        for (String c : candidats) {
            if (c == null) continue;
            File modules = new File(c, "lib/modules");
            if (modules.isFile()) return Path.of(c);
        }
        return null;
    }

    /**
     * Faux JDK : répertoire SANS lib/modules → jrtAvailable = false, la
     * résolution java.* repose sur android.jar SEUL (mêmes conditions
     * que l'appareil quand l'image jrt du toolchain n'a pas chargé —
     * échec silencieux de jrt-fs, cache négatif jrtScanAttempted).
     */
    private static Path fauxJdk() throws Exception {
        return Files.createTempDirectory("jdk-factice-sans-jrt");
    }

    /**
     * Fabrique le jar de fixture : {@code Biblio.class} (ASM, bytecode 8)
     * DÉCLARÉ {@code extends java.lang.invoke.StringConcatFactory} —
     * exactement la forme d'un binaire du classpath référençant
     * l'intrinsèque absent.
     */
    private File jarBiblio() throws Exception {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(
                org.objectweb.asm.Opcodes.V1_8,
                org.objectweb.asm.Opcodes.ACC_PUBLIC,
                "batisse/Biblio",
                null, /* signature */
                "java/lang/invoke/StringConcatFactory",
                null /* interfaces */);
        // Constructeur public sans argument : sans lui, ecj publierait
        // « Implicit super constructor Biblio() is undefined » — bruit de
        // fixture sans rapport avec le mécanisme étudié (le vrai code
        // compilé a toujours ses constructeurs).
        org.objectweb.asm.MethodVisitor init =
                cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitMethodInsn(
                org.objectweb.asm.Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        cw.visitEnd();
        byte[] octets = cw.toByteArray();
        File jar = tmp.resolve("biblio.jar").toFile();
        try (OutputStream os = new JarOutputStream(
                Files.newOutputStream(jar.toPath()))) {
            ((JarOutputStream) os).putNextEntry(new ZipEntry("batisse/Biblio.class"));
            os.write(octets);
        }
        return jar;
    }

    private static boolean contient(List<Diagnostic> ds, String fragment) {
        for (Diagnostic d : ds) {
            if (d.message().contains(fragment)) return true;
        }
        return false;
    }

    /**
     * GARDE DE NON-RÉGRESSION (le REPRO historique, rouge avant v0.108.0) :
     * compliance 17 + android.jar (API &lt; 33, SANS la classe) + binaire
     * du classpath la référencant (super-classe) + jdkHome SANS image jrt
     * → le faux positif « StringConcatFactory cannot be resolved » NE
     * DOIT PLUS APPARAÎTRE (constat appareil du propriétaire — le code
     * édité est pourtant LÉGAL pour Android, D8 désugare : aucune action
     * utilisateur ne pouvait le faire disparaître avant le correctif).
     *
     * <p>Histoire vérifiable : ce test, écrit AVANT le repli 4 de
     * {@code find}, était VERT (le message apparaissait) sur ce dépôt
     * avant l'implémentation de {@code StubsIntrinseques} — commit de
     * référence du constat.</p>
     */
    @Test
    void sansJrtLeFauxPositifNeReapparaitPas() throws Exception {
        File android31 = findAndroidJarAvant33();
        Assumptions.assumeTrue(android31 != null,
                "android.jar API < 33 introuvable (plateforme 31)");
        EcjCompilerEngine moteur = new EcjCompilerEngine(
                Collections.<File>emptyList(),
                Arrays.asList(android31, jarBiblio()),
                "17",
                fauxJdk(),
                null);
        List<Diagnostic> ds = moteur.diagnose("/src/p/A.java", SOURCE_EDITEE);
        assertFalse(contient(ds, "StringConcatFactory"),
                "GARDE : le faux positif ne doit JAMAIS réapparaître sans"
                + " jrt (diagnostics : " + ds + ")");
    }

    /**
     * CONTRAT des stubs : liste FERMÉE (java.lang.String et autres types
     * de plateforme JAMAIS stubbés), octets présents pour chaque
     * intrinsèque, format CAFEBABE version 8 lisible.
     */
    @Test
    void contratDesStubsIntrinseques() {
        assertFalse(StubsIntrinseques.estIntrinseque("java.lang.String"));
        assertFalse(StubsIntrinseques.estIntrinseque("java.util.List"));
        assertTrue(StubsIntrinseques.estIntrinseque(
                "java.lang.invoke.StringConcatFactory"));
        assertTrue(StubsIntrinseques.estIntrinseque(
                "java.lang.runtime.ObjectMethods"));
        assertTrue(StubsIntrinseques.estIntrinseque(
                "java.lang.runtime.SwitchBootstraps"));
        byte[] octets = StubsIntrinseques.octets(
                "java.lang.invoke.StringConcatFactory");
        assertNotNull(octets, "le stub doit exister");
        assertEquals(0xCA, (octets[0] & 0xFF), "magic CAFEBABE (1er octet)");
        assertEquals(0xFE, (octets[1] & 0xFF), "magic CAFEBABE (2e octet)");
        assertEquals(0xBA, (octets[2] & 0xFF), "magic CAFEBABE (3e octet)");
        assertEquals(0xBE, (octets[3] & 0xFF), "magic CAFEBABE (4e octet)");
        assertEquals(0, octets[4], "version mineure (poids fort) 0");
        assertEquals(0, octets[5], "version mineure (poids faible) 0");
        assertEquals(0, octets[6], "version majeure (poids fort) 0");
        assertEquals(52, octets[7], "version majeure 52 (Java 8)");
        assertNull(StubsIntrinseques.octets("java.lang.String"),
                "hors liste : null, jamais de stub de type de plateforme");
    }

    /**
     * TÉMOIN : le MÊME classpath avec l'image jrt d'un JDK réel → la
     * classe résout (repli 3 de find) → la hiérarchie de Biblio se
     * referme → PAS de diagnostic. Prouve que le mécanisme jrt est
     * sain : l'appareil qui voit l'erreur souffre du NON-CHARGEMENT de
     * son jrt toolchain — le stub est le filet structurel, pas le
     * remplacement.
     */
    @Test
    void temoinAvecJrtLeDiagnosticDisparait() throws Exception {
        File android31 = findAndroidJarAvant33();
        Assumptions.assumeTrue(android31 != null,
                "android.jar API < 33 introuvable");
        Path jdk = findJdkAvecJrt();
        Assumptions.assumeTrue(jdk != null, "JDK avec image jrt introuvable");
        EcjCompilerEngine moteur = new EcjCompilerEngine(
                Collections.<File>emptyList(),
                Arrays.asList(android31, jarBiblio()),
                "17",
                jdk,
                null);
        List<Diagnostic> ds = moteur.diagnose("/src/p/A.java", SOURCE_EDITEE);
        assertFalse(contient(ds, "StringConcatFactory"),
                "TÉMOIN : avec jrt, AUCUN diagnostic StringConcatFactory"
                + " (diagnostics : " + ds + ")");
    }

    /**
     * CORRECTIF : le MÊME contexte que le repro, mais le stub embarqué
     * des intrinsèques du compilateur referme la hiérarchie binaire →
     * le faux positif DISPARAÎT.
     */
    @Test
    void stubEmbarqueLeFauxPositifDisparait() throws Exception {
        File android31 = findAndroidJarAvant33();
        Assumptions.assumeTrue(android31 != null,
                "android.jar API < 33 introuvable");
        EcjCompilerEngine moteur = new EcjCompilerEngine(
                Collections.<File>emptyList(),
                Arrays.asList(android31, jarBiblio()),
                "17",
                fauxJdk(),
                null);
        List<Diagnostic> ds = moteur.diagnose("/src/p/A.java", SOURCE_EDITEE);
        assertFalse(contient(ds, "StringConcatFactory"),
                "CORRECTIF : le stub doit refermer la référence indirecte"
                + " (diagnostics : " + ds + ")");
    }

    /**
     * FAIT ÉTABLI 1 en test : android-36 ne contient PAS non plus la
     * classe (seule StringConcatException y est) — le correctif doit
     * donc fermer le binding MÊME sur la plateforme la plus récente,
     * sans jrt. Le stub est structurel, pas transitoire.
     */
    @Test
    void api36NonPlusSansJrt() throws Exception {
        File android36 = findAndroidJar36();
        Assumptions.assumeTrue(android36 != null,
                "android.jar API 36 introuvable");
        EcjCompilerEngine moteur = new EcjCompilerEngine(
                Collections.<File>emptyList(),
                Arrays.asList(android36, jarBiblio()),
                "17",
                fauxJdk(),
                null);
        List<Diagnostic> ds = moteur.diagnose("/src/p/A.java", SOURCE_EDITEE);
        assertFalse(contient(ds, "StringConcatFactory"),
                "API 36 + sans jrt : le stub ferme le binding (fait établi 1)"
                + " (diagnostics : " + ds + ")");
    }

    private static File findAndroidJar36() {
        String[] candidats = {
                "/home/z/android-sdk/platforms/android-36/android.jar",
                "/home/z/my-project/android-sdk/platforms/android-36/android.jar",
        };
        for (String c : candidats) {
            File f = new File(c);
            if (f.isFile()) return f;
        }
        return null;
    }
}
