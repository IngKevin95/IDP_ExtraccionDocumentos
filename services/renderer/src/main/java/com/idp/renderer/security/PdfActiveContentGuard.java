package com.idp.renderer.security;

import com.idp.renderer.core.RenderException;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Rechaza PDFs con JavaScript, acciones ejecutables o adjuntos embebidos. Recorre todos los objetos del
 * documento (no solo los alcanzables) de forma conservadora.
 */
public final class PdfActiveContentGuard {

    private static final COSName LAUNCH = COSName.getPDFName("Launch");
    private static final COSName RICH_MEDIA = COSName.getPDFName("RichMedia");
    private static final COSName FILE_ATTACHMENT = COSName.getPDFName("FileAttachment");
    private static final COSName AF = COSName.getPDFName("AF");

    private PdfActiveContentGuard() {}

    public static void check(PDDocument doc) {
        COSDocument cos = doc.getDocument();
        for (COSObjectKey key : cos.getXrefTable().keySet()) {
            COSBase obj = cos.getObjectFromPool(key).getObject();
            if (obj instanceof COSDictionary d) {
                inspect(d);
            }
        }
    }

    private static void inspect(COSDictionary d) {
        if (d.containsKey(COSName.JS)) {
            throw RenderException.activeContent("El PDF contiene JavaScript.");
        }
        COSName action = d.getCOSName(COSName.S);
        if (COSName.JAVA_SCRIPT.equals(action) || LAUNCH.equals(action)) {
            throw RenderException.activeContent("El PDF contiene acciones ejecutables.");
        }
        COSName subtype = d.getCOSName(COSName.SUBTYPE);
        if (RICH_MEDIA.equals(subtype)) {
            throw RenderException.activeContent("El PDF contiene contenido multimedia activo.");
        }
        if (FILE_ATTACHMENT.equals(subtype) || d.containsKey(COSName.EF) || d.containsKey(AF)
                || COSName.EMBEDDED_FILE.equals(d.getCOSName(COSName.TYPE))) {
            throw RenderException.activeContent("El PDF contiene adjuntos embebidos.");
        }
    }
}
