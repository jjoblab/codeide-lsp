package jo.lspjava.jdt;

import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ImportReference;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Organisateur d'imports Java — calcule les imports NÉCESSAIRES d'une unité
 * à partir des bindings ecj et produit des éditions (importation /
 * suppression / tri) pour transformer le bloc d'imports existant.
 *
 * <p>Approche pragmatique et sûre, adaptée de la philosophie
 * {@code JavaImportOrganizer} du projet de référence :</p>
 * <ul>
 *   <li><b>Collecte</b> — une traversée de l'AST résolu identifie, pour
 *       chaque référence de type ({@code SingleTypeReference} /
 *       {@code QualifiedTypeReference}) et chaque usage qualifié
 *       ({@code QualifiedNameReference}) le FQN du type référencé ;
 *       les types du même paquet / {@code java.lang} / déjà importés en
 *       on-demand ({@code .*}) n'ont pas besoin d'import.</li>
 *   <li><b>Tri</b> — les imports résultants sont triés
 *       alphabétiquement (statiques en dernier).</li>
 *   <li><b>Éditions</b> — remplacer le bloc
 *       {@code [premier import … fin du dernier import]} par le nouveau
 *       bloc formaté ; si aucun import existant, insérer après la
 *       déclaration de paquet.</li>
 * </ul>
 *
 * <p>Le calcul est <em>best-effort</em> : sur du code cassé, les bindings
 * non résolus sont simplement ignorés — les imports correspondants ne
 * sont pas proposés (jamais de faux import).</p>
 *
 * @author jo@Dev
 */
public final class JdtImportOrganizer {

    /** Résultat du calcul : plage à remplacer + nouveau bloc d'imports. */
    public static final class Result {
        /** Début du bloc d'imports existant (ou point d'insertion). */
        public final int replaceStart;
        /** Fin du bloc d'imports existant (ou point d'insertion). */
        public final int replaceEnd;
        /** Le nouveau bloc d'imports (avec saut de ligne final, ou vide). */
        public final String importsBlock;
        /** Les FQN retenus (debug / tests). */
        public final List<String> imports;

        Result(int replaceStart, int replaceEnd, String importsBlock,
               List<String> imports) {
            this.replaceStart = replaceStart;
            this.replaceEnd = replaceEnd;
            this.importsBlock = importsBlock;
            this.imports = imports;
        }
    }

    private final JdtCompilerEngine engine;

    public JdtImportOrganizer(JdtCompilerEngine engine) {
        this.engine = engine;
    }

    /**
     * Calcule les éditions d'organisation des imports.
     *
     * @param filePath chemin du fichier (FQCN de l'unité)
     * @param source   le texte complet
     * @return le résultat, ou null si l'AST n'a pas pu être résolu
     */
    public Result organize(String filePath, String source) {
        if (JdtCompilerEngine.DISABLED) return null;
        CompilationUnitDeclaration cud = engine.resolveUnit(filePath, source, false);
        if (cud == null || cud.scope == null) return null;

        String currentPackage = cud.currentPackage != null
                ? joinTokens(cud.currentPackage.tokens) : "";

        // On-demand existants : java.util.* → les types de java.util
        // n'ont pas besoin d'import explicite.
        Set<String> onDemandPackages = new LinkedHashSet<>();
        // FQN déjà importés explicitement.
        Set<String> existingImports = new LinkedHashSet<>();
        // Préserve les imports statiques existants (import static
        // foo.Bar.BAZ) : ils sont collectés séparément des imports normaux
        // et réinjectés dans le nouveau bloc.
        Set<String> existingStaticImports = new LinkedHashSet<>();
        Set<String> staticOnDemand = new LinkedHashSet<>();
        int importsStart = -1;
        int importsEnd = -1;
        if (cud.imports != null) {
            for (ImportReference imp : cud.imports) {
                if (importsStart < 0
                        || imp.declarationSourceStart < importsStart) {
                    importsStart = imp.declarationSourceStart;
                }
                if (imp.declarationSourceEnd > importsEnd) {
                    importsEnd = imp.declarationSourceEnd + 1;
                }
                String name = joinTokens(imp.tokens);
                boolean onDemand = (imp.bits
                        & org.eclipse.jdt.internal.compiler.ast.ASTNode.OnDemand) != 0;
                // imp.isStatic() est la méthode publique d'ImportReference
                // (pas de flag bits accessible).
                boolean isStatic = false;
                try {
                    isStatic = imp.isStatic();
                } catch (Throwable ignored) {
                    // Best-effort.
                }
                if (isStatic) {
                    // Statique — collecté à part pour être préservé.
                    if (onDemand) {
                        staticOnDemand.add(name);
                    } else {
                        existingStaticImports.add(name);
                    }
                } else {
                    if (onDemand) {
                        onDemandPackages.add(name);
                    } else {
                        existingImports.add(name);
                    }
                }
            }
        }

        // Collecte des FQN nécessaires.
        Set<String> needed = new TreeSet<>();
        ImportCollector collector = new ImportCollector(
                currentPackage, onDemandPackages, needed);
        try {
            JdtAst.traverseResilient(cud, collector);
        } catch (Throwable ignored) {
            // Traversée partielle — on garde ce qui est collecté.
        }

        // Tri : normaux alphabétiques ; statiques séparés à la fin.
        List<String> sorted = new ArrayList<>(needed);
        sorted.sort(Comparator.naturalOrder());

        // Les imports on-demand existants sont PRÉSERVÉS.
        sorted.addAll(onDemandPackages.stream()
                .map(pkg -> pkg + ".*")
                .toList());
        sorted.sort(Comparator.naturalOrder());

        // Ajoute les imports statiques existants à la fin.
        List<String> staticSorted = new ArrayList<>(existingStaticImports);
        staticSorted.sort(Comparator.naturalOrder());
        staticSorted.addAll(staticOnDemand.stream()
                .map(pkg -> pkg + ".*")
                .toList());
        staticSorted.sort(Comparator.naturalOrder());

        // Construit le nouveau bloc.
        StringBuilder block = new StringBuilder();
        for (String fqn : sorted) {
            if (block.length() > 0) block.append('\n');
            block.append("import ").append(fqn).append(';');
        }
        // Section statiques séparée par une ligne vide.
        if (!staticSorted.isEmpty()) {
            if (block.length() > 0) block.append('\n');
            for (String fqn : staticSorted) {
                if (block.length() > 0 && block.charAt(block.length() - 1) != '\n') {
                    block.append('\n');
                }
                block.append("import static ").append(fqn).append(';');
            }
        }
        if (block.length() > 0) block.append('\n');

        // Point d'insertion quand il n'y a pas d'imports existants :
        // après la déclaration de paquet (fin de ligne), sinon début.
        if (importsStart < 0) {
            if (cud.currentPackage != null) {
                importsStart = cud.currentPackage.declarationSourceEnd + 1;
                importsEnd = importsStart;
            } else {
                importsStart = 0;
                importsEnd = 0;
            }
        }

        return new Result(importsStart, importsEnd, block.toString(), sorted);
    }

    // ─── Collecteur ────────────────────────────────────────────────────

    /** Collecte les FQN de types nécessitant un import.
     *
     * <p>NOTE dispatch : {@code ASTVisitor} a des surcharges SPÉCIFIQUES
     * pour chaque forme de TypeReference ({@code ParameterizedQualifiedTypeReference},
     * {@code ParameterizedSingleTypeReference}, {@code ArrayTypeReference}, …)
     * — un {@code visit(QualifiedTypeReference, …)} n'est JAMAIS appelé pour
     * un type paramétré (vérifié par javap + sonde). Chaque forme doit être
     * surchargée individuellement.</p> */
    private static final class ImportCollector
            extends org.eclipse.jdt.internal.compiler.ASTVisitor {

        private final String currentPackage;
        private final Set<String> onDemandPackages;
        private final Set<String> needed;

        ImportCollector(String currentPackage, Set<String> onDemandPackages,
                        Set<String> needed) {
            this.currentPackage = currentPackage;
            this.onDemandPackages = onDemandPackages;
            this.needed = needed;
        }

        void consider(TypeBinding type) {
            if (type == null || !type.isValidBinding()) return;
            try {
                TypeBinding erasure = type.erasure() != null ? type.erasure() : type;
                // FQN sans arguments de type : paquet qualifié + nom source
                // (readableName d'un binding paramétré donnerait
                // « java.util.List<E>», qualifiedSourceName juste « List »).
                String pkg = String.valueOf(erasure.qualifiedPackageName());
                String simple = String.valueOf(erasure.sourceName());
                String fqn = pkg.isEmpty() ? simple : pkg + "." + simple;
                // Même paquet / default / java.lang : pas d'import.
                if (pkg.isEmpty() || pkg.equals(currentPackage)
                        || "java.lang".equals(pkg)) {
                    return;
                }
                // On-demand existant qui couvre ce paquet : pas d'import.
                if (onDemandPackages.contains(pkg)) return;
                needed.add(fqn);
            } catch (Throwable ignored) {
                // Binding partiel — ignoré.
            }
        }

        @Override
        public boolean visit(
                org.eclipse.jdt.internal.compiler.ast.SingleTypeReference ref,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            consider(ref.resolvedType);
            return true;
        }

        @Override
        public boolean visit(
                org.eclipse.jdt.internal.compiler.ast.SingleTypeReference ref,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            consider(ref.resolvedType);
            return true;
        }

        @Override
        public boolean visit(
                org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference ref,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            consider(ref.resolvedType);
            return true;
        }

        @Override
        public boolean visit(
                org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference ref,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            consider(ref.resolvedType);
            return true;
        }

        @Override
        public boolean visit(
                org.eclipse.jdt.internal.compiler.ast.ParameterizedQualifiedTypeReference ref,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            consider(ref.resolvedType);
            return true;
        }

        @Override
        public boolean visit(
                org.eclipse.jdt.internal.compiler.ast.ParameterizedQualifiedTypeReference ref,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            consider(ref.resolvedType);
            return true;
        }

        @Override
        public boolean visit(
                org.eclipse.jdt.internal.compiler.ast.ParameterizedSingleTypeReference ref,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            consider(ref.resolvedType);
            return true;
        }

        @Override
        public boolean visit(
                org.eclipse.jdt.internal.compiler.ast.ParameterizedSingleTypeReference ref,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            consider(ref.resolvedType);
            return true;
        }

        @Override
        public boolean visit(
                org.eclipse.jdt.internal.compiler.ast.ArrayTypeReference ref,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            consider(ref.resolvedType);
            return true;
        }

        @Override
        public boolean visit(
                org.eclipse.jdt.internal.compiler.ast.ArrayTypeReference ref,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            consider(ref.resolvedType);
            return true;
        }

        @Override
        public boolean visit(
                org.eclipse.jdt.internal.compiler.ast.ArrayQualifiedTypeReference ref,
                org.eclipse.jdt.internal.compiler.lookup.BlockScope s) {
            consider(ref.resolvedType);
            return true;
        }

        @Override
        public boolean visit(
                org.eclipse.jdt.internal.compiler.ast.ArrayQualifiedTypeReference ref,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            consider(ref.resolvedType);
            return true;
        }

        @Override
        public boolean visit(TypeDeclaration td,
                org.eclipse.jdt.internal.compiler.lookup.ClassScope s) {
            considerSuper(td);
            return true;
        }

        @Override
        public boolean visit(TypeDeclaration td,
                org.eclipse.jdt.internal.compiler.lookup.CompilationUnitScope s) {
            considerSuper(td);
            return true;
        }

        private void considerSuper(TypeDeclaration td) {
            if (td.binding == null) return;
            consider(td.binding.superclass());
            for (var itf : td.binding.superInterfaces()) {
                consider(itf);
            }
        }
    }

    private static String joinTokens(char[][] tokens) {
        if (tokens == null || tokens.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (char[] t : tokens) {
            if (sb.length() > 0) sb.append('.');
            sb.append(t);
        }
        return sb.toString();
    }
}
