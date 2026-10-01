package dev.forge.compiler;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class LibraryRGeneratorTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File symbols(String text) throws Exception { File file = temp.newFile(); Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); return file; }
    @Test public void preservesLibraryIndicesWhenAppAddsEarlierAttribute() throws Exception {
        File library = symbols("int[] styleable Card { 0x0, 0x0 }\nint styleable Card_radius 0\nint styleable Card_color 1\nint string title 0x0\n");
        File linked = symbols("int[] styleable Card { 0x7f010001, 0x7f010002, 0x7f010003 }\nint styleable Card_added 0\nint styleable Card_color 1\nint styleable Card_radius 2\nint string title 0x7f020004\n");
        String java = LibraryRGenerator.generate("dev.lib", library, linked);
        assertTrue(java, java.contains("Card = { 0x7f010003, 0x7f010002 }"));
        assertTrue(java, java.contains("Card_radius = 0x00000000"));
        assertTrue(java, java.contains("title = 0x7f020004")); assertFalse(java.contains("Card_added"));
    }
    @Test public void rejectsMissingLinkedSymbolsInsteadOfUsingZeroIds() throws Exception {
        try { LibraryRGenerator.generate("dev.lib", symbols("int string missing 0x0\n"), symbols("int string other 0x7f010001\n")); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("lack")); }
    }
    @Test public void rejectsIncompleteStyleableMapping() throws Exception {
        File library = symbols("int[] styleable Card { 0x0, 0x0 }\nint styleable Card_radius 0\n");
        try { LibraryRGenerator.generate("dev.lib", library, library); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Incomplete")); }
    }
}
