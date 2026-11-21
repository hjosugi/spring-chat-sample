package io.github.hjosugi.chatsample.rag;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

/**
 * A small PDF handbook of the shop, made with PDFBox so that what is in it is in plain
 * sight: one chapter per page, each with a running header at the top, a body, and the
 * page number at the bottom, and a table of contents (an outline) with a bookmark per
 * chapter.
 */
final class Handbook {

	static final String HEADER = "Coffee Gear Handbook";

	/**
	 * The chapters, one per page: the title, then the body.
	 */
	static final List<List<String>> CHAPTERS = List.of(List.of("Grinders", "Burr grinders grind evenly."),
			List.of("Kettles", "Gooseneck kettles pour slowly."), List.of("Filters", "Paper filters come in packs."),
			List.of("Scales", "Scales weigh to a tenth of a gram."), List.of("Beans", "Beans are roasted on Mondays."));

	private Handbook() {
	}

	/**
	 * Writes the handbook to a file.
	 * @param withOutline whether it has a table of contents
	 */
	static Path write(Path file, boolean withOutline) {
		PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
		try (PDDocument document = new PDDocument()) {
			PDDocumentOutline outline = new PDDocumentOutline();
			for (int i = 0; i < CHAPTERS.size(); i++) {
				PDPage page = new PDPage(PDRectangle.A4);
				document.addPage(page);
				try (PDPageContentStream content = new PDPageContentStream(document, page)) {
					// A4 is 842 points high: the header is 42 points below the top edge,
					// the page number 30 points above the bottom edge.
					line(content, font, 800, HEADER);
					line(content, font, 600, CHAPTERS.get(i).get(0));
					line(content, font, 580, CHAPTERS.get(i).get(1));
					line(content, font, 30, "Page " + (i + 1));
				}
				PDPageXYZDestination destination = new PDPageXYZDestination();
				destination.setPage(page);
				destination.setTop((int) page.getMediaBox().getHeight());
				PDOutlineItem bookmark = new PDOutlineItem();
				bookmark.setTitle(CHAPTERS.get(i).get(0));
				bookmark.setDestination(destination);
				outline.addLast(bookmark);
			}
			if (withOutline) {
				document.getDocumentCatalog().setDocumentOutline(outline);
			}
			document.save(file.toFile());
			return file;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static void line(PDPageContentStream content, PDType1Font font, float y, String text) throws IOException {
		content.beginText();
		content.setFont(font, 12);
		content.newLineAtOffset(50, y);
		content.showText(text);
		content.endText();
	}

	/**
	 * Text with its runs of white space made one space: the PDF readers lay the text out
	 * as it is placed on the page.
	 */
	static String squeezed(String text) {
		return text.replaceAll("\\s+", " ").strip();
	}

}
