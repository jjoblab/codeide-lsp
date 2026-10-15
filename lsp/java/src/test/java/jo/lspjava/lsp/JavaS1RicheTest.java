package jo.lspjava.lsp;

import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du lot S1 « parité utile JDT.LS » (plan de stabilisation §3.1) :
 * <ul>
 *   <li><b>S1-b</b> — TAGS de diagnostics (Unnecessary sur les
 *       {@code IProblem.Unused*} : un import inutilisé voyage taggé —
 *       l'éditeur cel peint la plage estompée) ;</li>
 *   <li><b>S1-c</b> — {@code workspace/symbol} : recherche de types par
 *       préfixe sur les SOURCES du projet (localisation réelle, plage du
 *       nom déclarant) ; requête vide → liste vide (I9) ; moteur non
 *       initialisé → liste vide (jamais d'exception) ; capacité
 *       {@code workspaceSymbolProvider} déclarée ;</li>
 *   <li><b>S1-d</b> — registre des serveurs vivants : un serveur créé
 *       par la façade s'inscrit (S1-d côté SessionLsp) ; son
 *       {@code shutdown} prévient la façade via le rappel aLArret.</li>
 * </ul>
 *
 * <p>Les tests S1-c « moteur non prêt » tournent SANS classpath (même
 * philosophie que LifecycleHygieneTest) ; le cas PRÊT est couvert par
 * {@code applyWorkspaceModel} via la racine source réelle.</p>
 */
class JavaS1RicheTest {

    @TempDir
    Path dossier;

    /** Client espion — enregistre les publications de diagnostics. */
    private static final class ClientEspion implements LanguageClient {
        final List<PublishDiagnosticsParams> publications = new CopyOnWriteArrayList<>();

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            publications.add(params);
        }

        @Override
        public void telemetryEvent(Object object) {
            // ignoré
        }

        @Override
        public void showMessage(org.eclipse.lsp4j.MessageParams messageParams) {
            // ignoré
        }

        @Override
        public java.util.concurrent.CompletableFuture<org.eclipse.lsp4j.MessageActionItem>
                showMessageRequest(org.eclipse.lsp4j.ShowMessageRequestParams requestParams) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(org.eclipse.lsp4j.MessageParams message) {
            // ignoré
        }
    }

    /** Facade d'environnement avec UNE racine source (le dossier @TempDir). */
    private jo.lspjava.engine.CompilationEnvironment environnementAvecRacine() {
        return new jo.lspjava.engine.CompilationEnvironment(
                List.of(dossier.toFile()), List.of(), null);
    }

    // ── S1-b — tags de diagnostics ──────────────────────────────────────

    @Test
    void importInutiliseVoyageAvecTagUnnecessary() throws Exception {
        var server = new JavaLanguageServer(environnementAvecRacine());
        ClientEspion espion = new ClientEspion();
        server.connect(espion);
        server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
        server.initialized(new org.eclipse.lsp4j.InitializedParams());
        server.getTextDocumentService().didOpen(new org.eclipse.lsp4j.DidOpenTextDocumentParams(
                new TextDocumentItem(
                        dossier.resolve("T1.java").toUri().toString(), "java", 1,
                        "import java.util.ArrayList;\n\nclass T1 {\n}\n")));
        // La publication arrive asynchrone (debounce + scheduler) — polling.
        long limite = System.currentTimeMillis() + 15_000;
        while (espion.publications.isEmpty() && System.currentTimeMillis() < limite) {
            Thread.sleep(100);
        }
        assertTrue(!espion.publications.isEmpty(), "diagnostics publiés");
        var diags = espion.publications.get(0).getDiagnostics();
        var inutilise = diags.stream()
                .filter(d -> d.getTags() != null
                        && d.getTags().contains(org.eclipse.lsp4j.DiagnosticTag.Unnecessary))
                .findFirst();
        assertTrue(inutilise.isPresent(), "l'import inutilisé porte le tag Unnecessary");
        assertEquals("ecj:" + org.eclipse.jdt.core.compiler.IProblem.UnusedImport,
                inutilise.get().getCode().getLeft());
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }

    @Test
    void diagnosticSansCodeEcnConnuSansTag() throws Exception {
        var server = new JavaLanguageServer(environnementAvecRacine());
        ClientEspion espion = new ClientEspion();
        server.connect(espion);
        server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
        server.initialized(new org.eclipse.lsp4j.InitializedParams());
        server.getTextDocumentService().didOpen(new org.eclipse.lsp4j.DidOpenTextDocumentParams(
                new TextDocumentItem(
                        dossier.resolve("T2.java").toUri().toString(), "java", 1,
                        "class T2 {\n    int x = \"texte\";\n}\n")));
        long limite = System.currentTimeMillis() + 15_000;
        while (espion.publications.isEmpty() && System.currentTimeMillis() < limite) {
            Thread.sleep(100);
        }
        assertTrue(!espion.publications.isEmpty(), "diagnostics publiés");
        var typeMismatch = espion.publications.get(0).getDiagnostics().stream()
                .filter(d -> d.getTags() == null || d.getTags().isEmpty())
                .findFirst();
        assertTrue(typeMismatch.isPresent(), "aucun tag inventé sur un problème sans famille");
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }

    // ── S1-c — workspace/symbol ─────────────────────────────────────────

    @Test
    void capaciteWorkspaceSymbolDeclaree() {
        var caps = new ServerCapabilitiesProvider().createCapabilities();
        assertNotNull(caps.getWorkspaceSymbolProvider());
        assertEquals(Boolean.TRUE, caps.getWorkspaceSymbolProvider().getLeft());
    }

    @Test
    void requeteVideListeVideConventionI9() throws Exception {
        var server = new JavaLanguageServer(environnementAvecRacine());
        server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
        server.initialized(new org.eclipse.lsp4j.InitializedParams());
        var reponse = server.getWorkspaceService()
                .symbol(new WorkspaceSymbolParams("")).join();
        assertTrue(reponse.getLeft().isEmpty(), "requête vide → liste vide (I9)");
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }

    @Test
    void rechercheTypeSourceLocalisationReelleEtPlage() throws Exception {
        File source = dossier.resolve("com/ex/MaVue.java").toFile();
        Files.createDirectories(source.getParentFile().toPath());
        Files.write(source.toPath(), (
                "package com.ex;\n\n"
                        + "public class MaVue {\n"
                        + "}\n").getBytes(StandardCharsets.UTF_8));
        var server = new JavaLanguageServer(environnementAvecRacine());
        server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
        server.initialized(new org.eclipse.lsp4j.InitializedParams());
        // Force la construction de l'index par un tour de moteur.
        server.getWorkspaceService().didChangeWatchedFiles(
                new DidChangeWatchedFilesParams(List.of()));
        var reponse = server.getWorkspaceService()
                .symbol(new WorkspaceSymbolParams("MaVue")).join();
        List<? extends org.eclipse.lsp4j.SymbolInformation> symboles = reponse.getLeft();
        assertTrue(!symboles.isEmpty(), "le type des sources est trouvé");
        org.eclipse.lsp4j.SymbolInformation maVue = symboles.stream()
                .filter(s -> s.getName().equals("MaVue")).findFirst().orElseThrow();
        assertEquals("com.ex", maVue.getContainerName());
        // Path.toUri() (forme du serveur) — même fichier, forme file:/…
        assertEquals(source.toPath().toUri().toString(), maVue.getLocation().getUri());
        // Plage du nom déclarant : ligne 2 (0-based), colonne 13.
        assertEquals(2, maVue.getLocation().getRange().getStart().getLine());
        assertEquals(13, maVue.getLocation().getRange().getStart().getCharacter());
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }

    @Test
    void moteurNonPretListeVideSansException() throws Exception {
        var server = new JavaLanguageServer();
        server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
        server.initialized(new org.eclipse.lsp4j.InitializedParams());
        var reponse = server.getWorkspaceService()
                .symbol(new WorkspaceSymbolParams("Rien")).join();
        assertTrue(reponse.getLeft().isEmpty(), "moteur non prêt → liste vide (I9)");
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }

    // ── S1-d — registre vivants (rappel aLArret) ────────────────────────

    @Test
    void aLArretEstAppeleAuShutdownEtALexit() throws Exception {
        var compteur = new int[] {0};
        var server = new JavaLanguageServer(environnementAvecRacine(), () -> compteur[0]++);
        server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
        assertEquals(0, compteur[0], "pas de rappel avant l'arrêt");
        server.shutdown().get(5, TimeUnit.SECONDS);
        assertEquals(1, compteur[0], "shutdown prévient la façade");
        server.exit();
        assertEquals(2, compteur[0], "exit confirme (rappel idempotent côté façade)");
    }

    @Test
    void evenementsFichiersInvalidentLeCacheSansErreur() throws Exception {
        File source = dossier.resolve("com/ex/Vue.java").toFile();
        Files.createDirectories(source.getParentFile().toPath());
        Files.write(source.toPath(), "package com.ex;\nclass Vue {\n}\n"
                .getBytes(StandardCharsets.UTF_8));
        var server = new JavaLanguageServer(environnementAvecRacine());
        server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
        // Notification réelle (fichier existant) : aucune exception, cache invalide.
        server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(
                List.of(new FileEvent(source.toURI().toString(), FileChangeType.Changed))));
        // Fichier ABSENT : toléré (suppression externe), aucune exception.
        server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(
                List.of(new FileEvent(
                        dossier.resolve("com/ex/Absente.java").toUri().toString(),
                        FileChangeType.Deleted))));
        // Non .java : filtré, aucune exception.
        server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(
                List.of(new FileEvent(
                        dossier.resolve("notes.txt").toUri().toString(),
                        FileChangeType.Changed))));
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }
}
