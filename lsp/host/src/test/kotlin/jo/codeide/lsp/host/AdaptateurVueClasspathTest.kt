package jo.codeide.lsp.host

import jo.codeide.tooling.protocol.ClasspathEntry
import jo.codeide.tooling.protocol.ClasspathKind
import jo.codeide.tooling.protocol.ClasspathModule
import jo.codeide.tooling.protocol.ClasspathResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import javax.tools.ToolProvider

/**
 * Tests de l'ADAPTATEUR de vue de classpath Kotlin (K3,
 * `AdaptateurVueClasspath` — la couture neutre [VueClasspathKotlin]
 * branchée sur la vue d'index fusion ADR 0111) — par la surface
 * publique [SessionLsp.vueClasspathKotlin], sur un index RÉEL nourri
 * des DEUX conteneurs que l'union de l'orchestrateur dessert :
 *
 *  - un JAR plat (`alpha.Alpha` compilé au vol) ;
 *  - un AAR non normalisé (`beta.Beta` dans `classes.jar` interne — le
 *    localisateur rend le chemin COMPOSITE « aar!classes.jar », le
 *    lecteur doit l'ouvrir DANS l'AAR, jamais comme un fichier plat).
 *
 * L'index réel ([lsp:index]) vit dessous : les requêtes du moteur
 * Kotlin sont éprouvées telles qu'elles le seront en production (noms,
 * paquets, membres, octets — y compris les chemins de repli :
 * conteneur absent, jar corrompu, éviction du cache FIFO des poignées
 * ouvertes).
 */
@Timeout(180)
class AdaptateurVueClasspathTest {
    @field:TempDir
    lateinit var tmp: Path

    /** Source de `alpha.Alpha` — portée par le JAR plat. */
    private val sourceAlpha: String =
        """
        package alpha;

        public class Alpha {
            public static int COMPTEUR = 3;
            public int instance = 0;
            public void bouger() {}
        }
        """.trimIndent()

    /** Source de `beta.Beta` — portée par le classes.jar INTERNE de l'AAR. */
    private val sourceBeta: String =
        """
        package beta;

        public class Beta {
            public String libelle = "b";
        }
        """.trimIndent()

    // ── Montage ────────────────────────────────────────────────────────

    /** Les chemins de conteneurs du montage. */
    private data class VueConteneurs(
        val jar: Path,
        val aar: Path,
    )

    /** Session RÉELLE dont l'index fusion porte les deux conteneurs. */
    private fun sessionDeuxConteneurs(): Pair<SessionLsp, VueConteneurs> {
        val racine = Files.createDirectories(tmp.resolve("projet"))
        val montage = tmp.resolve("montage")
        val jar = compilerEnJar(sourceAlpha, "alpha.Alpha", montage.resolve("alpha"))
        val aar = compilerEnAar(sourceBeta, "beta.Beta", montage.resolve("beta"))
        val session =
            SessionLsp(
                racineProjet = racine.toFile(),
                stockageIndex = tmp.resolve("index").toFile(),
            )
        val resultat =
            ClasspathResult(
                id = "fixture",
                protocolVersion = 1,
                projectDir = racine.toString(),
                modules =
                    listOf(
                        ClasspathModule(
                            name = ":lib",
                            sourceDirs = emptyList(),
                            entries =
                                listOf(
                                    ClasspathEntry(jar.toString(), ClasspathKind.JAR, "compile"),
                                    ClasspathEntry(aar.toString(), ClasspathKind.AAR, "compile"),
                                ),
                        ),
                    ),
            )
        session.appliquerClasspath(resultat)
        return Pair(session, VueConteneurs(jar, aar))
    }

    /** Attend la transition PRÊT de l'index (fusion, P7/P8). */
    private fun attendrePret(session: SessionLsp) {
        runBlocking {
            withTimeout(60_000) {
                session.etatIndexation.first { it.phase == PhaseIndexation.PRET }
            }
        }
    }

    /** La vue neutre de la session, PRÉTEXTE d'échec clair si absente. */
    private fun vuePrete(session: SessionLsp): jo.lspkotlin.api.VueClasspathKotlin =
        checkNotNull(session.vueClasspathKotlin()) { "la vue neutre doit exister une fois l'index PRÊT" }

    /** Compile [source] dans un dossier de classes (garde javac). */
    private fun compilerDossier(
        source: String,
        fqcn: String,
        cible: Path,
    ): Path {
        val compilateur = ToolProvider.getSystemJavaCompiler()
        org.junit.jupiter.api.Assumptions.assumeTrue(
            compilateur != null,
            "JDK sans compilateur (JRE)",
        )
        val fichier = cible.resolve("${fqcn.substringAfterLast('.')}.java")
        Files.createDirectories(cible)
        Files.writeString(fichier, source)
        compilateur.getStandardFileManager(null, null, null).use { gestionnaire ->
            val ok =
                compilateur
                    .getTask(
                        null,
                        gestionnaire,
                        null,
                        listOf("-d", cible.toString()),
                        null,
                        gestionnaire.getJavaFileObjectsFromFiles(listOf(fichier.toFile())),
                    ).call()
            check(ok) { "la fixture doit compiler : $fqcn" }
        }
        return cible
    }

    /** Compile puis empaquette en JAR plat (entree `paquet/Nom.class`). */
    private fun compilerEnJar(
        source: String,
        fqcn: String,
        cible: Path,
    ): Path {
        val classes = compilerDossier(source, fqcn, cible)
        val jar = cible.resolveSibling("fixture-${fqcn.substringAfterLast('.').lowercase()}.jar")
        JarOutputStream(java.io.FileOutputStream(jar.toFile())).use { flux ->
            val entree = fqcn.replace('.', '/') + ".class"
            flux.putNextEntry(ZipEntry(entree))
            flux.write(Files.readAllBytes(classes.resolve(entree)))
            flux.closeEntry()
        }
        return jar
    }

    /** Compile puis empaquette en AAR (classes.jar interne porteur). */
    private fun compilerEnAar(
        source: String,
        fqcn: String,
        cible: Path,
    ): Path {
        val classes = compilerDossier(source, fqcn, cible)
        val aar = cible.resolveSibling("fixture-${fqcn.substringAfterLast('.').lowercase()}.aar")
        // Le jar interne vit d'abord en mémoire : le fermer ne doit JAMAIS
        // fermer le flux de l'AAR qui l'embarque.
        val tampon = java.io.ByteArrayOutputStream()
        JarOutputStream(tampon).use { interne ->
            val entree = fqcn.replace('.', '/') + ".class"
            interne.putNextEntry(ZipEntry(entree))
            interne.write(Files.readAllBytes(classes.resolve(entree)))
            interne.closeEntry()
        }
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(aar.toFile())).use { flux ->
            flux.putNextEntry(ZipEntry("classes.jar"))
            flux.write(tampon.toByteArray())
            flux.closeEntry()
        }
        return aar
    }

    // ── Noms et paquets ────────────────────────────────────────────────

    @Test
    fun `noms par prefixe - porteur alpha par le jar, inconnu vide`() {
        val (session, _) = sessionDeuxConteneurs()
        try {
            attendrePret(session)
            val vue = vuePrete(session)
            val noms = vue.nomsParPrefixe("Alp", 10)
            assertEquals(listOf("Alpha"), noms.map { it.nomSimple })
            assertEquals(listOf("alpha.Alpha"), noms.single().fqcns)
            assertTrue(vue.nomsParPrefixe("Zzz", 10).isEmpty())
        } finally {
            session.close()
        }
    }

    @Test
    fun `paquets - estPaquet, typesDansPaquet et sousPaquetsDirects`() {
        val (session, _) = sessionDeuxConteneurs()
        try {
            attendrePret(session)
            val vue = vuePrete(session)
            assertTrue(vue.estPaquet("alpha"))
            assertTrue(vue.estPaquet("beta"))
            assertFalse(vue.estPaquet("inconnu"))
            // Types DIRECTEMENT dans le paquet (« ni plus ni moins »).
            assertEquals(listOf("alpha.Alpha"), vue.typesDansPaquet("alpha"))
            assertEquals(listOf("beta.Beta"), vue.typesDansPaquet("beta"))
            assertEquals(emptyList<String>(), vue.typesDansPaquet("inconnu"))
            // Racine vide : les deux sous-paquets de premier niveau.
            val sousPaquets = vue.sousPaquetsDirects("")
            assertEquals(setOf("alpha", "beta"), sousPaquets.toSet())
            assertEquals(emptyList<String>(), vue.sousPaquetsDirects("alpha"))
        } finally {
            session.close()
        }
    }

    // ── Membres ────────────────────────────────────────────────────────

    @Test
    fun `membres - champs et methodes declares traduits en vue JVM`() {
        val (session, _) = sessionDeuxConteneurs()
        try {
            attendrePret(session)
            val vue = vuePrete(session)
            val membres = checkNotNull(vue.membres("alpha.Alpha")) { "alpha.Alpha est porté par le jar" }
            val champs = membres.champs
            val methodes = membres.methodes
            assertEquals(setOf("COMPTEUR", "instance"), champs.map { it.nom }.toSet())
            assertTrue(methodes.any { it.nom == "bouger" && it.descripteur == "()V" })
            assertTrue(methodes.any { it.nom == "<init>" })
            assertNull(vue.membres("inconnu.Classe"), "classe introuvable : null honnête")
        } finally {
            session.close()
        }
    }

    // ── Octets de classes (jar plat, jar interne d'AAR, replis) ────────

    @Test
    fun `octets classe - jar plat et jar interne d'AAR`() {
        val (session, _) = sessionDeuxConteneurs()
        try {
            attendrePret(session)
            val vue = vuePrete(session)
            // Le jar plat et le jar INTERNE de l'AAR rendent le même
            // magic number (le localisateur désigne « aar!classes.jar »).
            for (fqcn in listOf("alpha.Alpha", "beta.Beta")) {
                val octets = checkNotNull(vue.octetsClasse(fqcn)) { "$fqcn doit être lisible depuis son conteneur" }
                assertArrayEquals(
                    byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()),
                    octets.copyOfRange(0, 4),
                )
            }
            assertNull(vue.octetsClasse("inconnu.Classe"), "classe introuvable : null honnête")
        } finally {
            session.close()
        }
    }

    @Test
    fun `octets classe - conteneur supprime apres l'indexation, null honnete`() {
        val (session, conteneurs) = sessionDeuxConteneurs()
        try {
            attendrePret(session)
            val vue = vuePrete(session)
            // La localisation reste servie par l'index (mémoire), le
            // FICHIER a disparu : le lecteur rend null, jamais d'exception.
            Files.delete(conteneurs.jar)
            assertNull(vue.octetsClasse("alpha.Alpha"))
            // L'AAR est SUPPRIMÉ avant la première lecture : même repli.
            Files.delete(conteneurs.aar)
            assertNull(vue.octetsClasse("beta.Beta"))
        } finally {
            session.close()
        }
    }

    @Test
    fun `octets classe - jar corrompu, null honnete sans exception`() {
        val (session, conteneurs) = sessionDeuxConteneurs()
        try {
            attendrePret(session)
            val vue = vuePrete(session)
            // Des octets absurdes : l'ouverture du ZipFile lève — la
            // tolérance D3 transforme la panne en null (poignée évacuée).
            Files.write(conteneurs.jar, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
            assertNull(vue.octetsClasse("alpha.Alpha"))
            // Le fichier reste NON jar : la requête suivante aussi honnête.
            Files.write(conteneurs.jar, Files.readAllBytes(conteneurs.aar))
            assertNull(vue.octetsClasse("alpha.Alpha"), "pas un jar valide : null honnête")
        } finally {
            session.close()
        }
    }

    @Test
    fun `cache de poignees - ouverture de sept jars, eviction FIFO silencieuse`() {
        // Sept jars distincts d'une classe chacun : la lecture des sept
        // octets traverse le cache borné (TAILLE_CACHE = 6) et ÉVICTE la
        // plus ancienne poignée sans erreur ni fuite visible.
        val racine = Files.createDirectories(tmp.resolve("projet-eviction"))
        val montage = tmp.resolve("sept-jars")
        val jars =
            (0 until 7).map { indice ->
                compilerEnJar(
                    "package petit$indice;\n\npublic class Petit$indice {}",
                    "petit$indice.Petit$indice",
                    montage.resolve("petit$indice"),
                )
            }
        val session =
            SessionLsp(
                racineProjet = racine.toFile(),
                stockageIndex = tmp.resolve("index-eviction").toFile(),
            )
        try {
            val resultat =
                ClasspathResult(
                    id = "eviction",
                    protocolVersion = 1,
                    projectDir = racine.toString(),
                    modules =
                        listOf(
                            ClasspathModule(
                                name = ":sept",
                                sourceDirs = emptyList(),
                                entries =
                                    jars.map { jar ->
                                        ClasspathEntry(jar.toString(), ClasspathKind.JAR, "compile")
                                    },
                            ),
                        ),
                )
            session.appliquerClasspath(resultat)
            attendrePret(session)
            val vue = vuePrete(session)
            for (indice in 0 until 7) {
                assertNotNull(
                    vue.octetsClasse("petit$indice.Petit$indice"),
                    "Petit$indice doit rester lisible après éviction",
                )
            }
            // La ré-lecture du PREMIER (évincé) repart d'une ouverture
            // fraîche : le fichier existe toujours, la lecture réussit.
            assertNotNull(vue.octetsClasse("petit0.Petit0"))
        } finally {
            session.close()
        }
    }

    // ── Avant PRÊT ─────────────────────────────────────────────────────

    @Test
    fun `vue avant l'application d'un classpath - null honnete`() {
        val racine = Files.createDirectories(tmp.resolve("projet-vide"))
        SessionLsp(
            racineProjet = racine.toFile(),
            stockageIndex = tmp.resolve("index-vide").toFile(),
        ).use { session ->
            assertNull(session.vueClasspathKotlin(), "aucun état d'orchestration : null, pas d'exception")
        }
    }
}
