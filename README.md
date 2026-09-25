# Vigyan Scanner

An Android app for scanning documents with your phone camera.

## Features
1. **Searchable PDF**: every PDF has an invisible text layer, so you can search it and copy text from it in any viewer or in Google Drive.
2. **Aadhaar QR**: Smart Fill reads the QR code on Aadhaar cards (old XML and the newer Secure QR). That data is exactly what UIDAI holds and takes priority over the printed text.
3. **Batch fill forms**: scan many forms in one go (1–3 pages each). Each form is filled and named after the student, and they go into a new folder. You then export one CSV.
4. **Hindi**: ⋮ › Text language › English + Hindi (the Devanagari model; it also reads English).
5. **Open from WhatsApp / Gallery / Files**: share a photo or PDF to the app, or use "Open a photo or PDF from the phone".
6. **Edit pages**: add pages, delete them, move them, or rotate them.
7. **Merge**: press and hold scans to select them, then tap Merge. The order you tap them is the page order.
8. **Size**: Small (WhatsApp/email), Normal, or High (print). Applies to PDF and JPG.
9. **ID card on A4**: pages at real card size (85.6 × 54 mm) on one A4 sheet.
10. **Password**: lock the PDF with an open password (standard 128-bit PDF encryption).
11. **Search**: finds words in scan names, OCR text and filled forms.
12. **Folders**: chips on the home screen; you can move scans between folders.
13. **Auto-name**: a scan is named after the person once Smart Fill finds a name (only if it still has its default name).

Also: save as PDF / JPG / TXT to `Download/Vigyan Scanner` or to Google Drive, or share to any app. Smart Fill can also save to CSV (its columns match the Vigyan ERP student import) or add a phone contact.

Needs Android 7 or newer with Google Play services.

## Build
GitHub Actions builds every push to `main` (`.github/workflows/build-apk.yml`): it runs the unit tests, builds the APK and publishes a Release. The latest APK:
`https://github.com/vigyaninternational/vigyan-scanner/releases/latest/download/VigyanScanner.apk`

## Code
- `PdfWriter.kt`: PDF writer (JPEG pages, invisible OCR text, RC4-128 password). Tested with PDFBox.
- `AadhaarQr.kt`, `FormExtractor.kt`: Smart Fill (plain Kotlin, unit-tested)
- `Ocr.kt`: ML Kit text (Latin / Devanagari) and QR reading
- `Images.kt`: resizing, rotating, importing photos/PDFs
- `ScanRepository.kt`: scans in the app's private storage (page order, per-page OCR, folders)
- `Exporter.kt`: PDF layouts, save to phone, Drive, share, CSV
- `ui/`: Compose screens
