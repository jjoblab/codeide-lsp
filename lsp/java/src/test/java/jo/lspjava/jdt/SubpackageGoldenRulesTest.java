package jo.lspjava.jdt;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jo.lspjava.api.CompletionItem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LES SIX RÈGLES D'OR du filtre subpackage (portage de
 * {@code CandidateCollector.packageChildren} du projet de référence).
 *
 * <p>Chaque règle est vérifiée sur le contexte exact du bug device
 * ({@code import android.}) :</p>
 * <ol>
 *   <li>Enfants DIRECTS uniquement — premier segment après {@code q.} :
 *       {@code android.} propose {@code content}, {@code os}, {@code view},
 *       {@code widget}… mais JAMAIS {@code content.pm} ni {@code os.storage} ;</li>
 *   <li>Dédoublonnage par segment — une seule entrée {@code content} quand
 *       des dizaines de classes vivent dans {@code android.content.*} ;</li>
 *   <li>Préfixe de requête AVEC le point final — un préfixe VIDE
 *       ({@code import android.|}) liste quand même les sous-paquets ;</li>
 *   <li>Types DIRECTEMENT dans {@code q} — R ET Manifest, ni plus ni moins,
 *       SANS auto-import (portage exactAll(PACKAGE_TYPES, q)) — seuls les
 *       types directement posés dans {@code android} doivent remonter, pas
 *       les milliers de classes sous {@code android.**} ;</li>
 *   <li>Insertion des packages = le SEGMENT simple (le texte avant le caret
 *       contient déjà {@code android.}) ;</li>
 *   <li>(Exclusions module JVM — N/A : CodeIDE est exclusivement Android.)</li>
 * </ol>
 *
 * @author jo@Dev
 */
class SubpackageGoldenRulesTest {

    @TempDir
    static Path tmp;

    static JdtCompilerEngine engine;
    static Path src;

    @BeforeAll
    static void setUp() throws Exception {
        File androidJar = findAndroidJar();
        Assumptions.assumeTrue(androidJar != null, "android.jar absent");
        src = Files.createDirectories(tmp.resolve("src/com/example/e"));
        List<File> roots = new ArrayList<>();
        roots.add(src.toFile());
        engine = new JdtCompilerEngine(roots, List.of(androidJar), "17");
    }

    private List<CompletionItem> complete(String head, String typed) {
        String source = "package com.example.e;\n\n" + head + typed
                + "\n\npublic class Main {\n}\n";
        int caret = ("package com.example.e;\n\n" + head).length() + typed.length();
        return new JdtCompletionEngine(engine).complete(
                "/src/com/example/e/Main.java", source, caret);
    }

    private static List<String> labels(List<CompletionItem> items) {
        List<String> out = new ArrayList<>();
        for (CompletionItem it : items) out.add(it.label());
        return out;
    }

    private static List<String> packageLabels(List<CompletionItem> items) {
        List<String> out = new ArrayList<>();
        for (CompletionItem it : items) {
            if (it.kind() == CompletionItem.Kind.PACKAGE) out.add(it.label());
        }
        return out;
    }

    private static List<String> classLabels(List<CompletionItem> items) {
        List<String> out = new ArrayList<>();
        for (CompletionItem it : items) {
            if (it.kind() == CompletionItem.Kind.CLASS) out.add(it.label());
        }
        return out;
    }

    // ═══ Règles 1+4 : import android. — « ni plus ni moins » ═══

    @Test
    void rule1_directChildrenOnly_neverDeepPackages() {
        List<CompletionItem> items = complete("import android.", "");
        assertNotNull(items);
        for (String pkg : packageLabels(items)) {
            assertFalse(pkg.contains("."),
                    "un sous-paquet proposé doit être un SEGMENT simple, pas un chemin"
                            + " profond : '" + pkg + "'");
        }
        // Les packages connus de SECOND niveau ne doivent PAS apparaître
        // comme items (ils ne sont accessibles qu'après avoir choisi le
        // segment parent) :
        List<String> pkgs = packageLabels(items);
        assertFalse(pkgs.contains("content.pm"), "content.pm est un petit-fils, pas un enfant direct");
        assertFalse(pkgs.contains("os.storage"), "os.storage est un petit-fils");
        // Règle 1 : les enfants directs connus Y SONT :
        assertTrue(pkgs.contains("content"), "android.content attendu : " + pkgs);
        assertTrue(pkgs.contains("os"), "android.os attendu : " + pkgs);
        assertTrue(pkgs.contains("view"), "android.view attendu : " + pkgs);
        assertTrue(pkgs.contains("widget"), "android.widget attendu : " + pkgs);
    }

    @Test
    void rule4_typesDirectlyInPackage_manifestAndR_deterministic() {
        // 20 appels consécutifs : le résultat doit être DÉTERMINISTE (Manifest
        // et R doivent apparaître à CHAQUE round).
        for (int round = 0; round < 20; round++) {
            List<CompletionItem> items = complete("import android.", "");
            assertNotNull(items, "round " + round);
            List<String> classes = classLabels(items);
            assertTrue(classes.contains("Manifest"),
                    "android.Manifest (type DIRECT) attendu à chaque round : " + classes);
            assertTrue(classes.contains("R"),
                    "android.R (type DIRECT) attendu à chaque round : " + classes);
            // « NI PLUS » : les types des SOUS-PAQUETS ne remontent pas :
            assertFalse(classes.contains("Bundle"),
                    "android.os.Bundle vit dans un sous-paquet — pas un type direct");
            assertFalse(classes.contains("Activity"),
                    "android.app.Activity vit dans un sous-paquet");
            // « NI MOINS » : exactement les 2 types directs d'android.jar :
            assertEquals(Set.of("Manifest", "R"), new HashSet<>(classes),
                    "android. ne doit proposer QUE les types DIRECTEMENT dans android : " + classes);
        }
    }

    @Test
    void rule4_typesDirect_neverAutoImport() {
        List<CompletionItem> items = complete("import android.", "");
        assertNotNull(items);
        for (CompletionItem it : items) {
            assertEquals(null, it.autoImportFqn(),
                    "un type proposé depuis un chemin de paquet déjà tapé ne"
                            + " doit JAMAIS porter d'auto-import : " + it.label());
        }
    }

    // ═══ Règle 2 : dédoublonnage par segment ═══

    @Test
    void rule2_dedupBySegment() {
        List<CompletionItem> items = complete("import android.", "");
        assertNotNull(items);
        List<String> pkgs = packageLabels(items);
        Set<String> uniq = new HashSet<>(pkgs);
        assertEquals(pkgs.size(), uniq.size(),
                "chaque segment de sous-paquet doit apparaître UNE seule fois : " + pkgs);
        // des dizaines de classes vivent dans android.content.* → UNE entrée
        assertEquals(1, pkgs.stream().filter("content"::equals).count(),
                "content doit apparaître exactement une fois");
    }

    // ═══ Règle 3 : préfixe vide liste quand même les sous-paquets ═══

    @Test
    void rule3_emptyPrefixStillListsSubPackages() {
        List<CompletionItem> items = complete("import android.", "");
        assertNotNull(items);
        assertTrue(packageLabels(items).size() >= 40,
                "un préfixe VIDE doit déclencher l'avalanche des sous-paquets"
                        + " (47 sous android dans android.jar) : " + items.size());
    }

    @Test
    void rule3_prefixFiltersSegments() {
        // import android.c| → uniquement les segments directs matchant « c »
        List<CompletionItem> items = complete("import android.c", "");
        assertNotNull(items);
        // La liste exacte dépend de l'android.jar utilisé (crypto a été
        // ajouté dans un build récent). On vérifie l'INCLUSION des
        // sous-paquets attendus + que tous les items démarrent par 'c'
        // (filtrage par préfixe), plutôt qu'une égalité stricte.
        java.util.Set<String> labels = new HashSet<>(packageLabels(items));
        assertTrue(labels.contains("companion"),
                "companion doit matcher 'c' : " + labels);
        assertTrue(labels.contains("content"),
                "content doit matcher 'c' : " + labels);
        assertTrue(labels.contains("credentials"),
                "credentials doit matcher 'c' : " + labels);
        for (String label : labels) {
            assertTrue(label.startsWith("c") || label.startsWith("C"),
                    "tous les items doivent démarrer par 'c' ou 'C' : " + labels
                            + " — trouvé « " + label + " »");
        }
        // les types directs filtrés par préfixe aussi :
        List<CompletionItem> itemsM = complete("import android.M", "");
        assertTrue(classLabels(itemsM).contains("Manifest"),
                "Manifest doit matcher le préfixe 'M' : " + labels(itemsM));
        assertFalse(classLabels(itemsM).contains("R"),
                "R ne matche pas 'M' : " + classLabels(itemsM));
    }

    // ═══ Règle 5 : insertion du SEGMENT simple ═══

    @Test
    void rule5_insertSimpleSegment() {
        List<CompletionItem> items = complete("import android.", "");
        assertNotNull(items);
        for (CompletionItem it : items) {
            assertFalse(it.insertText().contains("."),
                    "l'insertion doit être le SEGMENT simple (le texte avant le"
                            + " caret contient déjà 'android.') : " + it.insertText());
        }
        for (CompletionItem it : items) {
            if (it.kind() == CompletionItem.Kind.PACKAGE) {
                assertTrue(it.detail().startsWith("android."),
                        "le détail d'un package porte le FQN complet : " + it.detail());
            }
        }
    }

    // ═══ Parité projet de référence : matching gradué sur les segments ═══

    @Test
    void graduatedMatching_onSegments() {
        // camel-hump : « cV » → contentView n'existe pas, mais le hump doit
        // marcher via prefix-ci au minimum sur 'content'.
        List<CompletionItem> items = complete("import android.Con", "");
        assertTrue(packageLabels(items).contains("content"),
                "prefix-ci : 'Con' doit matcher 'content' : " + packageLabels(items));
    }

    // ═══ Contexte PACKAGE_REFERENCE (hors import) — mêmes règles ═══

    @Test
    void packageReferenceOutsideImport_sameGoldenRules() {
        String src = "package com.example.e;\n\n"
                + "public class Main {\n"
                + "    void f() {\n"
                + "        Object o = android.widget.\n"
                + "    }\n"
                + "}\n";
        int caret = src.indexOf("android.widget.") + "android.widget.".length();
        List<CompletionItem> items = new JdtCompletionEngine(engine).complete(
                "/src/com/example/e/Main.java", src, caret);
        assertNotNull(items, "PACKAGE_REFERENCE doit rendre des items");
        List<String> labels = labels(items);
        // types directs d'android.widget (TextView, Button…) + pas de deep
        assertTrue(labels.contains("TextView"),
                "android.widget.TextView attendu : " + labels);
        assertTrue(labels.contains("Button"),
                "android.widget.Button attendu : " + labels);
        assertFalse(labels.contains("content.pm"),
                "pas de deep package hors import non plus");
        for (CompletionItem it : items) {
            assertFalse(it.insertText().contains("."),
                    "insertion du segment simple hors import aussi : " + it.insertText());
        }
    }

    private static File findAndroidJar() {
        String[] candidates = {
                "/home/z/tools/android-sdk/platforms/android-34/android.jar",
                "/home/z/my-project/opt/android-sdk/platforms/android-34/android.jar",
                "/home/z/my-project/android-sdk/platforms/android-34/android.jar",
                "/home/z/my-project/android-sdk/platforms/android-35/android.jar",
                System.getenv("ANDROID_HOME") == null ? null
                        : System.getenv("ANDROID_HOME")
                                + "/platforms/android-34/android.jar",
        };
        for (String c : candidates) {
            if (c == null) continue;
            File f = new File(c);
            if (f.isFile()) return f;
        }
        return null;
    }
}
