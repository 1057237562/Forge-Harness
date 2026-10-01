package dev.forge.build;

import java.io.*;
import java.util.Locale;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

final class ManifestInfo {
    static final String ANDROID = "http://schemas.android.com/apk/res/android";
    final String packageName, versionName;
    final int minSdk, targetSdk, versionCode;
    private ManifestInfo(String pkg, int min, int target, int code, String name) {
        packageName = pkg; minSdk = min; targetSdk = target; versionCode = code; versionName = name;
    }
    static ManifestInfo read(File file) throws IOException {
        String text = WorkspaceFiles.readUtf8(file, 1024 * 1024);
        if (text.toUpperCase(Locale.ROOT).contains("<!DOCTYPE") || text.toUpperCase(Locale.ROOT).contains("<!ENTITY"))
            throw new CompatibilityException(file.getPath(), 0, "DTD and entity declarations are not supported");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((id, system) -> new InputSource(new StringReader("")));
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            });
            Element root = builder.parse(new InputSource(new StringReader(text))).getDocumentElement();
            if (!root.getTagName().equals("manifest")) throw new IOException("Expected <manifest>");
            NodeList sdk = root.getElementsByTagName("uses-sdk");
            if (sdk.getLength() > 1) throw new IOException("Multiple uses-sdk elements");
            Element uses = sdk.getLength() == 0 ? null : (Element) sdk.item(0);
            return new ManifestInfo(root.getAttribute("package"), number(uses, "minSdkVersion", 21),
                number(uses, "targetSdkVersion", 29), number(root, "versionCode", 1),
                value(root, "versionName", "1.0"));
        } catch (Exception error) {
            if (error instanceof CompatibilityException) throw (CompatibilityException) error;
            throw new CompatibilityException(file.getPath(), 0, "Invalid manifest: " + error.getMessage());
        }
    }
    private static String value(Element e, String key, String fallback) {
        String value = e == null ? "" : e.getAttributeNS(ANDROID, key);
        return value.isEmpty() ? fallback : value;
    }
    private static int number(Element e, String key, int fallback) {
        return Integer.parseInt(value(e, key, String.valueOf(fallback)));
    }
}
