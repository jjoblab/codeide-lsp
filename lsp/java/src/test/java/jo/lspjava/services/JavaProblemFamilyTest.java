package jo.lspjava.services;

import org.eclipse.jdt.core.compiler.IProblem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests du mapping ID ecj → famille sémantique ({@link JavaProblemFamily}).
 *
 * <p>Ces tests verrouillent le dispatch ID-FIRST : chaque ID ecj testé
 * doit mapper vers la même famille, indépendamment de la Locale de
 * l'utilisateur. C'est la condition pour que les quick-fixes soient
 * fiables sur une VM française/espagnole/etc.</p>
 *
 * @author jo@Dev
 */
class JavaProblemFamilyTest {

    @Test
    void undefinedTypesMapToUnresolvedReference() {
        assertEquals(JavaProblemFamily.UNRESOLVED_REFERENCE,
                JavaProblemFamily.fromId(IProblem.UndefinedType));
        assertEquals(JavaProblemFamily.UNRESOLVED_REFERENCE,
                JavaProblemFamily.fromId(IProblem.UndefinedName));
        assertEquals(JavaProblemFamily.UNRESOLVED_REFERENCE,
                JavaProblemFamily.fromId(IProblem.UnresolvedVariable));
        assertEquals(JavaProblemFamily.UNRESOLVED_REFERENCE,
                JavaProblemFamily.fromId(IProblem.UndefinedField));
        assertEquals(JavaProblemFamily.UNRESOLVED_REFERENCE,
                JavaProblemFamily.fromId(IProblem.ImportNotFound));
    }

    /**
     * UndefinedMethod est détaché de UNRESOLVED_REFERENCE — le quick-fix
     * « Importer X » ne s'applique pas (un nom de méthode n'est pas un type)
     * et le portage CreateMethodFromUsage du projet de référence prend le relais
     * (stub private/private static dans le type englobant).
     */
    @Test
    void undefinedMethodMapsToItsOwnFamily() {
        assertEquals(JavaProblemFamily.UNDEFINED_METHOD,
                JavaProblemFamily.fromId(IProblem.UndefinedMethod));
    }

    @Test
    void unusedWarningMapsToUnusedFamily() {
        // UnusedImport a sa propre famille — les autres à UNUSED_DECLARATION.
        assertEquals(JavaProblemFamily.UNUSED_IMPORT,
                JavaProblemFamily.fromId(IProblem.UnusedImport));
        assertEquals(JavaProblemFamily.UNUSED_DECLARATION,
                JavaProblemFamily.fromId(IProblem.LocalVariableIsNeverUsed));
        assertEquals(JavaProblemFamily.UNUSED_DECLARATION,
                JavaProblemFamily.fromId(IProblem.ArgumentIsNeverUsed));
        assertEquals(JavaProblemFamily.UNUSED_DECLARATION,
                JavaProblemFamily.fromId(IProblem.UnusedPrivateMethod));
        assertEquals(JavaProblemFamily.UNUSED_DECLARATION,
                JavaProblemFamily.fromId(IProblem.UnusedPrivateField));
        assertEquals(JavaProblemFamily.UNUSED_DECLARATION,
                JavaProblemFamily.fromId(IProblem.UnusedPrivateType));
    }

    @Test
    void unhandledExceptionMapsToUncaughtExceptionFamily() {
        assertEquals(JavaProblemFamily.UNCAUGHT_EXCEPTION,
                JavaProblemFamily.fromId(IProblem.UnhandledException));
        assertEquals(JavaProblemFamily.UNCAUGHT_EXCEPTION,
                JavaProblemFamily.fromId(IProblem.UnhandledExceptionInDefaultConstructor));
        assertEquals(JavaProblemFamily.UNCAUGHT_EXCEPTION,
                JavaProblemFamily.fromId(IProblem.UnhandledExceptionInImplicitConstructorCall));
        assertEquals(JavaProblemFamily.UNCAUGHT_EXCEPTION,
                JavaProblemFamily.fromId(IProblem.UnhandledExceptionOnAutoClose));
    }

    @Test
    void typeMismatchMapsToTypeMismatchFamily() {
        assertEquals(JavaProblemFamily.TYPE_MISMATCH,
                JavaProblemFamily.fromId(IProblem.TypeMismatch));
        assertEquals(JavaProblemFamily.TYPE_MISMATCH,
                JavaProblemFamily.fromId(IProblem.ReturnTypeMismatch));
        assertEquals(JavaProblemFamily.TYPE_MISMATCH,
                JavaProblemFamily.fromId(IProblem.IncompatibleReturnType));
    }

    @Test
    void missingSemicolonMapsToMissingSemi() {
        assertEquals(JavaProblemFamily.MISSING_SEMI,
                JavaProblemFamily.fromId(IProblem.MissingSemiColon));
    }

    @Test
    void unmappedIdsReturnUnknown() {
        // Un ID non mappé ne doit pas déclencher de quick-fix ID-FIRST.
        assertEquals(JavaProblemFamily.UNKNOWN,
                JavaProblemFamily.fromId(IProblem.ParsingError));
        assertEquals(JavaProblemFamily.UNKNOWN,
                JavaProblemFamily.fromId(IProblem.ParsingErrorInsertTokenBefore));
    }

    @Test
    void fromCodeParsesPrefixedCode() {
        // Code au format "ecj:<ID>" (depuis Diagnostic.code()).
        assertEquals(JavaProblemFamily.TYPE_MISMATCH,
                JavaProblemFamily.fromCode("ecj:" + IProblem.TypeMismatch));
        // Code sans préfixe (ID numérique brut).
        assertEquals(JavaProblemFamily.TYPE_MISMATCH,
                JavaProblemFamily.fromCode(String.valueOf(IProblem.TypeMismatch)));
        // Code invalide.
        assertEquals(JavaProblemFamily.UNKNOWN,
                JavaProblemFamily.fromCode(null));
        assertEquals(JavaProblemFamily.UNKNOWN,
                JavaProblemFamily.fromCode("ecj:PasUnNombre"));
        assertEquals(JavaProblemFamily.UNKNOWN,
                JavaProblemFamily.fromCode("garbage"));
    }
}
