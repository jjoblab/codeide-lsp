package jo.lspjava.jdt;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Initializer;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jo.lspjava.api.Diagnostic;
import jo.lspjava.api.TextRange;

/**
 * Diagnostics incrémentaux par corps de méthode (P6, v1.5.0) — portage du
 * commit projet de référence <em>« skip the file parse entirely when a keystroke
 * stays inside one body »</em> (4b9b880), adapté à l'architecture ecj de
 * lsp-java.
 *
 * <p><strong>Motivation :</strong> chaque {@code didChange} (debounce
 * 50 ms) déclenche un diagnose COMPLET — parse + résolution + analyse de
 * flux + codegen de tout le fichier, tous corps confondus. À vitesse de
 * frappe normale (~200 ms entre touches), c'est un parse complet PAR
 * TOUCHE. Or la quasi-totalité des frappes restent confinées DANS un
 * corps de méthode et ne changent rien au-dehors : en-têtes, signatures,
 * corps des autres méthodes — tout est identique. Seul le corps édité
 * peut produire de nouveaux problèmes.</p>
 *
 * <p><strong>Principe :</strong> quand l'édition depuis le dernier
 * diagnose est provablement confinée dans UN corps (celui-ci toujours
 * clos au même endroit — cf. preuve d'obligation ci-dessous), le moteur
 * re-diagnostique une version « focalisée » du fichier : le corps édité
 * intact, tous les autres corps remplacés par un corps synthétique
 * {@code throw null;} de MÊME LONGUEUR (positions préservées — aucun
 * mapping d'offset nécessaire). Les problèmes des corps remplacés sont
 * portés depuis le diagnostic précédent : leur texte n'a pas changé,
 * leurs problèmes non plus.</p>
 *
 * <p><strong>Preuve d'obligation</strong> (reprise du projet de référence) :
 * réutiliser la structure environnante n'est sain que si le corps édité
 * se termine ENCORE là où il se terminait. Taper {@code }} le ferme
 * plus tôt et tout ce qui suit appartient à autre chose. Le nouveau
 * texte du corps est donc re-lexé (conscient des chaînes, text blocks,
 * littéraux caractères et commentaires — une accolade dans une chaîne
 * n'est pas une accolade) et ses accolades vérifiées : la profondeur
 * doit retomber à zéro EXACTEMENT à la position attendue (l'ancienne
 * accolade fermante, décalée du delta d'édition) et nulle part avant.
 * Sinon → repli sur le diagnose complet (structure modifiée : recovery
 * complet d'ecj nécessaire).</p>
 *
 * <p><strong>Corps synthétique {@code throw null;} :</strong> un corps
 * vide déclencherait des erreurs « must return » FALLACIEUSES sur les
 * méthodes non void ; {@code throw null;} est statiquement valide pour
 * tout type de retour (méthode, constructeur, initialiseur) et ne
 * produit ni erreur ni warning. Le padding conserve les sauts de ligne
 * pour garder la structure de lignes du fichier focalisé proche de
 * l'original (les problèmes frais ne pointent jamais dans un corps
 * remplacé — ils y sont filtrés).</p>
 *
 * <p><strong>Fausse famille « unused » :</strong> un import / méthode /
 * champ privé utilisé UNIQUEMENT par un corps remplacé apparaîtrait
 * « never used » dans le parse focalisé alors que le parse complet ne
 * le signale pas. Ces problèmes frais (famille
 * {@code IProblem.Unused*}) sont supprimés quand le diagnostic
 * précédent ne les contenait pas et que le nom concerné figure
 * (délimité identifiant) dans le texte d'un corps remplacé — la seule
 * explication possible est un usage masqué par le remplacement.</p>
 *
 * <p><strong>Chaînage :</strong> le mémo conserve (source, problèmes
 * publiés, plages des corps membre) ; chaque tour re-mappe les plages
 * par la règle préfixe/suffixe et rafraîchit la plage du corps édité.
 * Le mémo survit aux {@code didChange} (clé sur le contenu) — c'est le
 * point — et est invalidé par {@code dispose()} (changement de
 * classpath) et à la fermeture du fichier.</p>
 *
 * <p><strong>Repli conservateur :</strong> au moindre doute (région
 * d'édition à cheval sur plusieurs corps, touchant une signature ou un
 * champ, accolades non équilibrées, parse focalisé en échec), le
 * diagnose complet d'origine est exécuté — le fast-path ne peut que
 * gagner du temps, jamais perdre un diagnostic.</p>
 *
 * @author jo@Dev
 */
final class JdtFastDiagnose {

    private JdtFastDiagnose() { }

    /**
     * Mémo du dernier diagnose (complet OU rapide) — clé sur le contenu.
     * Champ unique : l'éditeur diagnostique un fichier à la fois (même
     * rationale que {@link BindingParseCache}).
     */
    static final class Memo {
        final String filePath;
        final String source;
        /** Problèmes publiés (triés par offset, liste immuable). */
        final List<Diagnostic> problems;
        /**
         * Plages des corps de niveau membre : {@code int[n][2]} —
         * {@code [i][0]} = index du « { », {@code [i][1]} = index du
         * « } ». Triées par ouverture, disjointes, validées contre le
         * texte source.
         */
        final int[][] spans;

        Memo(String filePath, String source,
             List<Diagnostic> problems, int[][] spans) {
            this.filePath = filePath;
            this.source = source;
            this.problems = problems;
            this.spans = spans;
        }

        boolean matches(String filePath, String source) {
            return this.filePath.equals(filePath)
                    && this.source.equals(source);
        }
    }

    /**
     * Une édition confinée, prouvée : région d'édition + corps hôte
     * (ancien et nouveau) + plages à remplacer dans la nouvelle source.
     */
    static final class Confined {
        /** Début de la région remplacée (identique dans les 2 sources). */
        final int start;
        /** Fin (exclue) de la région dans l'ANCIENNE source. */
        final int oldEnd;
        /** Fin (exclue) de la région dans la NOUVELLE source. */
        final int newEnd;
        /** Delta d'édition : {@code newEnd - oldEnd}. */
        final int delta;
        /** Corps hôte dans l'ANCIENNE source : index du « { ». */
        final int oldOpen;
        /** Corps hôte dans l'ANCIENNE source : index du « } ». */
        final int oldClose;
        /** Corps hôte dans la NOUVELLE source : index du « } ». */
        final int newClose;
        /** Plages des AUTRES corps, mappées dans la nouvelle source. */
        final int[][] otherSpansNew;

        Confined(int start, int oldEnd, int newEnd,
                 int oldOpen, int oldClose, int newClose,
                 int[][] otherSpansNew) {
            this.start = start;
            this.oldEnd = oldEnd;
            this.newEnd = newEnd;
            this.delta = newEnd - oldEnd;
            this.oldOpen = oldOpen;
            this.oldClose = oldClose;
            this.newClose = newClose;
            this.otherSpansNew = otherSpansNew;
        }
    }

    // ─── Région d'édition (appariement par les deux bouts) ────────────

    /**
     * Calcule la région d'édition minimale contiguë entre deux sources
     * (préfixe commun + suffixe commun) — technique du commit projet de référence :
     * « The changed region is recovered by matching the two buffers from
     * both ends ». Couvre insertion, suppression, remplacement ET les
     * éditions multiples d'un même {@code didChange} (la région couvre
     * alors de la première à la dernière modification).
     *
     * @return {@code {start, oldEnd, newEnd}} — région [start, oldEnd)
     *         dans l'ancienne source, [start, newEnd) dans la nouvelle ;
     *         {@code null} si les sources sont identiques (région vide)
     */
    static int[] editRegion(String oldSource, String newSource) {
        int lenA = oldSource.length();
        int lenB = newSource.length();
        int maxCommon = Math.min(lenA, lenB);
        int p = 0;
        while (p < maxCommon
                && oldSource.charAt(p) == newSource.charAt(p)) p++;
        int q = 0;
        while (q < maxCommon - p
                && oldSource.charAt(lenA - 1 - q) == newSource.charAt(lenB - 1 - q)) q++;
        if (p == lenA - q && p == lenB - q) return null;  // identiques
        return new int[]{p, lenA - q, lenB - q};
    }

    // ─── Plages des corps de niveau membre ────────────────────────────

    /**
     * Extrait les plages des corps de niveau membre (méthodes,
     * constructeurs, initialiseurs statiques/d'instance) de l'arbre
     * résolu, types membres inclus, en VALIDANT chaque plage contre le
     * texte source : le caractère avant {@code bodyStart} doit être
     * « { » et celui après {@code bodyEnd} doit être « } » (sémantique
     * ecj : {@code bodyStart} = premier caractère après l'accolade
     * ouvrante, {@code bodyEnd} = dernier caractère avant la fermante).
     *
     * <p>La validation rejette les entrées synthétiques
     * ({@code <clinit>}, constructeur par défaut — positions
     * non-initialisées) et les méthodes sans corps (abstraites,
     * interfaces) dont les champs ne pointent pas sur une paire
     * d'accolades réelle.</p>
     */
    static int[][] memberBodySpans(CompilationUnitDeclaration cud,
                                   String source) {
        List<int[]> out = new ArrayList<>();
        collectBodySpans(cud.types, source, out);
        int[][] spans = out.toArray(new int[0][]);
        Arrays.sort(spans, (a, b) -> Integer.compare(a[0], b[0]));
        return spans;
    }

    private static void collectBodySpans(TypeDeclaration[] types,
                                         String source,
                                         List<int[]> out) {
        if (types == null) return;
        for (TypeDeclaration t : types) {
            if (t == null) continue;
            if (t.fields != null) {
                for (FieldDeclaration f : t.fields) {
                    if (f instanceof Initializer init
                            && init.block != null) {
                        addIfValid(out, source,
                                init.block.sourceStart,
                                init.block.sourceEnd);
                    }
                }
            }
            if (t.methods != null) {
                for (AbstractMethodDeclaration m : t.methods) {
                    if (m == null) continue;
                    // open = index du « { » = bodyStart - 1 ;
                    // close = index du « } » = bodyEnd + 1.
                    addIfValid(out, source, m.bodyStart - 1, m.bodyEnd + 1);
                }
            }
            collectBodySpans(t.memberTypes, source, out);
        }
    }

    /**
     * Ajoute la plage {@code [open, close]} si elle désigne bien une
     * paire d'accolades réelle du texte (garde anti-synthétique :
     * {@code <clinit>}, constructeur par défaut, méthodes abstraites).
     * Un corps vide ({@code {}}) est valide : {@code close == open + 1}.
     */
    private static void addIfValid(List<int[]> out, String source,
                                   int open, int close) {
        if (open < 0 || close >= source.length() || close <= open) return;
        if (source.charAt(open) != '{' || source.charAt(close) != '}') return;
        out.add(new int[]{open, close});
    }

    // ─── Preuve de confinement ────────────────────────────────────────

    /**
     * Tente de prouver que l'édition {@code oldSource → newSource} est
     * confinée dans UN corps de niveau membre du mémo, et que ce corps
     * se referme toujours au même endroit dans la nouvelle source.
     *
     * <p>Conditions (toutes obligatoires) :</p>
     * <ul>
     *   <li>la région d'édition est strictement À L'INTÉRIEUR des
     *       accolades d'un corps (les accolades elles-mêmes sont
     *       intactes) ;</li>
     *   <li>le re-lexage conscient (chaînes, text blocks, caractères,
     *       commentaires) du corps depuis son « { » dans la nouvelle
     *       source retombe à profondeur zéro EXACTEMENT sur la fermante
     *       attendue (ancienne fermante + delta) et nulle part
     *       avant ;</li>
     *   <li>aucune autre plage ne chevauche la région (impossible en
     *       théorie — plages disjointes — garde défensive).</li>
     * </ul>
     *
     * @return la preuve de confinement, ou null (→ diagnose complet)
     */
    static Confined tryConfine(Memo memo, String newSource) {
        int[] region = editRegion(memo.source, newSource);
        if (region == null) return null;
        int start = region[0];
        int oldEnd = region[1];
        int newEnd = region[2];
        int delta = newEnd - oldEnd;

        int[] host = null;
        for (int[] span : memo.spans) {
            // Strictement à l'intérieur : « { » < start et oldEnd ≤ « } »
            // (la fermante en oldClose est préservée : la région s'arrête
            // au plus tôt juste avant elle).
            if (span[0] < start && oldEnd <= span[1]) {
                if (host == null || span[0] > host[0]) {
                    host = span;  // la plus interne (imbriquée impossible
                                  // au niveau membre, défensif)
                }
            }
            // Garde : une autre plage chevauchant la région → abandon.
            if (span[1] > start && span[0] < oldEnd
                    && !(span[0] < start && oldEnd <= span[1])) {
                return null;
            }
        }
        if (host == null) return null;
        int oldOpen = host[0];
        int oldClose = host[1];

        // Preuve d'obligation : le corps se referme exactement là où
        // l'attend la correspondance préfixe/suffixe, et nulle part avant.
        int expectedClose = oldClose + delta;
        if (expectedClose >= newSource.length()) return null;
        int found = SourceFocuser.findMatchingClose(newSource, oldOpen);
        if (found != expectedClose) return null;

        // Plages des autres corps, mappées dans la nouvelle source.
        int[][] others = new int[memo.spans.length - 1][];
        int k = 0;
        for (int[] span : memo.spans) {
            if (span == host) continue;
            int no = mapPos(span[0], start, oldEnd, delta);
            int nc = mapPos(span[1], start, oldEnd, delta);
            if (newSource.charAt(no) != '{'
                    || newSource.charAt(nc) != '}') {
                // Défensif — le texte hors région est identique, les
                // accolades doivent y être. Sinon : théorie violée.
                return null;
            }
            others[k++] = new int[]{no, nc};
        }
        return new Confined(start, oldEnd, newEnd,
                oldOpen, oldClose, expectedClose, others);
    }

    /**
     * Mappe une position de l'ancienne source vers la nouvelle (règle
     * préfixe/suffixe) : inchangée avant la région, décalée du delta
     * après. Les positions DANS la région retirée n'existent pas —
     * l'appelant ne doit jamais en demander (les problèmes portés sont
     * hors du corps édité).
     */
    static int mapPos(int pos, int start, int oldEnd, int delta) {
        return pos < start ? pos : pos + delta;
    }

    // ─── Source focalisée (remplacement des autres corps) ─────────────

    /**
     * Corps synthétique injecté dans les corps remplacés — statiquement
     * valide pour tout type de retour, aucun warning.
     */
    private static final String THROW_NULL = "throw null;";

    /**
     * Construit la source focalisée : le corps édité (plage
     * {@code hostNew = [open, close]}) intact, chaque autre corps
     * remplacé par {@code throw null;} + padding de MÊME LONGUEUR
     * (positions préservées, sauts de ligne conservés dans le padding).
     *
     * <p>Un corps trop court pour contenir le synthétique
     * ({@code < 12} caractères de contenu) est laissé VERBATIM — il
     * n'y a rien à économiser dessus, et ses problèmes restent frais.</p>
     *
     * @return {@code [sourceFocusée, plagesEffectivementRemplacées]}
     */
    static Object[] blankOtherBodies(String source, int hostOpen,
                                     int hostClose, int[][] otherSpans) {
        StringBuilder sb = new StringBuilder(source);
        List<int[]> blanked = new ArrayList<>(otherSpans.length);
        for (int[] span : otherSpans) {
            int open = span[0];
            int close = span[1];
            int contentLen = close - open - 1;  // entre les accolades
            if (contentLen < THROW_NULL.length()) continue;  // verbatim
            // « throw null; » sur les premiers caractères, padding
            // ensuite (les \n d'origine conservés pour la structure
            // de lignes).
            for (int i = 0; i < contentLen; i++) {
                int pos = open + 1 + i;
                char repl;
                if (i < THROW_NULL.length()) {
                    repl = THROW_NULL.charAt(i);
                } else {
                    repl = sb.charAt(pos) == '\n' ? '\n' : ' ';
                }
                sb.setCharAt(pos, repl);
            }
            blanked.add(span);
        }
        return new Object[]{sb.toString(),
                blanked.toArray(new int[0][])};
    }

    // ─── Assemblage du résultat ───────────────────────────────────────

    /**
     * Assemble le résultat du diagnose rapide : problèmes frais (hors
     * plages remplacées — artefacts du corps synthétique filtrés) +
     * problèmes portés du mémo (dans les plages remplacées, positions
     * mappées), triés par offset.
     *
     * <p><strong>Systèmes de coordonnées</strong> — le piège classique :
     * les problèmes FRAIS sont en coordonnées NOUVELLES (la source
     * focalisée est isomorphe à la nouvelle source), les problèmes du
     * MÉMO sont en coordonnées ANCIENNES. Le filtre « dans un corps
     * remplacé » s'applique donc à {@code blankedNew} pour les frais
     * et à {@code blankedOld} pour les portés — JAMAIS le même tableau
     * pour les deux.</p>
     *
     * <p>Suppression de la fausse famille « unused » : un problème
     * frais de type {@code IProblem.Unused*} que le mémo ne contenait
     * pas ET dont le nom (argument ecj du membre) figure dans le texte
     * d'un corps remplacé est un artefact du masquage — supprimé.</p>
     */
    static List<Diagnostic> assemble(List<Diagnostic> fresh,
                                     Memo memo, Confined c,
                                     int[][] blankedNew,
                                     int[][] blankedOld,
                                     String blankedText) {
        List<Diagnostic> out = new ArrayList<>(fresh.size() + 16);
        for (Diagnostic d : fresh) {
            int s = d.range().startOffset();
            int e = d.range().endOffset();
            if (insideAny(blankedNew, s, e)) continue;  // artefact
            if (isSpuriousUnused(d, memo, blankedText)) continue;
            out.add(d);
        }
        for (Diagnostic d : memo.problems) {
            int s = d.range().startOffset();
            int e = d.range().endOffset();
            // Portage : test en coordonnées ANCIENNES (celles du mémo).
            if (!insideAny(blankedOld, s, e)) continue;  // déjà frais
            int ns = mapPos(s, c.start, c.oldEnd, c.delta);
            int ne = mapPos(e, c.start, c.oldEnd, c.delta);
            if (ns < 0 || ne <= ns) {
                continue;  // défensif — ne devrait pas arriver
            }
            out.add(new Diagnostic(new TextRange(ns, ne),
                    d.severity(), d.message(), d.code(), d.source(),
                    d.arguments()));
        }
        out.sort((a, b) -> Integer.compare(
                a.range().startOffset(), b.range().startOffset()));
        return out;
    }

    private static boolean insideAny(int[][] spans, int start, int end) {
        for (int[] span : spans) {
            if (start >= span[0] + 1 && end <= span[1]) return true;
        }
        return false;
    }

    /**
     * Vrai pour les problèmes « never used » de la famille
     * {@code IProblem.Unused*} (code transporté « ecj:&lt;id&gt; »)
     * dont la déclaration est de niveau fichier/type : import, méthode
     * privée, champ privé, type privé.
     */
    private static boolean isSpuriousUnused(Diagnostic d, Memo memo,
                                            String blankedText) {
        String code = d.code();
        if (code == null || !code.startsWith("ecj:")) return false;
        int id;
        try {
            id = Integer.parseInt(code.substring(4));
        } catch (NumberFormatException badCode) {
            return false;
        }
        if (id != IProblem.UnusedImport
                && id != IProblem.UnusedPrivateMethod
                && id != IProblem.UnusedPrivateField
                && id != IProblem.UnusedPrivateType) {
            return false;
        }
        // Le mémo NE contenait PAS ce problème → « used » au parse
        // complet précédent.
        for (Diagnostic m : memo.problems) {
            if (code.equals(m.code())
                    && d.message().equals(m.message())) {
                return false;  // déjà signalé avant : légitime, garder
            }
        }
        // Le nom du membre figure-t-il dans le texte masqué ? Structure
        // des arguments ecj (vérifiée sur ecj 3.40) : UnusedImport →
        // [nomCompletImport] ; UnusedPrivate{Field,Method,Type} →
        // [typeDéclarant, nomMembre, …]. Le type déclarant n'est PAS une
        // preuve d'usage — on ne teste que le membre lui-même.
        List<String> args = d.arguments();
        int from = (id == IProblem.UnusedImport || args.size() < 2)
                ? 0 : 1;
        for (int i = from; i < args.size(); i++) {
            String a = args.get(i);
            if (a == null || a.isEmpty()) continue;
            if (containsIdentifier(blankedText, a)) return true;
            String simple = a.contains(".")
                    ? a.substring(a.lastIndexOf('.') + 1) : a;
            if (!simple.equals(a) && containsIdentifier(blankedText, simple)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cherche {@code name} comme identifiant délimité (non-identifiant
     * ou début/fin de chaîne de part et d'autre) dans {@code text}.
     */
    private static boolean containsIdentifier(String text, String name) {
        if (text == null || text.isEmpty()
                || name == null || name.isEmpty()) return false;
        int from = 0;
        while (true) {
            int i = text.indexOf(name, from);
            if (i < 0) return false;
            boolean leftOk = i == 0
                    || !Character.isJavaIdentifierPart(text.charAt(i - 1));
            int after = i + name.length();
            boolean rightOk = after >= text.length()
                    || !Character.isJavaIdentifierPart(text.charAt(after));
            if (leftOk && rightOk) return true;
            from = i + 1;
        }
    }

    /**
     * Contreparties ANCIENNES (coordonnées du mémo) des corps
     * effectivement remplacés : mêmes spans que le filtre verbatim de
     * {@link #blankOtherBodies} — la longueur de contenu est invariante
     * par le mapping (les corps hors région ont un texte identique).
     *
     * <p>Nécessaire au filtre de portage : les problèmes du mémo sont en
     * coordonnées ANCIENNES, ils doivent être testés contre des plages
     * ANCIENNES, puis mappés — jamais l'inverse.</p>
     */
    static int[][] blankedOldSpans(Memo memo, int hostOpen, int hostClose) {
        List<int[]> out = new ArrayList<>(memo.spans.length);
        for (int[] span : memo.spans) {
            if (span[0] == hostOpen && span[1] == hostClose) continue;
            if (span[1] - span[0] - 1 < THROW_NULL.length()) continue;
            out.add(span);
        }
        return out.toArray(new int[0][]);
    }

    /**
     * Nouvelles plages de corps pour le mémo suivant : plages autres
     * mappées + plage hôte rafraîchie (nouvelle fermante prouvée).
     */
    static int[][] nextSpans(Confined c) {
        int[][] spans = new int[c.otherSpansNew.length + 1][];
        System.arraycopy(c.otherSpansNew, 0, spans, 0,
                c.otherSpansNew.length);
        spans[c.otherSpansNew.length] =
                new int[]{c.oldOpen, c.newClose};
        Arrays.sort(spans, (a, b) -> Integer.compare(a[0], b[0]));
        return spans;
    }
}
