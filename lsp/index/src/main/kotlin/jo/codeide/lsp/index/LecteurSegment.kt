package jo.codeide.lsp.index

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32

/**
 * Segment DÉCODÉ en mémoire — structures interrogables par la
 * [VueIndex], chargées via le cache LRU borné (ADR 0111 §6 : budget tas
 * explicite).
 *
 * Les membres restent BRUTS (octets + offsets) et sont analysés À LA
 * REQUÊTE : décoder tous les membres d'un gros jar (android.jar, ~15 ko
 * classes) coûterait des centaines de milliers d'objets — la lecture
 * paresseuse maintient le segment dans le budget.
 *
 * @property cle empreinte d'origine (recoupée à l'ouverture).
 * @property conteneur conteneur d'origine.
 * @property tailleSource taille du fichier source à la construction
 *           (validation de reprise W7).
 * @property mtimeSource horodatage du fichier source à la construction.
 * @property entrees noms d'entrées triés (`java/util/Map.class`).
 * @property paquets paquets triés (nom, plage de classes contiguë).
 * @property nomsSimples noms simples triés (nom → indices de classes).
 * @property membresBrut charge utile brute de la section MEMBRES
 *           (SANS le tableau d'offsets — déplacé dans [offsetsMembres]).
 * @property offsetsMembres offset de chaque classe dans [membresBrut]
 *           (0 = membres absents).
 * @property chainesBrut charge utile brute de la section CHAÎNES.
 * @property offsetsChaines offset cumulé de chaque chaîne dans
 *           [chainesBrut] (taille nb+1 — la chaîne i vit entre
 *           offsetsChaines[i] et offsetsChaines[i+1]).
 * @property origines index du jar interne porteur par entrée (AAR seul).
 *
 * LongParameterList justifié (règle 8) : miroir EXACT des sections du
 * format binaire CIDX — chaque champ est une section décodée.
 */
@Suppress("LongParameterList")
internal class SegmentDecode(
    internal val cle: String,
    internal val conteneur: Conteneur,
    internal val tailleSource: Long,
    internal val mtimeSource: Long,
    internal val entrees: List<String>,
    internal val paquets: List<PaquetSegment>,
    internal val nomsSimples: List<NomSimpleSegment>,
    internal val membresBrut: ByteArray,
    internal val offsetsMembres: IntArray,
    internal val chainesBrut: ByteArray,
    internal val offsetsChaines: IntArray,
    internal val origines: IntArray?,
) {
    /**
     * Membres d'une classe (index dans [entrees]) — analyse paresseuse du
     * bloc brut, `null` si absents ou illisibles (jamais de crash).
     */
    @Suppress("ReturnCount", "SwallowedException")
    internal fun membres(indexClasse: Int): MembresClasse? {
        val stocke = offsetsMembres.getOrNull(indexClasse) ?: return null
        if (stocke == OFFSET_ABSENT) return null
        // -1 : l'écrivain décale de 1 pour réserver 0 = ABSENT.
        val debut = stocke - DECALAGE_SENTINELLE
        return try {
            val entree = DataInputStream(ByteArrayInputStream(membresBrut, debut, membresBrut.size - debut))
            val drapeauxClasse = entree.readUnsignedShort()
            val nombreChamps = entree.readUnsignedShort()
            val nombreMethodes = entree.readUnsignedShort()
            val champs = lireMembres(entree, nombreChamps)
            val methodes = lireMembres(entree, nombreMethodes)
            MembresClasse(drapeauxClasse, champs, methodes)
        } catch (e: IOException) {
            // SwallowedException justifié (règle 8) : un bloc corrompu
            // invalide CE membre, pas l'index — le crc du segment détecte
            // la corruption à l'ouverture et reconstruit ce segment.
            null
        }
    }

    /** Nom du jar interne porteur d'une entrée (AAR), `null` sinon. */
    @Suppress("ReturnCount")
    internal fun jarInterne(indexClasse: Int): String? {
        val jars = (conteneur as? Conteneur.Arr)?.jarsInternes ?: return null
        val index = origines?.getOrNull(indexClasse) ?: return null
        return jars.getOrNull(index)
    }

    private fun lireMembres(
        entree: DataInputStream,
        nombre: Int,
    ): List<Membre> {
        val membres = ArrayList<Membre>(nombre)
        repeat(nombre) {
            val drapeaux = entree.readUnsignedShort()
            val nom = chaine(entree.readInt())
            val descripteur = chaine(entree.readInt())
            membres.add(Membre(drapeaux, nom, descripteur))
        }
        return membres
    }

    /** Chaîne i de la table brute (offsets précalculés). */
    private fun chaine(index: Int): String {
        val debut = offsetsChaines.getOrNull(index) ?: return ""
        val fin = offsetsChaines.getOrNull(index + 1) ?: debut
        return String(chainesBrut, debut, fin - debut, Charsets.UTF_8)
    }

    /** Estimation de l'empreinte tas (budget du cache LRU). */
    internal val octetsMemoire: Long =
        membresBrut.size.toLong() +
            chainesBrut.size.toLong() +
            entrees.sumOf { it.length.toLong() * FACTEUR_CHAINE } +
            nomsSimples.sumOf { it.nom.length.toLong() * FACTEUR_CHAINE + it.indices.size * TAILLE_ENTIER } +
            paquets.sumOf { it.nom.length.toLong() * FACTEUR_CHAINE } +
            entrees.size * FormatSegment.COUT_FIXE_PAR_ENTREE

    private companion object {
        private const val OFFSET_ABSENT: Int = 0
        private const val DECALAGE_SENTINELLE: Int = 1
        private const val FACTEUR_CHAINE: Int = 2
        private const val TAILLE_ENTIER: Int = 4
    }
}

/** Résultat de lecture d'un segment : décodé, ou refus propre (jamais crash). */
internal sealed interface LectureSegment {
    /** Segment décodé et validé (crc compris). */
    data class Ok(
        val segment: SegmentDecode,
    ) : LectureSegment

    /**
     * Refus PROPRE : absent, corrompu (crc/magie), tronqué ou d'une
     * version passée/future — ADR 0111 §4 : migration = ré-indexation
     * paresseuse, corruption = reconstruction de CE segment.
     */
    data class Refuse(
        val raison: String,
    ) : LectureSegment
}

/** Une section de la charge utile (tranche d'octets). */
private data class Tranche(
    val debut: Int,
    val longueur: Int,
)

/**
 * Lecteur de segments — ouvre un fichier, valide l'en-tête (magie,
 * version, clé attendue, statistiques source) et le crc32 de la charge,
 * puis décode les sections.
 */
internal class LecteurSegment {
    /**
     * Lit [fichier] et recoupe la clé et les statistiques source.
     *
     * @param cleAttendue empreinte attendue (le nom de fichier est opaque
     *        — un fichier écrit sous un mauvais nom est REFUSÉ).
     * @param tailleAttendue taille du fichier source au snapshot (`null`
     *        pour ne pas vérifier).
     * @param mtimeAttendue horodatage du fichier source au snapshot.
     *
     * LongMethod + ReturnCount justifiés (règle 8) : la VALIDATION se lit
     * comme le format (chaque écart a son retour anticipé explicite) — la
     * découper masquerait l'ordre des contrôles.
     */
    @Suppress("ReturnCount", "LongMethod")
    internal fun lire(
        fichier: File,
        cleAttendue: String,
        tailleAttendue: Long?,
        mtimeAttendue: Long?,
    ): LectureSegment {
        val octets =
            try {
                fichier.readBytes()
            } catch (e: IOException) {
                return LectureSegment.Refuse("illisible : ${e.message}")
            }
        if (octets.size < TAILLE_MINIMALE) {
            return LectureSegment.Refuse("tronqué (${octets.size} octets)")
        }
        val entree = DataInputStream(ByteArrayInputStream(octets))
        return try {
            val magie = entree.readInt()
            if (magie != FormatSegment.MAGIE) {
                return LectureSegment.Refuse("magie inconnue")
            }
            val version = entree.readUnsignedShort()
            if (version != FormatSegment.VERSION) {
                // Version passée OU future : ré-indexation paresseuse.
                return LectureSegment.Refuse("version $version ≠ ${FormatSegment.VERSION}")
            }
            entree.readUnsignedShort() // drapeaux — non interprétés (réservé)
            val crcAttendu = entree.readInt()
            val cle = entree.readUTF()
            if (cle != cleAttendue) {
                return LectureSegment.Refuse("clé divergente (fichier renommé ?)")
            }
            val conteneur = lireConteneur(entree)
            val tailleSource = entree.readLong()
            val mtimeSource = entree.readLong()
            if (tailleAttendue != null && tailleAttendue != tailleSource) {
                return LectureSegment.Refuse("source retailée ($tailleSource → $tailleAttendue)")
            }
            if (mtimeAttendue != null && mtimeAttendue != mtimeSource) {
                return LectureSegment.Refuse("source retouchée")
            }
            val nbEntrees = entree.readInt()
            val nbPaquets = entree.readInt()
            val nbNomsSimples = entree.readInt()
            val nbSections = entree.readUnsignedByte()
            // La position courante APRÈS la table des sections marque le
            // début de la charge utile — `available` la déduit exactement.
            val sections =
                Array(nbSections) {
                    val id = entree.readUnsignedByte()
                    val offset = entree.readInt()
                    Tranche(offset, entree.readInt()).let { id to it }
                }
            val debutCharge = octets.size - entree.available()
            if (crc32(octets, debutCharge) != crcAttendu) {
                return LectureSegment.Refuse("crc invalide (segment corrompu)")
            }
            val tranches = sections.toMap()
            val segment =
                decoderSections(
                    octets = octets,
                    debutCharge = debutCharge,
                    tranches = tranches,
                    conteneur = conteneur,
                    cle = cle,
                    tailleSource = tailleSource,
                    mtimeSource = mtimeSource,
                    nbEntrees = nbEntrees,
                    nbPaquets = nbPaquets,
                    nbNomsSimples = nbNomsSimples,
                )
            LectureSegment.Ok(segment)
        } catch (e: IOException) {
            // SwallowedException justifié (règle 8) : un segment tronqué
            // ou brouillé est REFUSÉ proprement — reconstruction, pas crash.
            return LectureSegment.Refuse("décodage impossible : ${e.message}")
        }
    }

    /**
     * Lecture RAPIDE d'en-tête seul (sans crc, sans décodage des
     * sections) — reprise « à chaud » : valider 250 segments ne doit pas
     * lire 125 Mo de données (cible ADR 0111 : reprise < 1,5 s). La
     * corruption est attrapée plus tard, à la première lecture complète
     * (auto-guérison par reconstruction du segment).
     *
     * @return `true` si l'en-tête est valide (magie, version, clé,
     *         statistiques source), `false` sinon — jamais d'exception.
     */
    @Suppress("ReturnCount", "SwallowedException")
    internal fun enTeteValide(
        fichier: File,
        cleAttendue: String,
        tailleAttendue: Long?,
        mtimeAttendue: Long?,
    ): Boolean {
        val tailleFichier = fichier.length()
        if (!fichier.isFile || tailleFichier < TAILLE_MINIMALE) {
            return false
        }
        return try {
            fichier.inputStream().use { flux ->
                val taille = TAILLE_EN_TETE_LIMITEE.coerceAtMost(tailleFichier)
                val tampon = ByteArray(taille.toInt())
                flux.readNBytes(tampon, 0, tampon.size)
                val entree = DataInputStream(ByteArrayInputStream(tampon))
                if (entree.readInt() != FormatSegment.MAGIE) {
                    return false
                }
                if (entree.readUnsignedShort() != FormatSegment.VERSION) {
                    // Version passée ou future : ré-indexation paresseuse.
                    return false
                }
                entree.readUnsignedShort() // drapeaux
                entree.readInt() // crc — NON vérifié ici (lecture rapide)
                if (entree.readUTF() != cleAttendue) {
                    return false
                }
                lireConteneur(entree) // conteneur — parse strict (longueurs)
                val tailleSource = entree.readLong()
                val mtimeSource = entree.readLong()
                if (tailleAttendue != null && tailleAttendue != tailleSource) {
                    return false
                }
                if (mtimeAttendue != null && mtimeAttendue != mtimeSource) {
                    return false
                }
                true
            }
        } catch (e: IOException) {
            // SwallowedException justifié (règle 8) : un en-tête tronqué
            // invalide CE segment — reconstruction paresseuse, pas crash.
            false
        }
    }

    /** Décode le conteneur (type + chemin + jars internes). */
    private fun lireConteneur(entree: DataInputStream): Conteneur {
        val type = entree.readUnsignedByte()
        val chemin = entree.readUTF()
        return when (type) {
            FormatSegment.TYPE_ARR -> {
                val nbJars = entree.readInt()
                val jars = ArrayList<String>(nbJars)
                repeat(nbJars) { jars.add(entree.readUTF()) }
                Conteneur.Arr(chemin, jars)
            }

            FormatSegment.TYPE_DOSSIER -> {
                Conteneur.Dossier(chemin)
            }

            else -> {
                Conteneur.Jar(chemin)
            }
        }
    }

    /** Décode les sections d'un segment validé. */
    @Suppress("LongParameterList", "LongMethod", "ReturnCount")
    private fun decoderSections(
        octets: ByteArray,
        debutCharge: Int,
        tranches: Map<Int, Tranche>,
        conteneur: Conteneur,
        cle: String,
        tailleSource: Long,
        mtimeSource: Long,
        nbEntrees: Int,
        nbPaquets: Int,
        nbNomsSimples: Int,
    ): SegmentDecode {
        fun tranche(id: Int): Tranche? {
            val relative = tranches[id] ?: return null
            return Tranche(debutCharge + relative.debut, relative.longueur)
        }

        val entrees =
            tranche(FormatSegment.SECTION_ENTREES)
                ?.let { lireNoms(octets, it) }
                ?: emptyList()

        val paquets =
            tranche(FormatSegment.SECTION_PAQUETS)
                ?.let { lirePaquets(octets, it, nbPaquets) }
                ?: emptyList()

        val nomsSimples =
            tranche(FormatSegment.SECTION_NOMS_SIMPLES)
                ?.let { lireNomsSimples(octets, it, nbNomsSimples) }
                ?: emptyList()

        val membresBrut: ByteArray
        val offsetsMembres: IntArray
        val sectionMembres = tranche(FormatSegment.SECTION_MEMBRES)
        if (sectionMembres == null) {
            membresBrut = ByteArray(0)
            offsetsMembres = IntArray(nbEntrees)
        } else {
            val entreeMembres = flux(octets, sectionMembres)
            offsetsMembres = IntArray(nbEntrees) { entreeMembres.readInt() }
            val tailleOffsets = nbEntrees * TAILLE_ENTIER
            membresBrut =
                octets.copyOfRange(
                    sectionMembres.debut + tailleOffsets,
                    sectionMembres.debut + sectionMembres.longueur,
                )
        }

        val chainesBrut: ByteArray
        val offsetsChaines: IntArray
        val sectionChaines = tranche(FormatSegment.SECTION_CHAINES)
        if (sectionChaines == null) {
            chainesBrut = ByteArray(0)
            offsetsChaines = IntArray(1)
        } else {
            val entreeChaines = flux(octets, sectionChaines)
            val nbChaines = entreeChaines.readInt()
            val tailles = IntArray(nbChaines) { entreeChaines.readInt() }
            offsetsChaines = IntArray(nbChaines + 1)
            for (i in 0 until nbChaines) {
                offsetsChaines[i + 1] = offsetsChaines[i] + tailles[i]
            }
            val debutOctets = nbChaines * TAILLE_ENTIER + TAILLE_ENTIER
            chainesBrut =
                octets.copyOfRange(
                    sectionChaines.debut + debutOctets,
                    sectionChaines.debut + sectionChaines.longueur,
                )
        }

        val origines: IntArray?
        val sectionOrigines = tranche(FormatSegment.SECTION_ORIGINES)
        if (sectionOrigines == null || conteneur !is Conteneur.Arr) {
            origines = null
        } else {
            val entreeOrigines = flux(octets, sectionOrigines)
            // Saut des noms de jars internes (déjà dans le conteneur) AVANT
            // de lire les origines par entrée — l'ordre d'écriture.
            val nbJars = entreeOrigines.readInt()
            repeat(nbJars) { entreeOrigines.readUTF() }
            origines = IntArray(nbEntrees) { entreeOrigines.readInt() }
        }

        return SegmentDecode(
            cle = cle,
            conteneur = conteneur,
            tailleSource = tailleSource,
            mtimeSource = mtimeSource,
            entrees = entrees,
            paquets = paquets,
            nomsSimples = nomsSimples,
            membresBrut = membresBrut,
            offsetsMembres = offsetsMembres,
            chainesBrut = chainesBrut,
            offsetsChaines = offsetsChaines,
            origines = origines,
        )
    }

    /** Flux borné à une tranche d'octets. */
    private fun flux(
        octets: ByteArray,
        tranche: Tranche,
    ): DataInputStream = DataInputStream(ByteArrayInputStream(octets, tranche.debut, tranche.longueur))

    /** Noms à préfixe partagé (section ENTRÉES). */
    private fun lireNoms(
        octets: ByteArray,
        tranche: Tranche,
    ): List<String> {
        val noms = ArrayList<String>(tranche.longueur / COUT_NOM_MOYEN)
        var precedent = ""
        var position = tranche.debut
        val fin = tranche.debut + tranche.longueur
        while (position < fin) {
            val partage = octets[position].toInt() and OctetsIndex.MASQUE_OCTET
            position++
            val longueurSuffixe = OctetsIndex.u16(octets, position)
            position += TAILLE_ENTIER / 2
            val nom = precedent.substring(0, partage) + String(octets, position, longueurSuffixe, Charsets.UTF_8)
            position += longueurSuffixe
            noms.add(nom)
            precedent = nom
        }
        return noms
    }

    /** Paquets (section PAQUETS). */
    private fun lirePaquets(
        octets: ByteArray,
        tranche: Tranche,
        nombre: Int,
    ): List<PaquetSegment> {
        val paquets = ArrayList<PaquetSegment>(nombre)
        var precedent = ""
        var position = tranche.debut
        repeat(nombre) {
            val partage = octets[position].toInt() and OctetsIndex.MASQUE_OCTET
            position++
            val longueurSuffixe = OctetsIndex.u16(octets, position)
            position += TAILLE_ENTIER / 2
            val nom = precedent.substring(0, partage) + String(octets, position, longueurSuffixe, Charsets.UTF_8)
            position += longueurSuffixe
            val premiere = OctetsIndex.u32(octets, position)
            position += TAILLE_ENTIER
            val nombreClasses = OctetsIndex.u32(octets, position)
            position += TAILLE_ENTIER
            paquets.add(PaquetSegment(nom, premiere, nombreClasses))
            precedent = nom
        }
        return paquets
    }

    /** Noms simples (section NOMS SIMPLES). */
    private fun lireNomsSimples(
        octets: ByteArray,
        tranche: Tranche,
        nombre: Int,
    ): List<NomSimpleSegment> {
        val lignes = ArrayList<NomSimpleSegment>(nombre)
        var precedent = ""
        var position = tranche.debut
        repeat(nombre) {
            val partage = octets[position].toInt() and OctetsIndex.MASQUE_OCTET
            position++
            val longueurSuffixe = OctetsIndex.u16(octets, position)
            position += TAILLE_ENTIER / 2
            val nom = precedent.substring(0, partage) + String(octets, position, longueurSuffixe, Charsets.UTF_8)
            position += longueurSuffixe
            val nbIndices = OctetsIndex.u32(octets, position)
            position += TAILLE_ENTIER
            val indices = IntArray(nbIndices)
            for (i in 0 until nbIndices) {
                indices[i] = OctetsIndex.u32(octets, position)
                position += TAILLE_ENTIER
            }
            lignes.add(NomSimpleSegment(nom, indices))
            precedent = nom
        }
        return lignes
    }

    /** crc32 des octets à partir d'un offset. */
    private fun crc32(
        octets: ByteArray,
        debut: Int,
    ): Int {
        val crc = CRC32()
        crc.update(octets, debut, octets.size - debut)
        return crc.value.toInt()
    }

    private companion object {
        private const val TAILLE_MINIMALE: Int = 32
        private const val TAILLE_ENTIER: Int = 4
        private const val COUT_NOM_MOYEN: Int = 24

        /** En-tête lu au plus (reprise rapide — clé + conteneur bornés). */
        private const val TAILLE_EN_TETE_LIMITEE: Long = 4096L
    }
}
