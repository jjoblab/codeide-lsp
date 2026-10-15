package jo.lspjava.lsp;

import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests du lot S0-b « rename honnête » (I8 — plan de stabilisation §3.1) :
 * <ul>
 *   <li>{@code prepareRename} — placeholder EXACT et plage exacte du jeton
 *       (caret AU MILIEU du nom compris) ;</li>
 *   <li>{@code rename} sans cible — erreur TYPOÉE RequestFailed avec
 *       message FR et {@code data} structuré, plus jamais d'édition vide
 *       silencieuse ;</li>
 *   <li>renommage nominal — forme {@code changes} héritée quand le client
 *       n'annonce pas {@code documentChanges}, forme versionnée
 *       {@code documentChanges} quand il l'annonce (négociation).</li>
 * </ul>
 *
 * @author jo@Dev
 */
class RenameHonneteTest {

    @TempDir
    static Path tmp;

    static JavaTextDocumentService service;
    static JavaLanguageServer server;
    static Path srcRoot;

    static String findAndroidJar() {
        String[] candidates = {
                "/home/z/tools/android-sdk/platforms/android-34/android.jar",
                System.getenv("ANDROID_HOME") + "/platforms/android-34/android.jar",
                System.getenv("ANDROID_SDK_ROOT") + "/platforms/android-34/android.jar"
        };
        for (String c : candidates) {
            if (c != null && new File(c).isFile()) return c;
        }
        File platforms = new File("/home/z/tools/android-sdk/platforms");
        File[] dirs = platforms.isDirectory() ? platforms.listFiles() : null;
        if (dirs != null) {
            for (File p : dirs) {
                File jar = new File(p, "android.jar");
                if (jar.isFile()) return jar.getAbsolutePath();
            }
        }
        return null;
    }

    /** Caps par défaut (cel-lsp actuel : PAS de WorkspaceEditCapabilities). */
    private static ClientCapabilities capsLegacy() {
        return new ClientCapabilities();
    }

    /** Caps d'un client qui annonce accepter les documentChanges versionnés. */
    private static ClientCapabilities capsDocumentChanges() {
        ClientCapabilities caps = new ClientCapabilities();
        WorkspaceClientCapabilities ws = new WorkspaceClientCapabilities();
        WorkspaceEditCapabilities we = new WorkspaceEditCapabilities();
        we.setDocumentChanges(true);
        ws.setWorkspaceEdit(we);
        caps.setWorkspace(ws);
        return caps;
    }

    /** Serveur INITIALIZED avec les caps données — miroir du flux LSP réel. */
    private static JavaLanguageServer serveurInitialise(ClientCapabilities caps) {
        JavaLanguageServer s = new JavaLanguageServer(new jo.lspjava.engine.CompilationEnvironment());
        InitializeParams params = new InitializeParams();
        params.setCapabilities(caps);
        try {
            s.initialize(params).get(10, TimeUnit.SECONDS);
            s.initialized(new InitializedParams());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return s;
    }

    private static String open(JavaLanguageServer srv, String fileName, String source) {
        try {
            Path file = srcRoot.resolve(fileName);
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
            String uri = "file://" + file;
            srv.getTextDocumentService().didOpen(
                    new DidOpenTextDocumentParams(new TextDocumentItem(uri, "java", 0, source)));
            return uri;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Position (ligne, caractère) d'un offset source — UTF-16, comme le moteur. */
    private static Position positionDe(String source, int offset) {
        int line = 0, character = 0;
        for (int i = 0; i < Math.min(offset, source.length()); i++) {
            if (source.charAt(i) == '\n') { line++; character = 0; }
            else { character++; }
        }
        return new Position(line, character);
    }

    @BeforeAll
    static void setUp() {
        String androidJar = findAndroidJar();
        assumeTrue(androidJar != null, "android.jar absent — tests rename ignorés");
        srcRoot = tmp.resolve("src/demo");
        // Le serveur PAR DÉFAUT du test porte les caps legacy (cel-lsp actuel).
        server = serveurInitialise(capsLegacy());
        service = (JavaTextDocumentService) server.getTextDocumentService();
        // L'environnement réel (classpath android.jar) — branché via le
        // même live-refresh que la production (replaceAll du sync Gradle) :
        // le moteur créé ensuite résout les bindings.
        server.environment().replaceAll(
                List.of(srcRoot.toFile()), List.of(new File(androidJar)));
    }

    private static final String SOURCE = """
            package demo;

            public class Panier {
                private int total = 0;

                public void ajouter(int montant) {
                    total = total + montant;
                }

                public int somme() {
                    return total;
                }
            }
            """;

    // ── prepareRename : placeholder + plage exacts ─────────────────────

    @Test
    void prepareRenamePlaceholderExactCaretMilieuDuNom() throws Exception {
        String uri = open(server, "Panier.java", SOURCE);
        int offsetNom = SOURCE.indexOf("ajouter") + 3; // caret AU MILIEU de « ajouter »

        var resultat = service.prepareRename(new PrepareRenameParams(
                new TextDocumentIdentifier(uri), positionDe(SOURCE, offsetNom)))
                .get(30, TimeUnit.SECONDS);

        assertNotNull(resultat, "un nom à ce caret est renommable");
        assertTrue(resultat.isSecond(), "forme { range, placeholder } attendue");
        PrepareRenameResult res = resultat.getSecond();
        assertEquals("ajouter", res.getPlaceholder(),
                "placeholder EXACT (critère S0-b) : " + res.getPlaceholder());

        // La plage couvre TOUT le jeton, pas seulement [caret, caret+longueur).
        int debut = offsetNom - 3;
        Range attendu = new Range(positionDe(SOURCE, debut),
                positionDe(SOURCE, debut + "ajouter".length()));
        assertEquals(attendu.getStart().getLine(), res.getRange().getStart().getLine());
        assertEquals(attendu.getStart().getCharacter(), res.getRange().getStart().getCharacter(),
                "début de plage = DÉBUT du jeton (l'ancien code partait du caret)");
        assertEquals(attendu.getEnd().getCharacter(), res.getRange().getEnd().getCharacter(),
                "fin de plage = FIN du jeton");
    }

    @Test
    void prepareRenameHorsNomRendNull() throws Exception {
        String uri = open(server, "Panier2.java", SOURCE);
        int offsetCommentaire = SOURCE.indexOf("package"); // hors jeton nom? non —
        // « package » est un mot-clé : TargetScan ne le considère pas comme un
        // jeton renommable → null (le client n'ouvre pas la boîte de dialogue).
        var resultat = service.prepareRename(new PrepareRenameParams(
                new TextDocumentIdentifier(uri), positionDe(SOURCE, offsetCommentaire)))
                .get(30, TimeUnit.SECONDS);
        assertNull(resultat,
                "prepareRename sur un mot-clé rend null (spec : non renommable)");
    }

    // ── rename : erreur typée sans cible ───────────────────────────────

    @Test
    void renameSansCibleErreurTypeeRequestFailed() throws Exception {
        String uri = open(server, "Panier3.java", SOURCE);
        int offsetMotCle = SOURCE.indexOf("public"); // pas un symbole renommable

        RenameParams params = new RenameParams(
                new TextDocumentIdentifier(uri), positionDe(SOURCE, offsetMotCle), "autre");
        var refuse = assertThrows(
                org.eclipse.lsp4j.jsonrpc.ResponseErrorException.class,
                () -> service.rename(params),
                "l'ancien code retournait un WorkspaceEdit VIDE — I8 exige une erreur typée");
        assertEquals(org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.RequestFailed.getValue(),
                refuse.getResponseError().getCode(),
                "code RequestFailed (-32803) : " + refuse.getResponseError());
        assertTrue(refuse.getResponseError().getMessage()
                        .contains("Aucun symbole renommable"),
                "message FR explicite : " + refuse.getResponseError().getMessage());
        assertNotNull(refuse.getResponseError().getData(),
                "data structuré (code machine + position) attendu");
        assertTrue(refuse.getResponseError().getData().toString()
                        .contains("renommage.sansCible"),
                "data.code = renommage.sansCible : " + refuse.getResponseError().getData());
    }

    // ── rename : nominal, forme legacy (caps cel-lsp actuelles) ────────

    @Test
    void renommageNominalFormeChangesQuandClientNeDeclarePas() throws Exception {
        String uri = open(server, "Panier4.java", SOURCE);
        int offsetNom = SOURCE.indexOf("total");

        RenameParams params = new RenameParams(
                new TextDocumentIdentifier(uri), positionDe(SOURCE, offsetNom), "solde");
        WorkspaceEdit edit = service.rename(params).get(60, TimeUnit.SECONDS);

        assertNotNull(edit);
        assertNotNull(edit.getChanges(),
                "caps SANS documentChanges → forme héritée changes");
        assertNull(edit.getDocumentChanges(),
                "les DEUX formes ne coexistent jamais (spec)");
        assertTrue(edit.getChanges().containsKey(uri),
                "le fichier ouvert est retouché : " + edit.getChanges().keySet());
        List<TextEdit> edits = edit.getChanges().get(uri);
        assertEquals(4, edits.size(),
                "déclaration + 2 usages de l'affectation + return : " + edits);
        for (TextEdit e : edits) {
            assertEquals("solde", e.getNewText());
            assertEquals("total", SOURCE.substring(
                    offsetDe(SOURCE, e.getRange().getStart()),
                    offsetDe(SOURCE, e.getRange().getEnd())),
                    "chaque plage remplace exactement « total »");
        }
    }

    // ── rename : nominal, forme versionnée (caps documentChanges) ──────

    @Test
    void renommageNominalFormeDocumentChangesVersionneeQuandNegociee() throws Exception {
        JavaLanguageServer serveurDoc = serveurInitialise(capsDocumentChanges());
        serveurDoc.environment().replaceAll(
                List.of(srcRoot.toFile()),
                List.of(new File(findAndroidJar())));
        JavaTextDocumentService svc = (JavaTextDocumentService) serveurDoc.getTextDocumentService();

        String uri = open(serveurDoc, "Panier5.java", SOURCE);
        int offsetNom = SOURCE.indexOf("somme");

        RenameParams params = new RenameParams(
                new TextDocumentIdentifier(uri), positionDe(SOURCE, offsetNom), "cumul");
        WorkspaceEdit edit = svc.rename(params).get(60, TimeUnit.SECONDS);

        assertNotNull(edit);
        assertNotNull(edit.getDocumentChanges(),
                "caps AVEC documentChanges → forme versionnée : " + edit.getChanges());
        // NB : lsp4j 0.22 initialise la carte changes à {} dès le constructeur
        // no-arg — sur le FIL la forme legacy est absente (l'éditeur cel-lsp
        // teste null/isEmpty) ; on verrouille « aucune édition legacy ».
        assertTrue(edit.getChanges() == null || edit.getChanges().isEmpty(),
                "les DEUX formes ne coexistent jamais (spec) : " + edit.getChanges());
        assertEquals(1, edit.getDocumentChanges().size());
        TextDocumentEdit docEdit = edit.getDocumentChanges().get(0).getLeft();
        assertEquals(uri, docEdit.getTextDocument().getUri());
        assertNotNull(docEdit.getTextDocument().getVersion(),
                "le document OUVERT porte sa version courante (édition versionnée)");
        assertEquals(Integer.valueOf(0), docEdit.getTextDocument().getVersion(),
                "didOpen a posé version 0");
        assertEquals(1, docEdit.getEdits().size(),
                "déclaration seule de « somme » (aucun autre usage dans la source) : "
                        + docEdit.getEdits());
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static int ligneDebut(String source, Position p) {
        int offset = 0, line = 0;
        while (offset < source.length() && line < p.getLine()) {
            if (source.charAt(offset++) == '\n') line++;
        }
        return offset;
    }

    private static int offsetDe(String source, Position p) {
        int offset = ligneDebut(source, p);
        return Math.min(source.length(), offset + p.getCharacter());
    }
}
