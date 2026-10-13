package jo.lspkotlin.classpath

import jo.lspkotlin.api.KindEntreeKotlin
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests du décodeur de métadonnées (K3) sur des jars RÉELS du
 * classpath de test :
 *  - kotlin-stdlib (Kotlin → appelables de premier niveau + extensions) ;
 *  - junit4 (Java → PORTE ABSENTE, zéro classe décodée — le saut de
 *    §4.2 est le contrat même du décodeur).
 */
class DecodeurMetadonneesTest {
    /** Le jar kotlin-stdlib du classpath de test (jar physique). */
    private fun jarStdlib(): File =
        File(
            kotlin.jvm.internal.Intrinsics::class.java.protectionDomain
                .codeSource.location.toURI(),
        )

    /** Le jar junit4 du classpath de test (bibliothèque Java). */
    private fun jarJunit(): File = File(org.junit.Test::class.java.protectionDomain.codeSource.location.toURI())

    @Test
    fun `stdlib - des appelables de premier niveau et des extensions`() {
        val appelables = DecodeurMetadonnees.decoder(jarStdlib(), KindEntreeKotlin.JAR)
        assertTrue("aucun appelable décodé du stdlib", appelables.size > 100)
        // Fonctions de premier niveau : p.ex. kotlin.text.trim (String).
        assertTrue(appelables.any { it.recepteur == "kotlin.String" })
        // Paquets réels du stdlib.
        assertTrue(appelables.any { it.paquet == "kotlin.text" })
        // Chaque appelable porte son conteneur (facade du fichier).
        assertTrue(appelables.all { it.conteneur.isNotEmpty() })
    }

    @Test
    fun `jar Java (junit) - porte absente, ZERO appelable`() {
        val appelables = DecodeurMetadonnees.decoder(jarJunit(), KindEntreeKotlin.JAR)
        assertTrue("un jar sans module Kotlin ne doit rien rendre", appelables.isEmpty())
    }

    @Test
    fun `fichier absent - liste vide, jamais d'exception (D3)`() {
        assertTrue(DecodeurMetadonnees.decoder(File("/inexistant/rien.jar"), KindEntreeKotlin.JAR).isEmpty())
        assertTrue(DecodeurMetadonnees.decoder(File("/inexistant/rien.aar"), KindEntreeKotlin.AAR).isEmpty())
    }
}
