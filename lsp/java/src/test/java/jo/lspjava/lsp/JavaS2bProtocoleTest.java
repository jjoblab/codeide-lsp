package jo.lspjava.lsp;

import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams;
import org.eclipse.lsp4j.DocumentOnTypeFormattingOptions;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SelectionRangeParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TypeHierarchyPrepareParams;
import org.eclipse.lsp4j.TypeHierarchySubtypesParams;
import org.eclipse.lsp4j.TypeHierarchySupertypesParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du lot S2-b « confort structurel restant » (plan §3.1) : protocole
 * bout-en-bout via {@link JavaLanguageServer}.
 *
 * <ul>
 *   <li><b>selectionRange</b> — chaîne strictement croissante, alignée sur
 *       les positions demandées ;</li>
 *   <li><b>onTypeFormatting</b> — '}' reformate le bloc fermé sans toucher
 *       le reste du buffer ;</li>
 *   <li><b>codeLens</b> — lenses par déclaration + resolve (comptage par
 *       bindings, déclaration exclue) ;</li>
 *   <li><b>hiérarchies</b> — prepareType/CallHierarchy + supertypes/
 *       subtypes/incoming/outgoing, localisation SOURCE (D3) ;</li>
 *   <li><b>filterText (I12)</b> — chaque item de complétion porte la clé
 *       matchée ;</li>
 *   <li>garde I2 : requêtes avant initialize rejetées.</li>
 * </ul>
 *
 * @author jo@Dev
 */
class JavaS2bProtocoleTest {

    @TempDir
    Path dossier;

    private static final String SOURCE_FORMAT = """
            package demo;

            class T {
                void a() {
            int x=1;
                }

                void b() {
            int y=2;
                }
            }
            """;

    private static final String SOURCE_BASE = """
            package demo;

            class Base {
                void utilise(int valeur) {
                }
            }
            """;

    private static final String SOURCE_APPELANT = """
            package demo;

            class Appelant {
                void declenche() {
                    Base b = new Base();
                    b.utilise(3);
                }
            }
            """;

    private static final String SOURCE_BASE2 = """
            package demo;

            class Base2 {
            }
            """;

    private static final String SOURCE_ENFANT2 = """
            package demo;

            class Enfant2 extends Base2 {
            }
            """;

    private JavaLanguageServer serveurInitialise() throws Exception {
        ecrire("demo/Base.java", SOURCE_BASE);
        ecrire("demo/Appelant.java", SOURCE_APPELANT);
        ecrire("demo/Base2.java", SOURCE_BASE2);
        ecrire("demo/Enfant2.java", SOURCE_ENFANT2);
        var server = new JavaLanguageServer(new jo.lspjava.engine.CompilationEnvironment(
                List.of(dossier.toFile()), List.of(), null));
        server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
        server.initialized(new org.eclipse.lsp4j.InitializedParams());
        return server;
    }

    private void ecrire(String relatif, String contenu) throws Exception {
        Path fichier = dossier.resolve(relatif);
        Files.createDirectories(fichier.getParent());
        Files.writeString(fichier, contenu);
    }

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

    private static int offsetDe(String source, Position p) {
        int offset = 0;
        for (int i = 0; i < p.getLine(); i++) {
            offset = source.indexOf('\n', offset) + 1;
        }
        return offset + p.getCharacter();
    }

    private String ouvrir(JavaLanguageServer server, String nom,
                          String source) throws Exception {
        String uri = dossier.resolve(nom).toUri().toString();
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "java", 1, source)));
        return uri;
    }

    // ── capabilities ───────────────────────────────────────────────────

    @Test
    void capabilitiesS2bDeclarees() {
        var caps = new ServerCapabilitiesProvider().createCapabilities();
        assertEquals(Boolean.TRUE, caps.getSelectionRangeProvider().getLeft(),
                "selectionRange annoncé (forme booléenne à gauche)");
        DocumentOnTypeFormattingOptions onType = caps
                .getDocumentOnTypeFormattingProvider();
        assertNotNull(onType, "onTypeFormatting annoncé");
        assertEquals("}", onType.getFirstTriggerCharacter(),
                "premier déclencheur = }");
        assertTrue(onType.getMoreTriggerCharacter().contains(";"),
                "second déclencheur = ;");
        assertNotNull(caps.getCodeLensProvider(), "codeLens annoncé");
        assertEquals(Boolean.TRUE, caps.getCodeLensProvider().getResolveProvider(),
                "codeLens resolveProvider");
        assertEquals(Boolean.TRUE, caps.getCallHierarchyProvider().getLeft(),
                "callHierarchy annoncé");
        assertEquals(Boolean.TRUE, caps.getTypeHierarchyProvider().getLeft(),
                "typeHierarchy annoncé");
    }

    // ── selectionRange ─────────────────────────────────────────────────

    @Test
    void selectionRangeChaineCroissanteBoutEnBout() throws Exception {
        var server = serveurInitialise();
        String uri = ouvrir(server, "demo/T.java", SOURCE_FORMAT);

        int offset = SOURCE_FORMAT.indexOf("int y=2;") + 5;
        var reponse = server.getTextDocumentService().selectionRange(
                        new SelectionRangeParams(new TextDocumentIdentifier(uri),
                                List.of(positionDe(SOURCE_FORMAT, offset))))
                .get(5, TimeUnit.SECONDS);
        assertEquals(1, reponse.size(), "autant de réponses que de positions");
        // Descendre la chaîne parent : croissance stricte.
        org.eclipse.lsp4j.SelectionRange niveau = reponse.get(0);
        int niveaux = 0;
        int longueurPrecedente = -1;
        StringBuilder trace = new StringBuilder();
        while (niveau != null) {
            int d = offsetDe(SOURCE_FORMAT, niveau.getRange().getStart());
            int f = offsetDe(SOURCE_FORMAT, niveau.getRange().getEnd());
            trace.append('[').append(d).append(',').append(f).append(") ");
            assertTrue(f > d, "plage non vide");
            assertTrue(f - d > longueurPrecedente,
                    "croissance stricte au niveau " + niveaux + " : " + trace);
            longueurPrecedente = f - d;
            niveaux++;
            niveau = niveau.getParent();
        }
        assertTrue(niveaux >= 4, "l'escalade est profonde (" + niveaux + ")");
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }

    // ── onTypeFormatting ───────────────────────────────────────────────

    @Test
    void onTypeFormattingAccoladeReformateLeBlocSeulement() throws Exception {
        var server = serveurInitialise();
        String uri = ouvrir(server, "demo/TF.java", SOURCE_FORMAT);

        // Le '}' fermant la méthode b : le dernier '}' avant celui de T.
        int finClasse = SOURCE_FORMAT.lastIndexOf('}');
        int finBlocB = SOURCE_FORMAT.lastIndexOf('}', finClasse - 1);
        var params = new DocumentOnTypeFormattingParams(
                new TextDocumentIdentifier(uri), new FormattingOptions(),
                positionDe(SOURCE_FORMAT, finBlocB + 1), "}");
        List<? extends org.eclipse.lsp4j.TextEdit> edits =
                server.getTextDocumentService().onTypeFormatting(params)
                        .get(5, TimeUnit.SECONDS);

        assertNotNull(edits, "des éditions sont produites");
        assertFalse(edits.isEmpty(), "un bloc mal indenté produit des éditions");
        // Reconstruction : b formaté, a intact.
        var triee = new java.util.ArrayList<>(edits);
        triee.sort(java.util.Comparator.comparingInt(
                (org.eclipse.lsp4j.TextEdit t) -> offsetDe(SOURCE_FORMAT,
                        t.getRange().getStart())).reversed());
        String texte = SOURCE_FORMAT;
        for (org.eclipse.lsp4j.TextEdit e : triee) {
            int d = offsetDe(SOURCE_FORMAT, e.getRange().getStart());
            int f = offsetDe(SOURCE_FORMAT, e.getRange().getEnd());
            texte = texte.substring(0, d) + e.getNewText() + texte.substring(f);
        }
        assertTrue(texte.contains("int y = 2;"),
                "le bloc b est formaté : " + texte);
        assertTrue(texte.contains("int x=1;"),
                "la méthode a (hors bloc) reste intacte : " + texte);
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }

    @Test
    void onTypeFormattingCaractereEtrangerAucuneEdition() throws Exception {
        var server = serveurInitialise();
        String uri = ouvrir(server, "demo/TX.java", SOURCE_FORMAT);
        // Le caret sur un offset qui n'héberge PAS le caractère tapé.
        var params = new DocumentOnTypeFormattingParams(
                new TextDocumentIdentifier(uri), new FormattingOptions(),
                positionDe(SOURCE_FORMAT, 0), "}");
        List<? extends org.eclipse.lsp4j.TextEdit> edits =
                server.getTextDocumentService().onTypeFormatting(params)
                        .get(5, TimeUnit.SECONDS);
        assertNotNull(edits);
        assertTrue(edits.isEmpty(),
                "le caractère à l'offset ne correspond pas → no-op");
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }

    // ── codeLens ───────────────────────────────────────────────────────

    @Test
    void codeLensParDeclarationResolveCompte() throws Exception {
        // Serveur ISOLÉ (aucun autre fichier du projet) : le compte de
        // références de « utilise » est 0.
        var server = new JavaLanguageServer(new jo.lspjava.engine.CompilationEnvironment(
                List.of(dossier.toFile()), List.of(), null));
        server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
        server.initialized(new org.eclipse.lsp4j.InitializedParams());
        String uri = ouvrir(server, "demo/CLBase.java", SOURCE_BASE);

        List<? extends CodeLens> lenses = server.getTextDocumentService()
                .codeLens(new CodeLensParams(new TextDocumentIdentifier(uri)))
                .get(5, TimeUnit.SECONDS);
        assertEquals(2, lenses.size(),
                "une lens par déclaration (classe + méthode) : " + lenses.size());

        // La lens de « utilise » — la plus profonde (ligne 3).
        CodeLens lensMethode = lenses.stream()
                .filter(l -> l.getRange().getStart().getLine() >= 3)
                .findFirst().orElseThrow();
        assertTrue(lensMethode.getCommand() == null,
                "la lens brute n'a pas encore de commande (resolve paresseux)");
        CodeLens resolue = server.getTextDocumentService()
                .resolveCodeLens(lensMethode).get(5, TimeUnit.SECONDS);
        assertNotNull(resolue.getCommand(), "la lens résolue porte la commande");
        assertEquals("0 référence", resolue.getCommand().getTitle(),
                "aucune référence à utilise dans le projet");
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }

    @Test
    void codeLensCompteLesReferencesMultiFichiers() throws Exception {
        // Serveur ISOLÉ : CRBase (ouvert) + l'appelant sur le disque (pas
        // ouvert) — le balayage consulte l'overlay PUIS le disque.
        var server = new JavaLanguageServer(new jo.lspjava.engine.CompilationEnvironment(
                List.of(dossier.toFile()), List.of(), null));
        server.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
        server.initialized(new org.eclipse.lsp4j.InitializedParams());
        // Fichiers AUSSI sur disque avec des noms CANONIQUES (la résolution
        // ecj mappe demo.Base → <racine>/demo/Base.java) : l'appelant est
        // lu sur disque, le déclarant est ouvert ET sur disque.
        ecrire("demo/Base.java", SOURCE_BASE);
        ecrire("demo/Appelant.java", SOURCE_APPELANT);
        String uriBase = ouvrir(server, "demo/Base.java", SOURCE_BASE);

        List<? extends CodeLens> lenses = server.getTextDocumentService()
                .codeLens(new CodeLensParams(new TextDocumentIdentifier(uriBase)))
                .get(5, TimeUnit.SECONDS);
        assertFalse(lenses.isEmpty());
        CodeLens lensMethode = lenses.stream()
                .filter(l -> l.getRange().getStart().getLine() >= 3)
                .findFirst().orElseThrow();
        CodeLens resolue = server.getTextDocumentService()
                .resolveCodeLens(lensMethode).get(10, TimeUnit.SECONDS);
        assertEquals("1 référence", resolue.getCommand().getTitle(),
                "l'appel b.utilise(3) est compté, la déclaration exclue : "
                        + resolue.getCommand().getTitle());
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }

    // ── hiérarchie de types ────────────────────────────────────────────

    @Test
    void typeHierarchyPrepareSupertypesSubtypes() throws Exception {
        var server = serveurInitialise();
        // L'ÉLÉMENT préparé est localisé dans SA source : la référence
        // « extends Base2 » (fichier Enfant2) prépare l'élément Base2
        // (fichier Base2).
        String uriEnfant = ouvrir(server, "demo/Enfant2.java", SOURCE_ENFANT2);

        int offset = SOURCE_ENFANT2.indexOf("extends Base2") + "extends ".length();
        var items = server.getTextDocumentService().prepareTypeHierarchy(
                        new TypeHierarchyPrepareParams(new TextDocumentIdentifier(uriEnfant),
                                positionDe(SOURCE_ENFANT2, offset)))
                .get(5, TimeUnit.SECONDS);
        assertEquals(1, items.size(), "l'élément Base2 est préparé");
        assertEquals("Base2", items.get(0).getName());
        assertTrue(items.get(0).getUri().endsWith("Base2.java"),
                "localisé dans SA source : " + items.get(0).getUri());

        // supertypes de Enfant2 → Base2.
        int offsetEnfant = SOURCE_ENFANT2.indexOf("class Enfant2") + "class ".length();
        var enfants = server.getTextDocumentService().prepareTypeHierarchy(
                        new TypeHierarchyPrepareParams(new TextDocumentIdentifier(uriEnfant),
                                positionDe(SOURCE_ENFANT2, offsetEnfant)))
                .get(5, TimeUnit.SECONDS);
        assertEquals(1, enfants.size());
        var sups = server.getTextDocumentService().typeHierarchySupertypes(
                        new TypeHierarchySupertypesParams(enfants.get(0)))
                .get(5, TimeUnit.SECONDS);
        assertEquals(1, sups.size(), "un supertype direct : Base2");
        assertEquals("Base2", sups.get(0).getName());

        // subtypes de Base2 → Enfant2 (balayage des racines sources).
        var sous = server.getTextDocumentService().typeHierarchySubtypes(
                        new TypeHierarchySubtypesParams(items.get(0)))
                .get(10, TimeUnit.SECONDS);
        assertEquals(1, sous.size(), "un héritier direct : Enfant2");
        assertEquals("Enfant2", sous.get(0).getName());
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }

    @Test
    void typeHierarchyPasDeTypeAuCaretListeVide() throws Exception {
        var server = serveurInitialise();
        String uri = ouvrir(server, "demo/Enfant2.java", SOURCE_ENFANT2);
        // Le caret DANS LE CORPS VIDE de la classe (après l'accolade
        // ouvrante) : aucune référence de type là — mais le type ENGLOBANT
        // Enfant2 est détecté (convention findImplementations : la classe
        // englobante compte) — l'élément préparé est Enfant2 lui-même.
        int offset = SOURCE_ENFANT2.indexOf('{') + 1;
        var items = server.getTextDocumentService().prepareTypeHierarchy(
                        new TypeHierarchyPrepareParams(new TextDocumentIdentifier(uri),
                                positionDe(SOURCE_ENFANT2, offset)))
                .get(5, TimeUnit.SECONDS);
        assertNotNull(items);
        assertEquals(1, items.size(),
                "le type ENGLOBANT au caret est l'élément (convention "
                        + "contextType)");
        assertEquals("Enfant2", items.get(0).getName());
        // Et le caret sur l'import/package (avant toute classe) → vide.
        int offsetPaquet = SOURCE_ENFANT2.indexOf("package");
        var vide = server.getTextDocumentService().prepareTypeHierarchy(
                        new TypeHierarchyPrepareParams(new TextDocumentIdentifier(uri),
                                positionDe(SOURCE_ENFANT2, offsetPaquet)))
                .get(5, TimeUnit.SECONDS);
        assertNotNull(vide);
        assertTrue(vide.isEmpty(),
                "aucun type référencé/englobant au caret → liste vide");
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }

    // ── hiérarchie d'appels ────────────────────────────────────────────

    @Test
    void callHierarchyPrepareIncomingOutgoing() throws Exception {
        var server = serveurInitialise();
        String uriAppelant = ouvrir(server, "demo/CHA.java", SOURCE_APPELANT);

        // prepare sur l'APPEL b.utilise(3) — l'élément est localisé dans
        // Base.java (la déclaration, pas l'appel).
        int offset = SOURCE_APPELANT.indexOf("b.utilise(3)") + "b.".length();
        var items = server.getTextDocumentService().prepareCallHierarchy(
                        new CallHierarchyPrepareParams(new TextDocumentIdentifier(uriAppelant),
                                positionDe(SOURCE_APPELANT, offset)))
                .get(5, TimeUnit.SECONDS);
        assertEquals(1, items.size(), "l'élément utilise est préparé");
        assertEquals("utilise", items.get(0).getName());
        assertTrue(items.get(0).getUri().endsWith("Base.java"),
                "l'élément pointe la DÉCLARATION : " + items.get(0).getUri());

        // incoming : Appelant.declenche appelle utilise — 1 site.
        var entrants = server.getTextDocumentService().callHierarchyIncomingCalls(
                        new CallHierarchyIncomingCallsParams(items.get(0)))
                .get(10, TimeUnit.SECONDS);
        assertEquals(1, entrants.size(), "un appelant : " + entrants.size());
        assertEquals("declenche", entrants.get(0).getFrom().getName());
        assertEquals(1, entrants.get(0).getFromRanges().size(),
                "un site d'appel dans declenche");

        // outgoing de declenche : utilise (+ cibles binaires écartées).
        int offsetDecl = SOURCE_APPELANT.indexOf("declenche()") + 2;
        var prepDecl = server.getTextDocumentService().prepareCallHierarchy(
                        new CallHierarchyPrepareParams(new TextDocumentIdentifier(uriAppelant),
                                positionDe(SOURCE_APPELANT, offsetDecl)))
                .get(5, TimeUnit.SECONDS);
        assertEquals(1, prepDecl.size());
        var sortants = server.getTextDocumentService().callHierarchyOutgoingCalls(
                        new CallHierarchyOutgoingCallsParams(prepDecl.get(0)))
                .get(10, TimeUnit.SECONDS);
        assertEquals(1, sortants.size(),
                "une cible source (utilise) : " + sortants.size());
        assertEquals("utilise", sortants.get(0).getTo().getName());
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }

    // ── filterText (I12) ───────────────────────────────────────────────

    @Test
    void completionPorteFilterTextAligneSurLeLabel() throws Exception {
        var server = serveurInitialise();
        String uri = ouvrir(server, "demo/FT.java", SOURCE_APPELANT);
        // Complétion après « b. » — les membres de Base voyagent avec
        // filterText.
        int offset = SOURCE_APPELANT.indexOf("b.utilise(3)") + "b.".length();
        var params = new org.eclipse.lsp4j.CompletionParams(
                new TextDocumentIdentifier(uri), positionDe(SOURCE_APPELANT, offset));
        var liste = server.getTextDocumentService().completion(params)
                .get(5, TimeUnit.SECONDS).getRight();
        assertNotNull(liste, "une liste de complétion est servie");
        assertFalse(liste.getItems().isEmpty(), "des candidats membres");
        for (org.eclipse.lsp4j.CompletionItem item : liste.getItems()) {
            assertNotNull(item.getFilterText(),
                    "filterText posé sur « " + item.getLabel() + " » (I12)");
            assertEquals(item.getLabel(), item.getFilterText(),
                    "filterText = la clé matchée par le serveur");
        }
        server.shutdown().get(5, TimeUnit.SECONDS);
        server.exit();
    }

    // ── garde I2 (lifecycle) ───────────────────────────────────────────

    @Test
    void requetesAvantInitialisationRejetees() {
        var server = new JavaLanguageServer(new jo.lspjava.engine.CompilationEnvironment(
                List.of(dossier.toFile()), List.of(), null));
        var service = server.getTextDocumentService();
        var id = new TextDocumentIdentifier("file:///x/A.java");

        assertThrows(org.eclipse.lsp4j.jsonrpc.ResponseErrorException.class,
                () -> service.selectionRange(new SelectionRangeParams(id,
                        List.of(new Position(0, 0)))),
                "selectionRange avant initialize → rejet");
        assertThrows(org.eclipse.lsp4j.jsonrpc.ResponseErrorException.class,
                () -> service.onTypeFormatting(new DocumentOnTypeFormattingParams(
                        id, new FormattingOptions(), new Position(0, 0), "}")),
                "onTypeFormatting avant initialize → rejet");
        assertThrows(org.eclipse.lsp4j.jsonrpc.ResponseErrorException.class,
                () -> service.codeLens(new CodeLensParams(id)),
                "codeLens avant initialize → rejet");
        assertThrows(org.eclipse.lsp4j.jsonrpc.ResponseErrorException.class,
                () -> service.prepareTypeHierarchy(new TypeHierarchyPrepareParams(
                        id, new Position(0, 0))),
                "prepareTypeHierarchy avant initialize → rejet");
        assertThrows(org.eclipse.lsp4j.jsonrpc.ResponseErrorException.class,
                () -> service.prepareCallHierarchy(new CallHierarchyPrepareParams(
                        id, new Position(0, 0))),
                "prepareCallHierarchy avant initialize → rejet");
    }
}
