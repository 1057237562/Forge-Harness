package dev.forge.compiler;

import dev.forge.build.*;
import java.io.*;
import java.util.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

final class ManifestPreparer {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    static void prepare(ProjectModel project, File destination) throws Exception {
        String text = WorkspaceFiles.readUtf8(WorkspaceFiles.resolve(project.root, project.manifest), 1024 * 1024);
        if (text.toUpperCase(Locale.ROOT).contains("<!DOCTYPE") || text.toUpperCase(Locale.ROOT).contains("<!ENTITY"))
            throw new IOException("Manifest DTD/entity declarations are not supported");
        text = text.replace("${applicationId}", project.applicationId);
        if (text.contains("${")) throw new CompatibilityException(project.manifest, 0, "Unknown manifest placeholder; declare concrete values");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true); factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((id, system) -> new InputSource(new StringReader("")));
        Document document = builder.parse(new InputSource(new StringReader(text)));
        Element manifest = document.getDocumentElement();
        String oldPackage = manifest.getAttribute("package");
        if (oldPackage.isEmpty()) oldPackage = project.namespace;
        manifest.setAttribute("package", project.applicationId);
        manifest.setAttributeNS(ANDROID, "android:versionCode", String.valueOf(project.versionCode));
        manifest.setAttributeNS(ANDROID, "android:versionName", project.versionName);
        Set<String> components = new HashSet<>(Arrays.asList("application", "activity", "activity-alias", "service", "receiver", "provider", "instrumentation"));
        NodeList elements = document.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            if (components.contains(element.getTagName())) {
                expand(element, "name", oldPackage);
                expand(element, "targetActivity", oldPackage);
                expand(element, "parentActivityName", oldPackage);
            }
            if (element.getTagName().equals("application")) element.setAttributeNS(ANDROID, "android:debuggable", "true");
        }
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.transform(new DOMSource(document), new StreamResult(destination));
    }
    private static void expand(Element element, String attribute, String pkg) {
        String value = element.getAttributeNS(ANDROID, attribute);
        if (value.isEmpty() || value.startsWith("@")) return;
        if (value.startsWith(".")) value = pkg + value;
        else if (!value.contains(".")) value = pkg + "." + value;
        element.setAttributeNS(ANDROID, "android:" + attribute, value);
    }
}
