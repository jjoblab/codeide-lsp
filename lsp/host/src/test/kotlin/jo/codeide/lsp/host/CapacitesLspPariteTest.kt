package jo.codeide.lsp.host

import jo.lspjava.engine.CompilationEnvironment
import jo.lspkotlin.api.SessionLangageKotlin
import jo.lspxml.api.SessionLangageXml
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.services.LanguageServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

/**
 * PARITÉ capacités annoncées ↔ capacités réelles (lot LSP-1, ADR 0128 §4)
 * — le garde-fou de [CapacitesAnnoncees] : la table qui pilote les
 * interrupteurs de l'écran L2 ne peut pas mentir, chaque serveur est
 * INITIALISÉ pour de vrai et ses `ServerCapabilities` traduites par
 * [CapacitesAnnoncees.depuisServeur] doivent être EXACTEMENT la table.
 *
 * DIAGNOSTICS vit hors de la comparaison : les `ServerCapabilities` LSP
 * n'ont pas de champ diagnostics (publication implicite par
 * `publishDiagnostics`, contrat des trois moteurs) — la valeur vient de
 * la table elle-même.
 *
 * Les sessions Kotlin/XML sont des PROXYS jamais sollicités : `initialize`
 * construit les capacités sans toucher à la session (le proxy ÉCHOE si
 * c'était faux — le test devient menteur à son tour).
 *
 * ── Lot L0 (plan 1.3.0) : TABLE COMPLÈTE + ANTI-STUBS ──
 *
 * W4 (écart du plan 1.3.0) : la parité ne verrouillait que les 10
 * capacités RÉGLABLES — rien n'empêchait un serveur d'annoncer ou de
 * cesser d'annoncer une capacité non réglable (symboles, pliage, jetons,
 * lentilles…) sans que personne ne le voie. Chaque serveur compare
 * désormais ses `ServerCapabilities` réelles à une TABLE COMPLÈTE
 * ([TABLE_JAVA]/[TABLE_KOTLIN]/[TABLE_XML]) : ajouter ou retirer une
 * capacité sans mettre la table à jour fait échouer le test.
 *
 * ANTI-STUB (critère 2 de « capacité en service », section 1 du plan) :
 * toute capacité annoncée par la table doit avoir un GESTIONNAIRE
 * SURCHARGÉ — une capacité annoncée dont la méthode LSP4J reste le DÉFAUT
 * de l'interface (toujours vide/null) est un mensonge de protocole, le
 * test la signale. La preuve que le gestionnaire fait un VRAI travail
 * (résultat non vide sur un document réel) vit dans les tests de bout en
 * bout cités par la matrice `docs/LSP_MATRICE_CAPACITES.md`.
 */
@Timeout(60)
class CapacitesLspPariteTest {
    @Test
    fun `java - table = capacites reelles du serveur`() {
        val serveur = jo.lspjava.lsp.JavaLanguageServer(CompilationEnvironment()) {}
        val capacites = capaciteReelles(serveur)
        assertEquals(
            CapacitesAnnoncees.capacites(LangageLsp.JAVA) - CapaciteLsp.DIAGNOSTICS,
            capacites,
        )
        serveur.shutdown().get(10, TimeUnit.SECONDS)
        serveur.exit()
    }

    @Test
    fun `kotlin - table = capacites reelles du serveur`() {
        val serveur = KotlinLanguageServer(proxyPassif(SessionLangageKotlin::class.java), "version-test")
        val capacites = capaciteReelles(serveur)
        assertEquals(
            CapacitesAnnoncees.capacites(LangageLsp.KOTLIN) - CapaciteLsp.DIAGNOSTICS,
            capacites,
        )
        serveur.shutdown().get(10, TimeUnit.SECONDS)
        serveur.exit()
    }

    @Test
    fun `xml - table = capacites reelles du serveur`() {
        val serveur = XmlLanguageServer(proxyPassif(SessionLangageXml::class.java), "version-test")
        val capacites = capaciteReelles(serveur)
        assertEquals(
            CapacitesAnnoncees.capacites(LangageLsp.XML) - CapaciteLsp.DIAGNOSTICS,
            capacites,
        )
        serveur.shutdown().get(10, TimeUnit.SECONDS)
        serveur.exit()
    }

    // ── L0 : table COMPLÈTE (toute capacité, réglable ou non) ─────────

    @Test
    fun `java - table COMPLETE des capacites annoncees (L0)`() {
        val serveur = jo.lspjava.lsp.JavaLanguageServer(CompilationEnvironment()) {}
        val resultat = serveur.initialize(InitializeParams()).get(10, TimeUnit.SECONDS)
        assertEquals(
            TABLE_JAVA,
            tableComplete(resultat.capabilities),
            "ajout ou retrait d'une capacité Java sans mettre TABLE_JAVA à jour",
        )
        assertGestionnairesSurcharges("Java", serveur, TABLE_JAVA)
        serveur.shutdown().get(10, TimeUnit.SECONDS)
        serveur.exit()
    }

    @Test
    fun `kotlin - table COMPLETE des capacites annoncees (L0)`() {
        val serveur = KotlinLanguageServer(proxyPassif(SessionLangageKotlin::class.java), "version-test")
        val resultat = serveur.initialize(InitializeParams()).get(10, TimeUnit.SECONDS)
        assertEquals(
            TABLE_KOTLIN,
            tableComplete(resultat.capabilities),
            "ajout ou retrait d'une capacité Kotlin sans mettre TABLE_KOTLIN à jour",
        )
        assertGestionnairesSurcharges("Kotlin", serveur, TABLE_KOTLIN)
        serveur.shutdown().get(10, TimeUnit.SECONDS)
        serveur.exit()
    }

    @Test
    fun `xml - table COMPLETE des capacites annoncees (L0)`() {
        val serveur = XmlLanguageServer(proxyPassif(SessionLangageXml::class.java), "version-test")
        val resultat = serveur.initialize(InitializeParams()).get(10, TimeUnit.SECONDS)
        assertEquals(
            TABLE_XML,
            tableComplete(resultat.capabilities),
            "ajout ou retrait d'une capacité XML sans mettre TABLE_XML à jour",
        )
        assertGestionnairesSurcharges("XML", serveur, TABLE_XML)
        serveur.shutdown().get(10, TimeUnit.SECONDS)
        serveur.exit()
    }

    /** Initialize RÉEL d'un serveur → traduction publique de ses capacités. */
    private fun capaciteReelles(serveur: LanguageServer): Set<CapaciteLsp> {
        val resultat = serveur.initialize(InitializeParams()).get(10, TimeUnit.SECONDS)
        return CapacitesAnnoncees.depuisServeur(resultat.capabilities)
    }

    /**
     * Traduction COMPLÈTE des [ServerCapabilities] (L0) : une entrée par
     * capacité annoncée, la valeur porte le DÉTAIL qui fait la différence
     * (déclencheurs, préparation du renommage, kinds d'actions, légende).
     * Une capacité absente des capacités réelles est absente de la table —
     * la comparaison avec la table attendue est EXACTE (ni plus, ni moins).
     *
     * Le catalogue vit dans [EXTRACTEURS] (une lambda par capacité, l'ordre
     * du protocole) — la méthode elle-même reste TRIVIALE : la complexité
     * d'un catalogue n'est pas celle d'une méthode (leçon de l'échec CI du
     * commit 02aee32 : 27 branches d'if en cascade = 37 de complexité
     * cyclomatique, detekt a raison de refuser).
     */
    private fun tableComplete(capacites: ServerCapabilities): Map<String, String> =
        LinkedHashMap<String, String>().apply {
            for (extraction in EXTRACTEURS) {
                extraction(capacites)?.let { (cle, valeur) -> put(cle, valeur) }
            }
        }

    /**
     * ANTI-STUB (L0, critère 2) : toute capacité annoncée par [table]
     * doit avoir ses gestionnaires LSP4J SURCHARGÉS par le service concret
     * du serveur — une méthode restée au DÉFAUT de l'interface (vide/null)
     * annonce un service qui n'existe pas. Les capacités de propriété
     * (synchronisation, encodage) et la négociation n'ont pas de requête.
     */
    private fun assertGestionnairesSurcharges(
        nomServeur: String,
        serveur: LanguageServer,
        table: Map<String, String>,
    ) {
        val classeDocuments = serveur.textDocumentService.javaClass
        val classeEspace = serveur.workspaceService.javaClass
        for ((capacite, methodes) in METHODES_PAR_CAPACITE) {
            if (capacite !in table) continue
            for ((zone, methode) in methodes) {
                val classe =
                    when (zone) {
                        Zone.DOCUMENTS -> classeDocuments
                        Zone.ESPACE -> classeEspace
                    }
                assertTrue(
                    classe.declaredMethods.any { it.name == methode },
                    "$nomServeur annonce « $capacite » mais ${classe.simpleName} ne surcharge pas " +
                        "$methode() — annonce mensongère (stub)",
                )
            }
        }
        // Garde miroir : une NOUVELLE clé de table sans entrée dans
        // METHODES_PAR_CAPACITE n'est pas vérifiée — le test l'exige
        // (propriétés sans requête : liste blanche explicite).
        for (capacite in table.keys) {
            if (capacite !in METHODES_PAR_CAPACITE && capacite !in SANS_REQUETE) {
                assertTrue(
                    capacite in METHODES_PAR_CAPACITE || capacite in SANS_REQUETE,
                    "capacité « $capacite » ($nomServeur) sans vérification anti-stub : " +
                        "l'ajouter à METHODES_PAR_CAPACITE ou à SANS_REQUETE",
                )
            }
        }
    }

    /** Zone du service LSP4J qui porte la méthode. */
    private enum class Zone {
        DOCUMENTS,
        ESPACE,
    }

    /**
     * Méthodes LSP4J qui DOIVENT être surchargées pour chaque capacité
     * annoncée (la clé est celle de [tableComplete]).
     */
    private companion object {
        /** Déclencheurs compacts (l'espace devient ␣, lisible dans un diff). */
        private fun declencheurs(caracteres: List<String>?): String =
            caracteres.orEmpty().joinToString("") { car -> if (car == " ") "␣" else car }

        /**
         * Catalogue des extracteurs L0 (l'ordre canonique du protocole) :
         * une lambda par capacité LSP, rend la paire (clé, détail) si la
         * capacité est annoncée, `null` sinon — le catalogue EST une table,
         * la méthode [tableComplete] reste triviale.
         */
        private val EXTRACTEURS: List<(ServerCapabilities) -> Pair<String, String>?> =
            listOf(
                { c ->
                    c.textDocumentSync?.let { sync ->
                        "synchronisation" to
                            when (sync.left) {
                                TextDocumentSyncKind.Full -> "complète"
                                TextDocumentSyncKind.Incremental -> "incrémentale"
                                else -> "autre"
                            }
                    }
                },
                { c ->
                    c.completionProvider?.let { "complétion" to "déclencheurs ${declencheurs(it.triggerCharacters)}" }
                },
                { c ->
                    c.hoverProvider
                        ?.left
                        ?.takeIf { it }
                        ?.let { "survol" to "oui" }
                },
                { c ->
                    c.definitionProvider
                        ?.left
                        ?.takeIf { it }
                        ?.let { "définition" to "oui" }
                },
                { c ->
                    c.typeDefinitionProvider
                        ?.left
                        ?.takeIf { it }
                        ?.let { "définition de type" to "oui" }
                },
                { c ->
                    c.implementationProvider
                        ?.left
                        ?.takeIf { it }
                        ?.let { "implémentations" to "oui" }
                },
                { c ->
                    c.codeActionProvider?.let {
                        "actions de code" to (it.right?.codeActionKinds?.joinToString(",") ?: "oui")
                    }
                },
                { c ->
                    c.documentSymbolProvider
                        ?.left
                        ?.takeIf { it }
                        ?.let { "symboles de document" to "oui" }
                },
                { c ->
                    c.documentHighlightProvider
                        ?.left
                        ?.takeIf { it }
                        ?.let { "surlignage" to "oui" }
                },
                { c ->
                    c.referencesProvider
                        ?.left
                        ?.takeIf { it }
                        ?.let { "références" to "oui" }
                },
                { c ->
                    c.renameProvider?.let {
                        "renommage" to if (it.right?.prepareProvider == true) "prépare" else "oui"
                    }
                },
                { c ->
                    c.documentFormattingProvider
                        ?.left
                        ?.takeIf { it }
                        ?.let { "formatage" to "oui" }
                },
                { c ->
                    c.documentRangeFormattingProvider
                        ?.left
                        ?.takeIf { it }
                        ?.let { "formatage de plage" to "oui" }
                },
                { c ->
                    c.documentOnTypeFormattingProvider?.let {
                        "formatage à la frappe" to
                            "déclencheurs ${it.firstTriggerCharacter}${it.moreTriggerCharacter.orEmpty().joinToString(
                                "",
                            )}"
                    }
                },
                { c -> c.workspaceSymbolProvider?.let { "symboles d'espace" to "oui" } },
                { c ->
                    c.selectionRangeProvider
                        ?.left
                        ?.takeIf { it }
                        ?.let { "plage de sélection" to "oui" }
                },
                { c ->
                    c.codeLensProvider?.let {
                        "lentilles de code" to if (it.resolveProvider == true) "résolution oui" else "résolution non"
                    }
                },
                { c ->
                    c.callHierarchyProvider
                        ?.left
                        ?.takeIf { it }
                        ?.let { "hiérarchie d'appels" to "oui" }
                },
                { c ->
                    c.typeHierarchyProvider
                        ?.left
                        ?.takeIf { it }
                        ?.let { "hiérarchie de types" to "oui" }
                },
                { c ->
                    c.signatureHelpProvider?.let {
                        "aide à la signature" to "déclencheurs ${declencheurs(it.triggerCharacters)}"
                    }
                },
                { c -> c.inlayHintProvider?.let { "indices en ligne" to "oui" } },
                { c ->
                    c.foldingRangeProvider
                        ?.left
                        ?.takeIf { it }
                        ?.let { "pliage" to "oui" }
                },
                { c ->
                    c.semanticTokensProvider?.let {
                        "jetons sémantiques" to "complète, légende ${it.legend.tokenTypes.size} types"
                    }
                },
                { c ->
                    c.linkedEditingRangeProvider
                        ?.left
                        ?.takeIf { it }
                        ?.let { "édition liée" to "oui" }
                },
                { c -> c.colorProvider?.let { "couleurs de document" to "oui" } },
                { c -> c.executeCommandProvider?.let { "commandes" to "oui" } },
                { c -> c.positionEncoding?.let { "encodage de position" to it } },
                { c ->
                    (c.experimental as? Map<*, *>)?.let { experimental ->
                        if ("superDefinitionProvider" in experimental) {
                            "super-définition (expérimental)" to "oui"
                        } else {
                            null
                        }
                    }
                },
                { c ->
                    (c.experimental as? Map<*, *>)?.let { experimental ->
                        if ("decompiledSourceProvider" in experimental) {
                            "source décompilée (expérimental)" to "oui"
                        } else {
                            null
                        }
                    }
                },
            )

        /**
         * Table COMPLÈTE attendue du serveur Java (L0) — miroir exact de
         * `ServerCapabilitiesProvider.createCapabilities()` : toute
         * divergence (ajout, retrait, détail) fait tomber le test.
         */
        private val TABLE_JAVA: Map<String, String> =
            linkedMapOf(
                "synchronisation" to "incrémentale",
                "complétion" to "déclencheurs .␣(,",
                "survol" to "oui",
                "définition" to "oui",
                "définition de type" to "oui",
                "implémentations" to "oui",
                "actions de code" to "quickfix,refactor,refactor.extract,refactor.rewrite,source.organizeImports",
                "symboles de document" to "oui",
                "surlignage" to "oui",
                "références" to "oui",
                "renommage" to "prépare",
                "formatage" to "oui",
                "formatage de plage" to "oui",
                "symboles d'espace" to "oui",
                "plage de sélection" to "oui",
                "formatage à la frappe" to "déclencheurs };",
                "lentilles de code" to "résolution oui",
                "hiérarchie d'appels" to "oui",
                "hiérarchie de types" to "oui",
                "aide à la signature" to "déclencheurs (,",
                "indices en ligne" to "oui",
                "pliage" to "oui",
                "jetons sémantiques" to "complète, légende 11 types",
                "encodage de position" to "utf-16",
                "super-définition (expérimental)" to "oui",
                "source décompilée (expérimental)" to "oui",
            )

        /**
         * Table COMPLÈTE attendue du serveur Kotlin (L0) — miroir exact
         * de `KotlinLanguageServer.initialize` : toute divergence (ajout,
         * retrait, détail) fait tomber le test.
         */
        private val TABLE_KOTLIN: Map<String, String> =
            linkedMapOf(
                "synchronisation" to "complète",
                "complétion" to "déclencheurs .:",
                "survol" to "oui",
                "définition" to "oui",
                "actions de code" to "oui",
                "symboles de document" to "oui",
                "pliage" to "oui",
                "aide à la signature" to "déclencheurs (,",
                "références" to "oui",
                "surlignage" to "oui",
                "renommage" to "prépare",
                "formatage" to "oui",
                "formatage de plage" to "oui",
                "définition de type" to "oui",
                "implémentations" to "oui",
                "indices en ligne" to "oui",
                "symboles d'espace" to "oui",
                "plage de sélection" to "oui",
            )

        /**
         * Table COMPLÈTE attendue du serveur XML (L0) — miroir exact de
         * `XmlLanguageServer.initialize` : toute divergence (ajout,
         * retrait, détail) fait tomber le test.
         */
        private val TABLE_XML: Map<String, String> =
            linkedMapOf(
                "synchronisation" to "complète",
                "complétion" to "déclencheurs <=:\"",
                "survol" to "oui",
                "définition" to "oui",
                "actions de code" to "oui",
                "symboles de document" to "oui",
                "pliage" to "oui",
                "renommage" to "prépare",
                "formatage" to "oui",
            )

        /** Capacité → méthodes LSP4J qui doivent être surchargées. */
        private val METHODES_PAR_CAPACITE: Map<String, List<Pair<Zone, String>>> =
            mapOf(
                "complétion" to listOf(Zone.DOCUMENTS to "completion"),
                "survol" to listOf(Zone.DOCUMENTS to "hover"),
                "définition" to listOf(Zone.DOCUMENTS to "definition"),
                "définition de type" to listOf(Zone.DOCUMENTS to "typeDefinition"),
                "implémentations" to listOf(Zone.DOCUMENTS to "implementation"),
                "actions de code" to listOf(Zone.DOCUMENTS to "codeAction"),
                "symboles de document" to listOf(Zone.DOCUMENTS to "documentSymbol"),
                "surlignage" to listOf(Zone.DOCUMENTS to "documentHighlight"),
                "références" to listOf(Zone.DOCUMENTS to "references"),
                "renommage" to listOf(Zone.DOCUMENTS to "rename", Zone.DOCUMENTS to "prepareRename"),
                "formatage" to listOf(Zone.DOCUMENTS to "formatting"),
                "formatage de plage" to listOf(Zone.DOCUMENTS to "rangeFormatting"),
                "formatage à la frappe" to listOf(Zone.DOCUMENTS to "onTypeFormatting"),
                "symboles d'espace" to listOf(Zone.ESPACE to "symbol"),
                "plage de sélection" to listOf(Zone.DOCUMENTS to "selectionRange"),
                "lentilles de code" to
                    listOf(
                        Zone.DOCUMENTS to "codeLens",
                        Zone.DOCUMENTS to "resolveCodeLens",
                    ),
                "hiérarchie d'appels" to
                    listOf(
                        Zone.DOCUMENTS to "prepareCallHierarchy",
                        Zone.DOCUMENTS to "callHierarchyIncomingCalls",
                        Zone.DOCUMENTS to "callHierarchyOutgoingCalls",
                    ),
                "hiérarchie de types" to
                    listOf(
                        Zone.DOCUMENTS to "prepareTypeHierarchy",
                        Zone.DOCUMENTS to "typeHierarchySupertypes",
                        Zone.DOCUMENTS to "typeHierarchySubtypes",
                    ),
                "aide à la signature" to listOf(Zone.DOCUMENTS to "signatureHelp"),
                "indices en ligne" to listOf(Zone.DOCUMENTS to "inlayHint"),
                "pliage" to listOf(Zone.DOCUMENTS to "foldingRange"),
                "jetons sémantiques" to listOf(Zone.DOCUMENTS to "semanticTokensFull"),
                "édition liée" to listOf(Zone.DOCUMENTS to "linkedEditingRange"),
                "couleurs de document" to
                    listOf(
                        Zone.DOCUMENTS to "documentColor",
                        Zone.DOCUMENTS to "colorPresentation",
                    ),
                "commandes" to listOf(Zone.ESPACE to "executeCommand"),
                "super-définition (expérimental)" to listOf(Zone.DOCUMENTS to "superDefinition"),
                "source décompilée (expérimental)" to listOf(Zone.DOCUMENTS to "decompiledSource"),
            )

        /** Clés de table SANS requête associée (propriétés du protocole). */
        private val SANS_REQUETE: Set<String> =
            setOf("synchronisation", "encodage de position")
    }

    /**
     * Proxy dynamique d'une interface de session : PASSIF (valeurs par
     * défaut du type de retour) — `initialize` ne lit aucun état de la
     * session pour construire les capacités, et le `shutdown` de fin de
     * test referme la session en no-op. Si une méthode devait UN JOUR
     * influencer les capacités annoncées, la parité du test le révélerait
     * (faux positif impossible : la table est comparée aux caps réelles).
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> proxyPassif(interfaceDuLangage: Class<T>): T =
        Proxy.newProxyInstance(
            interfaceDuLangage.classLoader,
            arrayOf(interfaceDuLangage),
        ) { _, methode, _ ->
            when (methode.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Double.TYPE -> 0.0
                java.lang.Float.TYPE -> 0f
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Character.TYPE -> ' '
                else -> null
            }
        } as T
}
