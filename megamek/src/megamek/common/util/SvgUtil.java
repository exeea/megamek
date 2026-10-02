/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import org.apache.batik.anim.dom.SVGDOMImplementation;
import org.apache.batik.bridge.BridgeContext;
import org.apache.batik.bridge.GVTBuilder;
import org.apache.batik.bridge.UserAgentAdapter;
import org.apache.batik.dom.util.SAXDocumentFactory;
import org.apache.batik.gvt.GraphicsNode;
import org.apache.batik.util.XMLResourceDescriptor;
import org.w3c.dom.Document;

/** Batik SVG loading (desktop only): a file's SVG document and the graphics tree built from it. */
public final class SvgUtil {
    private SvgUtil() { }

    /** The file's SVG document, for callers that read or edit it before its graphics are built. */
    public static Document document(File file) throws IOException {
        try (InputStream input = Files.newInputStream(file.toPath())) {
            SAXDocumentFactory factory = new SAXDocumentFactory(SVGDOMImplementation.getDOMImplementation(),
                  XMLResourceDescriptor.getXMLParserClassName());
            return factory.createDocument(file.toURI().toASCIIString(), input);
        }
    }

    /**
     * A context for building graphics. A bound context keeps the element of every graphics node
     * ({@link BridgeContext#getElement}); otherwise the graphics are built static, for drawing only.
     */
    public static BridgeContext context(boolean bound) {
        BridgeContext context = new BridgeContext(new UserAgentAdapter());
        context.setDynamicState(bound ? BridgeContext.INTERACTIVE : BridgeContext.STATIC);
        return context;
    }

    /** The file's graphics tree, built static for drawing. */
    public static GraphicsNode graphics(File file) throws IOException {
        return new GVTBuilder().build(context(false), document(file));
    }
}
