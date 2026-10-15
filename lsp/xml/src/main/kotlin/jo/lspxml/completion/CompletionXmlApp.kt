package jo.lspxml.completion

import jo.lspxml.api.GenreCompletionXml
import jo.lspxml.api.PropositionCompletionXml
import jo.lspxml.context.Contexte

/**
 * Complétion du genre XML_APP (XP12, plan de stabilisation LSP §3.3
 * S2 — « genres étendus … res/xml ») : fichiers de CONFIGURATION
 * d'application — `appwidget-provider` (widgets), `shortcuts`
 * (raccourcis launcher), `paths` (FileProvider), sécurité réseau
 * (`network-security-config`), règles de sauvegarde
 * (`data-extraction-rules`, `full-backup-content`).
 *
 * Catalogue FIXE par la documentation (motif XE5). Particularité :
 * ces familles vivent sous DES espaces de noms hétérogènes —
 * `android:` (appwidget, shortcuts), SANS préfixe (FileProvider,
 * sécurité réseau, sauvegardes) — les attributs portent leur nom
 * COMPLET comme les contributeurs voisins. Conservateur (X4) :
 * racine inconnue du catalogue = silence (un `res/xml` peut porter
 * n'importe quelle config personnalisée — ne rien inventer).
 */
internal object CompletionXmlApp {
    /** Racines de configuration connues (nom → détail). */
    private val BALISES: Map<String, String> =
        mapOf(
            "appwidget-provider" to "fournisseur de widget (minWidth, initialLayout…)",
            "shortcuts" to "raccourcis du launcher (shortcut → intent)",
            "shortcut" to "raccourci (shortcutId, shortcutShortLabel)",
            "intent" to "intention lancée par le raccourci (action, targetClass)",
            "paths" to "chemins partagés du FileProvider (meta-data)",
            "files-path" to "chemin interne (Context.getFilesDir)",
            "cache-path" to "chemin cache (Context.getCacheDir)",
            "external-path" to "racine du stockage externe",
            "external-files-path" to "fichiers externes de l'application",
            "external-cache-path" to "cache externe de l'application",
            "external-media-path" to "média externe de l'application (API 21)",
            "network-security-config" to "configuration de sécurité réseau (API 24)",
            "base-config" to "règles par défaut de l'application",
            "domain-config" to "règles par domaine (API 24)",
            "domain" to "domaine couvert (name)",
            "trust-anchors" to "ancres de confiance (certificats)",
            "certificates" to "certificat épinglé (src)",
            "debug-overrides" to "dérogations en mode debug",
            "data-extraction-rules" to "règles de sauvegarde/transfert (API 31)",
            "cloud-backup" to "règles de sauvegarde cloud",
            "device-transfer" to "règles de transfert d'appareil",
            "include" to "chemin INCLUS (domain, path)",
            "exclude" to "chemin EXCLU (domain, path)",
            "full-backup-content" to "règles de sauvegarde historiques (API 23)",
        )

    /** Enfants ADMISSIBLES selon le parent (null = racine du fichier). */
    private val ENFANTS: Map<String, List<String>> =
        mapOf(
            "shortcuts" to listOf("shortcut"),
            "shortcut" to listOf("intent"),
            "paths" to listOf(
                "files-path", "cache-path", "external-path", "external-files-path",
                "external-cache-path", "external-media-path",
            ),
            "network-security-config" to listOf("base-config", "domain-config", "debug-overrides"),
            "base-config" to listOf("trust-anchors"),
            "domain-config" to listOf("domain", "trust-anchors"),
            "trust-anchors" to listOf("certificates"),
            "data-extraction-rules" to listOf("cloud-backup", "device-transfer", "include", "exclude"),
            "cloud-backup" to listOf("include", "exclude"),
            "device-transfer" to listOf("include", "exclude"),
            "full-backup-content" to listOf("include", "exclude"),
        )

    /**
     * Racines LÉGALES d'un `res/xml` — les autres entrées de
     * [BALISES] ne vivent que comme enfants (shortcut, paths…).
     */
    private val RACINES: Set<String> =
        setOf(
            "appwidget-provider", "shortcuts", "paths", "network-security-config",
            "data-extraction-rules", "full-backup-content",
        )

    /** Attributs d'un chemin FileProvider (SANS préfixe de namespace). */
    private val ATTRIBUTS_CHEMIN: List<Pair<String, String>> =
        listOf(
            "name" to "nom du chemin (référencé dans les URI)",
            "path" to "sous-chemin partagé (vide = racine)",
        )

    /** Attributs d'une règle de sauvegarde include/exclude. */
    private val ATTRIBUTS_SAUVEGARDE: List<Pair<String, String>> =
        listOf(
            "domain" to "domaine : root, database, sharedpref, external, file, device_root…",
            "path" to "chemin visé dans le domaine",
            "requireFlags" to "drapeaux requis (clientSideEncryption, deviceToDeviceTransfer)",
        )

    /** Attributs PAR BALISE (nom complet → détail court). */
    private val ATTRIBUTS: Map<String, List<Pair<String, String>>> =
        mapOf(
            "appwidget-provider" to listOf(
                "android:minWidth" to "largeur minimale (dp)",
                "android:minHeight" to "hauteur minimale (dp)",
                "android:minResizeWidth" to "largeur minimale redimensionnable",
                "android:minResizeHeight" to "hauteur minimale redimensionnable",
                "android:maxResizeWidth" to "largeur maximale redimensionnable (API 31)",
                "android:maxResizeHeight" to "hauteur maximale redimensionnable (API 31)",
                "android:targetCellWidth" to "largeur cible en cellules (API 31)",
                "android:targetCellHeight" to "hauteur cible en cellules (API 31)",
                "android:updatePeriodMillis" to "période de mise à jour (ms, min 1800000)",
                "android:initialLayout" to "layout affiché à l'ajout (@layout/…)",
                "android:previewImage" to "aperçu du sélecteur (@drawable/…)",
                "android:previewLayout" to "layout d'aperçu (API 31)",
                "android:configure" to "activité de configuration (FQN)",
                "android:resizeMode" to "redimension : horizontal, vertical, none",
                "android:widgetCategory" to "emplacement : home_screen, searchbox",
                "android:description" to "description accessible (API 31)",
            ),
            "shortcut" to listOf(
                "android:shortcutId" to "identifiant stable du raccourci",
                "android:shortcutShortLabel" to "libellé court (@string/…)",
                "android:shortcutLongLabel" to "libellé long (@string/…)",
                "android:shortcutDisabledMessage" to "message si désactivé",
                "android:icon" to "icône du raccourci (@drawable/…)",
                "android:enabled" to "activé",
            ),
            "intent" to listOf(
                "android:action" to "action (android.intent.action.…)",
                "android:data" to "donnée (URI)",
                "android:mimeType" to "type MIME",
                "android:targetPackage" to "paquet cible",
                "android:targetClass" to "classe cible (FQN)",
            ),
            "files-path" to ATTRIBUTS_CHEMIN,
            "cache-path" to ATTRIBUTS_CHEMIN,
            "external-path" to ATTRIBUTS_CHEMIN,
            "external-files-path" to ATTRIBUTS_CHEMIN,
            "external-cache-path" to ATTRIBUTS_CHEMIN,
            "external-media-path" to ATTRIBUTS_CHEMIN,
            "base-config" to listOf(
                "cleartextTrafficPermitted" to "HTTP en clair autorisé (true/false)",
            ),
            "domain-config" to listOf(
                "cleartextTrafficPermitted" to "HTTP en clair autorisé (true/false)",
            ),
            "domain" to listOf("name" to "nom du domaine couvert"),
            "certificates" to listOf(
                "src" to "certificat (@raw/…, @system, @user ou ressource)",
                "overridePins" to "épingles DOMINANTES substituées (true/false)",
            ),
            "include" to ATTRIBUTS_SAUVEGARDE,
            "exclude" to ATTRIBUTS_SAUVEGARDE,
        )

    /** Énumérations des attributs CONNUS (nom local → valeurs). */
    private val ENUMERATIONS: Map<String, List<String>> =
        mapOf(
            "resizeMode" to listOf("none", "horizontal", "vertical"),
            "widgetCategory" to listOf("home_screen", "searchbox"),
            "cleartextTrafficPermitted" to listOf("true", "false"),
            "overridePins" to listOf("true", "false"),
            "enabled" to listOf("true", "false"),
        )

    /**
     * Complétion du NOM DE BALISE — racine de config connue ou enfant
     * du parent connu (X4 : racine inconnue = silence — une config
     * personnalisée n'est pas devinable).
     */
    internal fun balises(contexte: Contexte.NomDeBalise): List<PropositionCompletionXml> {
        val parent = contexte.parent
        val admissibles =
            when {
                parent == null -> BALISES.keys.filter { it in RACINES }.sorted()

                ENFANTS.containsKey(parent.nom) -> ENFANTS.getValue(parent.nom)

                else -> return emptyList()
            }
        val prefixe = contexte.prefixeTape
        val propositions = mutableListOf<PropositionCompletionXml>()
        for (nom in admissibles) {
            if (prefixe.isEmpty() || nom.startsWith(prefixe)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = nom,
                        genre = GenreCompletionXml.BALISE,
                        detail = BALISES[nom],
                        texteTri = ClassementXml.cleTri(ClassementXml.rangBalise(nom), nom),
                    ),
                )
            }
        }
        return propositions.sortedBy { it.texteTri }
    }

    /**
     * Complétion du NOM D'ATTRIBUT — PAR BALISE, déclarations xmlns:
     * en frappe de préfixe.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — les sorties
     * sont les RÉGIMES distincts (xmlns en frappe, balise inconnue) —
     * chacun porte sa décision de silence X4.
     */
    @Suppress("ReturnCount")
    internal fun attributs(contexte: Contexte.NomAttribut): List<PropositionCompletionXml> {
        val prefixe = contexte.prefixeTape
        if (CompletionXml.frappeDeDeclarationXmlns(prefixe)) {
            return CompletionXml.attributsXmlns(prefixe)
        }
        val element = contexte.element ?: return emptyList()
        val candidats = ATTRIBUTS[element.nom] ?: return emptyList()
        val propositions = mutableListOf<PropositionCompletionXml>()
        for ((nom, detail) in candidats) {
            if (CompletionXml.correspondancePrefixe(prefixe, nom)) {
                propositions.add(
                    PropositionCompletionXml(
                        libelle = nom,
                        genre = GenreCompletionXml.ATTRIBUT,
                        detail = detail,
                        texteTri = ClassementXml.cleTri(ClassementXml.rangAttribut(nom), nom),
                    ),
                )
            }
        }
        return propositions.sortedBy { it.texteTri }
    }

    /** Complétion de VALEUR — énumérations de configuration connues. */
    internal fun valeurs(contexte: Contexte.Valeur): List<PropositionCompletionXml> {
        val valeurs = ENUMERATIONS[contexte.attribut.substringAfter(':')] ?: return emptyList()
        val prefixe = contexte.prefixeTape
        return valeurs
            .filter { prefixe.isEmpty() || it.startsWith(prefixe) }
            .map { nom ->
                PropositionCompletionXml(
                    libelle = nom,
                    genre = GenreCompletionXml.VALEUR_ENUM,
                    detail = "enum",
                    texteTri = ClassementXml.cleTri(ClassementXml.RANG_VALEUR, nom),
                )
            }
    }
}
