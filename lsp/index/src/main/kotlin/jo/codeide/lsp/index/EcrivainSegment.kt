package jo.codeide.lsp.index

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.CRC32

/**
 * Format binaire des segments — ADR 0111 §4 : en-tête magique + version
 * de format, sections identifiables, crc32 de charge utile. Toutes les
 * valeurs sont en gros-boutien (`DataOutputStream`).
 *
 * ```
 * magic "CIDX" (u32) | version (u16) | drapeaux (u16, 0) | crc32 (u32)
 * clé (writeUTF) | type conteneur (u8) | conteneur (writeUTF)
 * | [AAR : u32 nb jars internes, chaque (writeUTF)]
 * taille source (u64) | mtime source (u64)
 * nb entrées (u32) | nb paquets (u32) | nb noms simples (u32)
 * u8 nb sections ; par section : u8 id, u32 offset, u32 longueur
 * <charge utile : sections, offsets relatifs au début de la charge>
 * ```
 *
 * Sections (identifiants stables, catalogue ouvert) :
 * 1 ENTRÉES (noms d'entrées triés, préfixe partagé) ;
 * 2 PAQUETS (nom, première classe, nombre — contiguïté garantie par le
 *   tri des entrées) ;
 * 3 NOMS SIMPLES (nom, indices de classes) ;
 * 4 MEMBRES (offsets par classe + blocs : drapeaux u16, membres
 *   (drapeaux u16 + index de chaîne nom u32 + index descripteur u32)) ;
 * 5 CHAÎNES (table unique des noms/descripteurs de membres) ;
 * 6 ORIGINES (AAR seul : jar interne porteur de chaque entrée).
 */
internal object FormatSegment {
    /** Magie du format — « CIDX » en gros-boutien. */
    internal const val MAGIE: Int = 0x43494458

    /** Version courante du format (migration = ré-indexation paresseuse). */
    internal const val VERSION: Int = 1

    /** Drapeaux de format — réservé (0). */
    internal const val DRAPEAUX: Int = 0

    /** Identifiant de section : entrées de classes. */
    internal const val SECTION_ENTREES: Int = 1

    /** Identifiant de section : paquets. */
    internal const val SECTION_PAQUETS: Int = 2

    /** Identifiant de section : noms simples. */
    internal const val SECTION_NOMS_SIMPLES: Int = 3

    /** Identifiant de section : membres. */
    internal const val SECTION_MEMBRES: Int = 4

    /** Identifiant de section : chaînes des membres. */
    internal const val SECTION_CHAINES: Int = 5

    /** Identifiant de section : origines (AAR). */
    internal const val SECTION_ORIGINES: Int = 6

    /** Type de conteneur : jar. */
    internal const val TYPE_JAR: Int = 0

    /** Type de conteneur : AAR (jars internes). */
    internal const val TYPE_ARR: Int = 1

    /** Type de conteneur : dossier de classes. */
    internal const val TYPE_DOSSIER: Int = 2

    /** Taille maximale d'un préfixe partagé (u8). */
    internal const val PREFIXE_PARTAGE_MAX: Int = 255

    /** Coût mémoire fixe estimé par entrée (String + index) — budget cache. */
    internal const val COUT_FIXE_PAR_ENTREE: Int = 48
}

/**
 * Écrivain de segments — sérialisation de [DonneesSegment] puis
 * écriture ATOMIQUE (`.tmp` unique + renommage, ADR 0111 : un crash
 * pendant l'écriture ne laisse jamais un segment à moitié écrit).
 */
internal class EcrivainSegment {
    /**
     * Écrit [donnees] dans [fichier] de façon atomique.
     *
     * @throws IOException si l'écriture ou le renommage échoue (l'appelant
     *         consigne une entrée ignorée, jamais un crash de l'index).
     */
    @Throws(IOException::class)
    internal fun ecrire(
        donnees: DonneesSegment,
        fichier: File,
    ) {
        val octets = serialiser(donnees)
        // Le nom temporaire est UNIQUE PAR ÉCRITURE (pid + thread + nano) :
        // deux constructions concurrentes du MÊME contenu (vues distinctes)
        // écriraient sinon le même .tmp — collision de renommage, l'une
        // échouerait en ERREUR (constaté sur CI).
        val temporaire =
            File(
                fichier.parentFile,
                fichier.name + "-" + ProcessHandle.current().pid() + "-" + Thread.currentThread().threadId() + "-" +
                    System.nanoTime() +
                    ".tmp",
            )
        FileOutputStream(temporaire).use { sortie ->
            sortie.write(octets)
            sortie.fd.sync()
        }
        if (!temporaire.renameTo(fichier)) {
            // FS sans renommage atomique sur même volume : copie+purge,
            // dernier recours (Android le supporte — branche défensive).
            temporaire.copyTo(fichier, overwrite = true)
            temporaire.delete()
        }
    }

    /** Sérialise les données en octets (séparée pour les tests). */
    internal fun serialiser(donnees: DonneesSegment): ByteArray {
        val sections = mutableMapOf<Int, ByteArray>()
        val chaineVersIndex = mutableMapOf<String, Int>()
        sections[FormatSegment.SECTION_MEMBRES] = EcrivainSections.sectionMembres(donnees, chaineVersIndex)
        sections[FormatSegment.SECTION_CHAINES] = EcrivainSections.sectionChaines(chaineVersIndex)
        sections[FormatSegment.SECTION_ENTREES] = EcrivainSections.sectionNoms(donnees.entrees)
        sections[FormatSegment.SECTION_PAQUETS] = EcrivainSections.sectionPaquets(donnees.paquets)
        sections[FormatSegment.SECTION_NOMS_SIMPLES] = EcrivainSections.sectionNomsSimples(donnees.nomsSimples)
        if (donnees.origines != null) {
            sections[FormatSegment.SECTION_ORIGINES] = EcrivainSections.sectionOrigines(donnees)
        }

        val charge = ByteArrayOutputStream()
        val table = mutableListOf<Triple<Int, Int, Int>>()
        for ((id, octets) in sections.toSortedMap()) {
            table.add(Triple(id, charge.size(), octets.size))
            charge.write(octets)
        }
        val payload = charge.toByteArray()

        val racine = ByteArrayOutputStream(payload.size + TAILLE_EN_TETE_ESTIMEE)
        val sortie = DataOutputStream(racine)
        sortie.writeInt(FormatSegment.MAGIE)
        sortie.writeShort(FormatSegment.VERSION)
        sortie.writeShort(FormatSegment.DRAPEAUX)
        sortie.writeInt(crc32(payload))
        sortie.writeUTF(donnees.cle)
        sortie.writeByte(typeConteneur(donnees.conteneur))
        sortie.writeUTF(donnees.conteneur.chemin)
        if (donnees.conteneur is Conteneur.Arr) {
            sortie.writeInt(donnees.conteneur.jarsInternes.size)
            donnees.conteneur.jarsInternes.forEach { sortie.writeUTF(it) }
        }
        sortie.writeLong(donnees.tailleSource)
        sortie.writeLong(donnees.mtimeSource)
        sortie.writeInt(donnees.entrees.size)
        sortie.writeInt(donnees.paquets.size)
        sortie.writeInt(donnees.nomsSimples.size)
        sortie.writeByte(table.size)
        for ((id, offset, longueur) in table) {
            sortie.writeByte(id)
            sortie.writeInt(offset)
            sortie.writeInt(longueur)
        }
        sortie.write(payload)
        sortie.flush()
        return racine.toByteArray()
    }

    /** Type de conteneur encodé. */
    private fun typeConteneur(conteneur: Conteneur): Int =
        when (conteneur) {
            is Conteneur.Jar -> FormatSegment.TYPE_JAR
            is Conteneur.Arr -> FormatSegment.TYPE_ARR
            is Conteneur.Dossier -> FormatSegment.TYPE_DOSSIER
        }

    /** crc32 d'une charge utile (aucun `java.util.zip.ZipFile`). */
    private fun crc32(octets: ByteArray): Int {
        val crc = CRC32()
        crc.update(octets)
        return crc.value.toInt()
    }

    private companion object {
        private const val TAILLE_EN_TETE_ESTIMEE: Int = 4096
    }
}
