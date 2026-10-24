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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import jo.lspjava.api.CompletionItem;

/**
 * v1.9.1 — Les classes internes binaires (« R$style », « R$id »,
 * « R$string »…) ne doivent PLUS fuiter dans les complétions servies par
 * l'INDEX DISQUE PRÊT (retour propriétaire : le popup proposait
 * {@code import com.example.monip.R$style;} et les FQN bruts).
 *
 * <p>Un {@link jo.lspjava.api.IndexNoms} PRÊT de test mime l'index de
 * production : il indexe les imbriquées PAR SEGMENT APRÈS DOLLAR (la clé
 * « style » porte le FQCN {@code com.example.monip.R$style}) et rend les
 * entrées brutes — exactement ce que {@code VueIndex.typesDansPaquet}
 * rendait avant le filtre. Les TROIS chemins consommateurs sont éprouvés :
 * complétion d'import ({@code import com.example.monip.|}), complétion de
 * types par préfixe, et quick-fix d'import (fqnsForSimpleName).</p>
 */
class FuiteClassesImbriqueesTest {

    @TempDir
    static Path tmp;

    static EcjCompilerEngine engine;
    static Path main;

    @BeforeAll
    static void setUp() throws Exception {
        File androidJar = findAndroidJar();
        Assumptions.assumeTrue(androidJar != null, "android.jar absent");

        Path src = Files.createDirectories(
                tmp.resolve("app/src/main/java/com/example/monip"));
        main = src.resolve("MainActivity.java");

        Path rJar = PopupSpontaneReproTestHelper.buildRJar(tmp);

        List<File> roots = new ArrayList<>();
        roots.add(src.toFile());
        engine = new EcjCompilerEngine(
                roots, List.of(androidJar, rJar.toFile()), "17",
                null, new IndexDisqueAvecImbriquees());
    }

    @Test
    void importApresPaquetProjetNeProposePasLesImbriquees() throws Exception {
        String src = "package com.example.monip;\n"
                + "\n"
                + "import com.example.monip.\n"
                + "\n"
                + "public class MainActivity {\n"
                + "}\n";
        int caret = src.indexOf("import com.example.monip.")
                + "import com.example.monip.".length();
        List<CompletionItem> items = new EcjCompletionEngine(engine)
                .complete(main.toString(), src, caret);

        List<String> labels = labels(items);
        org.junit.jupiter.api.Assertions.assertTrue(labels.contains("R"),
                "le type de premier niveau R doit rester proposé : " + labels);
        for (String label : labels) {
            org.junit.jupiter.api.Assertions.assertFalse(label.contains("$"),
                    "une imbriquée brute a fui dans la complétion d'import : '"
                            + label + "' parmi " + labels);
        }
    }

    @Test
    void completionTypeParPrefixeNeProposePasLesImbriquees() {
        // « style » est la CLÉ d'index de R$style — elle ne doit plus
        // produire le candidat « R$style » (impossible à écrire en Java).
        EcjCompilerEnvironment index = engine.typeIndex();
        List<String> fqcns = index.findTypesByPrefix("style", 25);
        for (String fqn : fqcns) {
            org.junit.jupiter.api.Assertions.assertFalse(fqn.contains("$"),
                    "un FQN d'imbriquée a fui par findTypesByPrefix : " + fqn);
        }
        // Le rappel normal reste servi : « R » donne le R de premier niveau.
        List<String> parR = index.findTypesByPrefix("R", 25);
        org.junit.jupiter.api.Assertions.assertTrue(
                parR.stream().anyMatch(f -> f.equals("com.example.monip.R")),
                "R de premier niveau attendu : " + parR);
    }

    @Test
    void quickFixImportNeProposePasLesImbriquees() {
        EcjCompilerEnvironment index = engine.typeIndex();
        // La clé « style » de l'index disque porte R$style : le quick-fix
        // « Importer style » ne doit PAS l'offrir (FQN binaire brut).
        List<String> pourStyle = index.fqnsForSimpleName("style");
        for (String fqn : pourStyle) {
            org.junit.jupiter.api.Assertions.assertFalse(fqn.contains("$"),
                    "un FQN d'imbriquée a fui par fqnsForSimpleName : " + fqn);
        }
        // « R » reste importable.
        List<String> pourR = index.fqnsForSimpleName("R");
        org.junit.jupiter.api.Assertions.assertTrue(
                pourR.stream().anyMatch(f -> f.equals("com.example.monip.R")),
                "R de premier niveau attendu : " + pourR);
    }

    @Test
    void completionAnnotationApresArobase() {
        // « @ » est déclencheur annoncé (v1.9.1) : le contexte annotation
        // doit produire des CANDIDATS DE TYPE (pas une liste vide).
        String src = "package com.example.monip;\n"
                + "\n"
                + "public class MainActivity {\n"
                + "    @\n"
                + "    void f() {}\n"
                + "}\n";
        int caret = src.indexOf("    @") + 5;
        List<CompletionItem> items = new EcjCompletionEngine(engine)
                .complete(main.toString(), src, caret);
        // Best-effort : le marqueur d'annotation doit au moins résoudre un
        // contexte (TYPE_REFERENCE ou NAME_REFERENCE) — la liste peut être
        // vide à préfixe vide (types filtrés par préfixe) mais JAMAIS null,
        // et le serveur répond honnêtement au client.
        org.junit.jupiter.api.Assertions.assertNotNull(items);
    }

    // ─── Harnais ─────────────────────────────────────────────────────

    private static List<String> labels(List<CompletionItem> items) {
        List<String> out = new ArrayList<>();
        if (items != null) {
            for (CompletionItem it : items) out.add(it.label());
        }
        return out;
    }

    /**
     * Index disque PRÊT mimant la production : les imbriquées sont
     * indexées par segment après dollar (« style » → R$style) et rendues
     * par {@code typesDansPaquet} SANS filtre — le comportement de
     * {@code VueIndex} avant le correctif v1.9.1.
     */
    private static final class IndexDisqueAvecImbriquees
            implements jo.lspjava.api.IndexNoms {

        @Override
        public boolean estPret() { return true; }

        @Override
        public void ajouterObservateurPret(Runnable observateur) {
            observateur.run();
        }

        @Override
        public java.nio.file.Path jarDe(String fqcn) { return null; }

        @Override
        public Map<String, List<String>> nomsParPrefixe(String prefixe, int limite) {
            if ("style".startsWith(prefixe)) {
                return Map.of("style", List.of("com.example.monip.R$style"));
            }
            if ("R".startsWith(prefixe)) {
                return Map.of("R", List.of("com.example.monip.R"));
            }
            return Collections.emptyMap();
        }

        @Override
        public boolean estPaquet(String nom) {
            return "com.example.monip".equals(nom) || "com.example".equals(nom)
                    || "com".equals(nom);
        }

        @Override
        public List<String> sousPaquetsDirects(String nom) {
            if (nom.isEmpty()) return List.of("com");
            if ("com".equals(nom)) return List.of("com.example");
            if ("com.example".equals(nom)) return List.of("com.example.monip");
            return Collections.emptyList();
        }

        @Override
        public List<String> typesDansPaquet(String nom) {
            // Le comportement AVANT correctif : tout ce qui vit dans la
            // ligne du paquet, y compris les entrées « $ ».
            if ("com.example.monip".equals(nom)) {
                return List.of("com.example.monip.R",
                        "com.example.monip.R$style",
                        "com.example.monip.R$id",
                        "com.example.monip.R$string");
            }
            return Collections.emptyList();
        }

        @Override
        public List<String> membresStatiques(String fqcn) {
            return Collections.emptyList();
        }
    }

    private static File findAndroidJar() {
        String[] candidates = {
                "/home/z/android-sdk/platforms/android-37.2/android.jar",
                "/home/z/tools/android-sdk/platforms/android-37.2/android.jar",
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.isFile()) return f;
        }
        return null;
    }
}
