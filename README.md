# Vigyan Scanner

An Android app for scanning documents with your phone camera.

- **Scan document**: Google's scanner finds the page edges, straightens and crops the page, and can add filters. It handles many pages and can also import photos from the gallery.
- **Save as** PDF, JPG (one per page) or text (.txt), to **phone storage** (`Download/Vigyan Scanner`) or **Google Drive** (Drive opens so you can pick the account and folder), or share to any app.
- **Scan to text (OCR)**: reads printed English text on the phone, with no internet needed after the first use. You can edit it, copy it, and save or share it.
- **Scan & fill a form**: reads an admission form, Aadhaar card or ID and fills in Name, Father's/Mother's name, DOB, Gender, Mobile, Email, Aadhaar, Roll/Admission no., Address and PIN. You check and edit the result, then save, copy, add it as a phone contact, or export it as CSV. The CSV columns match the Vigyan ERP student CSV import. ⋮ › *Export all filled forms* puts every form into one sheet.

Needs Android 7 or newer with Google Play services.

## Build
GitHub Actions builds every push to `main` (`.github/workflows/build-apk.yml`): it runs the unit tests, builds the APK and publishes it under Releases. The latest APK:
`https://github.com/<owner>/<repo>/releases/latest/download/VigyanScanner.apk`

To build on a PC, open the folder in Android Studio.

## Code
- `FormExtractor.kt`: Smart Fill rules (plain Kotlin, unit-tested in `app/src/test`)
- `Ocr.kt`: ML Kit text recognition
- `ScanRepository.kt`: saved scans live in the app's private storage
- `Exporter.kt`: save to phone, Drive, share, and CSV
- `ui/`: Compose screens
