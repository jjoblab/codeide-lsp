package jo.lspxml.rules

import jo.lspxml.api.CodesDiagnosticsXml
import jo.lspxml.api.DiagnosticXml
import jo.lspxml.api.SeveriteXml
import jo.lspxml.parse.DocumentXml
import jo.lspxml.parse.XmlElement

/**
 * Règles de MANIFEST (XE4, LSP_XML.md §5 — `xml.manifestSansPackage`,
 * `xml.composantSansNom`) — le genre MANIFEST est déduit de l'URI
 * (finissant par `AndroidManifest.xml`) ; conservatoire X4 : un
 * manifest non reconnu comme tel ne passe jamais ici.
 */
internal object ReglesManifeste {
    /** Composants d'`<application>` exigeant `android:name`. */
    private val COMPOSANTS: Set<String> =
        setOf("activity", "activity-alias", "service", "receiver", "provider")

    /**
     * Diagnostique un manifest — ordre du parcours ; la SESSION trie
     * par position.
     */
    internal fun diagnostiquer(document: DocumentXml): List<DiagnosticXml> {
        val diagnostics = mutableListOf<DiagnosticXml>()
        val manifest =
            document.racines.firstOrNull { it.nom == "manifest" } ?: return diagnostics
        regleSansPackage(manifest, diagnostics)
        regleComposantSansNom(manifest, diagnostics)
        return diagnostics
    }

    /**
     * `xml.manifestSansPackage` : la racine `<manifest>` sans attribut
     * `package` (sans préfixe — AGP tolère le déplacement en Gradle
     * mais un manifest SANS package reste inutilisable seul).
     */
    private fun regleSansPackage(
        manifest: XmlElement,
        diagnostics: MutableList<DiagnosticXml>,
    ) {
        if (manifest.attribut("package") == null) {
            diagnostics.add(
                DiagnosticXml(
                    code = CodesDiagnosticsXml.MANIFEST_SANS_PACKAGE,
                    severite = SeveriteXml.ERREUR,
                    message = "<manifest> sans attribut package",
                    debut = manifest.plageNom.debut,
                    fin = manifest.plageNom.fin,
                ),
            )
        }
    }

    /**
     * `xml.composantSansNom` : composant d'`<application>` (activity,
     * service, receiver, provider, activity-alias) sans `android:name`
     * — conservatoire : les BALISES inconnues ne sont pas concernées
     * (ce n'est pas un composant déclaré).
     */
    private fun regleComposantSansNom(
        manifest: XmlElement,
        diagnostics: MutableList<DiagnosticXml>,
    ) {
        for (application in manifest.enfants.filter { it.nom == "application" }) {
            for (composant in application.enfants) {
                val estComposant = composant.nom in COMPOSANTS
                if (estComposant && composant.attribut("android:name") == null) {
                    diagnostics.add(
                        DiagnosticXml(
                            code = CodesDiagnosticsXml.COMPOSANT_SANS_NOM,
                            severite = SeveriteXml.ERREUR,
                            message = "<${composant.nom}> sans attribut android:name",
                            debut = composant.plageNom.debut,
                            fin = composant.plageNom.fin,
                        ),
                    )
                }
            }
        }
    }
}
