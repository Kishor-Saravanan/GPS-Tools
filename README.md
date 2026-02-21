# 📍 GPS Tools — Android App

Scans photos/videos, separates them by GPS metadata, auto-tags files using timestamp matching, and exports a CSV report.

## Supported File Types
- **Images:** JPG, JPEG, PNG, HEIC, HEIF, DNG (GPS read + write)
- **Videos:** MP4, M4V (GPS read; write depends on device/Android version)

## Features
- Recursively scan thousands of files
- Separates files with/without GPS automatically
- Auto-tags files without GPS by matching timestamps with nearby GPS-containing files
- Configurable time window (±1min to ±2hr)
- Exports full CSV report
- Dark themed UI with live log

---

## 🚀 Deploy to GitHub (No Android Studio Needed)

### Step 1 — Create GitHub Repository
1. Go to https://github.com/new
2. Name: `GPS-Tools`
3. Make it **Public**
4. Click **Create repository** (don't add README)

### Step 2 — Push Code
Open Git Bash (Windows) or Terminal (Mac/Linux) inside the project folder:

```bash
git init
git checkout -b main
git add .
git commit -m "GPS Tools complete app"
git remote add origin https://github.com/YOUR_USERNAME/GPS-Tools.git
git push -u origin main
```
> Replace `YOUR_USERNAME` with your GitHub username.

### Step 3 — Download APK
1. Go to your repository on GitHub
2. Click **Actions** tab
3. Click the latest workflow run (green ✅ when done, ~5 minutes)
4. Scroll to **Artifacts** section
5. Download **GPS-Tools-Debug**
6. Install the APK on your Android phone

---

## 📱 First-Time Setup on Phone
1. Enable **"Install unknown apps"** for your file manager
2. Install the APK
3. Open GPS Tools
4. Grant **"All Files Access"** when prompted (Android 11+)
5. Set your source and destination folders
6. Tap **START PROCESS**

---

## ⚠️ Important Notes

| Topic | Detail |
|-------|--------|
| Video GPS writing | Works on Samsung and most Android 7.0+ devices; older phones may skip silently |
| PNG GPS | PNG doesn't natively support EXIF; GPS read/write may not work |
| 0,0 coordinates | Treated as invalid (no GPS) |
| Large folders | Tested design supports 25,000+ files |

---

## 📁 Output Structure
```
Source folder/          ← Files that originally had GPS (untouched)
Destination folder/     ← Files without GPS that couldn't be matched
Destination/AutoTagged/ ← Files that were successfully auto-tagged
/sdcard/GPS_Report_YYYYMMDD_HHmmss.csv ← Full report
```
