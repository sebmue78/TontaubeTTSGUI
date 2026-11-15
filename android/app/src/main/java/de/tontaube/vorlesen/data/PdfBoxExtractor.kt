package de.tontaube.vorlesen.data

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import de.tontaube.core.DocumentException
import de.tontaube.core.PdfTextExtractor
import java.io.IOException

/** Text eines PDFs seitenweise über PdfBox-Android (Absätze durch Leerzeilen getrennt). */
class PdfBoxExtractor(private val context: Context) : PdfTextExtractor {
    @Volatile private var initialized = false

    override fun pages(bytes: ByteArray): List<String> {
        if (!initialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }
        val doc = try {
            PDDocument.load(bytes)
        } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            throw DocumentException("Das PDF ist passwortgeschützt.", e)
        } catch (e: IOException) {
            throw DocumentException("Das PDF konnte nicht gelesen werden (${e.message}).", e)
        }
        doc.use { pdf ->
            if (pdf.isEncrypted && !pdf.currentAccessPermission.canExtractContent()) {
                throw DocumentException("Das PDF erlaubt kein Auslesen des Textes.")
            }
            val stripper = PDFTextStripper().apply {
                paragraphStart = ""
                paragraphEnd = "\n\n"
                lineSeparator = "\n"
                pageEnd = ""
            }
            return (1..pdf.numberOfPages).map { page ->
                stripper.startPage = page
                stripper.endPage = page
                stripper.getText(pdf)
            }
        }
    }
}
