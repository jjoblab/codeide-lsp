package jo.lspkotlin.session

import jo.lspkotlin.parse.ParseurKotlin
import jo.lspkotlin.parse.kt.KtFile
import jo.lspkotlin.resolve.DeclarationProjet
import jo.lspkotlin.resolve.SymbolesProjet
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/**
 * Scan des RACINES SOURCES du projet (K3, LSP_KOTLIN.md §4.1
 * « symbols » — « index de déclarations des sources projet » motif
 * projet tiers de référence, ici volontairement SIMPLE : un parcours complet, borné.
 *
 * CORRECTIF 0.134.32 (rapport « l'application buggue quand j'ouvre un
 * fichier .kt ») : le scan paresseux parsait TOUT le corpus de façon
 * SYNCHRONE à la PREMIÈRE requête après la sync — des secondes de CPU
 * + E/S sur le fil de la requête, tous les appels suivants (diagnostics,
 * complétion, survol) bloqués derrière. Deux leviers :
 *
 * 1. **Amorce WARM** : `definirRacines` lance le scan sur un fil DÉDIÉ
 *    AU MOMENT de la sync — le coût part des secondes avant la première
 *    frappe ; les lecteurs S'Y JOIGNENT ([declarations] attend la tâche
 *    en vol, jamais de double scan).
 * 2. **Incrémental par mtime** : un fichier inchangé n'est JAMAIS
 *    re-parsé (miroir ADR 0111 — le même jar inchangé n'est jamais
 *    redécodé) : chaque re-définition des racines (nouvelle sync) ne
 *    re-parse que les fichiers réellement modifiés — le « scan complet
 *    à chaque sync » n'existe plus.
 *
 * PARTAGÉ par le moteur (toutes les sessions d'un projet) : les
 * connexions LSP successives ne re-scannent pas ; le scan est
 * INVALIDÉ quand les racines changent — et les documents OUVERTS
 * dominent de toute façon côté session (un fichier édité vit son arbre
 * frais, le scan ne sert que les fichiers FERMÉS : la fraîcheur d'un
 * fichier fermé modifié hors éditeur est bornée à la prochaine sync,
 * écart documenté et accepté).
 *
 * URI des déclarations : `file://` + chemin FUSE (File.toURI) — la
 * cible de go-to-definition s'ouvre dans l'éditeur par le chemin
 * courant, même mécanique que le branchement LspEditor.
 *
 * K5 multi-langue (LSP_KOTLIN.md §5) : les racines portent les sources
 * Java AUSSI — [SourcesJava] en extrait les types de premier niveau
 * (résolution, complétion auto-import, survol — bascule Java↔Kotlin
 * dans un même projet) ; les déclarations portent le marqueur
 * `estJava` (le contrôle d'arité les ignore, D3).
 */
internal class ScanProjet(
    private val parseur: ParseurKotlin,
) {
    /** Racines courantes (identité d'invalidation). @Volatile : KP6. */
    @Volatile
    private var racines: List<File> = emptyList()

    /** Déclarations du dernier scan (vide avant le premier). @Volatile : KP6. */
    @Volatile
    private var scannees: List<DeclarationProjet> = emptyList()

    /** Verrou de la paire (racines, scannees, enVol) — scan unique sous verrou. */
    private val verrouScan = Any()

    /** Tâche WARM en vol (s'y joindre — [declarations] l'attend, jamais
     *  de re-scan concurrent du même travail). */
    private var enVol: FutureTask<List<DeclarationProjet>>? = null

    /**
     * Cache par FICHIER : chemin → (mtime, déclarations). Un fichier
     * inchangé n'est jamais re-parsé ; les fichiers DISPARUS sont
     * purgés à chaque passe (l'index ne ment jamais).
     */
    private val cacheFichiers =
        ConcurrentHashMap<String, Pair<Long, List<DeclarationProjet>>>()

    /** Fil UNIQUE du scan WARM (démon — la JVM ne l'attend pas). */
    private val executeur: ExecutorService =
        Executors.newSingleThreadExecutor { tache ->
            Thread(tache, "scan-projet-kotlin").apply { isDaemon = true }
        }

    /**
     * (Re)définit les racines — un appel aux MÊMES racines est un NO-OP
     * chaud (sync redondante) ; un changement INVALIDE le snapshot puis
     * AMORCE le scan sur le fil dédié (le coût part à la sync, jamais à
     * la première frappe — correctif 0.134.32).
     */
    internal fun definirRacines(nouvelles: List<File>) {
        synchronized(verrouScan) {
            if (nouvelles == racines) return
            racines = nouvelles
            scannees = emptyList()
        }
        amorcerScan()
    }

    /**
     * RAFRAÎCHIT le snapshot des déclarations (lot L1 du plan 1.3.0 —
     * un événement de fichier externe a signalé une modification hors
     * éditeur) : le snapshot est invalidé puis ré-amorcé sur le fil
     * dédié. Le CACHE PAR FICHIER (mtime) rend la passe DELTA-ONLY — un
     * fichier inchangé n'est jamais re-parsé, le coût est un listage.
     * Les documents OUVERTS restent dominants côté session (arbres
     * vivants) : la fraîcheur gagnée est celle des fichiers FERMÉS.
     */
    internal fun rafraichir() {
        if (racines.isEmpty()) return
        synchronized(verrouScan) {
            scannees = emptyList()
        }
        amorcerScan()
    }

    /**
     * Déclarations de toutes les racines — le snapshot COURANT, en
     * S'Y JOIGNANT au scan WARM en vol s'il y en a un (jamais de double
     * scan) ; sans tâche en vol et sans snapshot, le scan tolérable
     * historique (sous le fil appelant) reste le filet.
     *
     * @param exclureUri URI à EXCLURE (le document courant vit son
     *        propre arbre côté session — son doublon du scan serait
     *        périmé).
     */
    internal fun declarations(exclureUri: String? = null): List<DeclarationProjet> {
        scannerAuBesoin()
        val courantes = scannees
        if (exclureUri == null) return courantes
        return courantes.filter { it.uri != exclureUri }
    }

    /**
     * URIs des sources KOTLIN des racines (`file://` FUSE, même marche
     * bornée que le scan — S2 occurrences : le balayage des fichiers
     * fermés pour references/rename, avec le MÊME plafond
     * [BORN_FICHIERS]). Les `.kts` sont exclus (même convention que le
     * scan), les `.java` aussi (le moteur Kotlin ne collecte pas
     * d'occurrences dans les sources Java — sous-rapport D3 documenté).
     *
     * @param exclure URIs déjà couverts par ailleurs (documents ouverts
     *        — leurs arbres vivants dominent le disque).
     *
     * Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements
     * — le continue de l'extension et le return du plafond sont les
     * DEUX bornes du parcours (miroir de [parcourir]).
     */
    @Suppress("LoopWithTooManyJumpStatements")
    internal fun fichiersSources(exclure: Set<String> = emptySet()): List<String> {
        val resultat = mutableListOf<String>()
        for (racine in racines) {
            val fichiers = mutableListOf<File>()
            parcourir(racine, fichiers)
            for (fichier in fichiers) {
                if (fichier.extension != "kt") continue
                val uri = fichier.toURI().toString()
                if (uri in exclure) continue
                resultat.add(uri)
                if (resultat.size >= BORN_FICHIERS) return resultat
            }
        }
        return resultat
    }

    // ── Scan (WARM + filet synchrone) ─────────────────────────────

    /**
     * Amorce le scan des racines courantes sur le fil dédié : le coût
     * part AU MOMENT de la sync (definirRacines) — quand la première
     * requête lit, le travail est déjà en route, souvent fini. Un seul
     * vol à la fois (single-flight) ; les racines EMPTY n'amorcent pas.
     */
    private fun amorcerScan() {
        synchronized(verrouScan) {
            if (racines.isEmpty() || enVol != null) return
            val racinesDuScan = racines
            val tache =
                FutureTask<List<DeclarationProjet>> {
                    scanner(racinesDuScan)
                }
            enVol = tache
            executeur.execute {
                try {
                    tache.run()
                } finally {
                    poseResultat(tache, racinesDuScan)
                }
            }
        }
    }

    /**
     * Publie le résultat du scan WARM si les racines n'ont pas bougé
     * depuis le lancement (sinon le résultat est PÉRIMÉ — le snapshot
     * reste vide, la prochaine lecture ré-amorcera). La tâche tombe
     * dans tous les cas (une tâche finie ne doit jamais masquer un
     * nouveau vol).
     *
     * Exemption detekt ciblée (règle 16) : SwallowedException — la
     * tâche ne meurt que sur une panne hors donnée (scanner est
     * tolérant par fichier) ; l'absence de publication EST le signal,
     * le filet synchrone reprend (X4).
     */
    @Suppress("SwallowedException")
    private fun poseResultat(
        tache: FutureTask<List<DeclarationProjet>>,
        racinesDuScan: List<File>,
    ) {
        synchronized(verrouScan) {
            enVol = null
            if (racines == racinesDuScan) {
                scannees =
                    try {
                        tache.get()
                    } catch (t: Throwable) {
                        // La tâche n'est pas tombée sur une donnée
                        // (scanner est tolérant par fichier) — le filet
                        // synchrone reprendra. X4 : sous-rapport.
                        emptyList()
                    }
            }
        }
    }

    /**
     * Garantit un snapshot lisible : joint le WARM en vol s'il y en a
     * un, sinon re-scanne sous le verrou (filet historique — le fil
     * appelant paie le scan au plus UNE fois par invalidation, et le
     * cache mtime le rend court dès la seconde passe).
     *
     * Exemption detekt ciblée (règle 16) : SwallowedException — joindre
     * une tâche périmée/tombée n'est pas une perte : le filet
     * re-scanne (X4).
     */
    @Suppress("SwallowedException")
    private fun scannerAuBesoin() {
        if (racines.isEmpty()) return
        val enCours = synchronized(verrouScan) { enVol }
        if (enCours != null) {
            try {
                enCours.get()
            } catch (t: Throwable) {
                // Périmée ou tombée : le filet ci-dessous reprend.
            }
        }
        if (scannees.isNotEmpty()) return
        synchronized(verrouScan) {
            if (scannees.isEmpty()) scannees = scanner(racines)
        }
    }

    /**
     * Parcours complet des racines (fichiers .kt ET .java, ordre
     * déterministe), INCRÉMENTAL par mtime : un fichier inchangé rend
     * ses déclarations EN CACHE sans re-parse ; les fichiers disparus
     * sont purgés à la fin de la passe.
     *
     * SwallowedException ciblé (règle 16) : fichier illisible = absent
     * (D3) ; LoopWithTooManyJumpStatements ciblé (règle 16) : le
     * continue de la source Java et celui du fichier en cache sont les
     * branches du parcours multi-langue.
     */
    @Suppress("SwallowedException", "LoopWithTooManyJumpStatements")
    private fun scanner(racinesDuScan: List<File>): List<DeclarationProjet> {
        val resultat = mutableListOf<DeclarationProjet>()
        val vivants = mutableSetOf<String>()
        for (racine in racinesDuScan) {
            val fichiers = mutableListOf<File>()
            parcourir(racine, fichiers)
            for (fichier in fichiers) {
                val chemin = fichier.path
                vivants.add(chemin)
                val mtime = fichier.lastModified()
                val enCache = cacheFichiers[chemin]
                if (enCache != null && enCache.first == mtime) {
                    // Inchangé : JAMAIS re-parsé (miroir ADR 0111).
                    resultat += enCache.second
                    continue
                }
                val declarations = declarer(fichier) ?: continue
                cacheFichiers[chemin] = mtime to declarations
                resultat += declarations
            }
        }
        // L'index ne ment jamais : les fichiers disparus partent.
        cacheFichiers.keys.retainAll(vivants)
        return resultat
    }

    /**
     * Déclarations d'UN fichier (tolérant — un fichier illisible est
     * ABSENT du scan, partiel honnête D3) : `.java` par [SourcesJava]
     * (types de premier niveau, K5), `.kt` par le parseur vendoré.
     *
     * Exemption detekt ciblée (règle 16) : SwallowedException — un
     * fichier illisible est IGNORÉ, l'exception est le signal.
     */
    @Suppress("SwallowedException")
    private fun declarer(fichier: File): List<DeclarationProjet>? =
        try {
            if (fichier.extension == "java") {
                SourcesJava.declarations(fichier)
            } else {
                val arbre = parseur.analyser(fichier.readText(), script = false)
                val paquet = KtFile(arbre.racine).directivePackage()?.nomQualifie()
                SymbolesProjet.declarations(arbre.racine, fichier.toURI().toString(), paquet)
            }
        } catch (t: Throwable) {
            // Illisible : absent du scan (partiel honnête D3) — l'absence
            // EST le contrat, l'exception est le signal, pas une perte.
            null
        }

    /**
     * Marche récursive bornée (dossiers cachés/build exclus).
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount/LoopWithTooManyJump-
     * Statements — les bornes (fichiers au max, dossier illisible,
     * exclusion) sont des ISSUES du parcours borné.
     */
    @Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
    private fun parcourir(
        dossier: File,
        fichiers: MutableList<File>,
    ) {
        if (fichiers.size >= BORN_FICHIERS) return
        val enfants = dossier.listFiles() ?: return
        for (enfant in enfants.sortedBy { it.name }) {
            if (fichiers.size >= BORN_FICHIERS) return
            when {
                enfant.isDirectory ->
                    if (enfant.name !in EXCLUSIONS && !enfant.name.startsWith(".")) {
                        parcourir(enfant, fichiers)
                    }

                enfant.isFile &&
                    (enfant.extension == "kt" && !enfant.name.endsWith(".kts") ||
                        enfant.extension == "java") ->
                    fichiers.add(enfant)
            }
        }
    }

    private companion object {
        /** Borne de fichiers scannés (troncation honnête au-delà). */
        private const val BORN_FICHIERS: Int = 2000

        /** Dossiers exclus (sorties, caches — le scan est le SOURCE). */
        private val EXCLUSIONS: Set<String> = setOf("build", ".git", ".gradle")
    }
}
