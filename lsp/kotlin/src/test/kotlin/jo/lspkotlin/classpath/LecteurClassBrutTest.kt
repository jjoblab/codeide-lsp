package jo.lspkotlin.classpath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Tests du lecteur de class-files BRUT (K3) — deux vérités :
 *  - une classe SYNTHÉTIQUE fabriquée octet par octet (contrôle total
 *    du format : super, interfaces, absence de @Metadata) ;
 *  - une classe RÉELLE du kotlin-stdlib du classpath de test
 *    (`kotlin.jvm.internal.Intrinsics` — facade Kotlin avec @Metadata,
 *    kind FileFacade).
 */
class LecteurClassBrutTest {
    @Test
    fun `classe synthetique - identite, super et interfaces lues`() {
        val octets = octetsDeClasse("a.b.C", "x.y.Super", listOf("i.Iface", "j.Autre"))
        val infos = checkNotNull(LecteurClassBrut.lire(octets)) { "classe illisible" }
        assertEquals("a.b.C", infos.fqcn)
        assertEquals("x.y.Super", infos.superFqcn)
        assertEquals(listOf("i.Iface", "j.Autre"), infos.interfaces)
        assertNull(infos.metadonnees)
    }

    @Test
    fun `classe synthetique sans super - super null (java lang Object)`() {
        val infos = LecteurClassBrut.lire(octetsDeClasse("a.Racine", null, emptyList()))
        assertNotNull(infos)
        assertNull(infos!!.superFqcn)
        assertTrue(infos.interfaces.isEmpty())
    }

    @Test
    fun `octets invalides - null, jamais d'exception (D3)`() {
        assertNull(LecteurClassBrut.lire(ByteArray(0)))
        assertNull(LecteurClassBrut.lire(byteArrayOf(1, 2, 3, 4)))
        assertNull(LecteurClassBrut.lire("du texte".toByteArray()))
    }

    @Test
    fun `classe Kotlin reelle du stdlib - identite et metadata reconstruite`() {
        // StringsKt : facade multi-fichiers RÉELLE (k=4, @Metadata avec
        // mv/k/xi/d1 — javap-vérifié avant écriture du test).
        val octets = octetsRessource("kotlin/text/StringsKt.class")
        val infos = checkNotNull(LecteurClassBrut.lire(octets)) { "classe réelle illisible" }
        assertEquals("kotlin.text.StringsKt", infos.fqcn)
        // Le PROPOS du lecteur : l'annotation est reconstruite en
        // instance kotlin.Metadata exploitable. La superclasse est
        // RELLE (javap : la facade multi-fichiers étend sa dernière
        // partie StringsKt___StringsKt).
        assertNotNull(infos.metadonnees)
        assertEquals("kotlin.text.StringsKt___StringsKt", infos.superFqcn)
    }

    @Test
    fun `classe JAVA reelle du stdlib - pas de metadata (négatif honnête)`() {
        // Intrinsics est une classe JAVA du stdlib (« Compiled from
        // Intrinsics.java », aucun @Metadata) : le lecteur rend
        // metadonnees = null, sans erreur.
        val octets = octetsRessource("kotlin/jvm/internal/Intrinsics.class")
        val infos = checkNotNull(LecteurClassBrut.lire(octets)) { "classe réelle illisible" }
        assertEquals("kotlin.jvm.internal.Intrinsics", infos.fqcn)
        assertNull(infos.metadonnees)
    }

    /** Octets d'une ressource du classpath de test (jar stdlib réel). */
    private fun octetsRessource(chemin: String): ByteArray =
        checkNotNull(
            java.lang.ClassLoader.getSystemResourceAsStream(chemin)?.use { flux -> flux.readBytes() },
        ) { "ressource introuvable : $chemin" }

    /**
     * Fabrique une classe minimale VALIDE : paires {Utf8, Class} dans
     * l'ordre (this, super, interfaces) — aucun champ/méthode/attribut.
     */
    private fun octetsDeClasse(
        fqcn: String,
        superFqcn: String?,
        interfaces: List<String>,
    ): ByteArray {
        val sortie = ByteArrayOutputStream()
        DataOutputStream(sortie).use { flux ->
            flux.writeInt(0xCAFEBABE.toInt())
            flux.writeShort(0) // minor
            flux.writeShort(52) // major (Java 8)
            val paires = 1 + (if (superFqcn != null) 1 else 0) + interfaces.size
            flux.writeShort(paires * 2 + 1) // +1 : index 0 inutilisé
            fun utf8(texte: String) {
                flux.writeByte(1)
                val octets = texte.toByteArray(Charsets.UTF_8)
                flux.writeShort(octets.size)
                flux.write(octets)
            }
            fun classe(nomUtf8: Int) {
                flux.writeByte(7)
                flux.writeShort(nomUtf8)
            }
            var index = 1
            val thisUtf8 = index
            utf8(fqcn.replace('.', '/'))
            index += 2
            classe(thisUtf8)
            var superClasse = 0
            if (superFqcn != null) {
                val superUtf8 = index
                utf8(superFqcn.replace('.', '/'))
                index += 2
                classe(superUtf8)
                superClasse = superUtf8 + 1
            }
            val ifaceClasses = mutableListOf<Int>()
            for (iface in interfaces) {
                val ifaceUtf8 = index
                utf8(iface.replace('.', '/'))
                index += 2
                classe(ifaceUtf8)
                ifaceClasses.add(ifaceUtf8 + 1)
            }
            flux.writeShort(0x0021) // public super
            flux.writeShort(thisUtf8 + 1) // this_class
            flux.writeShort(superClasse) // super_class (0 = Object)
            flux.writeShort(ifaceClasses.size)
            ifaceClasses.forEach { flux.writeShort(it) }
            flux.writeShort(0) // fields
            flux.writeShort(0) // methods
            flux.writeShort(0) // attributes
        }
        return sortie.toByteArray()
    }
}
