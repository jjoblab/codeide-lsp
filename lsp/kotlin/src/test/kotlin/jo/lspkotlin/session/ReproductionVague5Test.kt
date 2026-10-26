package jo.lspkotlin.session

import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.MembreJvmKotlin
import jo.lspkotlin.api.MembresTypeKotlin
import jo.lspkotlin.api.NomTypeKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * REPRODUCTION des familles de la VAGUE 5 (retour utilisateurs
 * 25/10/2026 — backlog RETOURS §4, items 1 et 2) :
 *
 *  1. RÉCEPTEURS DE PORTÉE des lambdas de scope — le paramètre
 *     `content: RowScope.() -> Unit` de Compose `Row { … }` prête
 *     RowScope comme this IMPLICITE (U1 « weight »/« align ») :
 *     membre NU et MEMBRE-EXTENSION (`Modifier.weight(1f)` — weight
 *     vit DANS RowScope, le récepteur d'extension est le premier
 *     paramètre JVM), PILE des littéraux emboîtés (ColumnScope puis
 *     RowScope), candidates PROJET (type écrit `A.() -> B`, texte
 *     exact) et CLASSPATH (`buildList { add(…) }` — Function1<
 *     MutableList<E>, Unit> rendu par l'index) ;
 *  2. DESTRUCTURING de paramètre de lambda — `sortedByDescending
 *     { (_, total) -> total }` : chaque composant nommé est en
 *     portée (U3 « total ») ;
 *  3. PROPRIÉTÉS BUILTINS de Map — `totaux.entries`/`.keys`/
 *     `.values` (U3 « entries ») : kotlin.collections.Map n'a aucun
 *     .class, l'actual JVM expose entrySet()/keySet()/values().
 *
 * Chaque famille porte SON vrai positif : le silence ne doit jamais
 * devenir de la complaisance (un membre inconnu DANS le scope reste
 * signalé, miroir kotlinc).
 *
 * Miroir du bytecode réel : la vue factice calque les descripteurs
 * que livre le corpus normalisé v2 (supertypes lus par octets bruts,
 * membres par vue, java.util fourni — le stdlib réel ne porte PAS
 * les classes de la plateforme).
 */
class ReproductionVague5Test {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    /**
     * Vue factice miroir du bytecode des familles :
     *  - `exo.portee.Maison` : récepteur d'extension des
     *    member-extensions du scope ;
     *  - `exo.portee.PorteeMaison` : le scope PROJET (orner/compter) ;
     *  - `exo.portee.PorteeA`/`PorteeB` : la PILE des scopes ;
     *  - `exo.cp.PorteeEtrangere` : le scope CLASSPATH (fignoler) —
     *    ses membres ne sont PAS des déclarations projet : la voie 4
     *    du modèle plat ne peut pas les résoudre (le non-fuite du
     *    récepteur de portée y est donc OBSERVABLE) ;
     *  - `java.util.List` : add (le récepteur de buildList aliasé) ;
     *  - `java.util.Map` : entrySet/keySet/values (les builtins) ;
     *  - `java.lang.Object` : racine.
     */
    private fun vue(): VueClasspathKotlin = object : VueClasspathKotlin {
        private val membres: Map<String, MembresTypeKotlin> =
            mapOf(
                "exo.portee.Maison" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
                "exo.portee.PorteeMaison" to
                    MembresTypeKotlin(
                        0x0201,
                        emptyList(),
                        listOf(
                            MembreJvmKotlin(0x0001, "orner", "(Lexo/portee/Maison;)Lexo/portee/Maison;"),
                            MembreJvmKotlin(0x0001, "compter", "()I"),
                        ),
                    ),
                "exo.cp.PorteeEtrangere" to
                    MembresTypeKotlin(
                        0x0201,
                        emptyList(),
                        listOf(
                            MembreJvmKotlin(0x0001, "fignoler", "(Lexo/portee/Maison;)Lexo/portee/Maison;"),
                            MembreJvmKotlin(0x0001, "bricoler", "()I"),
                        ),
                    ),
                "exo.portee.PorteeA" to
                    MembresTypeKotlin(0x0201, emptyList(), listOf(MembreJvmKotlin(0x0001, "dansA", "()I"))),
                "exo.portee.PorteeB" to
                    MembresTypeKotlin(0x0201, emptyList(), listOf(MembreJvmKotlin(0x0001, "dansB", "()I"))),
                "java.util.List" to
                    MembresTypeKotlin(
                        0x0401,
                        emptyList(),
                        listOf(MembreJvmKotlin(0x0001, "add", "(Ljava/lang/Object;)Z")),
                    ),
                "java.util.Map" to
                    MembresTypeKotlin(
                        0x0401,
                        emptyList(),
                        listOf(
                            MembreJvmKotlin(0x0001, "entrySet", "()Ljava/util/Set;"),
                            MembreJvmKotlin(0x0001, "keySet", "()Ljava/util/Set;"),
                            MembreJvmKotlin(0x0001, "values", "()Ljava/util/Collection;"),
                        ),
                    ),
                "java.lang.Object" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
            )
        private val supers: Map<String, String?> =
            mapOf(
                "exo.portee.Maison" to "java.lang.Object",
                "exo.portee.PorteeMaison" to "java.lang.Object",
                "exo.cp.PorteeEtrangere" to "java.lang.Object",
                "exo.portee.PorteeA" to "java.lang.Object",
                "exo.portee.PorteeB" to "java.lang.Object",
                "java.util.List" to "java.util.Collection",
                "java.util.Collection" to "java.lang.Object",
                "java.util.Map" to "java.lang.Object",
                "java.lang.Object" to null,
            )

        override fun membres(fqcn: String): MembresTypeKotlin? = membres[fqcn]

        override fun octetsClasse(fqcn: String): ByteArray? = supers[fqcn]?.let { octets(fqcn, it) }

        override fun nomsParPrefixe(prefixe: String, limite: Int): List<NomTypeKotlin> =
            listOf("Maison", "PorteeMaison", "PorteeEtrangere", "PorteeA", "PorteeB")
                .filter { it.startsWith(prefixe) }
                .map { NomTypeKotlin(it, listOfNotNull(membres.keys.firstOrNull { cle -> cle.endsWith(".$it") })) }

        override fun typesDansPaquet(paquet: String): List<String> =
            membres.keys.filter { it.substringBeforeLast('.') == paquet }

        override fun estPaquet(nom: String): Boolean = nom.startsWith("exo.") || nom == "java.util"

        override fun sousPaquetsDirects(nom: String): List<String> = emptyList()
    }

    /** Octets de classe minimaux (lecture brute des supertypes). */
    private fun octets(fqcn: String, superFqcn: String?): ByteArray {
        val sortie = ByteArrayOutputStream()
        DataOutputStream(sortie).use { flux ->
            flux.writeInt(0xCAFEBABE.toInt())
            flux.writeShort(0)
            flux.writeShort(52)
            val paires = 1 + (if (superFqcn != null) 1 else 0)
            flux.writeShort(paires * 2 + 1)
            fun utf8(texte: String) {
                flux.writeByte(1)
                val o = texte.toByteArray(Charsets.UTF_8)
                flux.writeShort(o.size)
                flux.write(o)
            }
            fun classe(idx: Int) {
                flux.writeByte(7)
                flux.writeShort(idx)
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
            flux.writeShort(0x0021)
            flux.writeShort(thisUtf8 + 1)
            flux.writeShort(superClasse)
            flux.writeShort(0)
            flux.writeShort(0)
            flux.writeShort(0)
            flux.writeShort(0)
        }
        return sortie.toByteArray()
    }

    /** Session nourrie du stdlib réel + de la vue factice + des racines. */
    private fun session(racines: List<File>): jo.lspkotlin.api.SessionLangageKotlin {
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.definirClasspath(
            ClasspathKotlin(
                entrees =
                    listOf(
                        jo.lspkotlin.api.EntreeClasspathKotlin(
                            jo.lspkotlin.api.KindEntreeKotlin.JAR,
                            File(
                                kotlin.jvm.internal.Intrinsics::class.java.protectionDomain
                                    ?.codeSource?.location?.toURI(),
                            ).path,
                            "v5:stdlib",
                        ),
                    ),
                vue = vue(),
                racinesSources = racines,
                dossierIndex = null,
            ),
        )
        return session
    }

    /** Les messages « non résolue » du document, par nom. */
    private fun nonResolues(
        session: jo.lspkotlin.api.SessionLangageKotlin,
        uri: String,
    ): List<String> =
        session.diagnostics(uri)
            .filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }
            .map { it.message.substringAfter("« ").substringBefore(" »") }

    /** Sème les sources Kotlin de la famille visée et rend la racine. */
    private fun semer(vararg fichiers: Pair<String, String>): File {
        val racine = dossierTemporaire.newFolder()
        for ((chemin, contenu) in fichiers) {
            val fichier = File(racine, chemin)
            fichier.parentFile!!.mkdirs()
            fichier.writeText(contenu.trimIndent().replace("    ", "  ") + "\n")
        }
        return racine
    }

    /** Ouvre le premier fichier .kt semé (convention un fichier par test). */
    private fun ouvrir(
        session: jo.lspkotlin.api.SessionLangageKotlin,
        racine: File,
        chemin: String,
    ): String {
        val uri = "file:///$chemin"
        session.ouvrirDocument(uri, File(racine, chemin).readText())
        return uri
    }

    // ── 1. Récepteur de portée : candidates du PROJET ───────────────

    /** Membre NU + member-EXTENSION du scope PROJET, aucun faux positif. */
    @Test
    fun `portee - membre nu et member-extension du scope projet`() {
        val racine =
            semer(
                "com/ex/app/Ecrans.kt" to
                    """
                    package com.ex.app

                    class Maison
                    class PorteeMaison {
                      fun orner(m: Maison): Maison = m
                      fun compter(): Int = 1
                    }
                    fun Etage(contenu: PorteeMaison.() -> Unit) { PorteeMaison().contenu() }
                    """,
                "com/ex/app/Principal.kt" to
                    """
                    package com.ex.app

                    fun principale() {
                      Etage {
                        val n = compter()
                        Maison().orner()
                        val s = n + 1
                      }
                    }
                    """,
            )
        val session = session(listOf(racine))
        val uri = ouvrir(session, racine, "com/ex/app/Principal.kt")
        val nonResolues = nonResolues(session, uri)
        assertTrue(
            "compter()/orner() membres du récepteur de portée PorteeMaison doivent résoudre — obtenu : $nonResolues",
            nonResolues.isEmpty(),
        )
        session.close()
    }

    /** VRAI POSITIF : un membre inconnu DANS le scope reste signalé. */
    @Test
    fun `portee - membre inconnu dans le scope reste signale`() {
        val racine =
            semer(
                "com/ex/app/Ecrans.kt" to
                    """
                    package com.ex.app

                    class Maison
                    class PorteeMaison {
                      fun orner(m: Maison): Maison = m
                    }
                    fun Etage(contenu: PorteeMaison.() -> Unit) { PorteeMaison().contenu() }
                    """,
                "com/ex/app/Principal.kt" to
                    """
                    package com.ex.app

                    fun principale() {
                      Etage {
                        Maison().grincher()
                      }
                    }
                    """,
            )
        val session = session(listOf(racine))
        val uri = ouvrir(session, racine, "com/ex/app/Principal.kt")
        val nonResolues = nonResolues(session, uri)
        assertTrue(
            "grincher() inconnu dans le scope doit rester SIGNALÉ (miroir kotlinc) — obtenu : $nonResolues",
            "grincher" in nonResolues,
        )
        session.close()
    }

    /** HORS du scope : le membre du scope N'EST plus en portée.
     *
     * Le scope vit côté CLASSPATH (vue factice `exo.cp.PorteeEtrangere`)
     * : ses membres ne sont PAS des déclarations projet — la voie 4
     * du modèle plat (noms, tous conteneurs confondus) ne peut PAS
     * résoudre le callee, le non-fuite est donc OBSERVABLE (miroir
     * kotlinc : `PorteeEtrangere().bricoler()` hors du lambda est
     * rouge). */
    @Test
    fun `portee - hors du lambda le membre du scope est signale`() {
        val racine =
            semer(
                "com/ex/app/Etranger.kt" to
                    """
                    package com.ex.app

                    import exo.cp.PorteeEtrangere

                    fun Ecran(bloc: PorteeEtrangere.() -> Unit) {}
                    """,
                "com/ex/app/Principal.kt" to
                    """
                    package com.ex.app

                    import com.ex.app.Ecran

                    fun principale() {
                      Ecran {
                        bricoler()
                      }
                      EcranInconnu()
                      MaisonInconnue().fignoler()
                    }

                    class MaisonInconnue
                    fun EcranInconnu() {}
                    """,
            )
        val session = session(listOf(racine))
        val uri = ouvrir(session, racine, "com/ex/app/Principal.kt")
        val nonResolues = nonResolues(session, uri)
        assertTrue(
            "fignoler() HORS du lambda de scope doit être SIGNALÉ (non-fuite) — obtenu : $nonResolues",
            "fignoler" in nonResolues,
        )
        session.close()
    }

    /** La PILE : littéraux emboîtés, le plus profond d'abord. */
    @Test
    fun `portee - pile des scopes emboites`() {
        val racine =
            semer(
                "com/ex/app/Imbriques.kt" to
                    """
                    package com.ex.app

                    class PorteeA { fun dansA(): Int = 1 }
                    class PorteeB { fun dansB(): Int = 2 }
                    fun Exterieur(bloc: PorteeA.() -> Unit) {}
                    fun Interieur(bloc: PorteeB.() -> Unit) {}
                    """,
                "com/ex/app/Principal.kt" to
                    """
                    package com.ex.app

                    import com.ex.app.Interieur
                    import com.ex.app.Exterieur

                    fun principale() {
                      Exterieur {
                        dansA()
                        Interieur {
                          dansB()
                          dansA()
                        }
                      }
                    }
                    """,
            )
        val session = session(listOf(racine))
        val uri = ouvrir(session, racine, "com/ex/app/Principal.kt")
        val nonResolues = nonResolues(session, uri)
        assertTrue(
            "la pile des scopes (B puis A) doit prêter ses DEUX membres — obtenu : $nonResolues",
            nonResolues.isEmpty(),
        )
        session.close()
    }

    // ── 1-bis. Récepteur de portée : candidate du CLASSPATH ─────────

    /** `buildList { add(…) }` — MutableList prêté par l'index réel. */
    @Test
    fun `portee - buildList du classpath prete MutableList`() {
        val racine =
            semer(
                "com/ex/app/Batis.kt" to
                    """
                    package com.ex.app

                    fun batir(): List<String> = buildList {
                      add("pierre")
                      add("sable")
                    }
                    """,
            )
        val session = session(listOf(racine))
        val uri = ouvrir(session, racine, "com/ex/app/Batis.kt")
        val nonResolues = nonResolues(session, uri)
        assertTrue(
            "add() membre du récepteur de portée MutableList (buildList) doit résoudre — obtenu : $nonResolues",
            nonResolues.isEmpty(),
        )
        session.close()
    }

    // ── 2. Destructuring de paramètre de lambda ──────────────────────

    /** `(_, total) -> total` : les composants nommés sont en portée. */
    @Test
    fun `destructuring - composants nommes en portee`() {
        val racine =
            semer(
                "com/ex/app/Tri.kt" to
                    """
                    package com.ex.app

                    fun repartition(paires: List<Pair<String, Double>>): List<Double> =
                      paires.sortedByDescending { (_, total) -> total } +
                        paires.map { (cle, montant) -> montant + totalS()

                    fun totalS(): Double = 0.0
                    """,
            )
        val session = session(listOf(racine))
        val uri = ouvrir(session, racine, "com/ex/app/Tri.kt")
        val nonResolues = nonResolues(session, uri)
        assertTrue(
            "les composants destructurés (_, total)/(cle, montant) doivent être en portée — obtenu : $nonResolues",
            nonResolues.isEmpty(),
        )
        session.close()
    }

    /** VRAI POSITIF : un nom INCONNU dans le corps reste signalé. */
    @Test
    fun `destructuring - nom inconnu dans le corps reste signale`() {
        val racine =
            semer(
                "com/ex/app/TriFaux.kt" to
                    """
                    package com.ex.app

                    fun repartition(paires: List<Pair<String, Double>>): List<Double> =
                      paires.sortedByDescending { (_, total) -> totalInconnu }
                    """,
            )
        val session = session(listOf(racine))
        val uri = ouvrir(session, racine, "com/ex/app/TriFaux.kt")
        val nonResolues = nonResolues(session, uri)
        assertTrue(
            "totalInconnu doit rester SIGNALÉ (le destructuring ne crée pas de nom) — obtenu : $nonResolues",
            "totalInconnu" in nonResolues,
        )
        session.close()
    }

    // ── 3. Propriétés builtins de Map ────────────────────────────────

    /** `entries`/`keys`/`values` sur une Map — les actuals Java. */
    @Test
    fun `builtins map - entries keys values resolus`() {
        val racine =
            semer(
                "com/ex/app/Rapport.kt" to
                    """
                    package com.ex.app

                    fun rapport(totaux: Map<String, Double>): String {
                      val entrees = totaux.entries
                      val cles = totaux.keys
                      val valeurs = totaux.values
                      return "${'$'}entrees ${'$'}cles ${'$'}valeurs"
                    }
                    """,
            )
        val session = session(listOf(racine))
        val uri = ouvrir(session, racine, "com/ex/app/Rapport.kt")
        val nonResolues = nonResolues(session, uri)
        assertTrue(
            "entries/keys/values (builtins Map → entrySet()/keySet()) doivent résoudre — obtenu : $nonResolues",
            nonResolues.isEmpty(),
        )
        session.close()
    }
}
