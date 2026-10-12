package jo.lspjava.ecj;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stubs des INTRINSÈQUES DU COMPILATEUR — repli structurel de
 * {@link EcjCompilerEnvironment#find} (v0.108.0, retour propriétaire).
 *
 * <p><strong>Constat</strong> (reproduit par
 * {@code StringConcatFactoryReproTest}) : les classes que javac (target
 * ≥ 9) installe dans le pool de constantes de CHAQUE concaténation —
 * {@code java.lang.invoke.StringConcatFactory} — et que le compilateur
 * référence aussi pour les records ({@code java.lang.runtime.ObjectMethods})
 * et les switch pattern ({@code java.lang.runtime.SwitchBootstraps})
 * N'EXISTENT DANS AUCUNE plateforme Android (vérifié android-31 et
 * android-36 : seul {@code StringConcatException} y figure) : D8
 * désugare toujours ces constructions, le code source est LÉGAL pour
 * Android. Quand une classe BINAIRE du classpath (code du projet
 * compilé par le serveur Gradle) les référence et que l'image jrt du
 * JDK toolchain n'a pas chargé (échec silencieux de jrt-fs sur ART —
 * cache négatif {@code jrtScanAttempted}), ecj publie « The type X
 * cannot be resolved. It is indirectly referenced from required .class
 * files » : un FAUX POSITIF qu'AUCUNE action utilisateur (build,
 * compileSdk, dépendance) ne peut effacer.</p>
 *
 * <p><strong>Correctif</strong> : fournir au moteur une DÉCLARATION
 * MINIMALE de ces types — un {@code .class} vide (aucun membre : le
 * binding de la hiérarchie se referme, le contenu ne compte pas —
 * ecj ne vérifie PAS la signature des méthodes bootstrap au binding,
 * constaté par le repro : une simple concat source ne produit RIEN).
 * Le {@code .class} est FABRIQUÉ À LA MAIN en octets (format CAFEBABE
 * v8, {@link #classeMinimale}) : aucune compilation au runtime, aucune
 * dépendance, déterminisme total, coût nul — le cache est un champ
 * statique volatile.</p>
 *
 * <p><strong>Priorité PRÉSERVÉE</strong> : le stub ne répond QU'EN
 * DERNIER repli de {@code find} — APRÈS sources, jars et image jrt.
 * Une plateforme qui contiendrait la classe (jdkHome chargé) répond
 * toujours AVANT : le stub ne masque JAMAIS un type réel. Il ne
 * nourrit PAS non plus l'index de complétion : personne ne complète
 * un intrinsèque du compilateur à la main ; seul {@code find} (FQCN
 * exact) consulte ce repli.</p>
 *
 * @author jo@Dev
 */
final class StubsIntrinseques {

    /**
     * Les intrinsèques du compilateur, par niveau d'apparition :
     * concaténation indy (Java 9), records (Java 16), switch pattern
     * (Java 21). {@code LambdaMetafactory} n'y figure pas : présente
     * dans android.jar (API 26+) et jamais réifiée hors signatures.
     */
    private static final List<String> INTRINSEQUES = Collections.unmodifiableList(
            Arrays.asList(
                    "java.lang.invoke.StringConcatFactory",
                    "java.lang.runtime.ObjectMethods",
                    "java.lang.runtime.SwitchBootstraps"));

    /** Octets des stubs — construits UNE fois par process (lazy). */
    private static volatile Map<String, byte[]> cache;

    private static final Logger LOG =
            Logger.getLogger(StubsIntrinseques.class.getName());

    private StubsIntrinseques() {
    }

    /** @return true si le FQCN est un intrinsèque du compilateur. */
    static boolean estIntrinseque(String fqcn) {
        return INTRINSEQUES.contains(fqcn);
    }

    /**
     * @return les octets du {@code .class} stub du FQCN, ou null si le
     *         FQCN n'est pas un intrinsèque (ou si la fabrication a
     *         échoué — journalisée, jamais propagée).
     */
    static byte[] octets(String fqcn) {
        if (!estIntrinseque(fqcn)) return null;
        Map<String, byte[]> present = cache;
        if (present != null) return present.get(fqcn);
        synchronized (StubsIntrinseques.class) {
            if (cache == null) {
                Map<String, byte[]> construit = new HashMap<>();
                for (String fqn : INTRINSEQUES) {
                    try {
                        construit.put(fqn, classeMinimale(fqn));
                    } catch (Throwable t) {
                        // Best-effort TRACÉ (zéro déglutition silencieuse) :
                        // le moteur vivra sans stub pour CE type — le
                        // diagnostic historique restera, jamais de crash.
                        LOG.log(Level.WARNING,
                                "fabrication du stub intrinsèque échouée : "
                                        + fqn, t);
                    }
                }
                cache = Collections.unmodifiableMap(construit);
            }
            return cache.get(fqcn);
        }
    }

    /**
     * Fabrique un {@code .class} minimal : classe publique finale sans
     * super explicite (java.lang.Object), sans membre, version 52
     * (Java 8 — lisible par ecj et par tous les dexeurs).
     *
     * <pre>
     * magic 0xCAFEBABE, version 52.0 ;
     * pool : [1] Utf8 "a/b/C" — [2] Class #1 — [3] Utf8 "java/lang/Object" — [4] Class #3 ;
     * access : ACC_PUBLIC | ACC_FINAL | ACC_SUPER (0x0011) ;
     * this_class #2, super_class #4 ; interfaces 0, champs 0, méthodes 0,
     * attributs 0.
     * </pre>
     */
    private static byte[] classeMinimale(String fqcn) throws IOException {
        String interne = fqcn.replace('.', '/');
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(0xCAFEBABE);            // magic
        out.writeInt(52);                    // version 52 (Java 8)
        out.writeShort(5);                    // constant_pool_count (slot 0 inutilisé)
        out.writeByte(1);                    // tag 1 : Utf8
        out.writeShort(interne.length());
        out.writeBytes(interne);             // [1] nom interne
        out.writeByte(7);                    // tag 7 : Class
        out.writeShort(1);                   // [2] cette classe
        out.writeByte(1);                    // tag 1 : Utf8
        out.writeShort("java/lang/Object".length());
        out.writeBytes("java/lang/Object");  // [3]
        out.writeByte(7);                    // tag 7 : Class
        out.writeShort(3);                   // [4] java.lang.Object
        out.writeShort(0x0011);              // access_flags
        out.writeShort(2);                   // this_class
        out.writeShort(4);                   // super_class
        out.writeShort(0);                   // interfaces_count
        out.writeShort(0);                   // fields_count
        out.writeShort(0);                   // methods_count
        out.writeShort(0);                   // attributes_count
        out.flush();
        return bos.toByteArray();
    }
}
