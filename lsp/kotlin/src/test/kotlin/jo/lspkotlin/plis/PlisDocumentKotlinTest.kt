package jo.lspkotlin.plis

import jo.lspkotlin.parse.ParseurKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du moteur de pliage Kotlin (S2 — plan de stabilisation LSP
 * §3.2) : chaque corps à accolades est UNE plage (de la première
 * accolade ouvrante à la dernière fermante — JAMAIS du début du nœud),
 * le bloc d'imports entier en est un, les commentaires multi-lignes
 * aussi. Déduplication BODY/BLOCK ; tolérance D3 (jamais d'exception).
 */
class PlisDocumentKotlinTest {
    private val parseur = ParseurKotlin()

    @Test
    fun `corps de classe et de fonctions - une plage par paire d accolades`() {
        val texte =
            "class Rendu {\n" +
                "    fun taille(): Int {\n" +
                "        return 1\n" +
                "    }\n" +
                "\n" +
                "    fun vide() {}\n" +
                "}\n"
        val arbre = parseur.analyser(texte)
        val plages = PlisDocumentKotlin.depuis(arbre.racine)
        // Le corps de `vide()` est MONO-LIGNE : il reste ÉMIS par le
        // moteur (l'hôte filtre) — deux blocs de fonction + la classe.
        assertEquals(3, plages.size)
        val classe = plages.first()
        assertEquals("le pli CLASSE commence à l'accolade", texte.indexOf('{'), classe.debut)
        assertEquals("…et finit à la fermante", texte.lastIndexOf('}') + 1, classe.fin)
        val taille = plages[1]
        assertEquals(texte.indexOf('{', texte.indexOf("taille")), taille.debut)
        assertEquals(texte.indexOf('}', texte.indexOf("return")) + 1, taille.fin)
    }

    @Test
    fun `liste d imports - du premier au dernier`() {
        val texte =
            "package jo.essai\n" +
                "\n" +
                "import android.view.View\n" +
                "import android.view.ViewGroup\n" +
                "import android.widget.TextView\n" +
                "\n" +
                "class A\n"
        val arbre = parseur.analyser(texte)
        val plages = PlisDocumentKotlin.depuis(arbre.racine)
        val imports = plages.first()
        assertEquals("début du PREMIER import", texte.indexOf("import"), imports.debut)
        assertEquals(
            "fin du DERNIER import",
            texte.indexOf("TextView") + "TextView".length,
            imports.fin,
        )
    }

    @Test
    fun `commentaire multi-ligne - KDoc et bloc pliables`() {
        val texte =
            "/**\n" +
                " * La doc.\n" +
                " */\n" +
                "class A {\n" +
                "    /* bloc\n" +
                "     * multi */\n" +
                "    fun f() {}\n" +
                "}\n"
        val arbre = parseur.analyser(texte)
        val plages = PlisDocumentKotlin.depuis(arbre.racine)
        val kdoc = plages.first()
        assertEquals("le KDoc commence à 0", 0, kdoc.debut)
        assertEquals("…et finit à son `*/`", texte.indexOf("*/") + 2, kdoc.fin)
        // Ordre du document : le KDoc, la classe, le bloc, la fonction.
        val bloc = plages.first { it.debut == texte.indexOf("/*", kdoc.fin) }
        assertEquals(
            "fin du bloc multi-ligne",
            texte.indexOf("multi */") + "multi */".length,
            bloc.fin,
        )
    }

    @Test
    fun `lambda et when - pliés par accolades, pas de doublon`() {
        val texte =
            "fun f(v: Int): String {\n" +
                "    val g = { x: Int -> x + 1 }\n" +
                "    return when (v) {\n" +
                "        0 -> \"zero\"\n" +
                "        else -> \"autre\"\n" +
                "    }\n" +
                "}\n"
        val arbre = parseur.analyser(texte)
        val plages = PlisDocumentKotlin.depuis(arbre.racine)
        // AUCUN doublon : les paires (debut, fin) sont dédupliquées.
        assertEquals(plages.size, plages.distinctBy { it.debut to it.fin }.size)
        // Le when est plié à ses accolades (pas au mot-clé) :
        val pliWhen = plages.last { it.debut == texte.indexOf('{', texte.indexOf("when")) }
        assertEquals(
            "fin du when (accolades, pas le mot-clé)",
            texte.indexOf('}', texte.indexOf("zero")) + 1,
            pliWhen.fin,
        )
        // Le lambda mono-ligne reste émis (filtre hôte) :
        assertTrue(
            "le lambda est pliable",
            plages.any { it.debut == texte.indexOf('{', texte.indexOf("val g")) },
        )
    }

    @Test
    fun `structures de controle - if for while sans doublon BODY BLOCK`() {
        val texte =
            "fun f(xs: List<Int>) {\n" +
                "    if (xs.isNotEmpty()) {\n" +
                "        for (x in xs) {\n" +
                "            while (x > 0) {\n" +
                "                println(x)\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "}\n"
        val arbre = parseur.analyser(texte)
        val plages = PlisDocumentKotlin.depuis(arbre.racine)
        assertEquals("aucun doublon", plages.size, plages.distinctBy { it.debut to it.fin }.size)
        // Quatre blocs distincts : f, if, for, while.
        assertEquals(4, plages.size)
    }

    @Test
    fun `edition en cours - accolade fermante absente, sous-rapport sans exception`() {
        val texte = "fun f() {\n    val x = 1\n"
        val arbre = parseur.analyser(texte)
        val plages = PlisDocumentKotlin.depuis(arbre.racine)
        assertTrue("sans paire d'accolades, aucun pli", plages.isEmpty())
    }

    @Test
    fun `document vide ou mono-ligne - rendu honnete, filtre hote ensuite`() {
        assertTrue(PlisDocumentKotlin.depuis(parseur.analyser("").racine).isEmpty())
        // `fun f() {}` tout sur une ligne : la plage est émise (le
        // filtre mono-ligne appartient à l'hôte, qui voit les lignes).
        val arbre = parseur.analyser("fun f() {}")
        val plages = PlisDocumentKotlin.depuis(arbre.racine)
        assertEquals(1, plages.size)
        assertEquals("accolade ouvrante", 8, plages.single().debut)
        assertEquals("après la fermante", 10, plages.single().fin)
    }
}
