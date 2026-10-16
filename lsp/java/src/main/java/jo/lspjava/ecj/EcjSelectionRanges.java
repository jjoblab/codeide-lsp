package jo.lspjava.ecj;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.AllocationExpression;
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.ArrayAllocationExpression;
import org.eclipse.jdt.internal.compiler.ast.ArrayInitializer;
import org.eclipse.jdt.internal.compiler.ast.ArrayReference;
import org.eclipse.jdt.internal.compiler.ast.AND_AND_Expression;
import org.eclipse.jdt.internal.compiler.ast.Assignment;
import org.eclipse.jdt.internal.compiler.ast.BinaryExpression;
import org.eclipse.jdt.internal.compiler.ast.Block;
import org.eclipse.jdt.internal.compiler.ast.BreakStatement;
import org.eclipse.jdt.internal.compiler.ast.CastExpression;
import org.eclipse.jdt.internal.compiler.ast.ClassLiteralAccess;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.CompoundAssignment;
import org.eclipse.jdt.internal.compiler.ast.ConditionalExpression;
import org.eclipse.jdt.internal.compiler.ast.ConstructorDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ContinueStatement;
import org.eclipse.jdt.internal.compiler.ast.DoStatement;
import org.eclipse.jdt.internal.compiler.ast.EqualExpression;
import org.eclipse.jdt.internal.compiler.ast.ExplicitConstructorCall;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldReference;
import org.eclipse.jdt.internal.compiler.ast.ForeachStatement;
import org.eclipse.jdt.internal.compiler.ast.ForStatement;
import org.eclipse.jdt.internal.compiler.ast.IfStatement;
import org.eclipse.jdt.internal.compiler.ast.Initializer;
import org.eclipse.jdt.internal.compiler.ast.InstanceOfExpression;
import org.eclipse.jdt.internal.compiler.ast.LabeledStatement;
import org.eclipse.jdt.internal.compiler.ast.LambdaExpression;
import org.eclipse.jdt.internal.compiler.ast.LocalDeclaration;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.OR_OR_Expression;
import org.eclipse.jdt.internal.compiler.ast.PostfixExpression;
import org.eclipse.jdt.internal.compiler.ast.PrefixExpression;
import org.eclipse.jdt.internal.compiler.ast.QualifiedAllocationExpression;
import org.eclipse.jdt.internal.compiler.ast.QualifiedNameReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedThisReference;
import org.eclipse.jdt.internal.compiler.ast.ReferenceExpression;
import org.eclipse.jdt.internal.compiler.ast.ReturnStatement;
import org.eclipse.jdt.internal.compiler.ast.StringLiteral;
import org.eclipse.jdt.internal.compiler.ast.StringLiteralConcatenation;
import org.eclipse.jdt.internal.compiler.ast.SuperReference;
import org.eclipse.jdt.internal.compiler.ast.SwitchExpression;
import org.eclipse.jdt.internal.compiler.ast.SwitchStatement;
import org.eclipse.jdt.internal.compiler.ast.SynchronizedStatement;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.ThisReference;
import org.eclipse.jdt.internal.compiler.ast.ThrowStatement;
import org.eclipse.jdt.internal.compiler.ast.TryStatement;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.UnaryExpression;
import org.eclipse.jdt.internal.compiler.ast.WhileStatement;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.lookup.ClassScope;
import org.eclipse.jdt.internal.compiler.lookup.CompilationUnitScope;
import org.eclipse.jdt.internal.compiler.lookup.MethodScope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Plages de sélection ({@code textDocument/selectionRange}) et cible de
 * formatage à la frappe ({@code textDocument/onTypeFormatting}) — lot S2-b
 * du plan de stabilisation §3.1.
 *
 * <h2>Chaîne de sélection</h2>
 * Pour chaque position demandée, la CHAÎNE des nœuds AST englobants,
 * du plus intérieur au plus extérieur (unité de compilation), chaque
 * plage contenant strictement la précédente. Les déclarations portent
 * DEUX niveaux : le jeton NOM (plage exacte du texte du nom) puis la
 * déclaration COMPLÈTE ({@code declarationSourceStart/End} — javadoc et
 * modifieurs inclus). Les plages dupliquées (nœuds aux mêmes bornes) sont
 * dédupliquées — le client reçoit une chaîne STRICTEMENT croissante,
 * contracte LSP ({@code SelectionRange.parent}).
 *
 * <h2>Cible de formatage à la frappe</h2>
 * Le caractère venant d'être tapé ({@code '}}' ou {@code ';'}) délimite la
 * construction à reformater : le nœud AST qui SE TERMINE à ce caractère
 * (accolade fermante d'un bloc/corps de type pour {@code '}'} ; fin de
 * l'instruction pour {@code ';'}). Repli : le nœud intérieur CONTENANT le
 * caractère (plafonné) — jamais de mutilation, l'appelant applique les
 * éditions du formateur Eclipse dans cette plage seule.
 *
 * @author jo@Dev
 */
public final class EcjSelectionRanges {

    /** Une plage [debut, fin) en offsets de caractères. */
    public record Plage(int debut, int fin) {}

    /** Plafond du repli « nœud contenant » pour le formatage à la frappe. */
    static final int PLAFOND_CIBLE_FORMAT = 60_000;

    /** Fenêtre de tolérance autour de l'offset tapé (le caractère lui-même). */
    static final int FENETRE_FIN = 2;

    private final EcjCompilerEngine engine;

    public EcjSelectionRanges(EcjCompilerEngine engine) {
        this.engine = engine;
    }

    // ════════════════════════════════════════════════════════════════
    // selectionRange
    // ════════════════════════════════════════════════════════════════

    /**
     * La chaîne des nœuds englobant {@code offset}, du plus intérieur au
     * plus extérieur (plages STRICTEMENT croissantes en longueur).
     *
     * @return la chaîne (vide si le moteur est indisponible ou l'unité
     *         imparsable — jamais null)
     */
    public List<Plage> chaineSelection(String filePath, String source, int offset) {
        List<Plage> out = new ArrayList<>();
        if (EcjCompilerEngine.DISABLED || source == null || source.isEmpty()
                || offset < 0 || offset >= source.length()) {
            return out;
        }
        try {
            CompilationUnitDeclaration cud = engine.resolveUnit(filePath, source, false);
            if (cud == null) return out;

            Collecteur collecteur = new Collecteur(source, offset, false);
            EcjAst.traverseResilient(cud, collecteur);
            collecteur.plages.sort(
                    Comparator.comparingInt((Plage p) -> p.fin() - p.debut()));
            // Dédup + garde de croissance stricte (un nœud englobant peut
            // partager les bornes d'un enfant — p.ex. LabeledStatement).
            Set<Plage> vus = new LinkedHashSet<>();
            for (Plage p : collecteur.plages) {
                if (p.debut() < 0 || p.fin() > source.length() || p.fin() <= p.debut()) {
                    continue;
                }
                if (vus.add(p)) out.add(p);
            }
            // Le sommet : l'unité entière (le caret y est par construction).
            Plage unite = new Plage(0, source.length());
            if (vus.add(unite)) out.add(unite);
        } catch (Throwable ignored) {
            // Unité pathologique — chaîne partielle (peut être vide).
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════
    // onTypeFormatting
    // ════════════════════════════════════════════════════════════════

    /**
     * La plage AST à reformater pour le caractère venant d'être tapé à
     * {@code offsetDuCaractere} (l'offset DU caractère, pas après lui).
     *
     * @param declencheur le caractère tapé ('}' ou ';' — les déclencheurs
     *                    annoncés dans la capability)
     * @return la plage, ou null (aucune cible — l'appelant ne formate pas)
     */
    public Plage plageFormattable(String filePath, String source,
                                  int offsetDuCaractere, char declencheur) {
        if (EcjCompilerEngine.DISABLED || source == null || source.isEmpty()
                || offsetDuCaractere < 0
                || offsetDuCaractere >= source.length()
                || source.charAt(offsetDuCaractere) != declencheur) {
            return null;
        }
        if (declencheur != '}' && declencheur != ';') return null;
        try {
            CompilationUnitDeclaration cud = engine.resolveUnit(filePath, source, false);
            if (cud == null) return null;

            // Les nœuds ENGLOBANT le caractère tapé (le '}' d'un bloc en
            // fait partie : sourceEnd du Block = l'accolade elle-même).
            Collecteur collecteur = new Collecteur(
                    source, offsetDuCaractere, true);
            collecteur.relaxe = true;
            EcjAst.traverseResilient(cud, collecteur);
            List<Plage> candidates = new ArrayList<>(collecteur.plages);
            candidates.removeIf(p -> p.fin() <= p.debut()
                    || p.fin() > source.length()
                    || p.fin() - p.debut() > PLAFOND_CIBLE_FORMAT);
            if (candidates.isEmpty()) return null;
            candidates.sort(Comparator.comparingInt(
                    (Plage p) -> p.fin() - p.debut()));
            // Préférence : le nœud qui SE TERMINE au caractère tapé
            // (l'accolade fermante / le point-virgule) — le plus petit
            // d'abord ; repli : le plus petit nœud CONTENANT le caractère.
            for (Plage p : candidates) {
                if (p.fin() == offsetDuCaractere + 1) return p;
            }
            return candidates.get(0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Collecteur de nœuds
    // ════════════════════════════════════════════════════════════════

    /**
     * Le visiteur qui enregistre les nœuds pertinents. Mode 1 (sélection) :
     * les nœuds CONTENANT l'offset — avec le niveau « jeton nom » pour les
     * déclarations. Mode 2 (formatage) : les nœuds contenant l'offset SANS
     * niveau nom (une plage nom n'est pas une cible de formatage).
     *
     * <p>Les types d'AST couverts : unité, types (tous scopes), méthodes/
     * constructeurs/initialiseurs, champs, locales/arguments, blocs et
     * instructions, expressions. Un nœud non couvert (famille exotique)
     * n'ajoute pas de niveau — la chaîne reste valide (jamais fausse).</p>
     */
    private static final class Collecteur extends org.eclipse.jdt.internal.compiler.ASTVisitor {

        private final String source;
        private final int offset;
        private final boolean niveauNom;
        /** Mode formatage : les nœuds finissant JUSTE AVANT le caractère
         *  tapé comptent aussi (instruction dont le ';' est hors nœud). */
        boolean relaxe;
        final List<Plage> plages = new ArrayList<>();

        Collecteur(String source, int offset, boolean pourFormat) {
            this.source = source;
            this.offset = offset;
            this.niveauNom = !pourFormat;
        }

        /** La déclaration complète (les nœuds déclaration portent leur
         *  plage COMPLÈTE dans declarationSourceStart/End — champs présents
         *  sur les familles déclaration, pas sur ASTNode). */
        private void declaration(ASTNode n, int declDebut, int declFin) {
            int from = declDebut != 0 ? declDebut : n.sourceStart;
            int to = declFin != 0 ? declFin : n.sourceEnd;
            enregistrer(from, to + 1);
        }

        /** Le nœud expression/instruction simple : [sourceStart, sourceEnd]. */
        private void noeud(ASTNode n) {
            enregistrer(n.sourceStart, n.sourceEnd + 1);
        }

        /** Le niveau « jeton nom » d'une déclaration — le nom tel qu'écrit,
         *  retrouvé par frontières de mot dans la fenêtre [depuis, jusqu'à).
         *  La plage n'est retenue QUE si elle contient l'offset : la fenêtre
         *  d'une déclaration peut être plus large que le caret (p.ex. les
         *  paramètres d'une signature vue en entier). */
        private void jetonNom(int depuis, int jusquA, char[] nom) {
            if (!niveauNom || nom == null || nom.length == 0) return;
            int[] plage = EcjNavigationEngine.nameRange(
                    source, depuis, Math.min(jusquA, source.length() - 1),
                    String.valueOf(nom));
            if (plage != null && plage[0] <= offset && offset < plage[1]) {
                plages.add(new Plage(plage[0], plage[1]));
            }
        }

        private void enregistrer(int debut, int fin) {
            if (relaxe) {
                // Mode formatage : contient le caractère OU finit juste
                // avant (instruction dont le ';' n'appartient pas au nœud).
                if (debut <= offset && offset <= fin) {
                    plages.add(new Plage(debut, fin));
                }
                return;
            }
            // Mode sélection : le nœud doit CONTENIR l'offset (bornes incluses).
            if (debut <= offset && offset < fin) {
                plages.add(new Plage(debut, fin));
            }
        }

        // ── unité ───────────────────────────────────────────────────

        @Override
        public boolean visit(CompilationUnitDeclaration cud, CompilationUnitScope s) {
            // L'unité est ajoutée explicitement par chaineSelection().
            return true;
        }

        // ── types ───────────────────────────────────────────────────

        @Override
        public boolean visit(TypeDeclaration td, CompilationUnitScope s) {
            type(td);
            return true;
        }

        @Override
        public boolean visit(TypeDeclaration td, ClassScope s) {
            type(td);
            return true;
        }

        @Override
        public boolean visit(TypeDeclaration td, BlockScope s) {
            type(td);
            return true;
        }

        private void type(TypeDeclaration td) {
            if (td.name == null || td.name.length == 0) {
                // Type anonyme : la déclaration seule.
                declaration(td, td.declarationSourceStart, td.declarationSourceEnd);
                return;
            }
            // Niveau nom : la plage exacte du nom (sourceStart/sourceEnd
            // d'un TypeDeclaration = le nom seul, cf. EcjNavigationEngine).
            if (td.sourceEnd >= td.sourceStart) {
                jetonNom(td.sourceStart, td.sourceEnd + 1, td.name);
            }
            declaration(td, td.declarationSourceStart, td.declarationSourceEnd);
        }

        // ── membres ─────────────────────────────────────────────────

        @Override
        public boolean visit(MethodDeclaration md, ClassScope s) {
            if (md.selector != null && md.bodyStart > md.sourceStart) {
                jetonNom(md.sourceStart, md.bodyStart, md.selector);
            }
            declaration(md, md.declarationSourceStart, md.declarationSourceEnd);
            return true;
        }

        @Override
        public boolean visit(ConstructorDeclaration cd, ClassScope s) {
            if (cd.selector != null && cd.bodyStart > cd.sourceStart) {
                jetonNom(cd.sourceStart, cd.bodyStart, cd.selector);
            }
            declaration(cd, cd.declarationSourceStart, cd.declarationSourceEnd);
            return true;
        }

        @Override
        public boolean visit(Initializer init, MethodScope s) {
            declaration(init, init.declarationSourceStart,
                    init.declarationSourceEnd);
            return true;
        }

        @Override
        public boolean visit(FieldDeclaration fd, MethodScope s) {
            champ(fd);
            return true;
        }

        private void champ(FieldDeclaration fd) {
            if (fd.name != null && fd.name.length > 0) {
                jetonNom(fd.sourceStart, fd.sourceEnd + 1, fd.name);
            }
            declaration(fd, fd.declarationSourceStart, fd.declarationSourceEnd);
        }

        @Override
        public boolean visit(LocalDeclaration ld, BlockScope s) {
            if (ld.name != null && ld.name.length > 0) {
                jetonNom(ld.sourceStart, ld.sourceEnd + 1, ld.name);
            }
            // La DÉCLARATION complète (« int y=2; » — declarationSourceEnd
            // inclut le « ; ») : en ecj, sourceStart/End d'une locale = le
            // NOM seul. C'est elle qui sert de cible de formatage au « ; ».
            declaration(ld, ld.declarationSourceStart, ld.declarationSourceEnd);
            return true;
        }

        @Override
        public boolean visit(Argument a, BlockScope s) {
            if (a.name != null && a.name.length > 0) {
                jetonNom(a.sourceStart, a.sourceEnd + 1, a.name);
            }
            declaration(a, a.declarationSourceStart, a.declarationSourceEnd);
            return true;
        }

        // ── instructions ────────────────────────────────────────────

        @Override
        public boolean visit(Block b, BlockScope s) { noeud(b); return true; }

        @Override
        public boolean visit(IfStatement st, BlockScope s) { noeud(st); return true; }

        @Override
        public boolean visit(ForStatement st, BlockScope s) { noeud(st); return true; }

        @Override
        public boolean visit(ForeachStatement st, BlockScope s) { noeud(st); return true; }

        @Override
        public boolean visit(WhileStatement st, BlockScope s) { noeud(st); return true; }

        @Override
        public boolean visit(DoStatement st, BlockScope s) { noeud(st); return true; }

        @Override
        public boolean visit(SwitchStatement st, BlockScope s) { noeud(st); return true; }

        @Override
        public boolean visit(SwitchExpression st, BlockScope s) { noeud(st); return true; }

        @Override
        public boolean visit(TryStatement st, BlockScope s) { noeud(st); return true; }

        @Override
        public boolean visit(ThrowStatement st, BlockScope s) { noeud(st); return true; }

        @Override
        public boolean visit(ReturnStatement st, BlockScope s) { noeud(st); return true; }

        @Override
        public boolean visit(BreakStatement st, BlockScope s) { noeud(st); return true; }

        @Override
        public boolean visit(ContinueStatement st, BlockScope s) { noeud(st); return true; }

        @Override
        public boolean visit(LabeledStatement st, BlockScope s) { noeud(st); return true; }

        @Override
        public boolean visit(SynchronizedStatement st, BlockScope s) { noeud(st); return true; }

        @Override
        public boolean visit(ExplicitConstructorCall call, BlockScope s) {
            noeud(call);
            return true;
        }

        // ── expressions ─────────────────────────────────────────────

        @Override
        public boolean visit(Assignment e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(CompoundAssignment e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(BinaryExpression e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(EqualExpression e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(AND_AND_Expression e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(OR_OR_Expression e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(UnaryExpression e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(PrefixExpression e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(PostfixExpression e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(MessageSend e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(SingleNameReference e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(SingleNameReference e, ClassScope s) { noeud(e); return true; }

        @Override
        public boolean visit(QualifiedNameReference e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(QualifiedNameReference e, ClassScope s) { noeud(e); return true; }

        @Override
        public boolean visit(FieldReference e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(FieldReference e, ClassScope s) { noeud(e); return true; }

        @Override
        public boolean visit(ArrayReference e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(ThisReference e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(QualifiedThisReference e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(SuperReference e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(CastExpression e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(InstanceOfExpression e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(ConditionalExpression e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(AllocationExpression e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(QualifiedAllocationExpression e, BlockScope s) {
            noeud(e);
            return true;
        }

        @Override
        public boolean visit(ArrayAllocationExpression e, BlockScope s) {
            noeud(e);
            return true;
        }

        @Override
        public boolean visit(ArrayInitializer e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(StringLiteral e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(StringLiteralConcatenation e, BlockScope s) {
            noeud(e);
            return true;
        }

        @Override
        public boolean visit(ClassLiteralAccess e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(LambdaExpression e, BlockScope s) { noeud(e); return true; }

        @Override
        public boolean visit(ReferenceExpression e, BlockScope s) { noeud(e); return true; }
    }
}
