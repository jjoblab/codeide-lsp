package jo.lspkotlin.resolve

import jo.lspkotlin.index.IndexAppelablesKotlin
import jo.lspkotlin.parse.NoeudSyntaxique
import jo.lspkotlin.parse.kt.KtFile

/**
 * Résolution des références SIMPLES par nom (K4) — le prédicat PARTAGÉ
 * du diagnostic `kt.unresolved` et des quick-fixes d'import : la chaîne
 * COMPLÈTE du modèle K3/K4 doit échouer pour qu'un nom soit déclaré
 * non résolu :
 *
 *  1. locales/paramètres en portée (la plus proche gagne) ;
 *  2. membres des classes englobantes (this implicite) ;
 *  3. déclarations de premier niveau du FICHIER ;
 *  4. déclarations du PROJET (scan — tous genres) ;
 *  5. classes : nom écrit résolvable (imports, paquet courant, imports
 *     par défaut de Kotlin) ou classe homonyme du projet ;
 *  6. appelables de premier niveau du CLASSPATH **en portée du
 *     fichier** (index Kotlin, `println`… — ADR 0203 : paquet courant,
 *     import exact, joker ou paquet par défaut ; le prédicat est
 *     partagé avec le survol d'appelable en portée).
 *
 * Conservateur (D3) : une voie qui réussit suffit — on ne distingue pas
 * les raisons d'échec, l'appelant tranche (diagnostic ou candidat).
 *
 * Exemptions detekt ciblées (règle 16) : LongParameterList — le
 * CONTEXTE de résolution complet est FILÉ (patron TypageExpression) ;
 * ReturnCount — chaque voie est une ISSUE distincte du catalogue.
 */
@Suppress("LongParameterList", "ReturnCount")
internal object ResolutionReferences {
    /**
     * Le nom [nom] utilisé à [decalage] résout-il par QUELCONQUE voie ?
     *
     * @param racine racine du document porteur.
     * @param decalage position de la référence.
     * @param nom nom de la référence.
     * @param contexteNom contexte de nommage (imports, paquet, projet).
     * @param resolution résolveur classpath.
     * @param declarationsProjet déclarations du projet (document d'abord).
     * @param index appelables de premier niveau du classpath (`null` en
     *        régime dégradé — la voie 6 échoue alors silencieusement).
     */
    internal fun estResolu(
        racine: NoeudSyntaxique,
        decalage: Int,
        nom: String,
        contexteNom: ResolutionKotlin.ContexteNom,
        resolution: ResolutionKotlin,
        declarationsProjet: List<DeclarationProjet>,
        index: IndexAppelablesKotlin?,
    ): Boolean {
        // 1. Locales/paramètres en portée.
        if (SymbolesProjet.localesEnPortee(racine, decalage).any { it.declaration.nom == nom }) return true
        // 2. Membres des classes englobantes (this implicite).
        if (SymbolesProjet.membresEnglobants(racine, decalage).any { it.nom() == nom }) return true
        // 2 bis. K5 : membre du RÉCEPTEUR IMPLICITE d'un `with`
        // englobant (classpath ou projet — sinon faux `kt.unresolved`
        // sur TOUT nom NU dans un corps de `with`).
        RecepteursWith.recepteurImplicite(racine, decalage, contexteNom, resolution, declarationsProjet)?.let { fqcn ->
            if (resolution.champsNommes(fqcn, nom).isNotEmpty()) return true
            if (resolution.methodesNommees(fqcn, nom).isNotEmpty()) return true
            if (declarationsProjet.any { it.conteneur == fqcn && it.nom == nom }) return true
        }
        // 3. Déclarations de premier niveau du FICHIER.
        if (KtFile(racine).declarations().any { it.nom() == nom }) return true
        // 4. Déclarations du PROJET (tous genres — objets, alias, entrées).
        if (declarationsProjet.any { it.nom == nom }) return true
        // 5. Classes : nom écrit résolvable ou classe homonyme du projet.
        if (resolution.fqcnDuNomEcrit(nom, contexteNom) != null) return true
        if (contexteNom.classesProjet.values.any { it.endsWith(".$nom") }) return true
        // 6. Appelables de premier niveau du CLASSPATH (println…) —
        //    EN PORTÉE du fichier (ADR 0203) : paquet courant, import
        //    exact, joker, ou paquet par défaut de Kotlin. Sans la
        //    preuve de visibilité, TOUT appelable indexé résolvait un
        //    nom nu (`PI`/`sqrt` sans import passaient sous silence le
        //    diagnostic que kotlinc signale) ; le prédicat est le MÊME
        //    que celui du survol d'appelable en portée — les extensions
        //    homonymes restent sondées (corps de `with` : un nom nu y
        //    résout via l'extension importée du récepteur implicite).
        val appelablesPortee = index?.topParNom(nom).orEmpty()
        if (appelablesPortee.any { appelable -> resolution.appelableEnPortee(appelable, contexteNom) }) return true
        return false
    }
}
