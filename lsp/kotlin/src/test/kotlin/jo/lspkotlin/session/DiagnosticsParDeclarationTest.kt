package jo.lspkotlin.session

import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.MembresTypeKotlin
import jo.lspkotlin.api.NomTypeKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du chantier P1-A « diagnostics par déclaration » (garde
 * d'édition du texte vivant) — un document portant des marqueurs
 * d'erreur n'est plus écarté EN BLOC : seules les DÉCLARATIONS dont le
 * sous-arbre parse proprement sont diagnostiquées, et `kt.unresolved`
 * (preuve d'ABSENCE) porte des gardes dédiées :
 *
 *  - un nom DÉCLARÉ n'importe où dans le document (déclaration en cours
 *    de frappe ou avalée par la récupération) n'est jamais signalé ;
 *  - une zone package/import brisée coupe la preuve d'absence pour TOUT
 *    le document (les noms importés ne sont plus fiables) ;
 *  - un EN-TÊTE de classe brisé (supertypes, paramètres de type) rend
 *    les membres hérités illisibles : les membres propres restent
 *    diagnostiquables mais PAS absence-fiables ;
 *  - une surcharge du document courant hors région propre n'est pas un
 *    témoin d'arité (`kt.argumentCount` l'écarte).
 *
 * Miroir des tests de garde de SessionLangageKotlinK4Test (l'ancien
 * « tout ou rien » reste vrai pour un document SANS aucune région
 * propre), par le SPI de production (chemin D6).
 */
class DiagnosticsParDeclarationTest {
    /** Vue factice VIDE — aucun membre classpath : les preuves d'absence ne dépendent que de la structure. */
    private class VueVide : VueClasspathKotlin {
        override fun nomsParPrefixe(prefixe: String, limite: Int): List<NomTypeKotlin> = emptyList()

        override fun typesDansPaquet(paquet: String): List<String> = emptyList()

        override fun estPaquet(nom: String): Boolean = false

        override fun sousPaquetsDirects(nom: String): List<String> = emptyList()

        override fun membres(fqcn: String): MembresTypeKotlin? = null

        override fun octetsClasse(fqcn: String): ByteArray? = null
    }

    /** Session nourrie d'un classpath vide mais DÉCLARÉ (l'index existe, la preuve d'absence est possible). */
    private fun session(): SessionLangageKotlin {
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.definirClasspath(
            ClasspathKotlin(
                entrees = emptyList(),
                vue = VueVide(),
                racinesSources = emptyList(),
                dossierIndex = null,
            ),
        )
        return session
    }

    @Test
    fun `region propre - la frappe au bas du fichier n'eteint pas les preuves du dessus`() {
        val session = session()
        val source = "fun propre() {\n  introuvable\n}\n\nfun cassee( {\n"
        val uri = "file:///edition-bas.kt"
        session.ouvrirDocument(uri, source)
        val diagnostics = session.diagnostics(uri)
        assertTrue(
            "le marqueur de syntaxe reste rendu",
            diagnostics.any { it.code == CodesDiagnosticsKotlin.SYNTAXE },
        )
        val nonResolus =
            diagnostics.filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }
        assertEquals("la déclaration PROPRE garde son diagnostic", 1, nonResolus.size)
        assertEquals(source.indexOf("introuvable"), nonResolus.single().debut)
        session.close()
    }

    @Test
    fun `region propre - preuve locale (retour manquant) pendant l'edition ailleurs`() {
        val session = session()
        val source = "fun retourne(): Int {\n  val a = 1\n}\n\nfun cassee( {\n"
        val uri = "file:///edition-retour.kt"
        session.ouvrirDocument(uri, source)
        val diagnostics = session.diagnostics(uri)
        assertTrue(
            "le retour manquant de la fonction PROPRE est signalé",
            diagnostics.any { it.code == CodesDiagnosticsKotlin.RETOUR_MANQUANT },
        )
        session.close()
    }

    @Test
    fun `classe - membre propre diagnostique, membre casse muet`() {
        val session = session()
        val source =
            "class Maison {\n  fun propre() {\n    introuvable\n  }\n\n  fun cassee( {\n  }\n}\n"
        val uri = "file:///edition-classe.kt"
        session.ouvrirDocument(uri, source)
        val diagnostics = session.diagnostics(uri)
        val nonResolus =
            diagnostics.filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }
        assertEquals("le membre PROPRE reste diagnostiqué", 1, nonResolus.size)
        assertEquals(source.indexOf("introuvable"), nonResolus.single().debut)
        session.close()
    }

    @Test
    fun `nom declare en cours de frappe - jamais signale non resolu`() {
        val session = session()
        val source = "fun appelant() {\n  encours()\n}\n\nfun encours( {\n"
        val uri = "file:///edition-declare.kt"
        session.ouvrirDocument(uri, source)
        val diagnostics = session.diagnostics(uri)
        assertTrue(
            "un nom DÉCLARÉ (déclaration en cours) n'est jamais non résolu",
            diagnostics.none { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE },
        )
        assertTrue(
            "la surcharge en cours de frappe n'est pas un témoin d'arité",
            diagnostics.none { it.code == CodesDiagnosticsKotlin.NOMBRE_ARGUMENTS },
        )
        session.close()
    }

    @Test
    fun `zone import brisee - preuve d'absence muette sur tout le document`() {
        val session = session()
        val source = "import android.\n\nfun propre() {\n  introuvable\n}\n"
        val uri = "file:///edition-import.kt"
        session.ouvrirDocument(uri, source)
        val diagnostics = session.diagnostics(uri)
        assertTrue(
            "le marqueur de syntaxe de la zone import est rendu",
            diagnostics.any { it.code == CodesDiagnosticsKotlin.SYNTAXE },
        )
        assertTrue(
            "zone package/import brisée : aucun kt.unresolved sur le document",
            diagnostics.none { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE },
        )
        session.close()
    }

    @Test
    fun `en-tete de classe brise - absence muette dans les membres propres`() {
        val session = session()
        val source = "class Maison : Base< {\n  fun membre() {\n    introuvable\n  }\n}\n"
        val uri = "file:///edition-entete.kt"
        session.ouvrirDocument(uri, source)
        val diagnostics = session.diagnostics(uri)
        assertTrue(
            "en-tête brisé : les membres hérités ne sont pas lisibles, absence muette",
            diagnostics.none { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE },
        )
        session.close()
    }

    @Test
    fun `surcharge en cours de frappe - candidate d'arite ecartee`() {
        val session = session()
        val source = "fun consomme() {\n  canal(1, 2)\n}\n\nfun canal( {\n"
        val uri = "file:///edition-arite.kt"
        session.ouvrirDocument(uri, source)
        val diagnostics = session.diagnostics(uri)
        assertTrue(
            "la surcharge cassée n'est pas un témoin d'arité fiable",
            diagnostics.none { it.code == CodesDiagnosticsKotlin.NOMBRE_ARGUMENTS },
        )
        session.close()
    }

    @Test
    fun `document propre - comportement inchange`() {
        val session = session()
        val source = "fun demo() {\n  introuvable\n}\n"
        val uri = "file:///propre.kt"
        session.ouvrirDocument(uri, source)
        val diagnostics = session.diagnostics(uri)
        assertEquals(
            "sans marqueur d'erreur, le diagnostic historique est inchangé",
            1,
            diagnostics.filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }.size,
        )
        session.close()
    }
}
