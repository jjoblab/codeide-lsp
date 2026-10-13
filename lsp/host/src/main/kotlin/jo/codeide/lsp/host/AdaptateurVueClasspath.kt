package jo.codeide.lsp.host

import jo.codeide.lsp.index.Conteneur
import jo.codeide.lsp.index.PhaseIndex
import jo.codeide.lsp.index.VueIndex
import jo.lspkotlin.api.MembreJvmKotlin
import jo.lspkotlin.api.MembresTypeKotlin
import jo.lspkotlin.api.NomTypeKotlin
import jo.lspkotlin.api.VueClasspathKotlin
import java.io.File
import java.util.zip.ZipFile

/**
 * Adaptateur de PRODUCTION de la vue de classpath neutre Kotlin (K3,
 * LSP_KOTLIN.md §4.1 « classpath », D4 : réutilisation de l'index disque
 * Java — ADR 0111 — TRAVERSÉ par la couture neutre [VueClasspathKotlin],
 * le moteur ne connaît ni VueIndex ni :lsp:index).
 *
 * Sources : les extensions `java.classNames`/`java.packages`/
 * `java.packageTypes`/`java.members` de la vue FUSION (requêtes
 * identiques à celles du moteur Java), la localisation
 * `java.classLocator` pour les OCTETS de classes (chaîne de supertypes
 * + @Metadata côté moteur — le lecteur class-file est UN dans
 * :lsp:kotlin, l'ouverture des conteneurs reste ici).
 *
 * Tolérant (D3) : toute requête rend son verdict VIDE/null (l'index
 * sous-lit tant qu'il n'est pas PRÊT — comportement contractuel de la
 * vue fusion), jamais d'exception vers le moteur.
 *
 * @param vue la vue d'index fusion de la session Java (ADR 0111 §3).
 *
 * Exemption detekt ciblée (règle 16) : SwallowedException — la
 * TOLÉRANCE EST LE CONTRAT (D3 : chaque requête rend son verdict
 * vide/null, jamais d'exception vers le moteur — l'exception est le
 * signal de dégradation, pas une perte) ; ReturnCount/LoopWithTooMany-
 * JumpStatements — issues distinctes du cache (absent, fermé, lecture
 * impossible, référence inconnue). Même patron que les adaptateurs de
 * l'index (AdaptateurIndexNoms : « nomsParPrefixe a levé », FINE).
 */
@Suppress("SwallowedException", "ReturnCount", "LoopWithTooManyJumpStatements")
internal class AdaptateurVueClasspath(
    private val vue: VueIndex,
) : VueClasspathKotlin {
    /**
     * Cache borné de ZipFiles OUVERTS (les lectures d'octets de classes
     * sonderaient sinon le central directory à CHAQUE supertype —
     * ~100 ms par gros jar) : FIFO de [TAILLE_CACHE] poignées, la plus
     * ancienne est FERMÉE à l'éviction.
     */
    private val jarsOuverts = LinkedHashMap<String, ZipFile>()

    override fun nomsParPrefixe(
        prefixe: String,
        limite: Int,
    ): List<NomTypeKotlin> =
        try {
            vue.nomsParPrefixe(prefixe, limite).map { NomTypeKotlin(it.nomSimple, it.fqcns) }
        } catch (t: Throwable) {
            emptyList()
        }

    override fun typesDansPaquet(paquet: String): List<String> =
        try {
            vue.typesDansPaquet(paquet)
        } catch (t: Throwable) {
            emptyList()
        }

    override fun estPaquet(nom: String): Boolean =
        try {
            vue.estPaquet(nom)
        } catch (t: Throwable) {
            false
        }

    override fun sousPaquetsDirects(nom: String): List<String> =
        try {
            vue.sousPaquetsDirects(nom)
        } catch (t: Throwable) {
            emptyList()
        }

    override fun membres(fqcn: String): MembresTypeKotlin? =
        try {
            vue.membres(fqcn)?.let { membres ->
                MembresTypeKotlin(
                    drapeauxClasse = membres.drapeauxClasse,
                    champs = membres.champs.map { MembreJvmKotlin(it.drapeaux, it.nom, it.descripteur) },
                    methodes = membres.methodes.map { MembreJvmKotlin(it.drapeaux, it.nom, it.descripteur) },
                )
            }
        } catch (t: Throwable) {
            null
        }

    override fun octetsClasse(fqcn: String): ByteArray? {
        if (vue.etat.value.phase != PhaseIndex.PRET && vue.etat.value.phase != PhaseIndex.PARTIEL) {
            // Construction en cours : la localisation peut sous-lire —
            // on tente quand même (segments déjà prêts), comme le
            // moteur Java sonde en repli (ADR 0111 §8).
        }
        return try {
            val localisation = vue.localiser(fqcn) ?: return null
            val conteneur = localisation.conteneur
            when (conteneur) {
                is Conteneur.Jar -> {
                    // Chemin COMPOSITE « aar!jarInterne » (vue fusion,
                    // conteneurDe d'un segment AAR) : le jar interne est
                    // lu DANS l'AAR ouvert (un seul ZipFile par AAR dans
                    // le cache) ; jar plat sinon.
                    if (conteneur.chemin.contains(SEPARATEUR_JAR_INTERNE)) {
                        val cheminAar = conteneur.chemin.substringBefore(SEPARATEUR_JAR_INTERNE)
                        val jarInterne = conteneur.chemin.substringAfter(SEPARATEUR_JAR_INTERNE)
                        octetsDepuisJarCompose(cheminAar, jarInterne, localisation.entree)
                    } else {
                        octetsDepuisJar(conteneur.chemin, localisation.entree)
                    }
                }

                is Conteneur.Arr -> {
                    // AAR non normalisé : le premier jar interne porte la
                    // classe le plus souvent (classes.jar d'abord).
                    conteneur.jarsInternes
                        .asSequence()
                        .mapNotNull { jarInterne ->
                            octetsDepuisJarCompose(conteneur.chemin, jarInterne, localisation.entree)
                        }.firstOrNull()
                }

                is Conteneur.Dossier -> {
                    octetsDepuisDossier(conteneur.chemin, localisation.entree)
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Octets d'une entrée d'un jar PLAT (jar du classpath v8 normalisé). */
    private fun octetsDepuisJar(
        chemin: String,
        entree: String,
    ): ByteArray? =
        avecJar(chemin) { jar ->
            jar.getEntry(entree)?.let { entreeZip ->
                jar.getInputStream(entreeZip).use { flux -> flux.readBytes() }
            }
        }

    /** Octets d'une entrée d'un jar INTERNE d'un AAR (chemin composé aar!jar). */
    private fun octetsDepuisJarCompose(
        cheminAar: String,
        jarInterne: String,
        entree: String,
    ): ByteArray? =
        avecJar(cheminAar) { aar ->
            val octetsJar =
                aar.getEntry(jarInterne)?.let { entree ->
                    aar.getInputStream(entree).use { flux -> flux.readBytes() }
                } ?: return@avecJar null
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(octetsJar)).use { interne ->
                var courant = interne.nextEntry
                while (courant != null) {
                    if (courant.name == entree) return@use interne.readBytes()
                    courant = interne.nextEntry
                }
                null
            }
        }

    /** Octets d'une entrée d'un dossier de classes (sortie de module). */
    private fun octetsDepuisDossier(
        chemin: String,
        entree: String,
    ): ByteArray? {
        val fichier = File(chemin, entree)
        return if (fichier.isFile) fichier.readBytes() else null
    }

    /**
     * Exécute [bloc] avec le ZipFile de [chemin] ouvert (cache FIFO
     * borné, l'éviction FERME la poignée) — synchronized : les sessions
     * du moteur sérialisent déjà, la garde reste pour les marges.
     */
    @Synchronized
    private fun <T> avecJar(
        chemin: String,
        bloc: (ZipFile) -> T?,
    ): T? {
        val existant = jarsOuverts[chemin]
        if (existant != null) {
            // Réordonnance LRU (LinkedHashMap par accès simulé).
            jarsOuverts.remove(chemin)
            jarsOuverts[chemin] = existant
            return try {
                bloc(existant)
            } catch (t: Throwable) {
                null
            }
        }
        val fichier = File(chemin)
        if (!fichier.isFile) return null
        return try {
            val jar = ZipFile(fichier)
            jarsOuverts[chemin] = jar
            while (jarsOuverts.size > TAILLE_CACHE) {
                val plusAncienne = jarsOuverts.keys.firstOrNull() ?: break
                jarsOuverts.remove(plusAncienne)?.close()
            }
            bloc(jar)
        } catch (t: Throwable) {
            jarsOuverts.remove(chemin)
            null
        }
    }

    private companion object {
        /** Poignées ouvertes au maximum (android.jar pèse déjà gros). */
        private const val TAILLE_CACHE: Int = 6

        /**
         * Séparateur jar interne d'un conteneur AAR tel que rendu par la
         * vue fusion (`conteneurDe` : « chemin.aar!classes.jar ») — miroir
         * du SEPARATEUR_JAR_INTERNE de :lsp:index, contrat du localisateur.
         */
        private const val SEPARATEUR_JAR_INTERNE: String = "!"
    }
}
