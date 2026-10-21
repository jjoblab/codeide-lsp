package jo.lspjava.services;

import org.eclipse.jdt.core.compiler.IProblem;

import java.util.Locale;

/**
 * Familles sémantiques de problèmes ecj — le pattern {@code JavaProblemCodes}
 * du projet tiers de référence porté en Java.
 *
 * <p>Piége évité : brancher les quick-fixes sur des {@code contains()} du
 * MESSAGE localisé (« cannot be resolved », « Unused », « must be
 * caught »…) est fragile — ce message varie avec la Locale de
 * l'utilisateur : sur une VM française, « cannot be resolved » devient
 * « ne peut pas être résolu » et le quick-fix disparaît. projet tiers de référence fait
 * l'inverse : chaque {@link IProblem#getID()} est mappé à une famille
 * sémantique STABLE, et le quick-fix lit les arguments STRUCTURÉS
 * ({@code IProblem.getArguments()} : le FQN manquant, le type introuvable)
 * — jamais le texte.</p>
 *
 * <p>Cette classe est l'équivalent : un {@code int} (l'ID ecj) → une famille
 * énumérée. {@link CodeActionService} lit la famille et les arguments pour
 * dériver un quick-fix concret (insertion de l'import exact, cast du type
 * attendu, etc.).</p>
 *
 * <p><strong>Note :</strong> les IDs ecj sont des constantes publiques de
 * {@link IProblem} — ils sont stables entre versions d'Eclipse JDT (la
 * politique d'Eclipse est de ne JAMAIS réutiliser un ID pour un problème
 * différent, seulement d'en ajouter). On peut donc les mapper sans risque
 * de rupture entre montées de version.</p>
 *
 * @author jo@Dev
 */
public enum JavaProblemFamily {

    /** Type, variable ou champ non résolu(e) — import manquant,
     *  faute de frappe, ou référence à un symbole inexistant.
     *  {@code getArguments()[0]} contient le nom du symbole. */
    UNRESOLVED_REFERENCE,

    /** Méthode non résolue sur CE type ({@code foo(args)} /
     *  {@code this.foo(args)}) — créer le stub de méthode (portage
     *  {@code CreateMethodFromUsageQuickFixProvider} du projet tiers de référence).
     *  {@code getArguments()} = [FQN du propriétaire, nom, typeP0,
     *  typeP1, …] (types séparés — calibré empiriquement sur ecj 3.40). */
    UNDEFINED_METHOD,

    /** Import inutilisé — supprimer la ligne. */
    UNUSED_IMPORT,

    /** Variable locale / paramètre / champ privé non utilisé. */
    UNUSED_DECLARATION,

    /** Méthode peut lever une exception vérifiée non attrapée —
     *  entourer d'un try-catch ou déclarer throws. */
    UNCAUGHT_EXCEPTION,

    /** Incompatibilité de type — insérer un cast vers le type attendu.
     *  {@code getArguments()} contient les deux types. */
    TYPE_MISMATCH,

    /** Point-virgule manquant en fin de statement. */
    MISSING_SEMI,

    /** Famille non encore mappée — le repli message.contains()
     *  reste disponible en secours. */
    UNKNOWN;

    /**
     * Mappe un ID ecj vers une famille sémantique stable.
     *
     * @param code le code au format {@code "ecj:<ID>"} (depuis
     *             {@link jo.lspjava.api.Diagnostic#code()}), ou directement
     *             l'ID numérique en string.
     * @return la famille, {@link #UNKNOWN} si non mappée.
     */
    public static JavaProblemFamily fromCode(String code) {
        if (code == null) return UNKNOWN;
        String id = code.startsWith("ecj:") ? code.substring(4) : code;
        int n;
        try {
            n = Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return UNKNOWN;
        }
        return fromId(n);
    }

    /**
     * Mappe un ID ecj ({@link IProblem#getID()}) vers une famille sémantique.
     *
     * <p>Les IDs proviennent des constantes publiques de {@link IProblem}.
     * Chaque montée de version d'Eclipse JDT peut ajouter de nouveaux IDs
     * mais ne réutilise jamais un ID existant.</p>
     */
    public static JavaProblemFamily fromId(int problemId) {
        // IDs ecj de référence (constantes de IProblem). Pour la robustesse
        // face aux évolutions ecj, on teste par plage ET par ID explicite
        // quand la plage est ambiguë.
        // UndefinedMethod est détaché de UNRESOLVED_REFERENCE : le fix
        // d'import ne s'applique pas (un nom de méthode n'est pas un type)
        // et le portage CreateMethodFromUsage du projet tiers de référence prend le
        // relais (stub private/private static dans le type englobant).
        if (problemId == IProblem.UndefinedMethod) return UNDEFINED_METHOD;
        if (isUnresolved(problemId)) return UNRESOLVED_REFERENCE;
        if (problemId == IProblem.UnusedImport) return UNUSED_IMPORT;
        if (isUnused(problemId)) return UNUSED_DECLARATION;

        // UncaughtException — couvre les sous-cas « need throws / need
        // try-catch / auto-close ». Tous les IDs vérifiés existent en ecj
        // 3.40 (cf. IProblemConstantsTest).
        if (problemId == IProblem.UnhandledException
                || problemId == IProblem.UnhandledExceptionInDefaultConstructor
                || problemId == IProblem.UnhandledExceptionInImplicitConstructorCall
                || problemId == IProblem.UnhandledExceptionOnAutoClose) {
            return UNCAUGHT_EXCEPTION;
        }

        // Type mismatch — plusieurs variantes (affectation, retour, pattern).
        if (problemId == IProblem.TypeMismatch
                || problemId == IProblem.ReturnTypeMismatch
                || problemId == IProblem.IncompatibleReturnType
                || problemId == IProblem.InheritedIncompatibleReturnType) {
            return TYPE_MISMATCH;
        }

        // Point-virgule manquant — l'ID direct MissingSemiColon existe en
        // ecj 3.40 (cf. IProblemConstantsTest, ID=1610612960). On évite
        // d'interpréter ParsingError* (recovery plus large) pour ne pas
        // déclencher le quick-fix sur d'autres erreurs de parsing.
        if (problemId == IProblem.MissingSemiColon) return MISSING_SEMI;

        return UNKNOWN;
    }

    /**
     * Vérifie si l'ID ecj correspond à un problème de résolution de référence
     * (méthode, type, nom, champ, etc.) — plage des IDs « Undefined* » et
     * « Unresolved* ».
     */
    private static boolean isUnresolved(int id) {
        switch (id) {
            case IProblem.UndefinedName:
            case IProblem.UndefinedType:
            case IProblem.UnresolvedVariable:
            case IProblem.UndefinedField:
            case IProblem.UndefinedLabel:
            case IProblem.UndefinedModule:
            case IProblem.UndefinedModuleAddReads:
            case IProblem.UndefinedConstructor:
            case IProblem.UndefinedTypeVariable:
            case IProblem.ImportNotFound:
                return true;
            default:
                return false;
        }
    }

    /** Plage des warnings « unused » (local, param, champ privé, méthode,
     *  type, allocation) — À L'EXCLUSION de UnusedImport qui est géré
     *  séparément (famille {@link #UNUSED_IMPORT}). */
    private static boolean isUnused(int id) {
        switch (id) {
            case IProblem.LocalVariableIsNeverUsed:
            case IProblem.ArgumentIsNeverUsed:
            case IProblem.UnusedPrivateMethod:
            case IProblem.UnusedPrivateType:
            case IProblem.UnusedPrivateField:
            case IProblem.UnusedPrivateConstructor:
            case IProblem.UnusedObjectAllocation:
            case IProblem.UnusedLabel:
            case IProblem.UnusedWarningToken:
                return true;
            default:
                return false;
        }
    }

    /**
     * Pour un problème de parsing (insertion de token), retourne le token
     * à insérer si c'est un point-virgule — sinon null.
     *
     * @param arguments les arguments structurés du problème ecj
     *                 ({@link IProblem#getArguments()})
     */
    public static String semiIfMissing(String[] arguments) {
        if (arguments == null || arguments.length == 0) return null;
        for (String a : arguments) {
            if (";".equals(a)) return ";";
        }
        return null;
    }
}
