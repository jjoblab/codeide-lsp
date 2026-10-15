package jo.lspxml.session

import jo.lspxml.api.CodesDiagnosticsXml
import jo.lspxml.rules.GenreDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests SESSION des genres ÉTENDUS (XP12, plan de stabilisation LSP
 * §3.3 S2 — « genres étendus res/color, res/xml, res/navigation,
 * res/anim* ») : déduction d'URI (qualifiers et conteneurs compris),
 * complétion PAR FAMILLE (balises racines/enfants, attributs,
 * énumérations), extension de la règle xmlns:android et SILENCE X4
 * (parents inconnus, AUTRE inchangé).
 */
class SessionXp12Test {
    private val uriCouleur = "file:///projets/app/src/main/res/color/fond.xml"
    private val uriAnim = "file:///projets/app/src/main/res/anim/pop.xml"
    private val uriAnimateur = "file:///projets/app/src/main/res/animator/slide.xml"
    private val uriXmlApp = "file:///projets/app/src/main/res/xml/widget.xml"
    private val uriNavigation = "file:///projets/app/src/main/res/navigation/graphe.xml"
    private val uriTransition = "file:///projets/app/src/main/res/transition/disparait.xml"
    private val uriPolice = "file:///projets/app/src/main/res/font/roberto.xml"

    private fun session(): SessionLangageXmlImpl = SessionLangageXmlImpl(jo.lspxml.parse.ParseurXml())

    // ── Genres déduits de l'URI ────────────────────────────────────

    @Test
    fun `genre — uris des familles etendues, qualifiers et conteneurs compris`() {
        assertEquals(GenreDocument.COULEUR, GenreDocument.depuisUri("file:///a/res/color/puce.xml"))
        assertEquals(GenreDocument.COULEUR, GenreDocument.depuisUri("file:///a/res/color-night/puce.xml"))
        assertEquals(GenreDocument.ANIMATION, GenreDocument.depuisUri("file:///a/res/anim/pop.xml"))
        assertEquals(GenreDocument.ANIMATION, GenreDocument.depuisUri("file:///a/res/anim-v24/pop.xml"))
        assertEquals(GenreDocument.ANIMATEUR, GenreDocument.depuisUri("file:///a/res/animator/slide.xml"))
        assertEquals(GenreDocument.XML_APP, GenreDocument.depuisUri("file:///a/res/xml/chemins.xml"))
        assertEquals(GenreDocument.NAVIGATION, GenreDocument.depuisUri("file:///a/res/navigation/graphe.xml"))
        assertEquals(GenreDocument.TRANSITION, GenreDocument.depuisUri("file:///a/res/transition/disparait.xml"))
        assertEquals(GenreDocument.POLICE, GenreDocument.depuisUri("file:///a/res/font/roberto.xml"))
        // Conteneurs composites (couture XP13) et AUTRE inchangé.
        assertEquals(
            GenreDocument.XML_APP,
            GenreDocument.depuisUri("jar:file:///b/x.aar!/res/xml/chemins.xml"),
        )
        assertEquals(GenreDocument.AUTRE, GenreDocument.depuisUri("file:///a/res/truc/inconnu.xml"))
    }

    @Test
    fun `genre — animator AVANT anim (prefixe inclus)`() {
        // L'ordre du when décide : sans ce garde, res/animator porterait
        // deux genres (le préfixe de animator contient anim).
        val uri = "file:///a/res/animator/x.xml"
        assertEquals(GenreDocument.ANIMATEUR, GenreDocument.depuisUri(uri))
        assertFalse(GenreDocument.depuisUri(uri) == GenreDocument.ANIMATION)
    }

    // ── COULEUR — réutilisation du contributeur drawables ──────────

    @Test
    fun `completion couleur — racine selector, item enfant, drawables ABSENTS`() {
        val session = session()
        session.ouvrirDocument(uriCouleur, "<sel")
        assertEquals(listOf("selector"), session.completion(uriCouleur, 4).map { it.libelle })
        session.modifierDocument(uriCouleur, "<selector><")
        val enfants = session.completion(uriCouleur, "<selector><".length).map { it.libelle }
        assertEquals(listOf("item"), enfants)
        // X4 : une color list n'admet pas de shape ni de vector —
        // proposer des drawables ici serait FAUX.
        session.modifierDocument(uriCouleur, "<shape")
        assertTrue(session.completion(uriCouleur, 6).isEmpty())
    }

    @Test
    fun `completion couleur — item colorise (android color) et etats partages`() {
        val session = session()
        val source = "<selector><item "
        session.ouvrirDocument(uriCouleur, source)
        val attributs = session.completion(uriCouleur, source.length).map { it.libelle }
        // La colorisation distingue du selector drawable…
        assertTrue(attributs.contains("android:color"))
        assertTrue(attributs.contains("android:alpha"))
        // …les états voyagent PARTAGÉS avec le contributeur drawables.
        assertTrue(attributs.contains("android:state_pressed"))
        assertFalse(attributs.contains("android:drawable"))
    }

    @Test
    fun `diagnostics couleur — xmlns android manquant, quick-fix`() {
        val session = session()
        session.ouvrirDocument(
            uriCouleur,
            "<selector><item android:color=\"#fff\" android:state_pressed=\"true\"/></selector>",
        )
        val diagnostics = session.diagnostics(uriCouleur)
        assertTrue(diagnostics.any { it.code == CodesDiagnosticsXml.MANQUE_XMLNS_ANDROID })
        val diagnostic = diagnostics.first { it.code == CodesDiagnosticsXml.MANQUE_XMLNS_ANDROID }
        assertTrue(session.correctifs(uriCouleur, diagnostic.debut).isNotEmpty())
    }

    // ── ANIMATION (tween) ──────────────────────────────────────────

    @Test
    fun `completion animation — racines tween, enfants de set, attributs de transformation`() {
        val session = session()
        session.ouvrirDocument(uriAnim, "<")
        val racines = session.completion(uriAnim, 1).map { it.libelle }
        assertTrue(racines.containsAll(listOf("set", "alpha", "scale", "translate", "rotate")))
        // Enfants d'un set.
        session.modifierDocument(uriAnim, "<set><")
        val enfants = session.completion(uriAnim, 6).map { it.libelle }
        assertTrue(enfants.containsAll(listOf("alpha", "scale", "translate", "rotate")))
        // Attributs d'une transformation.
        val source = "<translate "
        session.modifierDocument(uriAnim, source)
        val attributs = session.completion(uriAnim, source.length).map { it.libelle }
        assertTrue(attributs.containsAll(listOf("android:fromXDelta", "android:toYDelta", "android:duration")))
        // X4 : un parent inconnu ne propose rien.
        session.modifierDocument(uriAnim, "<machin><")
        assertTrue(session.completion(uriAnim, 9).isEmpty())
    }

    @Test
    fun `completion animation — enumerations de timing et interpolateurs framework`() {
        val session = session()
        val source = "<set><translate android:repeatMode=\""
        session.ouvrirDocument(uriAnim, source)
        val valeurs = session.completion(uriAnim, source.length).map { it.libelle }
        assertTrue(valeurs.containsAll(listOf("restart", "reverse")))
        val sourceInterpolateur = "<set><translate android:interpolator=\""
        session.modifierDocument(uriAnim, sourceInterpolateur)
        val interpolateurs = session.completion(uriAnim, sourceInterpolateur.length).map { it.libelle }
        assertTrue(interpolateurs.contains("@android:anim/accelerate_interpolator"))
        assertTrue(interpolateurs.contains("@android:anim/bounce_interpolator"))
    }

    // ── ANIMATEUR (propriété) ──────────────────────────────────────

    @Test
    fun `completion animateur — racines propriete, objectAnimator, ordering de set`() {
        val session = session()
        session.ouvrirDocument(uriAnimateur, "<")
        val racines = session.completion(uriAnimateur, 1).map { it.libelle }
        assertTrue(racines.containsAll(listOf("set", "objectAnimator", "animator")))
        assertFalse(racines.contains("alpha"))
        // propertyValuesHolder n'est JAMAIS racine.
        assertFalse(racines.contains("propertyValuesHolder"))
        // Enfants d'un set + ordering.
        session.modifierDocument(uriAnimateur, "<set><")
        val enfants = session.completion(uriAnimateur, "<set><".length).map { it.libelle }
        assertTrue(enfants.containsAll(listOf("objectAnimator", "animator", "propertyValuesHolder")))
        val sourceAttributs = "<set "
        session.modifierDocument(uriAnimateur, sourceAttributs)
        val attributs = session.completion(uriAnimateur, sourceAttributs.length).map { it.libelle }
        assertTrue(attributs.contains("android:ordering"))
        // Valeurs de ordering.
        val sourceValeurs = "<set android:ordering=\""
        session.modifierDocument(uriAnimateur, sourceValeurs)
        assertTrue(
            session.completion(uriAnimateur, sourceValeurs.length).map { it.libelle }
                .containsAll(listOf("together", "sequentially")),
        )
    }

    // ── XML_APP (configurations) ───────────────────────────────────

    @Test
    fun `completion xml app — racines de config, attributs heterogenes`() {
        val session = session()
        session.ouvrirDocument(uriXmlApp, "<")
        val racines = session.completion(uriXmlApp, 1).map { it.libelle }
        assertTrue(
            racines.containsAll(
                listOf(
                    "appwidget-provider", "shortcuts", "paths",
                    "network-security-config", "data-extraction-rules",
                ),
            ),
        )
        // Attributs android: d'un widget.
        val sourceWidget = "<appwidget-provider "
        session.modifierDocument(uriXmlApp, sourceWidget)
        val attributs = session.completion(uriXmlApp, sourceWidget.length).map { it.libelle }
        assertTrue(
            attributs.containsAll(
                listOf("android:minWidth", "android:initialLayout", "android:updatePeriodMillis"),
            ),
        )
        // Attributs SANS namespace des chemins FileProvider.
        session.modifierDocument(uriXmlApp, "<paths><files-path ")
        val attributsChemin = session.completion(uriXmlApp, "<paths><files-path ".length).map { it.libelle }
        assertTrue(attributsChemin.containsAll(listOf("name", "path")))
    }

    @Test
    fun `completion xml app — enfants de shortcuts et config personnalisee silencieuse`() {
        val session = session()
        session.ouvrirDocument(uriXmlApp, "<shortcuts><")
        assertEquals(listOf("shortcut"), session.completion(uriXmlApp, "<shortcuts><".length).map { it.libelle })
        // X4 : une racine de config personnalisée n'est pas devinable.
        session.modifierDocument(uriXmlApp, "<ma-config ")
        assertTrue(session.completion(uriXmlApp, 11).isEmpty())
    }

    // ── NAVIGATION ─────────────────────────────────────────────────

    @Test
    fun `completion navigation — racine, destinations, actions et arguments`() {
        val session = session()
        session.ouvrirDocument(uriNavigation, "<")
        assertEquals(listOf("navigation"), session.completion(uriNavigation, 1).map { it.libelle })
        // Enfants d'une destination fragment : action/argument/deepLink.
        val source = "<navigation><fragment><"
        session.modifierDocument(uriNavigation, source)
        val enfants = session.completion(uriNavigation, source.length).map { it.libelle }
        assertTrue(enfants.containsAll(listOf("action", "argument", "deepLink")))
        // Attributs d'une action (app: destination).
        val sourceAction = "<navigation><fragment><action "
        session.modifierDocument(uriNavigation, sourceAction)
        val attributs = session.completion(uriNavigation, sourceAction.length).map { it.libelle }
        assertTrue(attributs.containsAll(listOf("app:destination", "app:popUpTo", "android:id")))
        // startDestination sur la racine.
        val sourceRacine = "<navigation "
        session.modifierDocument(uriNavigation, sourceRacine)
        val racineAttributs = session.completion(uriNavigation, sourceRacine.length).map { it.libelle }
        assertTrue(racineAttributs.contains("android:startDestination"))
    }

    // ── TRANSITION ─────────────────────────────────────────────────

    @Test
    fun `completion transition — racines, enfants de set, slideEdge`() {
        val session = session()
        session.ouvrirDocument(uriTransition, "<")
        val racines = session.completion(uriTransition, 1).map { it.libelle }
        assertTrue(racines.containsAll(listOf("transitionSet", "fade", "slide", "changeBounds")))
        // Enfants d'un set (transitions + cibles).
        session.modifierDocument(uriTransition, "<transitionSet><")
        val enfants = session.completion(uriTransition, "<transitionSet><".length).map { it.libelle }
        assertTrue(enfants.containsAll(listOf("fade", "slide", "targets")))
        // Énumération du bord de glissement.
        val source = "<slide android:slideEdge=\""
        session.modifierDocument(uriTransition, source)
        val valeurs = session.completion(uriTransition, source.length).map { it.libelle }
        assertTrue(valeurs.containsAll(listOf("start", "end", "top", "bottom")))
    }

    // ── POLICE ─────────────────────────────────────────────────────

    @Test
    fun `completion police — famille, font, styles et graisses`() {
        val session = session()
        session.ouvrirDocument(uriPolice, "<")
        assertEquals(listOf("font-family"), session.completion(uriPolice, 1).map { it.libelle })
        session.modifierDocument(uriPolice, "<font-family><")
        assertEquals(listOf("font"), session.completion(uriPolice, "<font-family><".length).map { it.libelle })
        // Attributs d'un font local.
        val source = "<font-family><font "
        session.modifierDocument(uriPolice, source)
        val attributs = session.completion(uriPolice, source.length).map { it.libelle }
        assertTrue(attributs.containsAll(listOf("android:font", "android:fontStyle", "android:fontWeight")))
        // Valeurs du style.
        val sourceValeurs = "<font-family><font android:fontStyle=\""
        session.modifierDocument(uriPolice, sourceValeurs)
        assertTrue(
            session.completion(uriPolice, sourceValeurs.length).map { it.libelle }
                .containsAll(listOf("normal", "italic")),
        )
    }

    // ── Diagnostics transverses ────────────────────────────────────

    @Test
    fun `diagnostics navigation — xmlns declare, silence des regles de genre`() {
        val session = session()
        session.ouvrirDocument(
            uriNavigation,
            "<navigation xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                "android:startDestination=\"@+id/accueil\" />",
        )
        assertTrue(
            session.diagnostics(uriNavigation)
                .none { it.code == CodesDiagnosticsXml.MANQUE_XMLNS_ANDROID },
        )
    }

    @Test
    fun `diagnostics xml app — sans namespace android, aucune alarme`() {
        val session = session()
        // FileProvider : les attributs n'ont PAS de préfixe — la règle
        // xmlns:android ne doit RIEN signaler.
        session.ouvrirDocument(uriXmlApp, "<paths><files-path name=\"tele\" path=\"tele/\"/></paths>")
        assertTrue(session.diagnostics(uriXmlApp).isEmpty())
    }

    @Test
    fun `definitions et survol — genres etendus sans regression`() {
        val session = session()
        // Le survol reste fonctionnel sur un genre étendu (référence
        // résolue par l'index — hors classpath ici : null honnête).
        val source = "<selector xmlns:android=\"http://schemas.android.com/apk/res/android\">" +
            "<item android:color=\"@color/absente\" android:state_pressed=\"true\"/></selector>"
        session.ouvrirDocument(uriCouleur, source)
        // Pas de plantage — le contrat tolérant tient sur les genres étendus.
        session.survol(uriCouleur, source.indexOf("absente") + 2)
        session.diagnostics(uriCouleur)
        session.symbolesDocument(uriCouleur)
        session.plagesPliables(uriCouleur)
    }
}
