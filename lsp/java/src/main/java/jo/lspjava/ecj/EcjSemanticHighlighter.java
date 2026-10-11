package jo.lspjava.ecj;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ConstructorDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldReference;
import org.eclipse.jdt.internal.compiler.ast.LocalDeclaration;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.QualifiedNameReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.SingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.lookup.ClassScope;
import org.eclipse.jdt.internal.compiler.lookup.CompilationUnitScope;
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding;
import org.eclipse.jdt.internal.compiler.lookup.LocalVariableBinding;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * Surlignage sémantique par bindings ecj — l'équivalent de la passe
 * SEMANTIC du daemon éditeur du projet de référence.
 *
 * <p><strong>Ce que ça apporte à l'utilisateur :</strong> le coloriseur
 * LEXICAL de l'éditeur devine « à vue » — un appel
 * {@code toast.makeText(...)} n'y est pas différencié d'une variable, un
 * type non importé reste monochrome. Ici chaque identifiant RÉSOLU par le
 * compilateur (types réels, méthodes, champs, locales, paramètres) est
 * recoloré par-dessus le lexique selon sa VRAIE nature : classes en couleur
 * TYPE, interfaces teintées annotation (palette existante), méthodes FUNC,
 * champs PROPERTY, paramètres/locales VARIABLE…</p>
 *
 * <p><strong>Protocole :</strong> exposé via {@code textDocument/semanticTokens/full}
 * (LSP standard) par {@code JavaTextDocumentService}, calculé sur la lane
 * ANALYSIS du {@code EcjEngineScheduler} (jamais devant la complétion), et
 * consommé par cel-lsp qui alimente {@code EditorSession.setSemanticTokens}
 * ; le rendu par spans existe déjà dans EditorRenderer (drawCachedSemSpans)
 * et c'est ce calcul qui l'alimente.</p>
 *
 * <p><strong>Légende :</strong> ordre canonique LSP pour rester aligné avec
 * le mapping fixe du rendu ({@code EditorView.semanticTypeToTokenType}) :
 * 2=class→TYPE, 3=enum→TYPE, 4=interface→ANNOTATION, 6/7=parameter/variable→VARIABLE,
 * 8=property→PROPERTY, 9/10=method/function→FUNC.</p>
 *
 * @author jo@Dev
 */
public final class EcjSemanticHighlighter {

    /** Types sémantiques — ordre canonique LSP (partagé avec le serveur). */
    public static final List<String> TOKEN_TYPES = List.of(
            "namespace", "type", "class", "enum", "interface", "struct",
            "parameter", "variable", "property", "method", "function");

    public static final int CLASS = 2;
    public static final int ENUM = 3;
    public static final int INTERFACE = 4;
    public static final int PARAMETER = 6;
    public static final int VARIABLE = 7;
    public static final int PROPERTY = 8;
    public static final int METHOD = 9;

    /**
     * Un token sémantique brut — offsets SOURCE (start inclusif, fin
     * exclusive), prêts pour l'encodage LSP delta côté service.
     */
    public record Span(int kind, int start, int length) {}

    private final EcjCompilerEngine engine;

    public EcjSemanticHighlighter(EcjCompilerEngine engine) {
        this.engine = engine;
    }

    /**
     * Calcule les tokens sémantiques de TOUTE l'unité.
     *
     * @return liste triée par offset croissant, sans recouvrements (le token
     *         le plus long gagne) ; vide si l'unité ne se résout pas.
     */
    public List<Span> highlights(String filePath, String source) {
        List<Span> out = new ArrayList<>();
        CompilationUnitDeclaration cud =
                engine.resolveUnit(filePath, source, false);
        if (cud == null || cud.scope == null) return out;

        // Candidats bruts — la traversée descend, donc les nœuds ENGLOBANTS
        // arrivent AVANT leurs composants : on ne filtre les recouvrements
        // qu'en une passe finale après tri (long d'abord).
        List<Span> raw = new ArrayList<>();
        try {
            EcjAst.traverseResilient(cud, visitor(raw, source));
        } catch (Throwable ignored) {
            // Traversée partielle — les tokens collectés restent valides.
        }

        raw.sort((a, b) -> a.start() != b.start()
                ? Integer.compare(a.start(), b.start())
                : Integer.compare(b.length(), a.length()));
        int coveredUntil = -1;
        for (Span s : raw) {
            if (s.start() >= coveredUntil && s.length() > 0) {
                out.add(s);
                coveredUntil = s.start() + s.length();
            }
        }
        return out;
    }

    // ─── Traversée ─────────────────────────────────────────────────────

    private org.eclipse.jdt.internal.compiler.ASTVisitor visitor(
            List<Span> out, String source) {
        return new org.eclipse.jdt.internal.compiler.ASTVisitor() {

            void emit(int kind, ASTNode node, int start, int end) {
                if (node == null || start < 0 || end <= start) return;
                if (end > source.length()) end = source.length();
                out.add(new Span(kind, start, end - start));
            }

            /** Plage exacte du NOM au sein d'une déclaration (word-scan). */
            int nameRange(ASTNode node, String name) {
                if (name == null || name.isEmpty()) return -1;
                try {
                    return jo.lspjava.ecj.EcjRenameEngine.findWord(source, name,
                            Math.max(0, node.sourceStart), node.sourceEnd + 1,
                            false);
                } catch (Throwable t) {
                    return -1;
                }
            }

            // ── Déclarations de types ──

            @Override
            public boolean visit(TypeDeclaration td, CompilationUnitScope s) {
                onTypeDecl(td);
                return true;
            }

            @Override
            public boolean visit(TypeDeclaration td, ClassScope s) {
                onTypeDecl(td);
                return true;
            }

            @Override
            public boolean visit(TypeDeclaration td, BlockScope s) {
                onTypeDecl(td);
                return true;
            }

            void onTypeDecl(TypeDeclaration td) {
                if (td.binding == null || !td.binding.isValidBinding()) return;
                if (td.name == null || td.name.length == 0) return;
                int p = nameRange(td, String.valueOf(td.name));
                emit(kindOf(td.binding), td, p, p + td.name.length);
            }

            // ── Références de types (usages) ──

            @Override
            public boolean visit(SingleTypeReference ref, BlockScope s) {
                onTypeRef(ref);
                return true;
            }

            @Override
            public boolean visit(SingleTypeReference ref, ClassScope s) {
                onTypeRef(ref);
                return true;
            }

            @Override
            public boolean visit(QualifiedTypeReference ref, BlockScope s) {
                onTypeRef(ref);
                return true;
            }

            @Override
            public boolean visit(QualifiedTypeReference ref, ClassScope s) {
                onTypeRef(ref);
                return true;
            }

            void onTypeRef(org.eclipse.jdt.internal.compiler.ast.TypeReference ref) {
                TypeBinding t = ref.resolvedType;
                if (!(t instanceof ReferenceBinding rb)) return;
                try {
                    if (!rb.isValidBinding()) return;
                    // LE DERNIER segment porte la plage complète du nom.
                    ReferenceBinding original = rb;
                    try { original = (ReferenceBinding) rb.original(); }
                    catch (Throwable ignored) {
                        // Binding binaire — l'original peut être indisponible.
                    }
                    emit(kindOf(original), ref, ref.sourceStart, ref.sourceEnd + 1);
                } catch (Throwable ignored) {
                    // Nœud pathologique — pas de token pour cette référence.
                }
            }

            // ── Méthodes (déclarations + appels) ──

            @Override
            public boolean visit(MethodDeclaration md, ClassScope s) {
                onMethodDecl(md, md.selector);
                return true;
            }

            @Override
            public boolean visit(ConstructorDeclaration cd, ClassScope s) {
                onMethodDecl(cd, cd.selector);
                return true;
            }

            void onMethodDecl(AbstractMethodDeclaration md, char[] selector) {
                if (md.binding == null || !md.binding.isValidBinding()) return;
                if (selector == null || selector.length == 0) return;
                int p = nameRange(md, String.valueOf(selector));
                emit(METHOD, md, p, p + selector.length);
            }

            @Override
            public boolean visit(MessageSend ms, BlockScope s) {
                MethodBinding mb = ms.binding;
                if (mb == null) return true;
                try {
                    if (!mb.isValidBinding()) return true;
                    // Le sélecteur est suivi immédiatement de '(' : word-scan
                    // sur la plage de l'appel en exigeant la parenthèse.
                    String sel = String.valueOf(mb.selector);
                    int p = callSelectorRange(ms, sel);
                    emit(METHOD, ms, p, p + sel.length());
                } catch (Throwable ignored) {
                    // Appel non résoluble — pas de token.
                }
                return true;
            }

            /** Recherche le sélecteur DANS l'appel : premier mot suivi de '(' . */
            int callSelectorRange(MessageSend ms, String sel) {
                int to = Math.min(ms.sourceEnd + 1, source.length());
                int from = Math.max(0, ms.sourceStart);
                while (from < to) {
                    int idx = source.indexOf(sel, from);
                    if (idx < 0 || idx >= to) break;
                    int after = idx + sel.length();
                    boolean leftOk = idx == 0
                            || !Character.isJavaIdentifierPart(source.charAt(idx - 1));
                    // Parenthèse (éventuellement espacée) juste après.
                    int k = after;
                    while (k < to && Character.isWhitespace(source.charAt(k))) k++;
                    boolean rightOk = k < to && source.charAt(k) == '(';
                    if (leftOk && rightOk) return idx;
                    from = after;
                }
                return -1;
            }

            // ── Champs / constantes d'enum ──

            @Override
            public boolean visit(FieldDeclaration fd,
                    org.eclipse.jdt.internal.compiler.lookup.MethodScope s) {
                // NB : FieldDeclaration.binding est DÉJÀ typé FieldBinding
                // dans ecj (pas besoin d'un instanceof).
                if (fd.binding != null && fd.binding.isValidBinding()) {
                    String name = fd.name != null ? String.valueOf(fd.name) : null;
                    int p = nameRange(fd, name);
                    // Champs ET constantes d'enum (dérivées de FieldBinding)
                    // partagent la teinte PROPERTY.
                    emit(PROPERTY, fd, p, p + (fd.name != null ? fd.name.length : 0));
                }
                return true;
            }

            // ── Accès de champ par FieldReference (receiver.champ) ──

            @Override
            public boolean visit(FieldReference fr, BlockScope s) {
                onFieldRef(fr);
                return true;
            }

            @Override
            public boolean visit(FieldReference fr, ClassScope s) {
                onFieldRef(fr);
                return true;
            }

            void onFieldRef(FieldReference fr) {
                try {
                    // FieldReference.binding est déjà typé FieldBinding.
                    if (fr.binding == null || !fr.binding.isValidBinding()) return;
                    String fname = String.valueOf(fr.token);
                    int from = Math.max(0, fr.sourceStart);
                    int to = Math.min(fr.sourceEnd + 1, source.length());
                    // Dernière occurrence du token précédée d'un point : le
                    // sélecteur SANS le récepteur (fr couvre receiver.champ).
                    int found = -1;
                    int idx = from;
                    while ((idx = source.indexOf(fname, idx)) >= 0 && idx < to) {
                        int end = idx + fname.length();
                        boolean leftDot = false;
                        int k = idx - 1;
                        while (k >= from && Character.isWhitespace(source.charAt(k))) k--;
                        if (k >= from) leftDot = source.charAt(k) == '.';
                        boolean leftIdOk = idx == 0
                                || !Character.isJavaIdentifierPart(source.charAt(idx - 1));
                        if (end <= to && leftDot && leftIdOk) found = idx;
                        idx = end;
                    }
                    if (found >= 0) emit(PROPERTY, fr, found, found + fname.length());
                } catch (Throwable ignored) {
                    // Accès non résoluble — pas de token.
                }
            }

            // ── Locales / paramètres (déclarations) ──

            @Override
            public boolean visit(LocalDeclaration ld, BlockScope s) {
                onLocalDecl(ld);
                return true;
            }

            void onLocalDecl(LocalDeclaration ld) {
                if (ld.binding == null) return;
                String name = ld.name != null ? String.valueOf(ld.name) : null;
                int p = nameRange(ld, name);
                boolean isParam = ld instanceof Argument;
                emit(isParam ? PARAMETER : VARIABLE, ld, p,
                        p + (ld.name != null ? ld.name.length : 0));
            }

            // ── Usages résolus (noms simples et qualifiés) ──

            @Override
            public boolean visit(SingleNameReference ref, BlockScope s) {
                onNameRef(ref);
                return true;
            }

            @Override
            public boolean visit(SingleNameReference ref, ClassScope s) {
                onNameRef(ref);
                return true;
            }

            @Override
            public boolean visit(QualifiedNameReference ref, BlockScope s) {
                onQualifiedName(ref);
                return true;
            }

            @Override
            public boolean visit(QualifiedNameReference ref, ClassScope s) {
                onQualifiedName(ref);
                return true;
            }

            void onNameRef(SingleNameReference ref) {
                if (ref.binding instanceof LocalVariableBinding lv) {
                    boolean param = lv.isParameter();
                    emit(param ? PARAMETER : VARIABLE, ref,
                            ref.sourceStart, ref.sourceEnd + 1);
                } else if (ref.binding instanceof FieldBinding fb && fb.isValidBinding()) {
                    emit(PROPERTY, ref, ref.sourceStart, ref.sourceEnd + 1);
                } else if (ref.binding instanceof ReferenceBinding tb) {
                    emit(kindOf((ReferenceBinding) originalOf(tb)), ref,
                            ref.sourceStart, ref.sourceEnd + 1);
                }
            }

            /** Version originale d'un binding paramétré (best-effort). */
            static TypeBinding originalOf(TypeBinding t) {
                try { return t instanceof ReferenceBinding rb ? rb.original() : t; }
                catch (Throwable e) { return t; }
            }

            /**
             * Un QualifiedNameReference peut porter PLUSIEURS segments
             * résolus ({@code otherFields.length} → champ .length en dernier).
             * Aucun segment supplémentaire : le nom ENTIER référence une
             * locale/paramètre/champ/type — peint selon ce binding unique.
             */
            void onQualifiedName(QualifiedNameReference ref) {
                FieldBinding[] others = ref.otherBindings;
                if (others == null || others.length == 0) {
                    org.eclipse.jdt.internal.compiler.lookup.Binding b = ref.binding;
                    int kind;
                    if (b instanceof LocalVariableBinding lv) {
                        kind = lv.isParameter() ? PARAMETER : VARIABLE;
                    } else if (b instanceof FieldBinding fb && fb.isValidBinding()) {
                        kind = PROPERTY;
                    } else if (b instanceof ReferenceBinding tb) {
                        kind = kindOf((ReferenceBinding) originalOf(tb));
                    } else {
                        return;
                    }
                    emit(kind, ref, ref.sourceStart, ref.sourceEnd + 1);
                    return;
                }
                // Peint le DERNIER segment résolu (le champ final du chemin).
                FieldBinding last = others[others.length - 1];
                if (!last.isValidBinding()) return;
                String fname = String.valueOf(last.name);
                int from = Math.max(0, ref.sourceStart);
                int to = Math.min(ref.sourceEnd + 1, source.length());
                int idx = from;
                int found = -1;
                while ((idx = source.indexOf(fname, idx)) >= 0 && idx < to) {
                    int end = idx + fname.length();
                    boolean leftOk = idx == from || source.charAt(idx - 1) == '.';
                    if (end <= to && leftOk) found = idx;
                    idx = end;
                }
                if (found >= 0) emit(PROPERTY, ref, found, found + fname.length());
            }

            // ── Classification ──

            int kindOf(TypeBinding t) {
                if (!(t instanceof ReferenceBinding rb)) return CLASS;
                try {
                    if (rb.isEnum()) return ENUM;
                    // Une annotation EST une interface en Java (JLS 9.6),
                    // mais l'ordre importe pour le rendu sémantique : on veut
                    // distinguer @interface d'interface pour que l'éditeur
                    // puisse les colorer différemment. isAnnotationType() est
                    // donc testé AVANT isInterface(), comme dans
                    // EcjCompletionEngine.kindOfType.
                    if (rb.isAnnotationType()) return INTERFACE;  // TODO: ajouter un kind ANNOTATION dédié
                    if (rb.isInterface()) return INTERFACE;
                    return CLASS;
                } catch (Throwable e) {
                    return CLASS;
                }
            }
        };
    }
}
