package jo.codeide.lsp.index

import java.io.File
import java.io.IOException

/**
 * Entrepôt des segments — stockage PARTAGÉ entre projets (ADR 0111 §2 :
 * répertoire de cache unique, pas dans le projet), segments mutualisés
 * entre modules et entre projets.
 *
 * Emplacement par défaut : `~/.codeide/lsp/index/segments/` — aligné à
 * l'étape 4 sur le répertoire de l'hôte Android (le chemin est
 * injectable, jamais deviné sur appareil).
 */
internal class EntrepotSegments(
    repertoireRacine: File,
) {
    /** Répertoire des segments (créé paresseusement). */
    internal val dossierSegments: File = File(repertoireRacine, ClesSegments.DOSSIER_SEGMENTS)

    /** Fichier d'un segment d'empreinte (nom opaque sha256). */
    internal fun fichierSegment(empreinte: String): File = File(dossierSegments, ClesSegments.nomFichier(empreinte))

    /** Le segment existe sur le disque. */
    internal fun existe(empreinte: String): Boolean = fichierSegment(empreinte).isFile

    /**
     * Écrit un segment de façon ATOMIQUE (`.tmp` + renommage — un crash
     * pendant l'écriture ne laisse jamais un segment à moitié écrit).
     *
     * @throws IOException si le stockage échoue (l'appelant passe en état
     *         ERREUR, jamais de crash de l'index).
     */
    @Throws(IOException::class)
    internal fun ecrire(
        donnees: DonneesSegment,
        empreinte: String,
    ): File {
        dossierSegments.mkdirs()
        val fichier = fichierSegment(empreinte)
        EcrivainSegment().ecrire(donnees, fichier)
        return fichier
    }

    /** Lit un segment complet (validation crc — voir [LecteurSegment.lire]). */
    internal fun lire(
        empreinte: String,
        tailleAttendue: Long?,
        mtimeAttendue: Long?,
    ): LectureSegment = LecteurSegment().lire(fichierSegment(empreinte), empreinte, tailleAttendue, mtimeAttendue)

    /** Valide l'EN-TÊTE seul d'un segment (reprise rapide, sans crc). */
    internal fun enTeteValide(
        empreinte: String,
        tailleAttendue: Long?,
        mtimeAttendue: Long?,
    ): Boolean = LecteurSegment().enTeteValide(fichierSegment(empreinte), empreinte, tailleAttendue, mtimeAttendue)

    /**
     * Rafraîchit l'horodatage d'un segment (LRU du nettoyage — l'âge
     * d'un segment est son dernier ACCÈS, pas sa création).
     */
    internal fun toucher(empreinte: String): Boolean =
        fichierSegment(empreinte).setLastModified(System.currentTimeMillis())

    /** Supprime un segment. */
    internal fun supprimer(empreinte: String): Boolean = fichierSegment(empreinte).delete()

    /** Liste les fichiers de segments du stockage (tous, orphelins compris). */
    internal fun lister(): List<File> {
        val fichiers = dossierSegments.listFiles() ?: return emptyList()
        return fichiers.filter { it.isFile && ClesSegments.estNomSegment(it.name) }
    }

    /** Purge les `.tmp` orphelins d'une écriture interrompue (démarrage). */
    internal fun purgerTemporaires(): Int {
        val fichiers = dossierSegments.listFiles() ?: return 0
        return fichiers.count { it.isFile && it.name.endsWith(SUFFIXE_TEMPORAIRE) && it.delete() }
    }

    /** Taille totale du stockage en octets. */
    internal fun tailleTotale(): Long = lister().sumOf { it.length() }

    private companion object {
        private const val SUFFIXE_TEMPORAIRE: String = ".tmp"
    }
}
