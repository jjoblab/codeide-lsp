package jo.codeide.lsp.index

/**
 * Assemblage des données d'un segment — logique PURE (testable) :
 * normalisation d'une moisson brute (entrées, membres, origines) en
 * structures TRIÉES et cohérentes pour l'écriture.
 *
 * Contrats garantis (vérifiés par les tests d'aller-retour) :
 * - entrées triées et UNIQUES (premier porteur gagnant sur doublon) ;
 * - paquets triés, chaque paquet couvrant une plage CONTIGUÈE d'entrées
 *   (conséquence du tri) — extension `java.packageTypes` « ni plus ni
 *   moins » ;
 * - noms simples triés, indices croissants.
 */
internal object AssembleurSegment {
    /**
     * Assemble un segment.
     *
     * @param conteneur conteneur d'origine.
     * @param cle clé du segment (empreinte W6 ou clé dérivée).
     * @param tailleSource taille du fichier source au moment de l'analyse.
     * @param mtimeSource horodatage du fichier source.
     * @param moisson entrées moissonnées : (nom d'entrée, membres analysés
     *        ou `null`, index du jar interne AAR ou `null`).
     */
    internal fun assembler(
        conteneur: Conteneur,
        cle: String,
        tailleSource: Long,
        mtimeSource: Long,
        moisson: List<Triple<String, MembresClasse?, Int?>>,
    ): DonneesSegment {
        // Tri + déduplication : le PREMIER porteur d'un nom d'entrée gagne
        // (classes.jar précède jars de `libs/` dans la moisson d'un AAR).
        val uniques =
            moisson
                .groupBy(keySelector = { it.first })
                .mapValues { (_, porteurs) -> porteurs.first() }
                .values
                .sortedBy { it.first }

        val entrees = uniques.map { it.first }
        val membres = uniques.map { it.second }
        val origines =
            if (uniques.any { it.third != null }) {
                uniques.map { it.third ?: ORIGINE_ABSENTE }.toIntArray()
            } else {
                null
            }

        val paquets = assemblerPaquets(entrees)
        val nomsSimples = assemblerNomsSimples(entrees)

        return DonneesSegment(
            cle = cle,
            conteneur = conteneur,
            tailleSource = tailleSource,
            mtimeSource = mtimeSource,
            entrees = entrees,
            paquets = paquets,
            nomsSimples = nomsSimples,
            membres = membres,
            origines = origines,
        )
    }

    /** Paquets triés avec plages contiguës (tri des entrées ⇒ contiguïté). */
    internal fun assemblerPaquets(entrees: List<String>): List<PaquetSegment> {
        val paquets = ArrayList<PaquetSegment>()
        var index = 0
        while (index < entrees.size) {
            val paquet = paquetDe(entrees[index])
            var fin = index
            while (fin < entrees.size && paquetDe(entrees[fin]) == paquet) {
                fin++
            }
            paquets.add(PaquetSegment(paquet, index, fin - index))
            index = fin
        }
        return paquets
    }

    /** Noms simples triés → indices croissants des classes porteuses. */
    internal fun assemblerNomsSimples(entrees: List<String>): List<NomSimpleSegment> {
        val parNom = HashMap<String, MutableList<Int>>()
        entrees.forEachIndexed { index, entree ->
            val nom = nomSimpleDe(entree) ?: return@forEachIndexed
            parNom.getOrPut(nom) { mutableListOf() }.add(index)
        }
        return parNom.keys.sorted().map { nom ->
            NomSimpleSegment(nom, parNom.getValue(nom).toIntArray())
        }
    }

    /** Paquet d'une entrée (`java/util/Map.class` → `java.util`, racine → ""). */
    internal fun paquetDe(entree: String): String {
        val derniereBarre = entree.lastIndexOf('/')
        if (derniereBarre < 0) return ""
        return entree.substring(0, derniereBarre).replace('/', '.')
    }

    /**
     * Nom simple d'une entrée (`java/util/Map$Entry.class` → `Entry`),
     * `null` si ce n'est pas un identifiant Java plausible (numérotés
     * anonymes `Map$1`, fichiers hors paquet invalides) — la LOCALISATION
     * de ces classes reste possible (entrées), seule la complétion par
     * nom simple les ignore.
     *
     * ReturnCount justifié (règle 8) : chaque écart (vide, anonyme,
     * non-identifiant) a son retour anticipé explicite.
     */
    @Suppress("ReturnCount")
    internal fun nomSimpleDe(entree: String): String? {
        val apresBarre = entree.substringAfterLast('/')
        val apresDollar = apresBarre.substringAfterLast('$')
        val apresClass = apresDollar.removeSuffix(".class")
        if (apresClass.isEmpty()) {
            return null
        }
        val premier = apresClass.first()
        if (!premier.isLetter() && premier != '_') {
            return null
        }
        return apresClass
    }

    private const val ORIGINE_ABSENTE: Int = 0
}
