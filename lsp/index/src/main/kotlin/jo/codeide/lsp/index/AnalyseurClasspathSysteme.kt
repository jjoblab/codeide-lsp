package jo.codeide.lsp.index

import java.io.File
import java.io.IOException

/** Type d'entrée indexable dérivé de [jo.codeide.lsp.model.KindEntree]. */
internal enum class TypeEntreeIndex {
    /** Jar (y compris jar extrait d'un AAR normalisé v8). */
    JAR,

    /** AAR brut — normalisé à l'indexation (ADR 0111 §10). */
    ARR,

    /** Dossier de classes (sortie compilée — signature d'arborescence). */
    DOSSIER,
}

/**
 * Une entrée de classpath à indexer — projection de
 * [jo.codeide.lsp.model.EntreeClasspathInstantane] (les entrées MODULE
 * ne sont PAS indexables : l'identité réelle du module frère vit dans
 * SON snapshot, la préséance des sources sur la sortie compilée est
 * réglée par le consommateur — ADR 0111 §10).
 */
internal data class EntreeAIndexer(
    val chemin: String,
    val type: TypeEntreeIndex,
    val empreinte: String,
    val portee: String?,
    val taille: Long,
    val mtime: Long,
)

/** Résultat de l'analyse d'une entrée. */
internal sealed interface ResultatAnalyse {
    /** Données prêtes à sérialiser (segment à écrire). */
    data class Ok(
        val donnees: DonneesSegment,
    ) : ResultatAnalyse

    /**
     * Segment existant VALIDÉ par le prédicat de reprise — rien à
     * écrire, la position adopte [cle] (reprise « à chaud », ADR 0111 :
     * seul un jar manquant est analysé).
     */
    data class Reprise(
        val cle: String,
    ) : ResultatAnalyse

    /**
     * Entrée illisible (jar brouillé, dossier vide…) — écartée
     * honnêtement (état PARTIEL de la construction), jamais de crash.
     */
    data class Illisible(
        val raison: String,
    ) : ResultatAnalyse
}

/**
 * Analyseur de classpath — moissonne le contenu d'une entrée (jar, AAR
 * normalisé, dossier) en [DonneesSegment]. Couture injectable : les
 * tests fournissent des faux, l'implémentation système lit le vrai
 * système de fichiers par [LecteurZipBrut] (jamais `ZipFile`).
 */
internal interface AnalyseurClasspath {
    /**
     * Analyse une entrée — jamais d'exception, un [ResultatAnalyse]
     * toujours.
     *
     * @param repriseValide prédicat : un segment de clé donnée existe-t-il
     *        déjà et son en-tête est-il valide ? Vrai → [ResultatAnalyse.Reprise]
     *        (l'analyse coûteuse est évitée — cible « +1 dépendance =
     *        indexation du seul nouveau jar »).
     */
    fun analyser(
        entree: EntreeAIndexer,
        repriseValide: (cle: String) -> Boolean,
    ): ResultatAnalyse
}

/**
 * Implémentation système : jars et AAR lus par [LecteurZipBrut],
 * dossiers parcourus récursivement, fichiers de classe analysés par
 * [AnalyseurClassFile].
 */
internal class AnalyseurClasspathSysteme(
    private val lecteurZip: LecteurZipBrut,
) : AnalyseurClasspath {
    @Suppress("ReturnCount")
    override fun analyser(
        entree: EntreeAIndexer,
        repriseValide: (cle: String) -> Boolean,
    ): ResultatAnalyse {
        val fichier = File(entree.chemin)
        return when (entree.type) {
            TypeEntreeIndex.JAR -> analyserJar(entree, fichier, repriseValide)
            TypeEntreeIndex.ARR -> analyserArr(entree, fichier, repriseValide)
            TypeEntreeIndex.DOSSIER -> analyserDossier(entree, fichier, repriseValide)
        }
    }

    /** Moissonne un jar simple (clé = empreinte W6 du fichier). */
    @Suppress("ReturnCount")
    private fun analyserJar(
        entree: EntreeAIndexer,
        fichier: File,
        repriseValide: (cle: String) -> Boolean,
    ): ResultatAnalyse {
        // Reprise : la clé est dérivable SANS ouvrir le jar.
        if (repriseValide(entree.empreinte)) {
            return ResultatAnalyse.Reprise(entree.empreinte)
        }
        val poignee =
            try {
                lecteurZip.ouvrir(fichier)
            } catch (e: IOException) {
                return ResultatAnalyse.Illisible("jar illisible : ${e.message}")
            }
        poignee.use {
            val moisson = ArrayList<Triple<String, MembresClasse?, Int?>>()
            for (nom in poignee.noms) {
                if (!estClasseIndexable(nom)) continue
                val membres = lireMembres { poignee.lire(nom) }
                moisson.add(Triple(nom, membres, null))
            }
            return ResultatAnalyse.Ok(
                AssembleurSegment.assembler(
                    conteneur = Conteneur.Jar(entree.chemin),
                    cle = entree.empreinte,
                    tailleSource = entree.taille,
                    mtimeSource = entree.mtime,
                    moisson = moisson,
                ),
            )
        }
    }

    /**
     * Moissonne un AAR NON normalisé — normalisation à l'indexation
     * (ADR 0111 §10) : `classes.jar` puis `jars de `libs/`` triés ; le
     * PREMIER porteur d'un nom d'entrée gagne (classes.jar précède).
     */
    @Suppress("ReturnCount", "SwallowedException")
    private fun analyserArr(
        entree: EntreeAIndexer,
        fichier: File,
        repriseValide: (cle: String) -> Boolean,
    ): ResultatAnalyse {
        val poignee =
            try {
                lecteurZip.ouvrir(fichier)
            } catch (e: IOException) {
                return ResultatAnalyse.Illisible("AAR illisible : ${e.message}")
            }
        poignee.use {
            val jarsInternes = jarsInternesDe(poignee.noms)
            if (jarsInternes.isEmpty()) {
                return ResultatAnalyse.Illisible("AAR sans jar interne (${entree.chemin})")
            }
            // Reprise : la clé exige la liste des jars internes (lecture
            // du répertoire central seule — les classes ne sont pas relues).
            val cle = ClesSegments.cleArr(entree.empreinte, jarsInternes)
            if (repriseValide(cle)) {
                return ResultatAnalyse.Reprise(cle)
            }
            val moisson = ArrayList<Triple<String, MembresClasse?, Int?>>()
            jarsInternes.forEachIndexed { indexJar, nomJar ->
                val octets =
                    try {
                        poignee.lire(nomJar)
                    } catch (e: IOException) {
                        // SwallowedException justifié (règle 8) : un jar
                        // interne corrompu est ÉCARTÉ, les autres suivent.
                        return@forEachIndexed
                    } ?: return@forEachIndexed
                moissonnerOctets(octets).forEach { (nom, membres) ->
                    moisson.add(Triple(nom, membres, indexJar))
                }
            }
            return ResultatAnalyse.Ok(
                AssembleurSegment.assembler(
                    conteneur = Conteneur.Arr(entree.chemin, jarsInternes),
                    cle = cle,
                    tailleSource = entree.taille,
                    mtimeSource = entree.mtime,
                    moisson = moisson,
                ),
            )
        }
    }

    /**
     * Moissonne un dossier de classes — la clé est la signature
     * d'arborescence (chemin relatif, taille, horodatage) : tout
     * changement d'un `.class` invalide le segment (ADR 0111 §10).
     */
    @Suppress("ReturnCount")
    private fun analyserDossier(
        entree: EntreeAIndexer,
        dossier: File,
        repriseValide: (cle: String) -> Boolean,
    ): ResultatAnalyse {
        if (!dossier.isDirectory) {
            return ResultatAnalyse.Illisible("dossier absent (${entree.chemin})")
        }
        val fichiers =
            dossier
                .walkTopDown()
                .filter { it.isFile && it.name.endsWith(SUFFIXE_CLASSE) }
                .sortedBy { it.relativeTo(dossier).invariantSeparatorsPath }
                .toList()
        if (fichiers.isEmpty()) {
            return ResultatAnalyse.Illisible("dossier sans classe (${entree.chemin})")
        }
        val signature =
            fichiers.map {
                Triple(it.relativeTo(dossier).invariantSeparatorsPath, it.length(), it.lastModified())
            }
        // Reprise : la signature EST la clé (ADR 0111 §10) — un segment
        // à jour n'est pas ré-analysé, seul le WALK est payé.
        val cle = ClesSegments.cleDossier(signature)
        if (repriseValide(cle)) {
            return ResultatAnalyse.Reprise(cle)
        }
        val moisson =
            fichiers.map { fichier ->
                val relatif = fichier.relativeTo(dossier).invariantSeparatorsPath
                Triple(relatif, lireMembres { fichier.readBytes() }, null)
            }
        return ResultatAnalyse.Ok(
            AssembleurSegment.assembler(
                conteneur = Conteneur.Dossier(entree.chemin),
                cle = cle,
                tailleSource = entree.taille,
                mtimeSource = entree.mtime,
                moisson = moisson,
            ),
        )
    }

    /** Moissonne les classes d'un jar en mémoire (jar interne d'AAR). */
    private fun moissonnerOctets(octets: ByteArray): List<Pair<String, MembresClasse?>> {
        val poignee = lecteurZip.depuisOctets(octets)
        return poignee.use {
            poignee.noms
                .filter { estClasseIndexable(it) }
                .map { nom -> Pair(nom, lireMembres { poignee.lire(nom) }) }
        }
    }

    /**
     * Lit et analyse une classe — `null` si illisible (partiel honnête).
     *
     * SwallowedException justifié (règle 8) : une entrée illisible n'a
     * pas de membres — la localisation reste (index partiel honnête).
     */
    @Suppress("ReturnCount", "SwallowedException")
    private fun lireMembres(lire: () -> ByteArray?): MembresClasse? {
        val octets =
            try {
                lire()
            } catch (e: IOException) {
                return null
            } ?: return null
        return AnalyseurClassFile.analyser(octets)
    }

    /** Jars internes retenus d'un AAR : classes.jar puis jars de `libs/` triés. */
    private fun jarsInternesDe(noms: List<String>): List<String> {
        val libs = noms.filter { it.startsWith("libs/") && it.endsWith(".jar") }.sorted()
        val classes = noms.filter { it == "classes.jar" }
        return classes + libs
    }

    /** Entrée indexable : `.class`, hors métadonnées et module-info. */
    private fun estClasseIndexable(nom: String): Boolean =
        nom.endsWith(SUFFIXE_CLASSE) &&
            !nom.startsWith("META-INF/") &&
            nom != "module-info.class"

    private companion object {
        private const val SUFFIXE_CLASSE: String = ".class"
    }
}
