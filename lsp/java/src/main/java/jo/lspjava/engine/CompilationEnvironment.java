package jo.lspjava.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import jo.lspjava.ecj.EcjCompilerEngine;

/**
 * Environnement de compilation — classpath et racines sources du projet,
 * partagés par tous les services ecj de :lspjava.
 *
 * <p>Le classpath vient du Gradle Tooling (jars des dépendances +
 * android.jar du bootclasspath) ; les racines sources des modules du projet.
 * Le {@link EcjCompilerEngine} en est dérivé paresseusement, et invalidé
 * quand le classpath change. Toute l'analyse passe par le compilateur ecj
 * in-process.</p>
 *
 * <p>Toutes les méthodes mutantes sont synchronisées : les listes internes
 * peuvent être lues pendant un remplacement de contexte concurrent.</p>
 *
 * @author jo@Dev
 */
public final class CompilationEnvironment {

    private static final Logger LOG = Logger.getLogger("CompilationEnvironment");

    private final List<File> sourceDirs;
    private final List<File> classpathEntries;
    private final String complianceLevel;

    /** Home du JDK fournissant java.* (jrt) — le JDK du terminal sur ART. */
    private java.nio.file.Path jdkHome;

    /**
     * Répertoire racine des caches Gradle ({@code ~/.gradle/caches}), là où
     * le Gradle Tooling pioche les JARs des dépendances ET les
     * {@code -sources.jar}. Exposé au LSP pour la résolution de noms de
     * paramètres binaires. Peut être null sur desktop/tests (où les sources
     * viennent du src.zip du JDK courant).
     */
    private volatile java.nio.file.Path gradleUserHome;

    /** Moteur ecj dérivé — invalidé quand le classpath change. */
    private EcjCompilerEngine jdtEngine;

    public CompilationEnvironment(List<File> sourceDirs, List<File> classpathEntries, String complianceLevel) {
        this.sourceDirs = new ArrayList<>(sourceDirs);
        this.classpathEntries = new ArrayList<>(classpathEntries);
        this.complianceLevel = complianceLevel;
    }

    public CompilationEnvironment() {
        this(new ArrayList<>(), new ArrayList<>(), "17");
    }

    /**
     * Écouteur de remplacement du contexte.
     *
     * <p>Notifié après un {@link #replaceAll} effectif (listes réellement
     * changées + moteur invalidé). Le serveur LSP s'y abonne pour relancer
     * l'analyse des documents ouverts : les diagnostics obsolètes (classes
     * « inexistantes » avant le sync) s'effacent seuls dès que le vrai
     * classpath arrive — sans exiger une frappe de l'utilisateur.</p>
     */
    public interface ContextListener {
        void onContextReplaced();
    }

    private final java.util.concurrent.CopyOnWriteArrayList<ContextListener>
            contextListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addContextListener(ContextListener l) {
        if (l != null) contextListeners.add(l);
    }

    public void removeContextListener(ContextListener l) {
        if (l != null) contextListeners.remove(l);
    }

    private void fireContextReplaced() {
        for (ContextListener l : contextListeners) {
            try {
                l.onContextReplaced();
            } catch (Throwable t) {
                // Un auditeur fautif ne doit jamais casser le pipeline sync
                // — mais son échec reste visible dans les logs.
                LOG.log(Level.WARNING, "Un auditeur de contexte a levé — ignoré", t);
            }
        }
    }

    /** @return vue non modifiable des racines sources courantes. */
    public synchronized List<File> sourceDirs() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(sourceDirs));
    }

    /** @return vue non modifiable des entrées de classpath courantes. */
    public synchronized List<File> classpathEntries() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(classpathEntries));
    }

    public String complianceLevel() { return complianceLevel; }

    /**
     * @return le JDK home configuré (résolu depuis {@code ToolPaths} côté app),
     *         ou null si non encore seedé.
     */
    public java.nio.file.Path jdkHome() { return jdkHome; }

    /**
     * @return le GRADLE_USER_HOME configuré (résolu depuis {@code ToolPaths}
     *         côté app), ou null si non seedé. Le LSP y cherche les
     *         {@code -sources.jar} des dépendances.
     */
    public java.nio.file.Path gradleUserHome() { return gradleUserHome; }

    public synchronized void addSourceDir(File dir) {
        if (dir != null && dir.isDirectory() && !sourceDirs.contains(dir)) {
            sourceDirs.add(dir);
            invalidateEcjEngine();
        }
    }

    /**
     * Remplace les source dirs existants par une nouvelle liste.
     *
     * <p>Utile pour le fallback classpath seeder : au lieu d'accumuler des
     * source dirs (qui peuvent devenir obsolètes — ex. un R.java stub
     * régénéré dans un autre path), on remplace toute la liste d'un coup
     * avant d'invalider le moteur ecj.</p>
     *
     * <p>Les chemins inexistants sont filtrés. L'invalidation du moteur
     * ecj n'a lieu que si la liste change effectivement.</p>
     *
     * @param dirs la nouvelle liste de source dirs (paths absolus)
     */
    public synchronized void setSourceDirs(java.util.List<File> dirs) {
        java.util.List<File> filtered = new java.util.ArrayList<>();
        if (dirs != null) {
            for (File d : dirs) {
                if (d != null && d.isDirectory() && !filtered.contains(d)) {
                    filtered.add(d);
                }
            }
        }
        if (filtered.size() == sourceDirs.size()
                && sourceDirs.containsAll(filtered)) {
            // Pas de changement — on évite l'invalidation coûteuse.
            return;
        }
        sourceDirs.clear();
        sourceDirs.addAll(filtered);
        invalidateEcjEngine();
    }

    /**
     * Ajoute plusieurs source dirs d'un coup, avec filtrage des doublons
     * et chemins inexistants. Plus efficace que d'appeler
     * {@link #addSourceDir(File)} en boucle (une seule invalidation du
     * moteur ecj à la fin si au moins un dir a été ajouté).
     *
     * @param dirs les source dirs à ajouter (paths absolus)
     */
    public synchronized void addSourceDirs(java.util.List<File> dirs) {
        if (dirs == null || dirs.isEmpty()) return;
        boolean changed = false;
        for (File d : dirs) {
            if (d != null && d.isDirectory() && !sourceDirs.contains(d)) {
                sourceDirs.add(d);
                changed = true;
            }
        }
        if (changed) invalidateEcjEngine();
    }

    /**
     * Ajoute plusieurs classpath entries d'un coup, avec filtrage des
     * chemins inexistants. Plus efficace que d'appeler
     * {@link #addClasspathEntry(File)} en boucle (une seule invalidation).
     *
     * @param jars les JARs à ajouter (paths absolus)
     */
    public synchronized void addClasspathEntries(java.util.List<File> jars) {
        if (jars == null || jars.isEmpty()) return;
        boolean changed = false;
        for (File j : jars) {
            if (j != null && j.isFile() && !classpathEntries.contains(j)) {
                classpathEntries.add(j);
                changed = true;
            }
        }
        if (changed) invalidateEcjEngine();
    }

    /**
     * Définit le home du JDK fournissant l'image jrt (classes java.*) —
     * sur Android/ART, le JDK installé par le terminal. Invalide le moteur.
     */
    public synchronized void setJdkHome(java.nio.file.Path home) {
        if (home == null || home.equals(jdkHome)) return;
        if (!java.nio.file.Files.isRegularFile(home.resolve("lib/modules"))) return;
        this.jdkHome = home;
        invalidateEcjEngine();
    }

    /**
     * Définit le GRADLE_USER_HOME (le {@code ~/.gradle} du layout CodeIDE).
     *
     * <p>Doit être appelé par {@code JavaLanguageServerDefinition.seedPlatform()}
     * AVANT que le Gradle Tooling ne livrera le classpath — c'est là que le
     * LSP cherche les {@code -sources.jar} attachés aux dépendances
     * ({@code ~/.gradle/caches/modules-2/files-2.1/<group>/<artifact>/
     * <version>/<hash>/<artifact>-<version>-sources.jar}). Sans cette
     * configuration, le résolveur de noms binaires tombe sur
     * {@code System.getProperty("java.home")} qui sur Android pointe vers le
     * runtime ART, PAS vers le JDK Termux — et les noms de paramètres des
     * méthodes AndroidX restent à {@code arg0, arg1…}.</p>
     *
     * <p>Note : n'invalide PAS le moteur ecj — le GRADLE_USER_HOME est lu
     * paresseusement par {@link EcjBinarySourceNames}, pas par le compilateur.</p>
     */
    public void setGradleUserHome(java.nio.file.Path gradleUserHome) {
        this.gradleUserHome = gradleUserHome;
    }

    // Note : toutes les méthodes add*/set* sont synchronisées sur this pour
    // cohérence avec replaceAll (mutation concurrente des listes partagées).
    public synchronized void addClasspathEntry(File entry) {
        if (entry == null || !entry.isFile()) return;
        // Garde anti-accumulation : un entry déjà présent ne re-déclenche
        // pas d'invalidation (évite re-index complet à chaque seed redondant).
        if (classpathEntries.contains(entry)) return;
        classpathEntries.add(entry);
        invalidateEcjEngine();
    }

    /**
     * Remplacement atomique du contexte de compilation.
     *
     * <p>Au lieu d'accumuler des entrées en provenance de sources hétérogènes
     * (cache, seeder fallback, résolveur léger… — accumulation qui laisse
     * vivre d'anciennes entrées obsolètes comme l'ex-stub R.java), l'app
     * fournit la liste complète et auteure : exactement ce que le Gradle
     * Tooling a résolu (classpaths, sourceSets) plus les artefacts réellement
     * générés par AGP ({@code R.jar}, {@code build/generated/source/**}).
     * Tout ce qui n'est pas dans ces listes disparaît.</p>
     *
     * <p>Une seule invalidation du moteur ecj, et seulement si les listes ont
     * effectivement changé — un sync redondant (même modèle) devient un
     * no-op système : zéro re-index, zéro GC, zéro ré-allocation.</p>
     *
     * @param newSourceDirs      liste complète des racines sources (null/vide
     *                           = vider) — chemins inexistants filtrés
     * @param newClasspathEntries liste complète des jars (idem)
     */
    public void replaceAll(java.util.List<File> newSourceDirs,
                           java.util.List<File> newClasspathEntries) {
        java.util.List<File> dirs = new ArrayList<>();
        if (newSourceDirs != null) {
            for (File d : newSourceDirs) {
                if (d != null && d.isDirectory() && !dirs.contains(d)) {
                    dirs.add(d);
                }
            }
        }
        java.util.List<File> jars = new ArrayList<>();
        if (newClasspathEntries != null) {
            for (File j : newClasspathEntries) {
                if (j != null && j.isFile() && !jars.contains(j)) {
                    jars.add(j);
                }
            }
        }
        boolean changed = false;
        synchronized (this) {
            if (dirs.size() != sourceDirs.size()
                    || !sourceDirs.containsAll(dirs)) {
                sourceDirs.clear();
                sourceDirs.addAll(dirs);
                changed = true;
            }
            if (jars.size() != classpathEntries.size()
                    || !classpathEntries.containsAll(jars)) {
                classpathEntries.clear();
                classpathEntries.addAll(jars);
                changed = true;
            }
            if (changed) {
                invalidateEcjEngine();
            }
        }
        // Hors verrou : notifie les abonnés d'un changement effectif
        // (live-republish des diagnostics côté serveur).
        if (changed) {
            fireContextReplaced();
        }
    }

    /**
     * Moteur de compilateur ecj partagé — même classpath/source dirs pour
     * diagnostics, hover, complétion, renommage, formatage…
     */
    public synchronized EcjCompilerEngine jdt() {
        if (jdtEngine == null) {
            jdtEngine = new EcjCompilerEngine(sourceDirs, classpathEntries,
                    complianceLevel, jdkHome);
            // Propage le GRADLE_USER_HOME au moteur — pour la résolution
            // des noms de paramètres binaires (recherche des -sources.jar).
            jdtEngine.setGradleUserHome(gradleUserHome);
        }
        return jdtEngine;
    }

    /**
     * Permet de tester si le moteur ecj a déjà été initialisé sans le
     * construire paresseusement. Utilisé par {@code didChange} pour éviter
     * de déclencher {@code buildJarIndex} (cold start 2-6 s) sur le thread
     * LSP4J juste pour invalider un cache inexistant.
     *
     * @return {@code true} si {@link #jdt()} a déjà été appelé au moins une fois
     *         et que le moteur n'a pas été invalidé depuis.
     */
    public synchronized boolean isEcjInitialized() {
        return jdtEngine != null;
    }

    private synchronized void invalidateEcjEngine() {
        if (jdtEngine != null) {
            jdtEngine.dispose();
            jdtEngine = null;
        }
    }

    /**
     * Libère les ressources natives du moteur ecj (handles de jars ouverts,
     * FileSystem jrt, ClassLoader du jrt-fs.jar). Appelé par
     * {@link jo.lspjava.lsp.JavaLanguageServer#shutdown()} — sinon le GC
     * d'ART finalise les {@code ZipFile} sans {@code close()}, ce qui
     * déclenche les warnings {@code A resource failed to call ZipFile.close}
     * du CloseGuard.
     *
     * <p>Idempotent : appels multiples sans effet après le premier.</p>
     */
    public synchronized void dispose() {
        invalidateEcjEngine();
    }
}
