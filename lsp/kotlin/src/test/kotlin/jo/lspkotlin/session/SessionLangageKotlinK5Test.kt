package jo.lspkotlin.session

import jo.lspkotlin.api.CodesDiagnosticsKotlin
import jo.lspkotlin.api.ClasspathKotlin
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
import java.io.File

/**
 * Tests du contrat de session K5 (LSP_KOTLIN.md §5, K5 « classes
 * synthétiques R/ViewBinding/DataBinding, intégration éditeur
 * complète — complétion multi-langue, bascule Java↔Kotlin ») — par le
 * SPI (chemin de production D6), avec un PROJET ANDROID FACTICE sur
 * disque (manifeste + res/layout + res/values + res/drawable) et la
 * MÊME vue factice que la suite K4 (étendue : `setOnClickListener`
 * et son SAM `View$OnClickListener`) :
 *
 *  - SYNTHÉTIQUES : `R` (familles + constantes), ViewBinding (classe +
 *    champs), bindings TOUJOURS dans le sous-paquet `databinding`
 *    (AGP réel — ADR 0219 ; DataBinding y ajoute ses variables),
 *    `BuildConfig` (type seul), COUPURE par la classe générée réelle ;
 *  - MULTI-LANGUE : classe JAVA du projet visible de Kotlin
 *    (complétion, résolution, définition) et arité jamais contrôlée
 *    sur un constructeur Java ;
 *  - `with` : membres du récepteur implicite proposés et résolus ;
 *  - SAM du CLASSPATH : paramètre de lambda typé par l'attente
 *    (`setOnClickListener { vue -> vue.… }`).
 */
class SessionLangageKotlinK5Test {
    @JvmField
    @Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    /** Vue factice (univers de la suite K4, étendu K5). */
    private val vue = VueClasspathFacticeK5()

    /** Session nourrie du classpath factice + des racines données. */
    private fun session(racines: List<File>): jo.lspkotlin.api.SessionLangageKotlin {
        val session = ServiceMoteurLangageKotlin.unique().creerSession()
        session.definirClasspath(
            ClasspathKotlin(
                entrees = emptyList(),
                vue = vue,
                racinesSources = racines,
                dossierIndex = null,
            ),
        )
        return session
    }

    // ── Fixture : projet Android factice ──────────────────────────────

    /**
     * Sème un projet Android minimal et rend la racine source
     * `src/main/java` : manifeste `com.ex.app`, un layout ordinaire
     * (TextView + Button), un layout DataBinding (racine `<layout>` +
     * variable), `strings.xml`, un drawable.
     */
    private fun semerProjetAndroid(): File {
        val projet = dossierTemporaire.newFolder("projet")
        val srcMain = File(projet, "src/main")
        val racineJava = File(srcMain, "java")
        assertTrue(racineJava.mkdirs())
        File(srcMain, "AndroidManifest.xml").writeText(
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.ex.app"/>""",
        )
        val layout = File(srcMain, "res/layout")
        assertTrue(layout.mkdirs())
        File(layout, "activity_main.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="match_parent">
              <TextView android:id="@+id/titre" android:layout_width="wrap_content"
                  android:layout_height="wrap_content"/>
              <Button android:id="@+id/ok" android:layout_width="wrap_content"
                  android:layout_height="wrap_content"/>
            </LinearLayout>
            """.trimIndent(),
        )
        File(layout, "fragment_liste.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <layout xmlns:android="http://schemas.android.com/apk/res/android">
              <data>
                <variable name="modele" type="com.ex.app.Modele"/>
              </data>
              <FrameLayout android:layout_width="match_parent" android:layout_height="match_parent">
                <androidx.recyclerview.widget.RecyclerView android:id="@+id/liste"
                    android:layout_width="match_parent" android:layout_height="match_parent"/>
              </FrameLayout>
            </layout>
            """.trimIndent(),
        )
        val valeurs = File(srcMain, "res/values")
        assertTrue(valeurs.mkdirs())
        File(valeurs, "strings.xml").writeText(
            """<resources><string name="app_nom">Ex</string><string name="autre">A</string></resources>""",
        )
        val dessinable = File(srcMain, "res/drawable")
        assertTrue(dessinable.mkdirs())
        File(dessinable, "ic_logo.xml").writeText("""<selector/>""")
        return racineJava
    }

    // ── Synthétiques : R ───────────────────────────────────────────────

    @Test
    fun `R - familles proposees apres le point, layout string id drawable`() {
        val session = session(listOf(semerProjetAndroid()))
        val source = "import com.ex.app.R\nval x = R.\n"
        val uri = "file:///r-familles.kt"
        session.ouvrirDocument(uri, source)
        val propositions = session.completion(uri, source.length - 1)
        val libelles = propositions.map { it.libelle }
        for (attendu in listOf("layout", "id", "string", "drawable")) {
            assertTrue("la famille $attendu devait être proposée : $libelles", attendu in libelles)
        }
        session.close()
    }

    @Test
    fun `R - constantes des familles (layout, string, id) proposees`() {
        val session = session(listOf(semerProjetAndroid()))
        val source = "import com.ex.app.R\nval x = R.layout.\n"
        val uri = "file:///r-constantes.kt"
        session.ouvrirDocument(uri, source)
        val layouts = session.completion(uri, source.length - 1).map { it.libelle }
        assertTrue("activity_main devait être proposé : $layouts", "activity_main" in layouts)
        assertTrue("fragment_liste devait être proposé : $layouts", "fragment_liste" in layouts)

        val sourceId = "import com.ex.app.R\nval x = R.id.\n"
        session.ouvrirDocument(uri, sourceId)
        val ids = session.completion(uri, sourceId.length - 1).map { it.libelle }
        assertTrue("titre devait être proposé : $ids", "titre" in ids)
        assertTrue("ok devait être proposé : $ids", "ok" in ids)

        val sourceChaine = "import com.ex.app.R\nval x = R.string.\n"
        session.ouvrirDocument(uri, sourceChaine)
        val chaines = session.completion(uri, sourceChaine.length - 1).map { it.libelle }
        assertTrue("app_nom devait être proposé : $chaines", "app_nom" in chaines)
        session.close()
    }

    @Test
    fun `R - reference nue resolue, aucun kt unresolved`() {
        val session = session(listOf(semerProjetAndroid()))
        val source = "import com.ex.app.R\nval x = R.layout.activity_main\n"
        val uri = "file:///r-resolution.kt"
        session.ouvrirDocument(uri, source)
        val diagnostics = session.diagnostics(uri)
        assertTrue(
            "aucun diagnostic non résolu sur R : $diagnostics",
            diagnostics.none { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE },
        )
        session.close()
    }

    @Test
    fun `R - sans import, meme paquet que le manifeste (retour U2 - MainActivity)`() {
        val session = session(listOf(semerProjetAndroid()))
        // MainActivity vit dans le PAQUET du manifeste : R est en portée
        // SANS import (miroir Android Studio — jamais rouge sur R).
        val source =
            """
            package com.ex.app

            fun demo(): Int = R.string.app_nom
            """.trimIndent()
        val uri = "file:///r-meme-paquet.kt"
        session.ouvrirDocument(uri, source)
        val diagnostics = session.diagnostics(uri)
        assertTrue(
            "R sans import résout par le paquet du manifeste : $diagnostics",
            diagnostics.none { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE },
        )
        session.close()
    }

    // ── Synthétiques : ViewBinding / DataBinding / BuildConfig ─────────

    @Test
    fun `ViewBinding - classe synthetique et champs des ids proposes`() {
        val session = session(listOf(semerProjetAndroid()))
        val source =
            """
            import com.ex.app.databinding.ActivityMainBinding
            fun demo(i: android.view.LayoutInflater) {
              val b = ActivityMainBinding.inflate(i)
              b.
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///binding.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("b.") + 2
        val propositions = session.completion(uri, decalage)
        val parLibelle = propositions.associateBy { it.libelle }
        assertNotNull("le champ titre devait être proposé", parLibelle["titre"])
        assertNotNull("le champ ok devait être proposé", parLibelle["ok"])
        assertNotNull("root devait être proposé", parLibelle["root"])
        assertEquals(
            "le champ titre porte le type du porteur (TextView)",
            "TextView",
            parLibelle["titre"]?.detail,
        )
        session.close()
    }

    @Test
    fun `ViewBinding - le nom de classe propose en complétion de nom`() {
        val session = session(listOf(semerProjetAndroid()))
        val source = "fun demo() {\n  Activ\n}\n"
        val uri = "file:///binding-nom.kt"
        session.ouvrirDocument(uri, source)
        val propositions = session.completion(uri, source.indexOf("Activ") + 5)
        assertTrue(
            "ActivityMainBinding devait être proposé",
            propositions.any { it.libelle == "ActivityMainBinding" },
        )
        session.close()
    }

    @Test
    fun `DataBinding - classe du sous-paquet databinding et variable`() {
        val session = session(listOf(semerProjetAndroid()))
        val source =
            """
            import com.ex.app.databinding.FragmentListeBinding
            fun demo(i: android.view.LayoutInflater) {
              val b = FragmentListeBinding.inflate(i)
              b.
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///databinding.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("b.") + 2
        val propositions = session.completion(uri, decalage)
        val libelles = propositions.map { it.libelle }
        assertTrue("la variable modele devait être proposée : $libelles", "modele" in libelles)
        assertTrue("l'id liste devait être proposé : $libelles", "liste" in libelles)
        // Sans diagnostic non résolu sur l'import databinding.
        val diagnostics = session.diagnostics(uri)
        assertTrue(
            "aucun non résolu sur le binding databinding : $diagnostics",
            diagnostics.none { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE },
        )
        session.close()
    }

    @Test
    fun `ViewBinding - la definition ouvre le fichier de layout`() {
        val session = session(listOf(semerProjetAndroid()))
        val source = "import com.ex.app.databinding.ActivityMainBinding\nval b = ActivityMainBinding\n"
        val uri = "file:///binding-def.kt"
        session.ouvrirDocument(uri, source)
        val definitions = session.definitions(uri, source.lastIndexOf("ActivityMainBinding") + 3)
        assertEquals(
            "la définition du binding est son layout",
            1,
            definitions.count { it.uri.endsWith("activity_main.xml") },
        )
        session.close()
    }

    @Test
    fun `BuildConfig - type resolu, membres sous rapportes`() {
        val session = session(listOf(semerProjetAndroid()))
        val source = "import com.ex.app.BuildConfig\nval nom = BuildConfig\n"
        val uri = "file:///buildconfig.kt"
        session.ouvrirDocument(uri, source)
        val diagnostics = session.diagnostics(uri)
        assertTrue(
            "BuildConfig résout (aucun non résolu) : $diagnostics",
            diagnostics.none { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE },
        )
        session.close()
    }

    @Test
    fun `coupure - une classe R reelle du scan efface le synthetique`() {
        val racineJava = semerProjetAndroid()
        val paquetR = File(racineJava, "com/ex/app")
        assertTrue(paquetR.mkdirs())
        File(paquetR, "R.java").writeText("package com.ex.app;\n\npublic final class R {\n}\n")
        val session = session(listOf(racineJava))
        val source = "import com.ex.app.R\nval x = R.\n"
        val uri = "file:///coupure.kt"
        session.ouvrirDocument(uri, source)
        val propositions = session.completion(uri, source.length - 1)
        assertTrue(
            "la famille layout ne devait PLUS être proposée (classe réelle) : ${propositions.map { it.libelle }}",
            propositions.none { it.libelle == "layout" },
        )
        session.close()
    }

    @Test
    fun `sans manifeste - aucun synthetique, rien de faux`() {
        val racine = dossierTemporaire.newFolder("projet-sans-manifeste")
        val session = session(listOf(racine))
        val source = "val x = R.\n"
        val uri = "file:///sans-manifeste.kt"
        session.ouvrirDocument(uri, source)
        assertTrue(session.completion(uri, source.length - 1).none { it.libelle == "layout" })
        session.close()
    }

    // ── Multi-langue : Java↔Kotlin ─────────────────────────────────────

    /** Sème une source Java (classe publique) et rend sa racine. */
    private fun semerSourceJava(): File {
        val racine = dossierTemporaire.newFolder("sources-mixtes")
        val paquet = File(racine, "com/ex/app")
        assertTrue(paquet.mkdirs())
        File(paquet, "MaVue.java").writeText(
            """
            package com.ex.app;

            public class MaVue {
                public MaVue(int a, String b) {
                }
            }
            """.trimIndent(),
        )
        return racine
    }

    @Test
    fun `multi langue - la classe Java est proposee et resolue depuis Kotlin`() {
        val session = session(listOf(semerSourceJava()))
        val source = "package com.ex.app\n\nfun demo() {\n  val v = MaVue(1, \"x\")\n}\n"
        val uri = "file:///mixte.kt"
        session.ouvrirDocument(uri, source)
        // Résolution : MaVue n'est PAS non résolu (voie déclarations).
        val diagnostics = session.diagnostics(uri)
        assertTrue(
            "MaVue résout depuis Kotlin : $diagnostics",
            diagnostics.none { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE },
        )
        // Complétion : la classe Java est proposée (auto-import si hors paquet).
        val completion = "package com.ex.app\n\nfun demo() {\n  MaV\n}\n"
        session.ouvrirDocument(uri, completion)
        val propositions = session.completion(uri, completion.indexOf("MaV") + 3)
        assertTrue(
            "MaVue devait être proposée : ${propositions.map { it.libelle }}",
            propositions.any { it.libelle == "MaVue" },
        )
        session.close()
    }

    @Test
    fun `multi langue - l arite d un constructeur Java n est jamais controlee`() {
        val session = session(listOf(semerSourceJava()))
        val source = "package com.ex.app\n\nfun demo() {\n  val v = MaVue(1, \"x\", 2.0, null)\n}\n"
        val uri = "file:///arite-java.kt"
        session.ouvrirDocument(uri, source)
        val diagnostics = session.diagnostics(uri)
        assertTrue(
            "aucun contrôle d'arité sur un constructeur Java : $diagnostics",
            diagnostics.none { it.code == CodesDiagnosticsKotlin.NOMBRE_ARGUMENTS },
        )
        session.close()
    }

    @Test
    fun `multi langue - la definition d une reference Kotlin ouvre le java`() {
        val session = session(listOf(semerSourceJava()))
        val source = "package com.ex.app\n\nfun demo() {\n  val v = MaVue(1, \"x\")\n}\n"
        val uri = "file:///definition-java.kt"
        session.ouvrirDocument(uri, source)
        val definitions = session.definitions(uri, source.indexOf("MaVue") + 2)
        assertTrue(
            "la définition pointe le fichier .java : $definitions",
            definitions.size == 1 && definitions[0].uri.endsWith("MaVue.java"),
        )
        session.close()
    }

    // ── Multi-langue : MEMBRES Java (retour utilisateurs 25/10/2026, U2) ──

    /** Sème le POJO Java de Marc : champs, getters/setters, toString annoté, statique. */
    private fun semerVinyleJava(paquet: File) {
        File(paquet, "Vinyle.java").writeText(
            """
            package com.ex.app;

            import java.util.ArrayList;
            import java.util.List;

            public class Vinyle {

                private String titre;
                private int annee;

                public Vinyle(String titre, int annee) {
                    this.titre = titre;
                    this.annee = annee;
                }

                public String getTitre() {
                    return titre;
                }

                public void setTitre(String titre) {
                    this.titre = titre;
                }

                public int getAnnee() {
                    return annee;
                }

                @Override
                public String toString() {
                    return titre + " (" + annee + ")";
                }

                public static List<Vinyle> catalogueDemo() {
                    List<Vinyle> bac = new ArrayList<>();
                    bac.add(new Vinyle("Kind of Blue", 1959));
                    return bac;
                }
            }
            """.trimIndent(),
        )
    }

    /** Sème l'utilitaire statique de Marc (constructeur privé). */
    private fun semerNormaliseurTitreJava(paquet: File) {
        File(paquet, "NormaliseurTitre.java").writeText(
            """
            package com.ex.app;

            public final class NormaliseurTitre {

                private NormaliseurTitre() {
                }

                public static String normaliser(String texte) {
                    return texte.trim().toLowerCase();
                }
            }
            """.trimIndent(),
        )
    }

    /** Sème le monde mixte de Marc : POJO Java + utilitaire statique. */
    private fun semerSourcesJavaAvecMembres(): File {
        val racine = dossierTemporaire.newFolder("src-membres")
        val paquet = File(racine, "com/ex/app")
        assertTrue(paquet.mkdirs())
        semerVinyleJava(paquet)
        semerNormaliseurTitreJava(paquet)
        return racine
    }

    @Test
    fun `multi langue - membres Java resolus depuis Kotlin (getters, statiques, propriete synthetique)`() {
        val session = session(listOf(semerSourcesJavaAvecMembres()))
        val source =
            """
            package com.ex.app

            fun demo(v: Vinyle) {
                val t = v.getTitre()
                val a = v.annee
                val bac = Vinyle.catalogueDemo()
                val n = NormaliseurTitre.normaliser(t)
                val affiche = "bac " + bac.size + " pour " + n + " " + a
            }
            """.trimIndent()
        val uri = "file:///membres-java.kt"
        session.ouvrirDocument(uri, source)
        val diagnostics = session.diagnostics(uri)
        assertTrue(
            "les membres Java résolvent (getTitre, annee, catalogueDemo, normaliser) : $diagnostics",
            diagnostics.none { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE },
        )
        session.close()
    }

    @Test
    fun `multi langue - membres Java proposes en completion apres le point`() {
        val session = session(listOf(semerSourcesJavaAvecMembres()))
        val source =
            """
            package com.ex.app

            fun demo(v: Vinyle) {
                v.ge
            }
            """.trimIndent()
        val uri = "file:///completion-membres-java.kt"
        session.ouvrirDocument(uri, source)
        val propositions = session.completion(uri, source.indexOf("v.ge") + 4)
        assertTrue(
            "getTitre/getAnnee devaient être proposés : ${propositions.map { it.libelle }}",
            propositions.any { it.libelle.startsWith("getTitre") } &&
                propositions.any { it.libelle.startsWith("getAnnee") },
        )
        val sourceStatique =
            """
            package com.ex.app

            fun demo() {
                NormaliseurTitre.nor
            }
            """.trimIndent()
        session.ouvrirDocument(uri, sourceStatique)
        val propositionsStatiques = session.completion(uri, sourceStatique.indexOf(".nor") + 4)
        assertTrue(
            "normaliser devait être proposé : ${propositionsStatiques.map { it.libelle }}",
            propositionsStatiques.any { it.libelle.startsWith("normaliser") },
        )
        session.close()
    }

    @Test
    fun `multi langue - le typage traverse un getter Java (chaine continue)`() {
        val session = session(listOf(semerSourcesJavaAvecMembres()))
        val source =
            """
            package com.ex.app

            fun demo(v: Vinyle) {
                v.getTitre().length
            }
            """.trimIndent()
        val uri = "file:///typage-java.kt"
        session.ouvrirDocument(uri, source)
        // getTitre() rend String : le SEGMENT .length qui suit résout —
        // si le getter n'était pas typé, la chaîne serait muette ET
        // « length » non résolu.
        val diagnostics = session.diagnostics(uri)
        assertTrue(
            "la chaîne v.getTitre().length résout : $diagnostics",
            diagnostics.none { it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE },
        )
        session.close()
    }

    // ── Récepteur implicite `with` ─────────────────────────────────────

    @Test
    fun `with - membres du recepteur implicite proposes sans selecteur`() {
        val session = session(emptyList())
        val source =
            """
            import android.widget.TextView
            fun demo(v: TextView) {
              with(v) {
                te
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///with.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("te") + 2
        val propositions = session.completion(uri, decalage)
        assertTrue(
            "le champ texte du récepteur implicite devait être proposé : ${propositions.map { it.libelle }}",
            propositions.any { it.libelle == "texte" },
        )
        session.close()
    }

    @Test
    fun `with - un membre nu du recepteur n est pas non resolu`() {
        val session = session(emptyList())
        val source =
            """
            import android.widget.TextView
            fun demo(v: TextView) {
              with(v) {
                texte = null
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///with-resolution.kt"
        session.ouvrirDocument(uri, source)
        val diagnostics = session.diagnostics(uri)
        assertTrue(
            "le membre nu texte résout (récepteur with) : $diagnostics",
            diagnostics.none {
                it.code == CodesDiagnosticsKotlin.REFERENCE_NON_RESOLUE && it.message.contains("texte")
            },
        )
        session.close()
    }

    @Test
    fun `with - le recepteur implicite traverse les literaux emboites`() {
        val session = session(emptyList())
        val source =
            """
            import android.widget.TextView
            fun demo(v: TextView, action: (TextView) -> Unit) {
              with(v) {
                action { te
                }
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///with-emboite.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.lastIndexOf("te") + 2
        // Sémantique Kotlin : le récepteur implicite d'un `with`
        // englobant reste EN PORTEE dans les littéraux emboîtés — les
        // membres de `v` restent proposés au corps profond.
        val propositions = session.completion(uri, decalage)
        assertTrue(
            "le récepteur implicite traverse le littéral emboîté : ${propositions.map { it.libelle }}",
            propositions.any { it.libelle == "texte" },
        )
        session.close()
    }

    // ── SAM du classpath : attente du lambda ───────────────────────────

    @Test
    fun `SAM classpath - le parametre du lambda est type par l attente`() {
        val session = session(emptyList())
        val source =
            """
            import android.widget.TextView
            fun demo(v: TextView) {
              v.setOnClickListener { vue ->
                vue.
              }
            }
            """.trimIndent().replace("    ", "  ")
        val uri = "file:///sam.kt"
        session.ouvrirDocument(uri, source)
        val decalage = source.indexOf("vue.") + 4
        val propositions = session.completion(uri, decalage)
        assertTrue(
            "les membres de View (paramètre du SAM) devaient être proposés : ${propositions.map { it.libelle }}",
            propositions.any { it.libelle == "requestLayout()" },
        )
        session.close()
    }

    // ── Vue factice (univers K4 + SAM K5) ──────────────────────────────

    /**
     * Vue de classpath factice : TextView (champ texte + super View),
     * View (requestLayout + setOnClickListener au SAM
     * `View$OnClickListener`), kotlin.String.
     */
    private class VueClasspathFacticeK5 : VueClasspathKotlin {
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
                        listOf(
                            jo.lspkotlin.api.MembreJvmKotlin(0x0001, "requestLayout", "()V"),
                            jo.lspkotlin.api.MembreJvmKotlin(
                                0x0001,
                                "setOnClickListener",
                                "(Landroid/view/View\$OnClickListener;)V",
                            ),
                        ),
                    ),
                "android.view.View\$OnClickListener" to
                    jo.lspkotlin.api.MembresTypeKotlin(
                        0x0201,
                        emptyList(),
                        listOf(
                            jo.lspkotlin.api.MembreJvmKotlin(
                                0x0401,
                                "onClick",
                                "(Landroid/view/View;)V",
                            ),
                        ),
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
                "android.view.View\$OnClickListener" to "java.lang.Object",
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
