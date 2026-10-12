package jo.codeide.lsp.index

import jo.codeide.lsp.model.ClasspathInstantane
import jo.codeide.lsp.model.EntreeClasspathInstantane
import jo.codeide.lsp.model.KindEntree
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.TreeSet

/**
 * Position d'une entrée de classpath dans la vue — construite par le
 * [ServiceIndexDisque] au fil de la construction.
 *
 * La clé du segment est CONNUE d'avance pour un jar (empreinte W6) ;
 * pour un AAR (jars internes) ou un dossier (signature d'arborescence)
 * elle n'est dérivable qu'en ouvrant la source — [adopter] la fixe à
 * l'issue de l'analyse. Les lectures concurrentes voient soit l'état
 * initial (non prêt), soit l'état adopté (prêt) — les champs volatils
 * publient l'adoption sans demi-état.
 *
 * @property entree entrée d'origine (référence de l'auto-guérison).
 * @property cle clé du segment (empreinte W6 du jar, clé dérivée AAR/
 *           dossier — vide tant qu'inconnue).
 * @property prete le segment existe et son en-tête est validé — les
 *           requêtes ne consultent que les positions prêtes (repli par
 *           sondage sinon, ADR 0111 §8).
 */
internal class PositionSegment(
    val entree: EntreeAIndexer,
    @Volatile private var cle: String,
) {
    /** Portée du classpath d'où l'entrée provient. */
    val portee: String? get() = entree.portee

    /** Position prête ? (publication atomique après [adopter]). */
    @Volatile
    var prete: Boolean = false
        private set

    /** Clé courante (vide tant que non dérivable). */
    fun cle(): String = cle

    /** Taille de validation W7 (jar/AAR ; dossier : signature = validation). */
    val tailleValidation: Long? get() = if (entree.type == TypeEntreeIndex.DOSSIER) null else entree.taille

    /** Horodatage de validation W7. */
    val mtimeValidation: Long? get() = if (entree.type == TypeEntreeIndex.DOSSIER) null else entree.mtime

    /** Adopte la clé définitive et devient prête. */
    fun adopter(cleDefinitive: String) {
        cle = cleDefinitive
        prete = true
    }
}

/**
 * Vue d'index d'UN classpath (module) — FUSION LSM À LA REQUÊTE
 * (ADR 0111 §3) : le classpath est l'union ORDONNÉE de ses segments,
 * aucun « gros index » n'est jamais reconstruit.
 *
 * Les requêtes consultent les segments dans l'ordre du classpath : le
 * premier porteur gagne (préséance de classpath). Les segments non
 * encore construits sont IGNORÉS — le consommateur sonde le disque en
 * repli tant que [etat] n'est pas [PhaseIndex.PRET] (ADR 0111 §8 ;
 * à l'état PRÊT, le localisateur devient autoritatif : les négatifs
 * sont sûrs, les caches mémoire de localisation du consommateur sont
 * VIDÉS à la transition — P7/P8).
 *
 * Extensions servies (ADR 0111 §5) : `java.classLocator`
 * ([localiser]), `java.classNames` ([nomsParPrefixe]), `java.packages`
 * ([estPaquet]), `java.packageTypes` ([typesDansPaquet] — sous-paquets
 * DIRECTS seulement via [sousPaquetsDirects], « ni plus ni moins »),
 * `java.members` ([membres]) et `java.membersByOwner`
 * ([membresStatiques] — imports statiques).
 *
 * TooManyFunctions justifié (règle 8) : l'API publique EST le catalogue
 * des extensions de requête d'ADR 0111 §5 — une fonction par extension,
 * par conception (localisateur, noms, paquets, types, membres, imports
 * statiques) ; les privées de haut niveau vivent hors classe.
 */
@Suppress("TooManyFunctions")
public class VueIndex internal constructor(
    private val positions: List<PositionSegment>,
    private val cache: CacheSegments,
    private val reconstruction: (empreinte: String) -> Unit,
    etatInitial: EtatIndex,
) {
    /** État de construction — publié par le service (progression par jar). */
    internal val etatMutable: MutableStateFlow<EtatIndex> = MutableStateFlow(etatInitial)

    /** État de construction (flux — progression par jar). */
    public val etat: StateFlow<EtatIndex> get() = etatMutable

    /** Positions internes (service uniquement). */
    internal fun positionsInternes(): List<PositionSegment> = positions

    /** Empreintes des segments référencés par cette vue (nettoyage). */
    public fun empreintesReferences(): Set<String> = positions.map { it.cle() }.filter { it.isNotEmpty() }.toSet()

    /**
     * Localise une classe — extension `java.classLocator`.
     *
     * @param fqcn nom pleinement qualifié (`java.util.Map$Entry`).
     * @return la localisation (conteneur + entrée + portée) du PREMIER
     *         segment du classpath qui la porte, `null` sinon.
     */
    public fun localiser(fqcn: String): LocalisationClasse? {
        val entree = fqcn.replace('.', '/') + SUFFIXE_CLASSE
        for (position in positions) {
            val segment = segmentDe(position) ?: continue
            val index = segment.entrees.binarySearch(entree)
            if (index >= 0) {
                return LocalisationClasse(
                    conteneur = conteneurDe(segment, index),
                    entree = entree,
                    portee = position.portee,
                )
            }
        }
        return null
    }

    /**
     * Noms simples portant un préfixe — extension `java.classNames`
     * (complétion de types). Le balayage par segment est une RECHERCHE
     * BINAIRES + marche bornée (tue le balayage linéaire W3 mesuré).
     *
     * @param prefixe préfixe insensible à la casse NON — sensible (Java
     *        distingue `Map`/`map`).
     * @param limite nombre maximum de noms retournés.
     * @return les noms (triés) et leurs FQCN dans l'ordre du classpath.
     *
     * NestedBlockDepth justifié (règle 8) : la fusion LSM itère segments
     * × lignes × porteurs par conception — extraire noierait l'ordre.
     */
    @Suppress("NestedBlockDepth")
    public fun nomsParPrefixe(
        prefixe: String,
        limite: Int = LIMITE_NOMS_PAR_DEFAUT,
    ): List<NomSimpleTypes> {
        val fqcnsDejaVus = HashSet<String>()
        val parNom = HashMap<String, MutableList<String>>()
        for (position in positions) {
            val segment = segmentDe(position) ?: continue
            for (ligne in lignesDepuis(segment, prefixe)) {
                val porteurs = ArrayList<String>(ligne.indices.size)
                for (index in ligne.indices) {
                    val fqcn = fqcnDe(segment.entrees[index])
                    if (fqcnsDejaVus.add(fqcn)) {
                        porteurs.add(fqcn)
                    }
                }
                if (porteurs.isNotEmpty()) {
                    parNom.getOrPut(ligne.nom) { mutableListOf() }.addAll(porteurs)
                }
                if (parNom.size >= limite) {
                    return parNom.keys.sorted().map { NomSimpleTypes(it, parNom.getValue(it)) }
                }
            }
        }
        return parNom.keys.sorted().map { NomSimpleTypes(it, parNom.getValue(it)) }
    }

    /**
     * Le paquet existe dans un segment prêt — extension `java.packages`.
     *
     * V0.102.0 (lot h4 écart 1) : les segments ne portent que les paquets
     * FEUILLES (plages contiguës des entrées, AssembleurSegment) ; un
     * paquet INTERMÉDIAIRE — « android » de « android.util » — existe
     * pourtant dès qu'un sous-paquet existe (toute classe
     * `android.util.Log` y pourvoit : l'index mémoire du moteur ajoute
     * TOUS les préfixes, `addPackagePrefixes`). Sans la même tolérance
     * ici, l'index disque PRÊT — AUTORITATIF (ADR 0111 §8, négatif
     * définitif) — nierait un préfixe que l'import exige : « The import
     * android cannot be resolved » alors que le jar est indexé.
     */
    public fun estPaquet(nom: String): Boolean =
        positions
            .asSequence()
            .mapNotNull { segmentDe(it) }
            .any { segment -> segmentContientPaquet(segment.paquets, nom) }

    /**
     * FQCN des types DIRECTEMENT dans un paquet — extension
     * `java.packageTypes` (« ni plus ni moins » : `java.util` ne rend
     * pas `java.util.function.Function`).
     *
     * LoopWithTooManyJumpStatements justifié (règle 8) : le continue du
     * segment sans le paquet et le garde-fou de dédoublonnage sont le
     * patron même de la fusion LSM.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    public fun typesDansPaquet(paquet: String): List<String> {
        val fqcns = mutableListOf<String>()
        val dejaVus = HashSet<String>()
        for (position in positions) {
            val segment = segmentDe(position) ?: continue
            val index = segment.paquets.binarySearchBy(paquet) { it.nom }
            if (index < 0) continue
            val ligne = segment.paquets[index]
            for (i in ligne.premiereClasse until ligne.premiereClasse + ligne.nombreClasses) {
                if (dejaVus.add(segment.entrees[i])) {
                    fqcns.add(fqcnDe(segment.entrees[i]))
                }
            }
        }
        return fqcns
    }

    /**
     * Sous-paquets DIRECTS d'un paquet (noms COMPLETS, un seul niveau
     * sous `paquet.`, ni plus ni moins) — complément de
     * `java.packageTypes` : `sousPaquetsDirects("java.util")` rend
     * `java.util.function`, PAS `function` seul ni `java.util.function.internal`.
     */
    public fun sousPaquetsDirects(paquet: String): List<String> {
        val prefixe = if (paquet.isEmpty()) "" else "$paquet."
        val enfants = TreeSet<String>()
        for (position in positions) {
            val segment = segmentDe(position) ?: continue
            val paquets = segment.paquets
            var index = premiereLigneAtteignant(paquets, prefixe) { it.nom }
            while (index < paquets.size) {
                val nom = paquets[index].nom
                if (prefixe.isNotEmpty() && !nom.startsWith(prefixe)) break
                val reste = nom.removePrefix(prefixe)
                if (reste.isNotEmpty()) {
                    enfants.add(prefixe + reste.substringBefore('.'))
                }
                index++
            }
        }
        return enfants.toList()
    }

    /**
     * Membres d'une classe — extension `java.members`.
     *
     * @param fqcn nom pleinement qualifié (les imbriqués par `$`).
     * @return les membres du premier porteur, `null` si la classe est
     *         introuvable ou son bloc illisible (partiel honnête).
     */
    public fun membres(fqcn: String): MembresClasse? {
        val (segment, index) = porteurDe(positions, { segmentDe(it) }, fqcn) ?: return null
        return segment.membres(index)
    }

    /**
     * Membres STATIQUES d'un propriétaire — extension
     * `java.membersByOwner` (imports statiques : complétion après
     * `import static java.util.Math.`).
     *
     * @param fqcn propriétaire (`java.util.Math`).
     * @return champs et méthodes statiques du premier porteur.
     */
    public fun membresStatiques(fqcn: String): List<Membre> {
        val membres = membres(fqcn) ?: return emptyList()
        return (
            membres.champs.asSequence().filter { it.drapeaux and DRAPEAU_STATIC != 0 } +
                membres.methodes.asSequence().filter { it.drapeaux and DRAPEAU_STATIC != 0 }
        ).toList()
    }

    /** Segment décodé d'une position prête (cache LRU traversant). */
    internal fun segmentDe(position: PositionSegment): SegmentDecode? {
        if (!position.prete) return null
        return cache.obtenir(
            empreinte = position.cle(),
            tailleAttendue = position.tailleValidation,
            mtimeAttendue = position.mtimeValidation,
            surCorruption = { empreinte, _ -> reconstruction(empreinte) },
        )
    }

    /** FQCN d'un nom d'entrée (`java/util/Map$Entry.class` → `java.util.Map$Entry`). */
    private fun fqcnDe(entree: String): String = entree.removeSuffix(SUFFIXE_CLASSE).replace('/', '.')
}

/** Porteur d'un FQCN : (segment, index d'entrée) du premier gagnant. */
private fun porteurDe(
    positions: List<PositionSegment>,
    segments: (PositionSegment) -> SegmentDecode?,
    fqcn: String,
): Pair<SegmentDecode, Int>? {
    val entree = fqcn.replace('.', '/') + SUFFIXE_CLASSE
    for (position in positions) {
        val segment = segments(position) ?: continue
        val index = segment.entrees.binarySearch(entree)
        if (index >= 0) {
            return Pair(segment, index)
        }
    }
    return null
}

/** Conteneur effectif d'une entrée (AAR : jar interne porteur). */
private fun conteneurDe(
    segment: SegmentDecode,
    index: Int,
): Conteneur {
    val jarInterne = segment.jarInterne(index)
    if (jarInterne != null && segment.conteneur is Conteneur.Arr) {
        return Conteneur.Jar(segment.conteneur.chemin + SEPARATEUR_JAR_INTERNE + jarInterne)
    }
    return segment.conteneur
}

/** Lignes de noms simples depuis le premier >= préfixe (marche bornée). */
private fun lignesDepuis(
    segment: SegmentDecode,
    prefixe: String,
): Sequence<NomSimpleSegment> {
    val lignes = segment.nomsSimples
    var index = premiereLigneAtteignant(lignes, prefixe) { it.nom }
    return generateSequence {
        if (index >= lignes.size) {
            return@generateSequence null
        }
        val ligne = lignes[index]
        if (!ligne.nom.startsWith(prefixe)) {
            return@generateSequence null
        }
        index++
        ligne
    }
}

/** Première ligne dont le nom est >= cible (borne inférieure générique). */
private fun <T> premiereLigneAtteignant(
    lignes: List<T>,
    cible: String,
    nom: (T) -> String,
): Int {
    var bas = 0
    var haut = lignes.size
    while (bas < haut) {
        val milieu = (bas + haut) / 2
        if (nom(lignes[milieu]) < cible) {
            bas = milieu + 1
        } else {
            haut = milieu
        }
    }
    return bas
}

/**
 * Un segment connaît-il le paquet [nom] ? Correspondance EXACTE d'abord,
 * puis préfixe : la première ligne >= "nom." commence par "nom." si un
 * sous-paquet existe (les noms de paquets sont des identifiants + '.'
 * uniquement — aucun ne peut s'intercaler entre le préfixe et ses
 * extensions, cf. analyse de [VueIndex.estPaquet]).
 *
 * Exemption detekt ciblée (règle 16) : ReturnCount — chaque clause est
 * une ISSUE distincte du contrat (racine vide, correspondance exacte,
 * verdict du préfixe) — même patron que les garde-fous voisins.
 */
@Suppress("ReturnCount")
private fun segmentContientPaquet(
    paquets: List<PaquetSegment>,
    nom: String,
): Boolean {
    if (paquets.binarySearchBy(nom) { it.nom } >= 0) return true
    if (nom.isEmpty()) return false
    val prefixe = "$nom."
    val index = premiereLigneAtteignant(paquets, prefixe) { it.nom }
    return index < paquets.size && paquets[index].nom.startsWith(prefixe)
}

/** FQCN d'un nom d'entrée (`java/util/Map$Entry.class` -> `java.util.Map$Entry`). */
private fun fqcnDe(entree: String): String = entree.removeSuffix(SUFFIXE_CLASSE).replace('/', '.')

private const val SUFFIXE_CLASSE: String = ".class"
private const val SEPARATEUR_JAR_INTERNE: String = "!"

/** Limite par défaut du nombre de noms retournés par préfixe. */
internal const val LIMITE_NOMS_PAR_DEFAUT: Int = 100
