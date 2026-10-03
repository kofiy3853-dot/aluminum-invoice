package com.whoknowsventures.quotes

import android.Manifest
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageCapture.OutputFileOptions
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private val Ink = Color(0xFF172B36)
private val Teal = Color(0xFF087E80)
private val Pale = Color(0xFFF4F7F5)
private val Muted = Color(0xFF718087)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Teal, onPrimary = Color.White, background = Pale, surface = Color.White)) { App() } }
    }
}

private enum class Screen { HOME, SCAN, REVIEW, DOCUMENT }
private enum class DocType { QUOTE, INVOICE }

private data class QuoteLineItem(
    val description: String = "",
    val quantity: String = "1",
    val unitPrice: String = "",
    val needsReview: Boolean = false
) {
    val total: Double
        get() = (quantity.toDoubleOrNull() ?: 0.0) * (unitPrice.replace(",", "").toDoubleOrNull() ?: 0.0)
}

@Composable
private fun App() {
    var screen by remember { mutableStateOf(Screen.HOME) }
    var type by remember { mutableStateOf(DocType.QUOTE) }
    var customer by remember { mutableStateOf("") }
    var job by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(listOf(QuoteLineItem())) }
    var notice by remember { mutableStateOf("") }
    var isExtracting by remember { mutableStateOf(false) }
    var extractionMessage by remember { mutableStateOf("") }
    var pendingPdf by remember { mutableStateOf<ByteArray?>(null) }
    val context = LocalContext.current
    val extractionExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(extractionExecutor) {
        onDispose { extractionExecutor.shutdownNow() }
    }

    fun extractEstimate(uri: Uri) {
        if (isExtracting) return
        isExtracting = true
        extractionMessage = "Reading the photo…"
        extractionExecutor.execute {
            try {
                val extractedItems = requestEstimateExtraction(context, uri)
                mainHandler.post {
                    isExtracting = false
                    extractionMessage = ""
                    if (extractedItems.isEmpty()) {
                        notice = "I couldn't find priced items in that photo. Try a clearer photo or add the lines manually."
                    } else {
                        items = extractedItems
                        if (job.isBlank()) job = "Scanned estimate"
                        type = DocType.QUOTE
                        screen = Screen.REVIEW
                    }
                }
            } catch (error: Exception) {
                mainHandler.post {
                    isExtracting = false
                    extractionMessage = ""
                    notice = error.message ?: "Couldn't read that photo. Check the connection and try again."
                }
            }
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) screen = Screen.SCAN else notice = "Camera access is needed to scan an estimate. You can still choose a saved photo."
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) extractEstimate(uri)
    }
    val savePdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        val pdfBytes = pendingPdf
        pendingPdf = null
        if (uri != null && pdfBytes != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(pdfBytes) }
                    ?: throw IllegalStateException("Couldn't open the selected folder.")
                notice = "PDF saved. Open it from your selected folder to print or share."
            } catch (error: Exception) {
                notice = error.message ?: "Couldn't save the PDF. Please try again."
            }
        }
    }
    fun exportPdf() {
        try {
            pendingPdf = createEstimatePdf(type, customer, job, items, items.sumOf { it.total })
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            savePdfPicker.launch("WhoKnows-${type.name.lowercase(Locale.US)}-$date.pdf")
        } catch (error: Exception) {
            pendingPdf = null
            notice = error.message ?: "Couldn't create the PDF. Please try again."
        }
    }
    val total = items.sumOf { it.total }

    Scaffold(containerColor = Pale, bottomBar = {
        if (screen == Screen.HOME) NavigationBar(containerColor = Color.White) {
            NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Default.Description, null) }, label = { Text("Home") })
            NavigationBarItem(selected = false, onClick = { screen = Screen.DOCUMENT }, icon = { Icon(Icons.Default.History, null) }, label = { Text("Documents") })
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when (screen) {
                Screen.HOME -> Home(
                    onScan = { cameraPermission.launch(Manifest.permission.CAMERA) },
                    onPhoto = { picker.launch("image/*") },
                    onCreate = { type = it; customer = ""; job = ""; items = listOf(QuoteLineItem()); screen = Screen.REVIEW },
                    onRecent = { type = it; customer = "Jordan Lee"; job = "Home improvement"; items = listOf(QuoteLineItem("Materials and labor", unitPrice = "1250")); screen = Screen.DOCUMENT }
                )
                Screen.SCAN -> LiveScanner(
                    onBack = { screen = Screen.HOME },
                    onPhoto = ::extractEstimate,
                    isExtracting = isExtracting,
                    status = extractionMessage
                )
                Screen.REVIEW -> Review(
                    type = type, customer = customer, onCustomer = { customer = it }, job = job, onJob = { job = it },
                    items = items,
                    onItemChange = { index, value -> items = items.toMutableList().also { it[index] = value } },
                    onAddItem = { items = items + QuoteLineItem() },
                    onBack = { screen = Screen.HOME }, onType = { type = it }, onSave = { screen = Screen.DOCUMENT }
                )
                Screen.DOCUMENT -> Document(type, customer, job, items, total, onBack = { screen = Screen.HOME }, onConvert = { type = DocType.INVOICE; screen = Screen.DOCUMENT }, onExportPdf = ::exportPdf)
            }
        }
    }
    if (isExtracting && screen != Screen.SCAN) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Reading estimate items…")
                }
            }
        )
    }
    if (notice.isNotBlank()) AlertDialog(onDismissRequest = { notice = "" }, confirmButton = { TextButton(onClick = { notice = "" }) { Text("Done") } }, text = { Text(notice) })
}

private fun requestEstimateExtraction(context: android.content.Context, uri: Uri): List<QuoteLineItem> {
    val mimeType = context.contentResolver.getType(uri)?.substringBefore(';')?.lowercase() ?: "image/jpeg"
    if (mimeType !in setOf("image/jpeg", "image/png", "image/webp")) {
        throw IllegalArgumentException("Please choose a JPG, PNG, or WEBP photo.")
    }
    val imageBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalArgumentException("Couldn't open that photo. Please choose it again.")
    if (imageBytes.size > 10 * 1024 * 1024) throw IllegalArgumentException("Please choose a photo under 10 MB.")

    val payload = JSONObject()
        .put("mimeType", mimeType)
        .put("imageBase64", Base64.encodeToString(imageBytes, Base64.NO_WRAP))
        .toString()
    val baseUrl = BuildConfig.EXTRACTOR_BASE_URL.trimEnd('/')
    val connection = (URL("$baseUrl/extract").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15000
        readTimeout = 100000
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        BuildConfig.EXTRACTOR_ACCESS_TOKEN.takeIf { it.isNotBlank() }?.let {
            setRequestProperty("Authorization", "Bearer $it")
        }
    }
    try {
        connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        val response = runCatching { JSONObject(responseText) }.getOrNull()
            ?: throw IllegalStateException("The estimate reader returned an unreadable response.")
        if (status !in 200..299) {
            throw IllegalStateException(response.optString("error").ifBlank { "Couldn't read that photo. Check the connection and try again." })
        }
        val rows = response.optJSONArray("items") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val rawDescription = row.optString("description").trim()
            val description = if (rawDescription.equals("corner carte", ignoreCase = true)) "Corner carte" else rawDescription
            if (description.isBlank()) return@mapNotNull null
            val quantity = row.optString("quantity").trim()
            val unitPrice = row.optString("unitPrice").trim().replace(",", "")
            val trustedKnownLabel = description == "Corner carte" &&
                row.optDouble("confidence", 0.0) >= 0.85 && quantity.isNotBlank() && unitPrice.isNotBlank()
            QuoteLineItem(
                description = description,
                quantity = quantity,
                unitPrice = unitPrice,
                needsReview = row.optBoolean("needsReview", true) && !trustedKnownLabel
            )
        }
    } finally {
        connection.disconnect()
    }
}

@Composable
private fun Home(onScan: () -> Unit, onPhoto: () -> Unit, onCreate: (DocType) -> Unit, onRecent: (DocType) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("WHO KNOWS", color = Teal, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp); Text("Aluminum Ventures", color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Bold) }
                IconButton(onClick = {}) { Icon(Icons.Default.MoreHoriz, "More", tint = Ink) }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().background(Ink, RoundedCornerShape(24.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Paper estimate?", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Scan it, check the details, then make a quote or invoice.", color = Color(0xFFD6E2E2), fontSize = 14.sp, lineHeight = 20.sp)
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Teal), shape = RoundedCornerShape(15.dp)) {
                    Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(9.dp)); Text("Scan handwritten estimate", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(onClick = onPhoto, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                    Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Choose a photo")
                }
            }
        }
        item { Text("CREATE NEW", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionCard("New quote", "Price a job", Icons.Default.Description, Modifier.weight(1f)) { onCreate(DocType.QUOTE) }
            ActionCard("New invoice", "Request payment", Icons.Default.ReceiptLong, Modifier.weight(1f)) { onCreate(DocType.INVOICE) }
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Recent", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold); Text("See all", color = Teal, fontSize = 13.sp) } }
        item { RecentRow("Quote  #001", "Jordan Lee · Home improvement", "Draft", "$1,250.00", Color(0xFFEAF4F1)) { onRecent(DocType.QUOTE) } }
        item { RecentRow("Invoice  #002", "Taylor Morgan · Repair", "Unpaid", "$680.00", Color(0xFFFFF2E4)) { onRecent(DocType.INVOICE) } }
        item { Text("Sample documents shown for the prototype", color = Muted, fontSize = 11.sp) }
    }
}

@Composable
private fun LiveScanner(onBack: () -> Unit, onPhoto: (Uri) -> Unit, isExtracting: Boolean, status: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraProvider = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var cameraStatus by remember { mutableStateOf("Fit the whole estimate on the page and keep the phone steady.") }
    var takingPhoto by remember { mutableStateOf(false) }

    LaunchedEffect(cameraView, lifecycleOwner) {
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider.value = provider
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(cameraView.surfaceProvider) }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (_: Exception) {
                cameraStatus = "Camera couldn't start. Check camera permission and try again."
            }
        }, ContextCompat.getMainExecutor(context))
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraProvider.value?.unbindAll()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Scan estimate", onBack)
        Text("Fit the full handwritten estimate in the frame. The photo will be read into item, quantity, and price rows for you to check.", Modifier.padding(horizontal = 22.dp, vertical = 6.dp), color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        AndroidView(factory = { cameraView }, modifier = Modifier.weight(1f).fillMaxWidth().padding(18.dp).clip(RoundedCornerShape(24.dp)))
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (isExtracting) status else cameraStatus, color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            Button(
                onClick = {
                    val photoFile = File(context.cacheDir, "estimate-${System.currentTimeMillis()}.jpg")
                    val options = OutputFileOptions.Builder(photoFile).build()
                    takingPhoto = true
                    cameraStatus = "Taking photo…"
                    imageCapture.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            takingPhoto = false
                            onPhoto(Uri.fromFile(photoFile))
                        }

                        override fun onError(exception: ImageCaptureException) {
                            takingPhoto = false
                            cameraStatus = "Couldn't take the photo. Try again with more light."
                        }
                    })
                },
                enabled = !isExtracting && !takingPhoto,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(15.dp)
            ) {
                Text(if (isExtracting) "Reading photo…" else if (takingPhoto) "Taking photo…" else "Take photo and read items")
            }
        }
    }
}

@Composable
private fun ActionCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.background(Color.White, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(39.dp).background(Color(0xFFE8F4F1), CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Teal, modifier = Modifier.size(20.dp)) }
        Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp); Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun RecentRow(title: String, subtitle: String, status: String, price: String, tint: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(17.dp)).clickable(onClick = onClick).padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(tint, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Description, null, tint = Teal, modifier = Modifier.size(21.dp)) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text(subtitle, color = Muted, fontSize = 11.sp) }
        Column(horizontalAlignment = Alignment.End) { Text(price, color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp); Text(status, color = Muted, fontSize = 11.sp) }
    }
}

@Composable
private fun Review(
    type: DocType,
    customer: String,
    onCustomer: (String) -> Unit,
    job: String,
    onJob: (String) -> Unit,
    items: List<QuoteLineItem>,
    onItemChange: (Int, QuoteLineItem) -> Unit,
    onAddItem: () -> Unit,
    onBack: () -> Unit,
    onType: (DocType) -> Unit,
    onSave: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        TopBar(if (type == DocType.QUOTE) "Review quote" else "Review invoice", onBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Check the details", color = Muted, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(15.dp)).padding(4.dp)) {
                DocType.entries.forEach { d ->
                    val selected = type == d
                    Box(Modifier.weight(1f).background(if (selected) Teal else Color.Transparent, RoundedCornerShape(12.dp)).clickable { onType(d) }.padding(12.dp), contentAlignment = Alignment.Center) { Text(if (d == DocType.QUOTE) "Quote" else "Invoice", color = if (selected) Color.White else Ink, fontWeight = FontWeight.SemiBold) }
                }
            }
            Field("Customer name", customer, onCustomer, "e.g. Jordan Lee")
            Field("Job description", job, onJob, "Describe the work")
            Text("LINE ITEMS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            items.forEachIndexed { index, line ->
                Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (line.needsReview) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Check this row; a field may be unclear.", color = Color(0xFF9A5600), fontSize = 12.sp)
                            TextButton(onClick = { onItemChange(index, line.copy(needsReview = false)) }) { Text("Checked") }
                        }
                    }
                    Field("Item or service", line.description, { onItemChange(index, line.copy(description = it)) }, "Description")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field("Qty", line.quantity, { onItemChange(index, line.copy(quantity = it)) }, "1", Modifier.weight(1f))
                        Field("Unit price ($)", line.unitPrice, { onItemChange(index, line.copy(unitPrice = it)) }, "0.00", Modifier.weight(1.6f))
                    }
                    Text("Line total  $${"%.2f".format(line.total)}", Modifier.align(Alignment.End), color = Muted, fontSize = 12.sp)
                }
            }
            TextButton(onClick = onAddItem) { Icon(Icons.Default.Add, null, tint = Teal); Spacer(Modifier.width(6.dp)); Text("Add another item", color = Teal, fontWeight = FontWeight.SemiBold) }
            Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("Total", color = Ink, fontWeight = FontWeight.Bold); Text("$${"%.2f".format(items.sumOf { it.total })}", color = Ink, fontWeight = FontWeight.Bold) }
            Text("Check the scanned quantities and prices before sharing.", color = Muted, fontSize = 12.sp)
        }
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth().padding(22.dp).height(54.dp), shape = RoundedCornerShape(15.dp)) { Text("Preview ${type.name.lowercase()}") }
    }
}

@Composable
private fun Field(label: String, value: String, onValue: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(label, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); OutlinedTextField(value, onValue, modifier = Modifier.fillMaxWidth(), placeholder = { Text(hint, color = Muted) }, shape = RoundedCornerShape(14.dp), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Teal, unfocusedBorderColor = Color(0xFFDCE4E1))) }
}

@Composable
private fun Document(type: DocType, customer: String, job: String, items: List<QuoteLineItem>, total: Double, onBack: () -> Unit, onConvert: () -> Unit, onExportPdf: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Preview ${type.name.lowercase()}", onBack)
        Column(Modifier.weight(1f).fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
            Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(22.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("WHO KNOWS ALUMINUM VENTURES", color = Teal, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp)
                Text(type.name, color = Ink, fontWeight = FontWeight.Bold, fontSize = 28.sp)
                HorizontalDivider(color = Color(0xFFE5EBE8))
                Text("BILL TO", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text(customer.ifBlank { "Customer name" }, color = Ink, fontWeight = FontWeight.SemiBold)
                Text(job.ifBlank { "Job description" }, color = Muted, fontSize = 13.sp)
                HorizontalDivider(color = Color(0xFFE5EBE8))
                items.forEach { line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(line.description.ifBlank { "Work and materials" }, color = Ink)
                            Text("${line.quantity} × $${line.unitPrice.ifBlank { "0.00" }}", color = Muted, fontSize = 12.sp)
                        }
                        Text("$${"%.2f".format(line.total)}", color = Ink)
                    }
                }
                HorizontalDivider(color = Color(0xFFE5EBE8))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("TOTAL", color = Ink, fontWeight = FontWeight.Bold); Text("$${"%.2f".format(total)}", color = Teal, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
                Text(if (type == DocType.QUOTE) "Valid for 30 days" else "Payment due upon receipt", color = Muted, fontSize = 12.sp)
            }
            Text("Company contact details can be added in settings.", color = Muted, fontSize = 12.sp)
        }
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onExportPdf, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp)) { Text("Save as PDF") }
            if (type == DocType.QUOTE) OutlinedButton(onClick = onConvert, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp)) { Text("Convert to invoice") }
        }
    }
}

private fun createEstimatePdf(type: DocType, customer: String, job: String, items: List<QuoteLineItem>, total: Double): ByteArray {
    val document = PdfDocument()
    val pageWidth = 595
    val pageHeight = 842
    val left = 42f
    val right = 553f
    val ink = android.graphics.Color.rgb(23, 43, 54)
    val teal = android.graphics.Color.rgb(8, 126, 128)
    val muted = android.graphics.Color.rgb(105, 120, 127)
    val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 10f }
    val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 9f }
    val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 10f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = teal; textSize = 11f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 25f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(220, 228, 225); strokeWidth = 1f }
    var pageNumber = 0
    var page: PdfDocument.Page? = null
    var canvas: Canvas? = null
    var y = 0f

    fun drawTableHeader() {
        val c = canvas ?: return
        val header = Paint(bold).apply { color = muted; textSize = 9f }
        c.drawText("ITEM OR SERVICE", left, y, header)
        c.drawText("QTY", 359f, y, header)
        c.drawText("UNIT PRICE", 402f, y, header)
        c.drawText("AMOUNT", 490f, y, header)
        y += 10f
        c.drawLine(left, y, right, y, rule)
        y += 17f
    }

    fun startPage(continued: Boolean) {
        pageNumber += 1
        page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        canvas = page!!.canvas
        val c = canvas!!
        y = 48f
        c.drawText("WHO KNOWS ALUMINUM VENTURES", left, y, brand)
        val dateText = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date())
        val datePaint = Paint(small).apply { textAlign = Paint.Align.RIGHT }
        c.drawText(dateText, right, y, datePaint)
        y += 37f
        c.drawText(if (continued) "${type.name} (continued)" else type.name, left, y, title)
        y += 22f
        if (!continued) {
            c.drawText("CUSTOMER", left, y, small)
            y += 16f
            c.drawText(customer.ifBlank { "Customer name" }, left, y, bold)
            y += 19f
            c.drawText("JOB", left, y, small)
            y += 16f
            wrapPdfText(job.ifBlank { "Job description" }, regular, right - left).forEach { line ->
                c.drawText(line, left, y, regular)
                y += 14f
            }
            y += 8f
        }
        drawTableHeader()
    }

    fun finishPage() {
        page?.let { document.finishPage(it) }
        page = null
        canvas = null
    }

    try {
        startPage(continued = false)
        items.forEach { item ->
            val descriptionLines = wrapPdfText(item.description.ifBlank { "Work and materials" }, regular, 300f)
            val rowHeight = maxOf(29f, descriptionLines.size * 13f + 12f)
            if (y + rowHeight > 770f) {
                finishPage()
                startPage(continued = true)
            }
            val c = canvas!!
            descriptionLines.forEachIndexed { index, line -> c.drawText(line, left, y + index * 13f, regular) }
            c.drawText(item.quantity.ifBlank { "0" }, 359f, y, regular)
            c.drawText("$${item.unitPrice.ifBlank { "0.00" }}", 402f, y, regular)
            val amountPaint = Paint(regular).apply { textAlign = Paint.Align.RIGHT }
            c.drawText("$${"%.2f".format(Locale.US, item.total)}", right, y, amountPaint)
            y += rowHeight - 7f
            c.drawLine(left, y, right, y, rule)
            y += 13f
        }
        if (y + 62f > 780f) {
            finishPage()
            startPage(continued = true)
        }
        val c = canvas!!
        y += 8f
        c.drawLine(365f, y, right, y, rule)
        y += 25f
        c.drawText("TOTAL", 365f, y, bold)
        val totalPaint = Paint(brand).apply { textSize = 16f; textAlign = Paint.Align.RIGHT }
        c.drawText("$${"%.2f".format(Locale.US, total)}", right, y + 2f, totalPaint)
        y += 28f
        c.drawText(if (type == DocType.QUOTE) "Quote valid for 30 days" else "Payment due upon receipt", left, y, small)
        val footerPaint = Paint(small).apply { textAlign = Paint.Align.RIGHT }
        c.drawText("Page $pageNumber", right, 815f, footerPaint)
        finishPage()
        val output = java.io.ByteArrayOutputStream()
        document.writeTo(output)
        return output.toByteArray()
    } finally {
        document.close()
    }
}

private fun wrapPdfText(text: String, paint: Paint, maxWidth: Float): List<String> {
    val result = mutableListOf<String>()
    var line = ""
    text.trim().split(Regex("\\s+")).forEach { word ->
        val candidate = if (line.isEmpty()) word else "$line $word"
        if (line.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
            result += line
            line = word
        } else {
            line = candidate
        }
    }
    if (line.isNotEmpty()) result += line
    return result.ifEmpty { listOf("") }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Ink) }
        Text(title, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
