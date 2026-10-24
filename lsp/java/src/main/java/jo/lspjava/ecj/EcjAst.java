package jo.lspjava.ecj;

import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.QualifiedNameReference;
import org.eclipse.jdt.internal.compiler.lookup.Binding;
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
public final class EcjAst {

    private EcjAst() {}

    /**
     * Le binding DERNIER d'une référence qualifiée (ADR 0209 — correctif
     * audit navigation) : la convention ecj met le binding du PREMIER
     * segment dans {@code QualifiedNameReference.binding} (une locale,
     * un champ du premier maillon…) et le CHAÎNON RÉSOLU FINAL dans
     * {@code otherBindings} (vérifié empiriquement par le moteur de
     * renommage : {@code config.timeout} → binding=
     * LocalVariableBinding(config), otherBindings[0]=FieldBinding(timeout)).
     * Pour la sémantique go-to (définition, survol) du CARET sur
     * {@code a.tag}, la cible est le CHAMP {@code tag} — pas le
     * récepteur {@code a} : sans ce correctif, la définition d'un champ
     * qualifié sautait au RÉCEPTEUR (bug d'audit : le paramètre
     * {@code a} dans le fichier courant au lieu du champ dans le
     * fichier déclarant). Le renommage portait déjà la preuve
     * ({@code EcjRenameEngine.lastSegmentBinding} délègue ici).
     *
     * @param ref la référence qualifiée résolue
     * @return le binding du dernier chaînon ({@code ref.binding} quand
     *         la chaîne tient en un maillon), jamais null
     */
    public static Binding dernierBinding(QualifiedNameReference ref) {
        try {
            if (ref.otherBindings != null && ref.otherBindings.length > 0) {
                Binding dernier = ref.otherBindings[ref.otherBindings.length - 1];
                if (dernier != null) return dernier;
            }
        } catch (Throwable ignored) {
            // Chaîne non résolue — on retombe sur le premier maillon.
        }
        return ref.binding;
    }

    /**
     * Le binding du SEGMENT VISÉ PAR LE CARET dans une référence
     * qualifiée (ADR 0209) : un {@code QualifiedNameReference} est UN
     * nœud couvrant toute la chaîne — le segment i vit dans
     * {@code sourcePositions[i]} (plage [start&lt;&lt;32|end] par maillon),
     * le segment 0 porte {@code ref.binding} et les suivants
     * {@code otherBindings[i-1]}. Le caret sur le RÉCEPTEUR
     * ({@code a|} de {@code a.tag}) désigne la locale/le paramètre ; le
     * caret sur le CHAMP ({@code t|ag}) désigne le champ — jamais
     * l'inverse. Hors segment (offset hors bornes, positions absentes)
     * : le DERNIER chaînon (l'usage go-to vise le bout de la chaîne).
     *
     * @param ref    la référence qualifiée résolue
     * @param offset l'offset du caret (coordonnées de la source résolue)
     * @return le binding du segment visé, jamais null
     */
    public static Binding bindingAuCaret(QualifiedNameReference ref, int offset) {
        try {
            long[] positions = ref.sourcePositions;
            if (positions != null && positions.length > 0) {
                for (int i = 0; i < positions.length; i++) {
                    int debut = (int) (positions[i] >>> 32);
                    int fin = (int) (positions[i] & 0xFFFFFFFFL);
                    if (offset >= debut && offset <= fin) {
                        if (i == 0) {
                            return ref.binding != null ? ref.binding : dernierBinding(ref);
                        }
                        Binding[] autres = ref.otherBindings;
                        if (autres != null && i - 1 < autres.length && autres[i - 1] != null) {
                            return autres[i - 1];
                        }
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Positions absentes — repli sur le dernier chaînon.
        }
        return dernierBinding(ref);
    }

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
