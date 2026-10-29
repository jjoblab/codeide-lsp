package jo.lspkotlin.resolve

import jo.lspkotlin.api.ContenuSurvoleKotlin
import jo.lspkotlin.api.DRAPEAU_ENUM_KOTLIN
import jo.lspkotlin.api.DRAPEAU_PUBLIC_KOTLIN
import jo.lspkotlin.api.MembresTypeKotlin

/**
 * Fiche de survol d'un type du CLASSPATH (ADR 0230 — chantier P1-D de
 * la feuille de route du rapport d'analyse du 28/10) : la signature
 * enrichie d'un type binaire, prouvée par la LECTURE BRUTE de son
 * `.class` — jamais une ligne inventée (le bytecode ne porte NI KDoc
 * NI Javadoc : la fiche est STRUCTURELLE, D3).
 *
 * ## Forme servie
 * La PREMIÈRE ligne (signature — rendue dans la fence `kotlin` du
 * survol markdown par l'hôte) :
 *
 * ```kotlin
 * class android.widget.TextView extends android.view.View
 * ```
 *
 * Les lignes suivantes (documentation d'accompagnement — sous la
 * fence) : `Interfaces : …` (interfaces DIRECTES), puis pour une
 * enum `Constantes : …` (champs `ACC_ENUM`, plafond 20) ou pour une
 * classe `Champs publics : Type nom, …` (plafond 8). Le genre est
 * lu des DRAPEAUX JVM (`interface`, `enum`, `class`) ; un `object`
 * Kotlin est détecté par son champ `INSTANCE` statique (convention
 * du compilateur, preuve binaire — pas une devinette).
 *
 * ## Conservatisme (D3)
 * Chaque ligne née d'une preuve : sans `.class` lisible, la fiche
 * tombe sur la signature `class fqcn` historique ; les membres
 * synthétiques (préfixe `$`) sont exclus ; les champs non publics
 * ne se montrent pas. `null` n'existe pas ici — l'APPELANT
 * ([InterrogationsSymboles.survolTypeClasspath]) a déjà PROUVÉ le
 * FQCN par les imports du fichier.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — chaque
 * SECTION de la fiche est une fonction (tête, interfaces,
 * constantes, champs) ; MagicNumbers — les plafonds 20/8 sont des
 * bornes d'AFFICHAGE citées dans l'ADR.
 */
@Suppress("TooManyFunctions", "MagicNumber")
internal object FicheClasspath {
    /** Plafond d'entrées d'enum rendues (au-delà : « … »). */
    private const val PLAFOND_CONSTANTES = 20

    /** Plafond de champs publics rendus (au-delà : « … »). */
    private const val PLAFOND_CHAMPS = 8

    /** Drapeau JVM `static` (0x0008) — détection d'`INSTANCE` d'object. */
    private const val DRAPEAU_STATIQUE: Int = 0x0008

    /** Drapeau JVM `final` (0x0010) — détection d'`INSTANCE` d'object. */
    private const val DRAPEAU_FINAL: Int = 0x0010

    /** Drapeau JVM `interface` (0x0200). */
    private const val DRAPEAU_INTERFACE: Int = 0x0200

    /** Drapeau JVM `enum` des CHAMPS (0x4000 — constante d'enum). */
    private const val DRAPEAU_CHAMP_ENUM: Int = 0x4000

    /**
     * La fiche du type [fqcn] — signature en première ligne, sections
     * structurelles en documentation. La signature seule est rendue
     * quand le `.class` est illisible (le FQCN est déjà prouvé par
     * l'appelant).
     */
    internal fun fiche(
        fqcn: String,
        resolution: ResolutionKotlin,
    ): ContenuSurvoleKotlin {
        val membres = resolution.membresDe(fqcn)
        val infos = resolution.infosClasse(fqcn)
        if (membres == null && infos == null) return ContenuSurvoleKotlin("class $fqcn")
        val tete = tete(fqcn, membres, infos)
        val sections = mutableListOf<String>()
        infos?.interfaces?.takeIf { it.isNotEmpty() }?.let { interfaces ->
            sections.add("Interfaces : ${interfaces.joinToString(", ")}")
        }
        if (membres != null) {
            sections.addAll(sectionsMembres(membres, resolution))
        }
        return ContenuSurvoleKotlin(tete, sections.takeIf { it.isNotEmpty() }?.joinToString("\n"))
    }

    /** Première ligne : `[public] genre FQCN [extends super]`.
     *
     * Exemption detekt ciblée (règle 16) : CyclomaticComplexMethod —
     * la table de décision du genre et de l'héritage (interface, enum,
     * object, super universel masqué) est une ISSUE par preuve binaire.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun tete(
        fqcn: String,
        membres: MembresTypeKotlin?,
        infos: jo.lspkotlin.classpath.LecteurClassBrut.InfosClasse?,
    ): String {
        val drapeaux = membres?.drapeauxClasse ?: 0
        val estInterface = drapeaux and DRAPEAU_INTERFACE != 0
        val estEnum = drapeaux and DRAPEAU_ENUM_KOTLIN != 0
        val estObject = !estInterface && !estEnum && membres != null &&
            membres.champs.any { champ ->
                champ.nom == "INSTANCE" &&
                    champ.drapeaux and DRAPEAU_STATIQUE != 0 &&
                    champ.drapeaux and DRAPEAU_FINAL != 0
            }
        val genre =
            when {
                estInterface -> "interface"
                estEnum -> "enum"
                estObject -> "object"
                else -> "class"
            }
        // L'héritage ne s'affiche que pour un `class`/`object` dont la
        // superclasse DIRECTE est lisible et DIFFÉRENTE des racines
        // universelles (Object pour tout le monde, Enum pour les enums
        // — du bruit, jamais une information) ; une interface
        // `extends` des interfaces — déjà servies en section.
        val heritage =
            if (estInterface) {
                ""
            } else {
                infos?.superFqcn
                    ?.takeIf { it != "java.lang.Object" && !(estEnum && it == "java.lang.Enum") }
                    ?.let { " extends $it" } ?: ""
            }
        val signee = "$genre $fqcn$heritage"
        return if (drapeaux and DRAPEAU_PUBLIC_KOTLIN != 0) "public $signee" else signee
    }

    /** Sections membres : constantes d'enum OU champs publics.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque garde
     * (branche enum, constantes vides, champs vides) est une ISSUE
     * distincte du catalogue de la fiche.
     */
    @Suppress("ReturnCount")
    private fun sectionsMembres(
        membres: MembresTypeKotlin,
        resolution: ResolutionKotlin,
    ): List<String> {
        if (membres.drapeauxClasse and DRAPEAU_ENUM_KOTLIN != 0) {
            val constantes =
                membres.champs
                    .filter { champ -> champ.drapeaux and DRAPEAU_CHAMP_ENUM != 0 }
                    .map { champ -> champ.nom }
            if (constantes.isNotEmpty()) {
                val rendues = constantes.take(PLAFOND_CONSTANTES)
                val suite = if (constantes.size > rendues.size) ", …" else ""
                return listOf("Constantes : ${rendues.joinToString(", ")}$suite")
            }
            return emptyList()
        }
        val champs =
            membres.champs
                .filter { champ ->
                    champ.drapeaux and DRAPEAU_PUBLIC_KOTLIN != 0 &&
                        !champ.nom.startsWith("$") &&
                        // Le marqueur `INSTANCE` d'un objet est déjà
                        // DIT par le genre « object » de la première
                        // ligne — le répéter en champ serait du bruit.
                        champ.nom != "INSTANCE"
                }.map { champ ->
                    val type = resolution.traduireDescripteur(champ.descripteur).retour
                    "${resolution.renduCourt(type)} ${champ.nom}"
                }
        if (champs.isEmpty()) return emptyList()
        val rendus = champs.take(PLAFOND_CHAMPS)
        val suite = if (champs.size > rendus.size) ", …" else ""
        return listOf("Champs publics : ${rendus.joinToString(", ")}$suite")
    }
}
