package jo.lspkotlin.session

import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.MembreJvmKotlin
import jo.lspkotlin.api.MembresTypeKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Tests du quick-fix « IMPLÉMENTER LES MEMBRES MANQUANTS » (chantier
 * P1-C, ADR 0229) — par le SPI (chemin de production D6) : la clôture
 * des supertypes (PROJET via le port, CLASSPATH via la vue JVM), la
 * substitution des paramètres de type, l'appariement getters/setters
 * en propriétés, la garde générique (attribut `Signature` du bytecode)
 * et les exclusions du corps — chaque test éprouve AUSSI la borne
 * conservatrice (D3).
 *
 * La vue factice porte une interface du classpath (`demo.Ecoute`) dont
 * les OCTETS sont synthétiques : sans attribut `Signature` pour les
 * méthodes concrètes, AVEC pour la méthode générique (la garde est
 * éprouvée sur des octets réels, pas sur une convention de test).
 */
class MembresManquantsTest {
    private val vue = VueMembresFactice()

    private fun session(): jo.lspkotlin.api.SessionLangageKotlin {
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.definirClasspath(
            ClasspathKotlin(
                entrees = emptyList(),
                vue = vue,
                racinesSources = emptyList(),
                dossierIndex = null,
            ),
        )
        return session
    }

    // ── Supertypes PROJET ──────────────────────────────────────────────

    @Test
    fun `membres manquants - interface du projet, corps vide remplace`() {
        val session = session()
        val source =
            "interface Depot {\n  fun lire(id: String): String\n  val taille: Int\n}\n" +
                "class Memoire : Depot {\n}\n"
        val uri = "file:///memoire.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("Memoire") + 3
        val correctif = session.membresManquants(uri, decalage)
        // Le blanc entre accolades est REMPLACÉ par les deux stubs,
        // indentés d'une unité (4 espaces par défaut, corps vide).
        val ouverture = source.indexOf("{\n}")
        assertEquals(ouverture + 1, correctif?.debut)
        assertEquals(ouverture + 2, correctif?.fin)
        assertEquals(
            "\n    override fun lire(id: String): String = TODO()\n" +
                "    override val taille: Int get() = TODO()\n",
            correctif?.texte,
        )
        assertEquals(2, correctif?.nombre)
        session.close()
    }

    @Test
    fun `membres manquants - tout implemente, correctif absent (D3)`() {
        val session = session()
        val source =
            "interface Depot {\n  fun lire(id: String): String\n  val taille: Int\n}\n" +
                "class Memoire : Depot {\n  override fun lire(id: String): String = \"\"\n" +
                "  override val taille: Int get() = 0\n}\n"
        val uri = "file:///complet.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("Memoire") + 3
        assertNull(session.membresManquants(uri, decalage))
        session.close()
    }

    @Test
    fun `membres manquants - implementation partielle, insertion apres le dernier membre`() {
        val session = session()
        val source =
            "interface Depot {\n  fun lire(id: String): String\n  val taille: Int\n}\n" +
                "class Partiel : Depot {\n  override fun lire(id: String): String = \"\"\n}\n"
        val uri = "file:///partiel.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("Partiel") + 3
        val correctif = session.membresManquants(uri, decalage)
        // Le membre déclaré épuise `lire` (nom+arité) : seul `taille`
        // manque ; le blanc entre le dernier membre et l'accolade est
        // remplacé, indentation du membre existant (2 espaces).
        val finMembre = source.indexOf("= \"\"\n") + 4
        val fermeture = source.lastIndexOf("}")
        assertEquals(finMembre, correctif?.debut)
        assertEquals(fermeture, correctif?.fin)
        assertEquals("\n  override val taille: Int get() = TODO()\n", correctif?.texte)
        assertEquals(1, correctif?.nombre)
        session.close()
    }

    @Test
    fun `membres manquants - parametres de type substitues par la clause ecrite`() {
        val session = session()
        val source =
            "interface Depot<T> {\n  fun lire(id: T): T\n}\n" +
                "class Repo : Depot<String> {\n}\n"
        val uri = "file:///repo.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("Repo") + 2
        val correctif = session.membresManquants(uri, decalage)
        assertTrue(
            correctif?.texte?.contains("override fun lire(id: String): String = TODO()") == true,
        )
        session.close()
    }

    @Test
    fun `membres manquants - clause nue sur type generique, membre muet (D3)`() {
        val session = session()
        val source =
            "interface Depot<T> {\n  fun lire(id: T): T\n}\n" +
                "class RepoNu : Depot {\n}\n"
        val uri = "file:///repo-nu.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("RepoNu") + 3
        assertNull(session.membresManquants(uri, decalage))
        session.close()
    }

    @Test
    fun `membres manquants - corps absent, corps cree en fin d'en-tete`() {
        val session = session()
        val source =
            "interface Depot {\n  fun lire(id: String): String\n}\n" +
                "class Nu : Depot\n"
        val uri = "file:///nu.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("Nu") + 1
        val correctif = session.membresManquants(uri, decalage)
        assertEquals(source.length - 1, correctif?.debut)
        assertEquals(source.length - 1, correctif?.fin)
        assertEquals(
            " {\n    override fun lire(id: String): String = TODO()\n}",
            correctif?.texte,
        )
        session.close()
    }

    @Test
    fun `membres manquants - objet implementant l'interface`() {
        val session = session()
        val source =
            "interface Depot {\n  fun lire(id: String): String\n}\n" +
                "object Singleton : Depot {\n}\n"
        val uri = "file:///singleton.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("Singleton") + 4
        val correctif = session.membresManquants(uri, decalage)
        assertTrue(correctif?.texte?.contains("override fun lire(id: String): String = TODO()") == true)
        session.close()
    }

    @Test
    fun `membres manquants - cible interface ou classe abstraite, action absente (D3)`() {
        val session = session()
        val source =
            "interface Base {\n  fun a()\n}\n" +
                "interface Etendue : Base {\n}\n" +
                "abstract class Abstrait : Base {\n}\n"
        val uri = "file:///cibles.kt"
        session.ouvrirDocument(uri, source)
        assertNull(session.membresManquants(uri, source.indexOf("Etendue") + 2))
        assertNull(session.membresManquants(uri, source.indexOf("Abstrait") + 3))
        session.close()
    }

    @Test
    fun `membres manquants - membre concret du supertype, jamais collecte`() {
        val session = session()
        val source =
            "interface Depot {\n  fun lire(id: String): String\n  fun vide() { }\n}\n" +
                "class Memoire : Depot {\n}\n"
        val uri = "file:///concret.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("Memoire") + 3
        val correctif = session.membresManquants(uri, decalage)
        assertTrue(correctif?.texte?.contains("vide") == false)
        assertTrue(correctif?.texte?.contains("lire") == true)
        session.close()
    }

    // ── Supertypes CLASSPATH (vue JVM + sonde générique) ───────────────

    @Test
    fun `membres manquants - interface du classpath, methode et proprietes appariees`() {
        val session = session()
        val source = "import demo.Ecoute\nclass Vue : Ecoute {\n}\n"
        val uri = "file:///vue.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("Vue") + 1
        val correctif = session.membresManquants(uri, decalage)
        // `onClick` porte l'attribut Signature (générique) : ÉCARTÉ ;
        // `ouvrir` (méthode ordinaire), `getTitre`/`setTitre` (var),
        // `getMessage` (val seul) sont rendus depuis la vue JVM.
        val texte = correctif?.texte.orEmpty()
        assertTrue("ouvrir devait être rendu", "override fun ouvrir(p0: View): View = TODO()" in texte)
        assertTrue("le getter/setteur devient var", "override var titre: String" in texte)
        assertTrue(
            "la var porte ses accesseurs",
            "get() = TODO()" in texte && "set(value) { TODO() }" in texte,
        )
        assertTrue("le getter seul devient val", "override val message: String get() = TODO()" in texte)
        assertTrue("la méthode générique devait être écartée", "onClick" !in texte)
        assertEquals(3, correctif?.nombre)
        session.close()
    }

    /** Vue factice : interface du classpath `demo.Ecoute` + octets synthétiques. */
    private class VueMembresFactice : VueClasspathKotlin {
        private val membres: Map<String, MembresTypeKotlin> =
            mapOf(
                "demo.Ecoute" to
                    MembresTypeKotlin(
                        0x0201, // public interface
                        emptyList(),
                        listOf(
                            // générique (porte Signature dans les octets ci-dessous)
                            MembreJvmKotlin(0x0401, "onClick", "(Landroid/view/View;)V"),
                            MembreJvmKotlin(0x0401, "ouvrir", "(Landroid/view/View;)Landroid/view/View;"),
                            MembreJvmKotlin(0x0401, "getTitre", "()Ljava/lang/String;"),
                            MembreJvmKotlin(0x0401, "setTitre", "(Ljava/lang/String;)V"),
                            MembreJvmKotlin(0x0401, "getMessage", "()Ljava/lang/String;"),
                        ),
                    ),
            )

        override fun nomsParPrefixe(
            prefixe: String,
            limite: Int,
        ): List<jo.lspkotlin.api.NomTypeKotlin> = emptyList()

        override fun typesDansPaquet(paquet: String): List<String> = emptyList()

        override fun estPaquet(nom: String): Boolean = false

        override fun sousPaquetsDirects(nom: String): List<String> = emptyList()

        override fun membres(fqcn: String): MembresTypeKotlin? = membres[fqcn]

        override fun octetsClasse(fqcn: String): ByteArray? =
            if (fqcn == "demo.Ecoute") octetsEcoute() else null

        /**
         * Octets de `demo.Ecoute` : classe minimale lisible par
         * [jo.lspkotlin.classpath.LecteurClassBrut.lire] (super
         * java/lang/Object), dont la méthode `onClick` porte un attribut
         * `Signature` (la garde générique est éprouvée sur la vraie
         * structure du bytecode).
         */
        private fun octetsEcoute(): ByteArray {
            val sortie = ByteArrayOutputStream()
            DataOutputStream(sortie).use { flux ->
                flux.writeInt(0xCAFEBABE.toInt())
                flux.writeShort(0)
                flux.writeShort(52)
                // pool : 1 Class(demo/Ecoute) 2 Utf8 3 Class(java/lang/Object)
                //        4 Utf8 5 Utf8(onClick) 6 Utf8(desc onClick)
                //        7 Utf8(Signature) 8 Utf8(signature onClick)
                //        9 Utf8(ouvrir) 10 Utf8(desc ouvrir)
                flux.writeShort(10 + 1)
                fun utf8(texte: String) {
                    flux.writeByte(1)
                    val octets = texte.toByteArray(Charsets.UTF_8)
                    flux.writeShort(octets.size)
                    flux.write(octets)
                }
                flux.writeByte(7)
                flux.writeShort(2)
                utf8("demo/Ecoute")
                flux.writeByte(7)
                flux.writeShort(4)
                utf8("java/lang/Object")
                utf8("onClick")
                utf8("(Landroid/view/View;)V")
                utf8("Signature")
                utf8("(Landroid/view/View;)V")
                utf8("ouvrir")
                utf8("(Landroid/view/View;)Landroid/view/View;")
                flux.writeShort(0x0601) // access public|interface|abstract
                flux.writeShort(1) // this
                flux.writeShort(3) // super
                flux.writeShort(0) // interfaces
                flux.writeShort(0) // champs
                flux.writeShort(2) // méthodes
                // onClick : PORTE Signature (générique).
                flux.writeShort(0x0401)
                flux.writeShort(5)
                flux.writeShort(6)
                flux.writeShort(1)
                flux.writeShort(7) // attribute_name_index
                flux.writeInt(2) // longueur
                flux.writeShort(8) // signature_index
                // ouvrir : aucun attribut (concret au sens de la sonde).
                flux.writeShort(0x0401)
                flux.writeShort(9)
                flux.writeShort(10)
                flux.writeShort(0)
                flux.writeShort(0) // attributs de classe
            }
            return sortie.toByteArray()
        }
    }
}
