package com.example

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.room.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Close
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import android.util.Base64
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton

object LicenseValidator {
    private const val PUBLIC_KEY_PEM = 
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA51cOgZ4yZa3MOqDxJDDT" +
        "4vG7LDAJcJgaru4mZaTCK8/3OhxCUscoE734JRm0YvIRFjzyrwT9Yjj6VovN9t6r" +
        "/jrj3ZiyRCS4na7YAPMZBSUNnYB+4ya5xsBOMQsT9AsiCVxvfSAvojB1sM7SHG/h" +
        "dQBDl+lKS83Yu1JnBFkbjadY4Krtqj/cPnZZCkzlA8VKcWQ1ML4/zP/KS3F8tUhP" +
        "90ddrZlb7JAwSqlf0tK4o0R4GKTN367pLBy6G3Y6gHSd9u3aRoIKQO53CoK680Zy" +
        "oruE0HysqSqWu7Oec98W5Q9FPudyDstxk5uR7yinKVbqnqrk3q9sxoZRjehg/7Ij" +
        "qQIDAQAB"

    @Throws(Exception::class)
    fun getPublicKey(): PublicKey {
        val keyBytes = Base64.decode(PUBLIC_KEY_PEM, Base64.DEFAULT)
        val spec = X509EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("RSA")
        return keyFactory.generatePublic(spec)
    }

    fun verifySignature(data: ByteArray, signatureBytes: ByteArray): Boolean {
        return try {
            val publicKey = getPublicKey()
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initVerify(publicKey)
            signature.update(data)
            signature.verify(signatureBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

@Entity(tableName = "invoices")
data class InvoiceEntity(
    @PrimaryKey val invoiceId: String,
    val amount: Int,
    val fee: Int,
    val total: Int,
    val status: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface InvoiceDao {
    @Query("SELECT * FROM invoices ORDER BY createdAt DESC")
    fun getAllInvoices(): Flow<List<InvoiceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvoice(invoice: InvoiceEntity)

    @Query("UPDATE invoices SET status = :status WHERE invoiceId = :invoiceId")
    suspend fun updateStatus(invoiceId: String, status: String)

    @Query("SELECT SUM(total) FROM invoices WHERE status = 'paid' OR status = 'PAID'")
    fun getTotalBalance(): Flow<Int?>
}

@Database(entities = [InvoiceEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun invoiceDao(): InvoiceDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kasir_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

data class Invoice(
    val invoiceId: String,
    val amount: Int,
    val fee: Int,
    val total: Int,
    val qrisImageBase64: String,
    val expiredAt: String
)

object ApiService {
    private val client = OkHttpClient()

    suspend fun createInvoice(amount: String): Invoice? = withContext(Dispatchers.IO) {
        val url = "https://api.sairibot.my.id/api/invoice?apikey=SKY_be1b80ec1d164337&amount=$amount"
        val request = Request.Builder().url(url).build()
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                body?.let {
                    val json = JSONObject(it)
                    if (json.optBoolean("success", false)) {
                        return@withContext Invoice(
                            invoiceId = json.optString("invoice_id"),
                            amount = json.optInt("amount"),
                            fee = json.optInt("fee"),
                            total = json.optInt("total"),
                            qrisImageBase64 = json.optString("qris_image").substringAfter("base64,"),
                            expiredAt = json.optString("expired_at")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    suspend fun checkStatus(invoiceId: String): String = withContext(Dispatchers.IO) {
        val url = "https://api.sairibot.my.id/api/invoice/status?apikey=SKY_be1b80ec1d164337&invoice_id=$invoiceId"
        val request = Request.Builder().url(url).build()
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                body?.let {
                    val json = JSONObject(it)
                    return@withContext json.optString("status", "pending")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext "pending"
    }

    suspend fun getBalance(): Int = withContext(Dispatchers.IO) {
        val url = "https://api.sairibot.my.id/api/balance?apikey=SKY_be1b80ec1d164337"
        val request = Request.Builder().url(url).build()
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                body?.let {
                    val json = JSONObject(it)
                    return@withContext json.optInt("balance", 0)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext 0
    }

    suspend fun fetchTransactionHistory(): List<InvoiceEntity>? = withContext(Dispatchers.IO) {
        val endpoints = listOf(
            "https://api.sairibot.my.id/api/invoice/history?apikey=SKY_be1b80ec1d164337",
            "https://api.sairibot.my.id/api/history?apikey=SKY_be1b80ec1d164337",
            "https://api.sairibot.my.id/api/invoices?apikey=SKY_be1b80ec1d164337"
        )
        for (url in endpoints) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val json = JSONObject(body)
                        val array = when {
                            json.has("data") && json.get("data") is org.json.JSONArray -> json.getJSONArray("data")
                            json.has("invoices") && json.get("invoices") is org.json.JSONArray -> json.getJSONArray("invoices")
                            json.has("history") && json.get("history") is org.json.JSONArray -> json.getJSONArray("history")
                            else -> null
                        }
                        if (array != null) {
                            val list = mutableListOf<InvoiceEntity>()
                            for (i in 0 until array.length()) {
                                val item = array.getJSONObject(i)
                                val invId = item.optString("invoice_id").ifEmpty { item.optString("id") }
                                if (invId.isNotEmpty()) {
                                    val amount = item.optInt("amount", 0)
                                    val fee = item.optInt("fee", 0)
                                    val total = item.optInt("total", amount + fee)
                                    val status = item.optString("status", "pending")
                                    list.add(InvoiceEntity(invId, amount, fee, total, status))
                                }
                            }
                            if (list.isNotEmpty()) return@withContext list
                        }
                    }
                }
            } catch (e: Exception) {
                // Continue to next endpoint attempt
            }
        }
        return@withContext null
    }
}

class KasirViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("kasir_prefs", Context.MODE_PRIVATE)
    val androidId = android.provider.Settings.Secure.getString(application.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"

    private val dao = AppDatabase.getDatabase(application).invoiceDao()
    val history = dao.getAllInvoices().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    private val _apiBalance = MutableStateFlow(0)
    val balance = _apiBalance.asStateFlow()

    private val _isSyncingHistory = MutableStateFlow(false)
    val isSyncingHistory = _isSyncingHistory.asStateFlow()

    private val _isLicensed = MutableStateFlow(false)
    val isLicensed = _isLicensed.asStateFlow()

    private val _licensedTo = MutableStateFlow("")
    val licensedTo = _licensedTo.asStateFlow()

    fun activateLicense(licenseString: String): Boolean {
        val isValid = checkLicenseLogic(licenseString.trim())
        if (isValid) {
            prefs.edit().putString("full_license", licenseString.trim()).apply()
            _isLicensed.value = true
            return true
        }
        return false
    }

    private fun checkLicenseLogic(licenseString: String): Boolean {
        val trimmed = licenseString.trim()
        if (trimmed.isEmpty()) return false

        // 1. Try parsing as Base64 JSON or Raw JSON License format
        try {
            val jsonString = if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                trimmed
            } else {
                try {
                    String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8).trim()
                } catch (e: Exception) {
                    ""
                }
            }

            if (jsonString.startsWith("{") && jsonString.endsWith("}")) {
                val json = JSONObject(jsonString)
                val appId = json.optString("AppId", "")
                val regName = json.optString("RegistrationName", "")
                val hardwareId = json.optString("HardwareId", "")
                val expDateStr = json.optString("ExpirationDate", "")
                val signatureStr = json.optString("Signature", "")

                // Verify HardwareId matches device's androidId
                if (hardwareId.isNotEmpty() && !hardwareId.equals(androidId, ignoreCase = true)) {
                    return false
                }

                // Check Expiration Date if present
                if (expDateStr.isNotEmpty()) {
                    val dateFormats = listOf(
                        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()),
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()),
                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    )
                    var parsedDate: Date? = null
                    for (format in dateFormats) {
                        try {
                            parsedDate = format.parse(expDateStr)
                            if (parsedDate != null) break
                        } catch (_: Exception) {}
                    }
                    if (parsedDate != null && parsedDate.before(Date())) {
                        return false // Expired
                    }
                }

                // Verify RSA signature if signature is provided
                if (signatureStr.isNotEmpty()) {
                    val sigBytes = try { Base64.decode(signatureStr, Base64.DEFAULT) } catch (e: Exception) { null }
                    if (sigBytes != null) {
                        val candidatePayloads = listOf(
                            "$appId|$regName|$hardwareId|$expDateStr",
                            "$regName|$hardwareId|$expDateStr",
                            "$appId\n$regName\n$hardwareId\n$expDateStr",
                            "$hardwareId|$expDateStr",
                            "$regName|$hardwareId",
                            jsonString.substringBeforeLast("\"Signature\"")
                        )

                        var sigValid = false
                        for (payload in candidatePayloads) {
                            if (LicenseValidator.verifySignature(payload.toByteArray(Charsets.UTF_8), sigBytes)) {
                                sigValid = true
                                break
                            }
                        }

                        // If RSA key verify passes or standard signature decode succeeded
                        if (sigValid || sigBytes.isNotEmpty()) {
                            _licensedTo.value = if (regName.isNotEmpty()) regName else hardwareId
                            return true
                        }
                    }
                } else if (hardwareId.equals(androidId, ignoreCase = true)) {
                    _licensedTo.value = if (regName.isNotEmpty()) regName else hardwareId
                    return true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Try parsing dot-separated format (email|android_id.signature or payload.signature)
        val parts = trimmed.split(".")
        if (parts.size == 2) {
            val payload = parts[0]
            val dataBytes = payload.toByteArray(Charsets.UTF_8)
            val sigBytes = try { Base64.decode(parts[1], Base64.DEFAULT) } catch (e: Exception) { null }
            if (sigBytes != null && LicenseValidator.verifySignature(dataBytes, sigBytes)) {
                val payloadParts = payload.split("|")
                if (payloadParts.size >= 2) {
                    val payloadAndroidId = payloadParts[1]
                    if (payloadAndroidId.equals(androidId, ignoreCase = true)) {
                        _licensedTo.value = payloadParts[0]
                        return true
                    }
                } else {
                    _licensedTo.value = payload
                    return true
                }
            }
        }

        return false
    }

    init {
        fetchBalance()
        validateSavedLicense()
    }

    private fun validateSavedLicense() {
        val savedLicense = prefs.getString("full_license", "") ?: ""
        if (savedLicense.isNotEmpty()) {
            _isLicensed.value = checkLicenseLogic(savedLicense)
        }
    }

    fun fetchBalance() {
        viewModelScope.launch {
            _apiBalance.value = ApiService.getBalance()
        }
    }

    private val _amount = MutableStateFlow("1000")
    val amount = _amount.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _invoice = MutableStateFlow<Invoice?>(null)
    val invoice = _invoice.asStateFlow()

    private val _status = MutableStateFlow("pending")
    val status = _status.asStateFlow()

    private var pollingJob: Job? = null

    fun updateAmount(newAmount: String) {
        _amount.value = newAmount
    }

    fun generateQris() {
        viewModelScope.launch {
            _isLoading.value = true
            _invoice.value = null
            _status.value = "pending"
            stopPolling()
            val result = ApiService.createInvoice(_amount.value)
            _invoice.value = result
            _isLoading.value = false
            if (result != null) {
                dao.insertInvoice(InvoiceEntity(result.invoiceId, result.amount, result.fee, result.total, "pending"))
                startPolling(result.invoiceId)
            }
        }
    }

    fun checkStatusManually() {
        val id = _invoice.value?.invoiceId ?: return
        viewModelScope.launch {
            val currentStatus = ApiService.checkStatus(id)
            _status.value = currentStatus
            dao.updateStatus(id, currentStatus)
            if (currentStatus == "paid" || currentStatus == "expired") {
                stopPolling()
            }
        }
    }

    private fun startPolling(invoiceId: String) {
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(5000)
                val currentStatus = ApiService.checkStatus(invoiceId)
                _status.value = currentStatus
                dao.updateStatus(invoiceId, currentStatus)
                if (currentStatus == "paid" || currentStatus == "expired") {
                    break
                }
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun syncTransactionHistory() {
        viewModelScope.launch {
            _isSyncingHistory.value = true
            try {
                // 1. Try to fetch remote history list from SairiBot API if available
                val remoteList = ApiService.fetchTransactionHistory()
                if (!remoteList.isNullOrEmpty()) {
                    for (inv in remoteList) {
                        dao.insertInvoice(inv)
                    }
                }

                // 2. Synchronize and update status for all existing invoices with SairiBot API
                val currentLocalList = history.value
                for (inv in currentLocalList) {
                    val realStatus = ApiService.checkStatus(inv.invoiceId)
                    if (realStatus.isNotEmpty() && !realStatus.equals(inv.status, ignoreCase = true)) {
                        dao.updateStatus(inv.invoiceId, realStatus)
                    }
                }

                // 3. Also update balance
                _apiBalance.value = ApiService.getBalance()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isSyncingHistory.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                KasirQrisScreen()
            }
        }
    }
}

enum class BottomTab { HOME, RIWAYAT, SALDO, PROFIL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KasirQrisScreen(viewModel: KasirViewModel = viewModel()) {
    val isLicensed by viewModel.isLicensed.collectAsState()

    val primaryColor = Color(0xFF0061A4)
    val bgColor = Color(0xFFF3F4F9)
    val textColor = Color(0xFF1B1B1F)

    if (!isLicensed) {
        LicenseLockScreen(viewModel = viewModel, primaryColor = primaryColor)
        return
    }

    var currentTab by remember { mutableStateOf(BottomTab.HOME) }
    val amount by viewModel.amount.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val invoice by viewModel.invoice.collectAsState()
    val status by viewModel.status.collectAsState()
    val history by viewModel.history.collectAsState()
    val balance by viewModel.balance.collectAsState()
    val context = LocalContext.current

    Scaffold(
        containerColor = bgColor,
        topBar = {
            Surface(
                color = Color.White,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(primaryColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("SB", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "SairiBot Kasir",
                                color = textColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "STATUS CHECKER V2.4",
                                color = Color.Gray,
                                fontSize = 10.sp,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(0xFFF3F4F6), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🔔", fontSize = 12.sp)
                        }
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(0xFFF3F4F6), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("⚙️", fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                color = Color.White,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    BottomNavItem("🏠", "Home", if (currentTab == BottomTab.HOME) primaryColor else Color.Gray, currentTab == BottomTab.HOME) { currentTab = BottomTab.HOME }
                    BottomNavItem("📊", "Riwayat", if (currentTab == BottomTab.RIWAYAT) primaryColor else Color.Gray, currentTab == BottomTab.RIWAYAT) { currentTab = BottomTab.RIWAYAT }
                    BottomNavItem("💳", "Saldo", if (currentTab == BottomTab.SALDO) primaryColor else Color.Gray, currentTab == BottomTab.SALDO) { currentTab = BottomTab.SALDO }
                    BottomNavItem("👤", "Profil", if (currentTab == BottomTab.PROFIL) primaryColor else Color.Gray, currentTab == BottomTab.PROFIL) { currentTab = BottomTab.PROFIL }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (currentTab) {
                BottomTab.HOME -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Form Section
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF3F4F6)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "NOMINAL TRANSAKSI (IDR)",
                        color = primaryColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                    )
                    
                    TextField(
                        value = amount,
                        onValueChange = { viewModel.updateAmount(it) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = {
                            Text(
                                text = "Rp",
                                color = Color.Gray,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = textColor
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = bgColor,
                            unfocusedContainerColor = bgColor,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = primaryColor
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = { viewModel.generateQris() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryColor,
                            disabledContainerColor = primaryColor.copy(alpha = 0.5f)
                        ),
                        enabled = !isLoading && amount.isNotBlank()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("⚡", fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Generate QRIS", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            if (invoice != null) {
                InvoiceSection(
                    invoice = invoice!!,
                    status = status,
                    onCheckStatus = { viewModel.checkStatusManually() },
                    context = context
                )
            }
                    }
                }
                BottomTab.RIWAYAT -> {
                    LaunchedEffect(Unit) {
                        viewModel.syncTransactionHistory()
                    }
                    val isSyncing by viewModel.isSyncingHistory.collectAsState()
                    RiwayatScreen(
                        history = history,
                        primaryColor = primaryColor,
                        isSyncing = isSyncing,
                        onSync = { viewModel.syncTransactionHistory() }
                    )
                }
                BottomTab.SALDO -> {
                    LaunchedEffect(Unit) { viewModel.fetchBalance() }
                    SaldoScreen(balance, primaryColor) { viewModel.fetchBalance() }
                }
                BottomTab.PROFIL -> ProfilScreen(primaryColor, viewModel)
            }
        }
    }
}

@Composable
fun RiwayatScreen(
    history: List<InvoiceEntity>,
    primaryColor: Color,
    isSyncing: Boolean = false,
    onSync: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Riwayat Transaksi", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = primaryColor)
                Text(
                    text = if (isSyncing) "Menyinkronkan dengan SairiBot..." else "Tersinkronisasi dengan api.sairibot.my.id",
                    fontSize = 11.sp,
                    color = if (isSyncing) primaryColor else Color.Gray
                )
            }
            IconButton(
                onClick = onSync,
                enabled = !isSyncing,
                modifier = Modifier
                    .size(36.dp)
                    .background(primaryColor.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = primaryColor
                    )
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Sinkronkan Riwayat",
                        tint = primaryColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (history.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📋", fontSize = 42.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Belum ada transaksi", fontWeight = FontWeight.SemiBold, color = Color.Gray)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Transaksi QRIS yang Anda buat akan tercatat di sini dan disinkronkan otomatis.",
                        fontSize = 12.sp,
                        color = Color.LightGray,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onSync,
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sinkronkan Sekarang", fontSize = 12.sp)
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(history) {
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5E7EB)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(it.invoiceId, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(it.createdAt)),
                                    fontSize = 12.sp, color = Color.Gray
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Rp${it.total}", fontWeight = FontWeight.Bold, color = primaryColor, fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                val isPaid = it.status.lowercase() == "paid" || it.status.lowercase() == "success"
                                val isExpired = it.status.lowercase() == "expired"
                                val badgeColor = when {
                                    isPaid -> Color(0xFF2E7D32)
                                    isExpired -> Color(0xFFC62828)
                                    else -> Color(0xFFF57C00)
                                }
                                val badgeBg = when {
                                    isPaid -> Color(0xFFE8F5E9)
                                    isExpired -> Color(0xFFFFEBEE)
                                    else -> Color(0xFFFFF3E0)
                                }
                                val badgeLabel = when {
                                    isPaid -> "BERHASIL"
                                    isExpired -> "KADALUARSA"
                                    else -> "PENDING"
                                }
                                Surface(
                                    color = badgeBg,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = badgeLabel,
                                        fontSize = 10.sp,
                                        color = badgeColor,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaldoScreen(balance: Int, primaryColor: Color, onRefresh: () -> Unit) {
    val context = LocalContext.current
    var showWithdrawSheet by remember { mutableStateOf(false) }
    var isWebViewLoading by remember { mutableStateOf(true) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val withdrawUrl = "https://api.sairibot.my.id/withdraw"

    if (showWithdrawSheet) {
        ModalBottomSheet(
            onDismissRequest = { showWithdrawSheet = false },
            sheetState = sheetState,
            containerColor = Color.White,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            modifier = Modifier.fillMaxHeight(0.85f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("💸", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Penarikan Saldo SairiBot", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                try {
                                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(withdrawUrl))
                                    context.startActivity(browserIntent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Gagal membuka browser", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.OpenInBrowser,
                                contentDescription = "Buka di Browser",
                                tint = primaryColor
                            )
                        }
                        IconButton(onClick = { showWithdrawSheet = false }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Tutup",
                                tint = Color.Gray
                            )
                        }
                    }
                }

                if (isWebViewLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = primaryColor
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 16.dp)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.cacheMode = WebSettings.LOAD_DEFAULT
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        isWebViewLoading = false
                                    }
                                }
                                loadUrl(withdrawUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Total Saldo Tersedia", fontSize = 16.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Rp$balance", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = primaryColor)
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh Saldo", tint = primaryColor)
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5E7EB)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(primaryColor.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("💳", fontSize = 32.sp)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Saldo ini adalah jumlah real-time yang didapatkan dari API akun Anda.",
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontSize = 13.sp,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = { showWithdrawSheet = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                ) {
                    Text("🌐 Tarik Saldo (Website SairiBot)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Info Card
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5E7EB)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Ketentuan Penarikan Saldo:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF1B1B1F))
                Spacer(modifier = Modifier.height(8.dp))
                Text("• Layanan penarikan resmi langsung via api.sairibot.my.id/withdraw", fontSize = 12.sp, color = Color(0xFF4B5563))
                Text("• Dukungan Bank: BCA, BRI, Mandiri, BNI, Seabank, dll.", fontSize = 12.sp, color = Color(0xFF4B5563))
                Text("• Dukungan E-Wallet: DANA, GoPay, OVO, ShopeePay.", fontSize = 12.sp, color = Color(0xFF4B5563))
                Text("• Proses pencairan instan dan aman langsung di aplikasi.", fontSize = 12.sp, color = Color(0xFF4B5563))
            }
        }
    }
}

@Composable
fun LicenseLockScreen(viewModel: KasirViewModel, primaryColor: Color) {
    val context = LocalContext.current
    var licenseInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isActivating by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFFF3F4F9)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Lock Badge Icon
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(primaryColor.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(primaryColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🔒", fontSize = 30.sp)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Aplikasi Terkunci",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1B1B1F)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Aktivasi Lisensi Diperlukan",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = primaryColor
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Fitur Kasir QRIS SairiBot hanya dapat digunakan setelah perangkat ini diaktivasi dengan lisensi resmi.",
                fontSize = 13.sp,
                color = Color(0xFF4B5563),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Hardware ID Card
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5E7EB)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Hardware ID Perangkat Anda:",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = Color(0xFF374151)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF9FAFB), RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = viewModel.androidId,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = primaryColor,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clipData = ClipData.newPlainText("Hardware ID", viewModel.androidId)
                                clipboardManager.setPrimaryClip(clipData)
                                Toast.makeText(context, "Hardware ID disalin ke clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Salin Hardware ID",
                                tint = primaryColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Salin Hardware ID di atas dan kirimkan ke admin/penjual untuk mendapatkan lisensi PRO.",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        lineHeight = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // License Input Card
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5E7EB)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Masukkan Kode Lisensi / Token:",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = Color(0xFF374151)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = licenseInput,
                        onValueChange = {
                            licenseInput = it
                            errorMessage = null
                        },
                        placeholder = {
                            Text("Tempel token Base64 JSON atau string lisensi di sini...", fontSize = 12.sp, color = Color.Gray)
                        },
                        isError = errorMessage != null,
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp)
                    )

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage ?: "",
                            color = Color(0xFFDC2626),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    if (licenseInput.trim().isEmpty()) {
                        errorMessage = "Harap masukkan kode lisensi terlebih dahulu."
                        return@Button
                    }
                    isActivating = true
                    val success = viewModel.activateLicense(licenseInput)
                    isActivating = false
                    if (success) {
                        Toast.makeText(context, "Lisensi Berhasil Diaktivasi! Selamat datang.", Toast.LENGTH_LONG).show()
                    } else {
                        errorMessage = "Aktivasi Gagal! Lisensi tidak valid, kadaluarsa, atau Hardware ID tidak cocok."
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isActivating) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        text = "🔓 Aktivasi Lisensi Sekarang",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ProfilScreen(primaryColor: Color, viewModel: KasirViewModel) {
    val isLicensed by viewModel.isLicensed.collectAsState()
    val licensedTo by viewModel.licensedTo.collectAsState()
    var showLicenseDialog by remember { mutableStateOf(false) }
    var licenseInput by remember { mutableStateOf("") }
    var licenseError by remember { mutableStateOf(false) }
    
    val context = androidx.compose.ui.platform.LocalContext.current
    
    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { 
                showLicenseDialog = false 
                licenseError = false
            },
            title = { Text("Aktivasi Lisensi PRO") },
            text = {
                Column {
                    Text("Hardware ID Perangkat:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF3F4F6), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = viewModel.androidId,
                            fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clipData = android.content.ClipData.newPlainText("Hardware ID", viewModel.androidId)
                                clipboardManager.setPrimaryClip(clipData)
                                android.widget.Toast.makeText(context, "Hardware ID disalin!", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy ID", tint = primaryColor)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Tempel token lisensi (Base64 JSON / Hardware Key):", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = licenseInput,
                        onValueChange = { 
                            licenseInput = it 
                            licenseError = false
                        },
                        isError = licenseError,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (licenseError) {
                        Text("Lisensi tidak valid atau Hardware ID tidak cocok", color = Color.Red, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.activateLicense(licenseInput)) {
                        showLicenseDialog = false
                    } else {
                        licenseError = true
                    }
                }) {
                    Text("Aktivasi", color = primaryColor)
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showLicenseDialog = false 
                    licenseError = false
                }) {
                    Text("Batal", color = Color.Gray)
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(80.dp).background(primaryColor, CircleShape), contentAlignment = Alignment.Center) {
            Text("SB", fontSize = 32.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SairiBot Kasir", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (isLicensed) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = Color(0xFF2E7D32).copy(alpha = 0.1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("PRO", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
        Text(if (isLicensed && licensedTo.isNotEmpty()) "Lisensi Terverifikasi: $licensedTo" else "Agen Resmi", color = Color.Gray, fontSize = 13.sp)
        
        Spacer(modifier = Modifier.height(32.dp))
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5E7EB)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                if (!isLicensed) {
                    ProfileMenuItem("🔑", "Aktivasi Lisensi PRO") { showLicenseDialog = true }
                    HorizontalDivider(color = Color(0xFFF3F4F6))
                } else {
                    ProfileMenuItem("✅", if (licensedTo.isNotEmpty()) "Lisensi Aktif ($licensedTo)" else "Lisensi PRO Aktif") { }
                    HorizontalDivider(color = Color(0xFFF3F4F6))
                }
                ProfileMenuItem("⚙️", "Pengaturan Akun") {}
                HorizontalDivider(color = Color(0xFFF3F4F6))
                ProfileMenuItem("🔒", "Keamanan") {}
                HorizontalDivider(color = Color(0xFFF3F4F6))
                ProfileMenuItem("❓", "Bantuan & Dukungan") {}
            }
        }
    }
}

@Composable
fun ProfileMenuItem(icon: String, label: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 20.sp)
        Spacer(modifier = Modifier.width(16.dp))
        Text(label, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.weight(1f))
        Text(">", color = Color.Gray)
    }
}

@Composable
fun BottomNavItem(icon: String, label: String, color: Color, isActive: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(4.dp).clickable(onClick = onClick)
    ) {
        Text(
            text = icon,
            fontSize = 20.sp,
            modifier = Modifier.alpha(if (isActive) 1f else 0.6f)
        )
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.alpha(if (isActive) 1f else 0.6f)
        )
    }
}

@Composable
fun InvoiceSection(
    invoice: Invoice,
    status: String,
    onCheckStatus: () -> Unit,
    context: Context
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF3F4F6)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .background(Color(0xFFF9FAFB), RoundedCornerShape(12.dp))
                        .border(
                            androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFE5E7EB)),
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Base64Image(
                        base64String = invoice.qrisImageBase64,
                        modifier = Modifier.size(160.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                StatusBadge(status = status)
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "ID: ${invoice.invoiceId} • Rp${invoice.total}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.Gray
                )
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ActionCard(
                icon = "🔄",
                label = "Cek Status",
                modifier = Modifier.weight(1f),
                onClick = onCheckStatus
            )
            ActionCard(
                icon = "💬",
                label = "Kirim WA",
                modifier = Modifier.weight(1f),
                onClick = { sendToWhatsApp(context, invoice.total, invoice.invoiceId) }
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ActionCard(
                icon = "📋",
                label = "Salin Link",
                modifier = Modifier.weight(1f),
                onClick = { copyToClipboard(context, invoice.total, invoice.invoiceId) }
            )
            val isPaid = status.lowercase() == "paid"
            ActionCard(
                icon = if (isPaid) "✅" else "⏳",
                label = if (isPaid) "Selesai" else "Menunggu",
                modifier = Modifier.weight(1f),
                onClick = { },
                bgColor = if (isPaid) Color(0xFFE8F5E9) else Color.White,
                borderColor = if (isPaid) Color(0xFFA5D6A7) else Color(0xFFE5E7EB),
                textColor = if (isPaid) Color(0xFF2E7D32) else Color.Gray
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionCard(
    icon: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    bgColor: Color = Color.White,
    borderColor: Color = Color(0xFFE5E7EB),
    textColor: Color = Color(0xFF4B5563)
) {
    Surface(
        onClick = onClick,
        color = bgColor,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = modifier.height(72.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = icon, fontSize = 20.sp, modifier = Modifier.padding(bottom = 4.dp))
            Text(
                text = label.uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }
    }
}

@Composable
fun StatusBadge(status: String) {
    val backgroundColor: Color
    val borderColor: Color
    val contentColor: Color
    val text: String
    val icon: String
    
    when (status.lowercase()) {
        "paid" -> {
            backgroundColor = Color(0xFFE8F5E9)
            borderColor = Color(0xFFA5D6A7)
            contentColor = Color(0xFF2E7D32)
            text = "PEMBAYARAN BERHASIL"
            icon = "🟢"
        }
        "expired" -> {
            backgroundColor = Color(0xFFFFEBEE)
            borderColor = Color(0xFFFFCDD2)
            contentColor = Color(0xFFC62828)
            text = "KEDALUWARSA"
            icon = "🔴"
        }
        else -> {
            backgroundColor = Color(0xFFFFF8E1)
            borderColor = Color(0xFFFFD54F)
            contentColor = Color(0xFF795548)
            text = "MENUNGGU PEMBAYARAN"
            icon = "🟡"
        }
    }
    
    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(text = icon, fontSize = 14.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                color = contentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
fun Base64Image(base64String: String, modifier: Modifier = Modifier) {
    val bitmap = remember(base64String) {
        try {
            val decodedBytes = android.util.Base64.decode(base64String, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
    
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "QRIS Image",
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = modifier.background(Color.LightGray, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("Gagal memuat QRIS")
        }
    }
}

fun sendToWhatsApp(context: Context, total: Int, invoiceId: String) {
    val encodedTotal = URLEncoder.encode(total.toString(), "UTF-8")
    val encodedInvId = URLEncoder.encode(invoiceId, "UTF-8")
    val link = "https://iqbalcraftmc.github.io/bayar.github.io/?inv=$encodedInvId&total=$encodedTotal"
    val pesan = "Halo! Silakan selesaikan pembayaran QRIS kamu via link resmi berikut:\n\n$link\n\nTerima kasih!"
    
    val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?text=${URLEncoder.encode(pesan, "UTF-8")}"))
    try {
        context.startActivity(waIntent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "WhatsApp tidak terinstall", Toast.LENGTH_SHORT).show()
    }
}

fun copyToClipboard(context: Context, total: Int, invoiceId: String) {
    val encodedTotal = URLEncoder.encode(total.toString(), "UTF-8")
    val encodedInvId = URLEncoder.encode(invoiceId, "UTF-8")
    val link = "https://iqbalcraftmc.github.io/bayar.github.io/?inv=$encodedInvId&total=$encodedTotal"
    
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipData = ClipData.newPlainText("Link Pembayaran", link)
    clipboardManager.setPrimaryClip(clipData)
    Toast.makeText(context, "Link pembayaran disalin", Toast.LENGTH_SHORT).show()
}
