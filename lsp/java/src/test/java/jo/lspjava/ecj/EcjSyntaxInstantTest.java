package jo.lspjava.ecj;

import jo.lspjava.api.CompletionItem;
import jo.lspjava.api.Diagnostic;
import jo.lspjava.api.Severity;
import jo.lspjava.engine.CompilationEnvironment;
import jo.lspjava.services.DiagnosticService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du lot h4 « LSP instantané » (v0.101.0) — le régime de repli
 * quand la plateforme manque (classpath vide AVANT la première sync,
 * image jrt absente : JDK du terminal non installé).
 *
 * <p>La pré-condition y est FORCÉE : un jdkHome pointant vers un dossier
 * temporaire SANS {@code lib/modules} (l'image jrt desktop est ainsi
 * neutralisée) et un classpath vide — {@code isLikelyUsable()} est
 * faux, exactement l'état de l'appareil entre le didOpen et la
 * première sync utile.</p>
 *
 * <p>LES deux garanties du lot : ① une source syntaxiquement cassée
 * rend SES erreurs de syntaxe (le silence d'avant h4 était le défaut) ;
 * ② une source saine mais pleine de types non résolus rend une liste
 * VIDE — le repli ne FABRIQUE jamais de « cannot be resolved ».</p>
 *
 * <p>Le marqueur de caret : la séquence {@code /*CAR* /} (sans espace)
 * dans la source témoin marque la position de complétion — le préfixe
 * inséré la remplace et le caret se place juste après lui.</p>
 *
 * @author jo@Dev
 */
class EcjSyntaxInstantTest {

    @TempDir
    Path dossier;

    /** Un moteur SANS plateforme : classpath vide, jdkHome sans jrt. */
    private EcjCompilerEngine engineSansPlateforme() {
        return new EcjCompilerEngine(
                Collections.<java.io.File>emptyList(),
                Collections.<java.io.File>emptyList(),
                "17",
                dossier);
    }

    private static final String SOURCE_SAINE_NON_RESOLUE = """
            package demo;

            public class Main {
                private int total = 0;

                public void run(String parametre) {
                    String message = null;
                    System.out.println(message + parametre + total);
                }
            }
            """;

    private static final String SOURCE_CASSEE = """
            package demo;

            public class Main {
                public void run() {
                    int x = 42
                    System.out.println(x;
                }
            }
            """;

    @Test
    void preconditionLeMoteurSansPlateformeEstInutilisable() {
        assertFalse(engineSansPlateforme().isLikelyUsable(),
                "classpath vide + jdkHome sans lib/modules : le régime plein "
                        + "doit être fermé (c'est le mode instantané)");
    }

    // ─── Garantie ① : la syntaxe cassée parle ───────────────────────

    @Test
    void laSyntaxeCasseeRendSesErreursSansPlateforme() {
        List<Diagnostic> diags =
                engineSansPlateforme().diagnoseSyntax("/demo/Main.java", SOURCE_CASSEE);
        assertTrue(diags.stream().anyMatch(d -> d.severity() == Severity.ERROR),
                "Le point-virgule manquant et la parenthèse non fermée "
                        + "doivent être signalés, reçu : " + diags);
        for (Diagnostic d : diags) {
            assertNotNull(d.range(), "un diagnostic de syntaxe a une plage");
            assertTrue(d.range().startOffset() >= 0);
            assertTrue(d.range().endOffset() > d.range().startOffset());
        }
    }

    // ─── Garantie ② : pas de faux positifs de résolution ────────────

    @Test
    void laSourceSaineNonResolueResteSilencieuse() {
        // String/System ne résolvent pas sans plateforme — le repli ne
        // doit PAS publier de « cannot be resolved » : liste VIDE.
        List<Diagnostic> diags =
                engineSansPlateforme().diagnoseSyntax("/demo/Main.java", SOURCE_SAINE_NON_RESOLUE);
        assertTrue(diags.isEmpty(),
                "Aucun faux positif attendu en mode instantané, reçu : " + diags);
    }

    @Test
    void diagnoseSyntaxTriParOffset() {
        List<Diagnostic> diags =
                engineSansPlateforme().diagnoseSyntax("/demo/Main.java", SOURCE_CASSEE);
        for (int i = 1; i < diags.size(); i++) {
            assertTrue(diags.get(i - 1).range().startOffset()
                            <= diags.get(i).range().startOffset(),
                    "les diagnostics de syntaxe sont triés par offset");
        }
    }

    // ─── DiagnosticService : le repli est BRANCHÉ ───────────────────

    @Test
    void leServiceRendLaSyntaxeQuandLaPlateformeManque() {
        CompilationEnvironment environment = new CompilationEnvironment();
        environment.setJdkHome(dossier);  // neutralise le jrt desktop
        DiagnosticService service = new DiagnosticService(environment);
        List<Diagnostic> diags = service.diagnose("/demo/Main.java", SOURCE_CASSEE);
        assertTrue(diags.stream().anyMatch(d -> d.severity() == Severity.ERROR),
                "DiagnosticService doit servir la syntaxe en repli (h4), "
                        + "reçu : " + diags);
    }

    @Test
    void leServiceResteSilencieuxSurDuSainNonResolu() {
        CompilationEnvironment environment = new CompilationEnvironment();
        environment.setJdkHome(dossier);
        DiagnosticService service = new DiagnosticService(environment);
        List<Diagnostic> diags =
                service.diagnose("/demo/Main.java", SOURCE_SAINE_NON_RESOLUE);
        assertTrue(diags.isEmpty(),
                "Le repli du service ne fabrique pas de « cannot be "
                        + "resolved », reçu : " + diags);
    }

    // ─── Complétion instantanée : symboles + mots-clés ──────────────

    private static final String SOURCE_COMPLETION = """
            package demo;

            public class Main {
                private int compteurTotal;

                public void premiere() {
                    int localePremiere = 1;
                }

                public void seconde(String parametre) {
                    int localeSeconde = 2;
                    MARQUEUR
                }
            }
            """.replace("MARQUEUR", "/*CAR*/");

    /** Caret juste après le préfixe inséré à la place du marqueur. */
    private static int caretApres(String sourceTemoins, String prefixeInsere) {
        int marqueur = sourceTemoins.indexOf("/*CAR*/");
        assertTrue(marqueur >= 0, "le marqueur de caret doit exister dans la source");
        return marqueur + prefixeInsere.length();
    }

    @Test
    void laCompletionInstantaneeProposeLeChampFiltreParPrefixe() {
        EcjCompilerEngine engine = engineSansPlateforme();
        EcjSyntaxCompletion completion = new EcjSyntaxCompletion(engine);
        String source = SOURCE_COMPLETION.replace("/*CAR*/", "com");
        List<CompletionItem> items = completion.complete(
                "/demo/Main.java", source, caretApres(SOURCE_COMPLETION, "com"));
        assertTrue(items.stream().anyMatch(i ->
                        i.label().equals("compteurTotal")
                                && i.kind() == CompletionItem.Kind.FIELD),
                "le champ du fichier doit être proposé, reçu : " + labels(items));
        assertTrue(items.stream().noneMatch(i -> i.label().equals("premiere")),
                "le préfixe filtre : « premiere » ne matche pas « com »");
    }

    @Test
    void laCompletionInstantaneeProposeLesLocalesDeLaMethodeEnglobante() {
        EcjCompilerEngine engine = engineSansPlateforme();
        EcjSyntaxCompletion completion = new EcjSyntaxCompletion(engine);
        // Caret après "localeS" dans seconde().
        String source = SOURCE_COMPLETION.replace("/*CAR*/", "localeS");
        List<CompletionItem> items = completion.complete(
                "/demo/Main.java", source, caretApres(SOURCE_COMPLETION, "localeS"));
        assertTrue(items.stream().anyMatch(i ->
                        i.label().equals("localeSeconde")
                                && i.kind() == CompletionItem.Kind.VARIABLE),
                "la locale de la méthode englobante doit être proposée, "
                        + "reçu : " + labels(items));
        assertFalse(items.stream().anyMatch(i -> i.label().equals("localePremiere")),
                "la locale de l'AUTRE méthode n'est pas proposée (portée "
                        + "de la méthode englobante)");
    }

    @Test
    void laCompletionInstantaneeProposeLesMotsClesDuLangage() {
        EcjCompilerEngine engine = engineSansPlateforme();
        EcjSyntaxCompletion completion = new EcjSyntaxCompletion(engine);
        String source = SOURCE_COMPLETION.replace("/*CAR*/", "wh");
        List<CompletionItem> items = completion.complete(
                "/demo/Main.java", source, caretApres(SOURCE_COMPLETION, "wh"));
        assertTrue(items.stream().anyMatch(i ->
                        i.label().equals("while")
                                && i.kind() == CompletionItem.Kind.KEYWORD),
                "les mots-clés du langage sont proposés en repli, "
                        + "reçu : " + labels(items));
    }

    @Test
    void laCompletionInstantaneeNeDevineAucunTypeDuJdk() {
        EcjCompilerEngine engine = engineSansPlateforme();
        EcjSyntaxCompletion completion = new EcjSyntaxCompletion(engine);
        String source = SOURCE_COMPLETION.replace("/*CAR*/", "Str");
        List<CompletionItem> items = completion.complete(
                "/demo/Main.java", source, caretApres(SOURCE_COMPLETION, "Str"));
        assertFalse(items.stream().anyMatch(i ->
                        i.label().equals("String") || i.label().equals("StringBuilder")),
                "zéro devinette : le repli ne propose PAS de types du JDK "
                        + "(le régime plein les apportera), reçu : " + labels(items));
    }

    @Test
    void laCompletionInstantaneeProposeTypesEtMethodesDuFichier() {
        EcjCompilerEngine engine = engineSansPlateforme();
        EcjSyntaxCompletion completion = new EcjSyntaxCompletion(engine);
        // Préfixe vide (caret à blanc) : tout le fichier + mots-clés.
        List<CompletionItem> items = completion.complete(
                "/demo/Main.java", SOURCE_COMPLETION, caretApres(SOURCE_COMPLETION, ""));
        assertTrue(items.stream().anyMatch(i ->
                        i.label().equals("Main") && i.kind() == CompletionItem.Kind.CLASS));
        assertTrue(items.stream().anyMatch(i ->
                        i.label().equals("seconde") && i.kind() == CompletionItem.Kind.METHOD));
        assertTrue(items.stream().anyMatch(i ->
                        i.label().equals("premiere") && i.kind() == CompletionItem.Kind.METHOD));
        assertTrue(items.stream().anyMatch(i ->
                        i.label().equals("compteurTotal") && i.kind() == CompletionItem.Kind.FIELD));
        // Préfixe vide : le PARAMÈTRE de la méthode englobante est proposé
        // (le marqueur vit dans seconde(String parametre)).
        assertTrue(items.stream().anyMatch(i ->
                        i.label().equals("parametre")
                                && i.kind() == CompletionItem.Kind.PARAMETER),
                "le paramètre de la méthode englobante est proposé, "
                        + "reçu : " + labels(items));
    }

    @Test
    void laCompletionInstantaneeNeRendJamaisNull() {
        EcjCompilerEngine engine = engineSansPlateforme();
        EcjSyntaxCompletion completion = new EcjSyntaxCompletion(engine);
        assertNotNull(completion.complete("/demo/Main.java", "", 0), "liste, jamais null");
        assertNotNull(completion.complete("/demo/Main.java", null, 0), "liste, jamais null");
    }

    /** Libellés compacts pour les messages d'assertion. */
    private static String labels(List<CompletionItem> items) {
        StringBuilder sb = new StringBuilder("[");
        for (CompletionItem item : items) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(item.label()).append(':').append(item.kind());
        }
        return sb.append(']').toString();
    }
}
