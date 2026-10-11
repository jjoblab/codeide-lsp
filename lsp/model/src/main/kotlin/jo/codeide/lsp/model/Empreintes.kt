package jo.codeide.lsp.model

import java.io.File
import java.security.MessageDigest

/**
 * Empreintes par contenu des entrées de classpath (W6, ADR 0111 — étape 1).
 *
 * Stratégie par PROVENANCE du chemin (pas de re-hachage intégral — 250 jars
 * sur ART hors de question) :
 *
 * 1. **Cache Maven du GRADLE USER HOME** —
 *    `modules-2/files-2.1/<groupe>/<artefact>/<version>/<sha1>/<fichier>` :
 *    le sha1 du chemin EST l'identité du contenu (dépôt par contenu) —
 *    empreinte `maven:<sha1>`. Stable : même contenu, même chemin.
 *
 * 2. **Cache de transforms** — `transforms[-N]/<haché>/transformed/…` :
 *    le haché de transform couvre l'entrée ET les paramètres du
 *    transform (dérivé du contenu pour l'explosion d'AAR) — empreinte
 *    `transform:<haché>:<nom du fichier>`.
 *
 * 3. **Repli** (jar local `files(...)`, sortie de module) :
 *    `partiel:<sha256 début+fin>:<taille>:<mtime>` — empreinte PARTIELLE
 *    (64 Kio en tout) + taille + horodatage : détecte un changement de
 *    contenu sans lire tout le fichier.
 *
 * 4. **Dossier** (DOSSIER) : `dossier:<mtime>` — la signature
 *    d'arborescence complète est livrée par l'index disque (étape 2,
 *    ADR 0111) ; l'horodatage du dossier suffit à l'étape 1 pour
 *    invalider un modèle persisté.
 *
 * 5. **MODULE** : identité nominale `module:<cible>` — l'empreinte réelle
 *    du module frère vit dans SON snapshot (sinon changer B changerait
 *    l'empreinte de A — le critère du prompt l'interdit).
 */
public object Empreintes {
    /** Marqueur du cache Maven de Gradle (modules-2, dépôt par contenu). */
    private const val MARQUEUR_MODULES = "modules-2/files-2.1/"

    /** Marqueur du cache de transforms de Gradle. */
    private const val MARQUEUR_TRANSFORMS = "transforms"

    /** Longueur canonique d'un sha1 hexadécimal (cache Maven Gradle). */
    private const val LONGUEUR_SHA1: Int = 40

    /** Segments minimum après le marqueur Maven : groupe, artefact,
     *  version, sha1, fichier. */
    private const val SEGMENTS_MAVEN_MIN: Int = 5

    /** Position du sha1 comptée depuis la FIN du chemin Maven. */
    private const val POSITION_SHA1_FIN: Int = 2

    /** Taille lue au début et à la fin pour l'empreinte partielle. */
    private const val OCTETS_PARTIELS = 32 * 1024

    /**
     * Empreinte d'une entrée FICHIER (jar, AAR) selon la provenance de
     * [chemin], lue via [stats]. Retours anticipés : chaque provenance
     * (Maven, transform, repli) se déduit de la précédente.
     */
    @Suppress("ReturnCount")
    public fun empreinteFichier(
        chemin: String,
        stats: LecteurStatistiquesFichier,
    ): String {
        val normalise = chemin.replace(File.separatorChar, '/')
        if (MARQUEUR_MODULES in normalise) {
            sha1DuCheminMaven(normalise)?.let { return "maven:$it" }
        }
        hacheTransform(normalise)?.let { return "transform:$it" }
        return empreintePartielle(normalise, stats)
    }

    /** Empreinte d'un dossier (DOSSIER) — mtime (l'arborescence : étape 2). */
    public fun empreinteDossier(
        chemin: String,
        stats: LecteurStatistiquesFichier,
    ): String = "dossier:${stats.mtime(chemin)}"

    /** Identité nominale d'une dépendance de module. */
    public fun empreinteModule(cible: String): String = "module:$cible"

    /** sha256 hex d'une chaîne (identités composées, empreintes de classpath). */
    public fun sha256(chaine: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(chaine.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** sha1 extrait d'un chemin de cache Maven, `null` si introuvable. */
    private fun sha1DuCheminMaven(chemin: String): String? {
        val apresMarqueur = chemin.substringAfter(MARQUEUR_MODULES, "")
        val segments = apresMarqueur.split('/')
        // <groupe>/<artefact>/<version>/<sha1>/<fichier> — le sha1 est
        // l'avant-dernier segment (les groupes multi-niveaux précèdent).
        if (segments.size < SEGMENTS_MAVEN_MIN) return null
        val candidat = segments[segments.size - POSITION_SHA1_FIN]
        return candidat.takeIf { it.length == LONGUEUR_SHA1 && it.all { c -> c.isDigit() || c in 'a'..'f' } }
    }

    /**
     * Haché de transform d'un chemin `transforms[-N]/<haché>/transformed/…`
     * avec le nom du fichier livré, `null` si hors cache de transforms.
     */
    @Suppress("ReturnCount")
    private fun hacheTransform(chemin: String): String? {
        val segments = chemin.split('/')
        val index =
            segments.indexOfLast {
                it == MARQUEUR_TRANSFORMS ||
                    (it.startsWith(MARQUEUR_TRANSFORMS) && it != "transforms")
            }
        if (index < 0 || index + 1 >= segments.size) return null
        val hache = segments[index + 1]
        if (hache.isEmpty() || !hache.all { c -> c.isDigit() || c in 'a'..'f' }) return null
        return "$hache:${segments.last()}"
    }

    /**
     * Repli : empreinte partielle (début+fin du fichier) + taille + mtime.
     * Le contenu illisible ne fait pas échouer la conversion : l'entrée est
     * empreintée par (taille, mtime) et signalée `existe=false` par
     * l'appelant.
     */
    @Suppress("ReturnCount")
    private fun empreintePartielle(
        chemin: String,
        stats: LecteurStatistiquesFichier,
    ): String {
        val morceau = stats.empreintePartielle(chemin) ?: "illisible"
        return "partiel:$morceau:${stats.taille(chemin)}:${stats.mtime(chemin)}"
    }
}

/**
 * Lecture des statistiques de fichiers — couture injectable (le convertisseur
 * reste pur et testable avec des faux ; l'implémentation système lit le
 * vrai système de fichiers).
 */
public interface LecteurStatistiquesFichier {
    /** Le fichier/dossier existe. */
    public fun existe(chemin: String): Boolean

    /** Taille en octets (`0` si absent ou dossier). */
    public fun taille(chemin: String): Long

    /** Horodatage de dernière modification en millisecondes. */
    public fun mtime(chemin: String): Long

    /**
     * Empreinte partielle du CONTENU (début + fin du fichier), hexadécimale,
     * `null` si illisible — voir [Empreintes.empreinteFichier].
     */
    public fun empreintePartielle(chemin: String): String?
}

/** Implémentation système de [LecteurStatistiquesFichier]. */
public class LecteurStatistiquesSysteme : LecteurStatistiquesFichier {
    override fun existe(chemin: String): Boolean = File(chemin).exists()

    override fun taille(chemin: String): Long = File(chemin).takeIf { it.isFile }?.length() ?: 0L

    override fun mtime(chemin: String): Long = File(chemin).takeIf { it.exists() }?.lastModified() ?: 0L

    // SwallowedException justifié (règle 8) : un jar illisible (verrou,
    // disparition) n'échoue pas la conversion — repli (taille, mtime).
    @Suppress("SwallowedException")
    override fun empreintePartielle(chemin: String): String? {
        val fichier = File(chemin)
        if (!fichier.isFile) return null
        return try {
            val octets = fichier.readBytes()
            val debut = octets.copyOfRange(0, minOf(OCTETS_PARTIELS, octets.size))
            val fin =
                if (octets.size > OCTETS_PARTIELS * 2) {
                    octets.copyOfRange(octets.size - OCTETS_PARTIELS, octets.size)
                } else {
                    ByteArray(0)
                }
            val empreinte = MessageDigest.getInstance("SHA-256").digest(debut + fin)
            empreinte.joinToString("") { "%02x".format(it) }
        } catch (e: java.io.IOException) {
            null
        }
    }

    private companion object {
        private const val OCTETS_PARTIELS: Int = 32 * 1024
    }
}
