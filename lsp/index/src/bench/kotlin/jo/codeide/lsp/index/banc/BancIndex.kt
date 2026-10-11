package jo.codeide.lsp.index.banc

import jo.codeide.lsp.index.EtatIndex
import jo.codeide.lsp.index.PhaseIndex
import jo.codeide.lsp.index.ServiceIndexDisque
import jo.codeide.lsp.index.VueIndex
import jo.codeide.lsp.model.ClasspathInstantane
import jo.codeide.lsp.model.Empreintes
import jo.codeide.lsp.model.EntreeClasspathInstantane
import jo.codeide.lsp.model.KindEntree
import jo.codeide.lsp.model.LecteurStatistiquesSysteme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.system.measureNanoTime

/** Point d'entrée du banc (tâche :lsp:index:bench). */
fun main(args: Array<String>) = BancIndex.executer(args)

/**
 * BANC de l'index disque (ADR 0111 — « banc avant/après sur les trois
 * jeux »). JVM bornée (-Xmx2g, SerialGC) par la tâche :lsp:index:bench.
 *
 * Mesures par jeu (petit/moyen/lourd, recette scripts/lsp-index) :
 *  1. index FROID — première construction de tous les segments ;
 *  2. REPRISE — ré-ouverture du même classpath (cible < 1,5 s) ;
 *  3. +1 DÉPENDANCE — construction après ajout d'un jar hors manifestes
 *     (cible : SEUL le nouveau jar est indexé) ;
 *  4. REQUÊTES — localisation négative (pire cas : tous les segments
 *     consultés), complétion par préfixe, paquets, types de paquet,
 *     membres statiques — p50/p95 sur 30 itérations ;
 *  5. budget du cache LRU (octets comptés, cible < 30 Mo).
 *
 * La sortie (table + JSON) va dans build/lsp-index/banc-resultats.json.
 * La sortie standard EST le résultat du banc (outil de mesure, pas du
 * code applicatif — la règle 14 de journalisation ne s'y applique pas).
 */
@Suppress("TooManyFunctions")
object BancIndex {
    private const val ITERATIONS = 30
    private const val ATTENTE_CONSTRUCTION_MS = 300_000L

    /** Une ligne de résultat par jeu. */
    private data class Resultat(
        val jeu: String,
        val entrees: Int,
        val froidMs: Long,
        val repriseMs: Long,
        val plusUnMs: Long,
        val localiserNégatifP50Ms: Double,
        val localiserNégatifP95Ms: Double,
        val prefixeP50Ms: Double,
        val prefixeP95Ms: Double,
        val paquetsP50Ms: Double,
        val typesPaquetP50Ms: Double,
        val membresStatiquesP50Ms: Double,
        val octetsCache: Long,
        val phaseFinale: String,
    )

    fun executer(args: Array<String>) {
        val racineJeux = File(args.getOrElse(0) { "build/lsp-index/jeux" })
        val sortie = File(args.getOrElse(1) { "build/lsp-index/banc-resultats.json" })
        val resultats = mutableListOf<Resultat>()

        for (jeu in listOf("petit", "moyen", "lourd")) {
            val manifeste = File(racineJeux, "$jeu/manifeste.txt")
            if (!manifeste.isFile) {
                System.out.println("[banc] jeu '$jeu' absent (${manifeste.path}) — ignoré")
                continue
            }
            resultats += mesurer(jeu, manifeste)
        }

        imprimerTable(resultats)
        sortie.parentFile?.mkdirs()
        sortie.writeText(JSONEncoder.encoder(resultats))
        System.out.println("[banc] résultats JSON : ${sortie.path}")
    }

    /** Toutes les mesures d'un jeu. */
    private fun mesurer(
        jeu: String,
        manifeste: File,
    ): Resultat {
        val chemins = manifeste.readLines().filter { it.isNotBlank() && File(it).isFile }
        val stockage = File("build/lsp-index/banc-stockage/$jeu")
        stockage.deleteRecursively()
        stockage.mkdirs()
        val extra = File(racineJeux(manifeste), "extra/extra-banc.jar")

        val scope = CoroutineScope(Dispatchers.IO + Job())
        val service =
            ServiceIndexDisque(
                repertoire = stockage,
                scope = scope,
                dispatcheurIo = Dispatchers.IO,
            )

        // 1. FROID — tout est construit.
        val classpath = classpathDe(chemins)
        val froid = constructionMs(service, classpath).first

        // 2. REPRISE — ré-ouverture à chaud.
        val reprise = constructionMs(service, classpath).first

        // 3. +1 DÉPENDANCE — seul le nouveau jar est indexé.
        val plusUn = constructionMs(service, classpathDe(chemins + extra.path)).first

        // 4. REQUÊTES sur la vue complète (construite).
        val vue = service.vuePour(classpathDe(chemins + extra.path))
        attendrePrete(construire(service, vue))
        val localiser = mesurerEchantillon { vue.localiser("absent.$jeu.Inconnu") }
        val prefixe = mesurerEchantillon { vue.nomsParPrefixe("Co") }
        val paquets = mesurerEchantillon { vue.estPaquet("java.lang") }
        val typesPaquet = mesurerEchantillon { vue.typesDansPaquet("kotlin.collections") }
        val membres = mesurerEchantillon { vue.membresStatiques("java.lang.Math") }

        scope.cancel()
        return Resultat(
            jeu = jeu,
            entrees = chemins.size,
            froidMs = froid,
            repriseMs = reprise,
            plusUnMs = plusUn,
            localiserNégatifP50Ms = localiser.first,
            localiserNégatifP95Ms = localiser.second,
            prefixeP50Ms = prefixe.first,
            prefixeP95Ms = prefixe.second,
            paquetsP50Ms = paquets.first,
            typesPaquetP50Ms = typesPaquet.first,
            membresStatiquesP50Ms = membres.first,
            octetsCache = service.octetsCache,
            phaseFinale = vue.etat.value.phase.name,
        )
    }

    /** Construction complète : (durée ms, vue construite et vérifiée). */
    private fun constructionMs(
        service: ServiceIndexDisque,
        classpath: ClasspathInstantane,
    ): Pair<Long, VueIndex> {
        val vue = service.vuePour(classpath)
        val nano =
            measureNanoTime {
                runBlocking {
                    withTimeout(ATTENTE_CONSTRUCTION_MS) {
                        service.construire(vue).join()
                    }
                }
            }
        attendrePrete(vue.etat.value)
        return Pair(nano / 1_000_000, vue)
    }

    /** Construit et renvoie l'état final. */
    private fun construire(
        service: ServiceIndexDisque,
        vue: VueIndex,
    ): EtatIndex {
        runBlocking {
            withTimeout(ATTENTE_CONSTRUCTION_MS) {
                service.construire(vue).join()
            }
        }
        return vue.etat.value
    }

    /** Exige PRÊT/PARTIEL (jamais ERREUR — le banc échouerait sinon). */
    private fun attendrePrete(etat: EtatIndex) {
        check(etat.phase == PhaseIndex.PRET || etat.phase == PhaseIndex.PARTIEL) {
            "construction en échec : ${etat.erreurs}"
        }
    }

    /** p50/p95 en ms d'une requête répétée (échauffement inclus). */
    private fun mesurerEchantillon(requete: () -> Unit): Pair<Double, Double> {
        repeat(5) { requete() } // échauffement (cache LRU rempli)
        val durees = LongArray(ITERATIONS) { measureNanoTime { requete() } }
        val triees = durees.sorted()
        val p50 = triees[triees.size / 2] / 1_000_000.0
        val p95 = triees[(triees.size * 95) / 100] / 1_000_000.0
        return Pair(p50, p95)
    }

    /** Classpath instantané depuis des chemins de jars. */
    private fun classpathDe(chemins: List<String>): ClasspathInstantane {
        val stats = LecteurStatistiquesSysteme()
        val entrees =
            chemins.map { chemin ->
                val fichier = File(chemin)
                EntreeClasspathInstantane(
                    kind = if (chemin.endsWith(".aar")) KindEntree.AAR else KindEntree.JAR,
                    chemin = chemin,
                    portee = null,
                    sources = null,
                    aar = null,
                    empreinte = Empreintes.empreinteFichier(chemin, stats),
                    existe = fichier.isFile,
                    taille = fichier.length(),
                    mtime = fichier.lastModified(),
                )
            }
        return ClasspathInstantane(entrees = entrees, empreinte = "banc")
    }

    /** Racine des jeux depuis un manifeste. */
    private fun racineJeux(manifeste: File): File = manifeste.parentFile?.parentFile ?: File(".")

    /** Table lisible du rapport. */
    private fun imprimerTable(resultats: List<Resultat>) {
        System.out.println("=== BANC lsp:index (ADR 0111) — avant/après ===")
        System.out.println(
            "jeu      | entrées | froid ms | reprise ms | +1 dép. ms | localiser p50/p95 ms | préfixe p50/p95 ms | cache Mo | état",
        )
        for (r in resultats) {
            System.out.println(
                "%-8s | %7d | %8d | %10d | %10d | %9.2f/%5.2f  | %9.2f/%5.2f  | %8.1f | %s".format(
                    r.jeu,
                    r.entrees,
                    r.froidMs,
                    r.repriseMs,
                    r.plusUnMs,
                    r.localiserNégatifP50Ms,
                    r.localiserNégatifP95Ms,
                    r.prefixeP50Ms,
                    r.prefixeP95Ms,
                    r.octetsCache / 1_048_576.0,
                    r.phaseFinale,
                ),
            )
        }
        System.out.println(
            "Références AVANT (étude §4, JVM) : reconstruction 98/219/794 ms ; +1 dépendance 257/396/1 178 ms.",
        )
        System.out.println(
            "Cibles ADR 0111 : +1 dépendance = indexation du SEUL nouveau jar ; reprise < 1,5 s ; cache < 30 Mo.",
        )
    }

    /** Encodage JSON minimal (aucune dépendance au banc). */
    private object JSONEncoder {
        fun encoder(resultats: List<Resultat>): String {
            val lignes =
                resultats.joinToString(",\n") { r ->
                    """  {"jeu": "${r.jeu}", "entrees": ${r.entrees}, "froidMs": ${r.froidMs},
    "repriseMs": ${r.repriseMs}, "plusUnDependanceMs": ${r.plusUnMs},
    "localiserNegatifP50Ms": ${r.localiserNégatifP50Ms}, "localiserNegatifP95Ms": ${r.localiserNégatifP95Ms},
    "prefixeP50Ms": ${r.prefixeP50Ms}, "prefixeP95Ms": ${r.prefixeP95Ms},
    "paquetsP50Ms": ${r.paquetsP50Ms}, "typesPaquetP50Ms": ${r.typesPaquetP50Ms},
    "membresStatiquesP50Ms": ${r.membresStatiquesP50Ms}, "octetsCache": ${r.octetsCache},
    "phaseFinale": "${r.phaseFinale}"}""".replace("\n", " ")
                }
            return "[\n$lignes\n]\n"
        }
    }
}
