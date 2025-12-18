package com.example.sigaapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.compose.ui.res.painterResource
import com.example.sigaapp.service.VoiceService
import com.example.sigaapp.ui.theme.*
import com.example.sigaapp.ui.viewmodel.CardSize
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
// Required for clickable modifier
import com.example.sigaapp.data.local.SessionManager
import java.util.Locale

enum class UserRole { ADMINISTRADOR, OPERADOR, CAJERO }

// Data class para mensajes del chat
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    navController: NavController, 
    userRole: UserRole,
    permissions: List<String> = emptyList(),
    chatRepository: com.example.sigaapp.data.repository.ChatRepository,
    onLogout: () -> Unit,
    cardSize: CardSize = CardSize.MEDIUM,
    globalViewModel: com.example.sigaapp.ui.viewmodel.GlobalViewModel
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showBottomSheet by remember { mutableStateOf(false) }

    var chatMessages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var userInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    
    val sessionManager = remember { SessionManager(context) }
    var persistedRole by remember { mutableStateOf(sessionManager.getUserRole()) }
    var companyName by remember { mutableStateOf(sessionManager.getCompanyName()) }

    LaunchedEffect(Unit) {
        persistedRole = sessionManager.getUserRole()
        globalViewModel.loadLocales()
        companyName = sessionManager.getCompanyName()
    }

    val effectiveUserRole = remember(userRole, persistedRole) {
        fun mapRole(raw: String?): UserRole? {
            val value = raw?.uppercase(Locale.getDefault()) ?: return null
            return when {
                value.contains("ADMIN") -> UserRole.ADMINISTRADOR
                value.contains("CAJERO") -> UserRole.CAJERO
                value.contains("OPERADOR") -> UserRole.OPERADOR
                else -> null
            }
        }
        mapRole(persistedRole) ?: userRole
    }

    // Global State
    val locales by globalViewModel.locales.collectAsState()
    val selectedLocal by globalViewModel.selectedLocal.collectAsState()
    val dollarState by globalViewModel.dollarIndicator.collectAsState()
    val ufState by globalViewModel.ufIndicator.collectAsState()
    val utmState by globalViewModel.utmIndicator.collectAsState()
    val salesState by globalViewModel.salesMetrics.collectAsState()
    val inventoryState by globalViewModel.inventoryMetrics.collectAsState()
    var expandedLocalMenu by remember { mutableStateOf(false) }
    
    // Servicios de voz
    var isVoiceInputEnabled by remember { mutableStateOf(false) }
    var isVoiceOutputEnabled by remember { mutableStateOf(false) }
    val tts = VoiceService.rememberTextToSpeech()
    
    // Launcher para reconocimiento de voz
    val speechRecognizerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.getOrNull(0) ?: ""
            if (spokenText.isNotEmpty()) {
                userInput = spokenText
                isVoiceOutputEnabled = true // Activar respuesta de voz al usar micrófono
            }
        }
    }
    
    fun startVoiceInput() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(context)) {
            errorMessage = "El reconocimiento de voz no está disponible en este dispositivo"
            return
        }
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES") // Español
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla ahora...")
        }
        speechRecognizerLauncher.launch(intent)
    }

    // Mensaje inicial cuando se abre el chat
    LaunchedEffect(showBottomSheet) {
        if (showBottomSheet && chatMessages.isEmpty()) {
            chatMessages = listOf(
                ChatMessage(
                    text = "Hola, soy SIGA, tu asistente. ¿En qué puedo ayudarte hoy?",
                    isUser = false
                )
            )
        }
        // Detener voz al cerrar el chat
        if (!showBottomSheet) {
            VoiceService.stopSpeaking(tts)
        }
    }

    // Scroll automático al final cuando hay nuevos mensajes
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    fun sendMessage() {
        if (userInput.isBlank() || isLoading) return

        val userMessage = userInput.trim()
        userInput = ""
        errorMessage = null
        keyboardController?.hide() // Ocultar teclado al enviar

        // Agregar mensaje del usuario
        chatMessages = chatMessages + ChatMessage(text = userMessage, isUser = true)
        isLoading = true

        scope.launch {
            val result = chatRepository.sendMessage(userMessage)
            isLoading = false

            result.fold(
                onSuccess = { response ->
                    chatMessages = chatMessages + ChatMessage(text = response, isUser = false)
                    // Reproducir respuesta por voz si está habilitado
                    if (isVoiceOutputEnabled) {
                        VoiceService.speak(tts, response)
                    }
                },
                onFailure = { exception ->
                    errorMessage = exception.message ?: "Error al comunicarse con el asistente"
                    chatMessages = chatMessages + ChatMessage(
                        text = "Lo siento, hubo un error al procesar tu mensaje. (${exception.message})",
                        isUser = false
                    )
                }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Image(
                        painter = painterResource(id = R.drawable.logo_siga),
                        contentDescription = "SIGA v2",
                        modifier = Modifier
                            .height(56.dp) 
                            .padding(vertical = 4.dp), 
                        contentScale = ContentScale.Fit
                    )
                },
                actions = {
                    Surface(
                        color = ModernBlue.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.padding(end = 8.dp).clickable { expandedLocalMenu = true }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Storefront, contentDescription = null, tint = ModernBlue, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = selectedLocal?.nombre ?: "Central",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = ModernBlue
                            )
                        }
                    }
                    // Logout Button
                    IconButton(onClick = onLogout) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = "Cerrar Sesión",
                            tint = AlertRed
                        )
                    }

                        // Local Selector Dropdown
                        DropdownMenu(
                            expanded = expandedLocalMenu,
                            onDismissRequest = { expandedLocalMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Todas las Tiendas") },
                                onClick = {
                                    globalViewModel.selectLocal(null)
                                    expandedLocalMenu = false
                                }
                            )
                            locales.forEach { local ->
                                DropdownMenuItem(
                                    text = { Text(local.nombre) },
                                    onClick = {
                                        globalViewModel.selectLocal(local)
                                        expandedLocalMenu = false
                                    }
                                )
                            }
                        }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceLight,
                    titleContentColor = ModernBlue
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    showBottomSheet = true
                },
                containerColor = Color.White, 
                contentColor = ModernBlue,
                shape = RoundedCornerShape(50),
                modifier = Modifier.size(72.dp) 
            ) {
                 Image(
                    painter = painterResource(id = R.drawable.logo_asistente),
                    contentDescription = "Asistente SIGA",
                    modifier = Modifier.size(48.dp),
                    contentScale = ContentScale.Fit
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center, 
        containerColor = Background
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 100.dp), // Space for FAB/BottomNav
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Background),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. HERO SECTION (Ventas Netas)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = ModernBlue),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(
                            text = "Ventas Netas Hoy",
                            style = MaterialTheme.typography.labelLarge,
                            color = White.copy(alpha = 0.8f)
                        )
                        Text(
                            text = if (salesState.isLoading) "..." else "$ ${String.format(Locale.forLanguageTag("es-CL"), "%,d", salesState.totalSalesToday)}",
                            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                            color = White
                        )
                        Row(
                            modifier = Modifier.padding(top = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            Column {
                                Text(
                                    text = if (salesState.isLoading) "-" else salesState.transactionCount.toString(),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), 
                                    color = White
                                )
                                Text("Ventas", style = MaterialTheme.typography.bodySmall, color = White.copy(alpha = 0.7f))
                            }
                            Column {
                                Text(
                                    text = if (salesState.isLoading) "-" else "$ ${String.format(Locale.forLanguageTag("es-CL"), "%,d", salesState.averageTicket)}",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), 
                                    color = White
                                )
                                Text("Ticket Prom.", style = MaterialTheme.typography.bodySmall, color = White.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
            }

            // 2. INDICADORES (Horizontal Scroll)
            item {
                Column {
                    PaddingValues(horizontal = 16.dp).let { px ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(px),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Indicadores Económicos", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
                            Surface(
                                color = Background,
                                shape = RoundedCornerShape(4.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DisabledGray.copy(alpha = 0.3f))
                            ) {
                                Text(
                                    text = "EN VIVO",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldOps
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item { IndicatorCard(title = "Dólar", state = dollarState) }
                        item { IndicatorCard(title = "UF", state = ufState) }
                        item { IndicatorCard(title = "UTM", state = utmState) }
                    }
                }
            }

            // 3. OPERACIONES (Live Tiles Grid)
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        "Operaciones",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Col 1: Inventario
                        val modWeight = Modifier.weight(1f)
                        LiveMetricTile(
                            title = "Inventario",
                            value = if (inventoryState.isLoading) "..." else "${inventoryState.totalItems}",
                            trend = if (inventoryState.lowStockCount > 0) "${inventoryState.lowStockCount} quiebres" else "Stock OK",
                            trendIcon = if (inventoryState.lowStockCount > 0) Icons.AutoMirrored.Filled.TrendingDown else Icons.Default.CheckCircle,
                            isTrendPositive = inventoryState.lowStockCount == 0,
                            icon = Icons.Default.Inventory,
                            iconColor = ModernBlue,
                            onClick = { navController.navigate("inventory") },
                            modifier = modWeight
                        )
                        
                        // Col 2: Ventas
                         LiveMetricTile(
                            title = "Ventas",
                            value = if (salesState.isLoading) "..." else "${salesState.transactionCount}",
                            trend = "Ver detalle",
                            trendIcon = Icons.AutoMirrored.Filled.ArrowForward,
                            isTrendPositive = true,
                            icon = Icons.AutoMirrored.Filled.TrendingUp,
                            iconColor = AccentCyan,
                            onClick = { navController.navigate("sales") },
                            modifier = modWeight
                        )
                    }
                }
            }

            // 4. ACCESOS RÁPIDOS
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                     Text(
                        "Accesos Rápidos",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                     Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                         DashboardTile(
                            title = "Gastos",
                            icon = Icons.AutoMirrored.Filled.TrendingDown,
                            color = AlertRed,
                            enabled = true,
                            onClick = { 
                                showBottomSheet = true 
                                userInput = "Mostrar gastos del mes"
                                sendMessage()
                            },
                             modifier = Modifier.weight(1f)
                         )
                         DashboardTile(
                            title = "Soporte",
                            icon = Icons.Default.Support,
                            color = AccentCyan,
                            enabled = true,
                            onClick = { showBottomSheet = true },
                            modifier = Modifier.weight(1f)
                         )
                         DashboardTile(
                            title = "Ajustes",
                            icon = Icons.Default.Settings,
                            color = PrimaryDark,
                            enabled = effectiveUserRole == UserRole.ADMINISTRADOR,
                            onClick = { navController.navigate("settings") },
                            modifier = Modifier.weight(1f)
                         )
                     }
                }
            }
        }

        // Bottom Sheet Logic
        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { showBottomSheet = false },
                sheetState = sheetState,
                containerColor = SurfaceLight,
                dragHandle = {
                     Box(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .width(40.dp)
                            .height(4.dp)
                            .background(color = DisabledGray, shape = RoundedCornerShape(2.dp))
                    )
                }
            ) {
                 DashboardChatContent(
                    chatMessages = chatMessages,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    listState = listState,
                    userInput = userInput,
                    onUserInputChange = { 
                        userInput = it 
                        isVoiceOutputEnabled = false // Desactivar voz si el usuario escribe
                    },
                    onSendMessage = { sendMessage() },
                    isVoiceInputEnabled = isVoiceInputEnabled,
                    onVoiceInputToggle = { isVoiceInputEnabled = it },
                    startVoiceInput = { startVoiceInput() },
                    onClose = { showBottomSheet = false }
                 )
            }
        }
    }
}

@Composable
fun DashboardChatContent(
    chatMessages: List<ChatMessage>,
    isLoading: Boolean,
    errorMessage: String?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    userInput: String,
    onUserInputChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    isVoiceInputEnabled: Boolean,
    onVoiceInputToggle: (Boolean) -> Unit,
    startVoiceInput: () -> Unit,
    onClose: () -> Unit
) {
    // Auto-scroll when messages change OR loading starts
    LaunchedEffect(chatMessages.size, isLoading) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

     Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .heightIn(min = 400.dp, max = 800.dp) 
    ) {
        // Reusing Header
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Asistente SIGA", style = MaterialTheme.typography.titleMedium, color = PrimaryDark)
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, null) }
        }
        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
        
        // List
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
             items(chatMessages) { ChatBubble(message = it) }
             
             if (isLoading) {
                 item {
                     Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                     ) {
                         CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentCyan)
                         Spacer(modifier = Modifier.width(8.dp))
                         Text("Escribiendo...", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                     }
                 }
             }
        }
        
        // Input Area
         Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), 
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
             // Microphone Button
             IconButton(
                onClick = startVoiceInput,
                modifier = Modifier
                    .background(SurfaceLight, RoundedCornerShape(50))
                    .border(1.dp, DisabledGray.copy(alpha=0.5f), RoundedCornerShape(50))
                    .size(48.dp),
                enabled = !isLoading
             ) {
                 Icon(Icons.Default.Mic, contentDescription = "Hablar", tint = ModernBlue)
             }

             OutlinedTextField(
                value = userInput,
                onValueChange = onUserInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Escribe una instrucción...") },
                shape = RoundedCornerShape(20.dp),
                enabled = !isLoading
            )
            IconButton(
                onClick = onSendMessage,
                modifier = Modifier.background(if (isLoading) DisabledGray else AccentCyan, RoundedCornerShape(50)).size(48.dp),
                enabled = !isLoading
            ) {
                if (isLoading) {
                     CircularProgressIndicator(modifier = Modifier.size(24.dp), color = White)
                } else {
                     Icon(Icons.AutoMirrored.Filled.Send, null, tint = White)
                }
            }
        }
    }
}

@Composable
fun IndicatorCard(title: String, state: com.example.sigaapp.ui.viewmodel.IndicatorUiState) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceLight),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.width(150.dp).height(100.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            
            if (state.value != null) {
                Text(
                     text = "${"$"}${String.format(Locale.forLanguageTag("es-CL"), "%,.0f", state.value)}",
                     style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                     color = TextPrimary
                )
            } else if (state.isLoading) {
                 CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AccentCyan)
            } else {
                 Text("N/A", style = MaterialTheme.typography.headlineSmall, color = DisabledGray)
            }
            
            Text(
                text = if (state.date.isNotEmpty()) state.date.take(10) else "Actualizado",
                style = MaterialTheme.typography.labelSmall, 
                color = if (state.error == null) EmeraldOps else AlertRed
            )
        }
    }
}
