package jo.lspkotlin.session

import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.DRAPEAU_PUBLIC_KOTLIN
import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.api.KindEntreeKotlin
import jo.lspkotlin.api.MembresTypeKotlin
import jo.lspkotlin.api.NomTypeKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * REPRODUCTION (mission fiabilisation, étape B — rejeu du non-concluant
 * §4 du rapport faux positifs) : un TYPEALIAS PUBLIC d'une bibliothèque
 * Kotlin (aucun miroir bytecode — la sonde
 * [jo.lspkotlin.classpath.SondeTypealiasKotlinxTest] a établi que le
 * corpus kotlinx-datetime 0.6.1 en porte trois : `DayOfWeek`, `Month`,
 * `ZoneOffset`) n'est exposé NULLE PART par le pipeline moteur :
 *  - [DecodeurMetadonnees][jo.lspkotlin.classpath.DecodeurMetadonnees]
 *    ne lit que `kmPackage.functions`/`kmPackage.properties` — les
 *    `typeAliases` sont IGNORÉS ;
 *  - la VUE (contrat hôte, index des classes) ne porte que les
 *    `.class` réels — un alias n'y vit jamais.
 *
 * Le compilateur Kotlin RÉSOUT ces usages ; le moteur, lui, les
 * signalait par un FAUX `kt.unresolved` sur TOUT usage qui ne passe pas
 * par l'import EXACT (la voie 5 rend l'import nu muet sans sondage) :
 *  - type QUALIFIÉ complet (`kotlinx.datetime.Month`) : la garde E4
 *    (ADR 0216) rend le DERNIER segment faillible quand le FQCN ne vit
 *    pas dans la vue — l'alias n'y vit jamais ;
 *  - type NU derrière un import JOKER (`import kotlinx.datetime.*`) :
 *    la voie 5 joker sonde `typesDansPaquet` (classes seules) et la
 *    voie 6 sonde l'index des appelables (sans alias) — les deux
 *    échouent.
 *
 * CORRECTIF ÉTAPE B (§4 rejoué) : le décodeur émet les TYPEALIAS
 * PUBLICS comme entrées du genre [GenreAppelable.ALIAS][jo.lspkotlin.index.GenreAppelable]
 * (cible rendue en type de retour) ; l'index les sépare des appelables
 * (la complétion n'en est PAS polluée) et les prête à la voie 6
 * (prédicat de portée ADR 0203 inchangé) et à la garde E4 (FQCN
 * complet de l'alias connu → tous les segments muets, miroir de la
 * classe réelle).
 *
 * CONTRÔLES : les classes RÉELLES du corpus restent muettes dans les
 * DEUX formes (import exact, qualifié complet — la vue les porte), et
 * un nom INCONNU reste signalé en qualifié (D3 : le vrai négatif
 * n'est jamais éteint).
 */
class ReproductionTypealiasClasspathTest {
    /** Le jar kotlinx-datetime du classpath de test (ancre DateTimePeriod). */
    private fun jarDatetime(): File =
        File(
            kotlinx.datetime.DateTimePeriod::class.java.protectionDomain
                .codeSource
                .location
                .toURI(),
        )

    /**
     * Vue NEUTRE factice FIDÈLE au corpus : les classes top-level du
     * jar (ce que l'index hôte voit — JAMAIS les alias), membres vides
     * (l'existence prouvée suffit aux cas de figure : types et gardes).
     */
    private fun vueSurCorpus(): VueClasspathKotlin {
        val classesTopLevel = mutableSetOf<String>()
        val paquets = mutableSetOf<String>()
        // Exemption detekt ciblée (règle 16) : les `continue` sont les
        // filtres du parcours du jar (précédent AnalyseurClassFileDifferentielTest).
        @Suppress("LoopWithTooManyJumpStatements")
        ZipFile(jarDatetime()).use { zip ->
            for (entree in zip.entries()) {
                val nom = entree.name
                if (!nom.endsWith(".class") || nom == "module-info.class") continue
                if ('$' in nom.substringAfterLast('/')) continue
                val fqcn = nom.removeSuffix(".class").replace('/', '.')
                classesTopLevel += fqcn
                paquets += fqcn.substringBeforeLast('.')
            }
        }
        return object : VueClasspathKotlin {
            override fun membres(fqcn: String): MembresTypeKotlin? =
                if (fqcn in classesTopLevel) {
                    // Classe RÉELLE du corpus : vide mais LISIBLE (l'existence
                    // est la preuve, les membres ne servent pas ces cas).
                    MembresTypeKotlin(DRAPEAU_PUBLIC_KOTLIN, emptyList(), emptyList())
                } else {
                    null
                }

            override fun octetsClasse(fqcn: String): ByteArray? = null

            override fun nomsParPrefixe(prefixe: String, limite: Int): List<NomTypeKotlin> =
                classesTopLevel
                    .filter { it.substringAfterLast('.').startsWith(prefixe) }
                    .take(limite)
                    .map { NomTypeKotlin(it.substringAfterLast('.'), listOf(it)) }

            override fun typesDansPaquet(paquet: String): List<String> =
                classesTopLevel.filter { it.substringBeforeLast('.') == paquet }

            override fun estPaquet(nom: String): Boolean = nom in paquets

            override fun sousPaquetsDirects(nom: String): List<String> = emptyList()
        }
    }

    private fun session(): SessionLangageKotlin {
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.definirClasspath(
            ClasspathKotlin(
                entrees =
                    listOf(
                        EntreeClasspathKotlin(
                            KindEntreeKotlin.JAR,
                            jarDatetime().path,
                            "test:kotlinx-datetime-jvm-0.6.1",
                        ),
                    ),
                vue = vueSurCorpus(),
                racinesSources = emptyList(),
                dossierIndex = null,
            ),
        )
        return session
    }

    private fun nonResolues(
        session: SessionLangageKotlin,
        uri: String,
    ): List<String> =
        session.diagnostics(uri)
            .filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }
            .map { it.message }

    /** Import EXACT d'un alias + type nu : la voie 5 (import écrit) résout. */
    @Test
    fun `typealias importe explicitement - type nu muet`() {
        val session = session()
        val source =
            """
            import kotlinx.datetime.Month
            import kotlinx.datetime.ZoneOffset

            fun demo(mois: Month?): ZoneOffset? = null
            """.trimIndent()
        val uri = "file:///typealias-import-exact.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "l'import exact d'un typealias résout le type nu (Month, ZoneOffset) : $recus",
            recus.none { it.contains("« Month »") || it.contains("« ZoneOffset »") },
        )
    }

    /** Type QUALIFIÉ complet d'un alias : la garde E4 doit reconnaître le FQCN. */
    @Test
    fun `typealias qualifie complet - dernier segment muet`() {
        val session = session()
        val source =
            """
            fun demo(): kotlinx.datetime.Month? = null

            fun autre(): kotlinx.datetime.ZoneOffset? = null
            """.trimIndent()
        val uri = "file:///typealias-qualifie.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "le type qualifié complet d'un typealias est résolu (kotlinc le résout — Month, ZoneOffset) : $recus",
            recus.none { it.contains("« Month »") || it.contains("« ZoneOffset »") },
        )
    }

    /**
     * Import JOKER + type nu : la voie 6 doit prêter l'alias en portée.
     * `Month`/`DayOfWeek` sont sauvés PAR HASARD par les FONCTIONS
     * USINE expect/actual (`fun Month(int)`) — `ZoneOffset` est l'alias
     * PUR (aucune usine dans le jar) : c'est lui qui prouve la voie.
     */
    @Test
    fun `typealias derriere import joker - type nu muet`() {
        val session = session()
        val source =
            """
            import kotlinx.datetime.*

            fun demo(jour: DayOfWeek?, mois: Month?): ZoneOffset? = null
            """.trimIndent()
        val uri = "file:///typealias-joker.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "le typealias derrière un import joker est en portée (DayOfWeek, Month, ZoneOffset) : $recus",
            recus.none {
                it.contains("« DayOfWeek »") || it.contains("« Month »") || it.contains("« ZoneOffset »")
            },
        )
    }

    /** CONTRÔLE — classe RÉELLE du corpus : les deux formes restent muettes. */
    @Test
    fun `controles - classe reelle du corpus muette en import et en qualifie`() {
        val session = session()
        val sourceImport =
            """
            import kotlinx.datetime.DateTimePeriod

            fun a(p: DateTimePeriod?) = null
            """.trimIndent()
        val sourceQualifie =
            """
            fun b(): kotlinx.datetime.DateTimePeriod? = null
            """.trimIndent()
        val uriA = "file:///typealias-controle-import.kt"
        val uriB = "file:///typealias-controle-qualifie.kt"
        session.ouvrirDocument(uriA, sourceImport)
        session.ouvrirDocument(uriB, sourceQualifie)
        val recusA = nonResolues(session, uriA)
        val recusB = nonResolues(session, uriB)
        session.close()
        assertTrue("classe réelle importée muette (DateTimePeriod) : $recusA", recusA.isEmpty())
        assertTrue("classe réelle qualifiée muette (DateTimePeriod) : $recusB", recusB.isEmpty())
    }

    /** CONTRÔLE D3 — un nom INCONNU reste signalé en qualifié (vrai négatif). */
    @Test
    fun `controle d3 - nom inconnu en qualifie reste signale`() {
        val session = session()
        val source =
            """
            fun demo(): kotlinx.datetime.Inexistant? = null
            """.trimIndent()
        val uri = "file:///typealias-vrai-negatif.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "le dernier segment d'un FQCN inconnu reste signalé (D3) : $recus",
            recus.any { it.contains("« Inexistant »") },
        )
    }
}
