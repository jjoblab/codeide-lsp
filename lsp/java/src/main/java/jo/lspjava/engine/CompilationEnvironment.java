package jo.lspjava.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import jo.lspjava.ecj.EcjCompilerEngine;

/**
 * Environnement de compilation — UN contexte PAR MODULE Gradle (mission
 * « LSP Java » étape 3 lot E, docs/LSP_JAVA.md §15.7) : racines sources
 * et classpath propres à chaque module, moteur {@link EcjCompilerEngine}
 * dérivé PAR MODULE — son verrou, ses caches (parse, localisation,
 * fast-diagnose).
 *
 * <p>Le classpath vient du Gradle Tooling (jars des dépendances +
 * android.jar du bootclasspath) ; les racines sources des modules du
 * projet. Avant le lot E, UN contexte plat servait tout le projet : une
 * complétion du module A bloquait le diagnostic du module B (tous les
 * services du moteur sont {@code synchronized} sur l'instance). Désormais
 * chaque module a SON moteur — les verrous ne se chevauchent plus, et
 * l'invalidation du contexte de A (sync Gradle de A) ne vide pas les
 * caches de B.</p>
 *
 * <p>Le contexte « défaut » (clé {@link #MODULE_DEFAUT}) conserve le
 * comportement historique : les appels non qualifiés (
 * {@link #replaceAll}, {@link #jdt()}…) s'y adressent — les services
 * existants et les tests ne changent PAS (migration en deux commits
 * verts, §15.7 point 6 : le commit (a) n'altère aucun consommateur).</p>
 *
 * <p>Le niveau de compliance, le JDK home et l'index disque
 * ({@code IndexNoms}) restent PARTAGÉS entre les modules d'un même projet
 * (une seule plateforme) : les changer invalide TOUS les moteurs. Le
 * routage fichier → module — quelle clé passer à {@link #jdt(String)} —
 * est un rôle de l'HÔTE (l'association fichier → module de
 * {@code :lsp:model}, étape 4) : le moteur ne fait que porter la carte
 * des contextes. Les segments disque de l'index restent mutualisés
 * (ADR 0111 §2) — c'est l'ISOLATION mémoire des moteurs qui est
 * recherchée, pas la duplication de l'index persistant.</p>
 *
 * <p>Contrat de verrouillage : les méthodes du contexte défaut sont
 * synchronisées sur {@code this} (contrat historique inchangé) ; les
 * méthodes qualifiées verrouillent le contexte DU module visé — le
 * remplacement du module A n'attend pas celui du module B. Ordre
 * d'acquisition interdit : un contexte ne prend JAMAIS {@code this} (les
 * invalidations globales prennent {@code this} puis chaque contexte).</p>
 *
 * @author jo@Dev
 */
public final class CompilationEnvironment {

    private static final Logger LOG = Logger.getLogger("CompilationEnvironment");

    /**
     * Clé du contexte par défaut — les appels non qualifiés
     * ({@link #jdt()}, {@link #replaceAll(List, List)}…) s'y adressent,
     * tout comme {@link #jdt(String) jdt(null)} ou une clé vide passée à
     * une méthode qualifiée. Les chemins Gradle réels commencent par
     * «&nbsp;:&nbsp;» — jamais de collision.
     */
    public static final String MODULE_DEFAUT = "";

    /**
     * Contexte d'UN module — listes (filtrées, dédupliquées) + niveau
     * Java + moteur dérivé. MUTÉ sous le verrou du contexte (ou sous
     * {@code this} pour le contexte défaut — personne d'autre ne
     * verrouille un contexte défaut par la voie qualifiée : la clé vide
     * y est redirigée).
     */
    private static final class ContexteModule {
        final List<File> sourceDirs = new ArrayList<>();
        final List<File> classpathEntries = new ArrayList<>();

        /**
         * Niveau Java DU module (lot S1-a) — {@code null} = hériter du
         * niveau de l'environnement (défaut constructeur). Changer ce
         * niveau invalide le moteur (les diagnostics de niveau — var,
         * records, switch — dépendent de lui).
         */
        String complianceLevel;

        /** Moteur dérivé — invalidé quand les listes ou le niveau changent. */
        EcjCompilerEngine moteur;

        /**
         * Remplace les listes (déjà filtrées/dédupliquées par l'appelant).
         *
         * @return {@code true} si au moins une liste a effectivement
         *         changé (sinon l'appel est un no-op système — zéro
         *         invalidation, zéro re-index)
         */
        boolean remplacer(List<File> dirs, List<File> jars) {
            boolean changed = false;
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
            return changed;
        }

        /**
         * Pose le niveau Java du module ({@code null} = hériter du
         * défaut).
         *
         * @return {@code true} si le niveau a effectivement changé
         */
        boolean remplacerCompliance(String compliance) {
            boolean changed = !java.util.Objects.equals(compliance, complianceLevel);
            complianceLevel = compliance;
            return changed;
        }

        /** Dispose le moteur dérivé (idempotent : null-safe). */
        void invaliderMoteur() {
            if (moteur != null) {
                moteur.dispose();
                moteur = null;
            }
        }
    }

    /** Niveau de compliance partagé (une plateforme par projet). */
    private final String complianceLevel;

    /**
     * Contexte par défaut — comportement historique (appels non
     * qualifiés). Ses listes sont gardées par {@code this}.
     */
    private final ContexteModule contexteDefaut = new ContexteModule();

    /**
     * Contextes par module — clé : chemin Gradle du module
     * («&nbsp;:app&nbsp;», «&nbsp;:core:model&nbsp;»…, TRIMMÉ). Consultation
     * sans verrou environment ({@code ConcurrentHashMap}) ; chaque
     * contexte est muté sous SON verrou.
     */
    private final java.util.Map<String, ContexteModule> contextes =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Home du JDK fournissant java.* (jrt) — le JDK du terminal sur ART.
     * Volatile : lu à la construction des moteurs de modules HORS
     * verrou {@code this} ; écrit sous {@code this}.
     */
    private volatile java.nio.file.Path jdkHome;

    /**
     * Répertoire racine des caches Gradle ({@code ~/.gradle/caches}), là où
     * le Gradle Tooling pioche les JARs des dépendances ET les
     * {@code -sources.jar}. Exposé au LSP pour la résolution de noms de
     * paramètres binaires. Peut être null sur desktop/tests (où les sources
     * viennent du src.zip du JDK courant).
     */
    private volatile java.nio.file.Path gradleUserHome;

    /**
     * Index disque branché (étape 3 lot C, ADR 0111) — {@code null} si
     * aucun. Propagé à CHAQUE moteur dérivé (défaut et modules) ; les
     * changer invalide tous les moteurs pour que le prochain
     * {@link #jdt()} / {@link #jdt(String)} les reconstruise BRANCHÉS.
     *
     * <p>L'index est lié à un classpath : l'appelant (hôte de l'étape 4,
     * tests du lot C) garantit la cohérence — il attache l'index
     * correspondant au classpath courant et le RÉ-attache après chaque
     * remplacement effectif d'un contexte (les entrées MODULE du
     * {@code ClasspathInstantane} sont résolues en sources du frère par
     * l'appelant, qui construit les listes plates passées à
     * {@link #replaceAll} / {@link #remplacerContexteModule(String, List, List)}).</p>
     */
    private volatile jo.lspjava.api.IndexNoms indexNoms;

    /**
     * Branche l'index disque (étape 3 lot C) — voir {@link #indexNoms}.
     * Invalide TOUS les moteurs (défaut et modules — chacun est construit
     * avec CETTE référence ; lot E). Idempotent : ré-attacher la MÊME
     * instance ne re-invalide pas.
     */
    public void setIndexNoms(jo.lspjava.api.IndexNoms idx) {
        if (idx == this.indexNoms) return;
        this.indexNoms = idx;
        invaliderTousLesMoteurs();
    }

    /** L'index disque branché, ou {@code null} (transparence/tests). */
    public jo.lspjava.api.IndexNoms indexNoms() {
        return indexNoms;
    }

    public CompilationEnvironment(List<File> sourceDirs, List<File> classpathEntries, String complianceLevel) {
        contexteDefaut.sourceDirs.addAll(sourceDirs);
        contexteDefaut.classpathEntries.addAll(classpathEntries);
        this.complianceLevel = complianceLevel;
    }

    public CompilationEnvironment() {
        this(new ArrayList<>(), new ArrayList<>(), "17");
    }

    /**
     * Écouteur de remplacement du contexte.
     *
     * <p>Notifié après un remplacement EFFECTIF — {@link #replaceAll}
     * (défaut) ou {@link #remplacerContexteModule(String, List, List)}
     * (module : listes réellement changées + moteur invalidé). Le serveur
     * LSP s'y abonne pour relancer l'analyse des documents ouverts : les
     * diagnostics obsolètes (classes « inexistantes » avant le sync)
     * s'effacent seuls dès que le vrai classpath arrive — sans exiger une
     * frappe de l'utilisateur. L'auditeur est GLOBAL (pas par module) :
     * le live-republish actuel ré-analyse tous les documents ouverts ;
     * l'étape 4 (routage fichier → module) affinera si nécessaire.</p>
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

    /** @return vue non modifiable des racines sources courantes (défaut). */
    public synchronized List<File> sourceDirs() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(contexteDefaut.sourceDirs));
    }

    /** @return vue non modifiable des entrées de classpath courantes (défaut). */
    public synchronized List<File> classpathEntries() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(contexteDefaut.classpathEntries));
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
        if (dir != null && dir.isDirectory() && !contexteDefaut.sourceDirs.contains(dir)) {
            contexteDefaut.sourceDirs.add(dir);
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
        if (filtered.size() == contexteDefaut.sourceDirs.size()
                && contexteDefaut.sourceDirs.containsAll(filtered)) {
            // Pas de changement — on évite l'invalidation coûteuse.
            return;
        }
        contexteDefaut.sourceDirs.clear();
        contexteDefaut.sourceDirs.addAll(filtered);
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
            if (d != null && d.isDirectory() && !contexteDefaut.sourceDirs.contains(d)) {
                contexteDefaut.sourceDirs.add(d);
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
            if (j != null && j.isFile() && !contexteDefaut.classpathEntries.contains(j)) {
                contexteDefaut.classpathEntries.add(j);
                changed = true;
            }
        }
        if (changed) invalidateEcjEngine();
    }

    /**
     * Définit le home du JDK fournissant l'image jrt (classes java.*) —
     * sur Android/ART, le JDK installé par le terminal. Invalide TOUS
     * les moteurs (défaut et modules — chacun construit son
     * {@code jrt-fs} depuis ce home ; lot E).
     */
    public synchronized void setJdkHome(java.nio.file.Path home) {
        if (home == null || home.equals(jdkHome)) return;
        if (!java.nio.file.Files.isRegularFile(home.resolve("lib/modules"))) return;
        this.jdkHome = home;
        invaliderTousLesMoteurs();
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
     * <p>Note : n'invalide PAS les moteurs ecj (défaut ou modules) — le
     * GRADLE_USER_HOME est lu paresseusement par
     * {@link EcjBinarySourceNames}, pas par le compilateur.</p>
     */
    public void setGradleUserHome(java.nio.file.Path gradleUserHome) {
        this.gradleUserHome = gradleUserHome;
    }

    // Note : toutes les méthodes add*/set* du contexte défaut sont
    // synchronisées sur this pour cohérence avec replaceAll (mutation
    // concurrente des listes partagées).
    public synchronized void addClasspathEntry(File entry) {
        if (entry == null || !entry.isFile()) return;
        // Garde anti-accumulation : un entry déjà présent ne re-déclenche
        // pas d'invalidation (évite re-index complet à chaque seed redondant).
        if (contexteDefaut.classpathEntries.contains(entry)) return;
        contexteDefaut.classpathEntries.add(entry);
        invalidateEcjEngine();
    }

    /**
     * Remplacement atomique du contexte de compilation PAR DÉFAUT.
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
            changed = contexteDefaut.remplacer(dirs, jars);
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
     * Remplacement atomique du contexte d'UN MODULE (lot E, §15.7 point 2)
     * — sémantiques strictement identiques à {@link #replaceAll} mais
     * bornées au module demandé :
     *
     * <ul>
     *   <li>listes filtrées (chemins existants) et dédupliquées, puis
     *       comparées aux listes courantes DU MODULE ;</li>
     *   <li>remplacement effectif → invalidation du SEUL moteur du module
     *       (dispose) — les autres moteurs et leurs caches vivent ;</li>
     *   <li>sync redondant (mêmes listes) → no-op système : zéro
     *       invalidation, zéro re-index, les auditeurs ne sont pas
     *       notifiés ;</li>
     *   <li>changement effectif → les {@link ContextListener} globaux
     *       sont notifiés (délégation du mécanisme existant, hors
     *       verrou).</li>
     * </ul>
     *
     * <p>La clé {@code null} ou vide désigne le contexte défaut et est
     * REDIRIGÉE vers {@link #replaceAll} (aucune ambiguïté : les chemins
     * Gradle réels commencent par «&nbsp;:&nbsp;»). Un module inconnu est
     * créé à la volée (contexte vide avant son premier sync —
     * {@link #jdt(String)} y construit un moteur aux listes vides,
     * exactement comme le contexte défaut au démarrage).</p>
     *
     * @param module      chemin Gradle du module («&nbsp;:app&nbsp;») —
     *                    null/vide = défaut
     * @param newSourceDirs      liste complète des racines sources du module
     *                           (null/vide = vider) — chemins inexistants
     *                           filtrés
     * @param newClasspathEntries liste complète des jars du module (idem)
     */
    public void remplacerContexteModule(String module,
                                        java.util.List<File> newSourceDirs,
                                        java.util.List<File> newClasspathEntries) {
        // S1-a : sans niveau Java explicite, les SÉMANTIQUES listes restent
        // celles-ci — le niveau DU module est conservé (pas de retour au
        // défaut : un appelant qui ne connaît pas le niveau ne doit pas le
        // réinitialiser par accident).
        remplacerContexteModuleNiveau(module, newSourceDirs, newClasspathEntries, true, null);
    }

    /**
     * Remplacement atomique du contexte d'UN module AVEC SON NIVEAU JAVA
     * (lot S1-a « compliance par projet ») — sémantiques de
     * {@link #remplacerContexteModule(String, List, List)} étendues :
     *
     * <ul>
     *   <li>{@code complianceLevel} non-null : niveau Java du module
     *       (notation libre « 1.8 »/« 8 »/« 17 »/« 21 » — le moteur
     *       normalise, toute valeur hors 8/9/11/17/21 retombe sur 17) ;</li>
     *   <li>{@code complianceLevel} {@code null} : RETOUR au niveau par
     *       défaut de l'environnement (l'outil de build ne livre plus le
     *       niveau) ; la surcharge à 3 arguments ne touche PAS au niveau
     *       (il est conservé) ;</li>
     *   <li>un changement de niveau SEUL invalide le moteur du module
     *       (diagnostics de niveau à jour) et notifie les auditeurs ;</li>
     *   <li>sync redondant (listes ET niveau identiques) : no-op système.</li>
     * </ul>
     *
     * @param module      chemin Gradle du module («&nbsp;:app&nbsp;») —
     *                    null/vide = défaut (le niveau du DÉFAUT reste un
     *                    choix constructeur : redirection ignorante)
     * @param complianceLevel niveau Java du module, ou {@code null} pour
     *                    hériter du défaut
     */
    public void remplacerContexteModule(String module,
                                        java.util.List<File> newSourceDirs,
                                        java.util.List<File> newClasspathEntries,
                                        String complianceLevel) {
        remplacerContexteModuleNiveau(module, newSourceDirs, newClasspathEntries, false, complianceLevel);
    }

    /**
     * Corps commun des deux surcharges — {@code niveauNonDemande} distingue
     * « ne pas toucher au niveau » (surcharge à 3 arguments) de « poser ce
     * niveau, null = retour au défaut » (surcharge à 4 arguments).
     */
    private void remplacerContexteModuleNiveau(String module,
                                               java.util.List<File> newSourceDirs,
                                               java.util.List<File> newClasspathEntries,
                                               boolean niveauNonDemande,
                                               String complianceLevel) {
        if (module == null || module.isBlank()) {
            // Le niveau du DÉFAUT est un choix constructeur (une plateforme
            // par projet) — la voie qualifiée vide ne le mute pas.
            replaceAll(newSourceDirs, newClasspathEntries);
            return;
        }
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
        ContexteModule c = contextes.computeIfAbsent(module.trim(), k -> new ContexteModule());
        boolean changed;
        synchronized (c) {
            boolean listesChangees = c.remplacer(dirs, jars);
            boolean complianceChangee =
                    niveauNonDemande ? false : c.remplacerCompliance(complianceLevel);
            changed = listesChangees || complianceChangee;
            if (changed) {
                c.invaliderMoteur();
            }
        }
        // Hors verrou : délégation du mécanisme d'auditeurs existant.
        if (changed) {
            fireContextReplaced();
        }
    }

    /**
     * Niveau Java POSÉ sur un module (lot S1-a — observabilité/tests) :
     * {@code null} = hériter du défaut (aucun niveau propre ou jamais
     * poussé). La clé null/vide désigne le niveau du DÉFAUT.
     */
    public String complianceModule(String module) {
        if (module == null || module.isBlank()) {
            return complianceLevel;
        }
        ContexteModule c = contextes.get(module.trim());
        return c == null ? null : c.complianceLevel;
    }

    /**
     * Moteur de compilateur ecj du contexte PAR DÉFAUT — même
     * classpath/source dirs pour diagnostics, hover, complétion,
     * renommage, formatage…
     */
    public synchronized EcjCompilerEngine jdt() {
        if (contexteDefaut.moteur == null) {
            contexteDefaut.moteur = new EcjCompilerEngine(
                    contexteDefaut.sourceDirs, contexteDefaut.classpathEntries,
                    complianceLevel, jdkHome, indexNoms);
            // Propage le GRADLE_USER_HOME au moteur — pour la résolution
            // des noms de paramètres binaires (recherche des -sources.jar).
            contexteDefaut.moteur.setGradleUserHome(gradleUserHome);
        }
        return contexteDefaut.moteur;
    }

    /**
     * Moteur de compilateur ecj D'UN MODULE (lot E, §15.7 point 2) —
     * construit paresseusement depuis le contexte du module, SON verrou,
     * SES caches (parse, localisation, fast-diagnose : l'isolation est le
     * but — les segments disque de l'index restent mutualisés, ADR
     * 0111 §2). Invalidé par le prochain remplacement EFFECTIF du
     * contexte de ce module (les autres modules ne sont pas touchés).
     *
     * <p>Construit avec le compliance/JDK home/index PARTAGÉS de
     * l'environnement — un module d'un même projet vit sur la même
     * plateforme. Un module jamais configuré
     * ({@link #remplacerContexteModule}) reçoit un contexte vide :
     * son moteur fonctionne (comme le défaut au démarrage) et sera
     * invalidé au premier sync du module.</p>
     *
     * <p>La clé {@code null} ou vide est redirigée vers {@link #jdt()}
     * (défaut).</p>
     *
     * @param module chemin Gradle du module («&nbsp;:app&nbsp;»)
     * @return le moteur du module — jamais null
     */
    public EcjCompilerEngine jdt(String module) {
        if (module == null || module.isBlank()) {
            return jdt();
        }
        ContexteModule c = contextes.computeIfAbsent(module.trim(), k -> new ContexteModule());
        synchronized (c) {
            if (c.moteur == null) {
                // S1-a : le niveau DU MODULE quand il en a un posé —
                // hérité du défaut sinon (une plateforme par projet).
                c.moteur = new EcjCompilerEngine(
                        c.sourceDirs, c.classpathEntries,
                        c.complianceLevel != null ? c.complianceLevel : complianceLevel,
                        jdkHome, indexNoms);
                c.moteur.setGradleUserHome(gradleUserHome);
            }
            return c.moteur;
        }
    }

    /**
     * Routeur fichier → module — couture de l'HÔTE (découverte D6 du
     * rapport d'audit §14, ADR 0215) : le serveur LSP doit analyser un
     * fichier avec le moteur de SON module (racines sources, jars,
     * android.jar du modèle Gradle), pas avec le contexte défaut. Le lot
     * E a créé les contextes par module et {@link #jdt(String)} ; ce qui
     * manquait est le ROUTAGE — l'association fichier → module vivait
     * côté modèle (`AssociationFichier`) sans être branchée.
     *
     * <p>Le routeur est CONSULTÉ À CHAQUE REQUÊTE (jamais mis en cache
     * par l'environnement) : l'hôte lit l'instantané courant de son
     * modèle — un fichier ajouté à un nouveau sourceset route vers le
     * bon module dès la sync suivante, SANS re-pose du routeur. Un
     * routeur {@code null} (jamais posé — tests directs, packs
     * historiques) maintient le comportement historique : TOUT fichier
     * vers le contexte défaut.</p>
     */
    public interface RouteurModules {
        /**
         * Module propriétaire d'un fichier source.
         *
         * @param cheminFichier chemin ABSOLU sur disque (URI décodée)
         * @return le nom du module («&nbsp;:app&nbsp;»), ou {@code null}
         *         / vide pour le contexte défaut
         */
        String moduleDe(String cheminFichier);
    }

    /** Routeur posé par l'hôte — {@code null} = comportement historique. */
    private volatile RouteurModules routeurModules;

    /**
     * Pose le routeur fichier → module (idempotent, aucune
     * invalidation : le routeur est une LECTURE, jamais une donnée de
     * moteur).
     */
    public void setRouteurModules(RouteurModules routeur) {
        this.routeurModules = routeur;
    }

    /** Le routeur posé, ou {@code null} (transparence/tests). */
    public RouteurModules routeurModules() {
        return routeurModules;
    }

    /**
     * Moteur d'un FICHIER : celui de SON module quand le routeur
     * l'associe, sinon le contexte défaut. C'est LE point d'entrée des
     * services LSP (diagnostics, hover, complétion, navigation,
     * renommage…) — le diagnostic d'un fichier de {@code :app} vit sur
     * les racines sources et le classpath de {@code :app}, pas sur le
     * contexte défaut (découverte D6 : sans ce routage, un type du
     * MÊME PAQUET que le fichier — sans import — restait « cannot be
     * resolved » bien que le modèle l'eût nourri, car seul le contexte
     * défaut — android.jar seul — était consulté).
     *
     * <p>Le routeur est consulté HORS verrou ; {@link #jdt(String)}
     * gère ensuite le sien. Un échec du routeur ne doit JAMAIS casser
     * une requête : repli explicite sur le défaut (journal fin).</p>
     */
    public EcjCompilerEngine jdtPourFichier(String cheminFichier) {
        RouteurModules routeur = routeurModules;
        if (routeur == null || cheminFichier == null) {
            return jdt();
        }
        try {
            String module = routeur.moduleDe(cheminFichier);
            return jdt(module);
        } catch (Throwable t) {
            LOG.log(java.util.logging.Level.FINE,
                    "routage module échoué pour " + cheminFichier + " — défaut", t);
            return jdt();
        }
    }

    /**
     * Le moteur du contexte de [cheminFichier] est-il DÉJÀ construit —
     * sans le construire (miroir routé de {@link #isEcjInitialized()},
     * même usage : éviter un cold start synchrone sur le fil LSP pour
     * une pure invalidation de cache) ?
     */
    public boolean isEcjInitialisePour(String cheminFichier) {
        RouteurModules routeur = routeurModules;
        if (routeur == null || cheminFichier == null) {
            return isEcjInitialized();
        }
        try {
            return isEcjInitialized(routeur.moduleDe(cheminFichier));
        } catch (Throwable t) {
            LOG.log(java.util.logging.Level.FINE,
                    "routage module échoué pour " + cheminFichier + " — défaut", t);
            return isEcjInitialized();
        }
    }

    /**
     * Moteurs VIVANTS (défaut + modules déjà construits), ordre
     * stable (défaut d'abord, puis modules dans l'ordre d'insertion de
     * la map) — pour les requêtes SANS fichier porteur (le stub
     * décompilé d'un fqcn : le classpath pertinent est celui du module
     * appelant, déjà perdu — on sonde les moteurs qui EXISTENT, sans en
     * construire).
     */
    public java.util.List<EcjCompilerEngine> moteursVivants() {
        java.util.List<EcjCompilerEngine> out = new ArrayList<>();
        synchronized (this) {
            if (contexteDefaut.moteur != null) out.add(contexteDefaut.moteur);
        }
        for (ContexteModule c : contextes.values()) {
            synchronized (c) {
                if (c.moteur != null) out.add(c.moteur);
            }
        }
        return out;
    }

    /**
     * Permet de tester si le moteur ecj du contexte DÉFAUT a déjà été
     * initialisé sans le construire paresseusement. Utilisé par
     * {@code didChange} pour éviter de déclencher {@code buildJarIndex}
     * (cold start 2-6 s) sur le thread LSP4J juste pour invalider un
     * cache inexistant.
     *
     * @return {@code true} si {@link #jdt()} a déjà été appelé au moins une fois
     *         et que le moteur n'a pas été invalidé depuis
     */
    public synchronized boolean isEcjInitialized() {
        return contexteDefaut.moteur != null;
    }

    /**
     * Variante qualifiée de {@link #isEcjInitialized()} (lot E) — le
     * moteur du MODULE donné est-il déjà construit ? Même usage
     * (éviter un cold start synchrone sur le thread LSP pour une pure
     * invalidation de cache).
     *
     * <p>La clé {@code null} ou vide est redirigée vers
     * {@link #isEcjInitialized()}.</p>
     *
     * @param module chemin Gradle du module
     * @return {@code true} si {@link #jdt(String)} a déjà construit le
     *         moteur de ce module et qu'il n'a pas été invalidé depuis
     */
    public boolean isEcjInitialized(String module) {
        if (module == null || module.isBlank()) {
            return isEcjInitialized();
        }
        ContexteModule c = contextes.get(module.trim());
        if (c == null) return false;
        synchronized (c) {
            return c.moteur != null;
        }
    }

    /**
     * Pré-chauffe l'index de TOUS les moteurs UTILES (défaut + modules
     * NOURRIS — listes non vides, ADR 0215) en arrière-plan : le premier
     * didOpen d'un fichier de {@code :app} ne paie plus le cold start
     * de SON moteur. Le DÉFAUT est préchauffé SANS CONDITION (comportement
     * historique du preWarmIndex d'initialize : le contexte défaut peut
     * être nourri par {@code replaceAll} — moteur historique des tests
     * et des packs anciens — sans apparaître dans la map des modules) ;
     * les contextes de modules VIDES (jamais syncés) sont ÉCARTÉS — les
     * construire ne préparerait qu'un moteur vide qu'une sync
     * invaliderait aussitôt.
     */
    public void prechaufferTousLesMoteurs() {
        try {
            jdt().preWarmIndex();
        } catch (Throwable t) {
            LOG.log(java.util.logging.Level.FINE,
                    "pré-chauffe du moteur défaut échouée (ignorée)", t);
        }
        for (String module : modulesNourris()) {
            try {
                jdt(module).preWarmIndex();
            } catch (Throwable t) {
                LOG.log(java.util.logging.Level.FINE,
                        "pré-chauffe du moteur " + module + " échouée (ignorée)", t);
            }
        }
    }

    /**
     * Modules dont le contexte est NOURRI (une liste au moins non vide) —
     * ordre stable (tri) pour un pré-chauffe déterministe.
     */
    private synchronized java.util.List<String> modulesNourris() {
        java.util.List<String> out = new ArrayList<>();
        for (java.util.Map.Entry<String, ContexteModule> e : contextes.entrySet()) {
            if (!e.getValue().sourceDirs.isEmpty() || !e.getValue().classpathEntries.isEmpty()) {
                out.add(e.getKey());
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    /** Invalidation du moteur du contexte défaut (sous verrou {@code this}). */
    private synchronized void invalidateEcjEngine() {
        contexteDefaut.invaliderMoteur();
    }

    /**
     * Invalide le moteur de CHAQUE contexte (défaut + modules) —
     * {@code setIndexNoms}/{@code setJdkHome}/{@code dispose} (champs
     * PARTAGÉS qui entrent à la construction de tout moteur). Ordre de
     * verrou : {@code this} → chaque contexte (jamais l'inverse).
     */
    private void invaliderTousLesMoteurs() {
        synchronized (this) {
            contexteDefaut.invaliderMoteur();
        }
        for (ContexteModule c : contextes.values()) {
            synchronized (c) {
                c.invaliderMoteur();
            }
        }
    }

    /**
     * Libère les ressources des moteurs ecj de TOUS les contextes (défaut
     * + modules — lot E) : handles de jars ouverts, FileSystem jrt,
     * ClassLoader du jrt-fs.jar. Appelé par
     * {@link jo.lspjava.lsp.JavaLanguageServer#shutdown()} — sinon le GC
     * d'ART finalise les {@code ZipFile} sans {@code close()}, ce qui
     * déclenche les warnings {@code A resource failed to call ZipFile.close}
     * du CloseGuard.
     *
     * <p>Les CONTEXTES (listes par module) restent en place : un appel
     * ultérieur à {@link #jdt()} / {@link #jdt(String)} reconstruit le
     * moteur du contexte — comme le comportement historique du contexte
     * défaut. Idempotent : appels multiples sans effet après le premier
     * (pour les moteurs déjà libérés).</p>
     */
    public synchronized void dispose() {
        // Ordre de verrou : this → contexte (les méthodes qualifiées ne
        // prennent jamais `this` — aucun interblocage possible).
        contexteDefaut.invaliderMoteur();
        for (ContexteModule c : contextes.values()) {
            synchronized (c) {
                c.invaliderMoteur();
            }
        }
    }
}
