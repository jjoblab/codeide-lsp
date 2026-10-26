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
 * REPRODUCTION des familles de la VAGUE 4 (retour utilisateurs
 * 25/10/2026 — backlog RETOURS §4, items 1 à 4) :
 *
 *  1. MEMBRES HÉRITÉS du classpath par le RÉCEPTEUR IMPLICITE —
 *     `soumettre(…)`/`compte` nus dans une classe projet qui hérite
 *     d'une superclasse BINAIRE (le chaînon projet→classpath), Y
 *     COMPRIS depuis un OBJET ANONYME (la pile des this : l'objet
 *     d'abord, puis les classes englobantes — `submitList` nu dans un
 *     `object : Filter()` sous un adaptant, U2) ;
 *  2. TYPES IMBRIQUÉS des SUPERTYPES par nom simple (règle kotlinc
 *     PROUVÉE par expérience : hériter met les imbriqués en portée —
 *     `FilterResults` nu, bytecode `Filter$FilterResults`, U2) ;
 *  3. PARAMÈTRES du constructeur PRIMAIRE en zone de construction
 *     (super-clause, initialisateurs, init — PAS dans les méthodes,
 *     expérience kotlinc : `contexte`/`attributs`/`styleDef` de
 *     JaugeUsure, U2) + `field`/paramètre des setters custom ;
 *  4. NOMS COMPLETS QUALIFIÉS sans import — EN EXPRESSION
 *     (`androidx.recycler….LinearLayoutManager(this)`, U2) et
 *     IMBRIQUÉS QUALIFIÉS en position de TYPE
 *     (`ViewModelProvider.Factory`, U1) ;
 *  5. PROPRIÉTÉS SYNTHÉTIQUES Java dans un `apply { }` (le récepteur
 *     implicite de l'extension apply — `paint.style = …`, U2).
 *
 * Miroir du bytecode réel : la vue factice calque les descripteurs
 * que livre le corpus normalisé v2 (supertypes lus par octets bruts,
 * membres par vue, imbriqués sous la forme dollar).
 */
class ReproductionHeritageVague4Test {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    /**
     * Vue factice miroir du bytecode des familles :
     *  - `exo.herie.Base` : superclasse avec `soumettre(List)` et le
     *    champ `compte`, son IMBRIQUÉ `Base$Resultats` ;
     *  - `exo.herie.Autre` : classe d'objet anonyme, `filtrer()` ;
     *  - `exo.paq.ClassePublique` : cible du FQN complet en expression ;
     *  - `exo.conteneur.Conteneur` + `Conteneur$Fabrique` : imbriqué
     *    qualifié en position de TYPE ;
     *  - `exo.peint.Peinture` : `getStyle()/setStyle(I)V` (propriété
     *    synthétique Java dans apply) + imbriqué `Peinture$Capitaine`
     *    au champ statique `ROND`.
     */
    private fun vue(): VueClasspathKotlin = object : VueClasspathKotlin {
        private val membres: Map<String, MembresTypeKotlin> =
            mapOf(
                "exo.herie.Base" to
                    MembresTypeKotlin(
                        0x0021,
                        listOf(MembreJvmKotlin(0x0001, "compte", "I")),
                        listOf(MembreJvmKotlin(0x0001, "soumettre", "(Ljava/util/List;)V")),
                    ),
                "exo.herie.Base\$Resultats" to
                    MembresTypeKotlin(0x0021, listOf(MembreJvmKotlin(0x0001, "total", "I")), emptyList()),
                "exo.herie.Autre" to
                    MembresTypeKotlin(0x0021, emptyList(), listOf(MembreJvmKotlin(0x0001, "filtrer", "()V"))),
                "exo.paq.ClassePublique" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
                "exo.conteneur.Conteneur" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
                "exo.conteneur.Conteneur\$Fabrique" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
                "exo.peint.Peinture" to
                    MembresTypeKotlin(
                        0x0021,
                        listOf(MembreJvmKotlin(0x0008, "DRAPEAU", "I")),
                        listOf(
                            MembreJvmKotlin(0x0001, "getStyle", "()I"),
                            MembreJvmKotlin(0x0001, "setStyle", "(I)V"),
                        ),
                    ),
                "exo.peint.Peinture\$Capitaine" to
                    MembresTypeKotlin(
                        0x0019,
                        listOf(MembreJvmKotlin(0x0019, "ROND", "Lexo/peint/Peinture\$Capitaine;")),
                        emptyList(),
                    ),
                "java.lang.Object" to MembresTypeKotlin(0x0021, emptyList(), emptyList()),
            )
        private val supers: Map<String, String?> =
            mapOf(
                "exo.herie.Base" to "java.lang.Object",
                "exo.herie.Base\$Resultats" to "java.lang.Object",
                "exo.herie.Autre" to "java.lang.Object",
                "exo.paq.ClassePublique" to "java.lang.Object",
                "exo.conteneur.Conteneur" to "java.lang.Object",
                "exo.conteneur.Conteneur\$Fabrique" to "java.lang.Object",
                "exo.peint.Peinture" to "java.lang.Object",
                "exo.peint.Peinture\$Capitaine" to "java.lang.Enum",
                "java.lang.Object" to null,
            )

        override fun membres(fqcn: String): MembresTypeKotlin? = membres[fqcn]

        override fun octetsClasse(fqcn: String): ByteArray? = supers[fqcn]?.let { octets(fqcn, it) }

        override fun nomsParPrefixe(prefixe: String, limite: Int): List<NomTypeKotlin> =
            listOf("Base", "Autre", "ClassePublique", "Conteneur", "Peinture")
                .filter { it.startsWith(prefixe) }
                .map { NomTypeKotlin(it, listOfNotNull(membersNotNull(it))) }

        private fun membersNotNull(nom: String): String? =
            membres.keys.firstOrNull { it.endsWith(".$nom") }

        override fun typesDansPaquet(paquet: String): List<String> =
            membres.keys.filter { it.substringBeforeLast('.') == paquet }

        override fun estPaquet(nom: String): Boolean = nom.startsWith("exo.")

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
                            "v4:stdlib",
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

    // ── 1. Membres hérités + pile des this (objet anonyme) ───────────

    /** Le calme plat : AUCUNE non résolue sur le fichier CORRECT. */
    @Test
    fun `heritage - membres herites du classpath par this implicite`() {
        val racine =
            semer(
                "com/ex/app/Adaptant.kt" to
                    """
                    package com.ex.app

                    import exo.herie.Base

                    class Adaptant : Base() {
                      fun rafraichir(donnees: List<String>) {
                        soumettre(donnees)
                        val vue = compte + 1
                        soumettre(listOf(vue.toString()))
                      }
                    }
                    """,
            )
        val session = session(listOf(racine))
        val uri = "file:///com/ex/app/Adaptant.kt"
        session.ouvrirDocument(uri, File(racine, "com/ex/app/Adaptant.kt").readText())
        val nonResolues = nonResolues(session, uri)
        System.out.println("SONDAGE héritage this: $nonResolues")
        assertTrue(
            "soumettre/compte hérités de Base doivent résoudre (this implicite) — obtenu : $nonResolues",
            nonResolues.isEmpty(),
        )
        session.close()
    }

    /** La PILE des this : l'objet anonyme d'abord, puis la classe externe. */
    @Test
    fun `heritage - pile des this dans un objet anonyme`() {
        val racine =
            semer(
                "com/ex/app/Pile.kt" to
                    """
                    package com.ex.app

                    import exo.herie.Autre
                    import exo.herie.Base

                    class Pile : Base() {
                      val delegue = object : Autre() {
                        fun marcher(donnees: List<String>) {
                          filtrer()
                          soumettre(donnees)
                        }
                      }
                    }
                    """,
            )
        val session = session(listOf(racine))
        val uri = "file:///com/ex/app/Pile.kt"
        session.ouvrirDocument(uri, File(racine, "com/ex/app/Pile.kt").readText())
        val nonResolues = nonResolues(session, uri)
        System.out.println("SONDAGE pile des this: $nonResolues")
        assertTrue(
            "filtrer (this anonyme) ET soumettre (this externe) doivent résoudre — obtenu : $nonResolues",
            nonResolues.isEmpty(),
        )
        session.close()
    }

    // ── 2. Imbriqués des supertypes par nom simple ───────────────────

    /** `Resultats` nu : type IMBRIQUÉ de la superclasse binaire. */
    @Test
    fun `imbriques - herites du supertype par nom simple`() {
        val racine =
            semer(
                "com/ex/app/Filtre.kt" to
                    """
                    package com.ex.app

                    import exo.herie.Base

                    class Filtre : Base() {
                      fun produire(): Resultats? {
                        val resultat = Resultats()
                        return resultat
                      }

                      fun publier(results: Resultats?) {
                        val liste = results?.total
                        if (liste != null) {
                          soumettre(listOf(liste.toString()))
                        }
                      }
                    }
                    """,
            )
        val session = session(listOf(racine))
        val uri = "file:///com/ex/app/Filtre.kt"
        val source = File(racine, "com/ex/app/Filtre.kt").readText()
        session.ouvrirDocument(uri, source)
        val nonResolues = nonResolues(session, uri)
        System.out.println("SONDAGE imbriqués hérités: $nonResolues")
        // SONDE : le typage de `results` vu par la complétion membre.
        val positionTotal = source.indexOf("results?.total") + "results?.".length
        val completions = session.completion(uri, positionTotal)
        java.io.File(
            "/tmp/sondage-completion.txt",
        ).writeText("complétion results?. => ${completions.map { it.libelle }.take(10)}\n")
        assertTrue(
            "Resultats nu (signature, constructeur) ET le champ total doivent résoudre — obtenu : $nonResolues",
            nonResolues.isEmpty(),
        )
        session.close()
    }

    /** L'objet ANONYME hérite : le paramètre typé imbriqué + son champ. */
    @Test
    fun `imbriques - parametre typé dans un objet anonyme heritant`() {
        val racine =
            semer(
                "com/ex/app/Delegue.kt" to
                    """
                    package com.ex.app

                    import exo.herie.Autre
                    import exo.herie.Base

                    class Delegue : Base() {
                      val filtre = object : Autre() {
                        fun publier(results: Resultats?): Int {
                          val total = results?.total
                          return total ?: 0
                        }
                      }
                    }
                    """,
            )
        val session = session(listOf(racine))
        val uri = "file:///com/ex/app/Delegue.kt"
        session.ouvrirDocument(uri, File(racine, "com/ex/app/Delegue.kt").readText())
        val nonResolues = nonResolues(session, uri)
        System.out.println("SONDAGE paramètre typé objet anonyme: $nonResolues")
        assertTrue(
            "Resultats nu (paramètre d'objet anonyme) et total doivent résoudre — obtenu : $nonResolues",
            nonResolues.isEmpty(),
        )
        session.close()
    }

    // ── 3. Paramètres du primaire + setter custom ─────────────────────

    /** Zone de construction : initialisateurs et init voient les paramètres. */
    @Test
    fun `primaire - parametres en zone de construction`() {
        val racine =
            semer(
                "com/ex/app/Jauge.kt" to
                    """
                    package com.ex.app

                    import exo.herie.Base

                    class Jauge(contexte: Int, styleDef: Int) : Base() {
                      private val empreinte = contexte + styleDef

                      init {
                        soumettre(listOf(contexte.toString(), styleDef.toString(), empreinte.toString()))
                      }
                    }
                    """,
            )
        val session = session(listOf(racine))
        val uri = "file:///com/ex/app/Jauge.kt"
        session.ouvrirDocument(uri, File(racine, "com/ex/app/Jauge.kt").readText())
        val nonResolues = nonResolues(session, uri)
        System.out.println("SONDAGE primaire: $nonResolues")
        assertTrue(
            "contexte/styleDef lisibles dans l'initialisateur et le init — obtenu : $nonResolues",
            nonResolues.isEmpty(),
        )
        session.close()
    }

    /** Hors zone de construction : un paramètre du primaire y est SIGNALÉ. */
    @Test
    fun `primaire - hors portee dans une methode, vrai positif rattrape`() {
        val racine =
            semer(
                "com/ex/app/HorsZone.kt" to
                    """
                    package com.ex.app

                    import exo.herie.Base

                    class HorsZone(contexte: Int) : Base() {
                      fun dansMethode(): Int = contexte
                    }
                    """,
            )
        val session = session(listOf(racine))
        val uri = "file:///com/ex/app/HorsZone.kt"
        session.ouvrirDocument(uri, File(racine, "com/ex/app/HorsZone.kt").readText())
        val nonResolues = nonResolues(session, uri)
        System.out.println("SONDAGE hors zone: $nonResolues")
        assertTrue(
            "contexte doit rester SIGNALÉ dans un corps de méthode (miroir kotlinc) — obtenu : $nonResolues",
            "contexte" in nonResolues,
        )
        session.close()
    }

    /** `field` et le paramètre du setter custom. */
    @Test
    fun `setter - field et parametre en portee`() {
        val racine =
            semer(
                "com/ex/app/Niveau.kt" to
                    """
                    package com.ex.app

                    class Niveau {
                      var niveau: Int = 0
                        set(valeur) {
                          field = valeur + 1
                        }

                      var libelle: String = ""
                        get() {
                          return field
                        }
                    }
                    """,
            )
        val session = session(listOf(racine))
        val uri = "file:///com/ex/app/Niveau.kt"
        session.ouvrirDocument(uri, File(racine, "com/ex/app/Niveau.kt").readText())
        val nonResolues = nonResolues(session, uri)
        System.out.println("SONDAGE setter: $nonResolues")
        assertTrue(
            "field et valeur doivent résoudre dans le setter custom — obtenu : $nonResolues",
            nonResolues.isEmpty(),
        )
        session.close()
    }

    // ── 4. Noms complets qualifiés sans import ───────────────────────

    /** FQCN complet EN EXPRESSION : tous les segments muets. */
    @Test
    fun `fqn - expression completee sans import`() {
        val racine =
            semer(
                "com/ex/app/Fabrique.kt" to
                    """
                    package com.ex.app

                    class Fabrique {
                      fun creer(): exo.paq.ClassePublique = exo.paq.ClassePublique()
                    }
                    """,
            )
        val session = session(listOf(racine))
        val uri = "file:///com/ex/app/Fabrique.kt"
        session.ouvrirDocument(uri, File(racine, "com/ex/app/Fabrique.kt").readText())
        val nonResolues = nonResolues(session, uri)
        System.out.println("SONDAGE fqn: $nonResolues")
        assertTrue(
            "exo.paq.ClassePublique en expression ET en type doivent résoudre sans import — obtenu : $nonResolues",
            nonResolues.isEmpty(),
        )
        session.close()
    }

    /** FQCN INTROUVABLE : premier et dernier segments signalés (miroir
     *  kotlinc — « unresolved: exo » sur le récepteur racine), le SEGMENT
     *  INTERMÉDIAIRE (paquet potentiel) muet — jamais trois rougeurs. */
    @Test
    fun `fqn - introuvable, premier et dernier signales, milieu muet`() {
        val racine =
            semer(
                "com/ex/app/Casse.kt" to
                    """
                    package com.ex.app

                    class Casse {
                      fun casser() = exo.paq.Introuvable()
                    }
                    """,
            )
        val session = session(listOf(racine))
        val uri = "file:///com/ex/app/Casse.kt"
        session.ouvrirDocument(uri, File(racine, "com/ex/app/Casse.kt").readText())
        val nonResolues = nonResolues(session, uri)
        System.out.println("SONDAGE fqn introuvable: $nonResolues")
        assertTrue(
            "exo (récepteur inconnu) et Introuvable signalés, paq muet — obtenu : $nonResolues",
            nonResolues.toSet() == setOf("exo", "Introuvable"),
        )
        session.close()
    }

    /** Imbriqué QUALIFIÉ en position de TYPE : `Conteneur.Fabrique`. */
    @Test
    fun `imbriques - qualifies en position de type`() {
        val racine =
            semer(
                "com/ex/app/Di.kt" to
                    """
                    package com.ex.app

                    import exo.conteneur.Conteneur

                    class Di {
                      val fabrique: Conteneur.Fabrique? = null
                    }
                    """,
            )
        val session = session(listOf(racine))
        val uri = "file:///com/ex/app/Di.kt"
        session.ouvrirDocument(uri, File(racine, "com/ex/app/Di.kt").readText())
        val nonResolues = nonResolues(session, uri)
        System.out.println("SONDAGE imbriqué qualifié type: $nonResolues")
        assertTrue(
            "Conteneur.Fabrique en annotation de type doit résoudre — obtenu : $nonResolues",
            nonResolues.isEmpty(),
        )
        session.close()
    }

    // ── 5. Propriétés synthétiques Java dans apply ────────────────────

    /** `apply { style = … }` : le récepteur implicite est la Peinture. */
    @Test
    fun `apply - propriete synthetique java du recepteur implicite`() {
        val racine =
            semer(
                "com/ex/app/Habillage.kt" to
                    """
                    package com.ex.app

                    import exo.peint.Peinture

                    class Habillage {
                      fun habiller(): Peinture =
                        Peinture().apply {
                          style = 1
                        }
                    }
                    """,
            )
        val session = session(listOf(racine))
        val uri = "file:///com/ex/app/Habillage.kt"
        session.ouvrirDocument(uri, File(racine, "com/ex/app/Habillage.kt").readText())
        val nonResolues = nonResolues(session, uri)
        System.out.println("SONDAGE apply: $nonResolues")
        assertTrue(
            "style (setter synthétique de Peinture) doit résoudre dans apply — obtenu : $nonResolues",
            nonResolues.isEmpty(),
        )
        session.close()
    }

    /** Chaîne d'imbriqués en expression : `Peinture.Capitaine.ROND`. */
    @Test
    fun `imbriques - chaine en expression`() {
        val racine =
            semer(
                "com/ex/app/Trace.kt" to
                    """
                    package com.ex.app

                    import exo.peint.Peinture

                    class Trace {
                      fun arrondi() = Peinture.Capitaine.ROND
                    }
                    """,
            )
        val session = session(listOf(racine))
        val uri = "file:///com/ex/app/Trace.kt"
        session.ouvrirDocument(uri, File(racine, "com/ex/app/Trace.kt").readText())
        val nonResolues = nonResolues(session, uri)
        System.out.println("SONDAGE chaîne imbriqués: $nonResolues")
        assertTrue(
            "Peinture.Capitaine.ROND doit résoudre (sélecteur imbriqué + champ statique) — obtenu : $nonResolues",
            nonResolues.isEmpty(),
        )
        session.close()
    }
}
