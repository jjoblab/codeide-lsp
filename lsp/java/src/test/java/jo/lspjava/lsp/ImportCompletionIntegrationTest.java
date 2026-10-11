package jo.lspjava.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test d'intégration end-to-end de la complétion « import android. »
 * (repro user : « je tape par exemple "import " je devrais avoir des
 * suggestions de package… arrivé là "import android." ça me propose des
 * mots clés alors que cela aurait dû être la continuité du package par
 * exemple "import android.os." »).
 *
 * <p><b>Contexte</b> : le client doit envoyer le {@code didChange} en
 * attente AVANT la requête completion — {@code LspEditor.flushPendingChange()}
 * est appelé avant la requête dans {@code LspCompletionProvider.complete()},
 * ce qui envoie SYNCHRONEMENT le didChange en attente (la notification est
 * enfilée dans la même file LSP4J FIFO AVANT la requête completion, donc
 * le serveur traite didChange PUIS completion et voit le texte à jour).
 * Sinon, son {@code openDocuments} est STALE, la détection du contexte
 * {@code IMPORT_REFERENCE} échoue, le repli sur {@code NAME_REFERENCE}
 * fait retourner des MOTS-CLÉS à la place des sous-paquets.</p>
 *
 * <p><b>Ce test</b> vérifie que la COMPOSITION DID_CHANGE → COMPLETION
 * fonctionne correctement bout-en-bout via {@link JavaLanguageServer}
 * in-process : le serveur doit, après réception d'un {@code didChange}
 * synchrone suivi d'une requête {@code completion}, détecter le contexte
 * {@code IMPORT_REFERENCE} (et non {@code NAME_REFERENCE}) et retourner
 * les SOUS-PAQUETS directs (os, content, view…) + les TYPES DIRECTS
 * (Manifest, R) — pas des mots-clés.</p>
 *
 * <p>Ce test valide que la chaîne LSP4J + JavaLanguageServer +
 * JdtCompletionEngine traite correctement la séquence didChange →
 * completion. Si le client envoie dans cet ordre, le serveur répond
 * correctement.</p>
 *
 * @author jo@Dev
 */
class ImportCompletionIntegrationTest {

    @TempDir
    static Path tmp;

    static File androidJar;
    static Path srcRoot;

    JavaLanguageServer server;

    @BeforeAll
    static void setUpClass() throws Exception {
        androidJar = findAndroidJar();
        Assumptions.assumeTrue(androidJar != null,
                "android.jar absent — test skip");
        srcRoot = Files.createDirectories(
                tmp.resolve("app/src/main/java/com/example/e"));
    }

    @BeforeEach
    void setUp() {
        // CompilationEnvironment avec android.jar SEUL — suffisant pour
        // les sous-paquets android.*, java.* et les mots-clés. Aucun cache
        // Gradle requis.
        jo.lspjava.engine.CompilationEnvironment env =
                new jo.lspjava.engine.CompilationEnvironment(
                        List.of(srcRoot.toFile()),
                        List.of(androidJar),
                        "17");
        server = new JavaLanguageServer(env);

        // Initialize — passe la machine à états à INITIALIZED.
        InitializeParams params = new InitializeParams();
        params.setRootUri(srcRoot.toUri().toString());
        params.setProcessId(0);
        // workspaceFolders — le serveur les utilise pour résoudre les
        // sources du projet (utile pour la résolution des types du
        // paquet courant).
        java.util.List<WorkspaceFolder> folders = new ArrayList<>();
        folders.add(new WorkspaceFolder(srcRoot.toUri().toString()));
        params.setWorkspaceFolders(folders);
        server.initialize(params).join();
        server.initialized(new InitializedParams());
    }

    @AfterEach
    void tearDown() {
        try {
            server.shutdown().join();
            server.exit();
        } catch (Throwable ignored) {
            // Nettoyage best-effort.
        }
    }

    /**
     * Scénario EXACT du repro user : didOpen avec un Main SANS import,
     * puis didChange synchrone AJOUTANT « import android. » (simulant le
     * flush du fix), puis completion à la position juste après le point.
     * Le serveur doit retourner les sous-paquets (os, content, view…)
     * ET les types directs (Manifest, R) — PAS des mots-clés.
     */
    @Test
    void didChange_then_completion_returns_subpackages_not_keywords() throws Exception {
        String uri = srcRoot.resolve("MainActivity.java").toUri().toString();

        // 1. didOpen avec un Main VIDE (pas d'instruction import).
        String initialText = "package com.example.e;\n\n"
                + "public class MainActivity {\n"
                + "}\n";
        DidOpenTextDocumentParams openParams = new DidOpenTextDocumentParams();
        openParams.setTextDocument(new TextDocumentItem(
                uri, "java", 1, initialText));
        server.getTextDocumentService().didOpen(openParams);

        // 2. didChange SYNCHRONE ajoutant « import android. » —
        // simule EXACTEMENT ce que fait LspEditor.flushPendingChange()
        // avant d'envoyer la requête completion.
        String newText = "package com.example.e;\n\n"
                + "import android.\n"
                + "\n"
                + "public class MainActivity {\n"
                + "}\n";
        DidChangeTextDocumentParams changeParams = new DidChangeTextDocumentParams();
        VersionedTextDocumentIdentifier id = new VersionedTextDocumentIdentifier();
        id.setUri(uri);
        id.setVersion(2);
        changeParams.setTextDocument(id);
        // Full sync (range=null) — pas de risque de calcul de diff.
        TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent();
        change.setText(newText);
        changeParams.setContentChanges(List.of(change));
        server.getTextDocumentService().didChange(changeParams);

        // 3. Requête completion à la position juste après « import android. ».
        // Position = (line=2, character=len("import android.")) = (2, 15).
        int caretLine = 2;
        int caretChar = "import android.".length();
        CompletionParams cp = new CompletionParams();
        cp.setTextDocument(new TextDocumentIdentifier(uri));
        cp.setPosition(new Position(caretLine, caretChar));
        org.eclipse.lsp4j.CompletionContext ctx =
                new org.eclipse.lsp4j.CompletionContext();
        ctx.setTriggerKind(CompletionTriggerKind.Invoked);
        cp.setContext(ctx);

        Either<List<CompletionItem>, CompletionList> result =
                server.getTextDocumentService().completion(cp)
                        .get(30, TimeUnit.SECONDS);

        List<CompletionItem> items = result.isLeft()
                ? result.getLeft()
                : (result.getRight() != null ? result.getRight().getItems() : null);
        assertNotNull(items, "completion doit rendre une liste non-null");
        assertFalse(items.isEmpty(),
                "completion doit rendre AU MOINS UN item pour « import android. »");

        // 4. Vérifier que les items sont bien des sous-paquets / types
        // DIRECTS d'android — ET PAS des mots-clés Java (public, class,
        // static, void, int, etc.).
        List<String> labels = items.stream().map(CompletionItem::getLabel).toList();
        System.out.println("[ImportCompletionIntegrationTest] items pour « import android. » : "
                + labels.size() + " → " + labels);

        // Quelques sous-paquets directs d'android présents dans android.jar.
        assertTrue(labels.contains("os"),
                "le sous-paquet direct « os » doit être proposé : " + labels);
        assertTrue(labels.contains("content"),
                "le sous-paquet direct « content » doit être proposé : " + labels);
        assertTrue(labels.contains("widget"),
                "le sous-paquet direct « widget » doit être proposé : " + labels);

        // Les types DIRECTS d'android (Manifest, R) — règle 4 des golden
        // rules : ils doivent apparaître.
        assertTrue(labels.contains("Manifest"),
                "le type direct « android.Manifest » doit être proposé : " + labels);
        assertTrue(labels.contains("R"),
                "le type direct « android.R » doit être proposé : " + labels);

        // Signature du BUG : la liste ne doit PAS contenir de
        // mots-clés Java. Si la détection de contexte retombe sur
        // NAME_REFERENCE (parce que le serveur n'a pas vu le didChange),
        // le moteur ajoute les mots-clés filtrés par préfixe (« package »,
        // « public », « class », « interface »…). Aucun d'entre eux ne
        // doit apparaître dans la réponse IMPORT_REFERENCE.
        for (String kw : new String[]{
                "package", "public", "class", "interface", "void", "static",
                "import", "final", "private", "protected", "extends", "implements",
                "new", "return", "if", "else", "for", "while", "try", "catch"}) {
            assertFalse(labels.contains(kw),
                    "le mot-clé « " + kw + " » NE DOIT PAS figurer dans la "
                            + "complétion IMPORT_REFERENCE (sinon le serveur a "
                            + "raté le contexte import et retombe sur "
                            + "NAME_REFERENCE → c'est le bug user) : " + labels);
        }

        // 5. Tous les items doivent être de kind MODULE (= Package en
        // LSP4J 0.22+, le serveur mappe PACKAGE → Module) ou CLASS (7),
        // JAMAIS KEYWORD (14).
        for (CompletionItem it : items) {
            if (it.getKind() != null) {
                int kindCode = it.getKind().getValue();
                assertTrue(kindCode == CompletionItemKind.Module.getValue()
                                || kindCode == CompletionItemKind.Class.getValue()
                                || kindCode == CompletionItemKind.Interface.getValue()
                                || kindCode == CompletionItemKind.Enum.getValue()
                                || kindCode == CompletionItemKind.Struct.getValue(),
                        "l'item « " + it.getLabel() + " » a un kind inattendu "
                                + "(attendu Module/Class/Interface/Enum/Struct, "
                                + "pas Keyword) : kind=" + kindCode);
            }
        }
    }

    /**
     * Variante : didOpen SANS didChange préalable → completion DOIT
     * retourner des sous-paquets (le contexte IMPORT_REFERENCE est
     * détecté sur le texte du didOpen).
     */
    @Test
    void didOpen_with_import_returns_subpackages() throws Exception {
        String uri = srcRoot.resolve("Main2.java").toUri().toString();
        String text = "package com.example.e;\n\n"
                + "import android.\n"
                + "\n"
                + "public class Main2 {\n"
                + "}\n";
        DidOpenTextDocumentParams openParams = new DidOpenTextDocumentParams();
        openParams.setTextDocument(new TextDocumentItem(
                uri, "java", 1, text));
        server.getTextDocumentService().didOpen(openParams);

        // Position juste après « import android. ».
        CompletionParams cp = new CompletionParams();
        cp.setTextDocument(new TextDocumentIdentifier(uri));
        cp.setPosition(new Position(2, "import android.".length()));
        org.eclipse.lsp4j.CompletionContext ctx =
                new org.eclipse.lsp4j.CompletionContext();
        ctx.setTriggerKind(CompletionTriggerKind.Invoked);
        cp.setContext(ctx);

        Either<List<CompletionItem>, CompletionList> result =
                server.getTextDocumentService().completion(cp)
                        .get(30, TimeUnit.SECONDS);

        List<CompletionItem> items = result.isLeft()
                ? result.getLeft()
                : (result.getRight() != null ? result.getRight().getItems() : null);
        assertNotNull(items);
        List<String> labels = items.stream().map(CompletionItem::getLabel).toList();
        System.out.println("[ImportCompletionIntegrationTest] didOpen-only items : "
                + labels.size() + " → " + labels);
        assertTrue(labels.contains("os"),
                "le sous-paquet « os » doit être proposé via didOpen seul : " + labels);
        assertTrue(labels.contains("content"),
                "le sous-paquet « content » doit être proposé via didOpen seul : " + labels);
        // Mots-clés absents.
        assertFalse(labels.contains("package"),
                "le mot-clé « package » ne doit pas figurer : " + labels);
        assertFalse(labels.contains("class"),
                "le mot-clé « class » ne doit pas figurer : " + labels);
    }

    /**
     * Variante : complétion pour « import and » (préfixe non-vide, sans
     * point) — doit retourner android/androidx (filtrés par préfixe), pas
     * des mots-clés.
     */
    @Test
    void completion_import_prefix_returns_top_level_packages() throws Exception {
        String uri = srcRoot.resolve("Main3.java").toUri().toString();
        // didOpen avec un texte où l'utilisateur est en train de taper
        // « import and » (caret juste après « and »).
        String text = "package com.example.e;\n\n"
                + "import and\n"
                + "\n"
                + "public class Main3 {\n"
                + "}\n";
        DidOpenTextDocumentParams openParams = new DidOpenTextDocumentParams();
        openParams.setTextDocument(new TextDocumentItem(
                uri, "java", 1, text));
        server.getTextDocumentService().didOpen(openParams);

        // Position juste après « and ».
        CompletionParams cp = new CompletionParams();
        cp.setTextDocument(new TextDocumentIdentifier(uri));
        cp.setPosition(new Position(2, "import and".length()));
        org.eclipse.lsp4j.CompletionContext ctx =
                new org.eclipse.lsp4j.CompletionContext();
        ctx.setTriggerKind(CompletionTriggerKind.Invoked);
        cp.setContext(ctx);

        Either<List<CompletionItem>, CompletionList> result =
                server.getTextDocumentService().completion(cp)
                        .get(30, TimeUnit.SECONDS);

        List<CompletionItem> items = result.isLeft()
                ? result.getLeft()
                : (result.getRight() != null ? result.getRight().getItems() : null);
        assertNotNull(items);
        List<String> labels = items.stream().map(CompletionItem::getLabel).toList();
        System.out.println("[ImportCompletionIntegrationTest] « import and » items : "
                + labels.size() + " → " + labels);
        assertTrue(labels.contains("android"),
                "le top-level package « android » doit être proposé pour "
                        + "« import and » : " + labels);
        assertTrue(labels.contains("androidx"),
                "le top-level package « androidx » doit être proposé pour "
                        + "« import and » : " + labels);
        // Mots-clés absents (pas de « static », « void » etc. qui
        // démarreraient par « and » mais peuvent filtrer).
        assertFalse(labels.contains("package"),
                "le mot-clé « package » ne doit pas figurer : " + labels);
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
