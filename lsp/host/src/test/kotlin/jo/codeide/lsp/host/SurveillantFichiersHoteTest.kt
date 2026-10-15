package jo.codeide.lsp.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests du surveillant de fichiers côté HÔTE (S1-d « watched files »,
 * plan de stabilisation §3.1 — I4 de l'audit Java : la voie protocole
 * n'est plus muette). Le DIFF d'instantanés est le cœur : Créé/Modifié/
 * Supprimé en ordre déterministe, baseline sans événement, extensions
 * et exclusions du balayage borné.
 */
class SurveillantFichiersHoteTest {
    @TempDir
    lateinit var projet: File

    /** Surveillant NEUTRE (période ≤ 0) — les tours sont pilotés à la main. */
    private fun surveillant(): SurveillantFichiersHote = SurveillantFichiersHote(projet, periodeMs = 0L)

    @Test
    fun `baseline - instantané stable, AUCUN événement sans changement`() {
        File(projet, "src/com/ex/A.java").apply {
            parentFile!!.mkdirs()
            writeText("class A {}\n")
        }
        val s = surveillant()
        val baseline = s.instantane()
        assertTrue(baseline.isNotEmpty(), "le .java des racines est suivi")
        // Rien ne bouge : la diff baseline → courant est VIDE (le watcher
        // installe sa baseline au premier tour — zéro événement synthétique).
        assertTrue(s.unTour(baseline, s.instantane()).isEmpty())
    }

    @Test
    fun `diff - créé, modifié et supprimé en ordre du chemin`() {
        val a =
            File(projet, "src/A.java").apply {
                parentFile!!.mkdirs()
                writeText("class A {}\n")
            }
        val b =
            File(projet, "src/B.java").apply {
                parentFile!!.mkdirs()
                writeText("class B {}\n")
            }
        val s = surveillant()
        val avant = s.instantane()
        // Modification de A + suppression de B + création de C.
        a.writeText("class A2 {}\n")
        val mtimeAvant = b.lastModified()
        b.delete()
        val c = File(projet, "src/C.java").apply { writeText("class C {}\n") }
        // mtime influe : on force un mtime distinct du précédent (1 ms de résolution).
        c.setLastModified(mtimeAvant + 5000)
        a.setLastModified(mtimeAvant + 4000)
        val evenements = s.unTour(avant, s.instantane())
        val types = evenements.map { it.uri.substringAfterLast('/') to it.type }
        assertEquals(3, evenements.size)
        assertEquals(FileChangeType.Created, types.first { it.first == "C.java" }.second)
        assertEquals(FileChangeType.Changed, types.first { it.first == "A.java" }.second)
        assertEquals(FileChangeType.Deleted, types.first { it.first == "B.java" }.second)
        // Ordre du chemin (déterministe) : A < B < C.
        assertEquals(
            listOf("A.java", "B.java", "C.java"),
            evenements.map { it.uri.substringAfterLast('/') },
        )
    }

    @Test
    fun `périmètre - seuls les java sont suivis, build et cachés exclus`() {
        File(projet, "build/A.java").apply {
            parentFile!!.mkdirs()
            writeText("class A {}\n")
        }
        File(projet, ".git/B.java").apply {
            parentFile!!.mkdirs()
            writeText("class B {}\n")
        }
        File(projet, "src/notes.txt").apply {
            parentFile!!.mkdirs()
            writeText("texte")
        }
        File(projet, "src/Reel.java").apply {
            parentFile!!.mkdirs()
            writeText("class Reel {}\n")
        }
        val noms = surveillant().instantane().keys.map { it.substringAfterLast('/') }
        assertEquals(listOf("Reel.java"), noms)
    }

    @Test
    fun `borne - le balayage tronque au plafond (troncation honnête)`() {
        for (i in 0 until 10) {
            File(projet, "src/F$i.java").apply {
                parentFile!!.mkdirs()
                writeText("class F$i {}\n")
            }
        }
        // Le plafond réel est 2000 — on ne le franchit pas en test ; on
        // vérifie seulement que le total est cohérent (borné = complet ici).
        assertEquals(10, surveillant().instantane().size)
    }

    @Test
    fun `boucle - destination reçoit les événements, tour fautif ne casse pas`() {
        val s = SurveillantFichiersHote(projet, periodeMs = 50L)
        val recus = mutableListOf<DidChangeWatchedFilesParams>()
        var fautif = true
        val scope = CoroutineScope(Dispatchers.Default)
        val job =
            s.boucle(scope, Dispatchers.Default) { params ->
                if (fautif) {
                    fautif = false
                    error("destination fautive au premier tour (test)")
                }
                recus.add(params)
            }
        // Premier fichier : la destination reçoit puis LÈVE (tour fautif —
        // événement perdu, baseline déjà avancée : sémantique documentée).
        Thread.sleep(80)
        File(projet, "src/Tardif1.java").apply {
            parentFile!!.mkdirs()
            writeText("class T1 {}\n")
        }
        Thread.sleep(200)
        // Second fichier : le tour suivant pousse À NOUVEAU (la boucle a survécu).
        File(projet, "src/Tardif2.java").apply { writeText("class T2 {}\n") }
        Thread.sleep(300)
        scope.cancel()
        assertTrue(recus.isNotEmpty(), "la boucle a survécu et pousse les événements suivants")
        assertTrue(job.isCancelled || job.isCompleted)
    }
}
