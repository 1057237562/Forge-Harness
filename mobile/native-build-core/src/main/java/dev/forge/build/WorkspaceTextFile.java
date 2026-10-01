package dev.forge.build;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Complete UTF-8 documents only. A preview is never a saveable document. */
public final class WorkspaceTextFile {
    public static final int MAX_BYTES = 512000;
    public final String path, text, sha256;
    public final boolean bom;
    private WorkspaceTextFile(String path, String text, String hash, boolean bom) {
        this.path = path; this.text = text; this.sha256 = hash; this.bom = bom;
    }
    /** Keep the original on-disk version when restoring a draft; save must still detect conflicts. */
    public WorkspaceTextFile withExpectedHash(String expected) throws IOException {
        if (expected == null || !expected.matches("[a-f0-9]{64}")) throw new IOException("Invalid draft base hash");
        return new WorkspaceTextFile(path, text, expected, bom);
    }
    public static WorkspaceTextFile open(File root, String relative) throws IOException {
        File file = WorkspaceFiles.resolve(root, relative);
        if (!file.isFile()) throw new IOException("Not a regular text file");
        if (file.length() > MAX_BYTES) throw new IOException("File exceeds the 512 KB editor limit; use export or a larger-file tool");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream input = new FileInputStream(file)) {
            byte[] chunk = new byte[8192]; int count;
            while ((count = input.read(chunk)) != -1) {
                if (buffer.size() + count > MAX_BYTES) throw new IOException("File grew beyond editor limit");
                buffer.write(chunk, 0, count);
            }
        }
        byte[] data = buffer.toByteArray();
        boolean bom = data.length >= 3 && data[0] == (byte) 0xef && data[1] == (byte) 0xbb && data[2] == (byte) 0xbf;
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, bom ? 3 : 0, data.length - (bom ? 3 : 0))).toString(); }
        catch (CharacterCodingException error) { throw new IOException("File is not valid UTF-8 text", error); }
        if (text.indexOf('\0') >= 0) throw new IOException("Binary file cannot be edited as text");
        return new WorkspaceTextFile(relative, text, hash(data), bom);
    }
    public WorkspaceTextFile save(File root, String updatedText) throws IOException {
        if (updatedText.indexOf('\0') >= 0) throw new IOException("Text contains a binary NUL character");
        ByteBuffer encoded;
        try { encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap((bom ? "\ufeff" : "") + updatedText)); }
        catch (CharacterCodingException error) { throw new IOException("Text contains invalid Unicode", error); }
        if (encoded.remaining() > MAX_BYTES) throw new IOException("Edited content exceeds the 512 KB limit");
        byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
        File file = WorkspaceFiles.resolve(root, path);
        if (!file.isFile() || !WorkspaceFiles.sha256(file).equals(sha256))
            throw new IOException("File changed outside this editor. Reload and reconcile your edits before saving.");
        Path temporary = Files.createTempFile(file.toPath().getParent(), ".forge-edit-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(file.toPath())); }
            catch (UnsupportedOperationException nonPosixFilesystem) { /* Windows has no POSIX mode bits. */ }
            WorkspaceFiles.resolve(root, path);
            if (!WorkspaceFiles.sha256(file).equals(sha256)) throw new IOException("File changed while saving. Your edits have not replaced it.");
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        return new WorkspaceTextFile(path, updatedText, hash(bytes), bom);
    }
    private static String hash(byte[] data) throws IOException {
        try { return WorkspaceFiles.hex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException error) { throw new IOException(error); }
    }
}
