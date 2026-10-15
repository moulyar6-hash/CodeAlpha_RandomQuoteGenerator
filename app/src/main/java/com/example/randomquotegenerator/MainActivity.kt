
package com.example.randomquotegenerator

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Color as AndroidColor
import android.app.WallpaperManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class Quote(
    val text: String,
    val author: String,
    val category: String = "Custom",
    val custom: Boolean = false
)

private val starterQuotes = listOf(
    Quote("Believe you can and you're halfway there.", "Theodore Roosevelt", "Motivation"),
    Quote("It always seems impossible until it's done.", "Nelson Mandela", "Motivation"),
    Quote("The secret of getting ahead is getting started.", "Mark Twain", "Motivation"),
    Quote("Act as if what you do makes a difference. It does.", "William James", "Motivation"),
    Quote("Success is the sum of small efforts, repeated day in and day out.", "Robert Collier", "Success"),
    Quote("The best way to predict the future is to create it.", "Peter Drucker", "Success"),
    Quote("Success is not final, failure is not fatal.", "Winston Churchill", "Success"),
    Quote("Happiness depends upon ourselves.", "Aristotle", "Happiness"),
    Quote("The purpose of our lives is to be happy.", "Dalai Lama", "Happiness"),
    Quote("Keep your face always toward the sunshine.", "Walt Whitman", "Happiness"),
    Quote("Knowing yourself is the beginning of all wisdom.", "Aristotle", "Wisdom"),
    Quote("A journey of a thousand miles begins with a single step.", "Lao Tzu", "Wisdom"),
    Quote("Life is really simple, but we insist on making it complicated.", "Confucius", "Life"),
    Quote("In the middle of difficulty lies opportunity.", "Albert Einstein", "Life"),
    Quote("Every moment is a fresh beginning.", "T. S. Eliot", "Life")
)

private fun dateKey(date: Date = Date()): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

private fun quoteToJson(q: Quote) = JSONObject().apply {
    put("text", q.text)
    put("author", q.author)
    put("category", q.category)
    put("custom", q.custom)
}

private fun jsonToQuote(o: JSONObject) = Quote(
    o.optString("text"),
    o.optString("author"),
    o.optString("category", "Custom"),
    o.optBoolean("custom", true)
)

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                scheduleReminder(this)
            } else {
                Toast.makeText(this, "Notification permission not granted", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel(this)

        setContent {
            QuoteApp(
                enableReminder = {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        scheduleReminder(this)
                    }
                },
                disableReminder = { cancelReminder(this) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuoteApp(
    enableReminder: () -> Unit,
    disableReminder: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("quote_preferences", Context.MODE_PRIVATE)
    }
    val clipboard = LocalClipboardManager.current

    var darkMode by remember { mutableStateOf(prefs.getBoolean("dark_mode", false)) }
    var largeText by remember { mutableStateOf(prefs.getBoolean("large_text", false)) }
    var theme by remember { mutableStateOf(prefs.getString("theme", "Ocean") ?: "Ocean") }
    var dailyGoal by remember { mutableStateOf(prefs.getInt("daily_goal", 5)) }
    var reminderEnabled by remember { mutableStateOf(prefs.getBoolean("reminder", false)) }
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    var tab by remember { mutableStateOf("Explore") }

    var editorOpen by remember { mutableStateOf(false) }
    var editingQuote by remember { mutableStateOf<Quote?>(null) }
    var editorText by remember { mutableStateOf("") }
    var editorAuthor by remember { mutableStateOf("") }
    var editorCategory by remember { mutableStateOf("Custom") }
    var deleteTarget by remember { mutableStateOf<Quote?>(null) }

    fun readCustomQuotes(): List<Quote> {
        return try {
            val array = JSONArray(prefs.getString("custom_quotes", "[]"))
            (0 until array.length()).map { jsonToQuote(array.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    val customQuotes = remember {
        mutableStateListOf<Quote>().apply { addAll(readCustomQuotes()) }
    }
    val allQuotes = starterQuotes + customQuotes

    var currentQuote by remember { mutableStateOf(starterQuotes.first()) }

    var favorites by remember {
        mutableStateOf(
            prefs.getStringSet("favorites", emptySet<String>())?.toSet() ?: emptySet()
        )
    }

    val history = remember {
        mutableStateListOf<Quote>().apply {
            prefs.getString("history", "").orEmpty()
                .split("\n")
                .filter { it.isNotBlank() }
                .forEach { text ->
                    (starterQuotes + readCustomQuotes()).find { it.text == text }?.let { quote ->
                        if (none { it.text == quote.text }) add(quote)
                    }
                }
        }
    }

    fun saveCustomQuotes() {
        val array = JSONArray()
        customQuotes.forEach { array.put(quoteToJson(it)) }
        prefs.edit().putString("custom_quotes", array.toString()).apply()
    }

    fun saveHistory() {
        prefs.edit().putString(
            "history",
            history.take(30).joinToString("\n") { it.text }
        ).apply()
    }

    fun recordQuote(q: Quote) {
        currentQuote = q
        history.removeAll { it.text == q.text }
        history.add(0, q)
        while (history.size > 30) history.removeAt(history.lastIndex)
        saveHistory()

        val today = dateKey()
        val previousDay = prefs.getString("last_read_day", "")
        if (previousDay != today) {
            val yesterday = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -1)
            }.time.let { dateKey(it) }

            val previousStreak = prefs.getInt("reading_streak", 0)
            prefs.edit()
                .putInt(
                    "reading_streak",
                    if (previousDay == yesterday) previousStreak + 1 else 1
                )
                .putString("last_read_day", today)
                .putInt("quotes_viewed", prefs.getInt("quotes_viewed", 0) + 1)
                .putInt("today_count", 0)
                .apply()
        }

        val count = prefs.getInt("today_count", 0)
        if (count < dailyGoal) {
            prefs.edit().putInt("today_count", count + 1).apply()
        }
    }

    fun toggleFavorite(q: Quote) {
        favorites = if (q.text in favorites) favorites - q.text else favorites + q.text
        prefs.edit().putStringSet("favorites", favorites).apply()
    }

    fun shareQuote(q: Quote) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "“${q.text}”\n— ${q.author}")
        }
        context.startActivity(Intent.createChooser(intent, "Share quote"))
    }

    fun createQuoteBitmap(q: Quote): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val backgroundColor = when (theme) {
            "Sunset" -> AndroidColor.rgb(255, 220, 195)
            "Forest" -> AndroidColor.rgb(215, 235, 210)
            "Lavender" -> AndroidColor.rgb(232, 220, 250)
            "Classic" -> AndroidColor.WHITE
            else -> AndroidColor.rgb(215, 238, 248)
        }
        canvas.drawColor(backgroundColor)

        val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(35, 90, 115)
            textSize = 42f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val quotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(35, 45, 60)
            textSize = if (largeText) 54f else 48f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val authorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(35, 90, 115)
            textSize = 36f
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText("DAILY INSPIRATION", 540f, 240f, headingPaint)

        val lines = mutableListOf<String>()
        var line = ""
        q.text.split(" ").forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (quotePaint.measureText(candidate) > 850f && line.isNotEmpty()) {
                lines.add(line)
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines.add(line)

        var y = 470f - lines.size * 28f
        lines.forEach {
            canvas.drawText(it, 540f, y, quotePaint)
            y += 65f
        }
        canvas.drawText("— ${q.author}", 540f, y + 80f, authorPaint)
        return bitmap
    }

    fun saveQuoteImage(q: Quote) {
        try {
            val bitmap = createQuoteBitmap(q)
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Quote_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/QuoteGenerator")
                }
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("Could not create image")

            context.contentResolver.openOutputStream(uri).use { output ->
                checkNotNull(output)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            bitmap.recycle()
            Toast.makeText(context, "Quote image saved", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Image save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun setQuoteWallpaper(q: Quote) {
        try {
            val bitmap = createQuoteBitmap(q)
            WallpaperManager.getInstance(context).setBitmap(bitmap)
            bitmap.recycle()
            Toast.makeText(context, "Wallpaper updated", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Wallpaper failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val root = JSONObject()
                root.put("customQuotes", JSONArray().apply {
                    customQuotes.forEach { put(quoteToJson(it)) }
                })
                root.put("favorites", JSONArray().apply {
                    favorites.forEach { put(it) }
                })
                root.put("history", JSONArray().apply {
                    history.forEach { put(quoteToJson(it)) }
                })
                context.contentResolver.openOutputStream(uri).use { output ->
                    checkNotNull(output)
                    output.write(root.toString(2).toByteArray())
                }
                Toast.makeText(context, "Backup exported", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Cannot open file")
                val root = input.bufferedReader().use { JSONObject(it.readText()) }

                customQuotes.clear()
                val customArray = root.optJSONArray("customQuotes") ?: JSONArray()
                for (i in 0 until customArray.length()) {
                    customQuotes.add(jsonToQuote(customArray.getJSONObject(i)))
                }
                saveCustomQuotes()

                val favArray = root.optJSONArray("favorites") ?: JSONArray()
                favorites = (0 until favArray.length()).map {
                    favArray.getString(it)
                }.toSet()
                prefs.edit().putStringSet("favorites", favorites).apply()

                val historyArray = root.optJSONArray("history") ?: JSONArray()
                history.clear()
                for (i in 0 until historyArray.length()) {
                    history.add(jsonToQuote(historyArray.getJSONObject(i)))
                }
                saveHistory()

                Toast.makeText(context, "Backup imported", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val textToSpeech = remember {
        TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // The built-in speech engine is ready.
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
    }

    val categories = listOf(
        "All", "Motivation", "Success", "Happiness", "Wisdom", "Life", "Custom"
    )

    val displayedQuotes = when (tab) {
        "Favorites" -> allQuotes.filter {
            it.text in favorites &&
                    (it.text.contains(search, true) || it.author.contains(search, true))
        }
        "History" -> history.filter {
            it.text.contains(search, true) || it.author.contains(search, true)
        }
        else -> allQuotes.filter {
            (category == "All" || it.category == category) &&
                    (it.text.contains(search, true) || it.author.contains(search, true))
        }
    }

    val background = when (theme) {
        "Sunset" -> Color(0xFFFFE0CC)
        "Forest" -> Color(0xFFD9EAD3)
        "Lavender" -> Color(0xFFE9DDF7)
        "Classic" -> MaterialTheme.colorScheme.surface
        else -> Color(0xFFDCEFF8)
    }
    val cardBackground = when (theme) {
        "Sunset" -> Color(0xFFFFC49B)
        "Forest" -> Color(0xFFB7D7AD)
        "Lavender" -> Color(0xFFD7C1F0)
        "Classic" -> MaterialTheme.colorScheme.secondaryContainer
        else -> Color(0xFFB9DDEA)
    }

    MaterialTheme(
        colorScheme = if (darkMode) darkColorScheme() else lightColorScheme()
    ) {
        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Column {
                        Text("Daily Inspiration", fontWeight = FontWeight.Bold)
                        Text(
                            "Quotes for a better day",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                })
            },
            bottomBar = {
                NavigationBar {
                    listOf("Explore", "Favorites", "History", "Settings").forEach { page ->
                        NavigationBarItem(
                            selected = tab == page,
                            onClick = { tab = page },
                            icon = {
                                Text(
                                    when (page) {
                                        "Explore" -> "✦"
                                        "Favorites" -> "♥"
                                        "History" -> "◷"
                                        else -> "⚙"
                                    },
                                    fontSize = 20.sp
                                )
                            },
                            label = { Text(page) }
                        )
                    }
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background)
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (tab == "Settings") {
                    item {
                        Text("Appearance", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Dark mode", modifier = Modifier.weight(1f))
                            Switch(checked = darkMode, onCheckedChange = {
                                darkMode = it
                                prefs.edit().putBoolean("dark_mode", it).apply()
                            })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Larger text", modifier = Modifier.weight(1f))
                            Switch(checked = largeText, onCheckedChange = {
                                largeText = it
                                prefs.edit().putBoolean("large_text", it).apply()
                            })
                        }
                    }
                    item { Text("Quote card theme") }
                    item {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("Ocean", "Sunset", "Forest", "Lavender", "Classic").forEach {
                                FilterChip(selected = theme == it, onClick = {
                                    theme = it
                                    prefs.edit().putString("theme", it).apply()
                                }, label = { Text(it) })
                            }
                        }
                    }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = cardBackground)) {
                            Text(
                                "“${currentQuote.text}”\n\n— ${currentQuote.author}",
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                textAlign = TextAlign.Center,
                                fontSize = if (largeText) 25.sp else 20.sp
                            )
                        }
                    }
                    item {
                        Text("Daily reading goal: $dailyGoal quotes")
                        Slider(
                            value = dailyGoal.toFloat(),
                            onValueChange = { dailyGoal = it.toInt().coerceIn(1, 20) },
                            valueRange = 1f..20f,
                            steps = 18
                        )
                        TextButton(onClick = {
                            prefs.edit().putInt("daily_goal", dailyGoal).apply()
                            Toast.makeText(context, "Goal saved", Toast.LENGTH_SHORT).show()
                        }) { Text("Save reading goal") }
                        Text("Today's progress: ${prefs.getInt("today_count", 0)} / $dailyGoal")
                    }
                    item {
                        Text("Daily reminder")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (reminderEnabled) "Enabled (around 9 AM)" else "Disabled",
                                modifier = Modifier.weight(1f)
                            )
                            Switch(checked = reminderEnabled, onCheckedChange = {
                                reminderEnabled = it
                                prefs.edit().putBoolean("reminder", it).apply()
                                if (it) enableReminder() else disableReminder()
                            })
                        }
                    }
                    item {
                        Text("Reading statistics", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Quotes viewed: ${prefs.getInt("quotes_viewed", 0)}")
                        Text("Favorites: ${favorites.size}")
                        Text("Custom quotes: ${customQuotes.size}")
                        Text("Reading streak: ${prefs.getInt("reading_streak", 0)} day(s)")
                        Text("History entries: ${history.size}")
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                exportLauncher.launch("quote_backup.json")
                            }) { Text("Export backup") }
                            Button(onClick = {
                                importLauncher.launch(arrayOf("application/json", "text/*"))
                            }) { Text("Import backup") }
                        }
                    }
                } else {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = cardBackground)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text("✦ DAILY INSPIRATION ✦", fontWeight = FontWeight.Bold)
                                Text(
                                    "“${currentQuote.text}”",
                                    fontSize = if (largeText) 28.sp else 23.sp,
                                    lineHeight = if (largeText) 37.sp else 32.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center
                                )
                                Text("— ${currentQuote.author}")
                                Text("Category: ${currentQuote.category}")

                                Button(onClick = {
                                    val options = allQuotes.filter { it.text != currentQuote.text }
                                    recordQuote(options.randomOrNull() ?: allQuotes.first())
                                }) { Text("↻ New Quote") }

                                Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                                    TextButton(onClick = { toggleFavorite(currentQuote) }) {
                                        Text(if (currentQuote.text in favorites) "♥ Saved" else "♡ Favorite")
                                    }
                                    TextButton(onClick = {
                                        clipboard.setText(
                                            AnnotatedString("“${currentQuote.text}”\n— ${currentQuote.author}")
                                        )
                                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                    }) { Text("Copy") }
                                    TextButton(onClick = { shareQuote(currentQuote) }) { Text("Share") }
                                }

                                Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                                    TextButton(onClick = {
                                        textToSpeech.speak(
                                            "${currentQuote.text}. By ${currentQuote.author}",
                                            TextToSpeech.QUEUE_FLUSH,
                                            null,
                                            "quote"
                                        )
                                    }) { Text("Listen") }
                                    TextButton(onClick = { saveQuoteImage(currentQuote) }) {
                                        Text("Save image")
                                    }
                                    TextButton(onClick = { setQuoteWallpaper(currentQuote) }) {
                                        Text("Set wallpaper")
                                    }
                                }

                                TextButton(onClick = {
                                    val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
                                    recordQuote(allQuotes[day % allQuotes.size])
                                }) { Text("Quote of the Day") }
                            }
                        }
                    }

                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (tab) {
                                    "Favorites" -> "Your favorites"
                                    "History" -> "Recently viewed"
                                    else -> "Explore quotes"
                                },
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            if (tab == "Explore") {
                                TextButton(onClick = {
                                    editingQuote = null
                                    editorText = ""
                                    editorAuthor = ""
                                    editorCategory = "Custom"
                                    editorOpen = true
                                }) { Text("+ Add quote") }
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Search quotes or authors") },
                            singleLine = true
                        )
                    }
                    if (tab == "Explore") {
                        item {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                categories.forEach {
                                    FilterChip(selected = category == it, onClick = {
                                        category = it
                                    }, label = { Text(it) })
                                }
                            }
                        }
                    }
                    if (displayedQuotes.isEmpty()) {
                        item {
                            Text(
                                "No quotes found.",
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    items(displayedQuotes, key = { it.text }) { quote ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                recordQuote(quote)
                            }
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "“${quote.text}”",
                                    fontSize = if (largeText) 21.sp else 17.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text("— ${quote.author}")
                                Text("Category: ${quote.category}")
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { toggleFavorite(quote) }) {
                                        Text(if (quote.text in favorites) "♥ Saved" else "♡ Save")
                                    }
                                    if (quote.custom) {
                                        TextButton(onClick = {
                                            editingQuote = quote
                                            editorText = quote.text
                                            editorAuthor = quote.author
                                            editorCategory = quote.category
                                            editorOpen = true
                                        }) { Text("Edit") }
                                        TextButton(onClick = { deleteTarget = quote }) {
                                            Text("Delete")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (editorOpen) {
        AlertDialog(
            onDismissRequest = { editorOpen = false },
            title = { Text(if (editingQuote == null) "Add custom quote" else "Edit custom quote") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editorText,
                        onValueChange = { editorText = it },
                        label = { Text("Quote") }
                    )
                    OutlinedTextField(
                        value = editorAuthor,
                        onValueChange = { editorAuthor = it },
                        label = { Text("Author") }
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("Custom", "Motivation", "Success", "Happiness", "Wisdom", "Life")
                            .forEach {
                                FilterChip(
                                    selected = editorCategory == it,
                                    onClick = { editorCategory = it },
                                    label = { Text(it) }
                                )
                            }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (editorText.isBlank() || editorAuthor.isBlank()) {
                        Toast.makeText(context, "Enter quote and author", Toast.LENGTH_SHORT).show()
                    } else {
                        val updated = Quote(
                            editorText.trim(),
                            editorAuthor.trim(),
                            editorCategory,
                            true
                        )
                        if (editingQuote != null) {
                            val index = customQuotes.indexOfFirst {
                                it.text == editingQuote!!.text
                            }
                            if (index >= 0) customQuotes[index] = updated
                        } else {
                            customQuotes.add(updated)
                        }
                        saveCustomQuotes()
                        editorOpen = false
                        category = "Custom"
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editorOpen = false }) { Text("Cancel") }
            }
        )
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete quote?") },
            text = { Text("This custom quote will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    val target = deleteTarget!!
                    customQuotes.removeAll { it.text == target.text }
                    favorites = favorites - target.text
                    history.removeAll { it.text == target.text }
                    prefs.edit().putStringSet("favorites", favorites).apply()
                    saveCustomQuotes()
                    saveHistory()
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

private const val QUOTE_CHANNEL = "daily_quote_channel"
private const val QUOTE_ALARM_ID = 4103

private fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            QUOTE_CHANNEL,
            "Daily Quote Reminder",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}

private fun scheduleReminder(context: Context) {
    val intent = Intent(context, DailyQuoteReceiver::class.java)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    val pending = PendingIntent.getBroadcast(context, QUOTE_ALARM_ID, intent, flags)

    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 9)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
    }

    val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarm.setInexactRepeating(
        AlarmManager.RTC_WAKEUP,
        calendar.timeInMillis,
        AlarmManager.INTERVAL_DAY,
        pending
    )
    Toast.makeText(context, "Daily reminder scheduled for around 9 AM", Toast.LENGTH_SHORT).show()
}

private fun cancelReminder(context: Context) {
    val intent = Intent(context, DailyQuoteReceiver::class.java)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    val pending = PendingIntent.getBroadcast(context, QUOTE_ALARM_ID, intent, flags)
    val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarm.cancel(pending)
    Toast.makeText(context, "Reminder disabled", Toast.LENGTH_SHORT).show()
}

class DailyQuoteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        createNotificationChannel(context)

        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val prefs = context.getSharedPreferences("quote_preferences", Context.MODE_PRIVATE)
        val quotes = starterQuotes.toMutableList()

        try {
            val array = JSONArray(prefs.getString("custom_quotes", "[]"))
            for (i in 0 until array.length()) {
                quotes.add(jsonToQuote(array.getJSONObject(i)))
            }
        } catch (_: Exception) {
        }

        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val quote = quotes[day % quotes.size]
        val openIntent = Intent(context, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val contentIntent = PendingIntent.getActivity(context, 4104, openIntent, flags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, QUOTE_CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context).setPriority(Notification.PRIORITY_DEFAULT)
        }

        val notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Your Daily Inspiration")
            .setContentText("“${quote.text}” — ${quote.author}")
            .setStyle(
                Notification.BigTextStyle().bigText("“${quote.text}”\n— ${quote.author}")
            )
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(4105, notification)
    }
}
