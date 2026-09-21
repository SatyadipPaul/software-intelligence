package io.softwareintelligence.indextree;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.GraphJsonWriter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Identifies the exact graph a tree was derived from.
 *
 * <p>Taken over the canonical JSON export, which is already byte-identical for the same source on
 * any machine, so the fingerprint inherits that guarantee rather than inventing a second notion of
 * graph identity that could disagree with the first.
 */
public final class GraphFingerprint {
    private GraphFingerprint() { }

    public static String of(CodeGraph graph) {
        return of(GraphJsonWriter.write(graph));
    }

    public static String of(String canonicalJson) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM is required to ship SHA-256; if one does not, failing loudly beats a
            // fingerprint that silently stops detecting staleness.
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", impossible);
        }
    }
}
