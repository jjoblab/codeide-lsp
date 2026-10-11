package jo.codeide.lsp.index

import jo.codeide.lsp.model.Empreintes

/**
 * Clés des segments — dérivation des noms de fichiers depuis les
 * empreintes W6 de :lsp:model (ADR 0111 §1 : l'identité du contenu EST la
 * clé, déjà dérivable sans re-hachage).
 *
 * Une empreinte `maven:<sha1>`, `transform:<haché>:<fichier>` ou
 * `partiel:…` désigne un fichier de segment
 * `segments/<sha256(empreinte)>.idx` : le nom est opaque (sans
 * séparateur hostile au système de fichiers), l'empreinte exacte vit
 * DANS l'en-tête du segment (recoupée à l'ouverture — un fichier
 * renommé ou réécrit sous un mauvais nom est détecté, pas cru).
 */
public object ClesSegments {
    /** Suffixe des fichiers de segments. */
    public const val SUFFIXE_SEGMENT: String = ".idx"

    /** Sous-répertoire des segments dans le répertoire racine de l'index. */
    public const val DOSSIER_SEGMENTS: String = "segments"

    /** Longueur canonique d'un sha256 hexadécimal (nom de fichier). */
    private const val LONGUEUR_SHA256: Int = 64

    /**
     * Nom de fichier du segment d'une empreinte — `sha256(clé).idx`.
     *
     * @param empreinte empreinte W6 de l'entrée (`maven:…`, `transform:…`,
     *        `partiel:…`, `dossier:<signature>` ou `aar:…`).
     */
    public fun nomFichier(empreinte: String): String = Empreintes.sha256(empreinte) + SUFFIXE_SEGMENT

    /**
     * La clé est-elle une empreinte de fichier indexable (JAR, AAR) —
     * `maven:`, `transform:`, `partiel:` ou `aar:` ?
     *
     * Les empreintes `dossier:` du modèle (mtime seul) ne suffisent PAS
     * pour un segment : la signature d'arborescence d'ADR 0111 §10 les
     * remplace côté index.
     */
    public fun estEmpreinteFichier(empreinte: String): Boolean =
        empreinte.startsWith("maven:") ||
            empreinte.startsWith("transform:") ||
            empreinte.startsWith("partiel:") ||
            empreinte.startsWith("aar:")

    /**
     * Clé d'un segment d'AAR (contenu normalisé des jars internes) :
     * l'empreinte du fichier AAR + la liste des jars internes retenus —
     * changer un jar interne change la clé.
     */
    public fun cleArr(
        empreinteAar: String,
        jarsInternes: List<String>,
    ): String = "aar:${Empreintes.sha256(empreinteAar + "|" + jarsInternes.joinToString("|"))}"

    /**
     * Clé d'un segment de DOSSIER par signature d'arborescence
     * (ADR 0111 §10) : sha256 des (chemin relatif, taille, horodatage)
     * triés des fichiers `.class` — tout changement d'un fichier, d'un
     * ajout ou d'une suppression change la clé et invalide le segment.
     *
     * @param fichiers liste triée de triplets (chemin relatif, taille,
     *        horodatage) — voir [AnalyseurClasspath].
     */
    public fun cleDossier(fichiers: List<Triple<String, Long, Long>>): String {
        val canonique = fichiers.joinToString(";") { (relatif, taille, mtime) -> "$relatif:$taille:$mtime" }
        return "dossier:${Empreintes.sha256(canonique)}"
    }

    /**
     * Nom de fichier valide ? (longueur sha256 + suffixe — le balayage
     * du nettoyage ignore tout autre fichier sans le supprimer).
     */
    public fun estNomSegment(nom: String): Boolean =
        nom.length == LONGUEUR_SHA256 + SUFFIXE_SEGMENT.length && nom.endsWith(SUFFIXE_SEGMENT)
}
