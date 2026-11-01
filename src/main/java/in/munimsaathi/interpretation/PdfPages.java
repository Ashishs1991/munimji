package in.munimsaathi.interpretation;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;

final class PdfPages {

    private PdfPages() {
    }

    static String text(byte[] content) {
        if (content == null || content.length == 0) {
            return "This PDF has no extractable text.";
        }
        try (PDDocument document = PDDocument.load(content)) {
            String text = new PDFTextStripper().getText(document);
            if (text == null || text.isBlank()) {
                return "This PDF has no extractable text.";
            }
            return text.trim();
        } catch (IOException exception) {
            return "This PDF has no extractable text.";
        }
    }
}
