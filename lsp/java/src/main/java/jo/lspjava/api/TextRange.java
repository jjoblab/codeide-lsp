package jo.lspjava.api;

/**
 * Représente une plage de texte dans un document source.
 * Fournit des méthodes utilitaires pour la manipulation de plages.
 *
 * @author jo@Dev
 */
public final class TextRange {

    /** Position de départ (offset en caractères depuis le début du fichier). */
    private final int startOffset;

    /** Position de fin (offset exclusif). */
    private final int endOffset;

    /**
     * Crée une nouvelle plage de texte.
     *
     * @param startOffset position de départ inclusive
     * @param endOffset   position de fin exclusive
     * @throws IllegalArgumentException si startOffset est négatif ou endOffset est inférieur à startOffset
     */
    public TextRange(int startOffset, int endOffset) {
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException(
                "Plage invalide : start=" + startOffset + ", end=" + endOffset);
        }
        this.startOffset = startOffset;
        this.endOffset = endOffset;
    }

    /**
     * Retourne la position de départ.
     *
     * @return offset de départ
     */
    public int startOffset() {
        return startOffset;
    }

    /**
     * Retourne la position de fin.
     *
     * @return offset de fin (exclusif)
     */
    public int endOffset() {
        return endOffset;
    }

    /**
     * Retourne la longueur de la plage.
     *
     * @return nombre de caractères dans la plage
     */
    public int length() {
        return endOffset - startOffset;
    }

    /**
     * Vérifie si cette plage contient un offset donné.
     *
     * @param offset l'offset à tester
     * @return vrai si l'offset est dans la plage
     */
    public boolean contains(int offset) {
        return offset >= startOffset && offset < endOffset;
    }

    /**
     * Vérifie si cette plage se chevauche avec une autre.
     *
     * @param other l'autre plage
     * @return vrai si les plages se chevauchent
     */
    public boolean overlaps(TextRange other) {
        return this.startOffset < other.endOffset && other.startOffset < this.endOffset;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof TextRange other)) return false;
        return startOffset == other.startOffset && endOffset == other.endOffset;
    }

    @Override
    public int hashCode() {
        return 31 * startOffset + endOffset;
    }

    @Override
    public String toString() {
        return "TextRange[" + startOffset + ", " + endOffset + "]";
    }

    /**
     * Crée une plage à partir d'un offset et d'une longueur.
     *
     * @param offset position de départ
     * @param length longueur
     * @return la plage correspondante
     */
    public static TextRange of(int offset, int length) {
        return new TextRange(offset, offset + length);
    }
}
