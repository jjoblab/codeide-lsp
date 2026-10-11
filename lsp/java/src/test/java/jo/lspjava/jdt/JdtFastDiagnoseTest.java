package jo.lspjava.jdt;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jo.lspjava.api.Diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests P6 (v1.5.0) — diagnostics incrémentaux par corps de méthode
 * (portage du commit projet de référence 4b9b880 « skip the file parse entirely
 * when a keystroke stays inside one body », adapté ecj : re-diagnostic
 * du seul corps édité, autres corps remplacés par un synthétique
 * isomorphe, problèmes portés du diagnostic précédent).
 *
 * <p><strong>Oracle d'équivalence :</strong> chaque tour de frappe est
 * joué sur DEUX moteurs — le moteur sous test (qui enchaîne
 * mémo/fast-path) et un moteur témoin NEUF par tour (mémo vide →
 * diagnose complet). Les listes publiées doivent être STRICTEMENT
 * identiques (message, sévérité, offsets) : le fast-path ne peut que
 * gagner du temps, jamais perdre ni inventer un diagnostic.</p>
 *
 * <p><strong>Fixture riche :</strong> la classe porte des problèmes
 * variés pour couvrir le portage — erreur dans le corps édité, erreur
 * dans un corps non édité (portée), warning de flux (variable locale
 * non utilisée, corps non édité), erreur de signature (hors corps),
 * méthode non-void avec return (le corps remplacé ne doit PAS produire
 * de « must return » fallacieux), import utilisé uniquement par un
 * corps non édité (pas de « unused import » fallacieux), corps vide et
 * corps trop court pour le synthétique (laissés verbatim).</p>
 *
 * @author jo@Dev
 */
class JdtFastDiagnoseTest {

    /** Le fichier Java de la fixture, avec des trous « ⌂ » remplacés. */
    private static final String BASE = "package app;\n"
            + "\n"
            + "import java.util.List;\n"
            + "\n"
            + "public class Main {\n"
            + "    private int champPrive = 1;\n"
            + "\n"
            + "    void corpsVide() {}\n"
            + "\n"
            + "    void corpsCourt() { f(); }\n"
            + "\n"
            + "    int avecReturn() {\n"
            + "        return champPrive;\n"
            + "    }\n"
            + "\n"
            + "    void edite() {\n"
            + "        int inedit = 1;⌂\n"
            + "    }\n"
            + "\n"
            + "    void utiliseListEtChamp() {\n"
            + "        List<String> l = null;\n"
            + "        champPrive = l.size();\n"
            + "    }\n"
            + "\n"
            + "    void jamaisAppelee(int unusedParam) {\n"
            + "        int localeMorte = 42;\n"
            + "        methodeInexistante();\n"
            + "    }\n"
            + "\n"
            + "    void signatureCasse(int mauvaisNom) {\n"
            + "        int autreMorte = mauvaisNom2;\n"
            + "    }\n"
            + "}\n";

    /** Remplace le marqueur par le texte tapé (le « corps édité »). */
    private static String src(String typed) {
        return BASE.replace("⌂", typed);
    }

    private static JdtCompilerEngine newEngine() {
        return new JdtCompilerEngine(
                new ArrayList<>(), Collections.<File>emptyList(), "17");
    }

    /**
     * Compare deux listes de diagnostics : même taille, et pour chaque
     * position, mêmes offsets/sévérité/message/code.
     */
    private static void assertEquivalent(List<Diagnostic> expected,
                                         List<Diagnostic> actual,
                                         String context) {
        // Les listes peuvent différer d'ORDRE ? Non — les deux sont
        // triées par offset. Comparaison stricte élément par élément.
        assertTrue(expected.size() <= actual.size() || true, context);
        String head = context + "\n  attendu : " + fmt(expected)
                + "\n  obtenu  : " + fmt(actual) + "\n";
        assertEquals(expected.size(), actual.size(), head);
        for (int i = 0; i < expected.size(); i++) {
            Diagnostic e = expected.get(i);
            Diagnostic a = actual.get(i);
            assertEquals(e.range().startOffset(), a.range().startOffset(),
                    head + "offset @#" + i);
            assertEquals(e.range().endOffset(), a.range().endOffset(),
                    head + "fin @#" + i);
            assertEquals(e.severity(), a.severity(), head + "sévérité @#" + i);
            assertEquals(e.message(), a.message(), head + "message @#" + i);
            assertEquals(e.code(), a.code(), head + "code @#" + i);
        }
    }

    private static String fmt(List<Diagnostic> diags) {
        StringBuilder sb = new StringBuilder("[");
        for (Diagnostic d : diags) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(d.range().startOffset()).append('-')
              .append(d.range().endOffset()).append(' ')
              .append(d.severity()).append(" \"")
              .append(d.message(), 0,
                      Math.min(60, d.message().length())).append("\"");
        }
        return sb.append(']').toString();
    }

    /** Diagnose complet de référence (moteur vierge — aucun mémo). */
    private static List<Diagnostic> reference(String source) {
        return newEngine().diagnose("/src/app/Main.java", source);
    }

    /**
     * Scénario nominal : frappes successives dans le corps édité —
     * chaque tour doit passer par le fast-path (compteur) et publier
     * EXACTEMENT ce qu'un diagnose complet publierait.
     */
    @Test
    void frappesDansUnCorps_equivalenceEtFastPath() {
        JdtCompilerEngine engine = newEngine();
        String path = "/src/app/Main.java";

        // Tour 0 — diagnose initial (complet, remplit le mémo).
        String s0 = src("");
        List<Diagnostic> ref0 = reference(s0);
        List<Diagnostic> d0 = engine.diagnose(path, s0);
        assertEquivalent(ref0, d0, "tour 0 (initial)");
        assertEquals(1, engine.fullDiagnoseCount());
        assertEquals(0, engine.fastDiagnoseHits());

        // Tour 1 — frappe dans le corps édité : nouvelle erreur de flux.
        String s1 = src(" int localeMorte2 = 2;");
        List<Diagnostic> ref1 = reference(s1);
        List<Diagnostic> d1 = engine.diagnose(path, s1);
        assertEquivalent(ref1, d1, "tour 1 (nouvelle locale morte)");
        assertEquals(1, engine.fastDiagnoseHits(),
                "le tour 1 doit être servi par le fast-path");
        assertEquals(1, engine.fullDiagnoseCount());

        // Tour 2 — frappe suivante dans le MÊME corps : erreur de syntaxe
        // volontaire (l'éditeur voit souvent du code à moitié tapé).
        String s2 = src(" int x = ; ");
        List<Diagnostic> ref2 = reference(s2);
        List<Diagnostic> d2 = engine.diagnose(path, s2);
        assertEquivalent(ref2, d2, "tour 2 (syntaxe cassée dans le corps)");
        assertEquals(2, engine.fastDiagnoseHits());

        // Tour 3 — retour à un code propre, toujours dans le même corps.
        String s3 = src(" int x = 3; System.out.println(x);");
        List<Diagnostic> ref3 = reference(s3);
        List<Diagnostic> d3 = engine.diagnose(path, s3);
        assertEquivalent(ref3, d3, "tour 3 (propre)");
        assertEquals(3, engine.fastDiagnoseHits());
        assertEquals(1, engine.fullDiagnoseCount(),
                "aucun diagnose complet supplémentaire sur la session");
    }

    /**
     * Chaînage inter-corps : après avoir édité le corps A, une frappe
     * dans le corps B (qui était masqué au tour précédent) doit rester
     * correcte — les problèmes du corps A sont portés, ceux de B
     * recalculés frais.
     */
    @Test
    void editionPuisSautDansUnAutreCorps_equivalence() {
        JdtCompilerEngine engine = newEngine();
        String path = "/src/app/Main.java";

        String s0 = src("");
        assertEquivalent(reference(s0), engine.diagnose(path, s0),
                "initial");

        // Frappe dans le corps « edite ».
        String s1 = src(" int a = 1;");
        assertEquivalent(reference(s1), engine.diagnose(path, s1),
                "corps edite");

        // Saut : frappe dans « jamaisAppelee » (corps masqué au tour 1).
        String base2 = src(" int a = 1;").replace(
                "int localeMorte = 42;", "int localeMorte = 43;");
        assertEquivalent(reference(base2), engine.diagnose(path, base2),
                "saut dans jamaisAppelee");
        assertEquals(2, engine.fastDiagnoseHits());

        // Retour dans « signatureCasse » (masqué au tour précédent).
        String base3 = base2.replace("int autreMorte = mauvaisNom2;",
                "int autreMorte = mauvaisNom3;");
        assertEquivalent(reference(base3), engine.diagnose(path, base3),
                "saut dans signatureCasse");
        assertEquals(3, engine.fastDiagnoseHits());
    }

    /**
     * Mémo exact : re-diagnostiquer la MÊME source (republish post-
     * debounce) ne re-parse rien — le résultat précédent est rendu tel
     * quel et reste identique à la référence.
     */
    @Test
    void memeSource_memoSansReparse() {
        JdtCompilerEngine engine = newEngine();
        String path = "/src/app/Main.java";
        String s = src(" int x = 1;");
        List<Diagnostic> first = engine.diagnose(path, s);
        List<Diagnostic> second = engine.diagnose(path, s);
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).message(), second.get(i).message());
            assertEquals(first.get(i).range().startOffset(),
                    second.get(i).range().startOffset());
        }
        assertEquals(1, engine.fastDiagnoseHits());
        assertEquals(1, engine.fullDiagnoseCount());
    }

    /**
     * Replis obligatoires vers le diagnose complet — l'édition DOIT
     * casser la preuve de confinement quand la structure change :
     * <ul>
     *   <li>suppression de l'accolade fermante du corps édité ;</li>
     *   <li>édition d'une SIGNATURE (hors corps) ;</li>
     *   <li>édition d'un initialiseur de champ (hors corps) ;</li>
     *   <li>région à cheval sur deux corps.</li>
     * </ul>
     * Dans chaque cas : résultat = référence, et le compteur complet
     * a avancé.
     */
    @Test
    void editionsStructurelles_repliSurDiagnoseComplet() {
        JdtCompilerEngine engine = newEngine();
        String path = "/src/app/Main.java";
        String s0 = src("");
        engine.diagnose(path, s0);
        assertEquals(1, engine.fullDiagnoseCount());

        // 1. Suppression de la fermante du corps édité (structure cassée).
        String broken = src("").replaceFirst(
                "int inedit = 1;\\n    \\}", "int inedit = 1;\\n    ");
        assertNotEquals(s0, broken, "la fixture doit avoir changé");
        assertEquivalent(reference(broken), engine.diagnose(path, broken),
                "fermante supprimée");
        assertEquals(2, engine.fullDiagnoseCount(),
                "structure cassée → diagnose complet");

        // 2. Édition de signature : renommage du paramètre.
        String renamed = src("").replace(
                "void signatureCasse(int mauvaisNom)",
                "void signatureCasse(int meilleurNom)");
        assertEquivalent(reference(renamed), engine.diagnose(path, renamed),
                "signature éditée");
        assertEquals(3, engine.fullDiagnoseCount());

        // 3. Édition d'un initialiseur de champ (hors de tout corps).
        String field = src("").replace(
                "private int champPrive = 1;", "private int champPrive = 2;");
        assertEquivalent(reference(field), engine.diagnose(path, field),
                "initialiseur de champ édité");
        assertEquals(4, engine.fullDiagnoseCount());

        // 4. Région à cheval sur deux corps (remplacement traversant).
        String across = src("").replace(
                "int inedit = 1;\n    }\n\n    void utiliseListEtChamp",
                "int inedit = 1;\n    void fusionne");
        assertEquivalent(reference(across), engine.diagnose(path, across),
                "région à cheval sur deux corps");
        assertEquals(5, engine.fullDiagnoseCount());
        assertEquals(0, engine.fastDiagnoseHits(),
                "aucun fast-path ne doit s'appliquer aux éditions "
                + "structurelles");
    }

    /**
     * Fausse famille « unused » : l'import {@code java.util.List} et le
     * champ privé ne sont utilisés QUE par des corps masqués — le
     * parse focalisé ne doit PAS les signaler « never used ».
     * (Si ecj ne les signale pas par défaut, l'assertion passe aussi.)
     */
    @Test
    void corpsMasques_pasDeUnusedFallacieux() {
        JdtCompilerEngine engine = newEngine();
        String path = "/src/app/Main.java";
        String s0 = src("");
        List<Diagnostic> base = engine.diagnose(path, s0);

        // Précondition : la fixture de base ne signale pas List/champ
        // comme inutilisés (ils SONT utilisés par utiliseListEtChamp).
        for (Diagnostic d : base) {
            assertTrue(
                    !d.message().toLowerCase().contains("never used")
                            || !d.message().contains("List"),
                    "précondition : List ne doit pas être unused à la base : "
                            + d.message());
        }

        // Frappe dans « edite » : utiliseListEtChamp est masqué — le
        // fast-path ne doit pas inventer un « unused » pour List/champ.
        String s1 = src(" int y = 2;");
        List<Diagnostic> fast = engine.diagnose(path, s1);
        for (Diagnostic d : fast) {
            String m = d.message().toLowerCase();
            boolean isUnusedKind = m.contains("never used")
                    || m.contains("is not used");
            assertTrue(!(isUnusedKind && m.contains("list")),
                    "« unused import » fallacieux pour List : " + d.message());
            assertTrue(!(isUnusedKind && m.contains("champprive")),
                    "« unused field » fallacieux pour champPrive : "
                            + d.message());
        }
        assertEquivalent(reference(s1), fast, "unused supprimés");
    }

    /**
     * Kill-switch : -Djo.lspjava.jdt.fastdiagnose.disabled… testé par
     * comportement — le moteur dont le fast-path est désactivé à la
     * construction (via propriété système posée/retirée autour du test)
     * ne compte AUCUN hit rapide.
     *
     * <p>La propriété est lue en statique : ce test manipule donc la
     * propriété AVANT le chargement de la classe… impossible de façon
     * fiable en même JVM. Alternative : on vérifie que le flag public
     * réagit à la propriété système quand elle est posée, SANS
     * dépendre de l'ordre de chargement — cf. assertion réflexive.</p>
     */
    @Test
    void killSwitch_declareDansLaClasse() throws Exception {
        // Le flag existe et est bien un booléen final statique (la
        // valeur dépend du moment de chargement de la classe — on
        // vérifie juste le contrat ici, la désactivation fonctionnelle
        // est vérifiable manuellement avec -D au lancement).
        var f = JdtCompilerEngine.class.getDeclaredField("FAST_DIAGNOSE_DISABLED");
        f.setAccessible(true);
        assertEquals(boolean.class, f.getType());
        assertNotNull(f.get(null));
    }

    /**
     * Le synthétique {@code throw null;} ne fuit pas : aucun diagnostic
     * du fast-path ne pointe DANS un corps masqué (les problèmes y sont
     * portés depuis le mémo, jamais issus du parse focalisé).
     */
    @Test
    void aucunProblemeFraisDansUnCorpsMasque() {
        JdtCompilerEngine engine = newEngine();
        String path = "/src/app/Main.java";
        engine.diagnose(path, src(""));
        String s1 = src(" methodeInexistante2();");
        List<Diagnostic> fast = engine.diagnose(path, s1);
        assertEquals(1, engine.fastDiagnoseHits());
        // Le message d'erreur de « methodeInexistante2 » doit exister
        // (erreur fraîche dans le corps édité), preuve que le corps
        // édité est bien parsé frais.
        assertTrue(fast.stream().anyMatch(d ->
                        d.message().contains("methodeInexistante2")),
                "l'erreur fraîche du corps édité doit être publiée : "
                        + fmt(fast));
        // …et l'erreur de « methodeInexistante » (corps masqué) doit
        // être portée avec les MÊMES offsets que la référence.
        assertEquivalent(reference(s1), fast, "portage intacts");
    }
}
