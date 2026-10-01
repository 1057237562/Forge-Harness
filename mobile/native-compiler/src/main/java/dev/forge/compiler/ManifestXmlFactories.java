package dev.forge.compiler;

import javax.xml.parsers.*;

/** Explicit providers avoid Android's fixed JAXP factories and do not alter process properties. */
public final class ManifestXmlFactories {
    private ManifestXmlFactories() { }
    public static SAXParserFactory sax() { return new org.apache.xerces.jaxp.SAXParserFactoryImpl(); }
    public static SAXParserFactory sax(String ignored, ClassLoader loader) { return sax(); }
    public static DocumentBuilderFactory dom() { return new org.apache.xerces.jaxp.DocumentBuilderFactoryImpl(); }
    public static DocumentBuilderFactory dom(String ignored, ClassLoader loader) { return dom(); }
}
