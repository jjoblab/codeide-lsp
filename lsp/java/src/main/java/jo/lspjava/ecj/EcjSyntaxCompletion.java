package jo.lspjava.ecj;

import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.LocalDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;

import jo.lspjava.api.CompletionItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Complétion INSTANTANÉE par symboles du fichier (lot h4 « LSP
 * instantané », v0.101.0) — le régime de repli de
 * {@link EcjCompletionEngine} quand la plateforme manque (avant la
 * première sync, image jrt absente : {@code isLikelyUsable()} faux).
 *
 * <p><strong>Philosophie — le « mode sourd » des IDE de
 * bureau :</strong> la complétion n'attend pas le classpath pour parler.
 * Le PARSE seul suffit à proposer ce que le fichier DÉCLARE — types,
 * méthodes, constructeurs, champs (de tout le fichier) ; paramètres et
 * locales de la MÉTHODE englobant le caret (déclarés avant lui) ; et
 * les mots-clés du langage. Aucune résolution : on ne devine RIEN
 * (aucun type du JDK n'est proposé — le régime plein les apportera
 * avec le jrt puis le classpath).</p>
 *
 * <p><strong>Portée assumée (documentée) :</strong> les locales sont
 * collectées dans toute la méthode englobante, SANS granularité de
 * blocs — une locale d'un bloc {@code if} terminé reste proposée.
 * C'est le comportement du repli : large mais jamais silencieux ;
 * la montée en régime (plateforme prête) remplace ce régime
 * entièrement.</p>
 *
 * <p>Le parse est partagé avec {@link EcjCompilerEngine#diagnoseSyntax}
 * (point d'entrée {@link EcjCompilerEngine#parseSeul}) — même arbre,
 * aucun binding résolu, aucun environnement de noms. Le coût est celui
 * du parseur seul : l'étape bon marché de la chaîne ecj.</p>
 *
 * @author jo@Dev
 */
public final class EcjSyntaxCompletion {

    /** Mots-clés Java proposés en contexte de nom (miroir de
     *  {@link EcjCompletionEngine} — le régime plein propose les SIENS
     *  avec la sémantique de contexte ; le repli propose les noms du
     *  langage, c'est tout ce que le parse garantit). */
    private static final List<String> MOTS_CLES = List.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double",
            "else", "enum", "extends", "final", "finally", "float", "for",
            "if", "implements", "import", "instanceof", "int", "interface",
            "long", "native", "new", "package", "private", "protected",
            "public", "return", "short", "static", "strictfp", "super",
            "switch", "synchronized", "this", "throw", "throws", "transient",
            "try", "void", "volatile", "while", "true", "false", "null",
            "var", "record", "sealed", "permits", "yield");

    /** Pertinence : ce que le fichier déclare est plus proche de
     *  l'utilisateur que les mots du langage. */
    private static final int PERTINENCE_LOCALE = 100;
    private static final int PERTINENCE_PARAMETRE = 95;
    private static final int PERTINENCE_CHAMP = 90;
    private static final int PERTINENCE_METHODE = 85;
    private static final int PERTINENCE_TYPE = 80;
    private static final int PERTINENCE_MOT_CLE = 50;

    private final EcjCompilerEngine engine;

    public EcjSyntaxCompletion(EcjCompilerEngine engine) {
        this.engine = engine;
    }

    /**
     * Complétion instantanée au offset — symboles déclarés du fichier +
     * mots-clés, filtrés par le préfixe au caret.
     *
     * <p>CONTRAT (miroir de {@code EcjCompletionEngine.complete}) : ne
     * rend JAMAIS null — une liste, possiblement vide, honnête.</p>
     */
    public List<CompletionItem> complete(String filePath, String source, int offset) {
        if (EcjCompilerEngine.DISABLED) return new ArrayList<>();
        if (source == null || source.isEmpty()) return new ArrayList<>();
        int caret = Math.max(0, Math.min(offset, source.length()));
        String prefixe = prefixeAuCaret(source, caret);

        CompilationUnitDeclaration cud;
        try {
            cud = engine.parseSeul(filePath, source);
        } catch (Throwable t) {
            // Le parse a jeté (source pathologique) — repli mots-clés
            // seuls : la liste reste honnête, jamais nulle.
            return motsCles(prefixe);
        }
        if (cud == null) return motsCles(prefixe);

        List<CompletionItem> out = new ArrayList<>();
        // Symboles de tout le fichier : types, méthodes, champs.
        if (cud.types != null) {
            for (TypeDeclaration type : cud.types) {
                if (type == null) continue;
                collecterType(type, prefixe, out);
            }
        }
        // Paramètres et locales de la MÉTHODE englobant le caret.
        AbstractMethodDeclaration hote = methodeEnglobante(cud, caret);
        if (hote != null) {
            if (hote.arguments != null) {
                for (Argument argument : hote.arguments) {
                    if (argument != null && argument.name != null) {
                        ajouter(new String(argument.name), CompletionItem.Kind.PARAMETER,
                                PERTINENCE_PARAMETRE, prefixe, out);
                    }
                }
            }
            // Traverse la méthode ENTIÈRE (signature + corps, scopes
            // nulls — l'AST n'est pas résolu) : le visiteur ne retient
            // que les locales déclarées avant le caret. Un échec de
            // traversée (node pathologique) n'ampute que les LOCALES —
            // types/méthodes/champs déjà collectés restent proposés.
            try {
                CollecteurLocales collecteur = new CollecteurLocales(caret, prefixe, out);
                hote.traverse(collecteur, (org.eclipse.jdt.internal.compiler.lookup.ClassScope) null);
            } catch (Throwable ignore) {
                // Best-effort : le repli reste honnête sans les locales.
            }
        }
        // Mots-clés du langage — en plus des symboles.
        out.addAll(motsCles(prefixe));
        return out;
    }

    // ─── Symboles déclarés ─────────────────────────────────────────

    /** Types (récursif, member types compris) + leurs membres. */
    private void collecterType(TypeDeclaration type, String prefixe,
                               List<CompletionItem> out) {
        ajouter(new String(type.name), kindDe(type),
                PERTINENCE_TYPE, prefixe, out);
        if (type.fields != null) {
            for (FieldDeclaration champ : type.fields) {
                if (champ != null && champ.name != null) {
                    ajouter(new String(champ.name), CompletionItem.Kind.FIELD,
                            PERTINENCE_CHAMP, prefixe, out);
                }
            }
        }
        if (type.methods != null) {
            for (AbstractMethodDeclaration methode : type.methods) {
                if (methode == null || methode.selector == null) continue;
                ajouter(new String(methode.selector),
                        methode.isConstructor()
                                ? CompletionItem.Kind.CONSTRUCTOR
                                : CompletionItem.Kind.METHOD,
                        PERTINENCE_METHODE, prefixe, out);
            }
        }
        if (type.memberTypes != null) {
            for (TypeDeclaration membre : type.memberTypes) {
                if (membre != null) collecterType(membre, prefixe, out);
            }
        }
    }

    /** Kind de complétion d'une déclaration de type — par modifiers. */
    private static CompletionItem.Kind kindDe(TypeDeclaration type) {
        if ((type.modifiers & ClassFileConstants.AccInterface) != 0) {
            return CompletionItem.Kind.INTERFACE;
        }
        if ((type.modifiers & ClassFileConstants.AccEnum) != 0) {
            return CompletionItem.Kind.ENUM;
        }
        if ((type.modifiers & ClassFileConstants.AccAnnotation) != 0) {
            return CompletionItem.Kind.ANNOTATION;
        }
        return CompletionItem.Kind.CLASS;
    }

    /** La méthode dont le corps (ou la signature) contient le caret. */
    private static AbstractMethodDeclaration methodeEnglobante(
            CompilationUnitDeclaration cud, int caret) {
        if (cud.types == null) return null;
        for (TypeDeclaration type : cud.types) {
            if (type == null) continue;
            AbstractMethodDeclaration trouvee =
                    methodeEnglobanteDansType(type, caret);
            if (trouvee != null) return trouvee;
        }
        return null;
    }

    private static AbstractMethodDeclaration methodeEnglobanteDansType(
            TypeDeclaration type, int caret) {
        if (type.methods != null) {
            for (AbstractMethodDeclaration methode : type.methods) {
                if (methode == null) continue;
                // declarationSource* couvre annotations + signature ;
                // bodyEnd ferme le corps. Le caret DANS l'une des deux
                // plages désigne la méthode englobante.
                boolean dansDeclaration = caret >= methode.declarationSourceStart
                        && caret <= methode.declarationSourceEnd;
                boolean dansCorps = caret >= methode.bodyStart
                        && caret <= methode.bodyEnd;
                if (dansDeclaration || dansCorps) return methode;
            }
        }
        if (type.memberTypes != null) {
            for (TypeDeclaration membre : type.memberTypes) {
                if (membre == null) continue;
                AbstractMethodDeclaration trouvee =
                        methodeEnglobanteDansType(membre, caret);
                if (trouvee != null) return trouvee;
            }
        }
        return null;
    }

    /** Visiteur des locales du corps englobant (avant le caret). */
    private static final class CollecteurLocales extends ASTVisitor {
        private final int caret;
        private final String prefixe;
        private final List<CompletionItem> out;

        CollecteurLocales(int caret, String prefixe, List<CompletionItem> out) {
            this.caret = caret;
            this.prefixe = prefixe;
            this.out = out;
        }

        @Override
        public boolean visit(LocalDeclaration local, BlockScope scope) {
            // Locale déclarée AVANT le caret : visible à la position de
            // complétion (granularité de blocs ignorée — repli documenté).
            if (local != null && local.name != null
                    && local.sourceStart <= caret) {
                ajouter(new String(local.name), CompletionItem.Kind.VARIABLE,
                        PERTINENCE_LOCALE, prefixe, out);
            }
            return true;  // descendre : les blocs imbriqués aussi.
        }
    }

    // ─── Préfixe et filtres ────────────────────────────────────────

    /** Identifiant partiel immédiatement avant le caret ("" si aucun). */
    private static String prefixeAuCaret(String source, int caret) {
        int debut = caret;
        while (debut > 0
                && Character.isJavaIdentifierPart(source.charAt(debut - 1))) {
            debut--;
        }
        return source.substring(debut, caret);
    }

    /** Mots-clés filtrés par préfixe (liste vide si aucun ne matche). */
    private static List<CompletionItem> motsCles(String prefixe) {
        List<CompletionItem> out = new ArrayList<>();
        for (String mot : MOTS_CLES) {
            ajouter(mot, CompletionItem.Kind.KEYWORD,
                    PERTINENCE_MOT_CLE, prefixe, out);
        }
        return out;
    }

    /** Ajoute le candidat s'il matche le préfixe (jamais null-safe crash). */
    private static void ajouter(String label, CompletionItem.Kind kind,
                                int pertinence, String prefixe,
                                List<CompletionItem> out) {
        if (label == null || label.isEmpty()) return;
        if (!prefixe.isEmpty() && !label.startsWith(prefixe)) return;
        out.add(new CompletionItem(label, kind, null, null, null, pertinence));
    }
}
