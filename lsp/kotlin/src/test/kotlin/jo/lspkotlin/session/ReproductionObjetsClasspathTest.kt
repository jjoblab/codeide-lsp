package jo.lspkotlin.session

import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.EntreeClasspathKotlin
import jo.lspkotlin.api.KindEntreeKotlin
import jo.lspkotlin.api.MembreJvmKotlin
import jo.lspkotlin.api.MembresTypeKotlin
import jo.lspkotlin.api.NomTypeKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * REPRODUCTION des OBJETS DU CLASSPATH (retour utilisateurs 25/10/2026,
 * familles A du programme U1-U4) : `MaterialTheme.typography`,
 * `Arrangement.spacedBy`, `Icons.Filled` — un objet Kotlin compilé est
 * une classe au champ STATIQUE `INSTANCE` dont les membres sont des
 * membres d'INSTANCE. Le moteur n'offrait que « INSTANCE » en
 * complétion après le nom de l'objet (Léa : `MaterialTheme.` → UNE
 * proposition) et signalait chaque sélecteur (`typography`,
 * `colorScheme`, `spacedBy`) comme référence non résolue — la pluie de
 * rougeurs n° 1 des écrans Compose.
 *
 * Miroir du monde réel : le champ statique `INSTANCE` (descripteur
 * `Landroidx/compose/material3/MaterialTheme;`, drapeaux public+final+
 * static), le getter d'instance `getTypography` public non statique —
 * la vue factice calque le bytecode que livre le corpus normalisé v2.
 */
class ReproductionObjetsClasspathTest {
    /** Session nourrie du stdlib réel + vue factice miroir d'un objet. */
    private fun session(): jo.lspkotlin.api.SessionLangageKotlin {
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
                            "repro:stdlib",
                        ),
                    ),
                vue = vueObjet(),
                racinesSources = emptyList(),
                dossierIndex = null,
            ),
        )
        return session
    }

    /**
     * Vue factice miroir du bytecode d'un objet Kotlin compilé :
     * `exo.exemple.MaterialTheme` (champ statique INSTANCE + getters
     * d'instance) et `exo.exemple.Arrangement` (fonction membre
     * d'instance `spacedBy` — appelée par le NOM de l'objet, miroir
     * `Arrangement.spacedBy(8.dp)`), plus les types de retour.
     */
    private fun vueObjet(): VueClasspathKotlin = object : VueClasspathKotlin {
        private val membres: Map<String, MembresTypeKotlin> =
            mapOf(
                "exo.exemple.MaterialTheme" to
                    MembresTypeKotlin(
                        0x0031,
                        listOf(MembreJvmKotlin(0x0019, "INSTANCE", "Lexo/exemple/MaterialTheme;")),
                        listOf(
                            MembreJvmKotlin(0x0001, "getTypography", "()Lexo/exemple/Typography;"),
                            MembreJvmKotlin(0x0001, "getColorScheme", "()Lexo/exemple/ColorScheme;"),
                        ),
                    ),
                "exo.exemple.Arrangement" to
                    MembresTypeKotlin(
                        0x0031,
                        listOf(MembreJvmKotlin(0x0019, "INSTANCE", "Lexo/exemple/Arrangement;")),
                        listOf(
                            MembreJvmKotlin(
                                0x0001,
                                "spacedBy",
                                "(I)Lexo/exemple/Arrangement\$Espacement;",
                            ),
                        ),
                    ),
                "exo.exemple.Typography" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
                "exo.exemple.ColorScheme" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
                "exo.exemple.Arrangement\$Espacement" to
                    MembresTypeKotlin(0x0021, emptyList(), emptyList()),
            )

        override fun membres(fqcn: String): MembresTypeKotlin? = membres[fqcn]

        override fun octetsClasse(fqcn: String): ByteArray? = null

        override fun nomsParPrefixe(prefixe: String, limite: Int): List<NomTypeKotlin> =
            if (prefixe.startsWith("Material") || prefixe.startsWith("Arrangement")) {
                listOf(NomTypeKotlin(prefixe, listOf("exo.exemple.$prefixe")))
            } else {
                emptyList()
            }

        override fun typesDansPaquet(paquet: String): List<String> =
            if (paquet == "exo.exemple") {
                listOf("exo.exemple.MaterialTheme", "exo.exemple.Arrangement")
            } else {
                emptyList()
            }

        override fun estPaquet(nom: String): Boolean = nom == "exo.exemple"

        override fun sousPaquetsDirects(nom: String): List<String> = emptyList()
    }

    /** Les diagnostics du fichier [source] après ouverture. */
    private fun diagnostics(source: String): List<jo.lspkotlin.api.DiagnosticKotlin> {
        val session = session()
        val fichier = java.nio.file.Files.createTempDirectory("repro-objets").resolve("Ecran.kt").toFile()
        fichier.writeText(source)
        session.ouvrirDocument(fichier.toURI().toString(), source)
        val recus = session.diagnostics(fichier.toURI().toString())
        session.close()
        return recus
    }

    /** LE cas Léa : `MaterialTheme.typography` — zéro faux positif. */
    @Test
    fun `objet classpath - propriete membre, zero faux positif`() {
        val recus =
            diagnostics(
                """
                import exo.exemple.MaterialTheme
                import exo.exemple.Typography

                fun police(): Typography = MaterialTheme.typography
                """.trimIndent(),
            )
        assertEquals(
            "Le sélecteur d'un objet du classpath ne doit PAS être non résolu",
            emptyList<String>(),
            recus.filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }.map { it.message },
        )
    }

    /** Le cas Léa : `MaterialTheme.colorScheme` (deuxième getter). */
    @Test
    fun `objet classpath - second membre, zero faux positif`() {
        val recus =
            diagnostics(
                """
                import exo.exemple.ColorScheme
                import exo.exemple.MaterialTheme

                fun couleurs(): ColorScheme = MaterialTheme.colorScheme
                """.trimIndent(),
            )
        assertTrue(
            "colorScheme doit résoudre via le getter d'instance de l'objet",
            recus.none { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE },
        )
    }

    /** Le cas spacedBy : FONCTION membre d'instance APPELÉE par le nom de l'objet. */
    @Test
    fun `objet classpath - fonction membre appelee, zero faux positif`() {
        val recus =
            diagnostics(
                """
                import exo.exemple.Arrangement
                import exo.exemple.Arrangement.Espacement

                fun espacement(): Espacement = Arrangement.spacedBy(8)
                """.trimIndent(),
            )
        assertTrue(
            "spacedBy doit résoudre comme méthode d'instance de l'objet",
            recus.none { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE },
        )
    }

    /** La garde D3 : un sélecteur VRAIMENT absent reste signalé. */
    @Test
    fun `objet classpath - membre inexistant, toujours signale`() {
        val recus =
            diagnostics(
                """
                import exo.exemple.MaterialTheme
                import exo.exemple.Typography

                fun police(): Typography = MaterialTheme.dimensions
                """.trimIndent(),
            )
        assertTrue(
            "dimensions n'existe pas — l'absence doit rester prouvable (D3)",
            recus.any { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE },
        )
    }

    /** Le survol du membre d'objet porte le type du getter. */
    @Test
    fun `objet classpath - survol du membre`() {
        val session = session()
        val source =
            """
            import exo.exemple.MaterialTheme
            import exo.exemple.Typography

            fun police(): Typography = MaterialTheme.typography
            """.trimIndent()
        val fichier = java.nio.file.Files.createTempDirectory("repro-objets").resolve("Ecran.kt").toFile()
        fichier.writeText(source)
        session.ouvrirDocument(fichier.toURI().toString(), source)
        val survol = session.survol(fichier.toURI().toString(), source.indexOf("typography"))
        session.close()
        assertNotNull("le survol de typography doit exister", survol)
    }

    /** COMPAGNON D'EXPÉRIENCE (E2E U1) : session avec le VRAI jar
     *  kotlinx-coroutines-core-jvm du corpus — la sonde empirique du
     *  « collect » non résolu (famille E : suspend du classpath). */
    private fun sessionCoroutines(avecRacines: List<File> = emptyList()): jo.lspkotlin.api.SessionLangageKotlin {
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        val jars =
            listOf(
                kotlin.jvm.internal.Intrinsics::class.java.protectionDomain
                    ?.codeSource?.location?.toURI()?.path,
                System.getenv("U1_CORPUS")?.let { dossier ->
                    File(dossier, "kotlinx-coroutines-core-jvm-1.9.0.jar").absolutePath
                },
            ).mapNotNull { chemin ->
                chemin?.let { File(it) }?.takeIf { it.isFile }?.let {
                    EntreeClasspathKotlin(KindEntreeKotlin.JAR, it.absolutePath, "repro:$it")
                }
            }
        val jarCoroutines =
            jars.firstOrNull { it.chemin.contains("coroutines-core") }?.chemin
        session.definirClasspath(
            ClasspathKotlin(
                entrees = jars,
                vue = vueChainee(jarCoroutines),
                racinesSources = avecRacines,
                dossierIndex = null,
            ),
        )
        return session
    }

    /**
     * Vue à OCTETS RÉELS : les classes du jar coroutines sont lues
     * telles quelles (chaineSupertypes traverse StateFlow -> Flow —
     * les octets authentiques font foi), les MEMBRES restent muets
     * (vides mais NON nuls : la classe est « lisible ») — les
     * EXTENSIONS de l'index portent la résolution, comme en E2E.
     */
    private fun vueChainee(jar: String?): VueClasspathKotlin = object : VueClasspathKotlin {
        private val fichier: java.util.jar.JarFile? = jar?.let { chemin -> java.util.jar.JarFile(chemin) }

        override fun membres(fqcn: String): MembresTypeKotlin? =
            if (octetsClasse(fqcn) != null) MembresTypeKotlin(0x0021, emptyList(), emptyList()) else null

        override fun octetsClasse(fqcn: String): ByteArray? {
            val f = fichier
            val entree = f?.getEntry(fqcn.replace('.', '/') + ".class")
            return entree?.let { e -> f!!.getInputStream(e).use { it.readBytes() } }
        }

        override fun nomsParPrefixe(prefixe: String, limite: Int): List<NomTypeKotlin> = emptyList()

        override fun typesDansPaquet(paquet: String): List<String> = emptyList()

        override fun estPaquet(nom: String): Boolean = false

        override fun sousPaquetsDirects(nom: String): List<String> = emptyList()
    }

    /** U1-Léa : `etat.collect { }` sur StateFlow — suspend du classpath.
     *  REPRODUCTION FERMÉE (25/10/2026, vague 1 d89544d) : la garde
     *  erasure Object (E1) et les extensions de la chaîne
     *  StateFlow→Flow suffisent désormais — le jar coroutines réel
     *  (corpus U1) prouve ZÉRO faux positif. Le test reste actif :
     *  toute régression de l'index des appelables (DecodeurMetadonnees)
     *  ou de la sélection par types le fait échouer. Sans U1_CORPUS,
     *  le jar coroutines est absent : l'index ne connaît AUCUNE
     *  extension collect, l'étape 3bis (fqcn$Nom) et la garde Object
     *  gardent le silence D3 — le test reste vert par sous-rapport. */
    @Test
    fun `coroutines reel - collect suspend extension, zero faux positif`() {
        val session = sessionCoroutines()
        val source =
            """
            import kotlinx.coroutines.flow.MutableStateFlow
            import kotlinx.coroutines.flow.StateFlow

            class Depot {
                val _etat = MutableStateFlow(0)
                val etat: StateFlow<Int> = _etat

                fun ecoute(bloc: (Int) -> Unit) {
                    etat.collect { valeur -> bloc(valeur) }
                }
            }
            """.trimIndent()
        val fichier = java.nio.file.Files.createTempDirectory("repro-collect").resolve("Depot.kt").toFile()
        fichier.writeText(source)
        session.ouvrirDocument(fichier.toURI().toString(), source)
        val recus = session.diagnostics(fichier.toURI().toString())
        session.close()
        assertTrue(
            "collect (extension suspend du classpath réel) ne doit pas être non résolu — obtenu : " +
                recus.filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }.joinToString { it.message },
            recus.none { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE },
        )
    }

    /**
     * U1-Léa, pattern EXACT de MainActivity : le récepteur est un
     * MEMBRE typé (`vueModele.effets.collect { }` — SharedFlow écrit
     * sur une propriété d'AUTRE classe), PAS une propriété locale du
     * document courant. REPRODUCTION FERMÉE (25/10/2026, vague 2) : le
     * callee d'un appel sélecteur traverse désormais estSelecteurQualifie
     * — le type INCONNU du récepteur (type écrit résolu dans le contexte
     * de l'APPELANT, sans l'import du fichier déclarant) redevient MUET
     * (D3) au lieu de produire un faux « non résolu » par la voie
     * normale.
     */
    @Test
    fun `coroutines reel - collect sur membre distancie SharedFlow, zero faux positif`() {
        val session = sessionCoroutines()
        val source =
            """
            import kotlinx.coroutines.flow.MutableSharedFlow
            import kotlinx.coroutines.flow.SharedFlow
            import kotlinx.coroutines.flow.asSharedFlow

            sealed interface Effet {
                data class Vide(val n: Int = 0) : Effet
            }

            class VueModele {
                private val _effets = MutableSharedFlow<Effet>(extraBufferCapacity = 8)
                val effets: SharedFlow<Effet> = _effets.asSharedFlow()
            }

            fun ecoute(vueModele: VueModele, bloc: (Effet) -> Unit) {
                vueModele.effets.collect { effet -> bloc(effet) }
            }
            """.trimIndent()
        val fichier = java.nio.file.Files.createTempDirectory("repro-membre").resolve("Ecoute.kt").toFile()
        fichier.writeText(source)
        session.ouvrirDocument(fichier.toURI().toString(), source)
        val recus = session.diagnostics(fichier.toURI().toString())
        session.close()
        assertTrue(
            "collect sur membre distancié (SharedFlow écrit) ne doit pas être non résolu — obtenu : " +
                recus.filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }.joinToString { it.message },
            recus.none { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE },
        )
    }

    /**
     * U1-Léa, pattern EXACT de MainActivity DEUX FICHIERS : la
     * DÉCLARATION vit dans VueModele.kt (QUI IMPORTE SharedFlow),
     * l'USAGE dans Ecouteur.kt (QUI NE L'IMPORTE PAS — le fichier de
     * Léa n'importe que le ViewModel). Le type ÉCRIT du membre est
     * résolu par le contexte du document APPELANT : sans import, le
     * typage retombe au nom SIMPLE et l'extension collect (récepteur
     * FQCN) échappe. REPRODUCTION FERMÉE (25/10/2026, vague 2) : le
     * callee sélecteur est reconnu (traversée du CALL), le sélecteur
     * INCONNU redevient MUET (D3). Le chemin POSITIF (résoudre par le
     * contexte du fichier PORTeur) reste au backlog génériques.
     */
    @Test
    fun `coroutines reel - collect sur membre dont l import vit ailleurs, zero faux positif`() {
        val dossier = java.nio.file.Files.createTempDirectory("repro-membre-2f").toFile()
        val paquet = File(dossier, "com/ex/app")
        assertTrue(paquet.mkdirs())
        File(paquet, "VueModele.kt").writeText(
            """
            package com.ex.app

            import kotlinx.coroutines.flow.MutableSharedFlow
            import kotlinx.coroutines.flow.SharedFlow

            class VueModele {
                private val _effets = MutableSharedFlow<Int>(extraBufferCapacity = 8)
                val effets: SharedFlow<Int> = _effets.asSharedFlow()
            }
            """.trimIndent(),
        )
        val usage =
            """
            package com.ex.app

            fun ecoute(vueModele: VueModele, bloc: (Int) -> Unit) {
                vueModele.effets.collect { effet -> bloc(effet) }
            }
            """.trimIndent()
        val fichier = File(paquet, "Ecouteur.kt")
        fichier.writeText(usage)
        val session = sessionCoroutines(avecRacines = listOf(dossier))
        session.ouvrirDocument(fichier.toURI().toString(), usage)
        val recus = session.diagnostics(fichier.toURI().toString())
        session.close()
        assertTrue(
            "collect (membre typé ailleurs que l'appelant) ne doit pas être non résolu — obtenu : " +
                recus.filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }.joinToString { it.message },
            recus.none { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE },
        )
    }

    /**
     * U3-Nina : `CalculatriceBudget.consulterInconnu(budget)` — le membre
     * inexistant d'un OBJECT :shared SANS héritage, appelé depuis :app qui
     * l'IMPORTE. La preuve d'absence projet (vague 3) doit le SIGNALER :
     * l'object est scanné (racines = :shared + :app), sans supertype
     * écrit, son jeu de membres est exactement les DÉCLARÉS.
     * REPRODUCTION du faux négatif constaté en E2E U3 le 26/10/2026.
     */
    @Test
    fun `objectimporte - le membre inexistant d un object du projet est signale`() {
        val dossier = java.nio.file.Files.createTempDirectory("repro-u3-object").toFile()
        val partage = File(dossier, "budget/partage/logique")
        assertTrue(partage.mkdirs())
        File(partage, "CalculatriceBudget.kt").writeText(
            """
            package budget.partage.logique

            object CalculatriceBudget {
                fun solde(v: Double): Double = v
            }
            """.trimIndent(),
        )
        val source =
            """
            package budget.partage.app.vm

            import budget.partage.logique.CalculatriceBudget

            fun essai(v: Double): Double = CalculatriceBudget.consulterInconnu(v)
            """.trimIndent()
        val fichier = File(dossier, "budget/partage/app/vm/Essai.kt")
        fichier.parentFile.mkdirs()
        fichier.writeText(source)
        val session = sessionCoroutines(avecRacines = listOf(dossier))
        session.ouvrirDocument(fichier.toURI().toString(), source)
        val recus = session.diagnostics(fichier.toURI().toString())
        session.close()
        assertTrue(
            "consulterInconnu (membre inexistant d'un object importé sans héritage) doit être signalé — obtenu : " +
                recus.filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }.joinToString { it.message },
            recus.any {
                it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE && it.message.contains("consulterInconnu")
            },
        )
    }
}
