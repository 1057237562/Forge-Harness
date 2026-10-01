package dev.forge.build;

import java.io.*;
import java.util.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

final class PomXml {
    static Element read(File file) throws IOException {
        String text = WorkspaceFiles.readUtf8(file, 2 * 1024 * 1024);
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) throw new IOException("POM DTD/entities are not supported");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true); factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((id, system) -> new InputSource(new StringReader("")));
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            });
            Element element = builder.parse(new InputSource(new StringReader(text))).getDocumentElement();
            if (!name(element).equals("project")) throw new IOException("POM root must be project");
            return element;
        } catch (Exception error) { throw new IOException("Invalid POM " + file.getName() + ": " + error.getMessage(), error); }
    }
    static String name(Element e) { return e.getLocalName() == null ? e.getTagName() : e.getLocalName(); }
    static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        if (parent != null) for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element && (name == null || name((Element) node).equals(name))) result.add((Element) node);
        return result;
    }
    static Element child(Element parent, String name) {
        List<Element> elements = children(parent, name); return elements.isEmpty() ? null : elements.get(0);
    }
    static String text(Element parent, String name, String fallback) {
        Element element = child(parent, name); return element == null ? fallback : element.getTextContent().trim();
    }
}
