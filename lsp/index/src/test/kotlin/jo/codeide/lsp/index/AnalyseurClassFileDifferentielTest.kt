package jo.codeide.lsp.index

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * TEST DIFFÉRENTIEL DU LECTEUR DE MEMBRES (mission fiabilisation,
 * étape A.3) — [AnalyseurClassFile] (l'extension `java.members` de
 * l'index, ADR 0111 §5 — ce que la VUE sert au moteur Kotlin) contre
 * la RÉFLEXION JDK sur DEUX corpus réels : android.jar (AOSP API 16)
 * et le jar kotlin-stdlib du classpath de test.
 *
 * Mesures (état initial, HEAD v1.15.0, 2026-10-26) :
 *  - android.jar : 1 007 classes `android.*` — 0 illisible, 0 écart
 *    de champs, 0 écart de méthodes ;
 *  - kotlin-stdlib : 681 classes `kotlin.*` — 0 illisible, 0 écart.
 *
 * Le lecteur de membres ne perd RIEN de ce que la réflexion voit —
 * la famille de bugs V1 (ADR 0219 : pool Long/Double, sections
 * membres perdues silencieusement) est éteinte sur ces corpus. Toute
 * divergence future de CE test est une régression du lecteur.
 *
 * Normalisations du CONTRAT (documentées, pas des tolérances) :
 *  - le lecteur lit les CONSTRUCTEURS (`<init>`, `<clinit>` — la vue
 *    JVM les expose au nom `<init>`) alors que la réflexion les rend
 *    par `getDeclaredConstructors`, pas `getDeclaredMethods` ;
 *  - l'agent de couverture Kover/IntelliJ INJECTE un champ `__$hits$__`
 *    dans les classes CHARGÉES par la JVM de test (javap : ABSENT du
 *    class file sur disque) — artefact de la référence, filtré ;
 *  - classes internes (`$`) et `module-info` hors périmètre.
 */
class AnalyseurClassFileDifferentielTest {
    /** Le jar kotlin-stdlib du classpath de test. */
    private fun jarStdlib(): File =
        File(
            kotlin.jvm.internal.Intrinsics::class.java.protectionDomain
                .codeSource.location
                .toURI(),
        )

    /** android.jar réel (AOSP API 16, Maven Central) — ancre android.util.Log. */
    private fun jarAndroid(): File =
        File(
            android.util.Log::class.java.protectionDomain
                .codeSource.location
                .toURI(),
        )

    private class Bilan(
        val total: Int,
        val illisibles: List<String>,
        val ecarts: List<String>,
    )

    /** Différentiel membres (champs + méthodes déclarés) lecteur contre réflexion.
     *
     * Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements
     * — les continue (interne `$`/hors préfixe, classe illisible) sont
     * les bornes du balayage différentiel.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun differentielMembres(
        jar: File,
        prefixe: String,
    ): Bilan {
        val illisibles = mutableListOf<String>()
        val ecarts = mutableListOf<String>()
        var total = 0
        ZipFile(jar).use { zip ->
            val entrees =
                zip
                    .entries()
                    .asSequence()
                    .filter { it.name.endsWith(".class") && it.name != "module-info.class" }
            for (entree in entrees) {
                val fqcn = entree.name.removeSuffix(".class").replace('/', '.')
                if ('$' in fqcn || !fqcn.startsWith(prefixe)) continue
                val octets = zip.getInputStream(entree).use { it.readBytes() }
                val analyse = AnalyseurClassFile.analyser(octets)
                total++
                if (analyse == null) {
                    illisibles += fqcn
                    continue
                }
                val reflechie = Class.forName(fqcn, false, javaClass.classLoader)
                val champsRef =
                    reflechie.declaredFields
                        .map { it.name }
                        .filterNot { it.startsWith("__$") }
                        .toSet()
                val methodesRef = reflechie.declaredMethods.map { it.name }.toSet()
                val champsLus = analyse.champs.map { it.nom }.toSet()
                val methodesLues =
                    analyse.methodes
                        .map { it.nom }
                        .filterNot { it == "<init>" || it == "<clinit>" }
                        .toSet()
                if (champsLus != champsRef) {
                    ecarts += "$fqcn: champs luEnTrop=${champsLus - champsRef} manque=${champsRef - champsLus}"
                }
                if (methodesLues != methodesRef) {
                    ecarts +=
                        "$fqcn: methodes luEnTrop=${methodesLues - methodesRef} manque=${methodesRef - methodesLues}"
                }
            }
        }
        return Bilan(total, illisibles, ecarts)
    }

    @Test
    fun `android jar - membres declares identiques a la reflexion, zero classe illisible`() {
        val bilan = differentielMembres(jarAndroid(), "android.")
        println("=== DIFFERENTIEL MEMBRES android.jar: ${bilan.total} classes")
        assertTrue("corpus chargé (au moins 500 classes) : ${bilan.total}", bilan.total >= 500)
        assertTrue("aucune classe illisible : ${bilan.illisibles.take(5)}", bilan.illisibles.isEmpty())
        assertTrue("aucun écart champs/methodes : ${bilan.ecarts.take(5)}", bilan.ecarts.isEmpty())
    }

    @Test
    fun `kotlin stdlib - membres declares identiques a la reflexion, zero classe illisible`() {
        val bilan = differentielMembres(jarStdlib(), "kotlin.")
        println("=== DIFFERENTIEL MEMBRES kotlin-stdlib: ${bilan.total} classes")
        assertTrue("corpus chargé (au moins 500 classes) : ${bilan.total}", bilan.total >= 500)
        assertTrue("aucune classe illisible : ${bilan.illisibles.take(5)}", bilan.illisibles.isEmpty())
        assertTrue("aucun écart champs/methodes : ${bilan.ecarts.take(5)}", bilan.ecarts.isEmpty())
    }
}
