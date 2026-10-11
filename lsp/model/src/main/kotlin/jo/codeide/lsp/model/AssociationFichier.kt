package jo.codeide.lsp.model

/**
 * Association fichier → module (prompt 2 §7.1, étape 1) : par la racine de
 * sourceset la PLUS PROFONDE (correctif du bug FQCN de l'ancienne version
 * — « la racine projet ajoutée avant le vrai sourceset »), FQCN dérivé du
 * chemin sous la racine et RECROPÉ avec la déclaration `package` de la
 * source (écart = diagnostic explicite, pas un échec silencieux).
 */
public object AssociationFichier {
    /** Le fichier est-il sous une racine de [module] ? Racine si oui. */
    private fun racineDe(
        chemin: String,
        module: ModuleLsp,
    ): String? {
        val normalise = chemin.replace('\\', '/')
        val racines =
            (module.sources.principal + module.sources.test + module.sources.generes)
                .map { it.replace('\\', '/') }
        return racines
            .filter { racine -> normalise == racine || normalise.startsWith("$racine/") }
            .maxByOrNull { it.length }
    }

    /**
     * Module d'un fichier : celui dont la racine de sourceset la plus
     * profonde le contient — parmi les modules NON ignorés. `null` si
     * aucune racine ne le couvre (fichier hors sourcesets connus).
     */
    public fun associer(
        projet: ProjetLsp,
        chemin: String,
    ): ModuleLsp? {
        val normalise = chemin.replace('\\', '/')
        return projet.modules
            .filter { !it.ignore }
            .mapNotNull { module -> racineDe(normalise, module)?.let { racine -> racine to module } }
            .maxByOrNull { (racine, _) -> racine.length }
            ?.second
    }

    /**
     * FQCN attendu d'un fichier : chemin relatif sous sa racine de
     * sourceset (la plus profonde du module), séparateurs → points,
     * extension retirée. `null` si le fichier n'est sous aucune racine du
     * module. Retours anticipés : chaque garde a sa propre condition.
     */
    @Suppress("ReturnCount")
    public fun fqcnAttendu(
        chemin: String,
        module: ModuleLsp,
    ): String? {
        val normalise = chemin.replace('\\', '/')
        val racine = racineDe(normalise, module) ?: return null
        val relatif = normalise.removePrefix("$racine/").removePrefix(racine)
        if (relatif.isBlank()) {
            return null
        }
        val sansExtension = relatif.substringBeforeLast('.')
        return sansExtension.replace('/', '.').replace('\\', '.')
    }

    /**
     * Écart entre la déclaration `package` d'une source et son FQCN
     * attendu : message de diagnostic explicite, `null` si cohérent.
     * Une source sans déclaration de package sous une racine elle-même
     * non racine de package est un écart (le bug historique « 31
     * diagnostics sur un fichier de 349 caractères » venait exactement de
     * là).
     */
    public fun ecartFqcn(
        chemin: String,
        packageDeclare: String?,
        module: ModuleLsp,
    ): String? {
        val fqcn = fqcnAttendu(chemin, module) ?: return null
        val paquetAttendu = fqcn.substringBeforeLast('.', "")
        val declare = packageDeclare?.trim().orEmpty()
        return when {
            declare == paquetAttendu -> {
                null
            }

            declare.isEmpty() -> {
                "aucune déclaration package alors que le sourceset attend `$paquetAttendu` ($chemin)"
            }

            else -> {
                "la déclaration package `$declare` ne correspond pas au sourceset " +
                    "(attendu `$paquetAttendu`, $chemin)"
            }
        }
    }
}
