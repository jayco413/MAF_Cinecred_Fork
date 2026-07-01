package com.loadingbyte.cinecred.projectio

import ch.rabanti.nanoxlsx4j.Address
import ch.rabanti.nanoxlsx4j.styles.NumberFormat
import ch.rabanti.nanoxlsx4j.styles.NumberFormat.FormatNumber
import com.formdev.flatlaf.util.SystemInfo
import com.github.miachm.sods.OfficeAnnotation
import com.loadingbyte.cinecred.common.LOGGER
import com.loadingbyte.cinecred.common.execProcess
import com.loadingbyte.cinecred.common.l10n
import de.siegmar.fastcsv.reader.CsvReader
import de.siegmar.fastcsv.reader.StringArrayHandler
import de.siegmar.fastcsv.writer.CsvWriter
import jxl.CellView
import jxl.WorkbookSettings
import jxl.write.*
import org.slf4j.LoggerFactory
import java.io.*
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.*


class Spreadsheet private constructor(val name: String, val records: List<Record>) : Iterable<Spreadsheet.Record> {

    val numRecords: Int get() = records.size
    val numColumns: Int get() = records.maxOf { it.cells.size }

    operator fun get(recordNo: Int): Record = records[recordNo]
    operator fun get(recordNo: Int, columnNo: Int): String = records[recordNo].cells[columnNo]

    fun withName(name: String): Spreadsheet = Spreadsheet(name, records)
    fun map(transform: (String) -> String): Spreadsheet =
        Spreadsheet(name, records.map { Record(it.recordNo, it.cells.map(transform)) })

    override fun iterator(): Iterator<Record> = records.iterator()

    companion object {
        operator fun invoke(name: String, matrix: List<List<String>>) = Spreadsheet(name, matrix.mapIndexed(::Record))
    }

    class Record(val recordNo: Int, val cells: List<String>) {
        fun isNotEmpty() = cells.any { it.isNotEmpty() }
    }

}


// The formats are ordered according to decreasing preference.
val SPREADSHEET_FORMATS = listOf(XlsxFormat, XlsFormat, OdsFormat, NumbersFormat, CsvFormat)

private const val MAX_ROWS = 1_000_000
private const val MAX_COLS = 100


interface SpreadsheetFormat {

    val fileExt: String
    val label: String
    val available: Boolean get() = true

    /** @throws Exception */
    fun read(file: Path, defaultName: String): List<Spreadsheet> =
        read(file.inputStream(), defaultName)

    /**
     * @param stream Will be closed by this method.
     * @throws Exception
     */
    fun read(stream: InputStream, defaultName: String): List<Spreadsheet>

    /** @throws Exception */
    fun write(file: Path, spreadsheet: Spreadsheet, look: SpreadsheetLook) =
        write(file.outputStream(), spreadsheet, look)

    /**
     * @param stream Will be closed by this method.
     * @throws Exception
     */
    fun write(stream: OutputStream, spreadsheet: Spreadsheet, look: SpreadsheetLook)

}


class SpreadsheetLook(
    val frozenRows: Int,
    val rowLooks: Map<Int, RowLook>,
    /** Width of the columns, in characters. */
    val colWidths: List<Int>,
    val comments: List<Comment>
) {

    class RowLook(
        val fontSize: Int = -1,
        val bold: Boolean = false,
        val italic: Boolean = false
    )

    class Comment(val row: Int, val col: Int, val text: String)

}


object XlsxFormat : SpreadsheetFormat {

    override val fileExt get() = "xlsx"
    override val label get() = "Microsoft Excel 2007+"

    // Note: We have observed at least one case were ZipFile managed to read an XLSX while ZipInputStream failed.
    // Since NanoXLSX4j uses ZipFile when called with a file, we take that code path when we indeed want to read a file.
    // This maximizes our chances of evading the error case.
    override fun read(file: Path, defaultName: String) =
        read(ch.rabanti.nanoxlsx4j.Workbook.load(file.absolutePathString()))

    override fun read(stream: InputStream, defaultName: String): List<Spreadsheet> =
        read(stream.use { ch.rabanti.nanoxlsx4j.Workbook.load(stream) })

    private fun read(workbook: ch.rabanti.nanoxlsx4j.Workbook): List<Spreadsheet> {
        return List(workbook.worksheets.size) { sheetIdx ->
            val sheet = workbook.worksheets[sheetIdx]
            val numRows = (sheet.lastRowNumber + 1).coerceAtMost(MAX_ROWS)
            val numCols = (sheet.lastColumnNumber + 1).coerceAtMost(MAX_COLS)
            val matrix = List(numRows) { MutableList(numCols) { "" } }
            for (cell in sheet.cells.values)
                if (cell.rowNumber < MAX_ROWS && cell.columnNumber < MAX_COLS)
                    cell.value?.let { matrix[cell.rowNumber][cell.columnNumber] = it.toString() }
            Spreadsheet(sheet.sheetName, matrix)
        }
    }

    override fun write(stream: OutputStream, spreadsheet: Spreadsheet, look: SpreadsheetLook) = stream.use {
        val numCols = spreadsheet.numColumns

        val workbook = ch.rabanti.nanoxlsx4j.Workbook(spreadsheet.name)
        val sheet = workbook.worksheets[0]

        // Add the sheet content.
        for (record in spreadsheet)
            for ((col, cell) in record.cells.withIndex())
                if (cell.isNotEmpty())
                    sheet.addCell(cell, col, record.recordNo)

        // Set the frozen rows.
        if (look.frozenRows > 0)
            sheet.setHorizontalSplit(1, true, Address(0, 1), null)

        // Set the row heights & styles.
        for ((row, rowLook) in look.rowLooks) {
            sheet.setStyle(Address(0, row), Address(numCols - 1, row), createStyle(rowLook))
        }

        // Set the column widths & make them use the raw text data format.
        val defaultStyle = createStyle()
        for (col in 0..<numCols) {
            sheet.setColumnDefaultStyle(col, defaultStyle)
            look.colWidths.getOrNull(col)?.let { sheet.setColumnWidth(col, it * 0.5f) }
        }

        val baos = ByteArrayOutputStream()
        workbook.saveAsStream(baos)

        // NanoXLSX4j doesn't yet have the ability to write comments, so we patch the generated XLSX file ourselves.
        addComments(stream, baos.toByteArray(), look)
    }

    private fun createStyle() =
        ch.rabanti.nanoxlsx4j.styles.Style().apply {
            numberFormat = NumberFormat().apply { number = FormatNumber.format_49 }
        }

    private fun createStyle(rowLook: SpreadsheetLook.RowLook) =
        createStyle().apply {
            if (rowLook.fontSize != -1)
                font.size = rowLook.fontSize.toFloat()
            font.isBold = rowLook.bold
            font.isItalic = rowLook.italic
        }

    private fun addComments(stream: OutputStream, xlsx: ByteArray, look: SpreadsheetLook) {
        val zis = ZipInputStream(ByteArrayInputStream(xlsx))
        val zos = ZipOutputStream(stream)

        while (true) {
            val ze = zis.nextEntry ?: break
            zos.putNextEntry(ZipEntry(ze.name))
            when (ze.name) {
                "[Content_Types].xml" -> {
                    val insert = """
                       <Default ContentType="application/vnd.openxmlformats-officedocument.vmlDrawing" Extension="vml" />
                       <Override ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.comments+xml"
                                 PartName="/xl/comments1.xml"/>
                    """.trimIndent()
                    var xml = zis.reader().readAllAsString()
                    val idx = xml.lastIndexOf("</Types>")
                    xml = "${xml.substring(0, idx)}$insert${xml.substring(idx)}"
                    zos.write(xml.toByteArray())
                }
                "xl/worksheets/sheet1.xml" -> {
                    val insert1 = " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
                    val insert2 = "<legacyDrawing r:id=\"d\"/>"
                    var xml = zis.reader().readAllAsString()
                    val idx1 = xml.indexOf("<worksheet xmlns=") + 10
                    val idx2 = xml.lastIndexOf("</worksheet>")
                    xml = "${xml.substring(0, idx1)}$insert1${xml.substring(idx1, idx2)}$insert2${xml.substring(idx2)}"
                    zos.write(xml.toByteArray())
                }
                else -> zis.transferTo(zos)
            }
            zos.closeEntry()
        }

        zos.putNextEntry(ZipEntry("xl/worksheets/_rels/sheet1.xml.rels"))
        """
            <?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="c" Target="../comments1.xml"
                          Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments" />
            <Relationship Id="d" Target="../drawings/vmlDrawing1.vml"
                          Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/vmlDrawing" />
            </Relationships>
        """.trimIndent().toByteArray().let(zos::write)
        zos.closeEntry()

        zos.putNextEntry(ZipEntry("xl/comments1.xml"))
        zos.write(buildString {
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <comments xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <authors><author/></authors>
                <commentList>
            """.trimIndent().let(::append)
            for (comment in look.comments)
                """
<comment ref="${'A' + comment.col}${comment.row + 1}" authorId="0">
<text><t>${escapeXMLChars(comment.text).replace("\n", "\r\n")}</t></text>
</comment>""".let(::append)
            append("</commentList></comments>")
        }.toByteArray())
        zos.closeEntry()

        zos.putNextEntry(ZipEntry("xl/drawings/vmlDrawing1.vml"))
        zos.write(buildString {
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <xml xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:v="urn:schemas-microsoft-com:vml"
                     xmlns:x="urn:schemas-microsoft-com:office:excel">
                <o:shapelayout v:ext="edit"><o:idmap v:ext="edit" data="1"/></o:shapelayout>
                <v:shapetype id="_x0000_t1" coordsize="21600,21600" o:spt="202" path="m,l,21600r21600,l21600,xe">
                <v:stroke joinstyle="miter"/><v:path gradientshapeok="t" o:connecttype="rect"/>
                </v:shapetype>
            """.trimIndent().let(::append)
            for ((idx, comment) in look.comments.withIndex()) {
                var endColIdx = comment.col
                var width = 0
                while (width < 90)
                    width += look.colWidths.getOrElse(endColIdx++) { 17 }
                """
                    <v:shape id="_x0000_s$idx" type="#_x0000_t1" style="position: absolute; visibility:hidden"
                             fillcolor="infoBackground [80]" strokecolor="none [81]" o:insetmode="auto">
                    <v:fill color2="infoBackground [80]"/>
                    <v:shadow color="none [81]" obscured="t"/>
                    <v:path o:connecttype="none"/>
                    <v:textbox style="mso-direction-alt:auto"/>
                    <x:ClientData ObjectType="Note">
                    <x:MoveWithCells/><x:SizeWithCells/>
                    <x:Anchor>${comment.col},0,${comment.row},0,$endColIdx,0,${comment.row + 30},0</x:Anchor>
                    <x:AutoFill>False</x:AutoFill><x:Row>${comment.row}</x:Row><x:Column>${comment.col}</x:Column>
                    </x:ClientData>
                    </v:shape>
                """.trimIndent().let(::append)
            }
            append("</xml>")
        }.toByteArray())
        zos.closeEntry()

        zos.close()
    }

    // Taken from XlsxWriter.
    private fun escapeXMLChars(input: String): String {
        val len = input.length
        val illegalCharacters = ArrayList<Int>(len)
        val characterTypes = ArrayList<Int>(len)
        for (i in 0..<len) {
            val c = input[i].code
            if (c < 0x9 || c in 0xb..<0xD || c in 0xe..<0x20 || c in 0xd800..<0xE000 || c > 0xFFFD) {
                illegalCharacters.add(i)
                characterTypes.add(0)
                continue
            }
            // @formatter:off
            when (c) {
                0x3C -> { illegalCharacters.add(i); characterTypes.add(1) }
                0x3E -> { illegalCharacters.add(i); characterTypes.add(2) }
                0x26 -> { illegalCharacters.add(i); characterTypes.add(3) }
            }
            // @formatter:on
        }
        if (illegalCharacters.isEmpty())
            return input
        val sb = StringBuilder(len)
        var lastIndex = 0
        for ((i, j) in illegalCharacters.withIndex()) {
            sb.append(input, lastIndex, j)
            when (characterTypes[i]) {
                0 -> sb.append(' ')
                1 -> sb.append("&lt;")
                2 -> sb.append("&gt;")
                3 -> sb.append("&amp;")
            }
            lastIndex = j + 1
        }
        sb.append(input.substring(lastIndex))
        return sb.toString()
    }

}


object XlsFormat : SpreadsheetFormat {

    override val fileExt get() = "xls"
    override val label get() = "Microsoft Excel 97-2003"

    override fun read(stream: InputStream, defaultName: String): List<Spreadsheet> {
        val workbook = stream.use { jxl.Workbook.getWorkbook(it, WorkbookSettings().apply { encoding = "ISO-8859-1" }) }
        return List(workbook.numberOfSheets) { sheetIdx ->
            val sheet = workbook.getSheet(sheetIdx)
            val numRows = sheet.rows.coerceAtMost(MAX_ROWS)
            val numCols = sheet.columns.coerceAtMost(MAX_COLS)
            val matrix = List(numRows) { row -> List(numCols) { col -> sheet.getCell(col, row).contents } }
            Spreadsheet(sheet.name, matrix)
        }
    }

    override fun write(stream: OutputStream, spreadsheet: Spreadsheet, look: SpreadsheetLook) = stream.use {
        val workbook = jxl.Workbook.createWorkbook(stream)
        val sheet = workbook.createSheet(spreadsheet.name, 0)
        val defaultStyle = createStyle()

        // Add the sheet content, set the row looks, and add the comments.
        for (record in spreadsheet) {
            val row = record.recordNo
            val rowLook = look.rowLooks[row]
            val style = rowLook?.let(::createStyle) ?: defaultStyle
            for ((col, cell) in record.cells.withIndex())
                if (cell.isNotEmpty()) {
                    val label = Label(col, row, cell, style)
                    look.comments.find { it.row == row && it.col == col }?.let { comment ->
                        label.setCellFeatures(WritableCellFeatures().apply { setComment(comment.text) })
                    }
                    sheet.addCell(label)
                }
        }

        // Set the frozen rows.
        if (look.frozenRows > 0)
            sheet.settings.verticalFreeze = look.frozenRows

        // Set the column widths & make them use the raw text data format.
        for (col in 0..<spreadsheet.numColumns)
            sheet.setColumnView(col, CellView().apply {
                look.colWidths.getOrNull(col)?.let { size = it * 130 }
                format = defaultStyle
            })

        workbook.write()
        workbook.close()
    }

    private fun createStyle() =
        WritableCellFormat(NumberFormats.TEXT)

    private fun createStyle(rowLook: SpreadsheetLook.RowLook) =
        createStyle().apply {
            val font = WritableFont(
                WritableFont.ARIAL,
                if (rowLook.fontSize != -1) rowLook.fontSize else WritableFont.DEFAULT_POINT_SIZE,
                if (rowLook.bold) WritableFont.BOLD else WritableFont.NO_BOLD,
                rowLook.italic
            )
            setFont(font)
        }

}


object OdsFormat : SpreadsheetFormat {

    override val fileExt get() = "ods"
    override val label get() = "LibreOffice Calc"

    override fun read(stream: InputStream, defaultName: String): List<Spreadsheet> {
        val workbook = stream.use { com.github.miachm.sods.SpreadSheet(stream) }
        return List(workbook.numSheets) { sheetIdx ->
            val sheet = workbook.getSheet(sheetIdx)
            val numRows = sheet.maxRows.coerceAtMost(MAX_ROWS)
            val numCols = sheet.maxColumns.coerceAtMost(MAX_COLS)
            val matrix = List(numRows) { r -> List(numCols) { c -> sheet.getRange(r, c).value?.toString() ?: "" } }
            Spreadsheet(sheet.name, matrix)
        }
    }

    override fun write(stream: OutputStream, spreadsheet: Spreadsheet, look: SpreadsheetLook) = stream.use {
        val numRows = spreadsheet.numRecords
        val numCols = spreadsheet.numColumns

        val sheet = com.github.miachm.sods.Sheet(spreadsheet.name, numRows, numCols)

        // Add the sheet content.
        val cellMatrix = Array(numRows) { row -> Array(numCols) { col -> spreadsheet[row, col].ifEmpty { null } } }
        sheet.dataRange.values = cellMatrix

        // Set the frozen rows.
        if (look.frozenRows > 0)
            sheet.freezeRows(look.frozenRows)

        // Set the row looks.
        for ((row, rowLook) in look.rowLooks)
            sheet.getRange(row, 0, 1, numCols).style = createStyle(rowLook)

        // Set the column widths & make them use the raw text data format.
        val defaultStyle = createStyle()
        for (col in 0..<numCols) {
            sheet.setDefaultColumnCellStyle(col, defaultStyle)
            look.colWidths.getOrNull(col)?.let { sheet.setColumnWidth(col, it.toDouble()) }
        }

        // Add the comments.
        for (comment in look.comments)
            sheet.getRange(comment.row, comment.col).annotation =
                OfficeAnnotation(comment.text, LocalDateTime.of(2000, 1, 1, 0, 0, 0))

        val workbook = com.github.miachm.sods.SpreadSheet()
        workbook.appendSheet(sheet)
        workbook.save(stream)
    }

    private fun createStyle() =
        com.github.miachm.sods.Style().apply { dataStyle = "@" }

    private fun createStyle(rowLook: SpreadsheetLook.RowLook) =
        createStyle().apply {
            if (rowLook.fontSize != -1)
                fontSize = rowLook.fontSize
            isBold = rowLook.bold
            isItalic = rowLook.italic
        }

}


object NumbersFormat : SpreadsheetFormat {

    override val fileExt get() = "numbers"
    override val label get() = "Apple Numbers"

    override val available = SystemInfo.isMacOS && try {
        runScript("AppleScript", "tell application \"Numbers\" to id")
        true
    } catch (_: IOException) {
        LOGGER.warn("Apple Numbers is not installed.")
        false
    }

    override fun read(file: Path, defaultName: String): List<Spreadsheet> {
        if (!available)
            throw FormatUnavailableException(l10n("projectIO.spreadsheet.numbersUnavailable", ".numbers"))
        val script = """
            function run(argv) {
                const Numbers = Application("Numbers")
                const args = {to: Path(argv[1]), as: "Microsoft Excel", withProperties: {excludeSummaryWorksheet: true}}
                for (const doc of Numbers.documents()) {
                    if (doc.file() == argv[0]) {
                        Numbers.export(doc, args)
                        return
                    }
                }
                const doc = Numbers.open(Path(argv[0]))
                try {
                    Numbers.windows[0].visible = false
                    Numbers.export(doc, args)
                } finally {
                    Numbers.close(doc, {saving: "no"})
                }
            }
        """
        return withTempFile("cinecred-numbers2xlsx-", ".xlsx") { tmpFile ->
            runScript("JavaScript", script, file.absolutePathString(), tmpFile.pathString)
            XlsxFormat.read(tmpFile, defaultName)
        }
    }

    override fun read(stream: InputStream, defaultName: String) = stream.use {
        withTempFile("cinecred-numbers2xlsx-", ".numbers") { tmpFile ->
            tmpFile.outputStream().use(stream::transferTo)
            read(tmpFile, defaultName)
        }
    }

    override fun write(file: Path, spreadsheet: Spreadsheet, look: SpreadsheetLook) {
        if (!available)
            throw FormatUnavailableException(l10n("projectIO.spreadsheet.numbersUnavailable", ".numbers"))
        val script = """
            function run(argv) {
                const Numbers = Application("Numbers")
                const doc = Numbers.open(Path(argv[0]))
                try {
                    Numbers.windows[0].visible = false
                    Numbers.save(doc, {in: Path(argv[1])})
                } finally {
                    Numbers.close(doc, {saving: "no"})
                }
            }
        """
        withTempFile("cinecred-xlsx2numbers-", ".xlsx") { tmpFile ->
            XlsxFormat.write(tmpFile, spreadsheet, look)
            runScript("JavaScript", script, tmpFile.pathString, file.absolutePathString())
        }
    }

    override fun write(stream: OutputStream, spreadsheet: Spreadsheet, look: SpreadsheetLook) = stream.use {
        withTempFile("cinecred-xlsx2numbers-", ".numbers") { tmpFile ->
            write(tmpFile, spreadsheet, look)
            tmpFile.inputStream().use { it.transferTo(stream) }; Unit
        }
    }

    private fun <R> withTempFile(prefix: String, suffix: String, action: (Path) -> R): R {
        val tmpFile = createTempFile(prefix, suffix)
        try {
            return action(tmpFile)
        } finally {
            try {
                tmpFile.deleteExisting()
            } catch (e: IOException) {
                // Ignore; file will be deleted upon OS restart anyway.
                LOGGER.error("Cannot delete temporary XLSX file '{}' created for Apple Numbers.", tmpFile, e)
            }
        }
    }

    private fun runScript(language: String, script: String, vararg args: String) {
        val logger = LoggerFactory.getLogger("AppleNumbers")
        execProcess(listOf("osascript", "-l", language, "-") + args, stdin = script, logger = logger)
    }

}


object CsvFormat : SpreadsheetFormat {

    override val fileExt get() = "csv"
    override val label get() = l10n("projectIO.spreadsheet.csv")

    override fun read(stream: InputStream, defaultName: String) =
        listOf(read(stream.reader().use(Reader::readText), defaultName))

    fun read(text: String, name: String): Spreadsheet {
        // Trim the character which results from the byte order mark (BOM) added by Excel.
        val trimmed = text.removePrefix(0xFEFF.toChar().toString())

        // Parse the CSV file into a string matrix.
        val matrix = CsvReader.builder().skipEmptyLines(false).build(StringArrayHandler.of(), trimmed).use { reader ->
            buildList {
                var row = 0
                for (line in reader) {
                    add(if (line.size <= MAX_ROWS) line.asList() else line.copyOf(MAX_ROWS).asList())
                    if (++row == MAX_ROWS)
                        break
                }
            }
        }

        // Create a spreadsheet.
        return Spreadsheet(name, matrix)
    }

    override fun write(stream: OutputStream, spreadsheet: Spreadsheet, look: SpreadsheetLook) {
        CsvWriter.builder().build(stream).use { writer ->
            for (record in spreadsheet)
                writer.writeRecord(record.cells)
        }
    }

}


class FormatUnavailableException(message: String) : IOException(message)
