package jo.codeide.lsp.index

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Lecteur ZIP minimaliste SANS `java.util.zip.ZipFile` (ADR 0111 §6 —
 * équivalent Kotlin du `RawZipReader` vendored, motivation CloseGuard
 * documentée par l'ADR de l'ancienne version : tant qu'on instancie
 * `ZipFile`, on alimente les warnings « A resource failed to call
 * ZipFile.close »).
 *
 * Implémente le sous-ensemble PKWARE APPNOTE requis : EOCD localisé par
 * scan arrière des 64 Kio finaux, répertoire central, saut d'en-tête
 * local puis lecture brute (STORED) ou gonflage (DEFLATED). ZIP64 non
 * supporté (aucun jar Android ne dépasse les limites — constat vendored).
 *
 * Deux sources : fichier (`RandomAccessFile`, une seule poignée fermée
 * proprement) ou octets en mémoire (jars internes d'un AAR — ADR 0111
 * §10 : normalisation à l'indexation).
 */
internal class LecteurZipBrut {
    /** Ouvre un jar du système de fichiers — penser à fermer la poignée. */
    @Throws(IOException::class)
    internal fun ouvrir(fichier: File): Poignee {
        val raf = RandomAccessFile(fichier, "r")
        return try {
            Poignee(SourceFichier(raf, raf.length()))
        } catch (e: IOException) {
            raf.close()
            throw e
        }
    }

    /** Ouvre un zip déjà en mémoire (jar interne d'un AAR). */
    internal fun depuisOctets(octets: ByteArray): Poignee = Poignee(SourceOctets(octets))

    /** Source d'octets à accès aléatoire (fichier ou mémoire). */
    internal interface Source {
        /** Taille totale de la source en octets. */
        val taille: Long

        fun lire(
            offset: Long,
            taille: Int,
        ): ByteArray

        fun fermer()
    }

    /** Source fichier — UNE poignée RandomAccessFile par zip. */
    private class SourceFichier(
        private val raf: RandomAccessFile,
        override val taille: Long,
    ) : Source {
        override fun lire(
            offset: Long,
            taille: Int,
        ): ByteArray {
            val octets = ByteArray(taille)
            synchronized(raf) {
                raf.seek(offset)
                raf.readFully(octets)
            }
            return octets
        }

        override fun fermer() {
            synchronized(raf) {
                raf.close()
            }
        }
    }

    /** Source mémoire (jamais fermée — rien à fermer). */
    private class SourceOctets(
        private val octets: ByteArray,
    ) : Source {
        override val taille: Long get() = octets.size.toLong()

        override fun lire(
            offset: Long,
            taille: Int,
        ): ByteArray = octets.copyOfRange(offset.toInt(), offset.toInt() + taille)

        // EmptyFunctionBlock justifié (règle 8) : une source mémoire n'a
        // RIEN à fermer — le contrat est porté par l'interface Source.
        @Suppress("EmptyFunctionBlock")
        override fun fermer() {}
    }

    /** Métadonnées d'une entrée du répertoire central. */
    private data class InfoEntree(
        val decalageLocal: Long,
        val tailleCompressee: Long,
        val methode: Int,
    )

    /**
     * Poignée sur un zip ouvert — répertoire central en mémoire, lecture
     * d'une entrée à la demande (synchronisée : lectures concurrentes
     * correctes, comme le vendored).
     */
    internal inner class Poignee internal constructor(
        private val source: Source,
    ) : AutoCloseable {
        private val entrees: MutableMap<String, InfoEntree> = linkedMapOf()

        init {
            lireRepertoireCentral()
        }

        /** Noms d'entrées du zip (ordre du répertoire central). */
        internal val noms: List<String> get() = entrees.keys.toList()

        /** L'entrée existe. */
        internal fun contient(nom: String): Boolean = nom in entrees

        /**
         * Octets DÉCOMPRESSIONNÉS d'une entrée, `null` si absente.
         *
         * @throws IOException si la lecture ou le gonflage échoue (entrée
         *         corrompue — l'appelant écarte l'entrée, pas le jar).
         */
        @Throws(IOException::class)
        internal fun lire(nom: String): ByteArray? {
            val info = entrees[nom] ?: return null
            val brut = source.lire(info.decalageLocal, TAILLE_EN_TETE_LOCAL.toInt())
            val longueurNom = OctetsIndex.u16Petit(brut, DECALAGE_NOM_LOCAL)
            val longueurExtra = OctetsIndex.u16Petit(brut, DECALAGE_EXTRA_LOCAL)
            val debutDonnees = info.decalageLocal + TAILLE_EN_TETE_LOCAL + longueurNom + longueurExtra
            val compressees = source.lire(debutDonnees, info.tailleCompressee.toInt())
            return when (info.methode) {
                METHODE_STOCKEE -> compressees

                METHODE_DEFLATEE -> gonfler(compressees)

                // Méthode inconnue (LZMA…) : entrée illisible — écartée
                // proprement par l'appelant, le reste du jar reste indexable.
                else -> throw IOException("méthode de compression ${info.methode} non supportée ($nom)")
            }
        }

        /**
         * Parse EOCD puis le répertoire central.
         *
         * ThrowsCount justifié (règle 8) : chaque échec a SON contrat
         * d'exception (EOCD, bornes, entrée invalide) — un échec générique
         * masquerait la cause.
         */
        @Suppress("ThrowsCount")
        @Throws(IOException::class)
        private fun lireRepertoireCentral() {
            val fin = source.taille
            val debutScan = (fin - TAILLE_SCAN_EOCD).coerceAtLeast(0)
            val fenetre = source.lire(debutScan, (fin - debutScan).toInt())
            val positionEocd =
                chercherEocd(fenetre)
                    ?: throw IOException("EOCD introuvable (zip tronqué ou non-zip)")
            val nbEntrees = OctetsIndex.u16Petit(fenetre, positionEocd + EOCD_NB_ENTREES)
            var decalageCd = OctetsIndex.u32Petit(fenetre, positionEocd + EOCD_DECALAGE_CD).toLong()
            if (decalageCd + TAILLE_ENTREE_CD > fin) {
                throw IOException("répertoire central hors bornes")
            }
            for (i in 0 until nbEntrees) {
                val entete = source.lire(decalageCd, TAILLE_ENTREE_CD.toInt())
                val signature = OctetsIndex.u32Petit(entete, 0)
                if (signature != SIGNATURE_CD) {
                    throw IOException("entrée de répertoire central invalide (n° $i)")
                }
                val methode = OctetsIndex.u16Petit(entete, CD_METHODE)
                val tailleCompressee = OctetsIndex.u32Petit(entete, CD_TAILLE_COMPRESSEE).toLong()
                val longueurNom = OctetsIndex.u16Petit(entete, CD_LONGUEUR_NOM)
                val longueurExtra = OctetsIndex.u16Petit(entete, CD_LONGUEUR_EXTRA)
                val longueurCommentaire = OctetsIndex.u16Petit(entete, CD_LONGUEUR_COMMENTAIRE)
                val decalageLocal = OctetsIndex.u32Petit(entete, CD_DECALAGE_LOCAL).toLong()
                val nom = String(source.lire(decalageCd + TAILLE_ENTREE_CD, longueurNom), Charsets.UTF_8)
                entrees[nom] = InfoEntree(decalageLocal, tailleCompressee, methode)
                decalageCd += TAILLE_ENTREE_CD + longueurNom + longueurExtra + longueurCommentaire
            }
        }

        /** EOCD cherché depuis la FIN de la fenêtre (scan arrière). */
        private fun chercherEocd(fenetre: ByteArray): Int? {
            var position = fenetre.size - TAILLE_EOCD
            while (position >= 0) {
                if (OctetsIndex.u32Petit(fenetre, position) == SIGNATURE_EOCD) {
                    return position
                }
                position--
            }
            return null
        }

        /** Gonflage d'une entrée DEFLATED (tampon croissant). */
        @Throws(IOException::class)
        private fun gonfler(compressees: ByteArray): ByteArray {
            val gonfleur = Inflater(true)
            try {
                gonfleur.setInput(compressees)
                val tampon = ByteArray(TAILLE_BLOC_GONFLAGE)
                val sortie = ByteArrayOutputStream(tailleEstimee(compressees.size))
                while (!gonfleur.finished()) {
                    val lus = gonfleur.inflate(tampon)
                    if (lus == 0 && gonfleur.needsInput()) {
                        throw IOException("entrée DEFLATED tronquée")
                    }
                    sortie.write(tampon, 0, lus)
                }
                return sortie.toByteArray()
            } catch (e: DataFormatException) {
                throw IOException("entrée DEFLATED corrompue", e)
            } finally {
                gonfleur.end()
            }
        }

        override fun close() {
            source.fermer()
        }
    }

    private companion object {
        private const val SIGNATURE_EOCD: Int = 0x06054b50
        private const val SIGNATURE_CD: Int = 0x02014b50
        private const val TAILLE_EOCD: Int = 22
        private const val EOCD_NB_ENTREES: Int = 10
        private const val EOCD_DECALAGE_CD: Int = 16
        private const val TAILLE_ENTREE_CD: Int = 46
        private const val CD_METHODE: Int = 10
        private const val CD_TAILLE_COMPRESSEE: Int = 20
        private const val CD_LONGUEUR_NOM: Int = 28
        private const val CD_LONGUEUR_EXTRA: Int = 30
        private const val CD_LONGUEUR_COMMENTAIRE: Int = 32
        private const val CD_DECALAGE_LOCAL: Int = 42
        private const val TAILLE_EN_TETE_LOCAL: Int = 30
        private const val DECALAGE_NOM_LOCAL: Int = 26
        private const val DECALAGE_EXTRA_LOCAL: Int = 28
        private const val METHODE_STOCKEE: Int = 0
        private const val METHODE_DEFLATEE: Int = 8
        private const val TAILLE_SCAN_EOCD: Long = 65557L
        private const val TAILLE_BLOC_GONFLAGE: Int = 65536

        /** Estimation initiale du tampon de gonflage (heuristique 4×). */
        private const val FACTEUR_ESTIMATION: Int = 4

        private fun tailleEstimee(tailleCompressee: Int): Int = tailleCompressee * FACTEUR_ESTIMATION
    }
}
