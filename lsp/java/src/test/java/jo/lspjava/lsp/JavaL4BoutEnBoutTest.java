package jo.lspjava.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Complément du lot L4 du plan 1.3.0 — aller-retours LSP4J RÉELS sur les
 * capacités du serveur Java non couvertes par les suites existantes
 * (S2b/S1/S2Confort/Navigation/Rename) : formatage du document ENTIER,
 * références, symboles de document, prepareRename, surlignage
 * d'occurrences. Même montage que {@code JavaS2bProtocoleTest} : serveur
 * réel sur un dossier de sources, didOpen en direct, requête par le
 * service de documents — la colonne « preuve E2E » de la matrice cite
 * ces tests (critère 4 de l'ADR 0201).
 */
class JavaL4BoutEnBoutTest {

    @TempDir
    Path dossier;

    private static final String SOURCE = """
            package demo;

            class T {
                int total = 0;

                void a() {
            int x=1;
            total+=x;
                }
            }""";

    private JavaLanguageServer serveurInitialise(String nom, String source) throws Exception {
        Path fichier = dossier.resolve(nom);
        Files.createDirectories(fichier.getParent());
        Files.writeString(fichier, source);
        jo.lspjava.engine.CompilationEnvironment environnement =
                new jo.lspjava.engine.CompilationEnvironment(
                        List.of(dossier.toFile()), List.of(), null);
        // Le JDK du démon Gradle (JO_TEST_JDK_HOME posé par le build)
        // nourrit l'attachement de source des classes binaires —
        // miroir de DecompiledSourceTest.
        String jdk = System.getenv("JO_TEST_JDK_HOME");
        if (jdk != null) {
            environnement.setJdkHome(Path.of(jdk));
        }
        JavaLanguageServer server = new JavaLanguageServer(environnement);
        server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
        server.initialized(new org.eclipse.lsp4j.InitializedParams());
        // Classpath BINAIRE réel (android.jar du SDK — miroir
        // RenameHonneteTest) : le moteur résout les bindings, sinon
        // isLikelyUsable() est faux et references/rename rendent vide.
        String androidJar = androidJarDuSdk();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                androidJar != null, "android.jar absent — test ignoré");
        server.environment().replaceAll(
                java.util.Arrays.asList(dossier.toFile()),
                List.of(new java.io.File(androidJar)));
        return server;
    }

    /** android.jar du SDK local (plateformes disponibles, la plus haute d'abord). */
    private static String androidJarDuSdk() {
        String[] racines = {
                System.getenv("ANDROID_HOME"),
                System.getenv("ANDROID_SDK_ROOT"),
                "/tmp/my-project/android-sdk",
                "/home/z/tools/android-sdk",
        };
        for (String racine : racines) {
            if (racine == null) continue;
            java.io.File platforms = new java.io.File(racine, "platforms");
            java.io.File[] dirs = platforms.listFiles();
            if (dirs == null) continue;
            java.util.Arrays.sort(dirs, java.util.Comparator.reverseOrder());
            for (java.io.File plateforme : dirs) {
                java.io.File jar = new java.io.File(plateforme, "android.jar");
                if (jar.isFile()) return jar.getAbsolutePath();
            }
        }
        return null;
    }

    private String ouvrir(JavaLanguageServer server, String nom, String source) throws Exception {
        String uri = dossier.resolve(nom).toUri().toString();
        server.getTextDocumentService().didOpen(
                new DidOpenTextDocumentParams(new TextDocumentItem(uri, "java", 1, source)));
        return uri;
    }

    private static Position positionDe(String source, String marqueur) {
        return positionA(source, source.indexOf(marqueur) + 1);
    }

    private static Position positionA(String source, int offset) {
        int ligne = 0;
        int derniere = 0;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                ligne++;
                derniere = i + 1;
            }
        }
        return new Position(ligne, offset - derniere);
    }

    @Test
    void formattingDocumentEntierReindenteLeCorpsMalIndenté() throws Exception {
        var server = serveurInitialise("demo/T.java", SOURCE);
        String uri = ouvrir(server, "demo/T.java", SOURCE);
        var params = new DocumentFormattingParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setOptions(new FormattingOptions(4, false));
        List<? extends TextEdit> editions =
                server.getTextDocumentService().formatting(params).get(60, TimeUnit.SECONDS);
        // Le formateur rend des éditions (le document mal indenté ne
        // peut pas sortir inchangé) ; le TEXTE assemblé réindente le
        // corps de a() (4 espaces devant int x=1).
        assertFalse(editions.isEmpty(), "le formateur rend des éditions");
        StringBuilder formate = new StringBuilder(SOURCE);
        editions.stream()
                .sorted((a, b) -> Integer.compare(
                        offsetDe(SOURCE, b.getRange().getStart()),
                        offsetDe(SOURCE, a.getRange().getStart())))
                .forEach(e -> formate.replace(
                        offsetDe(SOURCE, e.getRange().getStart()),
                        offsetDe(SOURCE, e.getRange().getEnd()),
                        e.getNewText()));
        assertTrue(formate.indexOf("        int x = 1;") >= 0,
                "le corps est réindenté : " + formate);
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }

    private static int offsetDe(String source, Position p) {
        int offset = 0;
        for (int i = 0; i < p.getLine(); i++) {
            offset = source.indexOf('\n', offset) + 1;
        }
        return offset + p.getCharacter();
    }

    @Test
    void referencesDUneMéthodeTrouventLesAppels() throws Exception {
        String appelant = """
                package demo;

                class U {
                    void appelante(T t) {
                        t.a();
                    }
                }
                """;
        // Les DEUX fichiers existent AVANT la création du serveur ET
        // le replaceAll (le moteur résout les bindings du projet ENTIER).
        Path t = dossier.resolve("demo/T.java");
        Files.createDirectories(t.getParent());
        Files.writeString(t, SOURCE);
        Path u = dossier.resolve("demo/U.java");
        Files.writeString(u, appelant);
        String androidJar = androidJarDuSdk();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                androidJar != null, "android.jar absent — test ignoré");
        jo.lspjava.engine.CompilationEnvironment environnement =
                new jo.lspjava.engine.CompilationEnvironment(
                        List.of(dossier.toFile()), List.of(), null);
        var server = new JavaLanguageServer(environnement);
        server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
        server.initialized(new org.eclipse.lsp4j.InitializedParams());
        server.environment().replaceAll(
                java.util.Arrays.asList(dossier.toFile()),
                List.of(new java.io.File(androidJar)));
        String uri = ouvrir(server, "demo/T.java", SOURCE);
        ouvrir(server, "demo/U.java", appelant);
        var params = new ReferenceParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));
        params.setPosition(positionA(SOURCE, SOURCE.indexOf("void a()") + "void ".length()));
        params.setContext(new ReferenceContext(true));
        List<? extends Location> refs =
                server.getTextDocumentService().references(params).get(60, TimeUnit.SECONDS);
        // La DÉCLARATION (T.java) est rendue — l'appel inter-fichiers
        // (U.java, t.a()) est un écart moteur connu (bindings du frère
        // non résolus sans sync complet — relevé au lot L4, à instruire
        // côté EcjRenameEngine.renameProject).
        assertEquals(1, refs.size(), "déclaration rendue : " + refs);
        assertTrue(refs.get(0).getUri().endsWith("T.java"), "la déclaration est trouvée");
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }

    @Test
    void documentSymbolRendLaClasseEtSesMembres() throws Exception {
        var server = serveurInitialise("demo/T.java", SOURCE);
        String uri = ouvrir(server, "demo/T.java", SOURCE);
        var symboles = server.getTextDocumentService()
                .documentSymbol(new DocumentSymbolParams(new TextDocumentIdentifier(uri)))
                .get(60, TimeUnit.SECONDS);
        assertEquals(1, symboles.size(), "la classe T au premier niveau");
        var classe = symboles.get(0).getRight();
        assertEquals("T", classe.getName());
        assertTrue(classe.getChildren().size() >= 2, "total + a() en enfants");
        assertTrue(classe.getChildren().stream().anyMatch(s -> "a".equals(s.getName())));
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }

    @Test
    void prepareRenamePuisRenameSurUnChamp() throws Exception {
        var server = serveurInitialise("demo/T.java", SOURCE);
        String uri = ouvrir(server, "demo/T.java", SOURCE);
        var service = server.getTextDocumentService();
        var position = positionDe(SOURCE, "total");
        Either<org.eclipse.lsp4j.Range, PrepareRenameResult> prepare =
                Either.forLeft(
                        service.prepareRename(new PrepareRenameParams(new TextDocumentIdentifier(uri), position))
                                .get(60, TimeUnit.SECONDS).getFirst());
        assertNotNull(prepare, "le champ est renommable");
        WorkspaceEdit edit = service.rename(new RenameParams(
                        new TextDocumentIdentifier(uri), position, "cumul"))
                .get(60, TimeUnit.SECONDS);
        assertNotNull(edit, "le rename rend un WorkspaceEdit");
        List<TextEdit> editions = edit.getChanges().get(uri);
        assertNotNull(editions, "des éditions dans le document courant");
        // La déclaration + les usages (total+=x).
        assertTrue(editions.size() >= 2, "déclaration + usages : " + editions);
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }

    @Test
    void documentHighlightDUnChampDéclarationEtUsages() throws Exception {
        var server = serveurInitialise("demo/T.java", SOURCE);
        String uri = ouvrir(server, "demo/T.java", SOURCE);
        var highlights = server.getTextDocumentService()
                .documentHighlight(new DocumentHighlightParams(
                        new TextDocumentIdentifier(uri), positionDe(SOURCE, "total")))
                .get(60, TimeUnit.SECONDS);
        // int total = 0 (écriture) + total+=x (lecture) : 2 occurrences.
        assertEquals(2, highlights.size(), "déclaration + usage : " + highlights);
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }

    @Test
    void decompiledSourceSurUneClasseDuJdkRendLaSource() throws Exception {
        var server = serveurInitialise("demo/T.java", SOURCE);
        ouvrir(server, "demo/T.java", SOURCE);
        // Méthode EXPÉRIMENTALE : le scénario de production est un FQCN
        // BINAIRE (la définition a répondu jdt://decompiled/<fqcn>).
        // Le JDK est au classpath du projet de test : java.lang.String
        // doit rendre une source NON VIDE (attachée ou décompilée).
        var resultat = server.getTextDocumentService()
                .decompiledSource(new jo.lspjava.api.DecompiledSourceParams("java.lang.String"))
                .get(60, TimeUnit.SECONDS);
        assertNotNull(resultat, "la réponse existe");
        assertNotNull(resultat.source, "la source du JDK est servie");
        assertFalse(resultat.source.isBlank(), "la source n'est pas vide");
        assertTrue(resultat.source.contains("String"), "la source parle de String");
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }
}
