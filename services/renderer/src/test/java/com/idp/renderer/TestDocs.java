package com.idp.renderer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;

/** Documentos sinteticos generados en memoria para los tests (nunca datos reales). */
final class TestDocs {

    static final String EICAR =
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    private TestDocs() {}

    static byte[] pdf(int pages, String text, PDRectangle size, boolean withText) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(size);
                doc.addPage(page);
                if (withText) {
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 18);
                        cs.newLineAtOffset(50, 700);
                        cs.showText(text + " " + (i + 1));
                        cs.endText();
                    }
                }
            }
            return save(doc);
        }
    }

    static byte[] pdf(int pages, String text) throws IOException {
        return pdf(pages, text, PDRectangle.A4, true);
    }

    static byte[] pdfWithJavaScript() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            doc.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert('x');"));
            return save(doc);
        }
    }

    static byte[] pdfWithAttachment() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDComplexFileSpecification fs = new PDComplexFileSpecification();
            fs.setFile("a.txt");
            fs.setEmbeddedFile(
                    new PDEmbeddedFile(doc, new ByteArrayInputStream("hola".getBytes(StandardCharsets.UTF_8))));
            PDAnnotationFileAttachment att = new PDAnnotationFileAttachment();
            att.setFile(fs);
            att.setRectangle(new PDRectangle(10, 10, 20, 20));
            List<PDAnnotation> anns = new ArrayList<>(page.getAnnotations());
            anns.add(att);
            page.setAnnotations(anns);
            return save(doc);
        }
    }

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        doc.save(bos);
        return bos.toByteArray();
    }

    static byte[] image(String format) throws IOException {
        BufferedImage img = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 120; x++) {
            for (int y = 0; y < 80; y++) {
                img.setRGB(x, y, (x * 2) << 16 | (y * 3) << 8 | 0x40);
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (!ImageIO.write(img, format, bos)) {
            throw new IOException("sin writer " + format);
        }
        return bos.toByteArray();
    }

    static byte[] docx(Map<String, byte[]> extra) throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>").getBytes(StandardCharsets.UTF_8));
        parts.put("_rels/.rels", ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>").getBytes(StandardCharsets.UTF_8));
        parts.put("word/document.xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + "<w:p><w:r><w:t>Texto de prueba sintetico</w:t></w:r></w:p></w:body></w:document>")
                .getBytes(StandardCharsets.UTF_8));
        parts.putAll(extra);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    static byte[] docx() throws IOException {
        return docx(Map.of());
    }

    /** Lee un ZIP de respuesta a un mapa nombre -> bytes. */
    static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                out.put(e.getName(), in.readAllBytes());
            }
        }
        return out;
    }
}
