# Who Knows Aluminum Ventures (Android)

Android estimate-to-quote prototype. Scanning now takes one photo and sends the full page to the estimate reader. The reader returns editable item/service, quantity, unit-price, and confidence fields. The Android app calculates line totals and the quote total locally; it does not use the handwritten amount column for arithmetic. Rows the reader is unsure about are marked for review.

Estimate photos are sent to the configured AI service. Do not use the scan feature for customer information unless the business is comfortable with this processing.

## Run the estimate reader locally

The OpenRouter API key belongs on the server, never in the Android app or source control.

1. Install Node.js 20 or newer.
2. Obtain an OpenRouter API key from [OpenRouter Keys](https://openrouter.ai/keys) (or a Gemini API key if configured).
3. In PowerShell, from this `server` folder, set the key for this terminal and start the server:

   ```powershell
   $env:OPENROUTER_API_KEY = "sk-or-v1-your-key-here"
   npm start
   ```

   Keep that terminal running. The server listens on port 8081 and uses the vision model configured via `OPENROUTER_MODEL` (defaults to `stealth/space-bunny-alpha`).

4. Start the Android emulator. Its debug build connects to the computer at `http://10.0.2.2:8081` by default.
5. Open the Android project in Android Studio and run the `app` configuration. Choose **Scan handwritten estimate** to take a photo, or **Choose a photo** to use a saved estimate.

For a physical Android phone, set `extractorBaseUrl` to the computer's HTTPS URL, or for local development to its Wi-Fi IP, when building the app. A phone cannot use the emulator-only `10.0.2.2` address. The debug app allows cleartext HTTP for local testing; production must use HTTPS.

## Build a debug APK

From the project folder:

```powershell
.\gradlew.bat assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Host the reader on Render

The repository includes `render.yaml` for a Render web service. Push this project to a **private GitHub repository** (do not include `server/.env`), then in Render choose **New > Blueprint** and connect that repository. Render will ask for two private environment variables:

- `OPENROUTER_API_KEY`: the key from OpenRouter.
- `APP_ACCESS_TOKEN`: a long random token used by this app to access the reader.

The server keeps the provider key off the phone, checks the app token, limits extraction traffic, and does not log extracted estimate contents. The token is included in the APK, so treat the APK as private and rotate the token if it is shared outside the business.

Render's free service sleeps after 15 minutes without traffic and can take about a minute to wake for the next scan. Render describes free services as suitable for exploration and previews, not production. Its always-on Starter web service is currently listed at $7/month. Check Render's current [free-service limits](https://render.com/docs/free) and [pricing](https://render.com/pricing) before choosing a plan.

After Render deploys, copy the service's HTTPS URL. Build the phone APK with that URL and the same app token you entered in Render:

```powershell
$env:EXTRACTOR_ACCESS_TOKEN = "the-same-token-entered-in-Render"
.\gradlew.bat assembleDebug -PextractorBaseUrl=https://your-service.onrender.com
```

Install `app/build/outputs/apk/debug/app-debug.apk` on the phone. Keep the app token private; it is a basic shared app credential, not individual user sign-in.

## Current prototype limits

- The extraction service returns rows for review; handwritten recognition can still be uncertain. Check marked rows and verify every price before creating a customer quote.
- Quote/invoice entry, preview, and PDF export are present. Document history, customer records, tax settings, company contacts, and in-app sharing are not implemented yet.
- Sample recent documents are demonstration content.
