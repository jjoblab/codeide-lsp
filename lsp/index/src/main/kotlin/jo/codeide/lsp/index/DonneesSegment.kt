package jo.codeide.lsp.index

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Une ligne de l'index des paquets (contiguïté des classes du paquet). */
internal data class PaquetSegment(
    val nom: String,
    val premiereClasse: Int,
    val nombreClasses: Int,
)

/** Une ligne de l'index des noms simples (nom → classes porteuses). */
internal data class NomSimpleSegment(
    val nom: String,
    val indices: IntArray,
)

/**
 * Données à sérialiser dans un segment — produites par
 * [AnalyseurClasspath], triées et cohérentes (contrats vérifiés par les
 * tests d'aller-retour).
 *
 * @property entrees noms d'entrées `.class` TRIÉS (ordre lexicographique
 *           des chemins internes) — la contiguïté des paquet en découle.
 * @property paquets paquets triés par nom, chaque paquet couvrant la
 *           plage contiguë de ses classes dans [entrees].
 * @property nomsSimples noms simples triés, chaque nom pointant les
 *           indices (croissants) de ses classes dans [entrees].
 * @property membres membres par classe (aligné sur [entrees], `null`
 *           quand l'entrée n'a pas pu être analysée — index partiel
 *           honnête, la localisation reste juste).
 * @property origines pour un segment AAR : index du jar interne porteur
 *           de chaque entrée ; `null` pour un jar/dossier simple.
 *
 * LongParameterList justifié (règle 8) : miroir exact du contenu d'un
 * segment (une propriété par section du format CIDX).
 */
@Suppress("LongParameterList")
internal class DonneesSegment(
    internal val cle: String,
    internal val conteneur: Conteneur,
    internal val tailleSource: Long,
    internal val mtimeSource: Long,
    internal val entrees: List<String>,
    internal val paquets: List<PaquetSegment>,
    internal val nomsSimples: List<NomSimpleSegment>,
    internal val membres: List<MembresClasse?>,
    internal val origines: IntArray?,
) {
    init {
        require(entrees.size == membres.size) { "membres alignés sur les entrées attendu" }
        if (origines != null) {
            require(origines.size == entrees.size) { "origines alignées sur les entrées attendu" }
        }
    }
}

/**
 * Encodeurs des SECTIONS du format CIDX — séparés de l'écriture atomique
 * ([EcrivainSegment]) : chaque section est petite, nommée et testable.
 */
internal object EcrivainSections {
    /** Section ENTRÉES : noms triés à préfixe partagé. */
    internal fun sectionNoms(noms: List<String>): ByteArray {
        val section = SectionEcriture()
        var precedent = ""
        for (nom in noms) {
            val partage = longueurPrefixePartage(precedent, nom)
            section.sortie.writeByte(partage)
            section.sortie.writeShort(nom.length - partage)
            section.sortie.write(nom.toByteArray(Charsets.UTF_8), partage, nom.length - partage)
            precedent = nom
        }
        return section.octets()
    }

    /** Section PAQUETS : noms à préfixe partagé + plage de classes. */
    internal fun sectionPaquets(paquets: List<PaquetSegment>): ByteArray {
        val section = SectionEcriture()
        var precedent = ""
        for (paquet in paquets) {
            val partage = longueurPrefixePartage(precedent, paquet.nom)
            section.sortie.writeByte(partage)
            section.sortie.writeShort(paquet.nom.length - partage)
            section.sortie.write(paquet.nom.toByteArray(Charsets.UTF_8), partage, paquet.nom.length - partage)
            section.sortie.writeInt(paquet.premiereClasse)
            section.sortie.writeInt(paquet.nombreClasses)
            precedent = paquet.nom
        }
        return section.octets()
    }

    /** Section NOMS SIMPLES : nom à préfixe partagé + indices croissants. */
    internal fun sectionNomsSimples(lignes: List<NomSimpleSegment>): ByteArray {
        val section = SectionEcriture()
        var precedent = ""
        for (ligne in lignes) {
            val partage = longueurPrefixePartage(precedent, ligne.nom)
            section.sortie.writeByte(partage)
            section.sortie.writeShort(ligne.nom.length - partage)
            section.sortie.write(ligne.nom.toByteArray(Charsets.UTF_8), partage, ligne.nom.length - partage)
            section.sortie.writeInt(ligne.indices.size)
            ligne.indices.forEach { section.sortie.writeInt(it) }
            precedent = ligne.nom
        }
        return section.octets()
    }

    /** Section MEMBRES : offsets par classe (sentinelle 0 = absent) + blocs. */
    internal fun sectionMembres(
        donnees: DonneesSegment,
        chaineVersIndex: MutableMap<String, Int>,
    ): ByteArray {
        val blocs = ByteArrayOutputStream()
        val sortieBlocs = DataOutputStream(blocs)
        val offsets = IntArray(donnees.entrees.size)
        donnees.membres.forEachIndexed { index, membres ->
            if (membres == null) {
                offsets[index] = OFFSET_ABSENT
                return@forEachIndexed
            }
            // +1 : 0 est la sentinelle ABSENT — le premier bloc réel
            // commencerait sinon à 0 et serait confondu avec l'absence.
            offsets[index] = blocs.size() + DECALAGE_SENTINELLE
            sortieBlocs.writeShort(membres.drapeauxClasse)
            sortieBlocs.writeShort(membres.champs.size)
            sortieBlocs.writeShort(membres.methodes.size)
            for (membre in membres.champs) {
                ecrireMembre(sortieBlocs, membre, chaineVersIndex)
            }
            for (membre in membres.methodes) {
                ecrireMembre(sortieBlocs, membre, chaineVersIndex)
            }
        }
        val section = SectionEcriture()
        for (offset in offsets) {
            section.sortie.writeInt(offset)
        }
        section.sortie.write(blocs.toByteArray())
        return section.octets()
    }

    /** Section CHAÎNES : tailles puis concaténation des chaînes dédupliquées. */
    internal fun sectionChaines(chaineVersIndex: Map<String, Int>): ByteArray {
        val section = SectionEcriture()
        val chaines = Array(chaineVersIndex.size) { "" }
        chaineVersIndex.forEach { (chaine, index) -> chaines[index] = chaine }
        section.sortie.writeInt(chaines.size)
        val octets = chaines.map { it.toByteArray(Charsets.UTF_8) }
        octets.forEach { section.sortie.writeInt(it.size) }
        octets.forEach { section.sortie.write(it) }
        return section.octets()
    }

    /** Section ORIGINES (AAR) : jars internes + porteur de chaque entrée. */
    internal fun sectionOrigines(donnees: DonneesSegment): ByteArray {
        val jars = (donnees.conteneur as Conteneur.Arr).jarsInternes
        val section = SectionEcriture()
        section.sortie.writeInt(jars.size)
        jars.forEach { section.sortie.writeUTF(it) }
        donnees.origines?.forEach { section.sortie.writeInt(it) }
        return section.octets()
    }

    /** Un membre : drapeaux + index de chaînes (nom, descripteur). */
    private fun ecrireMembre(
        sortie: DataOutputStream,
        membre: Membre,
        chaineVersIndex: MutableMap<String, Int>,
    ) {
        sortie.writeShort(membre.drapeaux)
        sortie.writeInt(indexChaine(membre.nom, chaineVersIndex))
        sortie.writeInt(indexChaine(membre.descripteur, chaineVersIndex))
    }

    /** Interne une chaîne dans la table (déduplication). */
    private fun indexChaine(
        chaine: String,
        chaineVersIndex: MutableMap<String, Int>,
    ): Int {
        val existant = chaineVersIndex[chaine]
        if (existant != null) {
            return existant
        }
        val index = chaineVersIndex.size
        chaineVersIndex[chaine] = index
        return index
    }

    /** Longueur du préfixe commun (bornée u8). */
    private fun longueurPrefixePartage(
        precedent: String,
        courant: String,
    ): Int {
        val borne = minOf(precedent.length, courant.length, FormatSegment.PREFIXE_PARTAGE_MAX)
        var partage = 0
        while (partage < borne && precedent[partage] == courant[partage]) {
            partage++
        }
        return partage
    }

    /** Flux d'écriture d'une section : tampon + DataOutputStream pair. */
    internal class SectionEcriture {
        private val tampon = ByteArrayOutputStream()

        val sortie: DataOutputStream = DataOutputStream(tampon)

        /** Octets écrits (après flush). */
        fun octets(): ByteArray {
            sortie.flush()
            return tampon.toByteArray()
        }
    }

    private const val OFFSET_ABSENT: Int = 0
    private const val DECALAGE_SENTINELLE: Int = 1
}
