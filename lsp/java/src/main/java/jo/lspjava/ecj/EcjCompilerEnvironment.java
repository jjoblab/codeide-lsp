package jo.lspjava.ecj;

import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import jo.lspjava.api.IndexNoms;
import jo.lspjava.ecj.compat.ArtCompatInputStream;

/**
 * Index de classpath PARTAGÉ pour le compilateur ecj — le portage Java du
 * pattern {@code JdtEnvironmentCache} du projet de référence.
 *
 * <p>L'état coûteux (ensembles des paquets binaires, index de complétion,
 * paquets du jrt) est construit UNE fois et mutualisé entre tous les appels
 * {@code diagnose} ; seule l'exclusion du type focal (variable par appel)
 * vit dans le wrapper {@link INameEnvironment} fin créé par
 * {@link EcjCompilerEngine}.</p>
 *
 * <h2>Lecture SANS {@code java.util.zip.ZipFile}</h2>
 *
 * <p>Sur la ROM cible (ART), le CloseGuard de libcore émet
 * {@code "A resource failed to call ZipFile.close."} même pour des
 * instances correctement fermées une fois finalisées — ~2 259 warnings
 * en une session d'édition (~30-60/s). Le seul remède structurel est de
 * ne PLUS JAMAIS instancier cette classe : toute la lecture zip passe par
 * {@link RawZipReader} (RandomAccessFile + Inflater, parsing du répertoire
 * central — aucun CloseGuard possible).</p>
 *
 * <p><strong>Nouvelle architecture — aucune instance de {@link ZipFile}
 * ne survit à une méthode :</strong></p>
 * <ol>
 *   <li><b>Indexation</b> — chaque jar est ouvert une seule fois en
 *       try-with-resources : on collecte les noms d'entrées {@code .class},
 *       les préfixes de paquets et l'index de complétion ; POUR LES PETITS
 *       JARS (&lt; {@value #SNAPSHOT_BUDGET_BYTES} o décompressés — tous les
 *       {@code classes.jar} extraits d'AARs), on capture AU PASSAGE les
 *       octets des classes dans un snapshot mémoire.</li>
 *   <li><b>Résolution</b> — {@link #find(String)} consulte les SETS de noms
 *       (aucune I/O) ; les octets viennent du snapshot s'il existe, sinon
 *       d'une ouverture TRANSITOIRE du jar refermée aussitôt (android.jar,
 *       gros jars).</li>
 *   <li><b>Bilan</b> — plus AUCUN fd durable côté index ⇒ plus aucun warning
 *       CloseGuard POSSIBLE venant de cette couche ; dispose() libère la
 *       mémoire Java pure ; moins de fds processus ; swap d'index O(1)
 *       système.</li>
 * </ol>
 *
 * <p>{@code find} résout un type, dans l'ordre :</p>
 * <ol>
 *   <li>les <b>racines sources</b> du projet sur disque — comme source
 *       (la résolution voit les .java des autres modules, sans build) ;</li>
 *   <li>les <b>jars du classpath</b> (dépendances + android.jar du
 *       bootclasspath, fournis par le Gradle Tooling) — comme binaire
 *       ({@link ClassFileReader} sur les octets .class) ;</li>
 *   <li>sur JVM desktop uniquement, l'<b>image jrt</b> du JDK courant —
 *       pour les tests unitaires ; sur Android cette source n'existe pas
 *       (pas de {@code lib/modules}) et la plateforme doit venir des jars
 *       fournis (l'app ajoute android.jar au classpath).</li>
 * </ol>
 *
 * <p><strong>Note ART (minSdk 26) :</strong> ce code tourne dans l'app —
 * seules les API API-26 sont utilisées. {@code InputStream.readAllBytes} /
 * {@code Files.readString} / {@code Path.of} (tous API 33+) sont remplacés
 * par {@link jo.lspjava.ecj.compat.ArtCompatInputStream} / {@code new String(Files.readAllBytes(..))}
 * / {@link Paths#get}.</p>
 *
 * @author jo@Dev
 */
final class EcjCompilerEnvironment {

    /** Logger pour tracer les erreurs de buildJarIndex. */
    private static final Logger LOG = Logger.getLogger("EcjCompilerEnvironment");

    /** Budget de snapshot par jar — au-delà, lecture transitoire à la demande. */
    static final long SNAPSHOT_BUDGET_BYTES = 2_500_000L;

    /**
     * Nombre MAXIMAL de handles {@link RawZipReader} ouverts simultanément
     * (pattern {@code MAX_OPEN_JARS} de {@code JdtEnvironmentCache.kt},
     * projet de référence v3.20). Au-delà, le handle le moins récemment utilisé est
     * refermé — les handles sont rouverts à la demande (coût d'un open ZIP,
     * ~quelques ms) au lieu d'empiler des descripteurs de fichiers sur les
     * gros classpaths (Android ~200 jars).
     */
    static final int MAX_OPEN_JARS = 24;

    private final List<Path> sourceRoots;
    private final List<Path> classpathJars;

    /**
     * Cache de localisation fqcn → jar (portage {@code locateCache} de
     * {@code JdtEnvironmentCache.kt}, projet de référence v3.20) : sans lui, chaque
     * {@link #find} SONDE les N sets d'entrées de jars — O(N) lookups par
     * type résolu, multiplié par les centaines de types qu'ecj résout par
     * frappe. Avec la sentinelle négative, un type ABSENT du classpath
     * n'est re-sondé qu'une seule fois.
     */
    private final EcjLocateCache locateCache = new EcjLocateCache();

    /**
     * Index disque branché (étape 3 lot C, ADR 0111) — {@code null} si
     * aucun : toutes les requêtes vivent alors de l'index mémoire seul
     * (comportement historique, repli intégral). Non-null : à l'état
     * PRÊT le localisateur est AUTORITATIF (négatifs sans sondage) et
     * les requêtes de découverte (préfixe, paquets, types) sont servies
     * par l'index disque ; tant que la construction n'a pas fini, la
     * sonde mémoire reste le substrat (repli par sondage, ADR 0111 §8).
     *
     * <p>L'observateur PRÊT vide le {@link EcjLocateCache} à la
     * transition (P7/P8 : un négatif mémorisé pendant la fenêtre de
     * construction doit se guérir) — l'autorité passe alors à
     * {@link IndexNoms#jarDe}.</p>
     */
    private final IndexNoms indexNoms;

    /** Cache inverse jar (Path) → index dans les listes parallèles. */
    private final java.util.Map<Path, Integer> jarIdxByPath =
            new java.util.HashMap<>();

    /**
     * Home du JDK fournissant l'image jrt (java.*). Peut être différent du
     * runtime courant — sur ART, {@code java.home} ne désigne pas un JDK et
     * n'a pas d'image modules ; on pointe alors vers le JDK installé par le
     * terminal (Termux) et on charge son {@code lib/jrt-fs.jar} (fournisseur
     * de système de fichiers jrt pur-Java — pattern {@code JrtImage} de
     * projet de référence).
     */
    private final java.nio.file.Path jdkHome;

    /**
     * Noms d'entrées {@code .class} par jar (parallèle à
     * {@link #indexedJars}). Consultés SANS I/O par {@link #find}.
     */
    private final List<Set<String>> jarClassEntries = new ArrayList<>();

    /**
     * Les jars EFFECTIVEMENT indexés, DANS L'ORDRE d'indexation
     * (parallèle exact de {@link #jarClassEntries} et {@link #jarSnapshots}).
     *
     * <p>INVARIANT : {@code jarIdx} est une position dans les listes
     * PARALLÈLES (jars indexés SEULEMENT), PAS dans {@link #classpathJars}
     * — qui contient TOUS les jars, y compris ceux écartés à l'indexation
     * (sources.jar sans .class, jar absent…). Résoudre un jar via
     * {@code classpathJars.get(jarIdx)} désignerait le MAUVAIS fichier dès
     * qu'un jar précédent a été écarté : les gros jars de fin de classpath
     * (les android.jar boot + plateforme) liraient les octets d'un autre
     * jar, et toute la plateforme {@code android.*} cesserait de résoudre
     * (diagnostics en cascade, complétion vide).</p>
     */
    private final List<Path> indexedJars = new ArrayList<>();

    /**
     * Octets des classes capturées à l'indexation (parallèle à
     * {@link #indexedJars}, clé = nom d'entrée complet
     * {@code a/b/C.class}) — null pour les jars au-delà du budget de
     * snapshot (lecture transitoire alors). La liste reste alignée :
     * entrée null = jar « transitoire ».
     */
    private final List<java.util.Map<String, byte[]>> jarSnapshots = new ArrayList<>();

    /** Paquets binaires (jars), préfixes inclus. */
    private final Set<String> binaryPackages = new HashSet<>();

    /** Index de complétion : nom simple → FQNs (types des jars).
     *  Construit pendant l'indexation des jars.
     *  ConcurrentHashMap (thread-safe) : buildJarIndex parallélise
     *  l'indexation des jars avec ForkJoinPool. */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.List<String>> simpleNameIndex =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Index jrt (FQCN → chemin .class) + paquets — scannés
     *  paresseusement, desktop seulement (pattern JrtImage du projet de référence). */
    private java.util.Map<String, Path> jrtIndex = null;
    private Set<String> jrtPackages = null;

    /**
     * Cache NÉGATIF du scan jrt : true dès la PREMIÈRE tentative
     * (réussie ou non). Sans lui, tout échec du chargement jrt-fs
     * (JDK externe incompatible, modules corrompu…) serait retenté à
     * CHAQUE appel isPackage/subPackages/findTypesByPrefix — soit des
     * milliers d'URLClassLoader recréés par complétion (fuites ZipFile
     * + ~5 ms/appel ; cf. scanJrtIfNeeded).
     */
    private boolean jrtScanAttempted;

    /**
     * Index des FQN des sources projet (walk des racines sources), mis
     * en cache avec TTL : un Files.walk complet PAR APPEL serait de l'I/O
     * disque Android à chaque frappe de complétion. Le cache se rafraîchit
     * au plus toutes les {@link #SOURCE_INDEX_TTL_MS} ms.
     */
    private volatile java.util.List<String> sourceTypeFqns;
    private volatile long sourceTypeFqnsStamp;
    private static final long SOURCE_INDEX_TTL_MS = 10_000L;

    /** true si le JDK courant expose une image jrt (desktop/tests). */
    private final boolean jrtAvailable;

    EcjCompilerEnvironment(List<Path> sourceRoots, List<Path> classpathJars) {
        this(sourceRoots, classpathJars, null, null);
    }

    EcjCompilerEnvironment(List<Path> sourceRoots, List<Path> classpathJars,
                           java.nio.file.Path jdkHome) {
        this(sourceRoots, classpathJars, jdkHome, null);
    }

    EcjCompilerEnvironment(List<Path> sourceRoots, List<Path> classpathJars,
                           java.nio.file.Path jdkHome, IndexNoms indexNoms) {
        this.sourceRoots = new ArrayList<>(sourceRoots);
        this.classpathJars = new ArrayList<>(classpathJars);
        this.jdkHome = jdkHome != null ? jdkHome
                : Paths.get(System.getProperty("java.home", ""));
        this.jrtAvailable = Files.isRegularFile(this.jdkHome.resolve("lib/modules"));
        this.indexNoms = indexNoms;
        if (indexNoms != null) {
            // P7/P8 (ADR 0111 §8) : à la transition PRÊT, les négatifs
            // mémorisés pendant la fenêtre de construction deviennent
            // périmés — le cache de localisation se vide, l'autorité
            // passe à l'index. L'implémentation SPI attrape un
            // observateur fautif (contrat de IndexNoms).
            indexNoms.ajouterObservateurPret(locateCache::clear);
        }
        // Index disque DÉJÀ prêt (réouverture à chaud) : l'index mémoire
        // n'est PAS construit — requêtes et localisation vivent de
        // l'index disque et du pool de handles par chemin (économie :
        // snapshots + index de noms + sets d'entrées). Sinon (pas
        // d'index, ou construction en cours) : index mémoire construit
        // comme toujours — c'est le substrat du repli par sondage.
        if (indexNoms == null || !indexNoms.estPret()) {
            buildJarIndex();
        }
    }

    /**
     * true si au moins un {@code R.class} (ou
     * {@code R$<res>.class}) RÉEL a été indexé dans le classpath. Piloté par
     * le scan binaire ({@code buildJarIndex}), jamais par une génération de
     * stubs — zéro devinette. Consommé par la couche LSP pour le downgrade
     * HONNÊTE des faux « R cannot be resolved » tant que la première
     * construction n'a pas encore fait produire R par aapt2.
     */
    private volatile boolean hasRealRClasses;

    /** @return true si des symboles R réels vivent dans l'index binaire. */
    public boolean hasRealRClasses() { return hasRealRClasses; }

    /**
     * @return true si des classes Data Binding GÉNÉRÉES existent déjà —
     *         sources projet sous un segment {@code databinding} (issues
     *         de {@code dataBindingGenBaseClasses}, visibles dès la
     *         génération) ou binaires du classpath (classes compilées du
     *         module). Sonde à la demande avec TTL : les classes
     *         n'apparaissent qu'APRÈS un build, potentiellement pendant
     *         que le moteur vit — une valeur figée à l'indexation
     *         dirait FAUX à tort (motif « zéro devinette » du downgrade
     *         honnête, cf. hasRealRClasses).
     */
    boolean hasGeneratedDatabindingClasses() {
        long now = System.currentTimeMillis();
        if (now - databindingProbeStamp < DATABINDING_PROBE_TTL_MS) {
            return databindingProbeResult;
        }
        synchronized (this) {
            if (System.currentTimeMillis() - databindingProbeStamp
                    < DATABINDING_PROBE_TTL_MS) {
                return databindingProbeResult;
            }
            boolean trouve = false;
            try {
                for (String fqcn : sourceTypesCached()) {
                    if (estPaquetDatabinding(fqcn)) { trouve = true; break; }
                }
            } catch (Throwable t) {
                // Sonde best-effort — le repli est « pas générées ».
                LOG.log(Level.FINE, "Sonde databinding sources échouée", t);
            }
            if (!trouve) {
                for (List<String> fqns : simpleNameIndex.values()) {
                    for (String fqcn : fqns) {
                        if (estPaquetDatabinding(fqcn)) { trouve = true; break; }
                    }
                    if (trouve) break;
                }
            }
            databindingProbeResult = trouve;
            databindingProbeStamp = System.currentTimeMillis();
            return trouve;
        }
    }

    /** @return true si le FQCN vit sous un segment {@code databinding}. */
    private static boolean estPaquetDatabinding(String fqcn) {
        return fqcn != null && fqcn.contains(".databinding.");
    }

    /** TTL de la sonde databinding (rafraîchie au pire toutes les 5 s). */
    private static final long DATABINDING_PROBE_TTL_MS = 5_000L;

    private volatile long databindingProbeStamp;
    private volatile boolean databindingProbeResult;

    // ─── Résolution ───────────────────────────────────────────────────

    /** Résout un FQCN (sans exclusion — gérée par le wrapper par appel). */
    NameEnvironmentAnswer find(String fqcn) {
        // 1. Sources disque.
        char[] source = readSourceOnDisk(fqcn);
        if (source != null) {
            return new NameEnvironmentAnswer(new EcjSourceUnit(fqcn, source), null);
        }

        // 2. Jars du classpath — via le CACHE de localisation (O(1) après
        //    échauffement, négatifs mémorisés ; portage locateCache de
        //    projet de référence v3.20). Le premier miss seul paie la sonde O(N)
        //    sur les sets d'entrées ; un type absent ne re-sonde plus JAMAIS
        //    les N jars à chaque résolution.
        String entryName = fqcn.replace('.', '/') + ".class";
        if (indexDisquePret()) {
            // 2a. ÉTAPE 3 (lot C, ADR 0111 §8) : localisateur AUTORITATIF —
            // le prober du cache devient l'index disque (jarDe). Un
            // négatif est DÉFINITIF côté jars (la sonde mémoire est
            // sautée — y compris quand l'index mémoire n'existe pas,
            // réouverture à chaud) ; les octets se lisent par le pool de
            // handles PAR CHEMIN. locateBinary garde le repli du type
            // englobant pour les imbriquées ($).
            Path jar = locateCache.locateBinary(fqcn, this::sondeIndexDisque);
            if (jar != null) {
                byte[] bytes = classBytesParChemin(jar, entryName);
                if (bytes != null) {
                    try {
                        return new NameEnvironmentAnswer(
                                ClassFileReader.read(bytes, entryName), null);
                    } catch (Throwable t) {
                        // Entrée illisible — TRACÉ (zéro déglutition
                        // silencieuse), puis repli jrt ci-dessous.
                        logReadFailure(jar, entryName, t);
                    }
                }
            }
        } else {
            Integer jarIdx = cachedJarIdx(fqcn);
            if (jarIdx != null) {
                byte[] bytes = classBytes(jarIdx, entryName);
                if (bytes != null) {
                    try {
                        return new NameEnvironmentAnswer(
                                ClassFileReader.read(bytes, entryName), null);
                    } catch (Throwable t) {
                        // Entrée illisible — TRACÉ (zéro déglutition
                        // silencieuse), puis repli jrt ci-dessous.
                        logReadFailure(indexedJars.get(jarIdx), entryName, t);
                    }
                }
            }
        }

        // 3. jrt (desktop / tests seulement).
        if (jrtAvailable) {
            byte[] jrt = jrtBytes(fqcn);
            if (jrt != null) {
                try {
                    return new NameEnvironmentAnswer(
                            ClassFileReader.read(jrt, entryName), null);
                } catch (Throwable t) {
                    // Lecture jrt illisible — best-effort, tracée en debug.
                    LOG.log(Level.FINE, "Lecture jrt illisible", t);
                }
            }
        }

        // 4. Intrinsèques du compilateur (v0.108.0, repli STRUCTUREL —
        //    APRÈS sources, jars et jrt : ne masque JAMAIS un type réel).
        //    StringConcatFactory/ObjectMethods/SwitchBootstraps n'existent
        //    dans AUCUNE plateforme Android (D8 désugare toujours) : sans
        //    ce repli, un binaire du classpath qui les référence produit
        //    « ...cannot be resolved. It is indirectly referenced from
        //    required .class files » — faux positif incurable côté
        //    utilisateur. Voir StubsIntrinseques (repro :
        //    StringConcatFactoryReproTest).
        if (StubsIntrinseques.estIntrinseque(fqcn)) {
            byte[] octetsStub = StubsIntrinseques.octets(fqcn);
            if (octetsStub != null) {
                try {
                    return new NameEnvironmentAnswer(
                            ClassFileReader.read(octetsStub, entryName), null);
                } catch (Throwable t) {
                    LOG.log(Level.WARNING,
                            "Lecture du stub intrinsèque illisible : " + fqcn, t);
                }
            }
        }
        return null;
    }

    /** L'index disque est branché ET prêt (autorité contractuelle). */
    private boolean indexDisquePret() {
        return indexNoms != null && indexNoms.estPret();
    }

    /**
     * Sonde du localisateur à l'état PRÊT : le jar porteur rendu par
     * l'index disque. Un conteneur que le moteur ne sait pas lire
     * directement (AAR non normalisé) rend {@code null} — la classe
     * n'est PAS résoluble côté moteur par cette voie.
     *
     * <p>Les erreurs de l'index sont TRACÉES et rendent {@code null}
     * (repli jrt) — jamais propagées (l'index ne doit pas casser la
     * résolution ecj).</p>
     */
    private Path sondeIndexDisque(String fqcn) {
        try {
            return indexNoms.jarDe(fqcn);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "jarDe(" + fqcn + ") a levé — repli", t);
            return null;
        }
    }

    /**
     * Index du jar propriétaire du FQCN via le cache — ou {@code null} si
     * absent des jars indexés (le négatif est mémorisé par le cache, la
     * sonde linéaire ne rejoue qu'au premier miss).
     */
    private Integer cachedJarIdx(String fqcn) {
        Path jar = locateCache.locateBinary(fqcn, this::probeJarsFor);
        if (jar == null) return null;
        Integer idx = jarIdxByPath.get(jar);
        if (idx == null) {
            // Jar retiré de l'index entre-temps (dispose concurrent) —
            // le négatif n'est PAS mémorisé pour autant : on invalide
            // l'entrée pour forcer une re-sonde au prochain appel.
            locateCache.clear();
            return null;
        }
        return idx;
    }

    /**
     * Sonde linéaire (repli de premier miss) : premier jar indexé dont le
     * set d'entrées contient le {@code .class} du FQCN — zéro I/O, les
     * sets vivent en mémoire.
     */
    private Path probeJarsFor(String fqcn) {
        String entryName = fqcn.replace('.', '/') + ".class";
        for (int i = 0; i < jarClassEntries.size(); i++) {
            Set<String> entries = jarClassEntries.get(i);
            if (entries != null && entries.contains(entryName)) {
                return indexedJars.get(i);
            }
        }
        return null;
    }

    /** @return true si le paquet existe (sources, jars ou jrt). */
    boolean isPackage(String pkg) {
        // Paquets sources : existence du répertoire sous une racine.
        String rel = pkg.replace('.', '/');
        for (Path root : sourceRoots) {
            if (Files.isDirectory(root.resolve(rel))) {
                return true;
            }
        }
        // Côté jars : index disque à l'état PRÊT (autorité — fonctionne
        // aussi quand l'index mémoire n'existe pas), sinon l'index mémoire.
        if (indexDisquePret()) {
            try {
                if (indexNoms.estPaquet(pkg)) return true;
            } catch (Throwable t) {
                LOG.log(Level.FINE, "estPaquet(" + pkg + ") a levé — repli mémoire", t);
                return binaryPackages.contains(pkg);
            }
            return isJrtPackage(pkg);
        }
        if (binaryPackages.contains(pkg)) return true;
        return isJrtPackage(pkg);
    }

    /**
     * Libère la mémoire et les handles de l'index (fin de vie).
     *
     * <p>Aucun {@code java.util.zip.ZipFile} n'existe dans cette couche :
     * on referme les handles {@link RawZipReader} persistants puis on vide
     * les structures mémoire (snapshots, sets, indexes). Le FileSystem jrt
     * dédié (jrt-fs.jar externe) et son ClassLoader sont fermés ici — sauf
     * s'il s'agit du singleton shared du runtime courant
     * ({@link #jrtFileSystemShared}) : le fermer casserait toute la JVM
     * desktop (cf. pattern JrtImage du projet de référence).</p>
     */
    void dispose() {
        // Referme les handles RawZipReader persistants (UN RandomAccessFile
        // par jar volumineux non snapshoté). Les petits jars vivent en
        // mémoire seule — rien à fermer.
        synchronized (jarHandles) {
            for (RawZipReader.Handle h : jarHandles.values()) {
                try {
                    h.close();
                } catch (Throwable t) {
                    // Fermeture best-effort — tracée en debug.
                    LOG.log(Level.FINE, "Fermeture d'un handle de jar échouée", t);
                }
            }
            jarHandles.clear();
        }
        jarSnapshots.clear();
        sourceTypeFqns = null;
        sourceTypeFqnsStamp = 0L;
        jarClassEntries.clear();
        indexedJars.clear();
        readFailures.clear();
        simpleNameIndex.clear();
        binaryPackages.clear();
        // Invalidation du cache de localisation : un fqcn→jar mémorisé
        // (positif OU négatif) n'a plus de sens une fois l'index vidé.
        locateCache.clear();
        jarIdxByPath.clear();
        if (jrtFileSystem != null && !jrtFileSystemShared) {
            // FS dédié (jrt-fs.jar externe, JDK Termux) — fermeture safe.
            try {
                jrtFileSystem.close();
            } catch (Throwable t) {
                // Fermeture best-effort — tracée en debug.
                LOG.log(Level.FINE, "Fermeture du FileSystem jrt échouée", t);
            }
        }
        // Si jrtFileSystemShared == true, on NE ferme PAS — singleton JVM.
        jrtFileSystem = null;
        jrtFileSystemShared = false;
        if (jrtFsLoader != null) {
            try {
                jrtFsLoader.close();
            } catch (Throwable t) {
                // Fermeture best-effort — tracée en debug.
                LOG.log(Level.FINE, "Fermeture du loader jrt-fs échouée", t);
            }
            jrtFsLoader = null;
        }
    }

    /** @return true si au moins une source binaire est disponible (jars
     *         ou jrt). Un index disque branché compte — même quand
     *         l'index mémoire n'a pas été construit (réouverture à chaud
     *         à l'état PRÊT : l'autorité de localisation est l'index). */
    boolean hasBinarySource() {
        return !jarClassEntries.isEmpty() || jrtAvailable
                || (indexNoms != null && !classpathJars.isEmpty());
    }

    /**
     * Lecture directe du {@link ClassFileReader} d'un type
     * binaire du classpath (fallback de complétion sans bindings).
     *
     * @return le reader binaire, ou null si absent/illisible/defini en
     *         source projet.
     */
    org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader binaryType(
            String fqcn) {
        // Aucune réflexion sur le champ package-private de
        // NameEnvironmentAnswer (restrictions hidden-API sur ART) : on
        // rejoue exactement la lecture find() → ClassFileReader.read(),
        // via le même CACHE de localisation que find (premier miss seul
        // paie la sonde linéaire).
        String entryName = fqcn.replace('.', '/') + ".class";
        if (indexDisquePret()) {
            // Étape 3 lot C : localisateur autoritatif (cf. find 2a).
            Path jar = locateCache.locateBinary(fqcn, this::sondeIndexDisque);
            if (jar != null) {
                byte[] bytes = classBytesParChemin(jar, entryName);
                if (bytes != null) {
                    try {
                        return org.eclipse.jdt.internal.compiler.classfmt
                                .ClassFileReader.read(bytes, entryName);
                    } catch (Throwable t) {
                        LOG.log(Level.FINE, "Entrée de classfile illisible", t);
                    }
                }
            }
            return null;
        }
        Integer jarIdx = cachedJarIdx(fqcn);
        if (jarIdx != null) {
            byte[] bytes = classBytes(jarIdx, entryName);
            if (bytes == null) {
                // Types DEFINED en source projet : pas de binaire → non
                // couvert ici.
                return null;
            }
            try {
                return org.eclipse.jdt.internal.compiler.classfmt
                        .ClassFileReader.read(bytes, entryName);
            } catch (Throwable t) {
                // Entrée illisible — tracée en debug.
                LOG.log(Level.FINE, "Entrée de classfile illisible", t);
            }
        }
        return null;
    }

    // ─── Observabilité ────────────────────────────────────────────────

    /**
     * Résultat d'indexation par jar — alimenté pendant
     * {@link #buildJarIndex()} : chaque jar du classpath reçoit soit un
     * bilan OK, soit la raison de son exclusion. Alimente les logs device
     * (« zéro devinette » sur les jars que ecj ne voit pas).
     */
    private final java.util.Map<Path, String> jarIndexReport =
            new java.util.LinkedHashMap<>();

    /** @return nombre de jars effectivement indexés (au moins 1 .class). */
    int debugIndexedJarCount() {
        return jarClassEntries.size();
    }

    /** @return nombre de paquets binaires connus. */
    int debugBinaryPackageCount() {
        return binaryPackages.size();
    }

    /** @return nombre de noms simples indexés (complétion de types). */
    int debugSimpleNameCount() {
        return simpleNameIndex.size();
    }

    /**
     * @return nombre d'entrées du cache de localisation (positives ET
     *         négatives) — observabilité du vidage P7/P8 à la transition
     *         PRÊT (tests du lot C).
     */
    int debugLocateCacheSize() {
        return locateCache.size();
    }

    /**
     * @return une ligne de bilan par jar du classpath —
     *         {@code nom | OK nClasses [snapshot|handle] | SKIP raison}.
     *         Destiné aux logs LSP côté app (instrumentation sans devinette).
     */
    String debugJarIndexReport() {
        StringBuilder sb = new StringBuilder(256);
        for (Path jar : classpathJars) {
            sb.append(jar.getFileName()).append(" → ");
            String note = jarIndexReport.get(jar);
            sb.append(note != null ? note : "non-scané");
            sb.append('\n');
        }
        return sb.toString();
    }

    // ─── Interne ──────────────────────────────────────────────────────

    private char[] readSourceOnDisk(String fqcn) {
        String rel = fqcn.replace('.', '/') + ".java";
        for (Path root : sourceRoots) {
            Path p = root.resolve(rel);
            if (Files.isRegularFile(p)) {
                try {
                    return new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
                            .toCharArray();
                } catch (IOException | OutOfMemoryError ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Handles RawZipReader persistants (chemin → handle), en LRU d'accès
     * borné à {@link #MAX_OPEN_JARS} (pool du projet de référence) : l'entrée la
     * moins récemment utilisée est fermée et retirée dès dépassement.
     *
     * <p>Clé par CHEMIN (étape 3 lot C) : les lectures de l'index disque
     * localisent par chemin sans passer par l'index mémoire — le pool
     * sert alors aussi bien le chemin mémoire (jarIdx résolu en chemin)
     * que le chemin autoritatif ({@link #classBytesParChemin}), sous un
     * seul budget de descripteurs.</p>
     */
    private final Map<Path, RawZipReader.Handle> jarHandles =
            new java.util.LinkedHashMap<>(16, 0.75f, true);

    /**
     * Octets de {@code entryName} dans le jar d'index {@code jarIdx} :
     * snapshot mémoire si capturé, sinon lecture via UN handle persistant
     * {@link RawZipReader} — zéro {@code java.util.zip.ZipFile} (cf.
     * javadoc de classe : sur la ROM cible chaque new ZipFile() même fermé
     * émettait un warning CloseGuard à la finalisation).
     */
    private byte[] classBytes(int jarIdx, String entryName) {
        // 1. Snapshot mémoire (petits jars — la majorité des lookups
        //    s'arrête ici, zéro I/O).
        java.util.Map<String, byte[]> snap = jarSnapshots.get(jarIdx);
        if (snap != null) return snap.get(entryName);
        // 2. Jar volumineux (android.jar…) — handle paresseux réutilisé
        //    PAR CHEMIN, depuis un POOL LRU borné à MAX_OPEN_JARS (portage
        //    du pool de JdtEnvironmentCache.kt, projet de référence v3.20) : le
        //    handle le moins récemment utilisé est refermé dès dépassement.
        //    Les lectures COMPLÈTENT SOUS LE VERROU (comme projet de référence) :
        //    une éviction concurrente ne peut jamais refermer un handle
        //    en cours de lecture — le coût est une serialisation des
        //    lectures de gros jars, acceptable au regard d'un handle fermé
        //    à mi-lecture. indexedJars (parallèle EXACT) — JAMAIS
        //    classpathJars.get(jarIdx) : jarIdx désigne une position dans
        //    les listes parallèles des jars INDEXÉS (cf. javadoc du champ).
        if (jarIdx >= indexedJars.size()) return null;
        return classBytesParChemin(indexedJars.get(jarIdx), entryName);
    }

    /**
     * Octets d'une entrée d'un jar identifié PAR CHEMIN — la voie de
     * l'index disque (étape 3 lot C) : le localisateur autoritatif rend
     * un chemin, les octets se lisent par le même pool LRU de handles
     * (aucun snapshot requis — la réouverture à chaud n'a PAS construit
     * l'index mémoire).
     */
    private byte[] classBytesParChemin(Path jar, String entryName) {
        if (jar == null || !Files.isRegularFile(jar)) return null;
        synchronized (jarHandles) {
            RawZipReader.Handle h = jarHandles.get(jar);
            if (h == null) {
                try {
                    h = RawZipReader.open(jar);
                    jarHandles.put(jar, h);
                    trimJarHandles();
                } catch (Throwable t) {
                    logReadFailure(jar, "open", t);
                    return null;
                }
            }
            try {
                return h.read(entryName);
            } catch (Throwable t) {
                logReadFailure(jar, entryName, t);
                return null;
            }
        }
    }

    /**
     * Réduit le pool au plafond {@link #MAX_OPEN_JARS} : referme (best-effort,
     * tracé FINE) les handles les moins récemment utilisés. Appelé SOUS le
     * verrou {@link #jarHandles} — jamais pendant l'indexation (les handles
     * ne vivent qu'après, à la lecture).
     */
    private void trimJarHandles() {
        while (jarHandles.size() > MAX_OPEN_JARS) {
            var it = jarHandles.entrySet().iterator();
            var eldest = it.next();
            it.remove();
            try {
                eldest.getValue().close();
            } catch (Throwable t) {
                LOG.log(Level.FINE, "Fermeture d'un handle LRU évicté échouée", t);
            }
        }
    }

    /**
     * Observabilité « zéro devinette » : un échec de lecture avalé
     * ({@code catch (Throwable ignored)}) se traduirait par un null
     * silencieux indistinguable d'une classe absente. La PREMIÈRE erreur
     * par jar est donc tracée (via {@link #LOG}), les suivantes comptées
     * seulement.
     */
    private final Map<Path, String> readFailures = new java.util.LinkedHashMap<>();

    private void logReadFailure(Path jar, String what, Throwable t) {
        String key = jar == null ? "?" : jar.toString();
        String prev = readFailures.get(jar);
        if (prev == null) {
            String msg = t == null ? "?" : t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : "");
            readFailures.put(jar, what + " → " + msg);
            LOG.warning("[JavaLspIndex] ÉCHEC lecture " + what
                    + " — " + key + " : " + msg);
        }
    }

    /** @return la première erreur de lecture par jar (diag device). */
    String debugReadFailures() {
        return readFailures.isEmpty() ? "" : readFailures.toString();
    }

    /**
     * Sonde post-index : pour chaque jar « à handle paresseux »
     * (les plus gros — android.jar, appcompat, kotlin-stdlib…), lit UNE
     * entrée réelle via le chemin COMPLET ({@link #classBytes} +
     * {@link ClassFileReader#read}) et rend un mini-rapport.
     *
     * <p>Cette sonde rend tout échec de la chaîne de lecture VISIBLE dans
     * le résumé [JavaLspIndex] du logcat dès la construction de l'index —
     * sans attendre les symptômes (diagnostics en cascade, complétion
     * vide).</p>
     *
     * @return rapport une-ligne par jar lazy sondé : {@code nom → OK} ou
     *         {@code nom → ÉCHEC (raison)}. Vide si aucun jar lazy.
     */
    String debugProbeLazyReads() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < jarClassEntries.size(); i++) {
            if (jarSnapshots.get(i) != null) continue; // eager — déjà lu
            Set<String> entries = jarClassEntries.get(i);
            if (entries.isEmpty()) continue;
            String entry = null;
            for (String e : entries) { entry = e; break; }
            Path jar = indexedJars.get(i);
            String label = jar == null ? "?" : String.valueOf(jar.getFileName());
            try {
                byte[] bytes = classBytes(i, entry);
                if (bytes == null) {
                    sb.append(label).append(" → ÉCHEC (octets null)");
                } else {
                    ClassFileReader.read(bytes, entry);
                    sb.append(label).append(" → OK");
                }
            } catch (Throwable t) {
                sb.append(label).append(" → ÉCHEC (")
                        .append(t.getClass().getSimpleName())
                        .append(t.getMessage() != null ? ": " + t.getMessage() : "")
                        .append(')');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Ouvre, scane et referme chaque jar du classpath.
     *
     * <p><strong>Invariant :</strong> plus AUCUNE instance de
     * {@code java.util.zip.ZipFile} — un {@link RawZipReader.Handle} vit
     * STRICTEMENT le temps de ce try-with-resources. Pendant la passe :
     * <ul>
     *   <li>noms d'entrées {@code .class} → {@link #jarClassEntries} ;</li>
     *   <li>préfixes de paquets → {@link #binaryPackages} ;</li>
     *   <li>nom simple → FQNs → {@link #simpleNameIndex} ;</li>
     *   <li>octets → snapshot mémoire si le cumul décompressé reste sous
     *       {@link #SNAPSHOT_BUDGET_BYTES} (sinon le jar devient « à handle
     *       paresseux » : set gardé en mémoire, octets lus au premier besoin
     *       via {@link #classBytes}).</li>
     * </ul>
     * Toute exception = jar ignoré (best-effort, pattern projet de référence) — le
     * try-with-resources referme systématiquement le RandomAccessFile.</p>
     */
    private void buildJarIndex() {
        // Parallélisation avec ForkJoinPool : une boucle séquentielle
        // coûtait 2,5 s sur desktop (20 jars), 4-6 s sur ART low-end — gel
        // UI au premier didOpen. On PARSE donc les jars en parallèle
        // (parsing ZIP + lecture central directory = ~80% du temps), puis on
        // ajoute les résultats SÉQUENTIELLEMENT aux structures partagées
        // (jarClassEntries, indexedJars, jarSnapshots, jarIndexReport,
        // simpleNameIndex). La phase d'ajout est rapide car elle ne fait que
        // des put/add de sets/maps déjà construits.
        //
        // Stratégie :
        //   1. Pour chaque jar, en parallèle : open + lecture entries +
        //      snapshot (si budget) → produit un JarIndexResult.
        //   2. En séquentiel, après join : on merge les résultats dans les
        //      structures partagées.
        //
        // Pool size : min(4, availableProcessors). Sur ART BIG.little, plus
        // de threads = plus de contention sur les cœurs little sans gain.
        int parallelism = Math.min(4, Runtime.getRuntime().availableProcessors());

        // Étape 1 : parsing parallèle
        java.util.List<java.util.concurrent.Callable<JarIndexResult>> tasks = new java.util.ArrayList<>();
        for (Path jar : classpathJars) {
            tasks.add(() -> indexOneJar(jar));
        }

        java.util.List<JarIndexResult> results;
        if (parallelism <= 1 || tasks.size() <= 1) {
            // Mono-thread — fallback si un seul cœur ou un seul jar.
            results = new java.util.ArrayList<>();
            for (var task : tasks) {
                try {
                    results.add(task.call());
                } catch (Throwable t) {
                    results.add(JarIndexResult.error(null, t));
                }
            }
        } else {
            java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(parallelism);
            try {
                results = pool.submit(() ->
                    tasks.parallelStream()
                        .map(t -> {
                            try { return t.call(); }
                            catch (Throwable e) { return JarIndexResult.error(null, e); }
                        })
                        .collect(java.util.stream.Collectors.toList())
                ).get();
            } catch (Throwable t) {
                // Fallback mono-thread si le pool échoue.
                LOG.warning("buildJarIndex parallel failed, falling back to sequential: " + t);
                results = new java.util.ArrayList<>();
                for (var task : tasks) {
                    try {
                        results.add(task.call());
                    } catch (Throwable e) {
                        results.add(JarIndexResult.error(null, e));
                    }
                }
            } finally {
                pool.shutdown();
            }
        }

        // Étape 2 : merge séquentiel dans les structures partagées.
        // L'ordre d'insertion suit l'ordre de classpathJars pour conserver
        // la priorité de classpath (jar plus tôt = priorité plus haute).
        java.util.Map<Path, JarIndexResult> byPath = new java.util.HashMap<>();
        for (JarIndexResult r : results) {
            if (r != null && r.jar != null) byPath.put(r.jar, r);
        }
        for (Path jar : classpathJars) {
            JarIndexResult r = byPath.get(jar);
            if (r == null) continue;
            if (r.error != null) {
                jarIndexReport.put(jar, "SKIP " + r.error.getClass().getSimpleName()
                        + ": " + r.error.getMessage());
                continue;
            }
            if (r.skipReason != null) {
                jarIndexReport.put(jar, r.skipReason);
                continue;
            }
            if (r.entries == null || r.entries.isEmpty()) {
                jarIndexReport.put(jar, "SKIP 0 .class");
                continue;
            }
            // Merge dans les structures partagées.
            jarClassEntries.add(r.entries);
            indexedJars.add(jar);
            // Cache inverse Path → jarIdx pour le locateCache (find/binaryType) :
            // l'index EST la position dans les listes parallèles.
            jarIdxByPath.put(jar, indexedJars.size() - 1);
            boolean snapKept = r.snapshotLive && r.snapshot != null && !r.snapshot.isEmpty();
            jarSnapshots.add(snapKept ? r.snapshot : null);
            // Merge simpleNameIndex (ConcurrentHashMap, thread-safe).
            if (r.simpleNamesAdded != null) {
                for (java.util.Map.Entry<String, java.util.List<String>> e : r.simpleNamesAdded.entrySet()) {
                    simpleNameIndex.computeIfAbsent(e.getKey(), k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>()))
                                   .addAll(e.getValue());
                }
            }
            // Merge binaryPackages (synchronized).
            if (r.packagesAdded != null) {
                synchronized (binaryPackages) {
                    binaryPackages.addAll(r.packagesAdded);
                }
            }
            // hasRealRClasses (volatile, OK).
            if (r.hasRealRClasses) hasRealRClasses = true;
            // Report.
            if (snapKept) {
                long snapshotBytes = 0;
                for (byte[] b : r.snapshot.values()) snapshotBytes += b.length;
                jarIndexReport.put(jar, "OK " + r.entries.size()
                        + " classes, snapshot " + snapshotBytes / 1024 + " KB");
            } else {
                jarIndexReport.put(jar, "OK " + r.entries.size()
                        + " classes, handle paresseux");
            }
        }
    }

    /**
     * Résultat d'indexation d'un seul jar. Construit en
     * parallèle, mergé séquentiellement.
     */
    private static final class JarIndexResult {
        Path jar;
        Set<String> entries;
        java.util.Map<String, byte[]> snapshot;
        boolean snapshotLive;
        boolean hasRealRClasses;
        java.util.Map<String, java.util.List<String>> simpleNamesAdded;
        Set<String> packagesAdded;
        String skipReason;
        Throwable error;

        static JarIndexResult error(Path jar, Throwable t) {
            JarIndexResult r = new JarIndexResult();
            r.jar = jar;
            r.error = t;
            return r;
        }
    }

    /**
     * Indexe UN jar (thread-safe, appelé en parallèle).
     * Ne touche PAS aux structures partagées — construit un JarIndexResult
     * qui sera mergé séquentiellement.
     */
    private JarIndexResult indexOneJar(Path jar) {
        JarIndexResult result = new JarIndexResult();
        result.jar = jar;

        File f = jar.toFile();
        if (f == null || !f.isFile()) {
            result.skipReason = "SKIP absent du disque";
            return result;
        }

        try (RawZipReader.Handle zip = RawZipReader.open(jar)) {
            result.entries = new HashSet<>();
            result.snapshot = new java.util.HashMap<>();
            result.snapshotLive = true;
            result.simpleNamesAdded = new java.util.HashMap<>();
            result.packagesAdded = new HashSet<>();
            long budget = SNAPSHOT_BUDGET_BYTES;

            for (String name : zip.names()) {
                if (!name.endsWith(".class")) continue;
                result.entries.add(name);

                // Détection de la classe R (aapt2/processDebugResources).
                if (!result.hasRealRClasses) {
                    int slash = name.lastIndexOf('/');
                    String simple = slash < 0 ? name : name.substring(slash + 1);
                    if (simple.equals("R.class") || simple.startsWith("R$")) {
                        result.hasRealRClasses = true;
                    }
                }

                String internal = name.substring(0, name.length() - 6);
                // Package prefixes — on accumule dans packagesAdded au lieu
                // de binaryPackages directement.
                addPackagePrefixes(internal, result.packagesAdded);

                // Index simpleName → FQN — on accumule dans simpleNamesAdded.
                indexClassNameLocal(internal, result.simpleNamesAdded);

                if (result.snapshotLive && budget > 0) {
                    long sz = estimateSize(zip, name);
                    budget -= sz;
                    if (budget <= 0) {
                        result.snapshotLive = false;
                        result.snapshot.clear();
                    } else if (sz > 0) {
                        try {
                            byte[] bytes = zip.read(name);
                            if (bytes != null) {
                                result.snapshot.put(name, bytes);
                            }
                        } catch (Throwable t) {
                            // Entrée illisible — on garde le set complet
                            // (tracée en debug).
                            LOG.log(Level.FINE, "Snapshot d'entrée de jar illisible", t);
                        }
                    }
                }
            }

            if (result.entries.isEmpty()) {
                result.skipReason = "SKIP 0 .class (" + zip.names().size()
                        + " entrées — sources.jar ?)";
            }
            return result;
        } catch (Throwable t) {
            result.error = t;
            return result;
        }
    }

    /**
     * Indexe un nom de classe dans une map LOCALE (thread-safe :
     * indexOneJar est appelé en parallèle) — le merge dans
     * {@link #simpleNameIndex} se fait séquentiellement ensuite.
     */
    private static void indexClassNameLocal(String internalName,
                                            java.util.Map<String, java.util.List<String>> localIndex) {
        String dotted = internalName.replace('/', '.');
        int lastDot = dotted.lastIndexOf('.');
        String simple = lastDot < 0 ? dotted : dotted.substring(lastDot + 1);
        if (simple.isEmpty() || simple.contains("$")) return;
        if ("module-info".equals(simple) || "package-info".equals(simple)) return;
        localIndex.computeIfAbsent(simple, k -> new java.util.ArrayList<>()).add(dotted);
    }

    /**
     * Estime la taille décompressée d'une entrée depuis le répertoire
     * central (usize si renseigné, sinon heuristique csize×3).
     */
    private static long estimateSize(RawZipReader.Handle zip, String name) {
        RawZipReader.EntryInfo info = zip.info(name);
        if (info == null) return 0;
        if (info.usize > 0) return info.usize;
        return info.csize > 0 ? info.csize * 3 : 0;
    }

    /**
     * @return les sous-paquets DIRECTS de {@code path} (max 200 segments),
     *         NON filtrés par préfixe — le filtrage (matcher gradué :
     *         prefix/camel-hump/substring) remonte à l'appelant
     *         (packageChildren), pattern projet de référence où l'index pré-filtre
     *         mais où le matcher fait foi.
     *
     *         <p>Le test s'effectue sur la FRONTIÈRE DE POINT
     *         ({@code pkg.startsWith(dotPath + ".")}) : un simple
     *         {@code pkg.startsWith(dotPath)} matcherait le paquet
     *         LUI-MÊME ({@code "android".startsWith("android")}) et rendrait
     *         le {@code substring(dotPath.length() + 1)} invalide ; il
     *         exclut AUSSI le faux-positif « androidx est un sous-paquet de
     *         android ». projet de référence filtre les segments avec son
     *         PrefixMatcher gradué (camel-hump « cV » → « contentView »),
     *         d'où le filtrage remonté dans packageChildren qui construit
     *         le matcher.</p>
     */
    java.util.List<String> subPackages(String path) {
        scanJrtIfNeeded();
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        String dotPath = path == null ? "" : path;
        String prefixDot = dotPath.isEmpty() ? "" : dotPath + ".";
        java.util.function.Consumer<String> consider = pkg -> {
            if (pkg == null || pkg.isEmpty()) return;
            if (!dotPath.isEmpty() && !pkg.startsWith(prefixDot)) return;
            String tail = dotPath.isEmpty() ? pkg : pkg.substring(prefixDot.length());
            if (tail.isEmpty() || tail.indexOf('.') >= 0) return; // direct seulement
            out.add(tail);
        };
        // Côté jars : index disque PRÊT (autorité) ou index mémoire.
        // NOTE : la SPI rend les sous-paquets en NOMS COMPLETS
        // (« alpha.sous ») — le contrat du moteur rend des SEGMENTS
        // relatifs au chemin demandé (« sous ») : la conversion se
        // fait ici (le jrt et l'index mémoire fournissent déjà des
        // paquets complets au `consider`).
        boolean indexDisqueUtilise = false;
        if (indexDisquePret()) {
            try {
                for (String sous : indexNoms.sousPaquetsDirects(dotPath)) {
                    if (sous == null || sous.isEmpty()) continue;
                    out.add(sous.startsWith(prefixDot)
                            ? sous.substring(prefixDot.length()) : sous);
                }
                indexDisqueUtilise = true;
            } catch (Throwable t) {
                LOG.log(Level.FINE, "sousPaquetsDirects a levé — repli mémoire", t);
            }
        }
        if (!indexDisqueUtilise) {
            binaryPackages.forEach(consider);
        }
        if (jrtPackages != null) jrtPackages.forEach(consider);
        java.util.List<String> all = new ArrayList<>(out);
        return all.size() > 200 ? new ArrayList<>(all.subList(0, 200)) : all;
    }

    /**
     * Les types DIRECTEMENT dans le paquet {@code pkg} — portage
     * exact de {@code index.exactAll<ClassNameValue>(PACKAGE_TYPES, q)} de
     * projet de référence (CandidateCollector.packageChildren, règle d'or n°4).
     *
     * <p>La requête est EXACTE : le paquet du FQN est {@code q}, ni un
     * sous-paquet ni un type imbriqué. Une requête
     * {@code startsWith(q + ".")} matcherait TOUTES les classes des
     * sous-paquets (2452 classes sous {@code android.**} dans android.jar)
     * puis un cap de 300 dans l'ordre ARBITRAIRE du HashMap n'inclurait
     * pas forcément les types directs ({@code android.Manifest},
     * {@code android.R}) — l'ordre de hachage DIFFÈRE entre la JVM desktop
     * et ART sur appareil. La requête EXACTE est DÉTERMINISTE,
     * dédoublonnée (TreeSet) et couvre les trois sources : jars du
     * classpath, image jrt du JDK, sources du projet.</p>
     *
     * @return les FQN des types de premier niveau dont le paquet est
     *         EXACTEMENT {@code pkg}, triés, sans cap arbitraire (borne de
     *         sécurité 1000 jamais atteinte en pratique — projet de référence
     *         exactAll est non borné).
     */
    java.util.List<String> directTypesInPackage(String pkg) {
        scanJrtIfNeeded();
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        if (pkg == null || pkg.isEmpty()) return new ArrayList<>(out);
        String pref = pkg + ".";
        java.util.function.Consumer<String> consider = fqn -> {
            if (fqn == null || !fqn.startsWith(pref)) return;
            // Type DIRECT de premier niveau : aucun point après le préfixe
            // (sous-paquet exclu) ni '$' (classe interne/imbriquée exclue —
            // le jrt indexe Map$Entry sous « java.util.Map$Entry », la
            // règle d'or n°1 de packageChildren ne propose QUE les types
            // de premier niveau, comme le PACKAGE_TYPES du projet de référence).
            if (fqn.indexOf('.', pref.length()) >= 0) return;
            if (fqn.indexOf('$') >= 0) return;
            out.add(fqn);
        };
        // Côté jars : index disque PRÊT (la requête EXACTE « ni plus ni
        // moins » est native côté index) ou balayage de l'index mémoire.
        boolean indexDisqueUtilise = false;
        if (indexDisquePret()) {
            try {
                for (String fqn : indexNoms.typesDansPaquet(pkg)) {
                    if (fqn != null) out.add(fqn);
                }
                indexDisqueUtilise = true;
            } catch (Throwable t) {
                LOG.log(Level.FINE, "typesDansPaquet a levé — repli mémoire", t);
            }
        }
        if (!indexDisqueUtilise) {
            for (java.util.List<String> fqns : simpleNameIndex.values()) {
                fqns.forEach(consider);
            }
        }
        if (jrtIndex != null) jrtIndex.keySet().forEach(consider);
        for (String fqn : sourceTypesCached()) consider.accept(fqn);
        java.util.List<String> all = new ArrayList<>(out);
        return all.size() > 1000 ? new ArrayList<>(all.subList(0, 1000)) : all;
    }

    java.util.List<String> findTypesByPrefix(String prefix, int limit) {
        return findTypesByPrefix(new EcjPrefixMatcher(prefix), limit);
    }

    /**
     * Tous les FQN dont le nom simple EST EXACTEMENT
     * {@code simpleName} (classpath jars + image jrt + sources projet),
     * dédoublonnés et triés (plus courts d'abord).
     *
     * <p>Sert au quick-fix « Importer X » sur référence non résolue — le
     * portage du {@code AddImportQuickFixProvider} du projet de référence
     * ({@code index.fuzzyAll<ClassNameValue>(CLASS_NAMES, name, 50)
     * .filter { it.value.fqn.substringAfterLast('.') == name }}) : les
     * candidats d'import viennent de l'index de noms de classes.</p>
     */
    java.util.List<String> fqnsForSimpleName(String simpleName) {
        scanJrtIfNeeded();
        java.util.TreeSet<String> dedup = new java.util.TreeSet<>(
                java.util.Comparator.comparingInt(String::length)
                        .thenComparing(java.util.Comparator.naturalOrder()));
        if (simpleName == null || simpleName.isEmpty()) {
            return new ArrayList<>(dedup);
        }
        // Côté jars : index disque PRÊT (recherche binaire par préfixe,
        // puis filtre EXACT — les noms égaux au simpleName trient AVANT
        // leurs extensions lexicographiques, donc dans la fenêtre) ;
        // sinon l'index mémoire (accès direct par clé).
        if (indexDisquePret()) {
            try {
                java.util.Map<String, java.util.List<String>> graines =
                        nomsDepuisIndex(simpleName, Math.max(64, simpleName.length() * 16));
                java.util.List<String> exact = graines.get(simpleName);
                if (exact != null) {
                    dedup.addAll(exact);
                }
            } catch (Throwable t) {
                LOG.log(Level.FINE, "fqnsForSimpleName: index a levé — repli mémoire", t);
                java.util.List<String> mem = simpleNameIndex.get(simpleName);
                if (mem != null) dedup.addAll(mem);
            }
        } else {
            java.util.List<String> exact = simpleNameIndex.get(simpleName);
            if (exact != null) {
                dedup.addAll(exact);
            }
        }
        if (jrtIndex != null) {
            for (String fqn : jrtIndex.keySet()) {
                int dot = fqn.lastIndexOf('.');
                String s = dot < 0 ? fqn : fqn.substring(dot + 1);
                if (simpleName.equals(s)) dedup.add(fqn);
            }
        }
        for (String fqn : sourceTypesCached()) {
            int dot = fqn.lastIndexOf('.');
            String s = dot < 0 ? fqn : fqn.substring(dot + 1);
            if (simpleName.equals(s)) dedup.add(fqn);
        }
        return new ArrayList<>(dedup);
    }

    /**
     * Recherche de types par matcher GRADUÉ (portage
     * {@code index.fuzzyAll<ClassNameValue>(CLASS_NAMES, prefix, n)} de
     * projet de référence) : le simple name peut matcher par préfixe sensible,
     * préfixe insensible, camel-hump ou substring (requêtes >= 3
     * caractères) — un simple startsWith insensible raterait
     * systématiquement les humps.
     */
    java.util.List<String> findTypesByPrefix(EcjPrefixMatcher matcher, int limit) {
        scanJrtIfNeeded(); // complète l'index depuis l'image jrt (desktop)
        java.util.List<String> out = new ArrayList<>();
        // Préfixe VIDE autorisé — compléter juste après un point dans un
        // import (« import androidx.appcomp| » sans avoir encore tapé de
        // lettres) doit proposer les types du paquet, pas une liste vide.
        // Pas de coupure prémature « limit*3 » : l'ordre de simpleNameIndex
        // (HashMap) est arbitraire, une coupe pourrait éjecter les candidats
        // essentiels (java.lang.String) au profit de types exotiques. Le
        // parcours complet (~14k entrées) reste sub-ms.
        //
        // ÉTAPE 3 lot C : à l'état PRÊT, le côté jars vit de l'INDEX DISQUE
        // (recherche binaire par graine — tue le balayage W3 mesuré :
        // 109 ms p50 à 250 jars). La graine = indexPrefix() du matcher
        // (préfixe plain garanti, premier caractère pour le hump), en
        // DEUX variantes de casse du premier caractère (l'index est
        // sensible, le matcher matche insensible) ; le matcher gradué
        // post-filtre (grades réels : EXACT/PREFIX/PREFIX_CI/HUMP).
        // Écart documenté : les candidats SUBSTRING (grade démoté,
        // requêtes >= 3 caractères) du côté jars ne sont plus servis à
        // l'état PRÊT — ils sortaient du balayage intégral W3 ; côté jrt
        // et sources (ensembles petits), ils restent servis.
        if (indexDisquePret()) {
            try {
                java.util.Map<String, java.util.List<String>> graines =
                        nomsDepuisIndexGraine(matcher, limit);
                for (java.util.Map.Entry<String, java.util.List<String>> e
                        : graines.entrySet()) {
                    if (matcher.matches(e.getKey())) {
                        out.addAll(e.getValue());
                    }
                }
            } catch (Throwable t) {
                LOG.log(Level.FINE, "findTypesByPrefix: index a levé — repli mémoire", t);
                for (java.util.Map.Entry<String, java.util.List<String>> e
                        : simpleNameIndex.entrySet()) {
                    if (matcher.matches(e.getKey())) {
                        out.addAll(e.getValue());
                    }
                }
            }
        } else {
            for (java.util.Map.Entry<String, java.util.List<String>> e
                    : simpleNameIndex.entrySet()) {
                if (matcher.matches(e.getKey())) {
                    out.addAll(e.getValue());
                }
            }
        }
        // Aussi le jrt (desktop) si scanné.
        if (jrtIndex != null) {
            for (String fqn : jrtIndex.keySet()) {
                int dot = fqn.lastIndexOf('.');
                String simple = dot < 0 ? fqn : fqn.substring(dot + 1);
                if (matcher.matches(simple)) out.add(fqn);
                if (out.size() >= limit * 5) break;
            }
        }
        // Types du projet (racines sources) — via le CACHE TTL
        // (sourceTypeFqns) au lieu d'un Files.walk complet PAR APPEL
        // (I/O disque Android à chaque frappe de complétion).
        for (String fqn : sourceTypesCached()) {
            int dot = fqn.lastIndexOf('.');
            String simple = dot < 0 ? fqn : fqn.substring(dot + 1);
            if (matcher.matches(simple)) {
                out.add(fqn);
            }
        }
        // Tri : plus courts d'abord (java.util.List avant java.util.concurrent.List),
        // dédupliqué.
        java.util.TreeSet<String> dedup = new java.util.TreeSet<>(
                java.util.Comparator.comparingInt(String::length)
                        .thenComparing(java.util.Comparator.naturalOrder()));
        dedup.addAll(out);
        return new ArrayList<>(new ArrayList<>(dedup)
                .subList(0, Math.min(limit, dedup.size())));
    }

    /**
     * Grains de noms depuis l'index disque pour un préfixe EXACT (sensible
     * à la casse) — deux variantes de casse du premier caractère
     * (l'index trie en ordre binaire sensible, le matcher du consommateur
     * matche insensible : « tex » doit atteindre « TextView »).
     *
     * <p>Fusionne les porteurs par nom simple (le premier porteur garde
     * la préséance de classpath — l'ordre des variantes importe peu,
     * les noms diffèrent par la casse initiale).</p>
     */
    private java.util.Map<String, java.util.List<String>> nomsDepuisIndex(
            String prefixe, int limite) {
        java.util.Map<String, java.util.List<String>> fusion = new java.util.HashMap<>();
        for (String graine : variantesCasse(prefixe)) {
            java.util.Map<String, java.util.List<String>> part =
                    indexNoms.nomsParPrefixe(graine, limite);
            if (part == null) continue;
            for (java.util.Map.Entry<String, java.util.List<String>> e
                    : part.entrySet()) {
                fusion.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                        .addAll(e.getValue());
            }
        }
        return fusion;
    }

    /**
     * Grains pour la complétion : la graine garantie du matcher
     * ({@link EcjPrefixMatcher#indexPrefix} — préfixe plain complet,
     * premier caractère pour une requête hump) et sa limite ADAPTÉE :
     * une requête hump doit balayer une part suffisante de la plage du
     * premier caractère pour ne pas perdre le rappel (les matches hump
     * sont dispersés dans la plage triée), une requête plain n'a besoin
     * que d'une fenêtre au-delà de la limite.
     */
    private java.util.Map<String, java.util.List<String>> nomsDepuisIndexGraine(
            EcjPrefixMatcher matcher, int limit) {
        String graine = matcher.indexPrefix();
        int limite;
        if (graine.isEmpty()) {
            limite = Math.max(limit * 4, 400);
        } else if (matcher.isHumpQuery()) {
            limite = Math.max(limit * 20, 2000);
        } else {
            limite = Math.max(limit * 8, 400);
        }
        return nomsDepuisIndex(graine, limite);
    }

    /**
     * Variantes de casse du premier caractère d'un préfixe (au plus
     * deux) : {@code "tex"} → {@code ["tex", "Tex"]},
     * {@code "Tex"} → {@code ["Tex", "tex"]}, {@code ""} →
     * {@code [""]}. Un caractère sans casse rend une seule variante.
     */
    private static java.util.List<String> variantesCasse(String prefixe) {
        java.util.List<String> variantes = new ArrayList<>(2);
        if (prefixe == null || prefixe.isEmpty()) {
            variantes.add("");
            return variantes;
        }
        variantes.add(prefixe);
        char c0 = prefixe.charAt(0);
        char autre = Character.isUpperCase(c0)
                ? Character.toLowerCase(c0)
                : Character.toUpperCase(c0);
        if (autre != c0) {
            variantes.add(autre + prefixe.substring(1));
        }
        return variantes;
    }

    /**
     * FQN des sources projet, en cache TTL
     * ({@link #SOURCE_INDEX_TTL_MS}). Le premier appel (ou un cache
     * périmé) déclenche UN walk par racine source ; les suivants servent
     * la liste en mémoire — zéro I/O.
     */
    private java.util.List<String> sourceTypesCached() {
        long now = System.currentTimeMillis();
        java.util.List<String> cached = sourceTypeFqns;
        if (cached != null && now - sourceTypeFqnsStamp < SOURCE_INDEX_TTL_MS) {
            return cached;
        }
        synchronized (this) {
            // Double-check après acquisition du verrou.
            if (sourceTypeFqns != null
                    && System.currentTimeMillis() - sourceTypeFqnsStamp
                            < SOURCE_INDEX_TTL_MS) {
                return sourceTypeFqns;
            }
            java.util.List<String> fqns = new ArrayList<>(64);
            for (Path root : sourceRoots) {
                try (var walk = Files.walk(root, 8)) {
                    walk.filter(f -> f.toString().endsWith(".java")
                                    && Files.isRegularFile(f))
                        .forEach(f -> {
                            String rel = root.relativize(f).toString()
                                    .replace('\\', '/');
                            if (rel.endsWith(".java")) rel = rel.substring(0,
                                    rel.length() - 5);
                            fqns.add(rel.replace('/', '.'));
                        });
                } catch (Throwable t) {
                    // Racine illisible — tracée en debug.
                    LOG.log(Level.FINE, "Racine de sources illisible", t);
                }
            }
            sourceTypeFqns = fqns;
            sourceTypeFqnsStamp = System.currentTimeMillis();
            return fqns;
        }
    }

    /** Ajoute chaque préfixe pointé du nom interne ({@code a/b/C.class}
     *  ajoute {@code a} et {@code a.b}). */
    static void addPackagePrefixes(String internalName, Set<String> out) {
        String dotted = internalName.replace('/', '.');
        int pkgEnd = dotted.lastIndexOf('.');
        if (pkgEnd <= 0) return;
        out.add(dotted.substring(0, pkgEnd));
        int dot = dotted.indexOf('.');
        while (dot > 0 && dot < pkgEnd) {
            out.add(dotted.substring(0, dot));
            dot = dotted.indexOf('.', dot + 1);
        }
    }

    // ─── jrt (desktop / tests / JDK externe via jrt-fs.jar) ────────────

    /** FileSystem jrt ouvert (chargé depuis jrt-fs.jar si home externe). */
    private java.nio.file.FileSystem jrtFileSystem;
    /** ClassLoader du jrt-fs.jar — maintenu en vie avec le FileSystem. */
    private java.net.URLClassLoader jrtFsLoader;
    /**
     * True si {@link #jrtFileSystem} est le FileSystem jrt SHARED du runtime
     * courant (desktop/tests uniquement). Dans ce cas, {@link #dispose()}
     * NE LE FERME PAS — le fermer casserait toute la JVM
     * ({@code FileSystems.getFileSystem("jrt:/")} est un singleton global).
     *
     * <p>Pattern <strong>JrtImage</strong> du projet de référence
     * ({@code dev.ide.lang.jdt.env.JrtImage.kt:271-275}) : le jrt du runtime
     * courant est délibérément jamais fermé.</p>
     */
    private boolean jrtFileSystemShared;

    private synchronized boolean isJrtPackage(String pkg) {
        scanJrtIfNeeded();
        return jrtPackages != null && jrtPackages.contains(pkg);
    }

    /**
     * Ouvre (et met en cache) le FileSystem jrt du {@link #jdkHome} :
     * <ul>
     *   <li>home == runtime courant → FileSystem jrt intégré
     *       ({@code FileSystems.getFileSystem}) — <strong>marqué shared</strong>,
     *       jamais fermé (singleton JVM global) ;</li>
     *   <li>home externe (JDK Termux depuis l'app ART, autre JDK en test) →
     *       chargement de {@code <home>/lib/jrt-fs.jar} dans un
     *       URLClassLoader et instanciation de son fournisseur jrt avec
     *       {@code java.home = <home>} — la façon dont IntelliJ/projet de référence
     *       lisent un JDK externe. Pur Java : fonctionne sur ART.</li>
     * </ul>
     */
    private java.nio.file.FileSystem openJrtFileSystem() throws Exception {
        if (jrtFileSystem != null) return jrtFileSystem;
        if (!jrtAvailable) return null;

        java.nio.file.Path current = Paths.get(System.getProperty("java.home", ""));
        boolean sameHome = current.toAbsolutePath().normalize()
                .equals(jdkHome.toAbsolutePath().normalize());
        if (sameHome) {
            // FileSystem jrt SHARED du runtime — ne jamais
            // close(), sous peine de casser toute la JVM desktop.
            jrtFileSystem = java.nio.file.FileSystems.getFileSystem(
                    java.net.URI.create("jrt:/"));
            jrtFileSystemShared = true;
            return jrtFileSystem;
        }

        Path fsJar = jdkHome.resolve("lib/jrt-fs.jar");
        if (!Files.isRegularFile(fsJar)) return null;
        // Wrap dans un try pour fermer l'URLClassLoader sur les chemins
        // d'erreur (provider jrt non trouvé, exception lors de
        // newFileSystem, etc.) — sinon le loader serait leaké si on
        // retournait null ou si une exception était jetée avant
        // l'affectation à jrtFsLoader.
        java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[]{fsJar.toUri().toURL()},
                EcjCompilerEnvironment.class.getClassLoader());
        try {
            for (java.nio.file.spi.FileSystemProvider provider : java.util.ServiceLoader.load(
                    java.nio.file.spi.FileSystemProvider.class, loader)) {
                if ("jrt".equals(provider.getScheme())) {
                    jrtFileSystem = provider.newFileSystem(java.net.URI.create("jrt:/"),
                            java.util.Collections.singletonMap(
                                    "java.home", jdkHome.toString()));
                    jrtFileSystemShared = false;  // FS dédié — on peut le fermer.
                    jrtFsLoader = loader;  // ne garder la référence que si succès.
                    return jrtFileSystem;
                }
            }
            // Aucun provider jrt trouvé — fermer le loader (best-effort).
            try { loader.close(); } catch (Throwable t) {
                LOG.log(Level.FINE, "Fermeture du loader jrt-fs échouée", t);
            }
            return null;
        } catch (Throwable t) {
            // Exception lors de newFileSystem — fermer le loader (best-effort).
            try { loader.close(); } catch (Throwable tClose) {
                LOG.log(Level.FINE, "Fermeture du loader jrt-fs échouée", tClose);
            }
            LOG.warning("jrt-fs initialization failed: " + t);
            return null;
        }
    }

    private byte[] jrtBytes(String fqcn) {
        scanJrtIfNeeded();
        Path p = jrtIndex == null ? null : jrtIndex.get(fqcn);
        if (p == null) return null;
        try (InputStream in = Files.newInputStream(p)) {
            return ArtCompatInputStream.readAllBytes(in);
        } catch (Throwable t) {
            // Lecture jrt illisible — best-effort, tracée en debug.
            LOG.log(Level.FINE, "Lecture jrt illisible", t);
        }
        return null;
    }

    private synchronized void scanJrtIfNeeded() {
        if (jrtIndex != null || !jrtAvailable || jrtScanAttempted) return;
        // UNE SEULE tentative (cache négatif) : ecj appelle isPackage des
        // milliers de fois par résolution — si le chargement jrt-fs échoue
        // (JDK externe incompatible, modules corrompu…), chaque appel
        // recréerait un URLClassLoader (fuite de son JarFile → rafales de
        // warnings « A resource failed to call ZipFile.close » sur ART,
        // ~5 ms/appel). Après un échec, on ne retente PLUS JAMAIS : les
        // appels suivants retombent à un test de flag O(1).
        jrtScanAttempted = true;
        try {
            java.nio.file.FileSystem fs = openJrtFileSystem();
            if (fs == null) {
                closeJrtFsLoaderQuietly();
                return;
            }
            Path modules = fs.getPath("/modules");
            // Le chemin jrt est /modules/<MODULE>/<pkg>/<Type>.class — le nom
            // du module varie (java.base, java.xml, jdk.crypto.ec, …) : on
            // construit l'index FQCN → Path en UN seul walk (pattern JrtImage
            // du projet de référence — ~40k entrées, desktop seulement).
            java.util.Map<String, Path> index = new java.util.HashMap<>(65_536);
            Set<String> pkgs = new HashSet<>(8192);
            try (var walk = Files.walk(modules)) {
                walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                    String rel = modules.relativize(p).toString();
                    int slash = rel.indexOf('/');
                    if (slash < 0) return;
                    String noModule = rel.substring(slash + 1)
                            .replaceAll("\\.class$", "");
                    String fqcn = noModule.replace('/', '.');
                    if (fqcn.endsWith(".module-info") || fqcn.endsWith(".package-info")) {
                        return;
                    }
                    // Parité publicBytecodeKind du projet de référence : le
                    // ClassNameIndex/PackageTypesIndex du projet de référence n'indexe
                    // que les types PUBLICS des SDK/libs (access flag ASM).
                    // Lire les 40k bytecode du jrt serait prohibitif — le
                    // filtre équivalent à coût zéro exclut les packages du
                    // JDK NON EXPORTÉS (impossible à importer depuis du code
                    // applicatif : erreur de compilation garantie).
                    if (fqcn.startsWith("jdk.internal.")
                            || fqcn.startsWith("sun.")
                            || fqcn.startsWith("com.sun.")) {
                        return;
                    }
                    index.putIfAbsent(fqcn, p);
                    addPackagePrefixes(noModule, pkgs);
                });
            }
            this.jrtIndex = index;
            this.jrtPackages = pkgs;
        } catch (Throwable t) {
            // Pas de jrt exploitable — les tests retomberont sur les jars.
            // Le loader éventuellement créé à mi-chemin est REFERMÉ (plus
            // aucune fuite de ZipFile possible sur cette branche).
            // v0.108.0 : WARNING (était FINE) — l'échec du chargement jrt
            // est la cause racine silencieuse du faux positif
            // StringConcatFactory sur appareil (java.* de la plateforme
            // seule ne contient PAS les intrinsèques du compilateur) :
            // invisible en FINE, il doit remonter aux journaux de recette.
            // Le repli structurel des intrinsèques (StubsIntrinseques)
            // couvre les types concernés, ce diagnostic reste le signal
            // « l'image jrt du JDK toolchain n'a pas chargé — à instruire ».
            LOG.log(Level.WARNING,
                    "Scan du jrt impossible (jdkHome=" + jdkHome
                            + ") — java.* tombe sur la plateforme seule,"
                            + " les intrinsèques du compilateur passent par"
                            + " le repli de stubs. À instruire : jrt-fs"
                            + " incompatible ou JDK toolchain absent.", t);
            closeJrtFsLoaderQuietly();
        }
    }

    /**
     * Referme le {@link #jrtFsLoader} éventuel SANS lever.
     * Appelé sur chaque chemin d'échec du scan jrt : le loader porte un
     * JarFile (= ZipFile sur ART) qui, laissé orphelin, nourrit les
     * rafales CloseGuard sur device.
     */
    private void closeJrtFsLoaderQuietly() {
        java.net.URLClassLoader loader = jrtFsLoader;
        jrtFsLoader = null;
        if (loader != null) {
            try {
                loader.close();
            } catch (Throwable t) {
                // Fermeture best-effort — tracée en debug.
                LOG.log(Level.FINE, "Fermeture du loader jrt-fs échouée", t);
            }
        }
    }
}
