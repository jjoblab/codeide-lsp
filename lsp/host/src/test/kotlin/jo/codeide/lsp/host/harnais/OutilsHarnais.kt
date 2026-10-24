package jo.codeide.lsp.host.harnais

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * HARNais d'audit — OUTILS du client LSP réel.
 *
 * Chaque groupe de scénarios démarre un VRAI sous-processus serveur
 * ([ServeurStdio]) et lui parle par le transport stdio JSON-RPC de
 * LSP4J — le montage le plus proche du déploiement réel (l'« in-process
 * » de production est remplacé par des pipes, le FIL RESTE LE MÊME :
 * Content-Length + JSON, sérialisation/désérialisation, concurrence).
 */
public object OutilsHarnais {
    /** Racine de travail commune à tous les groupes de cette JVM. */
    public val racineTravail: File =
        File(System.getProperty("java.io.tmpdir"), "harnais-lsp-${System.nanoTime()}").apply {
            mkdirs()
        }

    /** Index disque PARTAGÉ entre les groupes (segments par empreinte → redémarrages chauds). */
    public val dossierIndexPartage: File = File(racineTravail, "index-partage").apply { mkdirs() }

    /** Segments kotlin-appelables partagés (même raisonnement). */
    public val dossierIndexKotlinPartage: File = File(racineTravail, "index-kotlin-partage").apply { mkdirs() }

    /** Le jar kotlin-stdlib du classpath de test (peut être null → régime dégradé). */
    public val stdlib: File? =
        System
            .getProperty("java.class.path")
            .orEmpty()
            .split(File.pathSeparator)
            .firstOrNull { it.endsWith(".jar") && it.contains("kotlin-stdlib") }
            ?.let(::File)
            ?.takeIf { it.isFile }

    /**
     * Le android.jar de la plateforme (miroir du classpath de production
     * CodeIDE — la plateforme est TOUJOURS dans les entrées réelles).
     * Localisé depuis le local.properties du dépôt (sdk.dir).
     */
    public val androidJar: File? =
        run {
            var dossier = File(System.getProperty("user.dir").orEmpty())
            repeat(8) {
                val proprietes = File(dossier, "local.properties")
                if (proprietes.isFile) {
                    val sdk =
                        proprietes
                            .readLines()
                            .firstOrNull { it.startsWith("sdk.dir=") }
                            ?.substringAfter("=")
                    if (sdk != null) {
                        File(sdk, "platforms/android-37.2/android.jar").takeIf { it.isFile }?.let { return@run it }
                    }
                }
                dossier = dossier.parentFile ?: return@run null
            }
            null
        }

    /**
     * Prépare le projet de test sur disque (racines sources scannées par
     * le moteur — le scénario multi-fichiers en dépend).
     */
    public fun preparerProjet(racine: File) {
        val src = File(racine, "src").apply { mkdirs() }
        File(src, "jo/demo").apply { mkdirs() }
        File(src, "jo/demo/Calcul.kt").writeText(CALCUL_KT)
        File(src, "jo/demo/Main.kt").writeText(MAIN_KT)
        File(src, "autre").apply { mkdirs() }
        File(src, "autre/Homonymes.kt").writeText(HOMONYMES_KT)
    }

    // ── Projet JAVA (découverte D6, ADR 0215) ─────────────────────────

    /** Le cas du propriétaire : deux fichiers du MÊME PAQUET + un
     *  ViewBinding DÉJÀ GÉNÉRÉ dans la racine `generated` du modèle. */
    public val MAIN_ACTIVITY_JAVA: String =
        """
        package com.ex.app;

        import com.ex.app.databinding.ActivityMainBinding;

        public class MainActivity extends android.app.Activity {
            private final Greeter greeter = new Greeter();
            private ActivityMainBinding binding;

            @Override
            public void onCreate(android.os.Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                binding = ActivityMainBinding.inflate(getLayoutInflater());
                setContentView(binding.getRoot());
                greeter.saluer();
            }
        }
        """.trimIndent() + "\n"

    public val GREETER_JAVA: String =
        """
        package com.ex.app;

        public class Greeter {
            public String saluer() {
                return "Salut";
            }
        }
        """.trimIndent() + "\n"

    /** ViewBinding façon AGP (généré dans `<pkg>.databinding`). */
    public val ACTIVITY_MAIN_BINDING_JAVA: String =
        """
        package com.ex.app.databinding;

        public final class ActivityMainBinding {
            public android.widget.TextView root;

            public static ActivityMainBinding inflate(android.view.LayoutInflater inflater) {
                return new ActivityMainBinding();
            }

            public android.widget.TextView getRoot() {
                return root;
            }
        }
        """.trimIndent() + "\n"

    /**
     * Prépare le projet JAVA de test sur disque — `src` (principal) et
     * `generated` (racine des sources générées, ViewBinding compris),
     * le MÊME montage que le modèle v8 du tooling.
     */
    public fun preparerProjetJava(racine: File) {
        val src = File(racine, "src/com/ex/app").apply { mkdirs() }
        File(src, "MainActivity.java").writeText(MAIN_ACTIVITY_JAVA)
        File(src, "Greeter.java").writeText(GREETER_JAVA)
        val generated = File(racine, "generated/com/ex/app/databinding").apply { mkdirs() }
        File(generated, "ActivityMainBinding.java").writeText(ACTIVITY_MAIN_BINDING_JAVA)
    }

    /**
     * Écrit le `ClasspathResult` JSON du projet Java (module `:app` :
     * src + generated + android.jar de la plateforme) — le client
     * harnais le pousse au serveur stdio par la notification
     * `harnais/appliquerClasspath` APRÈS le didOpen (le live-republish
     * est la PREUVE que le routage D6 nourrit le moteur du module).
     */
    public fun ecrireClasspathJava(
        racine: File,
        androidJar: File?,
    ): File {
        val resultat =
            jo.codeide.tooling.protocol.ClasspathResult(
                id = "harnais-java",
                protocolVersion = 1,
                projectDir = racine.absolutePath,
                modules =
                    listOf(
                        jo.codeide.tooling.protocol.ClasspathModule(
                            name = ":app",
                            sourceDirs = listOf(File(racine, "src").absolutePath),
                            entries =
                                listOfNotNull(
                                    androidJar?.let {
                                        jo.codeide.tooling.protocol.ClasspathEntry(
                                            it.absolutePath,
                                            jo.codeide.tooling.protocol.ClasspathKind.JAR,
                                            "platform",
                                        )
                                    },
                                ),
                            fichiersGeneres = listOf(File(racine, "generated").absolutePath),
                            androidJar = androidJar?.absolutePath,
                        ),
                    ),
            )
        val fichier = File(racine, "classpath-harnais.json")
        fichier.writeText(
            kotlinx.serialization.json.Json.encodeToString(
                jo.codeide.tooling.protocol.ClasspathResult
                    .serializer(),
                resultat,
            ),
        )
        return fichier
    }

    /**
     * Démarre le serveur LSP JAVA (stdio) et négocie
     * l'initialisation — miroir de [demarrerServeur] pour la face
     * Java de la découverte D6 (projets Java réels : classement par
     * module, ViewBinding, same-package).
     */
    public fun demarrerServeurJava(
        nomGroupe: String,
        initialiser: Boolean = true,
    ): Connexion {
        val racine = File(racineTravail, "projet-java-$nomGroupe").apply { mkdirs() }
        preparerProjetJava(racine)
        val javaExe = File(System.getProperty("java.home"), "bin/java")
        val classpath = System.getProperty("java.class.path")
        val constructeur =
            ProcessBuilder(
                javaExe.absolutePath,
                "-Xmx640m",
                "-Dfile.encoding=UTF-8",
                "-cp",
                classpath,
                "jo.codeide.lsp.host.harnais.ServeurStdioJava",
            )
        constructeur.environment()["HARNAIS_RACINE"] = racine.absolutePath
        androidJar?.let { constructeur.environment()["HARNAIS_ANDROID_JAR"] = it.absolutePath }
        constructeur.environment()["HARNAIS_INDEX"] = dossierIndexPartage.absolutePath
        val processus = constructeur.start()
        val stderr = StringBuilder()
        thread(isDaemon = true, name = "stderr-java-$nomGroupe") {
            processus.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) }
        }
        val client = ClientCollecteur()
        val lanceur =
            org.eclipse.lsp4j.jsonrpc.Launcher
                .Builder<org.eclipse.lsp4j.services.LanguageServer>()
                .setLocalService(client)
                .setRemoteInterface(org.eclipse.lsp4j.services.LanguageServer::class.java)
                .setInput(processus.inputStream)
                .setOutput(processus.outputStream)
                .create()
        lanceur.startListening()
        val connexion = Connexion(processus, lanceur.remoteProxy, lanceur.remoteEndpoint, client, racine, stderr)
        if (initialiser) {
            initialiser(connexion)
        }
        return connexion
    }

    public val CALCUL_KT: String =
        """
        package jo.demo

        /**
         * Additionne [a] et [b].
         */
        fun additionner(a: Int, b: Int): Int {
            return a + b
        }

        interface Forme {
            fun aire(): Double
            val nom: String
        }

        class Cercle(private val rayon: Double) : Forme {
            override fun aire(): Double = 3.14159 * rayon * rayon
            override val nom: String = "cercle"
            fun perimetre(): Double = 2 * 3.14159 * rayon
        }

        class Carre(var cote: Double) : Forme {
            override fun aire(): Double = cote * cote
            override val nom: String = "carre"
        }

        const val CONSTANTE_GLOBALE: Int = 42
        """.trimIndent() + "\n"

    public val MAIN_KT: String =
        """
        package jo.demo

        fun principal() {
            val total = additionner(1, 2)
            val forme: Forme = Cercle(2.0)
            println(total)
            println(forme.aire())
        }
        """.trimIndent() + "\n"

    public val HOMONYMES_KT: String =
        """
        package autre

        interface Forme

        class Rond : Forme
        """.trimIndent() + "\n"

    /**
     * Interface DISTANTE du client harnais — miroir EXACT du pattern
     * `CodeIdeLanguageServer` de cel-lsp (le client production) : les
     * méthodes personnalisées vivent AU SOMMET de l'interface avec
     * `@JsonRequest` (PAS de redéclaration covariante de
     * `getTextDocumentService` — lsp4j 0.22 lève « Duplicate RPC
     * method » sur ce pattern, le check est apparu en 0.22). Sans
     * cette interface, la RÉPONSE d'une requête par nom serait
     * désérialisée en null (méthode inconnue du proxy LanguageServer
     * standard) — le serveur Kotlin en est le miroir au fil : même
     * méthode, mêmes paramètres, même DTO `NavigationTarget` (ADR
     * 0210).
     */
    internal interface NavServeurKotlinHarnais : org.eclipse.lsp4j.services.LanguageServer {
        /**
         * Les cibles Super au caret — miroir exact du câble cel-lsp
         * (`textDocument/superDefinition`, DTO `SuperNavTarget` ↔
         * `NavigationTarget`, champs publics Gson identiques).
         */
        @org.eclipse.lsp4j.jsonrpc.services.JsonRequest("textDocument/superDefinition")
        fun superDefinition(
            params: org.eclipse.lsp4j.TextDocumentPositionParams,
        ): java.util.concurrent.CompletableFuture<List<jo.lspjava.api.NavigationTarget>>
    }

    /** Client LSP collecteur : publications, messages, attente polie. */
    public class ClientCollecteur : LanguageClient {
        public val publications: CopyOnWriteArrayList<PublishDiagnosticsParams> = CopyOnWriteArrayList()
        public val messages: CopyOnWriteArrayList<MessageParams> = CopyOnWriteArrayList()

        override fun telemetryEvent(objet: Any?) {
            // La télémétrie n'est pas auditée ici.
        }

        override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) {
            publications.add(diagnostics)
        }

        override fun showMessage(params: MessageParams) {
            messages.add(params)
        }

        override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> =
            CompletableFuture.completedFuture(MessageActionItem())

        override fun logMessage(params: MessageParams) {
            messages.add(params)
        }

        /** Dernière publication de [uri] satisfaisant [condition], avec patience. */
        public fun attendre(
            uri: String,
            delaiMs: Long = 20_000,
            condition: (PublishDiagnosticsParams) -> Boolean = { true },
        ): PublishDiagnosticsParams {
            val fin = System.currentTimeMillis() + delaiMs
            while (System.currentTimeMillis() < fin) {
                publications.filter { it.uri == uri && condition(it) }.lastOrNull()?.let { return it }
                Thread.sleep(25)
            }
            throw AssertionError(
                "aucune publication de $uri après ${delaiMs}ms (reçues : ${publications.map {
                    "${it.uri}#${it.version}"
                }})",
            )
        }

        /** Toutes les publications reçues pour [uri]. */
        public fun publicationsDe(uri: String): List<PublishDiagnosticsParams> = publications.filter { it.uri == uri }

        /** Le diagnostic unique de code [code] dans la dernière publication de [uri]. */
        public fun unique(
            uri: String,
            code: String,
            condition: (PublishDiagnosticsParams) -> Boolean = { true },
        ): Diagnostic {
            val publication = attendre(uri) { condition(it) }
            val trouves = publication.diagnostics.filter { it.code?.left == code }
            assertTrue(trouves.isNotEmpty(), "diagnostic $code attendu dans ${publication.diagnostics}")
            return trouves.first()
        }
    }

    /** Une connexion LSP vivante : processus + proxy typé + client collecteur. */
    public class Connexion(
        public val processus: Process,
        public val distant: LanguageServer,
        public val endpoint: org.eclipse.lsp4j.jsonrpc.Endpoint,
        public val client: ClientCollecteur,
        public val racine: File,
        public val stderr: StringBuilder,
    ) {
        public fun arreter() {
            runCatching { distant.shutdown().get(20, TimeUnit.SECONDS) }
            runCatching { distant.exit() }
            runCatching { processus.outputStream.close() }
            if (!processus.waitFor(20, TimeUnit.SECONDS)) {
                processus.destroyForcibly()
                throw AssertionError("le serveur ne s'est pas arrêté en 20 s\n${stderr.trop}")
            }
        }

        public fun detruire() {
            runCatching { processus.destroyForcibly() }
        }
    }

    /** Queue stderr tronquée pour les messages d'échec. */
    public val StringBuilder.trop: String
        get() = if (length > 4000) "…" + substring(length - 4000) else toString()

    /** Démarre un serveur stdio et négocie l'initialisation. */
    public fun demarrerServeur(
        nomGroupe: String,
        initialiser: Boolean = true,
    ): Connexion {
        val racine = File(racineTravail, "projet-$nomGroupe").apply { mkdirs() }
        preparerProjet(racine)
        val javaExe = File(System.getProperty("java.home"), "bin/java")
        val classpath = System.getProperty("java.class.path")
        val constructeur =
            ProcessBuilder(
                javaExe.absolutePath,
                "-Xmx640m",
                "-Dfile.encoding=UTF-8",
                "-cp",
                classpath,
                "jo.codeide.lsp.host.harnais.ServeurStdio",
            )
        constructeur.environment()["HARNAIS_RACINE"] = racine.absolutePath
        stdlib?.let { constructeur.environment()["HARNAIS_STDLIB"] = it.absolutePath }
        androidJar?.let { constructeur.environment()["HARNAIS_ANDROID_JAR"] = it.absolutePath }
        constructeur.environment()["HARNAIS_INDEX"] = dossierIndexPartage.absolutePath
        constructeur.environment()["HARNAIS_INDEX_KOTLIN"] = dossierIndexKotlinPartage.absolutePath
        constructeur.environment()["JO_TEST_JDK_HOME"] = System.getProperty("java.home")
        val processus = constructeur.start()
        val stderr = StringBuilder()
        thread(isDaemon = true, name = "stderr-$nomGroupe") {
            processus.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) }
        }
        val client = ClientCollecteur()
        // Miroir du câble cel-lsp : le client CONNAÎT l'interface
        // distante étendue (méthodes personnalisées comprises) — la
        // désérialisation des réponses par nom passe par LA carte de
        // types du remote interface, pas celle du seul LanguageServer.
        val lanceur =
            org.eclipse.lsp4j.jsonrpc.Launcher
                .Builder<NavServeurKotlinHarnais>()
                .setLocalService(client)
                .setRemoteInterface(NavServeurKotlinHarnais::class.java)
                .setInput(processus.inputStream)
                .setOutput(processus.outputStream)
                .create()
        lanceur.startListening()
        val connexion = Connexion(processus, lanceur.remoteProxy, lanceur.remoteEndpoint, client, racine, stderr)
        if (initialiser) {
            initialiser(connexion)
        }
        return connexion
    }

    /** Négociation initialize standard. */
    public fun initialiser(connexion: Connexion) {
        val params = org.eclipse.lsp4j.InitializeParams()
        params.workspaceFolders =
            listOf(org.eclipse.lsp4j.WorkspaceFolder(connexion.racine.toURI().toString(), "harnais"))
        val resultat = connexion.distant.initialize(params).get(120, TimeUnit.SECONDS)
        assertNotNull(resultat.capabilities, "capabilities absentes de la réponse initialize")
        connexion.distant.initialized(org.eclipse.lsp4j.InitializedParams())
    }

    /** Ouvre un document du projet (URI disque) et attend sa première publication. */
    public fun ouvrir(
        connexion: Connexion,
        cheminRelatif: String,
        version: Int = 1,
    ): String {
        val uri = File(connexion.racine, cheminRelatif).toURI().toString()
        val texte = File(connexion.racine, cheminRelatif).readText()
        return ouvrir(connexion, uri, texte, version)
    }

    /** Ouvre un document VIRTUEL (texte arbitraire) et attend sa publication. */
    public fun ouvrir(
        connexion: Connexion,
        uri: String,
        texte: String,
        version: Int = 1,
    ): String {
        connexion.distant.textDocumentService.didOpen(
            org.eclipse.lsp4j.DidOpenTextDocumentParams(
                org.eclipse.lsp4j.TextDocumentItem(uri, "kotlin", version, texte),
            ),
        )
        return uri
    }

    /** didChange en synchronisation complète + attente de la publication [version]. */
    public fun modifier(
        connexion: Connexion,
        uri: String,
        texte: String,
        version: Int,
    ) {
        connexion.distant.textDocumentService.didChange(
            org.eclipse.lsp4j.DidChangeTextDocumentParams(
                org.eclipse.lsp4j.VersionedTextDocumentIdentifier(uri, version),
                listOf(org.eclipse.lsp4j.TextDocumentContentChangeEvent(texte)),
            ),
        )
        client(connexion).attendre(uri) { (it.version ?: -1) >= version }
    }

    public fun client(connexion: Connexion): ClientCollecteur = connexion.client

    /** Position (ligne, colonne UTF-16) d'un décalage dans un texte (miroir de test). */
    public fun positionDe(
        texte: String,
        decalage: Int,
    ): Position {
        var ligne = 0
        var debutLigne = 0
        for (index in 0 until decalage.coerceAtMost(texte.length)) {
            if (texte[index] == '\n') {
                ligne++
                debutLigne = index + 1
            }
        }
        return Position(ligne, decalage - debutLigne)
    }

    /** Décalage d'une position LSP (UTF-16) — miroir de test, tolérant aux excursions. */
    public fun decalageDe(
        texte: String,
        position: Position,
    ): Int {
        val lignes = texte.split("\n")
        var decalage = 0
        for (index in 0 until position.line.coerceAtMost(lignes.size)) {
            decalage += lignes[index].length + 1
        }
        if (position.line < lignes.size) {
            decalage += position.character.coerceAtMost(lignes[position.line].length)
        }
        return decalage
    }

    /** Applique des TextEdits LSP à un texte (ordre décroissant, plages indépendantes). */
    public fun appliquer(
        texte: String,
        editions: List<TextEdit>,
    ): String {
        data class Edition(
            val debut: Int,
            val fin: Int,
            val nouveau: String,
        )

        val converties =
            editions
                .map { edition ->
                    Edition(
                        decalageDe(texte, edition.range.start),
                        decalageDe(texte, edition.range.end),
                        edition.newText,
                    )
                }.sortedByDescending { it.debut }
        var resultat = texte
        for (edition in converties) {
            resultat = resultat.substring(0, edition.debut) + edition.nouveau + resultat.substring(edition.fin)
        }
        return resultat
    }

    /** La plage LSP (lignes uniquement) d'une édition pour les assertions de plage. */
    public fun lignesDe(
        texte: String,
        plage: Range,
    ): IntRange {
        val debut = decalageDe(texte, plage.start)
        val fin = decalageDe(texte, plage.end)
        return positionDe(texte, debut).line..positionDe(texte, fin.let { if (it > debut) it - 1 else it }).line
    }

    /** Recherche l'offset d'une sous-chaîne, refus explicite si absente. */
    public fun offsetDe(
        texte: String,
        aiguille: String,
        occurrence: Int = 0,
    ): Int {
        var decalage = -1
        repeat(occurrence + 1) {
            decalage = texte.indexOf(aiguille, decalage + 1)
            assertTrue(decalage >= 0, "« $aiguille » introuvable (occurrence $occurrence)")
        }
        return decalage
    }

    /** Vrai ssi une capacité booléenne de ServerCapabilities est annoncée (Either gauche vrai). */
    public fun annonce(e: org.eclipse.lsp4j.jsonrpc.messages.Either<Boolean, *>?): Boolean =
        e?.isLeft == true && e.left == true

    /** Le premier diagnostic de code donné dans une publication. */
    public fun diagnosticDe(
        publication: PublishDiagnosticsParams,
        code: String,
    ): Diagnostic? = publication.diagnostics.firstOrNull { it.code?.left == code }

    /** Égalité de plage en positions, message clair. */
    public fun assertPlage(
        attendue: Pair<Pair<Int, Int>, Pair<Int, Int>>,
        plage: Range,
        contexte: String,
    ) {
        assertEquals(attendue.first.first, plage.start.line, "$contexte — ligne début")
        assertEquals(attendue.first.second, plage.start.character, "$contexte — colonne début")
        assertEquals(attendue.second.first, plage.end.line, "$contexte — ligne fin")
        assertEquals(attendue.second.second, plage.end.character, "$contexte — colonne fin")
    }

    /**
     * Requête la méthode LSP PERSONNALISÉE `textDocument/superDefinition`
     * (ADR 0210) par le TRANSPORT RÉEL, appel TYPÉ sur le proxy —
     * miroir exact du câble cel-lsp (`getExtendedServer()
     * .superDefinition(params)` sur `CodeIdeLanguageServer`) : le proxy
     * du harnais porte l'interface étendue [NavServeurKotlinHarnais],
     * la désérialisation rend des `NavigationTarget` typés (champs
     * publics Gson).
     *
     * @return les cibles (chemin ABSOLU + plage du nom en offsets + libellé)
     */
    public fun superDefinitionParTransport(
        connexion: Connexion,
        uri: String,
        position: Position,
    ): List<jo.lspjava.api.NavigationTarget> {
        val proxy =
            connexion.distant as? NavServeurKotlinHarnais
                ?: return emptyList()
        val params =
            org.eclipse.lsp4j.TextDocumentPositionParams(
                org.eclipse.lsp4j.TextDocumentIdentifier(uri),
                position,
            )
        return proxy.superDefinition(params).get(120, TimeUnit.SECONDS) ?: emptyList()
    }
}
