package jo.codeide.lsp.index

import jo.codeide.lsp.model.ClasspathInstantane
import jo.codeide.lsp.model.EntreeClasspathInstantane
import jo.codeide.lsp.model.KindEntree
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Fixtures des tests de l'index — construction de jars RÉELS (ZipOutputStream,
 * autorisé en test : l'interdiction vise les lectures CloseGuard d'ART),
 * d'octets de classe forgés (analyseur sous contrôle total) et d'entrées
 * de classpath du modèle.
 */
internal object FixturesIndex {
    /** Construit un jar en mémoire : (nom d'entrée → octets). */
    fun octetsZip(
        entrees: Map<String, ByteArray>,
        compresse: Boolean = true,
    ): ByteArray {
        val tampon = ByteArrayOutputStream()
        ZipOutputStream(tampon).use { sortie ->
            if (!compresse) {
                sortie.setMethod(ZipOutputStream.STORED)
            }
            entrees.forEach { (nom, octets) ->
                if (!compresse) {
                    val entree = ZipEntry(nom)
                    entree.size = octets.size.toLong()
                    entree.compressedSize = octets.size.toLong()
                    val crc = java.util.zip.CRC32()
                    crc.update(octets)
                    entree.crc = crc.value
                    sortie.putNextEntry(entree)
                } else {
                    sortie.putNextEntry(ZipEntry(nom))
                }
                sortie.write(octets)
                sortie.closeEntry()
            }
        }
        return tampon.toByteArray()
    }

    /** Écrit un jar sur le disque (répertoire temporaire du test). */
    fun ecrireZip(
        fichier: File,
        entrees: Map<String, ByteArray>,
        compresse: Boolean = true,
    ): File {
        Files.createDirectories(fichier.parentFile.toPath())
        FileOutputStream(fichier).use { it.write(octetsZip(entrees, compresse)) }
        return fichier
    }

    /**
     * Octets d'un fichier de classe minimal — un constructeur public et un
     * champ statique (le parseur ne lit ni this_class ni attributs : la
     * table est donc réduite aux Utf8 référencés).
     *
     * @param drapeauxClasse drapeaux de la classe.
     * @param champ (drapeaux, nom, descripteur) du champ unique.
     * @param methode (drapeaux, nom, descripteur) de la méthode unique.
     */
    fun octetsClasse(
        drapeauxClasse: Int = 0x0021,
        champ: Triple<Int, String, String> = Triple(0x0009, "valeur", "I"),
        methode: Triple<Int, String, String> = Triple(0x0001, "<init>", "()V"),
    ): ByteArray {
        val chaine = mutableListOf<String>()

        fun indexDe(texte: String): Int {
            chaine.add(texte)
            return chaine.size
        }
        val idxChampNom = indexDe(champ.second)
        val idxChampDesc = indexDe(champ.third)
        val idxMethodeNom = indexDe(methode.second)
        val idxMethodeDesc = indexDe(methode.third)

        val tampon = ByteArrayOutputStream()
        val sortie = java.io.DataOutputStream(tampon)
        sortie.writeInt(0xCAFEBABE.toInt())
        sortie.writeShort(0) // mineure
        sortie.writeShort(52) // majeure
        sortie.writeShort(chaine.size + 1) // nombre de constantes (+ slot 0)
        chaine.forEach {
            sortie.writeByte(1) // Utf8
            sortie.writeShort(it.length)
            sortie.write(it.toByteArray(Charsets.UTF_8))
        }
        sortie.writeShort(drapeauxBornes(drapeauxClasse))
        sortie.writeShort(0) // this_class (non lu)
        sortie.writeShort(0) // super_class (non lu)
        sortie.writeShort(0) // interfaces
        sortie.writeShort(1) // un champ
        ecrireMembre(sortie, champ.first, idxChampNom, idxChampDesc)
        sortie.writeShort(1) // une méthode
        ecrireMembre(sortie, methode.first, idxMethodeNom, idxMethodeDesc)
        sortie.flush()
        return tampon.toByteArray()
    }

    /** Drapeaux de classe bornés u16 (défensif, valeurs de test). */
    private fun drapeauxBornes(drapeaux: Int): Int = drapeaux and 0xFFFF

    /** Un membre : drapeaux + index nom + index descripteur, zéro attribut. */
    private fun ecrireMembre(
        sortie: java.io.DataOutputStream,
        drapeaux: Int,
        idxNom: Int,
        idxDesc: Int,
    ) {
        sortie.writeShort(drapeaux and 0xFFFF)
        sortie.writeShort(idxNom)
        sortie.writeShort(idxDesc)
        sortie.writeShort(0) // attributs
    }

    /** Jar de test standard : deux paquets, une imbriquée, un anonyme. */
    fun jarStandard(): Map<String, ByteArray> =
        mapOf(
            "alpha/Alpha.class" to octetsClasse(champ = Triple(0x0009, "compteur", "I")),
            "alpha/Alpha\$Interne.class" to octetsClasse(),
            "alpha/Alpha\$1.class" to octetsClasse(),
            "beta/beta/Profond.class" to octetsClasse(),
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0".toByteArray(),
            "module-info.class" to octetsClasse(),
        )

    /** Entrée JAR du modèle pour un fichier réel. */
    fun entreeJar(
        chemin: String,
        empreinte: String,
        taille: Long,
        mtime: Long,
        portee: String? = "compile",
    ): EntreeClasspathInstantane =
        EntreeClasspathInstantane(
            kind = KindEntree.JAR,
            chemin = chemin,
            portee = portee,
            sources = null,
            aar = null,
            empreinte = empreinte,
            existe = true,
            taille = taille,
            mtime = mtime,
        )

    /** Classpath instantané d'une liste d'entrées. */
    fun classpath(entrees: List<EntreeClasspathInstantane>): ClasspathInstantane =
        ClasspathInstantane(entrees = entrees, empreinte = "empreinte-test")

    /** Répertoire temporaire unique du test appelant. */
    fun dossierTemporaire(nom: String): File {
        val base = Files.createTempDirectory("lsp-index-$nom").toFile()
        Runtime.getRuntime().addShutdownHook(Thread { base.deleteRecursively() })
        return base
    }
}
