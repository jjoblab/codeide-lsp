package jo.lspkotlin.session

import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.GenreIndiceKotlin
import jo.lspkotlin.api.GenreSymboleKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests des trois capacités L2 restantes — INDICES EN LIGNE
 * (`textDocument/inlayHint`), SYMBOLES D'ESPACE (`workspace/symbol`)
 * et CHAÎNES DE SÉLECTION (`textDocument/selectionRange`) — par le
 * SPI (chemin de production D6), moteur RÉEL.
 *
 * Le cœur éprouvé : l'honnêteté D3. Un appel sans candidate prouvée
 * ne rend AUCUN indice de paramètre ; une initialisation dont le type
 * n'est pas certain (expression composée, référence, suffixe non
 * modélisé) ne rend AUCUN indice de type ; les locales ne remontent
 * jamais dans la palette d'espace ; la chaîne de sélection est
 * STRICTEMENT croissante du plus profond au fichier entier.
 */
class CapacitesL2KotlinTest {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    /** Session avec racines sources (scan multi-fichiers actif). */
    private fun sessionAvecRacines(vararg fichiers: Pair<String, String>): SessionLangageKotlin {
        val racine = dossierTemporaire.newFolder("src")
        for ((chemin, contenu) in fichiers) {
            val fichier = File(racine, chemin)
            fichier.parentFile.mkdirs()
            fichier.writeText(contenu)
        }
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.definirClasspath(
            ClasspathKotlin(
                entrees = emptyList(),
                vue = null,
                racinesSources = listOf(racine),
                dossierIndex = null,
            ),
        )
        return session
    }

    /** Décalage du PREMIER caractère de [occurrence] dans [texte]. */
    private fun decalageDe(
        texte: String,
        occurrence: String,
        numero: Int = 0,
    ): Int {
        var index = -1
        repeat(numero + 1) {
            index = texte.indexOf(occurrence, index + 1)
        }
        return index
    }

    // ── Indices en ligne ─────────────────────────────────────────────

    @Test
    fun `indices - noms de paramètres aux appels positionnels prouvés`() {
        val texte =
            """
            fun somme(a: Int, b: Int): Int = a + b

            fun essai(): Int {
                return somme(1, 2)
            }
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        session.ouvrirDocument("fixture://Main.kt", texte)
        val indices = session.indices("fixture://Main.kt", 0, texte.length)
        // Les DEUX arguments positionnels portent le nom de leur
        // paramètre (anchés sur les arguments, label AVANT).
        assertEquals(2, indices.size)
        assertTrue(indices.all { it.genre == GenreIndiceKotlin.PARAMETRE })
        assertEquals(listOf("a", "b"), indices.map { it.libelle })
        assertEquals(decalageDe(texte, "1"), indices[0].debut)
        assertEquals(decalageDe(texte, "2"), indices[1].debut)
        session.close()
    }

    @Test
    fun `indices - argument nommé sans indice, positionnels suivis`() {
        val texte =
            """
            fun somme(a: Int, b: Int): Int = a + b

            fun essai(): Int = somme(1, b = 2)
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        session.ouvrirDocument("fixture://Main.kt", texte)
        val indices = session.indices("fixture://Main.kt", 0, texte.length)
        // Seul le positionnel `1` porte un indice ; le NOMMÉ `b = 2`
        // porte déjà son nom — jamais d'indice.
        assertEquals(1, indices.size)
        assertEquals("a", indices.single().libelle)
        assertEquals(decalageDe(texte, "1"), indices.single().debut)
        session.close()
    }

    @Test
    fun `indices - types certains des déclarations sans type écrit`() {
        val texte =
            """
            class Vue

            val entier = 42
            val decimale = 1.5
            val booleen = true
            val lettre = 'k'
            val chaine = "bonjour"
            val long = 7L
            val objet = Vue()
            val nonSu = 1u
            val typeEcrit: Int = 3
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        session.ouvrirDocument("fixture://Main.kt", texte)
        val indices = session.indices("fixture://Main.kt", 0, texte.length)
        val parLibelle = indices.filter { it.genre == GenreIndiceKotlin.TYPE }.associate { it.libelle to it.debut }
        assertEquals(
            mapOf(
                "Int" to decalageDe(texte, "entier"),
                "Double" to decalageDe(texte, "decimale"),
                "Boolean" to decalageDe(texte, "booleen"),
                "Char" to decalageDe(texte, "lettre"),
                "String" to decalageDe(texte, "chaine"),
                "Long" to decalageDe(texte, "long"),
                "Vue" to decalageDe(texte, "objet"),
            ),
            parLibelle,
        )
        // Les indices TYPE sont ancrés sur le NOM de la déclaration.
        assertEquals(decalageDe(texte, "long"), parLibelle.getValue("Long"))
        session.close()
    }

    @Test
    fun `indices - incertain sans indice, plage bornée, document inconnu vide`() {
        val texte =
            """
            fun f(x: Int): Int = x

            val compose = f(1) + f(2)
            val reference = f
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        session.ouvrirDocument("fixture://Main.kt", texte)
        val tous = session.indices("fixture://Main.kt", 0, texte.length)
        // L'appel f(1)/f(2) prouve ses paramètres ; l'initialisation
        // COMPOSÉE (somme d'appels) n'a pas de type certain : AUCUN
        // indice de type pour compose ; la RÉFÉRENCE f non plus.
        assertTrue(tous.none { it.genre == GenreIndiceKotlin.TYPE && it.debut == decalageDe(texte, "compose") })
        assertTrue(tous.none { it.genre == GenreIndiceKotlin.TYPE && it.debut == decalageDe(texte, "reference") })
        // Plage bornée : les indices ne sortent JAMAIS de la plage.
        val debutPlage = decalageDe(texte, "compose")
        val bornes = session.indices("fixture://Main.kt", debutPlage, texte.length)
        assertTrue(bornes.all { it.debut >= debutPlage })
        assertTrue(bornes.none { it.debut == decalageDe(texte, "f(1)") })
        // Document inconnu : vide, jamais d'exception.
        assertTrue(session.indices("fixture://inconnu.kt", 0, 10).isEmpty())
        session.close()
    }

    @Test
    fun `indices - it implicite du littéral, preuve par la fonction projet`() {
        val texte =
            """
            fun repeter(n: Int, action: (String) -> Unit) = Unit

            fun essai() {
                repeter(2) { println(it) }
            }
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        session.ouvrirDocument("fixture://Main.kt", texte)
        val indices = session.indices("fixture://Main.kt", 0, texte.length)
        // Le `it` IMPLICITE porte UN indice LAMBDA au libellé COMPLET
        // (nom et type — le nom n'existe nulle part dans le source),
        // ancré sur l'ACCOLADE OUVRANTE du littéral.
        val braceLitteral = texte.indexOf("{", texte.indexOf("repeter(2)"))
        val lambdas = indices.filter { it.genre == GenreIndiceKotlin.LAMBDA }
        assertEquals(1, lambdas.size)
        assertEquals("it: String", lambdas.single().libelle)
        assertEquals(braceLitteral, lambdas.single().debut)
        assertEquals(braceLitteral + 1, lambdas.single().fin)
        session.close()
    }

    @Test
    fun `indices - paramètres explicites sans type écrit, un TYPE par nom`() {
        val texte =
            """
            fun combiner(a: Int, b: Int, calcul: (Int, Int) -> Int): Int = 0

            fun essai(): Int {
                return combiner(1, 2) { x, y -> x + y }
            }
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        session.ouvrirDocument("fixture://Main.kt", texte)
        val indices = session.indices("fixture://Main.kt", 0, texte.length)
        // Chaque paramètre EXPLICITE sans type écrit porte UN indice
        // TYPE ancré sur son NOM (le contrat des déclarations) ; le
        // littéral lui-même ne porte PAS d'indice LAMBDA (les noms
        // sont déjà écrits).
        assertTrue(indices.none { it.genre == GenreIndiceKotlin.LAMBDA })
        val types = indices.filter { it.genre == GenreIndiceKotlin.TYPE }
        assertEquals(listOf("Int", "Int"), types.map { it.libelle })
        assertEquals(decalageDe(texte, "x"), types[0].debut)
        assertEquals(decalageDe(texte, "y"), types[1].debut)
        // Les arguments positionnels gardent leurs indices PARAMETRE.
        assertTrue(indices.any { it.genre == GenreIndiceKotlin.PARAMETRE && it.libelle == "a" })
        assertTrue(indices.any { it.genre == GenreIndiceKotlin.PARAMETRE && it.libelle == "b" })
        session.close()
    }

    @Test
    fun `indices - littéral sans preuve, arité divergente, paramètre typé`() {
        val texte =
            """
            fun combiner(a: Int, b: Int, calcul: (Int, Int) -> Int): Int = 0

            fun essai(): Int {
                inconnu(2) { println(it) }
                val r = combiner(1, 2) { x -> x }
                return combiner(1, 2) { x: Int, y -> x + y }
            }
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        session.ouvrirDocument("fixture://Main.kt", texte)
        val indices = session.indices("fixture://Main.kt", 0, texte.length)
        // `inconnu` n'est résolu NULLE PART : son littéral ne prouve
        // rien — AUCUN indice LAMBDA dans tout le document.
        assertTrue(indices.none { it.genre == GenreIndiceKotlin.LAMBDA })
        // L'arité du littéral diverge de l'attente (1 paramètre écrit
        // contre 2 attendus) : AUCUN indice de type sur `x`.
        val indicesX = indices.filter { it.genre == GenreIndiceKotlin.TYPE && it.debut == decalageDe(texte, "x") }
        assertTrue("arité divergente : aucun indice — reçus: $indices", indicesX.isEmpty())
        // Un paramètre DÉJÀ typé (`x: Int`) n'en reçoit pas non plus ;
        // le paramètre SANS type écrit (`y`) garde le sien.
        assertTrue(indices.any { it.genre == GenreIndiceKotlin.TYPE && it.debut == decalageDe(texte, "y") })
        session.close()
    }

    @Test
    fun `indices - it implicite par propriété typée et SAM du projet`() {
        val texte =
            """
            interface Ecrivain {
                fun ecrire(ligne: String)
            }

            val rappel: (Int) -> Unit = { println(it) }

            fun essai() {
                val e = Ecrivain { println(it) }
            }
            """.trimIndent()
        // Session SANS racines sources : le document ouvert EST la
        // seule source de déclarations (une session à racines qui
        // scannerait CE MÊME fichier sous une autre URI produirait un
        // DOUBLON de l'interface — la garde d'unicité du SAM la
        // rejetterait honnêtement, D3).
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.ouvrirDocument("fixture://Main.kt", texte)
        val indices = session.indices("fixture://Main.kt", 0, texte.length)
        val lambdas = indices.filter { it.genre == GenreIndiceKotlin.LAMBDA }
        // DEUX `it` implicites prouvés : l'initialiseur de la
        // PROPRIÉTÉ typée (`rappel: (Int) -> Unit`) et le constructeur
        // SAM de l'interface à méthode unique.
        assertEquals(listOf("it: Int", "it: String"), lambdas.map { it.libelle }.sorted())
        val braceRappel = texte.indexOf("{", texte.indexOf("rappel"))
        assertEquals(braceRappel, lambdas.single { it.libelle == "it: Int" }.debut)
        session.close()
    }

    // ── Symboles d'espace ────────────────────────────────────────────

    @Test
    fun `symbolesEspace - par requête contient, locales exclues, ouverts et fermés`() {
        val ouvert =
            """
            package p

            class ClientOuvert

            fun localeOuverte() {
                val cachee = 1
            }
            """.trimIndent()
        val ferme =
            """
            package p

            interface ServiceFerme
            """.trimIndent()
        val session = sessionAvecRacines("p/Ouvert.kt" to ouvert, "p/Ferme.kt" to ferme)
        session.ouvrirDocument("fixture://Ouvert.kt", ouvert)
        // Requête « ferme » (contient, insensible à la casse) →
        // l'interface du fichier FERMÉ du scan.
        val fermeResultat = session.symbolesEspace("ferme")
        assertEquals(listOf("ServiceFerme"), fermeResultat.map { it.nom })
        assertEquals(GenreSymboleKotlin.INTERFACE, fermeResultat.single().genre)
        assertTrue(fermeResultat.single().uri.endsWith("Ferme.kt"))
        // Requête vide = tout (ordres stables par nom) — la LOCALE
        // `cachee` ne remonte JAMAIS.
        val tout = session.symbolesEspace("")
        assertTrue(tout.any { it.nom == "ClientOuvert" })
        assertTrue(tout.any { it.nom == "localeOuverte" })
        assertTrue(tout.none { it.nom == "cachee" })
        // Ordre par nom.
        assertEquals(tout.map { it.nom }, tout.map { it.nom }.sorted())
        session.close()
    }

    @Test
    fun `symbolesEspace - requête sans réponse vide`() {
        val session = sessionAvecRacines("Aucun.kt" to "val x = 1\n")
        session.ouvrirDocument("fixture://Aucun.kt", "val x = 1\n")
        assertTrue(session.symbolesEspace("zzzintrouvable").isEmpty())
        session.close()
    }

    // ── Chaînes de sélection ─────────────────────────────────────────

    @Test
    fun `selections - chaîne strictement croissante du profond au fichier`() {
        val texte =
            """
            fun essai() {
                val x = 1
            }
            """.trimIndent()
        val session = sessionAvecRacines("Main.kt" to texte)
        session.ouvrirDocument("fixture://Main.kt", texte)
        val chaines = session.selections("fixture://Main.kt", listOf(decalageDe(texte, "1")))
        assertEquals(1, chaines.size)
        val plages = chaines.single().plages
        // Le fichier ENTIER est le DERNIER maillon.
        assertEquals(0, plages.last().debut)
        assertEquals(texte.length, plages.last().fin)
        // STRICTEMENT croissant, du plus profond au fichier.
        plages.zipWithNext { courant, suivant ->
            assertTrue(
                "maillons non croissants : $plages",
                suivant.debut <= courant.debut && suivant.fin >= courant.fin,
            )
        }
        assertTrue(plages.first().fin - plages.first().debut < plages.last().fin - plages.last().debut)
        // Le maillon le plus profond est le littéral `1`.
        assertEquals(decalageDe(texte, "1"), plages.first().debut)
        session.close()
    }

    @Test
    fun `selections - plusieurs positions dans l'ordre, hors arbre fichier seul`() {
        val texte = "val a = 1\nval b = 2\n"
        val session = sessionAvecRacines("Main.kt" to texte)
        session.ouvrirDocument("fixture://Main.kt", texte)
        val curseurs = listOf(decalageDe(texte, "a"), decalageDe(texte, "b"), texte.length + 5)
        val chaines = session.selections("fixture://Main.kt", curseurs)
        assertEquals(3, chaines.size)
        // Hors arbre : la chaîne du FICHIER SEUL (rien n'est inventé).
        assertEquals(1, chaines[2].plages.size)
        assertEquals(0, chaines[2].plages.single().debut)
        // Document inconnu : liste VIDE, jamais d'exception.
        assertTrue(session.selections("fixture://inconnu.kt", listOf(0)).isEmpty())
        session.close()
    }
}
