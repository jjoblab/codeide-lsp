package jo.lspjava.lsp;

import org.eclipse.lsp4j.*;

import java.util.List;

/**
 * Fournisseur des capacités du serveur LSP Java.
 *
 * <p>Les {@link ClientCapabilities} transmises via
 * {@link #applyClientCapabilities(ClientCapabilities)} sont STOCKÉES et
 * exposées par {@link #clientCapabilities()} pour la négociation fine des
 * services (aujourd'hui : la forme versionnée {@code documentChanges} du
 * renommage — I8). Contrepartie honnête (I1, quick-win) : {@code
 * createCapabilities()} ne consulte PAS les capabilities pour désactiver
 * des providers. État de la consommation client (v0.134.19) : cel-lsp
 * consomme les providers qu'il connaît (complétion, hover, définitions,
 * occurrences, références, renommage, formatage, folding, hints,
 * sémantique, signature, symboles) ; les capacités S2-b
 * ({@code selectionRange}, {@code onTypeFormatting}, {@code codeLens},
 * hiérarchies call/type) sont annoncées pour les clients LSP tiers — cel
 * n'a pas (encore) d'UI pour elles, aucune n'est déclenchée in-process.</p>
 *
 * <p>{@code codeActionProvider} annonce explicitement les
 * {@code codeActionKinds} supportés ({@code quickfix}, {@code refactor},
 * {@code source.organizeImports}).</p>
 *
 * @author jo@Dev
 */
public final class ServerCapabilitiesProvider {

    /** Client capabilities reçues (peut être null si non transmis). */
    private ClientCapabilities clientCapabilities;

    /**
     * Transmet les clientCapabilities avant {@link #createCapabilities()}.
     * Les capabilities sont stockées telles quelles et exposées via
     * {@link #clientCapabilities()} — cf. javadoc de classe pour la
     * portée RÉELLE de la négociation (I1).
     */
    public void applyClientCapabilities(ClientCapabilities caps) {
        this.clientCapabilities = caps;
    }

    /**
     * @return les clientCapabilities reçues (null avant initialize, ou si
     *         le client n'en a pas transmis).
     */
    public ClientCapabilities clientCapabilities() {
        return clientCapabilities;
    }

    public ServerCapabilities createCapabilities() {
        ServerCapabilities caps = new ServerCapabilities();

        CompletionOptions completionOptions = new CompletionOptions();
        // v1.9.1 : PLUS d'espace, ni « ( » ni « , » comme déclencheurs de
        // COMPLÉTION. Le client cel ouvre le popup à préfixe VIDE dès que
        // le caractère précédent est annoncé déclencheur : « espace »
        // ouvrait le popup après CHAQUE espace (et après l'auto-indent du
        // retour à la ligne — le texte inséré finit par un espace) avec
        // la liste bruyante noms+mot-clés (retour propriétaire v1.9.0
        // « popup juste après une déclaration ou un retour à la ligne »).
        // La discipline retenue (même frontière que l'éditeur de
        // référence) : « . » rouvre (accès membre), « @ » rouvre
        // (annotations) ; TOUT AUTRE caractère non-identifiant referme.
        // Les « ( » et « , » restent déclencheurs de SIGNATURE HELP
        // (c'est leur rôle — voir signatureHelpOptions ci-dessous).
        completionOptions.setTriggerCharacters(List.of(".", "@"));
        // resolveProvider=false car resolveCompletionItem est un no-op
        // (retourne l'item tel quel) — le client ne fait pas de round-trip inutile.
        completionOptions.setResolveProvider(false);
        caps.setCompletionProvider(completionOptions);

        caps.setHoverProvider(true);
        caps.setDefinitionProvider(true);
        // go-to-type-declaration : le type du symbole au caret
        // (var/param/champ/appel), résolu par bindings ecj.
        caps.setTypeDefinitionProvider(true);
        // go-to-implementations : les héritiers directs du type en contexte
        // (une référence de type au caret, sinon la classe englobante),
        // multi-fichiers via le balayage des racines sources.
        caps.setImplementationProvider(true);
        // go-to-super : méthode LSP personnalisée textDocument/superDefinition
        // (aucun équivalent standard), annoncée via la capability experimental
        // (pattern jdtls) — le client la recherche sous la clé
        // « superDefinitionProvider » ; un serveur tiers qui ne la connaît pas
        // ne l'annonce pas et l'option Super n'apparaît pas.
        // go-to-decompiled : méthode LSP personnalisée
        // textDocument/decompiledSource câblée sur le fallback binaire de
        // definition/typeDefinition (le serveur renvoie une Location à URI
        // jdt://decompiled/<fqcn>.java ; le client appelle alors
        // decompiledSource(fqcn) pour récupérer le texte source à afficher
        // dans le buffer read-only). Annoncée via experimental.
        java.util.Map<String, Object> experimental = new java.util.LinkedHashMap<>();
        experimental.put("superDefinitionProvider", true);
        experimental.put("decompiledSourceProvider", true);
        caps.setExperimental(experimental);
        // codeActionProvider annonce explicitement les codeActionKinds
        // supportés (LSP 3.17 : CodeActionOptions { codeActionKinds: string[] }).
        // Kinds standard : "quickfix", "refactor", "refactor.extract",
        // "refactor.inline", "refactor.rewrite", "source", "source.organizeImports".
        org.eclipse.lsp4j.CodeActionOptions codeActionOptions = new org.eclipse.lsp4j.CodeActionOptions();
        codeActionOptions.setCodeActionKinds(List.of(
                "quickfix",
                "refactor",
                "refactor.extract",
                "refactor.rewrite",
                "source.organizeImports"
        ));
        codeActionOptions.setResolveProvider(false);
        caps.setCodeActionProvider(codeActionOptions);
        // Sync INCRÉMENTAL : le client envoie des éditions Range
        // (préfixe/suffixe commun) au lieu du texte complet. Sur un buffer
        // de 100 Ko, chaque frappe ne transporte plus que quelques octets
        // (vs sérialisation du tampon complet + re-parse JSON à l'arrivée).
        caps.setTextDocumentSync(TextDocumentSyncKind.Incremental);

        // Capabilities additionnelles (l'hôte active automatiquement les
        // providers déclarés).
        caps.setDocumentSymbolProvider(true);       // go-to-symbol / outline
        caps.setDocumentHighlightProvider(true);    // occurrences du symbole
        caps.setReferencesProvider(true);           // find references
        caps.setRenameProvider(true);               // rename symbol
        // prepareRename : le client demande si le symbole à la position
        // est renommable AVANT d'ouvrir la boîte de dialogue.
        org.eclipse.lsp4j.RenameOptions renameOpts = new org.eclipse.lsp4j.RenameOptions();
        renameOpts.setPrepareProvider(true);
        caps.setRenameProvider(renameOpts);
        caps.setDocumentFormattingProvider(true);   // format code
        // rangeFormatting (S2-a) : formater la SÉLECTION — le formateur
        // Eclipse parse l'unité entière mais ne produit d'éditions que
        // dans la plage demandée (le reste du buffer n'est jamais déplacé).
        caps.setDocumentRangeFormattingProvider(true);
        // workspace/symbol (S1-c) : recherche de types à l'échelle de
        // l'espace (index jars + sources + jrt — substrat de complétion).
        caps.setWorkspaceSymbolProvider(true);

        // ── S2-b (v0.134.19) : confort structurel restant ────────────
        // selectionRange : chaîne des nœuds AST englobant chaque position
        // (expansion progressive de la sélection, Alt+Shift+Up des IDE).
        caps.setSelectionRangeProvider(true);
        // onTypeFormatting : reformatage de la construction VENANT d'être
        // fermée — '}' ferme un bloc/corps de type, ';' termine une
        // déclaration. Le premier déclencheur est obligatoire en LSP.
        org.eclipse.lsp4j.DocumentOnTypeFormattingOptions onTypeOpts =
                new org.eclipse.lsp4j.DocumentOnTypeFormattingOptions("}",
                        List.of(";"));
        caps.setDocumentOnTypeFormattingProvider(onTypeOpts);
        // codeLens « références » : une lens par déclaration porteuse,
        // comptage paresseux (codeLens/resolve — substrat renommage).
        org.eclipse.lsp4j.CodeLensOptions codeLensOpts =
                new org.eclipse.lsp4j.CodeLensOptions(true);
        caps.setCodeLensProvider(codeLensOpts);
        // Hiérarchies d'appels et de types (substrat findSuper /
        // findImplementations — localisation SOURCE uniquement, D3).
        caps.setCallHierarchyProvider(true);
        caps.setTypeHierarchyProvider(true);

        // Signature help — déclenché par "(" et ","
        org.eclipse.lsp4j.SignatureHelpOptions sigOptions = new org.eclipse.lsp4j.SignatureHelpOptions();
        sigOptions.setTriggerCharacters(List.of("(", ","));
        caps.setSignatureHelpProvider(sigOptions);

        // Inlay hints — annotations de type fantômes pour les déclarations
        // « var » Java 10+, rendues par l'éditeur en texte gris estompé après
        // le nom de la variable. Pas de résolution côté serveur
        // (resolveProvider=false) : tous les hints sont calculés intégralement
        // dans l'appel initial inlayHint().
        caps.setInlayHintProvider(true);

        // Folding — régions Java (imports repliés par défaut, corps/blocs/
        // commentaires multi-lignes), portage EcjCodeFolder du projet tiers de référence.
        // Le client tire un foldingRange après chaque publication de
        // diagnostics (même cadencement que semanticTokens).
        caps.setFoldingRangeProvider(true);

        // Surlignage SÉMANTIQUE par bindings ecj (types réels résolus, pas
        // des heuristiques lexicales). La légende suit l'ordre canonique LSP
        // que le rendu de l'éditeur attend (2=class→TYPE, 3=enum,
        // 4=interface, 6=parameter/7=variable, 8=property, 9/10=méthode).
        // Le client tire textDocument/semanticTokens/full après chaque
        // publication de diagnostics ; le serveur calcule sur la lane
        // ANALYSIS du EcjEngineScheduler (jamais devant la complétion).
        org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions semOpts =
                new org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions();
        org.eclipse.lsp4j.SemanticTokensLegend legend = new org.eclipse.lsp4j.SemanticTokensLegend(
                List.of("namespace", "type", "class", "enum", "interface",
                        "struct", "parameter", "variable", "property",
                        "method", "function"),
                List.of());
        semOpts.setLegend(legend);
        semOpts.setFull(
                org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(true));
        caps.setSemanticTokensProvider(semOpts);

        // Négociation positionEncoding : LSP 3.17 permet au serveur d'annoncer
        // les encodages de position supportés. lspjava utilise UTF-16 (défaut,
        // correspond au char Java dans toOffset/toPosition). Annoncé
        // explicitement pour que le client n'envoie ni positions UTF-8 ni
        // codepoints.
        caps.setPositionEncoding(org.eclipse.lsp4j.PositionEncodingKind.UTF16);

        return caps;
    }
}
