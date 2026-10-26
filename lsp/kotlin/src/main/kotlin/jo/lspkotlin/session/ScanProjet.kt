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
 * SYNCHRONE à la PREMIÈRE requête après la sync. Deux leviers
 * historiques : l'amorce WARM à la sync et l'incrémental par mtime.
 *
 * ADR 0212 (demande D2 de CodeIDE) — le FILET SYNCHRONE est SUPPRIMÉ :
 * plus AUCUN chemin de requête ne parse le projet sur le fil appelant.
 * `declarations()` JOINT le WARM en vol (attente bornée par le fil de
 * fond lancé à la sync), sinon rend le snapshot courant — et si un
 * passage n'a pas encore abouti pour les racines courantes, AMORCE le
 * WARM et rend le snapshot (sous-lecture honnête d'UNE requête, « une
 * requête précoce répond honnêtement moins riche, jamais en bloquant
 * tout le serveur »). Le drapeau [pret] distingue « un passage a
 * ABOUTI pour ces racines » (même résultat VIDE — un projet sans `.kt`
 * est un état légitime, pas une invalidation) d'un snapshot vide par
 * invalidation : sans lui, chaque requête ré-amorcerait un WARM.
 *
 * ADR 0212/0213 — le scanner MARCHE D'ABORD puis PARSE : le listage
 * précède le traitement, la progression devient un VRAI X/N (total
 * connu) et l'observation par fichier ([ObservationScan]) alimente
 * l'écran d'indexation ; retour `false` de l'observateur = ANNULATION
 * coopérative (ADR 0214) : le passage s'arrête ENTRE deux fichiers,
 * publie son PARTIEL et se marque `pret` (l'acquis est exploitable,
 * la reprise ADR 0214 le complète).
 *
 * ÉTAPE B (mission fiabilisation, H1 — rapport faux positifs
 * 2026-10-26 §7.1) : la MARCHE est honnête — seule la COLLECTE des
 * fichiers à traiter est bornée ([BORN_FICHIERS]), le COMPTE des
 * fichiers éligibles ne l'est PAS ([StatsPassage.total] rend le total
 * RÉEL) ; un passage TRONQUÉ porte [tronque] et [estUniversConnu]
 * rend FAUX (un univers tronqué n'est pas un univers CONNU — la
 * preuve d'absence projet est coupée, miroir de la garde vague 3).
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
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — UN verbe par
 * demande de CodeIDE (lire/rafraîchir D2, observer/attendre/stats D1
 * et D3, l'amorce et le scan WARM) : le compte épouse le CONTRAT
 * ADR 0212/0213, pas une dérive (même patron que
 * [jo.lspkotlin.index.IndexAppelablesKotlin]).
 */
@Suppress("TooManyFunctions")
internal class ScanProjet(
    private val parseur: ParseurKotlin,
    /**
     * Borne de la marche (couture de test — mission fiabilisation,
     * étape A.2 : reproduire la troncation à [BORN_FICHIERS] sans
     * semer 2000 fichiers ; la production garde le défaut).
     */
    private val bornFichiers: Int = BORN_FICHIERS,
) {
    /** Racines courantes (identité d'invalidation). @Volatile : KP6. */
    @Volatile
    private var racines: List<File> = emptyList()

    /** Déclarations du dernier scan (vide avant le premier). @Volatile : KP6. */
    @Volatile
    private var scannees: List<DeclarationProjet> = emptyList()

    /**
     * Un passage a-t-il ABOUTI pour les racines courantes (ADR 0212) ?
     * Posé à la publication d'un passage dont les racines sont les
     * courantes (même VIDE/PARTIEL), abaissé à chaque invalidation
     * (nouvelles racines, rafraîchissement).
     */
    @Volatile
    private var pret = false

    /**
     * Le dernier passage PUBLIÉ était-il TRONQUÉ (des fichiers
     * éligibles vivent derrière [BORN_FICHIERS]) ? Étape B (H1) : un
     * univers tronqué n'est PAS un univers CONNU — [estUniversConnu]
     * coupe la preuve d'absence. Publié sous le MÊME verrou que
     * `scannees`/`pret` (cohérence de la paire). @Volatile : KP6.
     */
    @Volatile
    private var tronque = false

    /** Statistiques du dernier passage (progression, re-parses). */
    @Volatile
    private var statsPassage = StatsPassage(0, 0, 0)

    /**
     * Marche récursive des racines (dossiers cachés/build exclus) —
     * la COLLECTE des fichiers à TRAITER est bornée, le COMPTE des
     * fichiers éligibles ne l'est PAS (étape B, H1 : statistiques
     * honnêtes, troncation observable).
     */
    private class Marche(private val borne: Int) {
        /** Fichiers à TRAITER (parse, cache — collecte bornée). */
        internal val fichiers = mutableListOf<File>()

        /** Total de fichiers éligibles VUS (compte NON borné — honnête). */
        internal var vus = 0
            private set

        /** Des fichiers éligibles vivent-ils derrière la borne ? */
        internal val tronque: Boolean
            get() = vus > fichiers.size

        /** Compte UN fichier éligible ; le collecte si la borne le permet. */
        internal fun ajouter(fichier: File) {
            vus++
            if (fichiers.size < borne) fichiers.add(fichier)
        }
    }

    /** Résultat d'un passage : déclarations + TRONCATION de la marche. */
    private class ResultatScan(
        internal val declarations: List<DeclarationProjet>,
        internal val tronque: Boolean,
    )

    /**
     * Observateur du passage (ADR 0213) — posé par le moteur avant
     * d'amorcer, `null` = silence (sessions seules). Consulté entre
     * chaque fichier ; son retour `false` ANNULE le passage (ADR 0214).
     */
    @Volatile
    private var observateur: ObservationScan? = null

    /** Verrou de la paire (racines, scannees, enVol) — scan unique sous verrou. */
    private val verrouScan = Any()

    /** Tâche WARM en vol (s'y joindre — [declarations] l'attend, jamais
     *  de re-scan concurrent du même travail). */
    private var enVol: FutureTask<ResultatScan>? = null

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
     * Observation du passage de scan (ADR 0213/0214) — appelée APRÈS
     * chaque fichier traité, retour `false` = annulation coopérative
     * (le passage s'arrête avant le fichier suivant, l'acquis est
     * publié).
     */
    internal fun interface ObservationScan {
        fun surFichier(
            faits: Int,
            total: Int,
            nom: String,
        ): Boolean
    }

    /** Statistiques d'un passage de scan (bilan ADR 0213).
     *
     * [total] est le total RÉEL des fichiers éligibles VUS (étape B,
     * H1 — la marche compte AU-DELÀ de la borne) : `faits < total`
     * signale une troncation ou une annulation, jamais un petit projet
     * sain déguisé. */
    internal class StatsPassage(
        internal val faits: Int,
        internal val total: Int,
        internal val reanalyses: Int,
    )

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
            pret = false
            tronque = false
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
            pret = false
            tronque = false
        }
        amorcerScan()
    }

    /**
     * Déclarations de toutes les racines — le snapshot COURANT après
     * [assurerSnapshot] : JOINT le WARM en vol s'il y en a un, sinon
     * rend l'état publié (jamais de scan sur le fil appelant — ADR
     * 0212, le filet historique est supprimé).
     *
     * @param exclureUri URI à EXCLURE (le document courant vit son
     *        propre arbre côté session — son doublon du scan serait
     *        périmé).
     */
    internal fun declarations(exclureUri: String? = null): List<DeclarationProjet> {
        assurerSnapshot()
        val courantes = scannees
        if (exclureUri == null) return courantes
        return courantes.filter { it.uri != exclureUri }
    }

    /**
     * URIs des sources KOTLIN des racines (`file://` FUSE, même marche
     * que le scan — S2 occurrences : le balayage des fichiers fermés
     * pour references/rename, avec le MÊME plafond de collecte que la
     * marche du scan). Les `.kts` sont exclus (même convention que le
     * scan), les `.java` aussi (le moteur Kotlin ne collecte pas
     * d'occurrences dans les sources Java — sous-rapport D3 documenté).
     *
     * @param exclure URIs déjà couverts par ailleurs (documents ouverts
     *        — leurs arbres vivants dominent le disque).
     *
     * Exemption detekt ciblée (règle 16) : LoopWithTooManyJumpStatements
     * — le continue de l'extension et le return du plafond sont les
     * DEUX bornes du parcours de lecture.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    internal fun fichiersSources(exclure: Set<String> = emptySet()): List<String> {
        val resultat = mutableListOf<String>()
        val marche = Marche(bornFichiers)
        for (racine in racines) {
            parcourir(racine, marche)
        }
        for (fichier in marche.fichiers) {
            if (fichier.extension != "kt") continue
            val uri = fichier.toURI().toString()
            if (uri in exclure) continue
            resultat.add(uri)
            if (resultat.size >= BORN_FICHIERS) return resultat
        }
        return resultat
    }

    /**
     * Garantit qu'un snapshot existe SANS JAMAIS scanner sur le fil
     * appelant (ADR 0212) : JOINT le WARM en vol (attente bornée par le
     * fil de fond déjà lancé), sinon si un passage a ABOUTI pour les
     * racines courantes (`pret`) rend le snapshot tel quel (même VIDE
     * ou PARTIEL — c'est la vérité), sinon AMORCE le WARM et rend le
     * snapshot courant (sous-lecture honnête d'UNE requête, le WARM
     * publie pour les suivantes).
     *
     * Exemption detekt ciblée (règle 16) : SwallowedException — joindre
     * une tâche périmée/tombée n'est pas une perte : la boucle re-lit
     * l'état et la prochaine requête ré-amorce (X4).
     */
    @Suppress("SwallowedException")
    private fun assurerSnapshot() {
        if (racines.isEmpty()) return
        val enCours = synchronized(verrouScan) { enVol }
        if (enCours != null) {
            try {
                enCours.get()
            } catch (t: Throwable) {
                // Périmée ou tombée : l'état est relu ci-dessous.
            }
        }
        if (pret) return
        amorcerScan()
    }

    /**
     * Attend la fin du passage COURANT des racines courantes — usage
     * MOTEUR (ADR 0211 : la passe d'indexation séquence segments puis
     * scan avant de publier son événement final). Ne scanne JAMAIS sur
     * le fil appelant : joint le vol en cours, ré-amorce si un passage
     * n'a pas abouti. Retour immédiat quand tout est abouti.
     *
     * Exemption detekt ciblée (règle 16) : SwallowedException — une
     * tâche périmée/tombée relance la boucle qui ré-amorce (X4) ;
     * LoopWithTooManyJumpStatements — les issues de la boucle
     * (vol joint, pret, racines vides) sont ses bornes.
     */
    @Suppress("SwallowedException", "LoopWithTooManyJumpStatements")
    internal fun attendrePassageCourant() {
        while (true) {
            if (racines.isEmpty()) return
            val enCours = synchronized(verrouScan) { enVol }
            if (enCours != null) {
                try {
                    enCours.get()
                } catch (t: Throwable) {
                    // Périmée ou tombée : la boucle re-lit l'état.
                }
                continue
            }
            if (pret) return
            amorcerScan()
        }
    }

    /** Pose l'observateur du prochain passage (ADR 0213 — usage moteur). */
    internal fun definirObservateur(obs: ObservationScan?) {
        observateur = obs
    }

    /**
     * L'univers des sources projet est-il COMPLET pour la PREUVE
     * d'ABSENCE (retour utilisateurs 25/10/2026, vague 3) ? Racines
     * VIDES (l'univers est alors exactement les documents OUVERTS —
     * le moteur n'a jamais promis les fichiers fermés), dernier
     * passage ABOUTI pour les racines courantes (`pret` — un scan
     * amorcé mais pas publié rend le snapshot PARTIEL : une extension
     * vivant dans un fichier non encore scanné rendrait une absence
     * « prouvée » FAUSSE, D3 l'interdit) ET NON TRONQUÉ (`tronque` —
     * étape B, H1 : un type déclaré derrière [BORN_FICHIERS]
     * rendrait l'absence des voies 4/5 tout aussi fausse).
     * @Volatile : KP6.
     */
    internal fun estUniversConnu(): Boolean = racines.isEmpty() || (pret && !tronque)

    /** Statistiques du dernier passage (progression + re-parses — bilan ADR 0213). */
    internal fun statsDernierPassage(): StatsPassage = statsPassage

    // ── Scan (WARM — le seul fil qui parse) ────────────────────────

    /**
     * Amorce le scan des racines courantes sur le fil dédié : le coût
     * part AU MOMENT de la sync (definirRacines) — quand la première
     * requête lit, le travail est déjà en route, souvent fini. Un seul
     * vol à la fois (single-flight) ; les racines EMPTY n'amorcent pas.
     *
     * ADR 0212 : la PUBLICATION et la LIBÉRATION du vol vivent DANS le
     * corps de la tâche (avant son achèvement) — un lecteur qui JOINT
     * le vol est garanti de voir le résultat publié ET le vol libéré
     * (happens-before du FutureTask) ; un `finally` côté EXECUTOR ne
     * garantirait RIEN (il court APRÈS l'achèvement : `get()` peut
     * rendre avant la libération, et l'amorce suivante serait bloquée
     * par un vol fantôme — la fenêtre de course est fermée par
     * construction, leçon des 4 échecs du harnais).
     */
    private fun amorcerScan() {
        synchronized(verrouScan) {
            if (racines.isEmpty() || enVol != null) return
            val racinesDuScan = racines
            val tache =
                FutureTask<ResultatScan> {
                    try {
                        val resultat = scanner(racinesDuScan)
                        publierResultat(racinesDuScan, resultat)
                        resultat
                    } finally {
                        synchronized(verrouScan) { enVol = null }
                    }
                }
            enVol = tache
            executeur.execute { tache.run() }
        }
    }

    /**
     * Publie le résultat du scan WARM si les racines n'ont pas bougé
     * depuis le lancement (sinon le résultat est PÉRIMÉ — il n'est PAS
     * publié, la prochaine lecture ré-amorcera). Le drapeau `pret`
     * suit l'aboutissement (ADR 0212 — un résultat VIDE ou PARTIEL
     * annulé est un état abouti, pas une invalidation) ; `tronque`
     * suit la MARCHE (étape B, H1 — il ne coupe PAS `pret` : la
     * fraîcheur du snapshot tient, c'est la PREUVE D'ABSENCE que
     * [estUniversConnu] coupe).
     */
    private fun publierResultat(
        racinesDuScan: List<File>,
        resultat: ResultatScan,
    ) {
        synchronized(verrouScan) {
            if (racines == racinesDuScan) {
                scannees = resultat.declarations
                pret = true
                tronque = resultat.tronque
            }
        }
    }

    /**
     * Parcours complet des racines (fichiers .kt ET .java, ordre
     * déterministe) — ADR 0212 : la MARCHE (listage borné) précède le
     * PARSE, la progression est un VRAI X/N et l'annulation coopérative
     * (ADR 0214) coupe ENTRE les fichiers. INCRÉMENTAL par mtime : un
     * fichier inchangé rend ses déclarations EN CACHE sans re-parse ;
     * les fichiers disparus sont purgés à la fin de la passe (sur les
     * VIVANTS DE LA MARCHE, même si le parse a été annulé en route —
     * un fichier sain n'est jamais purgé par une annulation).
     *
     * SwallowedException ciblé (règle 16) : fichier illisible = absent
     * (D3) ; LoopWithTooManyJumpStatements ciblé (règle 16) : le
     * continue de la source Java et celui du fichier en cache sont les
     * branches du parcours multi-langue.
     */
    @Suppress("SwallowedException", "LoopWithTooManyJumpStatements")
    private fun scanner(racinesDuScan: List<File>): ResultatScan {
        // 1. MARCHE d'abord — total CONNU et HONNÊTE (ADR 0212/0213 ;
        //    étape B, H1 : le COMPTE va au-delà de la borne, seule la
        //    COLLECTE est bornée).
        val marche = Marche(bornFichiers)
        for (racine in racinesDuScan) {
            parcourir(racine, marche)
        }
        val vivants = marche.fichiers.map { it.path }.toMutableSet()
        // 2. PARSE ensuite — incrémental mtime, observation par fichier.
        val resultat = mutableListOf<DeclarationProjet>()
        var reanalyses = 0
        var traites = 0
        for ((index, fichier) in marche.fichiers.withIndex()) {
            val chemin = fichier.path
            val mtime = fichier.lastModified()
            val enCache = cacheFichiers[chemin]
            if (enCache != null && enCache.first == mtime) {
                // Inchangé : JAMAIS re-parsé (miroir ADR 0111).
                resultat += enCache.second
            } else {
                val declarations = declarer(fichier)
                if (declarations != null) {
                    cacheFichiers[chemin] = mtime to declarations
                    resultat += declarations
                    reanalyses++
                }
            }
            traites = index + 1
            val obs = observateur
            if (obs != null && !obs.surFichier(traites, marche.fichiers.size, fichier.name)) {
                // Annulation coopérative (ADR 0214) : l'acquis est
                // publié, le passage se marquera PARTIEL côté moteur.
                break
            }
        }
        // L'index ne ment jamais : les fichiers disparus partent (sur
        // les vivants de la MARCHE — l'annulation ne purge jamais un
        // fichier sain non encore parcouru par le parse).
        cacheFichiers.keys.retainAll(vivants)
        statsPassage =
            StatsPassage(
                faits = traites,
                total = marche.vus,
                reanalyses = reanalyses,
            )
        return ResultatScan(resultat, marche.tronque)
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
     * Marche récursive (dossiers cachés/build exclus) — dossiers en
     * profondeur, fichiers éligibles dans l'ORDRE du tri par nom
     * (déterminisme : la même cible sort toujours devant/derrière la
     * borne). La COLLECTE s'arrête à la borne, le COMPTE CONTINUE
     * (étape B, H1 — [Marche.ajouter] décide) ; un dossier illisible
     * est ABSENT (D3, l'exception est le signal).
     */
    private fun parcourir(
        dossier: File,
        marche: Marche,
    ) {
        val enfants = dossier.listFiles() ?: return
        for (enfant in enfants.sortedBy { it.name }) {
            when {
                enfant.isDirectory ->
                    if (enfant.name !in EXCLUSIONS && !enfant.name.startsWith(".")) {
                        parcourir(enfant, marche)
                    }

                enfant.isFile &&
                    (enfant.extension == "kt" && !enfant.name.endsWith(".kts") ||
                        enfant.extension == "java") ->
                    marche.ajouter(enfant)
            }
        }
    }

    private companion object {
        /** Borne de fichiers TRAITÉS (collecte bornée, compte honnête
         *  au-delà — étape B, H1 : [ScanProjet.tronque]). */
        private const val BORN_FICHIERS: Int = 2000

        /** Dossiers exclus (sorties, caches — le scan est le SOURCE). */
        private val EXCLUSIONS: Set<String> = setOf("build", ".git", ".gradle")
    }
}
