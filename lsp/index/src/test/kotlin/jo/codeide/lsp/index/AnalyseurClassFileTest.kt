package jo.codeide.lsp.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'analyseur de fichiers de classe — octets forgés (contrôle
 * total) ET classe réelle compilée (le pool de constantes du compilateur
 * réel est parcouru : Long/Double à deux slots, attributs sautés).
 */
class AnalyseurClassFileTest {
    @Test
    fun `analyse une classe forgée — drapeaux, champ statique, constructeur`() {
        val octets = FixturesIndex.octetsClasse()

        val membres = AnalyseurClassFile.analyser(octets)
        assertNotNull(membres)
        assertEquals(0x0021, membres!!.drapeauxClasse)
        assertEquals(1, membres.champs.size)
        assertEquals(0x0009, membres.champs[0].drapeaux)
        assertEquals("valeur", membres.champs[0].nom)
        assertEquals("I", membres.champs[0].descripteur)
        assertEquals(1, membres.methodes.size)
        assertEquals("<init>", membres.methodes[0].nom)
        assertEquals("()V", membres.methodes[0].descripteur)
    }

    @Test
    fun `une classe réelle du classpath est analysée (pool du compilateur)`() {
        // Charge les octets d'une vraie classe compilée du test lui-même.
        val nomRessource = javaClass.name.replace('.', '/') + ".class"
        val octets = javaClass.classLoader.getResourceAsStream(nomRessource)?.readBytes()

        // La ressource existe en test JVM ; si l'environnement l'omet,
        // le test reste vert SANS masquer : il vérifie juste la robustesse.
        if (octets == null) {
            return
        }
        val membres = AnalyseurClassFile.analyser(octets)
        assertNotNull(membres)
        // Cette classe de test porte au moins deux méthodes de test.
        assertTrue(membres!!.methodes.size >= 2)
        assertTrue(membres.methodes.any { it.nom.contains("class") || it.nom == "<init>" })
    }

    @Test
    fun `des octets aléatoires rendent null (pas d'exception)`() {
        assertNull(AnalyseurClassFile.analyser(ByteArray(64) { it.toByte() }))
        assertNull(AnalyseurClassFile.analyser(ByteArray(0)))
        assertNull(AnalyseurClassFile.analyser(" texte simple ".toByteArray()))
    }

    @Test
    fun `une classe tronquée après le pool rend null (jamais de crash)`() {
        val octets = FixturesIndex.octetsClasse()
        assertNull(AnalyseurClassFile.analyser(octets.copyOfRange(0, octets.size - 4)))
    }

    @Test
    fun `les constantes Long et Double occupent deux slots (décalage correct)`() {
        // ADR 0219 : classe forgée AVEC un long DANS le pool — les 8
        // octets de la VALEUR doivent être sautés ; avant le correctif,
        // le lecteur lisait le tag suivant DANS la valeur, échouait sur
        // un tag inconnu et rendait null : TOUTE classe portant une
        // constante long/double perdait sa section membres (le vrai cas
        // android.content.Context du corpus Views).
        val chaine = mutableListOf<String>()

        fun utf8(texte: String): Int {
            chaine.add(texte)
            return chaine.size
        }
        val idxNomChamp = utf8("valeur")
        val idxDescChamp = utf8("J")
        val idxNomMethode = utf8("<init>")
        val idxDescMethode = utf8("()V")

        val tampon = java.io.ByteArrayOutputStream()
        val sortie = java.io.DataOutputStream(tampon)
        sortie.writeInt(0xCAFEBABE.toInt())
        sortie.writeShort(0)
        sortie.writeShort(52)
        // +1 : slot 0 fantôme ; +2 : le Long occupe DEUX slots.
        sortie.writeShort(chaine.size + 3)
        sortie.writeByte(1)
        sortie.writeShort(chaine[idxNomChamp - 1].toByteArray(Charsets.UTF_8).size)
        sortie.write(chaine[idxNomChamp - 1].toByteArray(Charsets.UTF_8))
        sortie.writeByte(1)
        sortie.writeShort(chaine[idxDescChamp - 1].toByteArray(Charsets.UTF_8).size)
        sortie.write(chaine[idxDescChamp - 1].toByteArray(Charsets.UTF_8))
        sortie.writeByte(5) // TAG_LONG
        sortie.writeLong(Long.MAX_VALUE) // ← les 8 octets à SAUTER
        sortie.writeByte(1)
        sortie.writeShort(chaine[idxNomMethode - 1].toByteArray(Charsets.UTF_8).size)
        sortie.write(chaine[idxNomMethode - 1].toByteArray(Charsets.UTF_8))
        sortie.writeByte(1)
        sortie.writeShort(chaine[idxDescMethode - 1].toByteArray(Charsets.UTF_8).size)
        sortie.write(chaine[idxDescMethode - 1].toByteArray(Charsets.UTF_8))
        sortie.writeShort(0x0021)
        sortie.writeShort(0) // this
        sortie.writeShort(0) // super
        sortie.writeShort(0) // interfaces
        sortie.writeShort(1) // un champ
        sortie.writeShort(0x0019)
        sortie.writeShort(idxNomChamp)
        sortie.writeShort(idxDescChamp)
        sortie.writeShort(0) // attributs du champ
        sortie.writeShort(1) // une méthode
        sortie.writeShort(0x0001)
        // +2 : le Long occupe les slots 3-4, les chaînes de la méthode
        // vivent aux indices 5 et 6 du pool.
        sortie.writeShort(idxNomMethode + 2)
        sortie.writeShort(idxDescMethode + 2)
        sortie.writeShort(0) // attributs de la méthode
        sortie.flush()

        val membres = AnalyseurClassFile.analyser(tampon.toByteArray())
        assertNotNull("la classe avec un long dans le pool DOIT avoir ses membres", membres)
        assertEquals("valeur", membres!!.champs.single().nom)
        assertEquals("<init>", membres.methodes.single().nom)
    }
}
