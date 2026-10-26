package jo.lspkotlin.classpath

import jo.lspkotlin.api.KindEntreeKotlin
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.SortedSet
import java.util.zip.ZipFile

/**
 * TEST DIFFÉRENTIEL DU LECTEUR DE CLASSES (mission fiabilisation,
 * étape A.3) — [LecteurClassBrut] contre la RÉFLEXION JDK sur DEUX
 * corpus réels : android.jar (AOSP API 16, Maven Central — le SDK
 * Android est absent des environnements de test JVM) et le jar
 * kotlin-stdlib du classpath de test. Le prompt demande « réflexion
 * JDK pour les classes du JDK » : la réflexion sur classes CHARGÉES
 * (jamais initialisées, [Class.forName] à `initialize=false`) sert de
 * référence pour TOUTES les classes — aucune dépendance ASM n'entre
 * dans le module (entité indépendante D6).
 *
 * Mesures (état initial, HEAD v1.15.0, 2026-10-26) :
 *  - android.jar : 1 007 classes `android.*` — 0 illisible,
 *    0 écart de superclasse, 0 écart d'interfaces ;
 *  - kotlin-stdlib : 678 classes `kotlin.*` — 0 illisible, 0 écart.
 *
 * Le correctif ADR 0219 (skipBytes des 8 octets Long/Double du pool)
 * tient sur les deux corpus : le lecteur ne perd PLUS de classe. Toute
 * divergence future de CE test est une régression du lecteur — le bug
 * V1 (membres perdus sans erreur) ne doit jamais revenir.
 *
 * Normalisation du contrat (pas une tolérance de comparaison) :
 *  - le fichier de classe d'une INTERFACE porte super_class =
 *    java.lang.Object alors que la réflexion rend `getSuperclass()`
 *    null — l'attendu est normalisé côté référence ;
 *  - les classes INTERNES (`$`) et `module-info` sont hors périmètre
 *    (le lecteur les lit par ailleurs — LecteurClassBrutTest).
 *
 * Second volet : [DecodeurMetadonnees] — les appelables de premier
 * niveau du stdlib sont confrontés aux méthodes STATIQUES PUBLIQUES
 * des façades `*Kt` (réflexion). La comparaison par NOMS est
 * structurellement bruitée (les extensions vivent côté récepteur, les
 * propriétés top-level compilent en CHAMPS statiques, les typealias
 * n'ont pas de miroir bytecode, les synthétiques `$annotations` sont
 * privées) : le test ARRÊTE donc les invariants forts (volume,
 * appelables usuels présents, extensions réputées) et IMPRIME les
 * statistiques d'écart pour le rapport d'étape (aucune perte de
 * fonction réelle constatée — voir RAPPORT-FAUX-POSITIFS-2026-10-26).
 */
class LecteurClassBrutDifferentielTest {
    /** Le jar kotlin-stdlib du classpath de test. */
    private fun jarStdlib(): File =
        File(
            kotlin.jvm.internal.Intrinsics::class.java.protectionDomain
                .codeSource.location.toURI(),
        )

    /** android.jar réel (AOSP API 16, Maven Central) — ancre android.util.Log. */
    private fun jarAndroid(): File =
        File(
            android.util.Log::class.java.protectionDomain
                .codeSource.location.toURI(),
        )

    /** Classes de premier niveau (`$` exclu) d'un préfixe, dans un jar.
     *
     * Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements —
     * les continue (pas un .class, interne `$`, hors préfixe) sont les
     * bornes du balayage de corpus.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun classesDe(
        jar: File,
        prefixe: String,
    ): List<Pair<String, ByteArray>> {
        val octets = mutableListOf<Pair<String, ByteArray>>()
        ZipFile(jar).use { zip ->
            for (entree in zip.entries().asSequence()) {
                if (!entree.name.endsWith(".class") || entree.name == "module-info.class") continue
                val fqcn = entree.name.removeSuffix(".class").replace('/', '.')
                if ('$' in fqcn || !fqcn.startsWith(prefixe)) continue
                octets += fqcn to zip.getInputStream(entree).use { it.readBytes() }
            }
        }
        return octets
    }

    private class Bilan(
        val total: Int,
        val illisibles: List<String>,
        val ecarts: List<String>,
    )

    /** Différentiel structure (superclasse + interfaces) lecteur contre réflexion. */
    private fun differentielStructure(
        jar: File,
        prefixe: String,
    ): Bilan {
        val illisibles = mutableListOf<String>()
        val ecarts = mutableListOf<String>()
        var total = 0
        for ((fqcn, octets) in classesDe(jar, prefixe)) {
            val lue = LecteurClassBrut.lire(octets)
            total++
            if (lue == null) {
                illisibles += fqcn
                continue
            }
            val reflechie = Class.forName(fqcn, false, javaClass.classLoader)
            val attenduSuper = reflechie.superclass?.name ?: "java.lang.Object"
            if (lue.superFqcn != attenduSuper) {
                ecarts += "$fqcn: super lu=${lue.superFqcn} attendu=$attenduSuper"
            }
            val attenduesInterfaces = reflechie.interfaces.map { it.name }.sorted()
            if (lue.interfaces.sorted() != attenduesInterfaces) {
                ecarts += "$fqcn: interfaces lu=${lue.interfaces.sorted()} attendu=$attenduesInterfaces"
            }
        }
        return Bilan(total, illisibles, ecarts)
    }

    @Test
    fun `android jar - structure identique a la reflexion, zero classe illisible`() {
        val bilan = differentielStructure(jarAndroid(), "android.")
        println("=== DIFFERENTIEL STRUCTURE android.jar: ${bilan.total} classes")
        assertTrue("corpus chargé (au moins 500 classes) : ${bilan.total}", bilan.total >= 500)
        assertTrue("aucune classe illisible : ${bilan.illisibles.take(5)}", bilan.illisibles.isEmpty())
        assertTrue("aucun écart super/interfaces : ${bilan.ecarts.take(5)}", bilan.ecarts.isEmpty())
    }

    @Test
    fun `kotlin stdlib - structure identique a la reflexion, zero classe illisible`() {
        val bilan = differentielStructure(jarStdlib(), "kotlin.")
        println("=== DIFFERENTIEL STRUCTURE kotlin-stdlib: ${bilan.total} classes")
        assertTrue("corpus chargé (au moins 500 classes) : ${bilan.total}", bilan.total >= 500)
        assertTrue("aucune classe illisible : ${bilan.illisibles.take(5)}", bilan.illisibles.isEmpty())
        assertTrue("aucun écart super/interfaces : ${bilan.ecarts.take(5)}", bilan.ecarts.isEmpty())
    }

    /**
     * Méthodes STATIQUES PUBLIQUES des façades `*Kt` du stdlib — la
     * RÉFLEXION du volet métadonnées (initialisation jamais exécutée).
     */
    private fun statiquesDesFacades(jar: File): SortedSet<String> {
        val facades =
            ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.endsWith("Kt.class") }
                    .map { it.name.removeSuffix(".class").replace('/', '.') }
                    .filter { '$' !in it }
                    .toList()
            }
        val statiques = sortedSetOf<String>()
        for (fqcn in facades) {
            Class.forName(fqcn, false, javaClass.classLoader).declaredMethods
                .filter { m ->
                    java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                        java.lang.reflect.Modifier.isPublic(m.modifiers)
                }
                .forEach { m -> statiques += m.name }
        }
        return statiques
    }

    @Test
    fun `metadonnees stdlib - appelables usuels presents, extensions reputees, volume rendu`() {
        val jar = jarStdlib()
        val appelables = DecodeurMetadonnees.decoder(jar, KindEntreeKotlin.JAR)
        val noms = appelables.map { it.nom }.toSet()
        val recepteurs = appelables.filter { it.recepteur != null }.map { it.nom }.toSet()
        // Volume : le stdlib porte des milliers d'appelables déclarés.
        assertTrue("volume d'appelables lu (>= 3000) : ${appelables.size}", appelables.size >= 3000)
        // Fonctions de premier niveau USUELLES (aucune ne doit manquer).
        // NB : `arrayOf`/`intArrayOf` manquent de l'index — FAUX POSITIFS
        // constatés par ce différentiel, reproduits et documentés par
        // ReproductionAppelablesBuiltinTest (correctif étape B).
        val usuelles =
            listOf(
                "println", "print", "error", "require", "check", "TODO", "repeat", "with",
                "runCatching", "lazy", "listOf", "mapOf", "setOf", "emptyList",
                "maxOf", "minOf", "abs", "buildList", "sortedSetOf",
            )
        val manquantes = usuelles.filterNot { it in noms }
        assertTrue("fonctions top-level usuelles lues (manquantes: $manquantes)", manquantes.isEmpty())
        // Extensions RÉPUTÉES à récepteur CLASSE (récepteur non null côté métadonnées).
        val reputees = listOf("filter", "map", "forEach")
        val extensionsManquantes = reputees.filterNot { it in recepteurs }
        assertTrue("extensions réputées lues (manquantes: $extensionsManquantes)", extensionsManquantes.isEmpty())
        // Extensions à récepteur PARAMÈTRE DE TYPE (`fun <T> T.let(…)`) :
        // indexées comme appelables TOP-LEVEL du paquet `kotlin`
        // ([DecodeurMetadonnees.recepteurFqcn] — sous-rapport assumé K4) ;
        // les diagnostics les résolvent par la voie 6 + imports par défaut
        // (verrouillé par ReproductionExtensionsRecepteurNulTest).
        val reputeesT = listOf("let", "also", "apply", "run", "takeIf", "takeUnless")
        val topManquantes = reputeesT.filterNot { it in noms }
        assertTrue(
            "extensions à récepteur T lues comme top-level (manquantes: $topManquantes)",
            topManquantes.isEmpty(),
        )
        // Statistiques d'écart contre la référence (réflexion sur les
        // façades *Kt statiques publiques) — IMPRIMÉES pour le rapport,
        // la divergence par noms étant structurelle (propriétés → champs
        // statiques, typealias → aucun miroir, extensions → récepteur).
        val statiques = statiquesDesFacades(jar)
        val tops = appelables.filter { it.recepteur == null }.map { it.nom }.toSet()
        println(
            "=== DIFFERENTIEL METADONNEES stdlib: appelables=${appelables.size} " +
                "topDistincts=${tops.size} extensionsDistinctes=${recepteurs.size} " +
                "statiquesFacade=${statiques.size} horsLecteur=${statiques - tops} " +
                "horsFacade=${tops - statiques}",
        )
    }
}
