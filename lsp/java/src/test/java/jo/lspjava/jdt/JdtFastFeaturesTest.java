package jo.lspjava.jdt;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests v1.6.0 — extension du fast-path P6 aux features par-frappe :
 * <strong>hover</strong> et <strong>signature help</strong> servis sans
 * re-parse depuis l'arbre focalisé que le diagnose rapide a déjà résolu
 * (chaîne à 3 niveaux : arbre focalisé retenu → arbre réel du
 * parseCache → réduction SourceFocuser du v1.5).
 *
 * <p><strong>Oracle d'équivalence</strong> (pattern
 * {@link JdtFastDiagnoseTest}) : chaque requête est jouée sur le moteur
 * sous test (qui enchaîne diagnose rapide / arbre retenu) ET sur un
 * moteur témoin NEUF (aucun mémo, aucun arbre retenu — chemin complet).
 * Les résultats doivent être STRICTEMENT identiques : le fast-path ne
 * peut que gagner du temps, jamais changer une réponse.</p>
 *
 * <p><strong>Compteurs</strong> : {@code fastResolveHits} prouve que
 * l'arbre focalisé a bien servi (sinon le test passerait à vide —
 * équivalence par repli), {@code cachedResolveHits} prouve le service
 * par l'arbre réel du parseCache (après un diagnose complet).</p>
 *
 * <p>Le kill-switch P6 ({@code -Djo.lspjava.jdt.fastdiagnose.disabled})
 * coupe aussi {@code resolveUnitFast} — son contrat est couvert par
 * {@code JdtFastDiagnoseTest.killSwitch_declareDansLaClasse}.</p>
 *
 * @author jo@Dev
 */
class JdtFastFeaturesTest {

    /** Fichier Java de la fixture — « ⌂ » = point d'insertion des frappes. */
    private static final String BASE = "package app;\n"
            + "\n"
            + "public class Main {\n"
            + "    private int total = 0;\n"
            + "\n"
            + "    int add(int a, int b) {\n"
            + "        return a + b;\n"
            + "    }\n"
            + "\n"
            + "    String join(String first, String second) {\n"
            + "        return first + second;\n"
            + "    }\n"
            + "\n"
            + "    void edite() {\n"
            + "        int avant = 1;⌂\n"
            + "    }\n"
            + "\n"
            + "    void autre() {\n"
            + "        String mot = \"x\";\n"
            + "        mot = mot.toUpperCase();\n"
            + "        total = add(total, 7);\n"
            + "    }\n"
            + "\n"
            + "    void bourre() {\n"
            + "        // BOURRAGE\n"
            + "    }\n"
            + "}\n";

    /** Frappe insérée dans le corps édité (méthode {@code edite}). */
    private static final String TYPED = " int somme = add(total, 2);";

    /** Remplace le marqueur par le texte tapé (le « corps édité »). */
    private static String src(String typed) {
        return BASE.replace("⌂", typed);
    }

    /**
     * Variante ≥ 2000 caractères : le corps {@code bourre} est rempli —
     * la réduction {@link SourceFocuser} s'active alors côté témoin
     * (fichier < 2000 : identité, pas de réduction), ce qui teste
     * l'équivalence « arbre focalisé isomorphe » contre « arbre réduit
     * par splices » — les deux transformations différentes du fichier.
     */
    private static String bigSrc(String typed) {
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            pad.append("        total += 1; // ligne de bourrage numero ")
               .append(i).append('\n');
        }
        return src(typed).replace("// BOURRAGE", pad.toString());
    }

    private static JdtCompilerEngine newEngine() {
        return new JdtCompilerEngine(
                new ArrayList<>(), Collections.<File>emptyList(), "17");
    }

    /** Simule un didChange : le flux LSP invalide le parseCache. */
    private static void didChange(JdtCompilerEngine engine, String path) {
        engine.invalidateParseCache(path);
    }

    // ─── Hover ────────────────────────────────────────────────────────

    /**
     * Hover dans le corps édité après un diagnose RAPIDE : servi par
     * l'arbre focalisé retenu (compteur), réponse identique au témoin
     * sur trois sortes de symboles — locale, appel de méthode, champ.
     */
    @Test
    void hoverDansCorpsEdite_serviParArbreFocalise() {
        JdtCompilerEngine engine = newEngine();
        String path = "/src/app/Main.java";

        didChange(engine, path);
        engine.diagnose(path, src(""));
        didChange(engine, path);
        String s1 = src(TYPED);
        engine.diagnose(path, s1);
        assertEquals(1, engine.fastDiagnoseHits(),
                "la frappe doit être diagnostiquée par le fast-path P6");

        // Trois points de hover dans le corps hôte.
        hoverEquiv(engine, path, s1, s1.indexOf("somme"), "somme");
        hoverEquiv(engine, path, s1, s1.indexOf("add("), "add(");
        hoverEquiv(engine, path, s1, s1.indexOf("total, 2"),
                "total (argument)");

        assertTrue(engine.fastResolveHits() >= 1,
                "au moins un hover doit être servi par l'arbre focalisé "
                        + "(obtenu : " + engine.fastResolveHits() + ")");
        assertEquals(0, engine.cachedResolveHits(),
                "aucun service par le parseCache après un diagnose "
                        + "RAPIDE (le cache est vide — compileUnit bypass)");
    }

    /**
     * Hover HORS du corps hôte (dans un corps masqué par le
     * synthétique) : l'arbre focalisé ne doit PAS être servi (garde
     * d'offset) — repli jusqu'au chemin réduit, réponse identique au
     * témoin.
     */
    @Test
    void hoverHorsCorpsHote_repliSansArbreFocalise() {
        JdtCompilerEngine engine = newEngine();
        String path = "/src/app/Main.java";

        didChange(engine, path);
        engine.diagnose(path, src(""));
        didChange(engine, path);
        String s1 = src(TYPED);
        engine.diagnose(path, s1);
        assertEquals(1, engine.fastDiagnoseHits());

        // « mot » vit dans autre() — corps masqué au dernier diagnose.
        // Premier repli : garde d'offset → ni arbre focalisé, ni arbre
        // du parseCache (vide après un diagnose rapide) → chemin réduit,
        // qui REMPLIT le cache pour la requête suivante.
        hoverEquiv(engine, path, s1, s1.indexOf("mot.toUpperCase"),
                "mot (corps masqué)");
        assertEquals(0, engine.fastResolveHits(),
                "l'arbre focalisé ne doit jamais servir un offset hors "
                        + "du corps hôte");
        assertEquals(0, engine.cachedResolveHits(),
                "premier repli : le parseCache est vide après un "
                        + "diagnose rapide (compileUnit bypass)");

        // « first » vit dans join() — paramètre d'un autre corps. Le
        // cache est désormais rempli par le repli précédent : servi par
        // l'arbre réel (fidèle partout) — jamais par l'arbre focalisé.
        hoverEquiv(engine, path, s1, s1.indexOf("first + second"),
                "first (corps masqué)");
        assertEquals(0, engine.fastResolveHits(),
                "l'arbre focalisé ne doit toujours pas servir hors hôte");
        assertEquals(1, engine.cachedResolveHits(),
                "deuxième requête : servie par l'arbre réel du "
                        + "parseCache rempli au repli précédent");
    }

    /**
     * Hover sur un contenu NOUVEAU (frappe sans diagnose encore) : le
     * contenu-clé interdit de servir l'arbre périmé — l'oracle change
     * volontairement le TYPE de la locale ({@code int} →
     * {@code String}) : l'arbre périmé répondrait « int somme ».
     */
    @Test
    void contenuDifferent_arbrePerimeJamaisServi() {
        JdtCompilerEngine engine = newEngine();
        String path = "/src/app/Main.java";

        didChange(engine, path);
        engine.diagnose(path, src(""));
        didChange(engine, path);
        String s1 = src(TYPED);
        engine.diagnose(path, s1);   // arbre focalisé retenu pour s1
        assertEquals(1, engine.fastDiagnoseHits());

        // Frappe suivante SANS diagnose (le debounce n'a pas encore tiré).
        String s2 = s1.replace("int somme = add(total, 2);",
                "String somme = \"a\";");
        String ref = newEngine().hoverAt(path, s2, s2.indexOf("somme"));
        assertNotNull(ref, "le témoin doit hover la nouvelle locale");
        assertTrue(ref.contains("String"),
                "le type doit être le NOUVEAU : " + ref);

        String actual = engine.hoverAt(path, s2, s2.indexOf("somme"));
        assertEquals(ref, actual, "équivalence stricte avec le témoin");
        assertFalse(actual.contains("int somme"),
                "l'arbre périmé (s1) aurait répondu « int somme »");
        assertEquals(0, engine.fastResolveHits(),
                "la garde de contenu doit rejeter avant tout comptage");
    }

    // ─── Signature help ───────────────────────────────────────────────

    /**
     * Signature help dans la liste d'arguments d'un appel du corps
     * édité : servi par l'arbre focalisé retenu (compteur), surcharges
     * et paramètre actif identiques au témoin.
     */
    @Test
    void signatureHelpDansCorpsEdite_serviParArbreFocalise() {
        JdtCompilerEngine engine = newEngine();
        String path = "/src/app/Main.java";

        didChange(engine, path);
        engine.diagnose(path, src(""));
        didChange(engine, path);
        String s1 = src(TYPED);
        engine.diagnose(path, s1);
        assertEquals(1, engine.fastDiagnoseHits());

        // Caret après la virgule de « add(total, 2) » → paramètre 1.
        int call = s1.indexOf("add(total, ");
        int caret = call + "add(total, ".length();
        sigHelpEquiv(engine, path, s1, caret);

        assertTrue(engine.fastResolveHits() >= 1,
                "le signature help doit être servi par l'arbre focalisé");
        // Vérifications de sens (le témoin les garantit déjà, on les
        // rend explicites pour la lisibilité du rapport d'échec).
        JdtSignatureHelp.Result r =
                new JdtSignatureHelp(engine).signatureHelpAt(path, s1, caret);
        assertNotNull(r);
        assertEquals(1, r.activeParameter, "deuxième argument en saisie");
        assertTrue(r.signatures.get(r.activeSignature).label.contains("add"),
                "label : " + r.signatures.get(r.activeSignature).label);
    }

    /**
     * Signature help dans un appel d'un corps MASQUÉ : garde d'offset →
     * repli, réponse identique au témoin.
     */
    @Test
    void signatureHelpHorsCorpsHote_repli() {
        JdtCompilerEngine engine = newEngine();
        String path = "/src/app/Main.java";

        didChange(engine, path);
        engine.diagnose(path, src(""));
        didChange(engine, path);
        String s1 = src(TYPED);
        engine.diagnose(path, s1);

        // « add(total, 7) » vit dans autre() — corps masqué.
        int call = s1.indexOf("add(total, 7)");
        int caret = call + "add(total, ".length();
        sigHelpEquiv(engine, path, s1, caret);
        assertEquals(0, engine.fastResolveHits(),
                "aucun service par l'arbre focalisé hors du corps hôte");
    }

    // ─── Arbre réel du parseCache (diagnose complet) ──────────────────

    /**
     * Après un diagnose COMPLET (premier diagnose — pas de fast-path),
     * hover et signature help sont servis par l'arbre RÉEL du
     * parseCache (probe pure, zéro re-parse) — la réduction
     * SourceFocuser n'est plus nécessaire quand l'arbre réel est déjà
     * résolu pour ce contenu.
     */
    @Test
    void apresDiagnoseComplet_featuresParArbreReel() {
        JdtCompilerEngine engine = newEngine();
        String path = "/src/app/Main.java";
        String s = src(TYPED);

        didChange(engine, path);
        engine.diagnose(path, s);
        assertEquals(0, engine.fastDiagnoseHits(),
                "premier diagnose : complet");
        assertEquals(1, engine.fullDiagnoseCount());

        // Hover n'importe où dans le fichier (corps édité OU masqué —
        // l'arbre réel est fidèle partout).
        hoverEquiv(engine, path, s, s.indexOf("somme"), "somme");
        hoverEquiv(engine, path, s, s.indexOf("mot.toUpperCase"),
                "mot (autre corps)");
        int call = s.indexOf("add(total, ");
        sigHelpEquiv(engine, path, s, call + "add(total, ".length());

        assertEquals(0, engine.fastResolveHits(),
                "pas d'arbre focalisé retenu après un diagnose complet");
        assertTrue(engine.cachedResolveHits() >= 3,
                "hover×2 + signature help servis par l'arbre réel du "
                        + "parseCache (obtenu : " + engine.cachedResolveHits()
                        + ")");
    }

    // ─── Gros fichier (réduction active) + chaînage ───────────────────

    /**
     * Fichier ≥ 2000 caractères : le témoin RÉDUIT la source
     * (SourceFocuser par splices, offsets translatés) tandis que le
     * moteur sous test sert l'arbre focalisé ISOMORPHE (positions
     * réelles) — les deux transformations différentes doivent rendre
     * les mêmes réponses.
     */
    @Test
    void grosFichier_arbreFocaliseContreSourceReduite() {
        JdtCompilerEngine engine = newEngine();
        String path = "/src/app/Main.java";
        String big0 = bigSrc("");
        assertTrue(big0.length() >= 2_000,
                "précondition : la fixture doit dépasser 2000 chars "
                        + "(" + big0.length() + ")");

        didChange(engine, path);
        engine.diagnose(path, big0);
        didChange(engine, path);
        String big1 = bigSrc(TYPED);
        engine.diagnose(path, big1);
        assertEquals(1, engine.fastDiagnoseHits(),
                "frappe confinée → diagnose rapide même sur gros fichier");

        hoverEquiv(engine, path, big1, big1.indexOf("somme"), "somme");
        int call = big1.indexOf("add(total, ");
        sigHelpEquiv(engine, path, big1, call + "add(total, ".length());

        assertTrue(engine.fastResolveHits() >= 2,
                "hover + signature help servis par l'arbre focalisé "
                        + "(obtenu : " + engine.fastResolveHits() + ")");
    }

    /**
     * Session multi-tours : frappes enchaînées dans le corps édité,
     * chaque tour = didChange + diagnose + hover + signature help. Le
     * type de la locale change au tour 3 ({@code int} → {@code long}) —
     * l'arbre retenu du tour précédent ne doit jamais fuiter (réponse
     * « long » dès le tour 3, y compris avant le diagnose qui suit).
     */
    @Test
    void sessionMultiTours_reponsesToujoursEquivalentes() {
        JdtCompilerEngine engine = newEngine();
        String path = "/src/app/Main.java";

        // Tour 0 — diagnose initial.
        String s0 = src("");
        didChange(engine, path);
        engine.diagnose(path, s0);

        // Tour 1 — frappe : appel dans le corps édité.
        String s1 = src(TYPED);
        didChange(engine, path);
        engine.diagnose(path, s1);
        assertEquals(1, engine.fastDiagnoseHits());
        hoverEquiv(engine, path, s1, s1.indexOf("somme"), "somme tour 1");
        int call1 = s1.indexOf("add(total, ");
        sigHelpEquiv(engine, path, s1, call1 + "add(total, ".length());

        // Tour 2 — frappe suivante, même corps (valeur de l'argument).
        String s2 = s1.replace("add(total, 2)", "add(total, 5)");
        didChange(engine, path);
        engine.diagnose(path, s2);
        assertEquals(2, engine.fastDiagnoseHits());
        hoverEquiv(engine, path, s2, s2.indexOf("somme"), "somme tour 2");
        int call2 = s2.indexOf("add(total, ");
        sigHelpEquiv(engine, path, s2, call2 + "add(total, ".length());

        // Tour 3 — le TYPE de la locale change : l'arbre du tour 2
        // (int) doit être évincé par la clé de contenu.
        String s3 = s2.replace("int somme = add(total, 5);",
                "long somme = 9L;");
        String refAvant = newEngine().hoverAt(path, s3, s3.indexOf("somme"));
        assertNotNull(refAvant);
        assertTrue(refAvant.contains("long"),
                "le témoin voit le nouveau type : " + refAvant);
        String avantDiagnose = engine.hoverAt(path, s3, s3.indexOf("somme"));
        assertEquals(refAvant, avantDiagnose,
                "hover AVANT le diagnose du tour 3 : la garde de contenu "
                        + "doit interdire l'arbre du tour 2");
        assertFalse(avantDiagnose.contains("int somme"),
                "l'arbre périmé (int) ne doit jamais fuiter");

        didChange(engine, path);
        engine.diagnose(path, s3);
        assertEquals(3, engine.fastDiagnoseHits());
        hoverEquiv(engine, path, s3, s3.indexOf("somme"), "somme tour 3");
        // L'appel a disparu au tour 3 : signature help null des deux
        // côtés (équivalence des « rien à montrer »).
        JdtSignatureHelp.Result r =
                new JdtSignatureHelp(engine).signatureHelpAt(path, s3,
                        s3.indexOf("somme"));
        assertNull(r, "plus d'appel sous le caret au tour 3");
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    /**
     * Oracle hover : le moteur sous test et un moteur témoin NEUF
     * doivent rendre exactement la même réponse (y compris null),
     * comptée non-nulle quand le point est censé être hoverable.
     */
    private static void hoverEquiv(JdtCompilerEngine engine, String path,
                                   String source, int offset, String label) {
        String ref = newEngine().hoverAt(path, source, offset);
        String actual = engine.hoverAt(path, source, offset);
        assertEquals(ref, actual,
                "hover divergent sur « " + label + " » à l'offset " + offset
                        + " : attendu=" + ref + " obtenu=" + actual);
    }

    /**
     * Oracle signature help : équivalence stricte — mêmes signatures
     * (labels, noms de paramètres, documentation), même signature
     * active, même paramètre actif ; null d'un côté = null de l'autre.
     */
    private static void sigHelpEquiv(JdtCompilerEngine engine, String path,
                                     String source, int offset) {
        JdtSignatureHelp.Result ref =
                new JdtSignatureHelp(newEngine()).signatureHelpAt(path, source, offset);
        JdtSignatureHelp.Result actual =
                new JdtSignatureHelp(engine).signatureHelpAt(path, source, offset);
        String head = "signature help divergent à l'offset " + offset + " : ";
        if (ref == null) {
            assertNull(actual, head + "le témoin ne trouve pas d'appel, "
                    + "le moteur sous test non plus");
            return;
        }
        assertNotNull(actual, head + "le témoin trouve un appel, pas le "
                + "moteur sous test");
        assertEquals(ref.signatures.size(), actual.signatures.size(),
                head + "nombre de surcharges");
        assertEquals(ref.activeSignature, actual.activeSignature,
                head + "signature active");
        assertEquals(ref.activeParameter, actual.activeParameter,
                head + "paramètre actif");
        for (int i = 0; i < ref.signatures.size(); i++) {
            var e = ref.signatures.get(i);
            var a = actual.signatures.get(i);
            assertEquals(e.label, a.label, head + "label @" + i);
            assertEquals(e.parameterNames, a.parameterNames,
                    head + "noms de paramètres @" + i);
            assertEquals(e.documentation, a.documentation,
                    head + "documentation @" + i);
        }
    }
}
