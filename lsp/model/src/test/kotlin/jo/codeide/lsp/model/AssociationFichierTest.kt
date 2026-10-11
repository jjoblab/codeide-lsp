package jo.codeide.lsp.model

import jo.codeide.tooling.protocol.ClasspathEntry
import jo.codeide.tooling.protocol.ClasspathKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Association fichier → module (§7.1) : racine de sourceset la plus
 * profonde, FQCN attendu, recoupement avec la déclaration package —
 * le correctif du bug historique « racine projet avant le sourceset ».
 */
class AssociationFichierTest {
    private fun projetModule(
        racines: List<String>,
        nom: String = ":app",
    ): ProjetLsp {
        val module =
            ModuleLsp(
                nom = nom,
                type = TypeModuleLsp.ANDROID,
                variante = null,
                sources = EnsemblesSources(principal = racines),
                classpath = ClasspathInstantane(entrees = emptyList(), empreinte = "e"),
                dependancesModules = emptyList(),
                androidJar = null,
                ignore = false,
                raisonIgnore = null,
                avertissements = emptyList(),
                empreinte = "m",
            )
        return ProjetLsp(cheminRacine = "/p", modules = listOf(module), empreinte = "p")
    }

    @Test
    fun `la racine la plus profonde l'emporte sur la racine projet`() {
        // Le bug historique : la racine projet (/p/app) était ajoutée AVANT
        // le vrai sourceset (/p/app/src/main/java) — le FQCN devenait
        // app.src.main.java.… (31 diagnostics sur 349 caractères).
        val projet = projetModule(listOf("/p/app", "/p/app/src/main/java"))

        val module = AssociationFichier.associer(projet, "/p/app/src/main/java/com/exemple/MainActivity.java")

        assertEquals(":app", module?.nom)
        assertEquals(
            "com.exemple.MainActivity",
            AssociationFichier.fqcnAttendu("/p/app/src/main/java/com/exemple/MainActivity.java", module!!),
        )
    }

    @Test
    fun `entre deux modules - la racine la plus profonde decide`() {
        val moduleApp =
            ModuleLsp(
                nom = ":app",
                type = TypeModuleLsp.ANDROID,
                variante = null,
                sources = EnsemblesSources(principal = listOf("/p/app/src/main/java")),
                classpath = ClasspathInstantane(emptyList(), "e"),
                dependancesModules = emptyList(),
                androidJar = null,
                ignore = false,
                raisonIgnore = null,
                avertissements = emptyList(),
                empreinte = "a",
            )
        val moduleLib =
            moduleApp.copy(
                nom = ":lib",
                sources = EnsemblesSources(principal = listOf("/p/app/src/main/java/generee/par/lib")),
            )
        val projet = ProjetLsp("/p", listOf(moduleApp, moduleLib), "p")

        val dansLib = AssociationFichier.associer(projet, "/p/app/src/main/java/generee/par/lib/Classe.java")
        val dansApp = AssociationFichier.associer(projet, "/p/app/src/main/java/com/exemple/Main.java")

        assertEquals(":lib", dansLib?.nom)
        assertEquals(":app", dansApp?.nom)
    }

    @Test
    fun `un fichier hors racines n'appartient a aucun module`() {
        val projet = projetModule(listOf("/p/app/src/main/java"))

        assertNull(AssociationFichier.associer(projet, "/p/autre/chose.java"))
        assertNull(AssociationFichier.fqcnAttendu("/p/autre/chose.java", projet.modules.single()))
    }

    @Test
    fun `les racines de test et de generees associent aussi`() {
        val module =
            ModuleLsp(
                nom = ":app",
                type = TypeModuleLsp.ANDROID,
                variante = null,
                sources =
                    EnsemblesSources(
                        principal = listOf("/p/app/src/main/java"),
                        test = listOf("/p/app/src/test/java"),
                        generes = listOf("/p/app/build/generated/ap"),
                    ),
                classpath = ClasspathInstantane(emptyList(), "e"),
                dependancesModules = emptyList(),
                androidJar = null,
                ignore = false,
                raisonIgnore = null,
                avertissements = emptyList(),
                empreinte = "a",
            )
        val projet = ProjetLsp("/p", listOf(module), "p")

        assertEquals(
            ":app",
            AssociationFichier.associer(projet, "/p/app/src/test/java/com/exemple/MainActivityTest.kt")?.nom,
        )
        assertEquals(
            ":app",
            AssociationFichier.associer(projet, "/p/app/build/generated/ap/com/exemple/BuildConfig.java")?.nom,
        )
        assertEquals(
            "com.exemple.BuildConfig",
            AssociationFichier.fqcnAttendu("/p/app/build/generated/ap/com/exemple/BuildConfig.java", module),
        )
    }

    @Test
    fun `ecart de fqcn - declaration package recroisee avec le sourceset`() {
        val module =
            ModuleLsp(
                nom = ":app",
                type = TypeModuleLsp.ANDROID,
                variante = null,
                sources = EnsemblesSources(principal = listOf("/p/app/src/main/java")),
                classpath = ClasspathInstantane(emptyList(), "e"),
                dependancesModules = emptyList(),
                androidJar = null,
                ignore = false,
                raisonIgnore = null,
                avertissements = emptyList(),
                empreinte = "a",
            )
        val chemin = "/p/app/src/main/java/com/exemple/MainActivity.java"

        assertNull("déclaration conforme : pas d'écart", AssociationFichier.ecartFqcn(chemin, "com.exemple", module))
        assertEquals(
            "écart explicite (le bug « app.src.main.java » historique)",
            "la déclaration package `app.src.main.java.com.exemple` ne correspond pas au sourceset " +
                "(attendu `com.exemple`, $chemin)",
            AssociationFichier.ecartFqcn(chemin, "app.src.main.java.com.exemple", module),
        )
        assertEquals(
            "aucune déclaration là où le sourceset en attend une",
            "aucune déclaration package alors que le sourceset attend `com.exemple` ($chemin)",
            AssociationFichier.ecartFqcn(chemin, null, module),
        )
    }
}
