package jo.lspjava.lsp;

import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du lot S2-a « confort structurel » (plan de stabilisation §3.1) :
 * <ul>
 *   <li><b>rangeFormatting</b> — formater la SÉLECTION : la capacité est
 *       déclarée, les éditions produites restent dans la plage demandée et
 *       le code hors plage n'est jamais touché ; requête hors lifecycle →
 *       rejet (garde I2) ;</li>
 *   <li><b>survol riche</b> — signature seule → PLAINTEXT (la forme
 *       MarkedString-string est dépréciée, KP14 miroir Kotlin) ;
 *       signature + javadoc de la déclaration → MARKDOWN.</li>
 * </ul>
 *
 * @author jo@Dev
 */
class JavaS2ConfortTest {

    @TempDir
    Path dossier;

    /** Source avec deux méthodes mal formatées — b est la cible de sélection. */
    private static final String SOURCE_MAL_FORMATE = """
            class T {
                void a() {
            int x=1;
                }

                void b() {
            int y=2;
                }
            }
            """;

    /** Source avec javadoc — cible du survol riche. */
    private static final String SOURCE_JAVADOC = """
            class H {
                /** Additionne deux valeurs. */
                int somme(int a, int b) {
                    return a + b;
                }

                int doubleSomme() {
                    return somme(1, 2);
                }

                int sansDoc(int x) {
                    return x;
                }
            }
            """;

    private JavaLanguageServer serveurInitialise() throws Exception {
        var server = new JavaLanguageServer(new jo.lspjava.engine.CompilationEnvironment(
                List.of(dossier.toFile()), List.of(), null));
        server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
        server.initialized(new org.eclipse.lsp4j.InitializedParams());
        return server;
    }

    /** Convertit un offset (0-based) en Position LSP (lignes 0-based). */
    private static Position positionDe(String source, int offset) {
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

    // ── rangeFormatting ────────────────────────────────────────────────

    @Test
    void capaciteRangeFormattingDeclaree() {
        var caps = new ServerCapabilitiesProvider().createCapabilities();
        assertEquals(Boolean.TRUE, caps.getDocumentRangeFormattingProvider().getLeft(),
                "la capacité rangeFormatting est annoncée (forme booléenne)");
    }

    @Test
    void formatageDeSelectionBornesALaPlage() throws Exception {
        var server = serveurInitialise();
        String uri = dossier.resolve("T.java").toUri().toString();
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "java", 1, SOURCE_MAL_FORMATE)));

        int debutB = SOURCE_MAL_FORMATE.indexOf("void b()");
        int finB = SOURCE_MAL_FORMATE.indexOf('}', SOURCE_MAL_FORMATE.indexOf("int y=2;")) + 1;
        var params = new DocumentRangeFormattingParams(
                new TextDocumentIdentifier(uri),
                new org.eclipse.lsp4j.FormattingOptions(),
                new Range(positionDe(SOURCE_MAL_FORMATE, debutB),
                        positionDe(SOURCE_MAL_FORMATE, finB)));
        List<? extends org.eclipse.lsp4j.TextEdit> edits =
                server.getTextDocumentService().rangeFormatting(params)
                        .get(5, TimeUnit.SECONDS);

        assertNotNull(edits, "des éditions sont produites");
        assertFalse(edits.isEmpty(), "une plage non vide produit des éditions");
        int ligneB = positionDe(SOURCE_MAL_FORMATE, debutB).getLine();
        for (org.eclipse.lsp4j.TextEdit e : edits) {
            assertTrue(e.getRange().getStart().getLine() >= ligneB,
                    "aucune édition ne remonte avant la sélection : " + e.getRange());
        }
        // Reconstruction du texte appliqué — le code hors plage survit.
        // Les éditions LSP portent des positions de la source ORIGINALE.
        var trie = new java.util.ArrayList<>(edits);
        trie.sort(java.util.Comparator.comparingInt(
                (org.eclipse.lsp4j.TextEdit t) -> offsetDe(SOURCE_MAL_FORMATE, t.getRange().getStart()))
                .reversed());
        String texte = SOURCE_MAL_FORMATE;
        for (org.eclipse.lsp4j.TextEdit e : trie) {
            int d = offsetDe(SOURCE_MAL_FORMATE, e.getRange().getStart());
            int f = offsetDe(SOURCE_MAL_FORMATE, e.getRange().getEnd());
            texte = texte.substring(0, d) + e.getNewText() + texte.substring(f);
        }
        assertTrue(texte.contains("int x=1;"),
                "la méthode a (hors plage) reste inchangée : " + texte);
        assertTrue(texte.contains("int y = 2;"),
                "la méthode b (dans la plage) est formatée : " + texte);
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
    void rangeFormattingAvantInitialisationRejetee() {
        // Garde lifecycle I2 : une requête avant initialized est rejetée
        // (ServerNotInitialized), comme les autres requêtes du service.
        var server = new JavaLanguageServer(new jo.lspjava.engine.CompilationEnvironment(
                List.of(dossier.toFile()), List.of(), null));
        var service = server.getTextDocumentService();
        var params = new DocumentRangeFormattingParams(
                new TextDocumentIdentifier("file:///x/A.java"),
                new org.eclipse.lsp4j.FormattingOptions(),
                new Range(new Position(0, 0), new Position(0, 5)));
        assertThrows(org.eclipse.lsp4j.jsonrpc.ResponseErrorException.class,
                () -> service.rangeFormatting(params),
                "rangeFormatting avant initialized → rejet");
    }

    // ── survol riche ───────────────────────────────────────────────────

    @Test
    void hoverSignatureSeuleEnPlaintext() throws Exception {
        var server = serveurInitialise();
        String uri = dossier.resolve("H1.java").toUri().toString();
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "java", 1, SOURCE_JAVADOC)));

        // Sans javadoc → PLAINTEXT, signature exacte.
        int offset = SOURCE_JAVADOC.indexOf("sansDoc");
        Hover hover = server.getTextDocumentService().hover(
                new HoverParams(new TextDocumentIdentifier(uri), positionDe(SOURCE_JAVADOC, offset)))
                .get(5, TimeUnit.SECONDS);
        assertNotNull(hover, "survol attendu sur sansDoc");
        var contenu = hover.getContents().getRight();
        assertNotNull(contenu, "le contenu est une MarkupContent (plus de "
                + "MarkedString-string déprécié)");
        assertEquals(org.eclipse.lsp4j.MarkupKind.PLAINTEXT, contenu.getKind(),
                "signature seule → PLAINTEXT");
        assertTrue(contenu.getValue().contains("sansDoc"),
                "la signature nomme la méthode : " + contenu.getValue());
        assertFalse(contenu.getValue().contains("```"),
                "la signature en PLAINTEXT n'a pas de clôtures");
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }

    @Test
    void hoverAvecJavadocEnMarkdown() throws Exception {
        var server = serveurInitialise();
        String uri = dossier.resolve("H2.java").toUri().toString();
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "java", 1, SOURCE_JAVADOC)));

        // Référence à somme (déclaration ailleurs) → MARKDOWN signature + doc.
        int offset = SOURCE_JAVADOC.indexOf("somme(1, 2)");
        Hover hover = server.getTextDocumentService().hover(
                new HoverParams(new TextDocumentIdentifier(uri), positionDe(SOURCE_JAVADOC, offset)))
                .get(5, TimeUnit.SECONDS);
        assertNotNull(hover, "survol attendu sur l'appel somme(1, 2)");
        var contenu = hover.getContents().getRight();
        assertNotNull(contenu, "le contenu est une MarkupContent");
        assertEquals(org.eclipse.lsp4j.MarkupKind.MARKDOWN, contenu.getKind(),
                "signature + javadoc → MARKDOWN");
        assertTrue(contenu.getValue().startsWith("```java\n"),
                "bloc de code signature : " + contenu.getValue());
        assertTrue(contenu.getValue().contains("Additionne deux valeurs"),
                "la javadoc voyage : " + contenu.getValue());
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }
}
