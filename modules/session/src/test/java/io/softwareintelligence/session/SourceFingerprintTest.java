package io.softwareintelligence.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * What must and must not count as a change to the source.
 *
 * <p>A fingerprint that misses a change is the one failure here that produces no error: the session
 * keeps answering, from a graph describing code that is no longer on disk, with nothing in the
 * output to say so. Each test below is a way that could happen.
 */
final class SourceFingerprintTest {

    private static Path write(Path directory, String name, String content) throws IOException {
        Path file = directory.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static SourceFingerprint of(Path repository) throws IOException {
        return SourceFingerprint.of(repository, true);
    }

    @Test void the_same_source_fingerprints_the_same(@TempDir Path repository) throws IOException {
        write(repository, "src/A.java", "class A {}");
        assertEquals(of(repository), of(repository));
    }

    @Test void changed_content_changes_it(@TempDir Path repository) throws IOException {
        write(repository, "src/A.java", "class A {}");
        SourceFingerprint before = of(repository);
        write(repository, "src/A.java", "class A { void added() {} }");
        assertNotEquals(before, of(repository));
    }

    @Test void a_same_size_edit_changes_it(@TempDir Path repository) throws IOException {
        // The case that defeats size-and-timestamp fingerprints: identical length, and on a
        // coarse clock possibly an identical mtime, but different code.
        write(repository, "src/A.java", "class A { int x = 1; }");
        SourceFingerprint before = of(repository);
        write(repository, "src/A.java", "class A { int x = 2; }");
        assertNotEquals(before, of(repository));
    }

    @Test void a_restored_timestamp_does_not_hide_an_edit(@TempDir Path repository) throws IOException {
        Path file = write(repository, "src/A.java", "class A { int x = 1; }");
        FileTime original = Files.getLastModifiedTime(file);
        SourceFingerprint before = of(repository);

        Files.writeString(file, "class A { int y = 9; }");
        Files.setLastModifiedTime(file, original);

        assertNotEquals(before, of(repository), "content must decide, not the clock");
    }

    @Test void a_new_file_changes_it(@TempDir Path repository) throws IOException {
        write(repository, "src/A.java", "class A {}");
        SourceFingerprint before = of(repository);
        write(repository, "src/B.java", "class B {}");
        assertNotEquals(before, of(repository));
    }

    @Test void a_deleted_file_changes_it(@TempDir Path repository) throws IOException {
        write(repository, "src/A.java", "class A {}");
        Path second = write(repository, "src/B.java", "class B {}");
        SourceFingerprint before = of(repository);
        Files.delete(second);
        assertNotEquals(before, of(repository));
    }

    @Test void a_rename_changes_it_even_with_identical_bytes(@TempDir Path repository) throws IOException {
        Path file = write(repository, "src/A.java", "class A {}");
        SourceFingerprint before = of(repository);
        Files.move(file, repository.resolve("src/Renamed.java"));
        assertNotEquals(before, of(repository), "the path is part of the identity");
    }

    @Test void one_file_swapped_for_another_of_the_same_size_changes_it(@TempDir Path repository) throws IOException {
        // Deletion and addition in one step: a digest over content alone, with no count and no
        // paths, could come out identical here.
        write(repository, "src/A.java", "class A {}");
        write(repository, "src/B.java", "class B {}");
        SourceFingerprint before = of(repository);

        Files.delete(repository.resolve("src/A.java"));
        write(repository, "src/C.java", "class C {}");

        assertNotEquals(before, of(repository));
    }

    @Test void a_non_java_file_does_not_change_it(@TempDir Path repository) throws IOException {
        // The analyzer does not read these, so a graph built before one appeared is still current.
        write(repository, "src/A.java", "class A {}");
        SourceFingerprint before = of(repository);
        write(repository, "README.md", "# notes");
        assertEquals(before, of(repository));
    }

    @Test void the_file_count_is_reported(@TempDir Path repository) throws IOException {
        write(repository, "src/A.java", "class A {}");
        write(repository, "src/B.java", "class B {}");
        assertEquals(2, of(repository).files());
    }

    @Test void an_empty_repository_fingerprints_without_failing(@TempDir Path repository) throws IOException {
        SourceFingerprint fingerprint = of(repository);
        assertEquals(0, fingerprint.files());
        assertEquals(fingerprint, of(repository));
    }
}
