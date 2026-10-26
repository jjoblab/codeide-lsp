package jo.lspkotlin.session

import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.api.KindEntreeKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PREUVE D'ABSENCE SUR LES TYPES DU PROJET (retour utilisateurs
 * 25/10/2026, vague 3 — familles E U1 « collect » et faux négatifs
 * U3 « CalculatriceBudget.consulterInconnu ») : un sélecteur dont le
 * récepteur est un type DU PROJET sans héritage écrit porte un jeu
 * de membres COMPLET — les DÉCLARÉS du scan + les IMPLICITES de
 * Any/Object. L'absence y est PROUVÉE (miroir kotlinc : « Unresolved
 * reference » rouge) — le moteur ne se tait plus que là où la
 * preuve manque (héritage écrit, enum/annotation implicites,
 * extensions homonymes, scan non abouti).
 *
 * Le classpath porte le VRAI jar stdlib (l'index doit CONNAÎTRE des
 * entrées pour prouver l'absence d'EXTENSION — ADR 0223), les
 * racines sources nourrissent le scan projet (Kotlin ET Java — le
 * volet Java couvre les POJO de Marc, rapport U2).
 */
class PreuveAbsenceProjetTest {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    /** Vue factice VIDE : aucun type classpath n'entre en jeu. */
    private val vueVide: VueClasspathKotlin = object : VueClasspathKotlin {
        override fun membres(fqcn: String): jo.lspkotlin.api.MembresTypeKotlin? = null

        override fun octetsClasse(fqcn: String): ByteArray? = null

        override fun nomsParPrefixe(prefixe: String, limite: Int): List<jo.lspkotlin.api.NomTypeKotlin> = emptyList()

        override fun typesDansPaquet(paquet: String): List<String> = emptyList()

        override fun estPaquet(nom: String): Boolean = false

        override fun sousPaquetsDirects(nom: String): List<String> = emptyList()
    }

    /** Session nourrie du stdlib réel (index portant) + des racines du scan. */
    private fun session(racines: List<File>): jo.lspkotlin.api.SessionLangageKotlin {
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.definirClasspath(
            ClasspathKotlin(
                entrees =
                    listOf(
                        EntreeClasspathKotlin(
                            KindEntreeKotlin.JAR,
                            File(
                                kotlin.jvm.internal.Intrinsics::class.java.protectionDomain
                                    ?.codeSource?.location?.toURI(),
                            ).path,
                            "preuve:stdlib",
                        ),
                    ),
                vue = vueVide,
                racinesSources = racines,
                dossierIndex = null,
            ),
        )
        return session
    }

    /** Les diagnostics « non résolue » du document, par nom. */
    private fun nonResolues(
        session: jo.lspkotlin.api.SessionLangageKotlin,
        uri: String,
    ): List<String> =
        session.diagnostics(uri)
            .filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }
            .map { it.message }

    // ── Kotlin : object, class, héritage, enum, extensions ────────────

    /** Sème les sources Kotlin du dossier com/ex.app (racine passée au scan). */
    private fun semerSourcesKotlin(dossier: String = "src-kotlin"): File {
        val racine = dossierTemporaire.newFolder(dossier)
        val paquet = File(racine, "com/ex/app")
        assertTrue(paquet.mkdirs())
        File(paquet, "Depot.kt").writeText(
            """
            package com.ex.app

            class Boite(var contenu: String) {
                fun ouvrir(): Int = contenu.length
            }

            object Calculatrice {
                fun somme(a: Int, b: Int): Int = a + b
            }

            class VueAvecHeritage : Boite("x") {
                fun special(): Int = 1
            }

            enum class Couleur { ROUGE, VERT }

            fun Boite.saluer(): String = "salut"

            data class Point(val x: Int, val y: Int = 0)
            """.trimIndent(),
        )
        return racine
    }

    @Test
    fun `object sans heritage - le membre inexistant est signale, les declares et implicites muets`() {
        val session = session(listOf(semerSourcesKotlin()))
        val source =
            """
            package com.ex.app

            fun demo() {
                val s = Calculatrice.somme(1, 2)
                val t = Calculatrice.toString()
                val i = Calculatrice.consulterInconnu()
            }
            """.trimIndent()
        val uri = "file:///preuve-object.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "somme (déclaré) et toString (implicite Any) muets : $recus",
            recus.none { it.contains("somme") || it.contains("toString") },
        )
        assertTrue(
            "consulterInconnu (membre inexistant d'un object sans héritage) doit être signalé : $recus",
            recus.any { it.contains("consulterInconnu") },
        )
    }

    @Test
    fun `class sans heritage - la propriete inexistante est signalee, membres et extensions muets`() {
        val session = session(listOf(semerSourcesKotlin()))
        val source =
            """
            package com.ex.app

            fun demo(b: Boite) {
                val x = b.contenu
                val y = b.ouvrir()
                val z = b.saluer()
                val t = b.toString()
                val u = b.champInconnu
            }
            """.trimIndent()
        val uri = "file:///preuve-classe.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "contenu/ouvrir/saluer/toString muets (déclarés, extension projet, implicite) : $recus",
            recus.none {
                it.contains("contenu") || it.contains("ouvrir") || it.contains("saluer") || it.contains("toString")
            },
        )
        assertTrue(
            "champInconnu (propriété inexistente d'une classe sans héritage) doit être signalé : $recus",
            recus.any { it.contains("champInconnu") },
        )
    }

    @Test
    fun `class avec heritage ecrit - aucun membre herite n est prouvable absent`() {
        val session = session(listOf(semerSourcesKotlin()))
        val source =
            """
            package com.ex.app

            fun demo(vh: VueAvecHeritage) {
                val a = vh.special()
                val b = vh.ouvrir()
            }
            """.trimIndent()
        val uri = "file:///preuve-heritage.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "special (déclaré) et ouvrir (HÉRITÉ de Boite — jamais prouvable absent, D3) muets : $recus",
            recus.none { it.contains("special") || it.contains("ouvrir") },
        )
    }

    @Test
    fun `data class - copy resolu et propose (le modificateur data enfin lu du MODIFIER_LIST)`() {
        val session = session(listOf(semerSourcesKotlin()))
        val source =
            """
            package com.ex.app

            fun demo(p: Point) {
                val q = p.copy(x = 1)
                val a = q.x
                val b = q.y
            }
            """.trimIndent()
        val uri = "file:///preuve-data.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "copy (membre synthétisé des data classes) et x/y (propriétés) muets : $recus",
            recus.none { it.contains("« copy »") || it.contains("« x »") || it.contains("« y »") },
        )
        // Complétion membre : copy désormais offert (la vague 1
        // l'avait prédit — le modificateur data était ILLISIBLE du
        // scan, la branche était du code mort).
        val session2 = session(listOf(semerSourcesKotlin("src-kotlin-bis")))
        val source2 =
            """
            package com.ex.app

            fun demo(p: Point) {
                p.
            }
            """.trimIndent()
        val uri2 = "file:///preuve-data-completion.kt"
        session2.ouvrirDocument(uri2, source2)
        val propositions = session2.completion(uri2, source2.indexOf("p.") + 2)
        session2.close()
        assertTrue(
            "copy devait être proposé après « p. » : ${propositions.map { it.libelle }}",
            propositions.any { it.libelle.startsWith("copy") },
        )
    }

    @Test
    fun `enum - les membres implicites ne sont jamais prouvables absents`() {
        val session = session(listOf(semerSourcesKotlin()))
        val source =
            """
            package com.ex.app

            fun demo() {
                val r = Couleur.ROUGE
                val i = Couleur.INEXISTANT
            }
            """.trimIndent()
        val uri = "file:///preuve-enum.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "ROUGE (entrée déclarée) et INEXISTANT (enum : Comparable implicite, jamais prouvable) muets : $recus",
            recus.none { it.contains("ROUGE") || it.contains("INEXISTANT") },
        )
    }

    // ── Java : POJO de Marc (rapport U2) ───────────────────────────────

    /** Sème les sources Java : POJO sans extends, bac AVEC extends, générique contraint. */
    private fun semerSourcesJava(): File {
        val racine = dossierTemporaire.newFolder("src-java")
        val paquet = File(racine, "com/ex/app")
        assertTrue(paquet.mkdirs())
        File(paquet, "Vinyle.java").writeText(
            """
            package com.ex.app;

            public class Vinyle {

                private String titre;

                public String getTitre() {
                    return titre;
                }
            }
            """.trimIndent(),
        )
        File(paquet, "Bac.java").writeText(
            """
            package com.ex.app;

            import java.util.ArrayList;

            public class Bac extends ArrayList<Vinyle> {

                public int taille() {
                    return size();
                }
            }
            """.trimIndent(),
        )
        File(paquet, "BoiteGenerique.java").writeText(
            """
            package com.ex.app;

            public class BoiteGenerique<T extends Comparable<T>> {

                public T valeur;
            }
            """.trimIndent(),
        )
        return racine
    }

    @Test
    fun `java sans extends - le membre inexistant est signale, getters et implicites muets`() {
        val session = session(listOf(semerSourcesJava()))
        val source =
            """
            package com.ex.app

            fun demo(v: Vinyle) {
                val t = v.getTitre()
                val p = v.titre
                val s = v.toString()
                val x = v.methodeInconnue()
            }
            """.trimIndent()
        val uri = "file:///preuve-java.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "getTitre (méthode), titre (propriété synthétique ADR 0219), toString (implicite Object) muets : $recus",
            recus.none {
                it.contains("« getTitre »") || it.contains("« titre »") || it.contains("« toString »")
            },
        )
        assertTrue(
            "methodeInconnue (membre inexistant d'un POJO sans extends) doit être signalé : $recus",
            recus.any { it.contains("methodeInconnue") },
        )
    }

    @Test
    fun `java avec extends - aucun membre herite n est prouvable absent`() {
        val session = session(listOf(semerSourcesJava()))
        val source =
            """
            package com.ex.app

            fun demo(b: Bac) {
                val t = b.taille()
                val x = b.methodeInconnue()
            }
            """.trimIndent()
        val uri = "file:///preuve-java-heritage.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "taille (déclarée) et methodeInconnue (héritage écrit ArrayList — jamais prouvable, D3) muets : $recus",
            recus.none { it.contains("taille") || it.contains("methodeInconnue") },
        )
    }

    @Test
    fun `java generique - la contrainte du parametre n est pas un heritage`() {
        val session = session(listOf(semerSourcesJava()))
        val source =
            """
            package com.ex.app

            fun demo(bg: BoiteGenerique<Int>) {
                val v = bg.valeur
                val x = bg.methodeAbsente()
            }
            """.trimIndent()
        val uri = "file:///preuve-java-generique.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "valeur (champ déclaré) muet : $recus",
            recus.none { it.contains("valeur") },
        )
        assertTrue(
            "methodeAbsente doit être signalé — la contrainte `T extends Comparable<T>`" +
                " du paramètre n'est PAS un supertype : $recus",
            recus.any { it.contains("methodeAbsente") },
        )
    }
}
