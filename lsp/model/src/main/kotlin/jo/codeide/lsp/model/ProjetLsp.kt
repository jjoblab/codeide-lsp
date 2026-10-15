package jo.codeide.lsp.model

import kotlinx.serialization.Serializable

/**
 * Projet LSP complet — snapshot IMMUABLE (prompt 2 §7.1, étape 1).
 *
 * Construit par [ConvertisseurModeleLsp.depuisClasspathResult] à partir
 * du [jo.codeide.tooling.protocol.ClasspathResult] livré par le serveur de
 * tooling (v8 : modules Android par le modèle v2 d'AGP, modules JVM purs
 * par IdeaProject). Un consommateur garde l'instantané sous lequel il a
 * commencé — le remplacement se fait par publication d'un NOUVEAU
 * [ProjetLsp], jamais par mutation.
 *
 * @property cheminRacine répertoire racine du projet.
 * @property modules modules du projet, triés par nom (ordre canonique —
 *           l'empreinte du projet ne dépend pas de l'ordre de résolution).
 * @property empreinte empreinte du projet : sha256 des empreintes des
 *           modules triées — change si UN module change.
 */
@Serializable
public data class ProjetLsp(
    public val cheminRacine: String,
    public val modules: List<ModuleLsp>,
    public val empreinte: String,
)

/** Type d'un module — source du classpath v8 (ADR 0113). */
@Serializable
public enum class TypeModuleLsp {
    /** Module Android (modèle v2 AGP — android.jar, variantes). */
    ANDROID,

    /** Module JVM pur (IdeaProject). */
    JVM,
}

/**
 * Module LSP — snapshot immuable (§7.1).
 *
 * @property nom chemin Gradle (`:app`) — l'identifiant canonique v8.
 * @property type source du module (Android v2 ou JVM Idea).
 * @property variante variante Android retenue (`debug`), `null` sinon.
 * @property sources racines de sources PAR RÔLE (principal, test, générés).
 * @property classpath classpath COMPILÉ du module (entrées ordonnées,
 *           dédupliquées, empreintées — W6).
 * @property dependancesModules dépendances vers les modules frères (entrées
 *           MODULE), cycles rejetés avec avertissement.
 * @property androidJar plateforme de compilation (`android.jar` seul, ADR
 *           0112 — compile seulement), `null` pour un module JVM.
 * @property ignore module écarté avec sa raison (conteneur sans sources
 *           ni classpath), jamais silencieusement.
 * @property raisonIgnore raison de l'écart, `null` sinon.
 * @property avertissements avertissements du serveur + de la conversion
 *           (doublons résolus, dépendances non résolues…).
 * @property compatibiliteSource niveau Java SOURCE du module (v10, lot
 *           S1-a — `sourceCompatibility` Gradle, ex. « 1.8 »/« 17 »/« 21 »),
 *           `null` = indécidable (le moteur garde son défaut « 17 »).
 * @property compatibiliteCible niveau Java de BYTECODE du module
 *           (`targetCompatibility`), `null` = indécidable.
 * @property empreinte empreinte du module — classpath, sources, générés,
 *           androidJar, variante ET niveaux Java ; changer UNE dépendance
 *           change l'empreinte du SEUL module concerné.
 */
@Serializable
public data class ModuleLsp(
    public val nom: String,
    public val type: TypeModuleLsp,
    public val variante: String?,
    public val sources: EnsemblesSources,
    public val classpath: ClasspathInstantane,
    public val dependancesModules: List<DependanceModule>,
    public val androidJar: String?,
    public val ignore: Boolean,
    public val raisonIgnore: String?,
    public val avertissements: List<String>,
    public val compatibiliteSource: String? = null,
    public val compatibiliteCible: String? = null,
    public val empreinte: String,
)

/**
 * Racines de sources PAR RÔLE (§7.1 — W5 : main et test ne se mélangent
 * plus ; W10 : les générés ont leur rôle, fournis par le tooling).
 *
 * @property principal racines du sourceset main (java ET kotlin).
 * @property test racines du sourceset de test unitaire.
 * @property generes racines générées (R, BuildConfig, KSP/kapt,
 *           ViewBinding) — vides sans build préalable (comportement
 *           dégradé explicite, pas de faux positifs).
 */
@Serializable
public data class EnsemblesSources(
    public val principal: List<String> = emptyList(),
    public val test: List<String> = emptyList(),
    public val generes: List<String> = emptyList(),
)

/**
 * Dépendance vers un module frère (entrée MODULE du classpath).
 *
 * @property cible chemin Gradle du module cible (`:core:ui`).
 * @property portee portée (`compile` ou `test`).
 * @property exporte drapeau `api` (dépendance exportée) — NON déterminable
 *           depuis les modèles v8 (ni le graphe v2 AGP ni IdeaProject
 *           n'exposent de drapeau par arête) : toujours `false` à l'étape 1,
 *           documenté ; l'isolation par module (le classpath d'un module
 *           ne porte que SES dépendances résolues par Gradle) fait déjà le
 *           travail de W5 — l'implémentation d'un sibling n'y figure pas.
 */
@Serializable
public data class DependanceModule(
    public val cible: String,
    public val portee: String,
    public val exporte: Boolean = false,
)

/** Nature d'une entrée du classpath instantané. */
@Serializable
public enum class KindEntree {
    /** Jar de bytecode (y compris jar extrait d'un AAR normalisé, v8). */
    JAR,

    /** AAR brut NON normalisé (périphérique — le LSP ne sait pas le lire). */
    AAR,

    /** Dossier de classes (sortie d'un module). */
    DOSSIER,

    /** Dépendance vers un module frère (résolue par [DependanceModule]). */
    MODULE,
}

/**
 * Classpath COMPILÉ d'un module — entrées ordonnées, dédupliquées,
 * empreintées par contenu (W6).
 *
 * @property entrees entrées en ordre canonique (triées par kind puis chemin
 *           — même classpath ⇒ même empreinte, quel que soit l'ordre de
 *           résolution).
 * @property empreinte sha256 des identités (kind, chemin, empreinte) des
 *           entrées — la clé de cache et d'invalidation future (ADR 0111).
 */
@Serializable
public data class ClasspathInstantane(
    public val entrees: List<EntreeClasspathInstantane>,
    public val empreinte: String,
)

/**
 * Une entrée du classpath instantané, empreintée.
 *
 * @property kind nature de l'entrée.
 * @property chemin chemin absolu (jar extrait pour un AAR normalisé).
 * @property portee `compile` ou `test`, `null` pour les sorties du module.
 * @property sources jar de sources attaché, `null` si aucun.
 * @property aar AAR d'ORIGINE quand l'entrée provient d'un AAR normalisé
 *           (v8 — provenance et future clé d'index), `null` sinon.
 * @property empreinte identité du CONTENU (sha1 du chemin de cache Gradle,
 *           haché de transform, ou taille+mtime+empreinte partielle —
 *           voir [Empreintes]) ; pour MODULE, l'identité nominale.
 * @property existe le fichier existait au moment du snapshot (un jar du
 *           cache supprimé entre-temps est signalé, pas masqué — W7).
 * @property taille taille en octets au snapshot (`0` si absent/dossier).
 * @property mtime horodatage de dernière modification au snapshot (`0`
 *           pour une entrée MODULE) — recoupé à la relecture du modèle
 *           persisté (W7 : taille/horodatage).
 */
@Serializable
public data class EntreeClasspathInstantane(
    public val kind: KindEntree,
    public val chemin: String,
    public val portee: String?,
    public val sources: String?,
    public val aar: String?,
    public val empreinte: String,
    public val existe: Boolean,
    public val taille: Long,
    public val mtime: Long,
)
