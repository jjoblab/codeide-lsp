package jo.lspkotlin.session

import jo.lspkotlin.api.AppelFonctionKotlin
import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.ExpressionNonInterpreteeKotlin
import jo.lspkotlin.api.GenreArgumentAppelKotlin
import jo.lspkotlin.api.GenreInlineKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests du contrat de session C1 (preview Compose — `previews(uri)`,
 * PREVIEW.md §4.4) — par le SPI (chemin de production D6) : la preuve
 * FQCN des annotations PAR LES IMPORTS, les paramètres `@Preview`
 * tranchés dans la source, la réduction du corps en ARBRE D'APPELS
 * (littéraux distingués, lambda final = contenu, expressions
 * honnêtes), l'INLINING des composables du projet (cycles, budgets,
 * paramétrés, illisibles) — jamais un faux rendu.
 */
class SessionLangageKotlinPreviewsTest {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    /** En-tête commun des fichiers à previews : les DEUX imports réels. */
    private val imports =
        """
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview
        """.trimIndent()

    private fun session(): jo.lspkotlin.api.SessionLangageKotlin =
        ServiceMoteurLangageKotlin.unique().creerSession()

    private fun sessionProjet(): Pair<jo.lspkotlin.api.SessionLangageKotlin, File> {
        val racine = dossierTemporaire.newFolder("src")
        val session = session()
        session.definirClasspath(
            ClasspathKotlin(
                entrees = emptyList(),
                vue = null,
                racinesSources = listOf(racine),
                dossierIndex = null,
            ),
        )
        return session to racine
    }

    private fun uriDe(fichier: File): String = fichier.toURI().toString()

    // ── Découverte : preuve FQCN par les imports ──────────────────────

    @Test
    fun `preview simple - corps reduit en arbre d'appels`() {
        val session = session()
        val source =
            """
            $imports

            @Preview
            @Composable
            fun ApercuAccueil() {
                Text("Bonjour")
            }
            """.trimIndent()
        session.ouvrirDocument("file:///apercu.kt", source)
        val previews = session.previews("file:///apercu.kt")
        assertEquals(1, previews.size)
        val preview = previews.single()
        assertEquals("ApercuAccueil", preview.nom)
        assertTrue(preview.parametres.isEmpty())
        val appel = preview.corps.single() as AppelFonctionKotlin
        assertEquals("Text", appel.nom)
        assertEquals(1, appel.arguments.size)
        assertEquals(GenreArgumentAppelKotlin.LITTERAL, appel.arguments.single().genre)
        assertEquals("\"Bonjour\"", appel.arguments.single().texte)
        assertTrue(appel.contenu.isEmpty())
        assertEquals(GenreInlineKotlin.AUCUN, appel.genreInline)
    }

    @Test
    fun `parametres d'annotation - valeurs tranchees dans la source`() {
        val session = session()
        val source =
            """
            $imports

            @Preview(widthDp = 320, heightDp = 640, showBackground = true, device = "spec:shape=Normal")
            @Composable
            fun ApercuEcran() {
            }
            """.trimIndent()
        session.ouvrirDocument("file:///apercu.kt", source)
        val preview = session.previews("file:///apercu.kt").single()
        assertEquals(
            listOf("widthDp", "heightDp", "showBackground", "device"),
            preview.parametres.map { it.nom },
        )
        assertEquals("320", preview.parametres[0].valeurTexte)
        assertEquals("640", preview.parametres[1].valeurTexte)
        assertEquals("true", preview.parametres[2].valeurTexte)
        assertEquals("\"spec:shape=Normal\"", preview.parametres[3].valeurTexte)
        assertTrue(preview.corps.isEmpty())
    }

    @Test
    fun `sans Composable, sans Preview, homonyme d'un autre paquet - absents`() {
        val session = session()
        val source =
            """
            import androidx.compose.runtime.Composable
            import com.ex.autre.Preview

            @Preview
            fun SansComposable() {}

            @Composable
            fun SansPreview() {}

            @Preview
            @Composable
            fun Homonyme() {}
            """.trimIndent()
        // Le SEUL import de « Preview » pointe vers un AUTRE paquet :
        // l'annotation ne résout JAMAIS à ui-tooling-preview (D3).
        session.ouvrirDocument("file:///apercu.kt", source)
        assertEquals(0, session.previews("file:///apercu.kt").size)
    }

    @Test
    fun `import joker et forme qualifiee - preuve FQCN tenue`() {
        val session = session()
        val source =
            """
            import androidx.compose.runtime.*
            import androidx.compose.ui.tooling.preview.Preview

            @Preview
            @Composable
            fun JokerRuntime() {
                Texte("x")
            }

            @androidx.compose.ui.tooling.preview.Preview
            @androidx.compose.runtime.Composable
            fun FormeQualifiee() {
            }
            """.trimIndent()
        session.ouvrirDocument("file:///apercu.kt", source)
        val previews = session.previews("file:///apercu.kt")
        // Le joker runtime NE résout PAS Composable sans vue de
        // classpath — conservateur : JokerRuntime est SOUS-RAPPORTÉ ;
        // les formes qualifiées, elles, sont TOUJOURS prouvées.
        assertEquals(listOf("FormeQualifiee"), previews.map { it.nom })
    }

    @Test
    fun `plusieurs previews et membre de classe - ordre du document`() {
        val session = session()
        val source =
            """
            $imports

            @Preview
            @Composable
            fun Premiere() {
            }

            class Ecran {
                @Preview
                @Composable
                fun Membre() {
                }
            }

            @Preview
            @Composable
            fun Seconde() {
            }
            """.trimIndent()
        session.ouvrirDocument("file:///apercu.kt", source)
        assertEquals(
            listOf("Premiere", "Membre", "Seconde"),
            session.previews("file:///apercu.kt").map { it.nom },
        )
    }

    @Test
    fun `document inconnu - liste vide`() {
        assertEquals(0, session().previews("file:///inconnu.kt").size)
    }

    // ── Réduction du corps : appels, littéraux, expressions ───────────

    @Test
    fun `lambda final - contenu imbrique, arguments distingués`() {
        val session = session()
        val source =
            """
            $imports

            @Preview
            @Composable
            fun ApercuColonne() {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(texte = "Titre", color = Color.Blue)
                    Spacer(gap = 8.dp)
                    val locale = 1
                    Button(onClick = {}) {
                        Text("Clique")
                    }
                }
            }
            """.trimIndent()
        session.ouvrirDocument("file:///apercu.kt", source)
        val colonne =
            session.previews("file:///apercu.kt").single().corps.single() as AppelFonctionKotlin
        assertEquals("Column", colonne.nom)
        // modifier = Modifier.fillMaxWidth() : appel imbriqué = AUTRE.
        assertEquals(
            listOf(GenreArgumentAppelKotlin.AUTRE),
            colonne.arguments.map { it.genre },
        )
        assertEquals(4, colonne.contenu.size)
        val titre = colonne.contenu[0] as AppelFonctionKotlin
        assertEquals("Text", titre.nom)
        assertEquals(
            listOf("texte", "color"),
            titre.arguments.map { it.nom },
        )
        assertEquals(
            listOf(GenreArgumentAppelKotlin.LITTERAL, GenreArgumentAppelKotlin.REFERENCE),
            titre.arguments.map { it.genre },
        )
        // 8.dp : expression qualifiée = REFERENCE (le rendu parse le dp).
        val espace = colonne.contenu[1] as AppelFonctionKotlin
        assertEquals(GenreArgumentAppelKotlin.REFERENCE, espace.arguments.single().genre)
        // Une LOCALE est une expression non interprétée HONNÊTE (D3).
        assertTrue(colonne.contenu[2] is ExpressionNonInterpreteeKotlin)
        val bouton = colonne.contenu[3] as AppelFonctionKotlin
        assertEquals("Button", bouton.nom)
        // onClick = {} : AUTRE ; le lambda FINAL est le CONTENU.
        assertEquals(GenreArgumentAppelKotlin.AUTRE, bouton.arguments.single().genre)
        assertEquals("Text", (bouton.contenu.single() as AppelFonctionKotlin).nom)
    }

    // ── Inlining des composables du projet ────────────────────────────

    @Test
    fun `composable du projet - corps inline depuis le disque`() {
        val (session, racine) = sessionProjet()
        File(racine, "CarteProfil.kt").writeText(
            """
            package com.ex.app

            import androidx.compose.runtime.Composable

            @Composable
            fun CarteProfil() {
                Texte("Nom")
            }
            """.trimIndent(),
        )
        val source =
            """
            package com.ex.app

            $imports

            @Preview
            @Composable
            fun ApercuProfil() {
                CarteProfil()
            }
            """.trimIndent()
        val uri = uriDe(File(racine, "ApercuProfil.kt").apply { writeText(source) })
        session.ouvrirDocument(uri, source)
        val appel = session.previews(uri).single().corps.single() as AppelFonctionKotlin
        // La candidate vit sur le DISQUE (scan) — l'inline passe par le
        // port (disque), la preuve @Composable par SES imports.
        assertEquals(GenreInlineKotlin.PROJET_INLINE, appel.genreInline)
        assertEquals(1, appel.contenu.size)
        assertEquals("Texte", (appel.contenu.single() as AppelFonctionKotlin).nom)
    }

    @Test
    fun `cycle de composables - genre CYCLE, jamais de recursivite`() {
        val (session, racine) = sessionProjet()
        val source =
            """
            package com.ex.app

            $imports

            @Preview
            @Composable
            fun Alpha() {
                Beta()
            }

            @Composable
            fun Beta() {
                Alpha()
            }
            """.trimIndent()
        val uri = uriDe(File(racine, "Cycle.kt").apply { writeText(source) })
        session.ouvrirDocument(uri, source)
        // Le corps de la preview Alpha : l'appel `Beta()` — INLINÉ, son
        // contenu est le corps marché de Beta.
        val appelBeta =
            session.previews(uri).single().corps.single() as AppelFonctionKotlin
        assertEquals("Beta", appelBeta.nom)
        assertEquals(GenreInlineKotlin.PROJET_INLINE, appelBeta.genreInline)
        // Le corps inliné de Beta contient l'appel `Alpha()` — la
        // racine Alpha est DÉJÀ en visite : CYCLE typé, jamais de
        // récursion infinie.
        val rappelAlpha = appelBeta.contenu.single() as AppelFonctionKotlin
        assertEquals("Alpha", rappelAlpha.nom)
        assertEquals(GenreInlineKotlin.PROJET_CYCLE, rappelAlpha.genreInline)
        assertTrue(rappelAlpha.contenu.isEmpty())
    }

    @Test
    fun `composable parametre - genre PARAMETRES (C1 n'inlinie pas)`() {
        val (session, racine) = sessionProjet()
        val source =
            """
            package com.ex.app

            $imports

            @Preview
            @Composable
            fun ApercuTitre() {
                Titre("Bonjour")
            }

            @Composable
            fun Titre(texte: String) {
                Texte(texte)
            }
            """.trimIndent()
        val uri = uriDe(File(racine, "Titre.kt").apply { writeText(source) })
        session.ouvrirDocument(uri, source)
        val appel = session.previews(uri).single().corps.single() as AppelFonctionKotlin
        assertEquals(GenreInlineKotlin.PROJET_PARAMETRES, appel.genreInline)
        assertTrue(appel.contenu.isEmpty())
    }

    @Test
    fun `chaine d'inline profonde - genre BUDGET au-dela de la borne`() {
        val (session, racine) = sessionProjet()
        val lignes = mutableListOf<String>()
        lignes.add("package com.ex.app")
        lignes.add(imports)
        lignes.add("")
        lignes.add("@Preview")
        lignes.add("@Composable")
        lignes.add("fun Palier0() {")
        lignes.add("    Palier1()")
        lignes.add("}")
        // 10 paliers : au-delà de PROFONDEUR_INLINE (6), le 7e inline
        // est coupé — genre BUDGET typé, jamais une récursion infinie.
        for (palier in 1..9) {
            lignes.add("")
            lignes.add("@Composable")
            lignes.add("fun Palier$palier() {")
            lignes.add("    Palier${palier + 1}()")
            lignes.add("}")
        }
        lignes.add("")
        lignes.add("@Composable")
        lignes.add("fun Palier10() {")
        lignes.add("    Texte(\"fin\")")
        lignes.add("}")
        val source = lignes.joinToString("\n")
        val uri = uriDe(File(racine, "Paliers.kt").apply { writeText(source) })
        session.ouvrirDocument(uri, source)
        var noeud: AppelFonctionKotlin =
            session.previews(uri).single().corps.single() as AppelFonctionKotlin
        // Palier1..Palier5 : cinq inlines réussis (la racine Palier0
        // occupe DÉJÀ une place dans la pile — borne 6 en visite).
        var palier = 0
        while (palier < 5) {
            assertEquals(GenreInlineKotlin.PROJET_INLINE, noeud.genreInline)
            noeud = noeud.contenu.single() as AppelFonctionKotlin
            palier++
        }
        // L'inline suivant dépasse la borne : genre BUDGET, contenu vide.
        assertEquals(GenreInlineKotlin.PROJET_BUDGET, noeud.genreInline)
        assertTrue(noeud.contenu.isEmpty())
    }

    @Test
    fun `appel a une fonction du projet NON composable - pas d'inline`() {
        val (session, racine) = sessionProjet()
        val source =
            """
            package com.ex.app

            $imports

            @Preview
            @Composable
            fun ApercuAvecAide() {
                aide()
                Text("Texte")
            }

            fun aide() {}
            """.trimIndent()
        val uri = uriDe(File(racine, "Aide.kt").apply { writeText(source) })
        session.ouvrirDocument(uri, source)
        val corps = session.previews(uri).single().corps
        // `aide` existe au projet MAIS sans @Composable prouvé : PAS un
        // composable — l'appel reste AUCUN (simple appel inconnu).
        assertEquals(GenreInlineKotlin.AUCUN, (corps[0] as AppelFonctionKotlin).genreInline)
        assertEquals(GenreInlineKotlin.AUCUN, (corps[1] as AppelFonctionKotlin).genreInline)
    }

    // ── Tolérance de fin de vie du SPI ────────────────────────────────

    @Test
    fun `fermeture de session - previews vides sans jamais lever`() {
        val session = session()
        session.ouvrirDocument(
            "file:///apercu.kt",
            "$imports\n\n@Preview\n@Composable\nfun A() {\n}",
        )
        assertTrue(session.previews("file:///apercu.kt").isNotEmpty())
        session.close()
        assertEquals(0, session.previews("file:///apercu.kt").size)
    }
}
