package com.vigyan.scanner.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vigyan.scanner.BuildConfig

/** One help topic: [id] is what the ⓘ button on a screen opens. */
class HelpTopic(val id: String, val icon: String, val title: String, val intro: String, val steps: List<String>, val tips: List<String> = emptyList())

val HelpTopics = listOf(
    HelpTopic(
        "start", "👋", "Getting started",
        "Vigyan Scanner turns paper into PDFs, pictures and text, fills forms from scans, and makes passport photos and portal files. Everything stays on this phone until you save or share it.",
        listOf(
            "⚡ Quick scan: the fastest way for many pages. The camera stays open.",
            "👤 Scan & merge by name: all of one person's documents in one PDF named after them.",
            "📄 Scan document: Google's scanner, with automatic edges and cropping.",
            "🔤 Scan to text: read the words on a page, then copy, edit or save them.",
            "📝 Scan & fill a form: read the details (name, DOB, Aadhaar, marks…) from a form, ID card or certificate.",
            "📂 Open photo / PDF: bring in files from WhatsApp, the gallery or Files. You can also share files to Vigyan Scanner from other apps.",
            "Your scans are listed at the bottom of the home screen. Tap one to open it.",
        ),
        listOf("Tap ⓘ at the top of any screen for help with that screen, and 🏠 to come back home."),
    ),
    HelpTopic(
        "quick", "⚡", "Quick scan (many pages, fast)",
        "The camera stays open and each page is added straight away, with no screen to check in between.",
        listOf(
            "Tap the ⚡ Quick scan card.",
            "Hold the phone over the page. With ⚡ Auto on, it takes the picture by itself when the page is still. Or tap the round button, or press a volume key.",
            "Turn to the next page and repeat.",
            "✕ on the small picture removes the last page.",
            "If a page looks blurry, dark or has a glare, a warning appears. Tap Retake: the clearer of the two shots is kept.",
            "Several documents in one go: tap ✂ New document between them, or turn on \"Blank page = next document\" and put a blank sheet between documents. Each one is saved separately in a new folder.",
            "Tap Done.",
        ),
        listOf("🔦 Light turns on the torch in a dark room.", "✨ Enhance makes paper whiter and text darker.", "The start settings for Auto and Enhance are in ⚙ Settings."),
    ),
    HelpTopic(
        "person", "👤", "Scan & merge by name",
        "For admissions: scan the 10th, +2, CLC, Aadhaar or any other documents of one student into one PDF named after them, e.g. RAHUL_KUMAR.pdf. No student record is made.",
        listOf(
            "Tap the 👤 Scan & merge by name card.",
            "Type the name (and a reference number if you like).",
            "Choose how to add pages: ⚡ Quick scan, 📄 Document scanner, or 📂 photos / PDFs already on the phone.",
            "On the next screen, arrange the pages with ◀ ▶, turn, crop or delete them.",
            "Missing a document? Tap + Add pages any time, even days later.",
            "Check the file name under \"Save or upload as\" and tap Save to phone, Google Drive, Share or 🖨 Print.",
        ),
        listOf("Change the suggested order of documents in ⚙ Settings.", "Tap Edit on the name card to correct the name."),
    ),
    HelpTopic(
        "pages", "📄", "Working with a scan's pages",
        "Open a scan to see its pages. The buttons under each page:",
        listOf(
            "Tap a page to see it full screen. Pinch to zoom.",
            "◀ ▶ move the page earlier or later.",
            "⟳ turns it a quarter turn.",
            "⛶ crop / straighten: drag the 4 corners onto the page's corners, then Apply.",
            "🎨 page look: Brighten, Remove shadows, Grayscale, Black & white, or Clear background pattern (for certificates). Tick \"all pages\" to do every page.",
            "✏ write on the page: pen, highlight, text, ticks, date, signature, seal, photo.",
            "✕ deletes the page.",
            "+ Add pages adds more at the end. Split / extract pages makes a new scan from some pages.",
        ),
        listOf("Changed a page by mistake? 🎨 or ✏ › \"Back to the original page\" undoes all changes to it."),
    ),
    HelpTopic(
        "save", "💾", "Saving, sharing and printing",
        "At the bottom of a scan, under \"Save or upload as\":",
        listOf(
            "Check the file name. You can type your own.",
            "Pick PDF, JPG, PNG and/or Text.",
            "Size: Small (WhatsApp, email), Normal, or High (printing).",
            "Pages: all, or \"Only some\" to send just the pages you tap.",
            "PDF options: paper size (A4, A5, Letter), page numbers, searchable text, ID card layout (Aadhaar front and back on one A4), password.",
            "College stamp: space for the letter pad, \"Attested - true copy\", principal's signature, college seal.",
            "Tap Save to phone, Google Drive, Share, or 🖨 Print.",
        ),
        listOf(
            "Save to phone puts files in Download/Vigyan Scanner, or the folder you choose in ⚙ Settings.",
            "Google Drive opens the Drive app so you can pick the account and folder.",
            "Remember a PDF password: nobody can open the file without it.",
        ),
    ),
    HelpTopic(
        "text", "🔤", "Text (OCR) and Excel",
        "The app reads printed text from a scan.",
        listOf(
            "Open a scan and tap Text (OCR).",
            "Check and correct the text, then Copy, Save edits or Send.",
            "Save as a .txt file, or as an Excel table (each line a row, each separate piece of text a column).",
            "⟳ at the top reads the text again.",
            "The language (English, Hindi, Odia) is chosen in ⚙ Settings. Odia downloads its reading data once (about 5 MB).",
        ),
        listOf("Clear printed text reads best. Handwriting may come out wrong.", "Scan to Excel on a scan does the same for tables."),
    ),
    HelpTopic(
        "fill", "📝", "Smart Fill: details from forms, ID cards and certificates",
        "Smart Fill finds the name, parents, date of birth, mobile, Aadhaar, address, school, exam, marks, total, percentage and grade.",
        listOf(
            "Open a scan and tap Smart Fill (or use the 📝 Scan & fill card).",
            "Check every box against the paper and correct anything wrong.",
            "Aadhaar: the QR code is read too, which is the most reliable.",
            "Board certificates and marksheets (BSE 10th, CHSE +2): the name, mother, father, DOB, roll and registration numbers, school, exam, stream, division and marks are read, even with the coloured background.",
            "Tap Save. The scan is named after the person.",
            "Many forms at once: 🗂️ Batch fill forms, then Export filled forms (CSV or Excel) from the ⋮ menu. The CSV opens in the Vigyan ERP student import.",
        ),
        listOf("If something is read wrongly, ⟳ reads it again. A clearer scan helps the most."),
    ),
    HelpTopic(
        "formfill", "✏", "Filling in a paper form",
        "Scan a blank form, then write the details onto it and print or send it.",
        listOf(
            "First save the person's details: Smart Fill on their Aadhaar, ID card or certificate, then Save.",
            "Scan the blank form and open ✏ on its page.",
            "⋮ › Whose details: pick the person.",
            "⋮ › 🪄 Auto-fill blank fields: the app finds \"Name : ____\" style fields and writes the details in.",
            "Or place things yourself: 🔤 Details, ✓ Tick, ✗ Cross, 📅 Date, T Text, ✍️ Signature, 🖼 Photo (uses the passport photo you made), 🔵 Seal.",
            "✋ Move / size: drag anything to adjust it, and use Smaller / Bigger.",
            "Save writes it onto the page. Then save it as a PDF or 🖨 Print on A4.",
        ),
        listOf(
            "⋮ › Save as draft keeps your work to finish later.",
            "⋮ › Save as a form template remembers where everything goes, so next time the same form fills in one tap.",
        ),
    ),
    HelpTopic(
        "idcards", "🪪", "ID cards on A4 (Aadhaar, PAN…)",
        "Prints small cards at their real size on one A4 sheet, like a photocopy, landscape or portrait.",
        listOf(
            "Tap the 🪪 ID cards on A4 card.",
            "Scan the front of the first card, then its back, then the next card, and so on. The scanner crops each card.",
            "Choose A4 landscape (sideways) or portrait, real size or bigger (1.5×), and \"front and back side by side\".",
            "The small picture shows where each card goes, and how many sheets it makes.",
            "Save to phone, Google Drive, Share, or 🖨 Print.",
            "For cards already scanned: open the scan and tap 🪪 ID cards on A4.",
        ),
        listOf("Wrong order? Close the box, move pages with ◀ ▶, turn with ⟳, then tap 🪪 ID cards on A4 again.", "Print at 100% (actual size) so the cards come out at real size."),
    ),
    HelpTopic(
        "compress", "🗜", "Compress a PDF",
        "Makes a big PDF (e.g. from WhatsApp) smaller, for email or a portal limit.",
        listOf(
            "Tap the 🗜 Compress PDF card and pick the PDF.",
            "Choose the limit: 100 KB, 200 KB, 500 KB, 1 MB, 2 MB, or type your own.",
            "Black & white makes it much smaller.",
            "Tap \"Check the result first\" to see the new size.",
            "Save to phone, Google Drive or Share. The original file is not changed.",
            "A scan in the app can be compressed too: open it and tap 🗜 Compress.",
        ),
        listOf("The pages become pictures, so text in the smaller PDF can't be selected."),
    ),
    HelpTopic(
        "passport", "🧑", "Passport photo studio",
        "Makes passport and ID photos from a photo or a scan.",
        listOf(
            "Tap Passport photo on the home screen: take a photo or pick one from the gallery.",
            "Pick the size: 35×45 mm, 2×2 inch, 30×40 mm and others, or type your own in mm.",
            "The app finds the face and crops around it. Adjust the face size and move it with ← → ↑ ↓, or use ✂ Crop by hand and drag the frame yourself.",
            "⟲ ⟳ turn a sideways photo.",
            "Background: white, light blue, blue, red, grey or the original. Adjust brightness and contrast.",
            "Name and date at the bottom: tick it, type the name and date, and tap Put on the photo (for SSC, UPSC and other portals).",
            "Get it as: JPG, PNG, under a KB limit, a 6×4 print sheet, or an A4 sheet (choose margin, gap, rows and columns).",
        ),
        listOf("Print the sheets at 100% / actual size, not \"fit to page\", so the photos come out at the right size.", "Best photo: face the camera, even light, plain wall behind."),
    ),
    HelpTopic(
        "signature", "✍️", "Signature studio",
        "Cuts a signature or stamp out of white paper, with a see-through background.",
        listOf(
            "Sign on clean white paper and tap the ✍️ Signature cut-out card to scan it.",
            "⟲ ⟳ and the slider turn and straighten it.",
            "Keep fainter strokes and Ink darkness adjust the ink. Ink colour: original, black or blue.",
            "🧽 Eraser: drag over dots or lines to rub them out.",
            "Save as PNG (see-through) or JPG, at the width you want.",
            "For a portal: pick 140×60 px 10–20 KB or another size, or type your own.",
            "My signatures: save it with a name to use it on forms (✏ › Signature). It stays inside this app.",
        ),
        listOf("Use as principal's signature / college seal sets the college stamp."),
    ),
    HelpTopic(
        "portal", "📐", "Resize for portal",
        "Makes a photo, signature or PDF exactly the size a website asks for.",
        listOf(
            "Tap the 📐 Resize for portal card and pick the file, or open a scan and tap Resize for portal.",
            "Pick a preset (e.g. photo 413×531 px, 20–50 KB) or Custom: width, height, min and max KB.",
            "Black & white makes files much smaller. PNG instead of JPG if the portal asks for PNG.",
            "Tap \"Check the result first\" to see the KB, the pixel size, and whether it meets the rules.",
            "Then Save to phone, Google Drive or Share.",
        ),
        listOf("Save a custom setting with a name (e.g. \"OJEE photo\"). It appears at the top with a ★ next time.", "A warning means the file had to be squeezed a lot: allow more KB if the portal permits."),
    ),
    HelpTopic(
        "stamp", "🏫", "College stamp, seal and letter pad",
        "Put the college's attestation on saved copies.",
        listOf(
            "⋮ › College stamp & signature: type the college name and \"Signs as\".",
            "Scan the principal's signature and the college seal on white paper, then \"Use as…\".",
            "When saving a scan, tick \"Attested - true copy\", the signature and/or the seal.",
            "\"Space for letter pad\" puts the scan on A4 with blank space at the top and bottom, to print on the college letter pad.",
        ),
    ),
    HelpTopic(
        "files", "🗂️", "Finding and organising scans",
        "The list at the bottom of the home screen.",
        listOf(
            "Search: names, reference numbers and any text in the scans.",
            "Date buttons: Today, Last 7 days, Last 30 days, This year, Older.",
            "★ Favourites: open a scan and tap ☆ at the top.",
            "Folders: tap a folder to see only its scans. + New folder makes one.",
            "Press and hold a scan to select several: Merge, Folder, Delete, and ⋮ for favourites, copies and Rename all.",
            "Auto-sort (⋮ menu) files new scans by type: Aadhaar cards, marksheets and so on.",
            "Deleted scans go to 🗑 Recycle bin (⋮ menu) for 30 days. Restore them from there.",
        ),
        listOf("⋮ › Storage used shows how much space the app takes, and clears temporary files."),
    ),
    HelpTopic(
        "backup", "💾", "Backup, app lock and updates",
        "Keep your scans safe.",
        listOf(
            "⋮ › Backup & restore: make a backup file and save it to your own Google Drive or the phone. Each phone has its own backup.",
            "Restore adds anything missing; it never deletes.",
            "⋮ › App lock: a PIN (and fingerprint) to open the app. It locks again after the time you choose.",
            "Updates: a banner appears when a new version is out. Tap Update. Your scans are kept. Or ⋮ › Check for updates.",
            "⋮ › Share this app: send the download link (for WhatsApp) or the app file (Bluetooth, Quick Share).",
        ),
        listOf("The first update asks to allow \"Install unknown apps\" for Vigyan Scanner. Turn it on, come back, and tap Update again."),
    ),
    HelpTopic(
        "settings", "⚙", "Settings",
        "⋮ › ⚙ Settings.",
        listOf(
            "Look: phone setting, light or dark.",
            "Saving: the size chosen first, the file name format (Name, Name_Date, Date_Name…), and where Save to phone puts files (any folder you choose).",
            "Scan & merge by name: the usual order of documents.",
            "Quick scan: start with Auto and Enhance on or off, and blurry / dark / glare warnings.",
            "Reading text: English, English + Hindi, English + Odia.",
            "Backup reminder, update check and app lock.",
        ),
    ),
    HelpTopic(
        "trouble", "🛠", "Problems and tips",
        "If something doesn't work as expected:",
        listOf(
            "Text or details read wrongly: rescan in good light, hold the phone straight above the page, and avoid shadows. For certificates with a coloured pattern, try 🎨 › Clear background pattern.",
            "Scanner won't start: update Google Play services from the Play Store.",
            "Google Drive button doesn't open Drive: install the Google Drive app, or use Share.",
            "Update won't install: allow \"Install unknown apps\" for Vigyan Scanner in the phone's settings.",
            "Phone running out of space: ⋮ › Storage used › Clear temporary files, and empty the Recycle bin.",
            "Always check details against the paper before using them. Reading can make mistakes; it does not prove a document is genuine.",
        ),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(topic: String, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf(setOf(topic.ifBlank { "start" })) }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) HelpTopics else HelpTopics.filter { t ->
        (t.title + " " + t.intro + " " + t.steps.joinToString(" ") + " " + t.tips.joinToString(" ")).lowercase().contains(q)
    }
    val list = rememberLazyListState()
    LaunchedEffect(topic) {
        val i = HelpTopics.indexOfFirst { it.id == topic }
        if (i > 0) list.scrollToItem(i + 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Help & guide") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { HelpHomeActions(topic = "start", showHelp = false) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            state = list,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search help, e.g. passport, KB, Excel") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear") } },
                )
            }
            if (shown.isEmpty()) item { Text("Nothing found. Try another word.") }
            items(shown, key = { it.id }) { t ->
                val expanded = q.isNotEmpty() || t.id in open
                Card(
                    Modifier.fillMaxWidth().animateContentSize().clickable { open = if (t.id in open) open - t.id else open + t.id },
                    colors = if (t.id == topic) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.icon, fontSize = 22.sp)
                            Spacer(Modifier.width(10.dp))
                            Text(t.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(if (expanded) "▲" else "▼")
                        }
                        if (expanded) {
                            Text(t.intro, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                            t.steps.forEachIndexed { i, s ->
                                Row(Modifier.padding(top = 6.dp)) {
                                    Text("${i + 1}.", fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
                                    Text(s, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            t.tips.forEach { tip ->
                                Text("💡 $tip", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Vigyan Scanner ${BuildConfig.VERSION_NAME} · Vigyan International Junior College",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
