package jo.lspkotlin.session

import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.ClasspathKotlin
import jo.lspkotlin.api.SeveriteKotlin
import jo.lspkotlin.api.ServiceMoteurLangageKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Tests du contrat de session K4 (LSP_KOTLIN.md §5, K4 « inférence
 * bornée, smart casts, diagnostics sémantiques, quick-fixes d'import »)
 * — par le SPI (chemin de production D6), avec la MÊME vue factice que
 * la suite K3 (types/paquets/membres/octets synthétiques) :
 *
 *  - SMART CASTS positionnels : la complétion membre voit le type CASTÉ
 *    (branche THEN, when sans sujet, opérande droit de `&&`, garde
 *    `!is` à sortie précoce) ;
 *  - INFÉRENCE BORNÉE : paramètres de lambda typés par l'ATTENTE du
 *    consommateur projet (fonction, SAM d'interface, initialiseur
 *    typé), `it` implicite compris ;
 *  - DIAGNOSTICS SÉMANTIQUES : un test POSITIF et une garde de
 *    SOUS-RAPPORT par code du catalogue K4 ;
 *  - QUICK-FIXES : importsCandidats rend les FQCN du classpath portant
 *    exactement le nom non résolu, déjà importés exclus.
 */
class SessionLangageKotlinK4Test {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    /** Vue factice (même univers que la suite K3). */
    private val vue = VueClasspathFacticeK4()

    /** Session nourrie du classpath factice (régime complet K4). */
    private fun session(): jo.lspkotlin.api.SessionLangageKotlin {
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.definirClasspath(
            ClasspathKotlin(
                entrees = emptyList(),
                vue = vue,
                racinesSources = emptyList(),
                dossierIndex = null,
            ),
        )
        return session
    }

    /** Session SANS classpath (régime dégradé — sous-rapport D3). */
    private fun sessionNue(): jo.lspkotlin.api.SessionLangageKotlin =
        ServiceMoteurLangageKotlin.unique().creerSession()

    // ── Smart casts positionnels (la complétion membre voit le cast) ───

    @Test
    fun `smart cast - branche THEN d'un if, membres du type casté`() {
        val session = session()
        val source =
            """
            import android.widget.TextView
            fun demo(x: Any) {
              if (x is TextView) {
                x.
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///cast-if.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("x.") + 2
        val propositions = session.completion(uri, decalage)
        val parLibelle = propositions.associateBy { it.libelle }
        // Champ DÉCLARÉ de TextView — le cast a raffiné le type de x.
        assertNotNull("le champ texte doit être proposé (cast appliqué)", parLibelle["texte"])
        // Méthode HÉRITÉE de View — la chaîne du cast est résolue aussi.
        assertNotNull(parLibelle["requestLayout()"])
        session.close()
    }

    @Test
    fun `smart cast - garde !is a sortie precoce, membres apres le garde`() {
        val session = session()
        val source =
            """
            import android.widget.TextView
            fun demo(x: Any) {
              if (x !is TextView) return
              x.
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///cast-garde.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("x.", startIndex = source.indexOf("return")) + 2
        val propositions = session.completion(uri, decalage)
        assertNotNull(propositions.associateBy { it.libelle }["texte"])
        session.close()
    }

    @Test
    fun `smart cast - operande droit d'un &&`() {
        val session = session()
        val source =
            """
            import android.widget.TextView
            fun demo(x: Any, apres: Boolean): Boolean {
              return x is TextView && x.
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///cast-et.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("x.", source.indexOf("&&")) + 2
        val propositions = session.completion(uri, decalage)
        assertNotNull(propositions.associateBy { it.libelle }["texte"])
        session.close()
    }

    @Test
    fun `smart cast - entree d'un when sans sujet`() {
        val session = session()
        val source =
            """
            import android.widget.TextView
            fun demo(x: Any): Int {
              when {
                x is TextView -> x.
                else -> 0
              }
              return 0
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///cast-when.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("x.", source.indexOf("->")) + 2
        val propositions = session.completion(uri, decalage)
        assertNotNull(propositions.associateBy { it.libelle }["texte"])
        session.close()
    }

    @Test
    fun `smart cast - locale OMBREE apres la condition, le cast ne fuit pas`() {
        val session = session()
        val source =
            """
            import android.widget.TextView
            fun demo(x: Any) {
              if (x is TextView) {
                val x = "une chaine"
                x.
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///cast-ombre.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.lastIndexOf("x.") + 2
        val propositions = session.completion(uri, decalage)
        // La locale String OMBRE le sujet du cast : les membres de
        // TextView ne sont PAS proposés (length de String oui).
        assertTrue(propositions.associateBy { it.libelle }["texte"] == null)
        assertNotNull(propositions.associateBy { it.libelle }["length()"])
        session.close()
    }

    @Test
    fun `smart cast - garde sans sortie precoce, aucun cast (sous-rapport)`() {
        val session = session()
        val source =
            """
            import android.widget.TextView
            fun demo(x: Any) {
              if (x !is TextView) {
                val b = 1
              }
              x.
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///cast-sans-garde.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.lastIndexOf("x.") + 2
        val propositions = session.completion(uri, decalage)
        assertTrue(
            "la garde ne SORT pas : aucun cast ne s'applique",
            propositions.associateBy { it.libelle }["texte"] == null,
        )
        session.close()
    }

    // ── Inférence bornée des paramètres de lambda ──────────────────────

    @Test
    fun `lambda - parametre explicite type par l'attente de la fonction projet`() {
        val session = session()
        val source =
            """
            fun repeter(n: Int, action: (String) -> Unit) {}
            fun demo() {
              repeter(2) { s -> s. }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///lambda.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("s.") + 2
        val propositions = session.completion(uri, decalage)
        assertNotNull(
            "s est typé String par l'attente (String) -> Unit",
            propositions.associateBy { it.libelle }["length()"],
        )
        session.close()
    }

    @Test
    fun `lambda - it implicite type par l'attente a parametre unique`() {
        val session = session()
        val source =
            """
            fun repeter(n: Int, action: (String) -> Unit) {}
            fun demo() {
              repeter(2) { it. }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///lambda-it.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("it.") + 3
        val propositions = session.completion(uri, decalage)
        assertNotNull(propositions.associateBy { it.libelle }["length()"])
        session.close()
    }

    @Test
    fun `lambda - SAM d'une interface projet a methode unique`() {
        val session = session()
        val source =
            """
            interface Ecrivain {
              fun ecrire(ligne: String)
            }
            fun demo() {
              val e = Ecrivain { l -> l. }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///sam.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("l.") + 2
        val propositions = session.completion(uri, decalage)
        assertNotNull(
            "l est typé String par la méthode unique du SAM",
            propositions.associateBy { it.libelle }["length()"],
        )
        session.close()
    }

    @Test
    fun `lambda - initialiseur de propriete au type de fonction ecrit`() {
        val session = session()
        val source =
            """
            fun demo() {
              val cb: (String) -> Unit = { s -> s. }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///lambda-propriete.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("s.") + 2
        val propositions = session.completion(uri, decalage)
        assertNotNull(propositions.associateBy { it.libelle }["length()"])
        session.close()
    }

    @Test
    fun `lambda - sans attente demonstrable, aucun membre (sous-rapport)`() {
        val session = session()
        val source =
            """
            fun demo() {
              val f = { s -> s. }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///lambda-inconnu.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("s.") + 2
        val propositions = session.completion(uri, decalage)
        assertTrue(
            "aucune attente : s n'est pas typé (D3)",
            propositions.none { it.libelle == "length()" },
        )
        session.close()
    }

    // ── Diagnostics sémantiques ────────────────────────────────────────

    @Test
    fun `kt unresolved - reference simple introuvable, signalee`() {
        val session = session()
        val source = "fun demo() {\n  introuvable\n}\n"
        val uri = "file:///inconnu.kt"
        session.ouvrirDocument(uri, source)
        val diagnostics = session.diagnostics(uri)
        val nonResolus = diagnostics.filter { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE }
        assertEquals(1, nonResolus.size)
        val diagnostic = nonResolus.single()
        assertEquals(SeveriteKotlin.ERREUR, diagnostic.severite)
        assertEquals(source.indexOf("introuvable"), diagnostic.debut)
        assertEquals(source.indexOf("introuvable") + "introuvable".length, diagnostic.fin)
        assertTrue(diagnostic.message.contains("introuvable"))
        session.close()
    }

    @Test
    fun `kt unresolved - sans classpath, jamais signale (sous-rapport)`() {
        val session = sessionNue()
        session.ouvrirDocument("file:///nu.kt", "fun demo() {\n  introuvable\n}\n")
        assertTrue(
            "régime dégradé : impossible de prouver une absence",
            session.diagnostics("file:///nu.kt").none { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE },
        )
        session.close()
    }

    @Test
    fun `kt unresolved - selecteur qualifie et types, jamais signales (sous-rapport)`() {
        val session = session()
        val source =
            """
            fun demo(s: String) {
              s.inexistant
            }
            """.trimIndent().replace("    ", "  ")
        session.ouvrirDocument("file:///selecteur.kt", source)
        assertTrue(
            session.diagnostics("file:///selecteur.kt")
                .none { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE },
        )
        session.close()
    }

    @Test
    fun `kt typeMismatch - litteral chaine affecte a Int, signale`() {
        val session = session()
        session.ouvrirDocument("file:///type.kt", "val x: Int = \"s\"\n")
        val diagnostics = session.diagnostics("file:///type.kt")
        val incompatible = diagnostics.filter { it.code == CodesDiagnosticsKotlin.TYPE_INCOMPATIBLE }
        assertEquals(1, incompatible.size)
        assertEquals(SeveriteKotlin.ERREUR, incompatible.single().severite)
        assertEquals("\"s\"".length, incompatible.single().fin - incompatible.single().debut)
        session.close()
    }

    @Test
    fun `kt typeMismatch - conversions legitales, jamais signalees (sous-rapport)`() {
        val session = session()
        val source = "val a: Long = 1\nval b: Double = 2\nval c: String? = \"s\"\nval d: Boolean = true\n"
        session.ouvrirDocument("file:///type-ok.kt", source)
        assertTrue(
            session.diagnostics("file:///type-ok.kt").none { it.code == CodesDiagnosticsKotlin.TYPE_INCOMPATIBLE },
        )
        session.close()
    }

    @Test
    fun `kt argumentCount - appel d'une fonction du projet, arite fausse`() {
        val session = session()
        val source =
            """
            fun f(a: Int, b: Int) {}
            fun demo() {
              f(1)
            }
            """.trimIndent().replace("    ", "  ")
        session.ouvrirDocument("file:///arite.kt", source)
        val diagnostics = session.diagnostics("file:///arite.kt")
        val arites = diagnostics.filter { it.code == CodesDiagnosticsKotlin.NOMBRE_ARGUMENTS }
        assertEquals(1, arites.size)
        val diagnostic = arites.single()
        assertEquals(SeveriteKotlin.ERREUR, diagnostic.severite)
        assertEquals(source.indexOf("f(1)"), diagnostic.debut)
        assertTrue(diagnostic.message.contains("1"))
        assertTrue(diagnostic.message.contains("2"))
        session.close()
    }

    @Test
    fun `kt argumentCount - constructeur du projet, arite fausse`() {
        val session = session()
        val source =
            """
            class Point(val x: Int, val y: Int)
            fun demo() {
              val p = Point(1)
            }
            """.trimIndent().replace("    ", "  ")
        session.ouvrirDocument("file:///ctor.kt", source)
        val diagnostics = session.diagnostics("file:///ctor.kt")
        assertEquals(1, diagnostics.filter { it.code == CodesDiagnosticsKotlin.NOMBRE_ARGUMENTS }.size)
        session.close()
    }

    @Test
    fun `kt argumentCount - defauts et varargs, jamais signales (sous-rapport)`() {
        val session = session()
        val source =
            """
            fun g(a: Int, b: Int = 2) {}
            fun h(vararg xs: Int) {}
            fun demo() {
              g(1)
              h()
            }
            """.trimIndent().replace("    ", "  ")
        session.ouvrirDocument("file:///arite-ok.kt", source)
        assertTrue(
            session.diagnostics("file:///arite-ok.kt").none { it.code == CodesDiagnosticsKotlin.NOMBRE_ARGUMENTS },
        )
        session.close()
    }

    @Test
    fun `kt unsafeNullable - point sur un recepteur nullable, signale`() {
        val session = session()
        val source =
            """
            fun demo(s: String?) {
              s.length
            }
            """.trimIndent().replace("    ", "  ")
        session.ouvrirDocument("file:///sur.kt", source)
        val diagnostics = session.diagnostics("file:///sur.kt")
        val nonsurs = diagnostics.filter { it.code == CodesDiagnosticsKotlin.ACCES_NON_SUR }
        assertEquals(1, nonsurs.size)
        assertEquals(SeveriteKotlin.ERREUR, nonsurs.single().severite)
        assertTrue(nonsurs.single().message.contains("?."))
        session.close()
    }

    @Test
    fun `kt unsafeNullable - garde de nullite et acces sur, jamais signales (sous-rapport)`() {
        val session = session()
        val source =
            """
            fun demo(s: String?) {
              val n = s?.length
              if (s != null) {
                s.length
              }
            }
            """.trimIndent().replace("    ", "  ")
        session.ouvrirDocument("file:///sur-ok.kt", source)
        assertTrue(
            session.diagnostics("file:///sur-ok.kt").none { it.code == CodesDiagnosticsKotlin.ACCES_NON_SUR },
        )
        session.close()
    }

    @Test
    fun `kt missingReturn - corps de bloc sans return, signale`() {
        val session = session()
        val source = "fun f(): Int {\n  val a = 1\n}\n"
        session.ouvrirDocument("file:///retour.kt", source)
        val diagnostics = session.diagnostics("file:///retour.kt")
        val manquants = diagnostics.filter { it.code == CodesDiagnosticsKotlin.RETOUR_MANQUANT }
        assertEquals(1, manquants.size)
        assertEquals(source.indexOf("f()"), manquants.single().debut)
        assertTrue(manquants.single().message.contains("Int"))
        session.close()
    }

    @Test
    fun `kt missingReturn - Unit, corps d'expression et throw, jamais signales (sous-rapport)`() {
        val session = session()
        val source =
            """
            fun u() {
              val a = 1
            }
            fun e(): Int = 1
            fun t(): Int {
              throw IllegalStateException("x")
            }
            """.trimIndent().replace("    ", "  ")
        session.ouvrirDocument("file:///retour-ok.kt", source)
        assertTrue(
            session.diagnostics("file:///retour-ok.kt").none { it.code == CodesDiagnosticsKotlin.RETOUR_MANQUANT },
        )
        session.close()
    }

    @Test
    fun `kt unreachable - declaration apres un return, signalee`() {
        val session = session()
        val source = "fun f(): Int {\n  return 1\n  val a = 2\n}\n"
        session.ouvrirDocument("file:///mort.kt", source)
        val diagnostics = session.diagnostics("file:///mort.kt")
        val morts = diagnostics.filter { it.code == CodesDiagnosticsKotlin.CODE_INATTEIGNABLE }
        assertEquals(1, morts.size)
        assertEquals(SeveriteKotlin.AVERTISSEMENT, morts.single().severite)
        assertEquals(source.indexOf("val a"), morts.single().debut)
        session.close()
    }

    @Test
    fun `kt unreachable - apres throw aussi, return imbrique non frere ignore (sous-rapport)`() {
        val session = session()
        val source =
            """
            fun f(): Int {
              if (true) {
                return 1
              }
              return 2
            }
            fun g(): Int {
              throw IllegalStateException("x")
              val a = 1
            }
            """.trimIndent().replace("    ", "  ")
        session.ouvrirDocument("file:///mort2.kt", source)
        val morts =
            session.diagnostics("file:///mort2.kt")
                .filter { it.code == CodesDiagnosticsKotlin.CODE_INATTEIGNABLE }
        // Seul le `val a` de g suit DIRECTEMENT un terminal.
        assertEquals(1, morts.size)
        assertTrue(morts.single().debut >= source.lastIndexOf("val a"))
        session.close()
    }

    @Test
    fun `kt valReassign - affectation a un val, signalee`() {
        val session = session()
        val source = "fun demo() {\n  val v = 1\n  v = 2\n}\n"
        session.ouvrirDocument("file:///val.kt", source)
        val diagnostics = session.diagnostics("file:///val.kt")
        val reaffectations = diagnostics.filter { it.code == CodesDiagnosticsKotlin.REAFFECTATION_VAL }
        assertEquals(1, reaffectations.size)
        val diagnostic = reaffectations.single()
        assertEquals(SeveriteKotlin.ERREUR, diagnostic.severite)
        assertEquals(source.lastIndexOf("v"), diagnostic.debut)
        session.close()
    }

    @Test
    fun `kt valReassign - var et cibles introuvables, jamais signales (sous-rapport)`() {
        val session = session()
        val source = "fun demo() {\n  var w = 1\n  w = 2\n}\n"
        session.ouvrirDocument("file:///var.kt", source)
        assertTrue(
            session.diagnostics("file:///var.kt").none { it.code == CodesDiagnosticsKotlin.REAFFECTATION_VAL },
        )
        session.close()
    }

    @Test
    fun `diagnostics semantiques - arbre en edition, tout le semantique ecarte`() {
        val session = session()
        // L'erreur de parse désactive les preuves sémantiques : le
        // valReassign ci-dessous n'est PAS signalé (garde générale).
        val source = "fun demo() {\n  val v = 1\n  v = (\n}\n"
        session.ouvrirDocument("file:///edition.kt", source)
        val diagnostics = session.diagnostics("file:///edition.kt")
        assertTrue(diagnostics.any { it.code == CodesDiagnosticsKotlin.SYNTAXE })
        assertTrue(diagnostics.none { it.code == CodesDiagnosticsKotlin.REAFFECTATION_VAL })
        assertTrue(diagnostics.none { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE })
        session.close()
    }

    // ── Quick-fixes d'import ────────────────────────────────────────────

    @Test
    fun `importsCandidats - FQCN du classpath portant exactement le nom`() {
        val session = session()
        val source = "val v: TextView\n"
        val uri = "file:///fix.kt"
        session.ouvrirDocument(uri, source)
        val candidats = session.importsCandidats(uri, source.indexOf("TextView") + 1)
        assertEquals(listOf("android.widget.TextView"), candidats)
        session.close()
    }

    @Test
    fun `importsCandidats - deja importe, aucun candidat`() {
        val session = session()
        val source = "import android.widget.TextView\nval v: TextView\n"
        val uri = "file:///fix-importe.kt"
        session.ouvrirDocument(uri, source)
        val candidats = session.importsCandidats(uri, source.lastIndexOf("TextView") + 1)
        assertTrue("résolu par l'import : rien à proposer", candidats.isEmpty())
        session.close()
    }

    @Test
    fun `importsCandidats - document inconnu et sans classpath, vide`() {
        val session = session()
        assertTrue(session.importsCandidats("file:///jamais-ouvert.kt", 0).isEmpty())
        session.close()
        val nue = sessionNue()
        nue.ouvrirDocument("file:///nu.kt", "val v: TextView\n")
        assertTrue(
            "régime dégradé : pas de candidats",
            nue.importsCandidats("file:///nu.kt", 7).isEmpty(),
        )
        nue.close()
    }

    // ── Vue factice (univers de la suite K3) ────────────────────────────

    /**
     * Vue de classpath factice : TextView (android.widget, champ texte +
     * super View), kotlin.String (méthode length), paquets usuels.
     */
    private class VueClasspathFacticeK4 : VueClasspathKotlin {
        private val paquetsConnus: Set<String> =
            setOf("android", "android.widget", "android.view", "kotlin.text")

        private val membres: Map<String, jo.lspkotlin.api.MembresTypeKotlin> =
            mapOf(
                "android.widget.TextView" to
                    jo.lspkotlin.api.MembresTypeKotlin(
                        0x0021,
                        listOf(jo.lspkotlin.api.MembreJvmKotlin(0x0001, "texte", "Ljava/lang/CharSequence;")),
                        listOf(jo.lspkotlin.api.MembreJvmKotlin(0x0001, "setText", "(Ljava/lang/CharSequence;)V")),
                    ),
                "android.view.View" to
                    jo.lspkotlin.api.MembresTypeKotlin(
                        0x0021,
                        emptyList(),
                        listOf(jo.lspkotlin.api.MembreJvmKotlin(0x0001, "requestLayout", "()V")),
                    ),
                "kotlin.String" to
                    jo.lspkotlin.api.MembresTypeKotlin(
                        0x0011,
                        emptyList(),
                        listOf(jo.lspkotlin.api.MembreJvmKotlin(0x0001, "length", "()I")),
                    ),
            )

        private val superTypes: Map<String, String?> =
            mapOf(
                "android.widget.TextView" to "android.view.View",
                "android.view.View" to "java.lang.Object",
                "kotlin.String" to "java.lang.Object",
            )

        override fun nomsParPrefixe(prefixe: String, limite: Int): List<jo.lspkotlin.api.NomTypeKotlin> =
            if (prefixe == "TextV" || prefixe == "TextView") {
                listOf(jo.lspkotlin.api.NomTypeKotlin("TextView", listOf("android.widget.TextView")))
            } else {
                emptyList()
            }

        override fun typesDansPaquet(paquet: String): List<String> =
            when (paquet) {
                "android.widget" -> listOf("android.widget.TextView")
                else -> emptyList()
            }

        override fun estPaquet(nom: String): Boolean = nom in paquetsConnus

        override fun sousPaquetsDirects(nom: String): List<String> =
            when (nom) {
                "android" -> listOf("android.view", "android.widget")
                else -> emptyList()
            }

        override fun membres(fqcn: String): jo.lspkotlin.api.MembresTypeKotlin? = membres[fqcn]

        override fun octetsClasse(fqcn: String): ByteArray? = octetsSynthetiques(fqcn, superTypes[fqcn])

        /** Classe minimale (cf. LecteurClassBrutTest) au fil de l'eau. */
        private fun octetsSynthetiques(
            fqcn: String,
            superFqcn: String?,
        ): ByteArray {
            val sortie = ByteArrayOutputStream()
            DataOutputStream(sortie).use { flux ->
                flux.writeInt(0xCAFEBABE.toInt())
                flux.writeShort(0)
                flux.writeShort(52)
                val paires = 1 + (if (superFqcn != null) 1 else 0)
                flux.writeShort(paires * 2 + 1)
                fun utf8(texte: String) {
                    flux.writeByte(1)
                    val octets = texte.toByteArray(Charsets.UTF_8)
                    flux.writeShort(octets.size)
                    flux.write(octets)
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
    }
}
