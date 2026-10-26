package jo.lspkotlin.session

import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.DRAPEAU_PUBLIC_KOTLIN
import jo.lspkotlin.api.DRAPEAU_STATIQUE_KOTLIN
import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.api.KindEntreeKotlin
import jo.lspkotlin.api.MembreJvmKotlin
import jo.lspkotlin.api.MembresTypeKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.SessionLangageKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * REPRODUCTION H5 (mission fiabilisation, étape A.2 — hypothèses
 * « entrées d'enum (`entries`, `values()`), membres statiques de
 * bibliothèques » du prompt) : le moteur résout les sélecteurs d'un
 * récepteur CLASSPATH via la vue JVM (champs + méthodes du bytecode,
 * [VueClasspathKotlin.membres]).
 *
 * La vue factice modélise le contenu RÉEL d'un `.class` d'enum
 * Kotlin 1.9+ (miroir de `kotlin.io.FileWalkDirection` du stdlib) :
 * les constantes sont des CHAMPS statiques (`ROUGE`, `VERT`), la
 * génération `EnumEntries` produit un GETTER STATIQUE `getEntries()`,
 * et `values()`/`valueOf(String)` sont des méthodes statiques
 * historiques.
 *
 * Mesures :
 *  - `Couleur.ROUGE` (champ statique) : MUET attendu ;
 *  - `Couleur.values()` / `Couleur.valueOf(…)` (méthodes statiques
 *    nommées exactement) : MUETTES attendues ;
 *  - `Couleur.entries` (propriété synthétique portée par le getter
 *    STATIQUE `getEntries()`) : CORRIGÉ en étape B (rapport §7.3) —
 *    [ResolutionKotlin.getterSynthetique] accepte le getter STATIQUE
 *    quand le porteur est une CLASSE D'ENUM (drapeau ACC_ENUM, miroir
 *    ADR 0222) : `MonEnum.entries` (Kotlin 1.9+) RÉSOUT, plus de faux
 *    `kt.unresolved`.
 *
 * Le vrai négatif de contrôle (`Couleur.absenteDuBytecode`) reste
 * signalé — preuve que le harnais ne valide pas un « tout muet ».
 */
class ReproductionMembresClasspathTest {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    /**
     * Vue factice du `.class` d'un enum Kotlin 1.9+ : champs statiques
     * des constantes, getter STATIQUE `getEntries()` (EnumEntries),
     * `values()`/`valueOf` statiques, `toString` d'instance déclaré.
     */
    private val statiquePublic: Int = DRAPEAU_PUBLIC_KOTLIN or DRAPEAU_STATIQUE_KOTLIN

    /** Drapeaux d'une constante d'enum (champ statique public final). */
    private val constanteEnum: Int = statiquePublic or 0x0010

    private val vueEnum: VueClasspathKotlin =
        object : VueClasspathKotlin {
            override fun membres(fqcn: String): MembresTypeKotlin? =
                when (fqcn) {
                    "exo.lib.Couleur" ->
                        MembresTypeKotlin(
                            drapeauxClasse = 0x4011, // ACC_PUBLIC | ACC_FINAL | ACC_ENUM
                            champs =
                                listOf(
                                    MembreJvmKotlin(constanteEnum, "ROUGE", "Lexo/lib/Couleur;"),
                                    MembreJvmKotlin(constanteEnum, "VERT", "Lexo/lib/Couleur;"),
                                ),
                            methodes =
                                listOf(
                                    // Génération Kotlin 1.9+ (EnumEntries) : getter STATIQUE.
                                    MembreJvmKotlin(statiquePublic, "getEntries", "()Lkotlin/enums/EnumEntries;"),
                                    // Génération historique : méthodes statiques exactes.
                                    MembreJvmKotlin(statiquePublic, "values", "()[Lexo/lib/Couleur;"),
                                    MembreJvmKotlin(statiquePublic, "valueOf", "(Ljava/lang/String;)Lexo/lib/Couleur;"),
                                    // Membre d'instance (déclaré dans le .class).
                                    MembreJvmKotlin(DRAPEAU_PUBLIC_KOTLIN, "toString", "()Ljava/lang/String;"),
                                ),
                        )
                    else -> null
                }

            override fun octetsClasse(fqcn: String): ByteArray? = null

            override fun nomsParPrefixe(prefixe: String, limite: Int): List<jo.lspkotlin.api.NomTypeKotlin> =
                if (prefixe == "Couleur") {
                    listOf(jo.lspkotlin.api.NomTypeKotlin("Couleur", listOf("exo.lib.Couleur")))
                } else {
                    emptyList()
                }

            override fun typesDansPaquet(paquet: String): List<String> =
                if (paquet == "exo.lib") listOf("exo.lib.Couleur") else emptyList()

            override fun estPaquet(nom: String): Boolean = nom == "exo.lib"

            override fun sousPaquetsDirects(nom: String): List<String> = emptyList()
        }

    private fun session(): SessionLangageKotlin {
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
                            "membres:stdlib",
                        ),
                    ),
                vue = vueEnum,
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

    @Test
    fun `enum classpath - champ statique, values et valueOf sont muets`() {
        val session = session()
        val source =
            """
            import exo.lib.Couleur

            fun demo() {
                val r = Couleur.ROUGE
                val t = Couleur.values()
                val u = Couleur.valueOf("ROUGE")
            }
            """.trimIndent()
        val uri = "file:///membres-enum-statiques.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "ROUGE (champ statique), values() et valueOf() (méthodes statiques exactes) : muets : $recus",
            recus.none {
                it.contains("« ROUGE »") || it.contains("« values »") || it.contains("« valueOf »")
            },
        )
        assertTrue(
            "« Couleur » (le type importé) muet : $recus",
            recus.none { it.contains("« Couleur »") },
        )
    }

    @Test
    fun `enum classpath - entries (getter STATIQUE) est muet (miroir ADR 0222)`() {
        val session = session()
        val source =
            """
            import exo.lib.Couleur

            fun demo() {
                val e = Couleur.entries
                val premiere = e.first()
            }
            """.trimIndent()
        val uri = "file:///membres-enum-entries.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        // ÉTAPE B (H5 CORRIGÉE) : `entries` vit dans le bytecode comme
        // getter STATIQUE getEntries() — [ResolutionKotlin.getterSynthetique]
        // accepte le getter statique quand le porteur est une CLASSE
        // D'ENUM (miroir ADR 0222) : `Couleur.entries` RÉSOUT, plus de
        // faux `kt.unresolved` (Kotlin 1.9+).
        assertTrue(
            "« entries » (getter statique d'enum accepté) doit être MUET : $recus",
            recus.none { it.contains("« entries »") },
        )
    }

    @Test
    fun `controle - un membre reellement absent reste signale`() {
        val session = session()
        val source =
            """
            import exo.lib.Couleur

            fun demo(): Int = Couleur.ROUGE.absenteDuBytecode
            """.trimIndent()
        val uri = "file:///membres-controle.kt"
        session.ouvrirDocument(uri, source)
        val recus = nonResolues(session, uri)
        session.close()
        assertTrue(
            "vrai négatif de contrôle : « absenteDuBytecode » doit rester signalé : $recus",
            recus.any { it.contains("« absenteDuBytecode »") },
        )
    }
}
