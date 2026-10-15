package jo.codeide.lsp.model

import jo.codeide.tooling.protocol.ClasspathEntry
import jo.codeide.tooling.protocol.ClasspathKind
import jo.codeide.tooling.protocol.ClasspathModule
import jo.codeide.tooling.protocol.ClasspathResult

/**
 * Conversion PURE du [ClasspathResult] du protocole vers un [ProjetLsp]
 * immuable (prompt 2 §7.1, étape 1) — fonction testable sans Android, sans
 * démon, avec un [LecteurStatistiquesFichier] injecté (le convertisseur ne
 * touche pas au disque lui-même).
 *
 * Règles appliquées :
 *
 * - **W5** : sourcesets main/test/générés séparés par rôle ; le classpath
 *   d'un module ne porte que SES propres entrées (isolation par module) ;
 * - **W6** : empreinte par contenu de chaque entrée (provenance du cache
 *   Gradle — voir [Empreintes]), ordre CANONIQUE (tri par kind puis chemin)
 *   : le même classpath produit toujours la même empreinte ;
 * - **doublons de bibliothèque** : « plus récent gagne » (comparaison de
 *   versions numérique par segments) sur les jar partageant la même
 *   identité de bibliothèque (groupe:artefact du chemin de cache Maven,
 *   artefact du chemin de transform) — l'écart est signalé en
 *   avertissement, jamais silencieux ;
 * - **cycles** : une dépendance de module qui refermer un cycle dans le
 *   graphe du PROJET est REJETÉE avec avertissement (arêtes traitées en
 *   ordre canonique — le graphe reste acyclique et déterministe) ;
 * - **module ignoré** : écarté avec sa raison conservée (jamais
 *   silencieux) ;
 * - **critère du prompt** : changer une dépendance change l'empreinte du
 *   SEUL module concerné (l'empreinte d'une entrée MODULE est nominale :
 *   le contenu du frère vit dans SON propre snapshot).
 */
@Suppress("TooManyFunctions")
public object ConvertisseurModeleLsp {
    /** Segments d'un chemin Maven après `files-2.1` : groupe, artefact,
     *  version, sha1, fichier (groupe multi-niveaux en tête). */
    private const val SEGMENTS_MAVEN_MIN: Int = 5

    /** Position de l'artefact comptée depuis la FIN du chemin Maven. */
    private const val POSITION_ARTEFACT_FIN: Int = 4

    /** Position de la version comptée depuis la FIN du chemin Maven. */
    private const val POSITION_VERSION_FIN: Int = 3

    /**
     * Convertit un [ClasspathResult] (réponse v8 du serveur de tooling)
     * en [ProjetLsp].
     */
    public fun depuisClasspathResult(
        resultat: ClasspathResult,
        stats: LecteurStatistiquesFichier,
    ): ProjetLsp {
        // Rejet des cycles AU NIVEAU DU PROJET (union des arêtes de tous
        // les modules) : :a → :b et :b → :a ne vivent pas dans le même
        // module, la détection par module serait aveugle.
        val aretes = aretesCandidates(resultat.modules)
        val acceptees = mutableListOf<Pair<String, String>>()
        val rejetees = mutableListOf<Pair<String, String>>()
        for ((source, cible) in aretes) {
            if (cheminExiste(cible, source, acceptees)) {
                rejetees += source to cible
            } else {
                acceptees += source to cible
            }
        }

        val modules =
            resultat.modules
                .map { it.versModuleLsp(stats, rejetees) }
                .sortedBy { it.nom }
        return ProjetLsp(
            cheminRacine = resultat.projectDir,
            modules = modules,
            empreinte = empreinteProjet(modules),
        )
    }

    // -----------------------------------------------------------------
    // Modules
    // -----------------------------------------------------------------

    /** Conversion d'un module du protocole (v8) vers son snapshot. */
    private fun ClasspathModule.versModuleLsp(
        stats: LecteurStatistiquesFichier,
        aretesRejetees: List<Pair<String, String>>,
    ): ModuleLsp {
        val avertissements = (avertissements ?: emptyList()).toMutableList()
        val estAndroid = androidJar != null || varianteAndroid != null

        // Entrées : empreinte, existence, avertissement AAR brut.
        var entrees = entrer(entries, stats, avertissements)

        // Ordre canonique — la déduplication conserve la première
        // occurrence, le tri (kind, chemin) rend l'empreinte indépendante
        // de l'ordre de résolution de Gradle.
        entrees =
            entrees
                .distinctBy { it.kind to it.chemin }
                .sortedWith(compareBy({ it.kind.name }, { it.chemin }))
        entrees = resoudreDoublons(entrees, avertissements)

        // Dépendances de modules, arêtes cycliques rejetées à l'échelle du
        // projet (avertissement porté par le module SOURCE).
        val dependances =
            entrees
                .filter { it.kind == KindEntree.MODULE }
                .map { DependanceModule(cible = it.chemin, portee = it.portee ?: "compile") }
                .distinctBy { it.cible to it.portee }
                .filter { dependance ->
                    val rejet = name to dependance.cible in aretesRejetees
                    if (rejet) {
                        avertissements +=
                            "dépendance cyclique rejetée : $name → ${dependance.cible} " +
                            "(un chemin ${dependance.cible} → $name existe déjà)"
                    }
                    !rejet
                }

        val empreinteModule =
            Empreintes.sha256(
                buildString {
                    append("classpath:").append(empreinteClasspath(entrees))
                    sourcesCanoniques().forEach { racine -> append("|racine:").append(racine) }
                    androidJar?.let { append("|androidjar:").append(it) }
                    varianteAndroid?.let { append("|variante:").append(it) }
                    // v10 (S1-a) : le niveau Java entre dans l'identité du
                    // module — le changer (scripts Gradle) invalide SON
                    // moteur (diagnostics de niveau à jour).
                    compatibiliteSource().let { append("|source:").append(it) }
                    compatibiliteCible().let { append("|cible:").append(it) }
                },
            )

        return ModuleLsp(
            nom = name,
            type = if (estAndroid) TypeModuleLsp.ANDROID else TypeModuleLsp.JVM,
            variante = varianteAndroid,
            sources =
                EnsemblesSources(
                    principal = sourceDirs,
                    test = sourceDirsTest ?: emptyList(),
                    generes = fichiersGeneres ?: emptyList(),
                ),
            classpath = ClasspathInstantane(entrees = entrees, empreinte = empreinteClasspath(entrees)),
            dependancesModules = dependances,
            androidJar = androidJar,
            ignore = ignore,
            raisonIgnore = raisonIgnore,
            avertissements = avertissements,
            compatibiliteSource = compatibiliteSource(),
            compatibiliteCible = compatibiliteCible(),
            empreinte = empreinteModule,
        )
    }

    /**
     * Niveau Java source CANONIQUE du module (S1-a) : notation simple
     * (« 8 », « 11 », « 17 », « 21 » — la notation « 1.x » historique est
     * normalisée), blanc/indécidable = `null` (le moteur garde son défaut
     * « 17 »). La normalisation au bord du modèle rend l'empreinte
     * indépendante de la notation du tooling (Gradle livrant « 1.8 » ou
     * « 8 » selon l'AGP, le MÊME projet garde la MÊME empreinte).
     */
    private fun ClasspathModule.compatibiliteSource(): String? = normaliserNiveau(sourceCompatibility)

    /** Niveau Java cible canonique (voir [compatibiliteSource]). */
    private fun ClasspathModule.compatibiliteCible(): String? = normaliserNiveau(targetCompatibility)

    /** Normalisation « 1.x » → « x » (bornée) ; hors numérique simple → null. */
    internal fun normaliserNiveau(niveau: String?): String? {
        if (niveau.isNullOrBlank()) return null
        val s = niveau.trim().let { if (it.startsWith("1.")) it.substring(2) else it }
        return if (s.isNotEmpty() && s.all { it.isDigit() }) s else null
    }

    /** Racines de sources du module, tous rôles, ordre canonique. */
    private fun ClasspathModule.sourcesCanoniques(): List<String> =
        (sourceDirs + (sourceDirsTest ?: emptyList()) + (fichiersGeneres ?: emptyList())).distinct().sorted()

    // -----------------------------------------------------------------
    // Entrées
    // -----------------------------------------------------------------

    /** Convertit, empreinte et sonde les entrées du protocole. */
    private fun entrer(
        entrees: List<ClasspathEntry>,
        stats: LecteurStatistiquesFichier,
        avertissements: MutableList<String>,
    ): List<EntreeClasspathInstantane> =
        entrees.map { entree ->
            val kind =
                when (entree.kind) {
                    ClasspathKind.JAR -> KindEntree.JAR
                    ClasspathKind.AAR -> KindEntree.AAR
                    ClasspathKind.DOSSIER -> KindEntree.DOSSIER
                    ClasspathKind.MODULE -> KindEntree.MODULE
                }
            if (kind == KindEntree.AAR) {
                avertissements +=
                    "AAR non normalisé livré brut : ${entree.path} — l'index (étape 2) ne saura pas le lire"
            }
            val empreinte =
                when (kind) {
                    KindEntree.MODULE -> Empreintes.empreinteModule(entree.path)
                    KindEntree.DOSSIER -> Empreintes.empreinteDossier(entree.path, stats)
                    KindEntree.JAR, KindEntree.AAR -> Empreintes.empreinteFichier(entree.path, stats)
                }
            EntreeClasspathInstantane(
                kind = kind,
                chemin = entree.path,
                portee = entree.scope,
                sources = entree.sources,
                aar = entree.aar,
                empreinte = empreinte,
                existe = kind == KindEntree.MODULE || stats.existe(entree.path),
                taille = if (kind == KindEntree.MODULE) 0L else stats.taille(entree.path),
                mtime = if (kind == KindEntree.MODULE) 0L else stats.mtime(entree.path),
            )
        }

    /**
     * « Plus récent gagne » : parmi les entrées JAR partageant la même
     * identité de bibliothèque, seule la version la plus récente est
     * conservée ; les écartées sont signalées. L'ordre d'entrée est
     * préservé (canonique).
     */
    internal fun resoudreDoublons(
        entrees: List<EntreeClasspathInstantane>,
        avertissements: MutableList<String>,
    ): List<EntreeClasspathInstantane> {
        val jars = entrees.filter { it.kind == KindEntree.JAR }
        val parBibliotheque = jars.groupBy { identiteBibliotheque(it.chemin) }
        val ecartees = mutableListOf<EntreeClasspathInstantane>()
        for ((cle, concourantes) in parBibliotheque) {
            if (cle == null || concourantes.size < 2) {
                continue
            }
            val triees =
                concourantes.sortedWith(
                    Comparator { a, b ->
                        comparerVersions(versionDuChemin(b.chemin), versionDuChemin(a.chemin))
                    },
                )
            val gagnante = triees.first()
            triees.drop(1).forEach { perdante ->
                ecartees += perdante
                avertissements +=
                    "doublon de bibliothèque « $cle » : ${perdante.chemin} écartée " +
                    "au profit de ${gagnante.chemin} (plus récent gagne)"
            }
        }
        if (ecartees.isEmpty()) {
            return entrees
        }
        return entrees.filter { it !in ecartees }
    }

    /**
     * Identité de bibliothèque d'un chemin de cache Gradle, `null` si non
     * déterminable :
     * - Maven : `modules-2/files-2.1/<groupe>/<artefact>/<version>/<sha1>/…`
     *   → `groupe:artefact` ;
     * - transform : `transforms[-N]/<haché>/transformed/<artefact>-<version>/…`
     *   → `artefact` (segment sans son suffixe de version).
     */
    @Suppress("ReturnCount")
    internal fun identiteBibliotheque(chemin: String): String? {
        val segments = chemin.split('/')
        val indexMaven = segments.indexOfLast { it == "files-2.1" }
        if (indexMaven >= 0 && segments.size >= indexMaven + SEGMENTS_MAVEN_MIN) {
            val groupe = segments.subList(indexMaven + 1, segments.size - POSITION_ARTEFACT_FIN)
            if (groupe.isEmpty()) {
                return null
            }
            return "${groupe.joinToString(".")}:${segments[segments.size - POSITION_ARTEFACT_FIN]}"
        }
        val indexTransform = segments.indexOf("transformed")
        if (indexTransform >= 0 && indexTransform + 1 < segments.size) {
            return nomSansVersion(segments[indexTransform + 1])
        }
        return null
    }

    /** Version d'un chemin (Maven ou transform), segments numériques. */
    internal fun versionDuChemin(chemin: String): List<Int> {
        val segments = chemin.split('/')
        val version =
            when {
                "files-2.1" in segments -> {
                    segments.getOrNull(segments.size - POSITION_VERSION_FIN) ?: ""
                }

                "transformed" in segments -> {
                    val apres = segments.getOrNull(segments.indexOf("transformed") + 1) ?: ""
                    suffixeVersion(apres)
                }

                else -> {
                    ""
                }
            }
        return version.split('.').map { segment -> segment.toIntOrNull() ?: 0 }
    }

    /** Compare deux versions SEGMENT PAR SEGMENT, numériquement (1.10 > 1.9). */
    internal fun comparerVersions(
        a: List<Int>,
        b: List<Int>,
    ): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val va = a.getOrNull(i) ?: 0
            val vb = b.getOrNull(i) ?: 0
            if (va != vb) {
                return va.compareTo(vb)
            }
        }
        return 0
    }

    /**
     * Suffixe de version d'un segment `artefact-version` : la partie après
     * le DERNIER tiret si elle commence par un chiffre, chaîne vide sinon.
     */
    private fun suffixeVersion(segment: String): String {
        val apres = segment.substringAfterLast('-')
        return if (apres.isNotEmpty() && apres[0].isDigit()) {
            apres
        } else {
            ""
        }
    }

    /** Nom d'un segment `artefact-version` sans son suffixe de version. */
    private fun nomSansVersion(segment: String): String =
        if (suffixeVersion(segment).isEmpty()) {
            segment
        } else {
            segment.substringBeforeLast('-')
        }

    // -----------------------------------------------------------------
    // Dépendances de modules et cycles
    // -----------------------------------------------------------------

    /**
     * Arêtes candidates (module → cible) de TOUT le projet, ordre
     * canonique — les auto-dépendances referment immédiatement un cycle
     * et sont rejetées par la même détection (pas de cas particulier).
     */
    private fun aretesCandidates(modules: List<ClasspathModule>): List<Pair<String, String>> =
        modules
            .flatMap { module ->
                module.entries
                    .filter { it.kind == ClasspathKind.MODULE }
                    .map { module.name to it.path }
            }.distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))

    /** Un chemin de dépendances existe-il de [depuis] vers [vers] ? */
    private fun cheminExiste(
        depuis: String,
        vers: String,
        aretes: List<Pair<String, String>>,
    ): Boolean {
        val sortantes = aretes.groupBy({ it.first }, { it.second })
        val aVisiter = ArrayDeque<String>()
        aVisiter.addLast(depuis)
        val vus = mutableSetOf(depuis)
        while (aVisiter.isNotEmpty()) {
            val courant = aVisiter.removeFirst()
            if (courant == vers) {
                return true
            }
            sortantes[courant]?.forEach { cible ->
                if (cible !in vus) {
                    vus.add(cible)
                    aVisiter.addLast(cible)
                }
            }
        }
        return false
    }

    // -----------------------------------------------------------------
    // Empreintes
    // -----------------------------------------------------------------

    /** Empreinte du classpath : identités des entrées en ordre canonique. */
    private fun empreinteClasspath(entrees: List<EntreeClasspathInstantane>): String =
        Empreintes.sha256(
            entrees.joinToString("|") { "${it.kind.name}:${it.chemin}:${it.empreinte}" },
        )

    /** Empreinte du projet : empreintes des modules triées. */
    private fun empreinteProjet(modules: List<ModuleLsp>): String =
        Empreintes.sha256(modules.joinToString("|") { "${it.nom}:${it.empreinte}" })
}
