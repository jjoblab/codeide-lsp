package jo.lspjava.ecj;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jo.lspjava.api.CompletionItem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests des templates de complétion v1.3.0 — live templates
 * ({@link EcjLiveTemplates}), postfixes ({@link EcjPostfixTemplates}) et
 * leur intégration dans le pipeline {@link EcjCompletionEngine} (portage
 * Live/PostfixTemplates.kt du projet tiers de référence v3.20).
 *
 * <p>Trois niveaux :</p>
 * <ol>
 *   <li><b>Unitaire</b> — tiering de pertinence, scan du récepteur,
 *       prédicats sans binding (conscience de type) : SANS engine ;</li>
 *   <li><b>Intégration moteur</b> — {@code expr.var} sur un récepteur
 *       résolu produit un item SNIPPET avec édition de suppression du
 *       récepteur et snippet {@code Type ${1:nom} = expr;$0} ; les live
 *       templates sortent en position d'instruction et PAS en position
 *       d'expression ({@code x = sout|} — garde conservatrice) ;</li>
 *   <li><b>API</b> — l'item porte {@code isSnippet()} et des éditions
 *       additionnelles en offsets de la source ORIGINALE.</li>
 * </ol>
 *
 * <p>Le moteur d'intégration tourne avec classpath VIDE (jrt seul) : les
 * récepteurs {@code String} résolvent via l'image modules du JDK de test —
 * aucun android.jar requis.</p>
 *
 * @author jo@Dev
 */
class JdtTemplatesTest {

    @TempDir
    static Path tmp;

    static EcjCompilerEngine engine;
    static Path main;

    @BeforeAll
    static void setUp() throws Exception {
        Path src = Files.createDirectories(
                tmp.resolve("src/jo/test"));
        main = src.resolve("T.java");
        List<File> roots = new ArrayList<>();
        roots.add(src.toFile());
        // Classpath vide : java.lang.String et consorts résolvent via jrt
        // (desktop/tests). Le locateCache couvre alors uniquement des
        // négatifs — c'est aussi ce qu'on veut exercer.
        engine = new EcjCompilerEngine(roots, List.of(), "17");
    }

    // ═══ Niveau 1 : unitaire (sans engine) ═══

    @Test
    void liveTemplatesVidesAuCaretNu() {
        assertTrue(EcjLiveTemplates.itemsFor("").isEmpty(),
                "caret nu → aucune live template (pas d'inondation du popup)");
        assertTrue(EcjLiveTemplates.itemsFor(null).isEmpty(),
                "préfixe null → aucune");
    }

    @Test
    void liveTemplatesTiering() {
        List<EcjLiveTemplates.TemplateItem> souts = EcjLiveTemplates.itemsFor("sout");
        assertEquals(2, souts.size(), "préfixe « sout » → sout + soutv");
        assertEquals("sout", souts.get(0).key());
        assertEquals(EcjLiveTemplates.RELEVANCE_TEMPLATE_EXACT,
                souts.get(0).relevance(), "clé exacte → 2500 (en tête)");
        assertEquals("soutv", souts.get(1).key());
        assertEquals(EcjLiveTemplates.RELEVANCE_TEMPLATE_PARTIEL,
                souts.get(1).relevance(), "clé partielle → 100 (discrète)");
        assertEquals("System.out.println($0);", souts.get(0).snippet(),
                "snippet sout exact");
    }

    @Test
    void liveTemplatesTabStopsLies() {
        String fori = EcjLiveTemplates.itemsFor("fori").get(0).snippet();
        assertTrue(fori.contains("${1:i}"),
                "fori → tab stop lié ${1:i} : " + fori);
        String tr = EcjLiveTemplates.itemsFor("try").get(0).snippet();
        assertTrue(tr.contains("catch (${1:Exception}"),
                "try → catch avec placeholder : " + tr);
    }

    @Test
    void postfixScanDuRecepteur() {
        assertEquals("list", receiver("list.sout", 0));
        assertEquals("foo()", receiver("foo().var", 0));
        assertEquals("arr[i]", receiver("arr[i].for", 0));
        // Chaîne qualifiée : le récepteur est l'expression entière AVANT le
        // DERNIER point (celui qui porte la clé).
        assertEquals("a.b().c", receiver("a.b().c.sout", -1));
        assertEquals("\"texte\"", receiver("\"texte\".len", 0));
    }

    /** Extrait le récepteur scanné pour {@code expr.clé} (dot AVANT la clé). */
    private static String receiver(String text, int dotIdx) {
        int dot = -1;
        for (int i = 0; i <= dotIdx; i++) {
            dot = text.indexOf('.', dot + 1);
        }
        if (dotIdx < 0) dot = text.lastIndexOf('.');
        int recv = EcjPostfixTemplates.receiverStart(text, dot);
        return text.substring(recv, dot);
    }

    @Test
    void postfixSansBindingConscienceDeType() {
        // value.sout → template ANY_VALUE, plage de suppression = « value. »
        List<EcjPostfixTemplates.PostfixItem> items = EcjPostfixTemplates.itemsFor(
                "value.sout", 6, 10, "sout", null, false, 0);
        assertEquals(1, items.size(), "value.sout → exactement sout (sans binding)");
        assertEquals(0, items.get(0).deleteStart(), "suppression débute au récepteur");
        assertEquals(6, items.get(0).deleteEnd(), "suppression s'arrête avant la clé");
        assertEquals("System.out.println(value);$0", items.get(0).snippet());

        // value.var → ${1:…} = value; (nom dérivé « var » sans binding)
        List<EcjPostfixTemplates.PostfixItem> vars = EcjPostfixTemplates.itemsFor(
                "value.var", 6, 9, "var", null, false, 0);
        assertEquals(1, vars.size());
        assertTrue(vars.get(0).snippet().contains("${1:")
                && vars.get(0).snippet().contains("= value;"),
                "var → ${1:…} = value; : " + vars.get(0).snippet());

        // Sans type résolu, « opt » (isReference) n'est PAS proposée :
        // c'est la conscience de type — une template ne sort pas là où
        // elle ne compilerait pas.
        assertTrue(EcjPostfixTemplates.itemsFor(
                        "value.opt", 6, 9, "opt", null, false, 24).isEmpty(),
                "opt sans type résolu → non proposée");
        // « cast » (ANY_VALUE) reste proposée.
        assertEquals(1, EcjPostfixTemplates.itemsFor(
                        "value.cast", 6, 10, "cast", null, false, 24).size(),
                "cast (ANY_VALUE) proposée sans binding");

        // Sans point → aucune postfix.
        assertTrue(EcjPostfixTemplates.itemsFor(
                        "sout", 0, 4, "sout", null, false, 0).isEmpty(),
                "pas de récepteur → aucune postfix");
    }

    // ═══ Niveau 1 bis : gardes textuelles du moteur ═══

    @Test
    void positionInstruction() {
        String src = "void f() {\n    sout\n}";
        int key = src.indexOf("sout");
        assertTrue(EcjCompletionEngine.isStatementPosition(src, key),
                "tête de ligne → position d'instruction");
        assertTrue(EcjCompletionEngine.isStatementPosition(
                "a(); sout", "a(); sout".indexOf("sout")),
                "après « ; » → instruction");
        assertTrue(EcjCompletionEngine.isStatementPosition(
                "if (x) { sout", "if (x) { sout".indexOf("sout")),
                "après « { » → instruction");
        assertTrue(EcjCompletionEngine.isStatementPosition("sout", 0),
                "début de fichier → instruction");
        assertFalse(EcjCompletionEngine.isStatementPosition(
                "x = sout", "x = sout".indexOf("sout")),
                "après « = » → EXPRESSION (garde)");
        assertFalse(EcjCompletionEngine.isStatementPosition(
                "foo(sout", "foo(sout".indexOf("sout")),
                "dans des arguments → EXPRESSION (garde)");
        assertFalse(EcjCompletionEngine.isStatementPosition(
                "x.sout", "x.sout".indexOf("sout")),
                "après un point → accès membre (garde)");
    }

    @Test
    void ancreImport() {
        // "package a.b;\n" = 13 + "import java.util.Map;\n" = 22 → 35.
        assertEquals(35, EcjCompletionEngine.importAnchorOffset(
                "package a.b;\nimport java.util.Map;\n\nclass C {}\n"),
                "après le DERNIER import");
        assertEquals(13, EcjCompletionEngine.importAnchorOffset(
                "package a.b;\nclass C {}\n"),
                "après le package (aucun import)");
        assertEquals(0, EcjCompletionEngine.importAnchorOffset(
                "class C {}\n"), "début de fichier (rien)");
    }

    // ═══ Niveau 2 : intégration moteur (complete) ═══

    @Test
    void liveTemplateEnPositionInstruction() throws Exception {
        String src = "package jo.test;\n\n"
                + "public class T {\n"
                + "    void f() {\n"
                + "        sout\n"
                + "    }\n"
                + "}\n";
        int caret = src.indexOf("sout") + 4;
        List<CompletionItem> items = complete(src, caret);
        assertNotNull(items, "contexte résolu");
        CompletionItem sout = byLabel(items, "sout");
        assertNotNull(sout, "live template « sout » proposée : " + labels(items));
        assertEquals(CompletionItem.Kind.SNIPPET, sout.kind());
        assertTrue(sout.isSnippet(), "isSnippet → insertTextFormat=Snippet côté LSP");
        assertEquals("System.out.println($0);", sout.insertText());
        assertEquals(EcjLiveTemplates.RELEVANCE_TEMPLATE_EXACT, sout.relevance(),
                "clé entièrement tapée → en tête (2500)");
        assertTrue(items.get(0).label().equals("sout"),
                "la template exacte domine la liste : " + labels(items));
    }

    @Test
    void liveTemplateAbsenteEnPositionExpression() throws Exception {
        String src = "package jo.test;\n\n"
                + "public class T {\n"
                + "    void f() {\n"
                + "        int x = sout\n"
                + "    }\n"
                + "}\n";
        int caret = src.indexOf("sout") + 4;
        List<CompletionItem> items = complete(src, caret);
        // La complétion ordinaire peut être nulle (rien ne matche « sout ») —
        // l'important : AUCUNE template d'instruction n'est émise.
        if (items != null) {
            assertFalse(items.stream().anyMatch(i -> i.isSnippet()
                            && "sout".equals(i.label())),
                    "aucune template d'instruction après « = » (garde conservatrice) : "
                            + labels(items));
        }
    }

    @Test
    void postfixVarSurRecepteurResolu() throws Exception {
        String src = "package jo.test;\n\n"
                + "public class T {\n"
                + "    void f() {\n"
                + "        String name = \"x\";\n"
                + "        name.var\n"
                + "    }\n"
                + "}\n";
        int caret = src.indexOf("name.var") + "name.var".length();
        List<CompletionItem> items = complete(src, caret);
        assertNotNull(items, "MEMBER_ACCESS résolu");
        CompletionItem var = byLabel(items, "var");
        assertNotNull(var, "template « var » proposée sur String : " + labels(items));
        assertEquals(CompletionItem.Kind.SNIPPET, var.kind());
        assertTrue(var.isSnippet());
        // Snippet : String ${1:string} = name;$0 — le TYPE vient du binding,
        // le nom proposé dérive du type (première lettre minuscule).
        assertEquals("String ${1:string} = name;$0", var.insertText(),
                "réécriture consciente du type résolu");
        // Édition additionnelle : suppression du récepteur « name. » en
        // offsets de la source ORIGINALE.
        assertEquals(1, var.additionalEdits().size(),
                "une édition : suppression du récepteur");
        CompletionItem.TextEdit del = var.additionalEdits().get(0);
        int recvStart = src.lastIndexOf("name.var");
        assertEquals(recvStart, del.start(), "début = récepteur");
        assertEquals(recvStart + "name.".length(), del.end(), "fin = avant la clé");
        assertEquals("", del.newText(), "suppression");
        // Contrainte LSP : jamais de chevauchement avec la plage principale.
        assertTrue(del.end() <= src.indexOf("name.var") + "name.var".length());
    }

    @Test
    void postfixOptImporteOptionalToutSeul() throws Exception {
        String src = "package jo.test;\n\n"
                + "public class T {\n"
                + "    void f() {\n"
                + "        String name = \"x\";\n"
                + "        name.opt\n"
                + "    }\n"
                + "}\n";
        int caret = src.indexOf("name.opt") + "name.opt".length();
        List<CompletionItem> items = complete(src, caret);
        assertNotNull(items);
        CompletionItem opt = byLabel(items, "opt");
        assertNotNull(opt, "template « opt » sur un type référence : " + labels(items));
        assertEquals("Optional.ofNullable(name)$0", opt.insertText());
        // DEUX éditions : suppression du récepteur + import java.util.Optional.
        assertEquals(2, opt.additionalEdits().size(), "suppression + import");
        CompletionItem.TextEdit imp = opt.additionalEdits().get(1);
        // L'import s'insère après la ligne package (aucun import existant).
        int pkgEnd = src.indexOf("package jo.test;\n") + "package jo.test;\n".length();
        assertEquals(pkgEnd, imp.start());
        assertEquals(pkgEnd, imp.end());
        assertEquals("import java.util.Optional;\n", imp.newText());
    }

    @Test
    void postfixBoolFiltreesParType() throws Exception {
        // Un récepteur booléen : if/else/not s'appliquent.
        String boolSrc = "package jo.test;\n\n"
                + "public class T {\n"
                + "    void f() {\n"
                + "        boolean ok = true;\n"
                + "        ok.if\n"
                + "    }\n"
                + "}\n";
        int caret = boolSrc.indexOf("ok.if") + "ok.if".length();
        List<CompletionItem> boolItems = complete(boolSrc, caret);
        assertNotNull(boolItems);
        assertNotNull(byLabel(boolItems, "if"),
                "« if » sur un booléen : " + labels(boolItems));
        assertEquals("if (ok) {\n    $0\n}", byLabel(boolItems, "if").insertText());

        // Un récepteur String : AUCUNE template « if » (prédicat isBoolean) —
        // et aucune autre clé ne matche le préfixe « if » ; la complétion
        // peut donc être nulle, c'est la conscience de type.
        String strSrc = boolSrc.replace("boolean ok = true;", "String ok = \"x\";");
        int strCaret = strSrc.indexOf("ok.if") + "ok.if".length();
        List<CompletionItem> strItems = complete(strSrc, strCaret);
        if (strItems != null) {
            assertFalse(strItems.stream().anyMatch(
                            i -> i.isSnippet()),
                    "aucune template postfixe ne s'applique à « String.if » : "
                            + labels(strItems));
        }
    }

    @Test
    void lesTemplatesNePolluentPasLesContextesNonPrevus() throws Exception {
        // Prefix vide en position de nom → AUCUNE live template.
        String src = "package jo.test;\n\n"
                + "public class T {\n"
                + "    void f() {\n"
                + "        \n"
                + "    }\n"
                + "}\n";
        int caret = src.indexOf("        \n") + 8;
        List<CompletionItem> bare = complete(src, caret);
        if (bare != null) {
            assertFalse(bare.stream().anyMatch(CompletionItem::isSnippet),
                    "caret nu → aucune template : " + labels(bare));
        }
        // Import : la complétion de paquet ne contient aucune template.
        String impSrc = "package jo.test;\n\nimport java.util.\n";
        int impCaret = impSrc.length();
        List<CompletionItem> impItems = complete(impSrc, impCaret);
        if (impItems != null) {
            assertFalse(impItems.stream().anyMatch(CompletionItem::isSnippet),
                    "IMPORT_REFERENCE → aucune template : " + labels(impItems));
        }
    }

    // ═══ Helpers ═══

    private static List<CompletionItem> complete(String source, int caret)
            throws Exception {
        Assumptions.assumeTrue(engine != null, "engine indisponible");
        Files.write(main, source.getBytes(StandardCharsets.UTF_8));
        EcjCompletionEngine ce = new EcjCompletionEngine(engine);
        return ce.complete(main.toString(), source, caret);
    }

    private static CompletionItem byLabel(List<CompletionItem> items, String label) {
        for (CompletionItem it : items) {
            if (it.label().equals(label) && it.isSnippet()) return it;
        }
        return null;
    }

    private static List<String> labels(List<CompletionItem> items) {
        List<String> out = new ArrayList<>();
        for (CompletionItem it : items) out.add(it.label());
        return out;
    }
}
