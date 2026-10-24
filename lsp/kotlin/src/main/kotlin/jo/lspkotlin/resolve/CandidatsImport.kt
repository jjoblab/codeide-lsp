package jo.lspkotlin.resolve

import jo.lspkotlin.api.VueClasspathKotlin
import jo.lspkotlin.index.IndexAppelablesKotlin
import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.chaineAncetresA
import jo.lspkotlin.parse.kt.nomReference
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes

/**
 * FQCN candidats à l'IMPORT d'un nom non résolu (K4, LSP_KOTLIN.md §4.3
 * « quick-fixes : import (un correctif par FQN candidate, position
 * triée) ») — les types du CLASSPATH portant EXACTEMENT le nom de la
 * référence, exclusions faites :
 *
 *  - référence RÉSOLUE (une voie du modèle suffit — cf.
 *    [ResolutionReferences]) : rien à importer ;
 *  - FQCN DÉJÀ importé (exact) ou nom effectif déjà pris : l'import
 *    serait un doublon ou un conflit ;
 *  - le paquet COURANT : le type y est déjà visible ;
 *  - les candidats du PROJET : ils se résolvent sans import (voie 4/5).
 *
 * L'ordre du classpath est conservé (le premier porteur a la
 * préséance) — l'hôte en dérive UN correctif par FQN.
 *
 * Exemptions detekt ciblées (règle 16) : LongParameterList/ReturnCount
 * — le CONTEXTE de résolution complet est FILÉ (patron TypageExpression)
 * et chaque garde conservatrice est une ISSUE distincte, D3.
 */
@Suppress("LongParameterList", "ReturnCount")
internal object CandidatsImport {
    /** Limite de la requête de noms par préfixe (garde de coût). */
    private const val LIMITE_NOMS: Int = 50

    /**
     * FQCN candidates pour la référence à [decalage].
     *
     * @param racine racine du document porteur.
     * @param decalage position de la référence (ou de sa plage).
     * @param contexteNom contexte de nommage (exclusions d'imports).
     * @param resolution résolveur classpath.
     * @param declarationsProjet déclarations du projet.
     * @param index appelables de premier niveau (voie de résolution).
     * @param vue vue de classpath (source des candidats), `null` : vide.
     * @return les FQCN par nom exact, ordre du classpath ; vide sinon.
     */
    internal fun fqcns(
        racine: NoeudSyntaxique,
        decalage: Int,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
        declarationsProjet: List<DeclarationProjet>,
        index: IndexAppelablesKotlin?,
        vue: VueClasspathKotlin?,
        portFichier: SuperKotlin.PortFichier? = null,
    ): List<String> {
        val vueEffective = vue ?: return emptyList()
        val nom = nomReferencePosition(racine, decalage) ?: return emptyList()
        // Résolue : rien à importer (quick-fix inutile, jamais proposé).
        // MÊME prédicat que le diagnostic — membres HÉRITÉS compris (ADR
        // 0216) : sinon le quick-fix proposerait un import pour un appel
        // NU à un membre du supertype (setContentView…), déjà résolu.
        if (
            ResolutionReferences.estResolu(
                racine, decalage, nom, contexteNom, resolution, declarationsProjet, index, portFichier,
            )
        ) {
            return emptyList()
        }
        val dejaImportes = contexteNom.importsParNom.values.toHashSet()
        val paquetCourant = contexteNom.paquet
        return vueEffective
            .nomsParPrefixe(nom, LIMITE_NOMS)
            .asSequence()
            .filter { porteur -> porteur.nomSimple == nom }
            .flatMap { porteur -> porteur.fqcns.asSequence() }
            .filter { fqcn -> fqcn !in dejaImportes }
            .filter { fqcn -> paquetCourant == null || !fqcn.startsWith("$paquetCourant.") }
            // KP8 : le filtre kotlin.* blanket excluait légitimement
            // kotlin.time.Duration — mais comme les types default-imported
            // (ArrayList, StringBuilder, etc.) sont désormais résolus par
            // IMPORTS_DEFAUT avant d'atteindre CandidatsImport, le filtre
            // n'est plus nécessaire pour eux. On garde le filtre pour
            // éviter le bruit (les types kotlin.* sont déjà résolus).
            .filter { fqcn -> !fqcn.startsWith("kotlin.") }
            .distinct()
            .toList()
    }

    /** Nom de la référence couvrant [decalage] (ou celle qui la touche). */
    private fun nomReferencePosition(
        racine: NoeudSyntaxique,
        decalage: Int,
    ): String? {
        val chaine = chaineAncetresA(racine, decalage)
        val reference =
            chaine.lastOrNull { it.type == KtNodeTypes.REFERENCE_EXPRESSION } ?: return null
        return nomReference(reference)
    }
}
