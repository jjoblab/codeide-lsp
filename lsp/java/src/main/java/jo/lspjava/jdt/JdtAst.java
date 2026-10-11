package jo.lspjava.jdt;

import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.lookup.Scope;

/**
 * Traversée AST ecj « résiliente ».
 *
 * <p><strong>Pourquoi ce wrapper existe (repro AndroidClasspathReproTest) :</strong>
 * {@link CompilationUnitDeclaration#traverse} court-circuite TOUTE la
 * descente dès que {@code ignoreFurtherInvestigation == true} — le drapeau
 * est posé par ecj quand la hiérarchie du type est INCOHÉRENTE
 * (« The hierarchy of the type X is inconsistent »), typiquement quand un
 * jar transitoire manque au classpath (R non généré avant le premier build,
 * FragmentActivity absent de l'extraction AAR partielle, etc.). Conséquence
 * mesurée sur l'appareil : les CUD splicés de complétion contiennent bien
 * {@code types=1}, mais {@code traverse()} ne visite AUCUN nœud → aucun
 * contexte détecté → « completion: got 0 items » systématique.</p>
 *
 * <p><strong>Correctif :</strong> pour les usages LSP (complétion, hover,
 * signature help, rename…), la traversée doit avoir lieu même si la phase
 * d'analyse codegen a été abrégée. On neutralise donc le drapeau PENDANT la
 * traversée et on le restaure après (la traversée ne modifie pas l'AST ;
 * seule la lecture est concernée).</p>
 *
 * @author jo@Dev
 */
public final class JdtAst {

    private JdtAst() {}

    /**
     * Traverse {@code cud} avec {@code visitor} en neutralisant temporairement
     * {@code ignoreFurtherInvestigation}. Ne fait rien si l'unité ou sa portée
     * est absente. Les exceptions traversantes remontent à l'appelant (elles
     * sont déjà absorbées par les catch des moteurs).
     */
    public static void traverseResilient(CompilationUnitDeclaration cud,
                                  org.eclipse.jdt.internal.compiler.ASTVisitor visitor) {
        if (cud == null || cud.scope == null) return;
        boolean prev = cud.ignoreFurtherInvestigation;
        try {
            cud.ignoreFurtherInvestigation = false;
            cud.traverse(visitor, cud.scope);
        } finally {
            cud.ignoreFurtherInvestigation = prev;
        }
    }
}
