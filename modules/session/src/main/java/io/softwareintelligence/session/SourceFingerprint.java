package io.softwareintelligence.session;

import io.softwareintelligence.analyzer.java.JavaRepositoryAnalyzer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Identifies the source a graph was built from, so a later run can tell whether that graph still
 * describes what is on disk.
 *
 * <p>This is the opposite direction from {@code GraphFingerprint}, which identifies a graph by its
 * own content and answers "was this tree derived from this graph". That question cannot be asked
 * here, because answering it needs the graph, and the whole point of this one is to avoid building
 * the graph at all.
 *
 * <h2>Why content rather than timestamps</h2>
 *
 * <p>Sizes and modification times are cheaper, and wrong in the direction that matters. An editor
 * that restores an mtime, a checkout that preserves one, a same-size edit inside one filesystem
 * tick — each produces a fingerprint that says "unchanged" about source that changed, and the
 * result is an answer about code that is no longer there, delivered with every appearance of being
 * current. Reading the bytes cannot make that mistake. It costs a fraction of a second against the
 * tens of seconds it exists to save, which is not a trade worth thinking about twice.
 *
 * <h2>What is covered</h2>
 *
 * <p>Exactly the files {@link JavaRepositoryAnalyzer#sourceFiles} reports, which is exactly what
 * the analyzer parses — the same call, not a second copy of the rule. Paths are included alongside
 * content, so a rename with identical bytes still counts as a change, and the count is included so
 * that a deletion cannot be masked by an addition.
 *
 * <p><b>It does not cover the inputs that are not source.</b> A classpath entry, a git history read
 * by commit vocabulary, or a layer switched on and off all change the graph without changing a byte
 * of Java. Those belong to how the graph was requested rather than to what is on disk, so
 * {@link AnalysisSession} keeps them beside this and compares both.
 */
public record SourceFingerprint(String value, int files) {

    /**
     * Digests the source the analyzer would read.
     *
     * @param includeTests must match what the graph was built with; test sources change answers,
     *                     so a fingerprint taken over a different set is not comparable
     */
    public static SourceFingerprint of(Path repository, boolean includeTests) throws IOException {
        List<Path> sources = JavaRepositoryAnalyzer.sourceFiles(repository, includeTests);
        MessageDigest digest = sha256();
        Path base = repository.toAbsolutePath().normalize();
        for (Path source : sources) {
            // The path relative to the repository, so the same checkout in two directories
            // fingerprints alike and a rename does not slip through.
            digest.update(relative(base, source).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            hashInto(digest, source);
            digest.update((byte) 0);
        }
        return new SourceFingerprint(hex(digest.digest()), sources.size());
    }

    private static String relative(Path base, Path source) {
        Path path = base.relativize(source);
        // Separators differ between platforms; the identity of the file does not.
        return path.toString().replace('\\', '/');
    }

    private static void hashInto(MessageDigest digest, Path file) throws IOException {
        // Streamed rather than read whole: a repository is many files, and none of them needs to
        // be resident to be hashed.
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream digesting = new DigestInputStream(in, digest)) {
            byte[] buffer = new byte[16 * 1024];
            while (digesting.read(buffer) != -1) {
                // Reading is the work; DigestInputStream updates the digest as it goes.
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // Matching GraphFingerprint: failing loudly beats a fingerprint that quietly stops
            // detecting staleness.
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", impossible);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }
}
