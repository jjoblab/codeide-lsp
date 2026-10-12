package jo.codeide.lsp.host

import jo.codeide.tooling.protocol.ClasspathEntry
import jo.codeide.tooling.protocol.ClasspathKind
import jo.codeide.tooling.protocol.ClasspathModule
import jo.codeide.tooling.protocol.ClasspathResult
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import javax.tools.ToolProvider

// Fixture partagée des tests de :lsp:host (pas une classe de test).

/** Source de la classe `alpha.Alpha` compilée dans le jar fixture. */
internal val SOURCE_ALPHA: String =
    """
package alpha;

public class Alpha {
    public static int COMPTEUR = 3;
    public int instance = 0;
    public void bouger() {}
}
    """.trimIndent()

/** Source de `app.Main` — UTILISE alpha.Alpha (résolution frère/jar). */
internal val SOURCE_MAIN: String =
    """
package app;

import alpha.Alpha;

public class Main {
    void m() {
        Alpha a = new Alpha();
        a.bouger();
    }
}
    """.trimIndent()

/** Source de complétion — curseur juste après « Alp » (position NOM). */
internal val SOURCE_COMPLETION: String =
    """
package app;

public class Compl {
    void m() {
        Alp
    }
}
    """.trimIndent()

/** Source de `app.AndroidMain` — UTILISE android.util.Log (plateforme ADR 0112). */
internal val SOURCE_ANDROID_MAIN: String =
    """
package app;

import android.util.Log;

public class AndroidMain {
    void m() {
        Log.i("CodeIDE", "plateforme");
    }
}
    """.trimIndent()

/** Stub de plateforme : `android.util.Log` compilé au vol (lot h4 écart 1). */
internal val SOURCE_LOG_STUB: String =
    """
package android.util;

public class Log {
    public static int i(String tag, String msg) { return 0; }
}
    """.trimIndent()

/**
 * Traduit l'offset D'UN marqueur en position LSP (ligne, caractère —
 * tous deux 0-based) : la position du curseur juste APRÈS le marqueur.
 */
internal fun positionApres(
    source: String,
    marqueur: String,
): Pair<Int, Int> {
    val offset = source.indexOf(marqueur)
    check(offset >= 0) { "marqueur absent de la source : $marqueur" }
    val ligne = source.substring(0, offset).count { it == '\n' }
    val debutLigne = source.lastIndexOf('\n', offset - 1) + 1
    return Pair(ligne, offset - debutLigne + marqueur.length)
}

/** Racine du projet fixture : racine/lib/src + racine/app/src. */
internal class ProjetFixture(
    racine: Path,
) {
    val dossierLib: Path = Files.createDirectories(racine.resolve("lib/src/alpha"))
    val dossierApp: Path = Files.createDirectories(racine.resolve("app/src/app"))

    /** Jar fixture : alpha.Alpha compilé au vol (javac du JDK de test). */
    fun construireJarFixture(cible: Path): Path {
        val compilateur = ToolProvider.getSystemJavaCompiler()
        org.junit.jupiter.api.Assumptions.assumeTrue(
            compilateur != null,
            "JDK sans compilateur (JRE)",
        )
        val source = dossierLib.resolve("Alpha.java").toFile()
        Files.writeString(source.toPath(), SOURCE_ALPHA)
        val classes = Files.createDirectories(cible.resolve("classes"))
        compilateur.getStandardFileManager(null, null, null).use { gestionnaire ->
            val ok =
                compilateur
                    .getTask(
                        null,
                        gestionnaire,
                        null,
                        listOf("-d", classes.toString()),
                        null,
                        gestionnaire.getJavaFileObjectsFromFiles(listOf(source)),
                    ).call()
            check(ok) { "la fixture Alpha doit compiler" }
        }
        val jar = cible.resolve("fixture-alpha.jar")
        JarOutputStream(java.io.FileOutputStream(jar.toFile())).use { flux ->
            Files.walk(classes).sorted().forEach { chemin ->
                if (chemin.toString().endsWith(".class")) {
                    flux.putNextEntry(
                        ZipEntry(
                            classes.relativize(chemin).toString().replace(File.separatorChar, '/'),
                        ),
                    )
                    flux.write(Files.readAllBytes(chemin))
                    flux.closeEntry()
                }
            }
        }
        return jar
    }

    /** Jar de plateforme STUB (lot h4 écart 1) : `android.util.Log`
     *  compilé au vol — joue le android.jar du toolchain pour les tests
     *  SANS SDK Android sur le poste (même garde javac que le jar fixture). */
    fun construireJarPlateforme(cible: Path): Path {
        val compilateur = ToolProvider.getSystemJavaCompiler()
        org.junit.jupiter.api.Assumptions.assumeTrue(
            compilateur != null,
            "JDK sans compilateur (JRE)",
        )
        Files.createDirectories(cible)
        val source = cible.resolve("Log.java")
        Files.writeString(source, SOURCE_LOG_STUB)
        compilateur.getStandardFileManager(null, null, null).use { gestionnaire ->
            val ok =
                compilateur
                    .getTask(
                        null,
                        gestionnaire,
                        null,
                        listOf("-d", cible.toString()),
                        null,
                        gestionnaire.getJavaFileObjectsFromFiles(listOf(source.toFile())),
                    ).call()
            check(ok) { "la fixture Log doit compiler" }
        }
        val jar = cible.resolve("android-stub.jar")
        JarOutputStream(java.io.FileOutputStream(jar.toFile())).use { flux ->
            flux.putNextEntry(ZipEntry("android/util/Log.class"))
            flux.write(Files.readAllBytes(cible.resolve("android/util/Log.class")))
            flux.closeEntry()
        }
        return jar
    }

    /** Résultat de classpath tooling : :lib (jar) + :app (MODULE :lib) ;
     *  v0.102.0 : :app peut porter la plateforme (androidJar, ADR 0112). */
    fun resultatClasspath(
        jar: Path,
        avecIgnore: Boolean = false,
        androidJar: Path? = null,
    ): ClasspathResult {
        val modules =
            mutableListOf(
                ClasspathModule(
                    name = ":lib",
                    sourceDirs = listOf(dossierLib.parent.toString()),
                    entries =
                        listOf(
                            ClasspathEntry(
                                path = jar.toString(),
                                kind = ClasspathKind.JAR,
                                scope = "compile",
                            ),
                        ),
                ),
                ClasspathModule(
                    name = ":app",
                    sourceDirs = listOf(dossierApp.parent.toString()),
                    entries =
                        listOf(
                            ClasspathEntry(
                                path = ":lib",
                                kind = ClasspathKind.MODULE,
                                scope = "compile",
                            ),
                        ),
                    androidJar = androidJar?.toString(),
                ),
            )
        if (avecIgnore) {
            modules.add(
                ClasspathModule(
                    name = ":brouillard",
                    sourceDirs = emptyList(),
                    entries = emptyList(),
                    ignore = true,
                    raisonIgnore = "variante non résolue",
                ),
            )
        }
        return ClasspathResult(
            id = "fixture",
            protocolVersion = 1,
            projectDir =
                dossierApp.parent.parent.parent
                    .toString(),
            modules = modules,
        )
    }
}
